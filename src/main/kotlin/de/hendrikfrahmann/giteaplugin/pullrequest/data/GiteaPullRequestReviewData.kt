package de.hendrikfrahmann.giteaplugin.pullrequest.data

import com.intellij.diff.util.Side
import com.intellij.openapi.diagnostic.logger
import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaReviewDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.api.request.GiteaCreateReviewRequest
import de.hendrikfrahmann.giteaplugin.api.request.GiteaNewReviewComment
import de.hendrikfrahmann.giteaplugin.api.request.GiteaReviewEvent
import de.hendrikfrahmann.giteaplugin.api.request.createReview
import de.hendrikfrahmann.giteaplugin.api.request.deleteComment
import de.hendrikfrahmann.giteaplugin.api.request.deleteReview
import de.hendrikfrahmann.giteaplugin.api.request.editComment
import de.hendrikfrahmann.giteaplugin.api.request.loadPullRequestReviews
import de.hendrikfrahmann.giteaplugin.api.request.loadReviewComments
import de.hendrikfrahmann.giteaplugin.api.request.replyToReviewComment
import de.hendrikfrahmann.giteaplugin.api.request.setReviewThreadResolved
import de.hendrikfrahmann.giteaplugin.api.request.submitReview
import de.hendrikfrahmann.giteaplugin.util.childScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Date

private val LOG = logger<GiteaPullRequestReviewData>()

/** Reviews and inline threads of one PR, plus the current user's pending review. */
data class GiteaPullRequestReviewState(
  val reviews: List<GiteaReviewDTO>,
  val threads: List<GiteaReviewThread>,
  /** The current user's unsubmitted review on the server (e.g. started in the web UI); its comments are in [threads] with `isPending`. */
  val pendingReview: GiteaReviewDTO?,
  /** Number of the plugin's local draft comments (also in [threads], pending, with negative ids). */
  val draftCount: Int = 0,
) {
  /** Something to submit or discard: a server pending review or local drafts. */
  val hasPendingReview: Boolean get() = pendingReview != null || draftCount > 0
}

/**
 * Loads and changes the review data of PR [number]. Every change reloads the state, so the UI shows what the server has
 * (Gitea assigns ids, joins pending comments, …). Errors of changes are rethrown to the caller (the UI shows them).
 *
 * Review comments ("Start review") are kept as local drafts until the review is submitted, then sent in one
 * `POST …/reviews`: Gitea (28.1) adds a "reviewed" timeline entry for every such call, also for `event=PENDING`, and its
 * web UI shows all of the review's comments under each entry – adding drafts one by one on the server repeated them.
 */
class GiteaPullRequestReviewData(
  parentCs: CoroutineScope,
  private val api: GiteaApi,
  private val repository: GiteaRepositoryPath,
  private val number: Long,
  /** `null` without a login (anonymous connection). */
  private val currentUserLogin: String?,
  /** Author shown on drafts (avatar); falls back to [currentUserLogin]. */
  currentUser: GiteaUserDTO? = null,
) {
  private val cs = parentCs.childScope()
  private val mutex = Mutex()
  private val draftAuthor = currentUser ?: currentUserLogin?.let { GiteaUserDTO(0, it) }

  /** Last loaded server state, without drafts. */
  private var serverState: Result<GiteaPullRequestReviewState>? = null
  private var drafts: List<Draft> = emptyList()
  private var nextDraftId = -1L

  private val _state = MutableStateFlow<Result<GiteaPullRequestReviewState>?>(null)
  val state: StateFlow<Result<GiteaPullRequestReviewState>?> = _state.asStateFlow()

  init {
    reload()
  }

  fun reload() {
    cs.launch { load() }
  }

  /**
   * New inline comment on [path] at 1-based [line] of [side] in the diff of [commitSha]. [pending] keeps it as a local
   * draft of the user's review (sent with [submitReview]), otherwise it's posted right away as a single comment.
   */
  suspend fun addComment(path: String, side: Side, line: Int, commitSha: String, body: String, pending: Boolean) {
    if (pending) {
      changeDrafts { it + Draft(nextDraftId--, path, side, line, commitSha, body, Date()) }
      return
    }
    val comment = newComment(path, side, line, body)
    change { api.createReview(repository, number, GiteaCreateReviewRequest(GiteaReviewEvent.COMMENT, "", commitSha, listOf(comment))) }
  }

  suspend fun reply(thread: GiteaReviewThread, body: String) = change { api.replyToReviewComment(repository, number, thread.id, body) }

  suspend fun editComment(commentId: Long, body: String) {
    if (isDraft(commentId)) {
      changeDrafts { drafts ->
        drafts.map {
          if (it.id == commentId) {
            it.copy(body = body, updatedAt = Date())
          }
          else {
            it
          }
        }
      }
      return
    }
    change { api.editComment(repository, commentId, body) }
  }

  suspend fun deleteComment(commentId: Long) {
    if (isDraft(commentId)) {
      changeDrafts { drafts -> drafts.filter { it.id != commentId } }
      return
    }
    change { api.deleteComment(repository, commentId) }
  }

  suspend fun setResolved(thread: GiteaReviewThread, resolved: Boolean) = change { api.setReviewThreadResolved(repository, thread.id, resolved) }

  /**
   * Submits the review in one request: the drafts are created with it (Gitea adds them to a server pending review first,
   * so that one is submitted with them); without drafts the server pending review is submitted, or – without one – a
   * review with just a body is created (e.g. a plain approval). The drafts are kept if the request fails.
   */
  suspend fun submitReview(event: GiteaReviewEvent, body: String, headSha: String?) = change {
    val submitted = mutex.withLock { drafts }
    val pending = serverState?.getOrNull()?.pendingReview
    when {
      submitted.isNotEmpty() -> {
        // positions count in the version they were made on; drafts of an older head go with the newest one's commit
        val commitSha = submitted.last().commitSha
        val comments = submitted.map { newComment(it.path, it.side, it.line, it.body) }
        api.createReview(repository, number, GiteaCreateReviewRequest(event, body, commitSha, comments))
      }
      pending != null -> api.submitReview(repository, number, pending.id, event, body)
      else -> api.createReview(repository, number, GiteaCreateReviewRequest(event, body, headSha))
    }
    mutex.withLock {
      val ids = submitted.mapTo(HashSet()) { it.id }
      drafts = drafts.filter { it.id !in ids }
    }
  }

  suspend fun discardPendingReview() {
    changeDrafts { emptyList() }
    change { serverState?.getOrNull()?.pendingReview?.let { api.deleteReview(repository, number, it.id) } }
  }

  private suspend fun load() = mutex.withLock {
    serverState = try {
      Result.success(loadState())
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      LOG.info("Could not load the reviews of #$number", e)
      if (serverState?.isSuccess == true) {
        serverState
      }
      else {
        Result.failure(e)
      }
    }
    publish()
  }

  /** Server state plus the drafts as pending threads; call with [mutex] held. */
  private fun publish() {
    val server = serverState ?: return
    _state.value = server.map { state ->
      if (drafts.isEmpty()) {
        state
      }
      else {
        val reviewId = state.pendingReview?.id ?: DRAFT_REVIEW_ID
        val draftThreads = drafts.map { draft ->
          GiteaReviewThread(path = draft.path, side = draft.side, line = draft.line, commitSha = draft.commitSha, reviewId = reviewId,
                            comments = listOf(GiteaReviewComment(draft.id, draftAuthor, draft.body, draft.createdAt, draft.updatedAt, reviewId, true)),
                            resolver = null, reviewCommitSha = draft.commitSha)
        }
        val threads = (state.threads + draftThreads).sortedWith(compareBy({ it.path }, { it.line }, { it.root.createdAt }))
        state.copy(threads = threads, draftCount = drafts.size)
      }
    }
  }

  private suspend fun changeDrafts(transform: (List<Draft>) -> List<Draft>) = mutex.withLock {
    drafts = transform(drafts)
    publish()
  }

  private suspend fun loadState(): GiteaPullRequestReviewState = coroutineScope {
    val reviews = api.loadPullRequestReviews(repository, number)
    val withComments = reviews.filter { it.state != "REQUEST_REVIEW" && (it.commentsCount > 0 || it.state == "PENDING") }
    val comments = withComments.map { review -> async { review.id to api.loadReviewComments(repository, number, review.id) } }.awaitAll().toMap()
    val pending = currentUserLogin?.let { login -> reviews.firstOrNull { it.state == "PENDING" && it.user?.login.equals(login, ignoreCase = true) } }
    GiteaPullRequestReviewState(reviews, GiteaReviewThread.build(reviews, comments), pending)
  }

  private fun isDraft(commentId: Long) = commentId < 0

  private fun newComment(path: String, side: Side, line: Int, body: String) = when (side) {
    Side.LEFT -> GiteaNewReviewComment(path, body, oldPosition = line)
    Side.RIGHT -> GiteaNewReviewComment(path, body, newPosition = line)
  }

  /**
   * Runs in this data's scope, not the caller's: a comment field whose inlay or editor is rebuilt while the request runs
   * (diff recreated, PR refreshed) would otherwise cancel the reload after the server already made the change – the
   * comment then never showed up and was submitted again.
   */
  private suspend fun change(block: suspend () -> Unit) {
    cs.async {
      try {
        block()
      }
      finally {
        withContext(NonCancellable) { load() }
      }
    }.await()
  }

  /** A review comment kept locally until the review is submitted; [id] is negative to tell it from server comments. */
  private data class Draft(val id: Long, val path: String, val side: Side, val line: Int, val commitSha: String, val body: String,
                           val createdAt: Date, val updatedAt: Date? = null)

  private companion object {
    /** Review id of draft threads while the server has no pending review of the user. */
    const val DRAFT_REVIEW_ID = -1L
  }
}
