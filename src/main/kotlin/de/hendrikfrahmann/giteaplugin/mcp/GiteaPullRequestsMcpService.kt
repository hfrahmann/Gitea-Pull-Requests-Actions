package de.hendrikfrahmann.giteaplugin.mcp

import com.intellij.diff.util.Side
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaIssueDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaPullRequestDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaReviewDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaTimelineEventDTO
import de.hendrikfrahmann.giteaplugin.api.request.GiteaCreatePullRequestRequest
import de.hendrikfrahmann.giteaplugin.api.request.GiteaCreateReviewRequest
import de.hendrikfrahmann.giteaplugin.api.request.GiteaEditPullRequestRequest
import de.hendrikfrahmann.giteaplugin.api.request.GiteaMergePullRequestRequest
import de.hendrikfrahmann.giteaplugin.api.request.GiteaMergeStyle
import de.hendrikfrahmann.giteaplugin.api.request.GiteaNewReviewComment
import de.hendrikfrahmann.giteaplugin.api.request.GiteaPullRequestIssueQuery
import de.hendrikfrahmann.giteaplugin.api.request.GiteaPullRequestListQuery
import de.hendrikfrahmann.giteaplugin.api.request.GiteaPushData
import de.hendrikfrahmann.giteaplugin.api.request.GiteaReactionTarget
import de.hendrikfrahmann.giteaplugin.api.request.GiteaReviewEvent
import de.hendrikfrahmann.giteaplugin.api.request.addReaction
import de.hendrikfrahmann.giteaplugin.api.request.createIssueComment
import de.hendrikfrahmann.giteaplugin.api.request.createPullRequest
import de.hendrikfrahmann.giteaplugin.api.request.createReview
import de.hendrikfrahmann.giteaplugin.api.request.editPullRequest
import de.hendrikfrahmann.giteaplugin.api.request.getPullRequest
import de.hendrikfrahmann.giteaplugin.api.request.getRepository
import de.hendrikfrahmann.giteaplugin.api.request.loadAllowedReactions
import de.hendrikfrahmann.giteaplugin.api.request.loadCombinedStatus
import de.hendrikfrahmann.giteaplugin.api.request.loadPullRequestCommits
import de.hendrikfrahmann.giteaplugin.api.request.loadPullRequestDiff
import de.hendrikfrahmann.giteaplugin.api.request.loadPullRequestFiles
import de.hendrikfrahmann.giteaplugin.api.request.loadPullRequestIssuesPage
import de.hendrikfrahmann.giteaplugin.api.request.loadPullRequestReviews
import de.hendrikfrahmann.giteaplugin.api.request.loadPullRequestsPage
import de.hendrikfrahmann.giteaplugin.api.request.loadReactions
import de.hendrikfrahmann.giteaplugin.api.request.loadReviewComments
import de.hendrikfrahmann.giteaplugin.api.request.loadTimeline
import de.hendrikfrahmann.giteaplugin.api.request.mergePullRequest
import de.hendrikfrahmann.giteaplugin.api.request.removeReaction
import de.hendrikfrahmann.giteaplugin.api.request.replyToReviewComment
import de.hendrikfrahmann.giteaplugin.api.request.searchReviewRequestedPullRequestsPage
import de.hendrikfrahmann.giteaplugin.api.request.setReviewThreadResolved
import de.hendrikfrahmann.giteaplugin.mcp.GiteaMcpJson.checkNumber
import de.hendrikfrahmann.giteaplugin.mcp.GiteaMcpJson.checkPage
import de.hendrikfrahmann.giteaplugin.mcp.GiteaMcpJson.checkText
import de.hendrikfrahmann.giteaplugin.mcp.GiteaMcpJson.login
import de.hendrikfrahmann.giteaplugin.mcp.GiteaMcpJson.obj
import de.hendrikfrahmann.giteaplugin.mcp.GiteaMcpJson.subject
import de.hendrikfrahmann.giteaplugin.mcp.GiteaMcpJson.write
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestCreateDefaults
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestReviewData
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestReviewState
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaReviewThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/** An inline comment of a review: 1-based [line] in the new file version ([Side.RIGHT]) or the old one ([Side.LEFT]). */
data class GiteaMcpReviewComment(val path: String, val line: Int, val side: String = "RIGHT", val body: String)

/**
 * The logic of the "Gitea Pull Requests" MCP tools, without any MCP dependency: arguments in, compact JSON out,
 * [GiteaMcpException] for anything the assistant should be told. Every call checks the settings first ([GiteaMcpGates]).
 */
@Suppress("TooManyFunctions") // one function per MCP tool
class GiteaPullRequestsMcpService(private val env: GiteaMcpEnvironment) {

  // ---- read ----

  suspend fun listRepositories(): String {
    GiteaMcpGates.checkRead(env)
    val repositories = env.repositories().map {
      obj("repository" to it.repository.toString(), "server" to it.server, "remote" to it.remoteName, "remoteUrl" to it.remoteUrl,
          "root" to it.root, "connected" to it.connected, "account" to it.account, "anonymous" to (it.account == null))
    }
    return write(obj("repositories" to repositories))
  }

  @Suppress("LongParameterList") // the tool's filters
  suspend fun listPullRequests(repository: String?, state: String, search: String?, author: String?, labels: List<String>,
                               reviewRequested: Boolean, page: Int, limit: Int): String {
    val target = readTarget(repository)
    val prState = state.lowercase()
    if (prState !in PR_STATES) {
      throw GiteaMcpException("`state` must be one of ${PR_STATES.joinToString()}, got \"$state\".")
    }
    checkPage(page, limit)
    val text = search?.trim()?.takeIf { it.isNotEmpty() }
    val poster = author?.trim()?.takeIf { it.isNotEmpty() }
    val labelNames = labels.map { it.trim() }.filter { it.isNotEmpty() }
    val api = target.api
    val path = target.repository
    val items: List<Map<String, Any?>> = when {
      reviewRequested -> {
        if (target.isAnonymous) {
          throw GiteaMcpException("`reviewRequested` needs a Gitea account – $path is read anonymously.")
        }
        val query = GiteaPullRequestIssueQuery(prState, text, labelNames, createdBy = poster)
        api.searchReviewRequestedPullRequestsPage(path.owner, query, page, limit).body
          .filter { it.repository?.fullName.equals(path.toString(), ignoreCase = true) }
          .map(::issueSummary)
      }
      text != null || labelNames.isNotEmpty() ->
        api.loadPullRequestIssuesPage(path, GiteaPullRequestIssueQuery(prState, text, labelNames, createdBy = poster), page, limit).body
          .map(::issueSummary)
      else -> api.loadPullRequestsPage(path, GiteaPullRequestListQuery(prState, poster), page, limit).body.map(::pullRequestSummary)
    }
    return write(obj("repository" to path.toString(), "page" to page, "pullRequests" to items, "mayHaveMore" to (items.size >= limit)))
  }

  suspend fun getPullRequest(repository: String?, number: Long): String {
    checkNumber(number)
    val target = readTarget(repository)
    val (pr, reviews) = coroutineScope {
      val reviews = async { target.api.loadPullRequestReviews(target.repository, number) }
      target.api.getPullRequest(target.repository, number) to reviews.await()
    }
    return write(pullRequestDetails(pr, reviews))
  }

  suspend fun getPullRequestFiles(repository: String?, number: Long, path: String?): String {
    checkNumber(number)
    val target = readTarget(repository)
    val file = path?.trim()?.trimStart('/')?.takeIf { it.isNotEmpty() }
    if (file != null) {
      val diff = fileDiff(target.api.loadPullRequestDiff(target.repository, number), file)
                 ?: throw GiteaMcpException("$file is not changed in pull request #$number.")
      val truncated = diff.length > MAX_DIFF_CHARS
      return write(obj("number" to number, "path" to file, "diff" to diff.take(MAX_DIFF_CHARS), "truncated" to truncated.takeIf { it }))
    }
    val files = target.api.loadPullRequestFiles(target.repository, number).map {
      obj("path" to it.filename, "previousPath" to it.previousFilename, "status" to it.status, "additions" to it.additions,
          "deletions" to it.deletions)
    }
    return write(obj("number" to number, "files" to files))
  }

  suspend fun getPullRequestCommits(repository: String?, number: Long): String {
    checkNumber(number)
    val target = readTarget(repository)
    // Gitea sends them newest first; oldest first reads like the branch
    val commits = target.api.loadPullRequestCommits(target.repository, number).reversed().map {
      obj("sha" to it.sha, "title" to subject(it.commit?.message), "author" to (login(it.author) ?: it.commit?.author?.name),
          "date" to it.commit?.author?.date)
    }
    return write(obj("number" to number, "commits" to commits))
  }

  suspend fun getPullRequestTimeline(repository: String?, number: Long, includeEvents: Boolean): String {
    checkNumber(number)
    val target = readTarget(repository)
    val items = target.api.loadTimeline(target.repository, number)
      .filter { includeEvents || it.type in CONVERSATION_TYPES }
      .map(::timelineItem)
    return write(obj("number" to number, "timeline" to items))
  }

  suspend fun getPullRequestComments(repository: String?, number: Long): String {
    checkNumber(number)
    val target = readTarget(repository)
    val comments = target.api.loadTimeline(target.repository, number).filter { it.type == "comment" }.map {
      obj("id" to it.id, "author" to login(it.user), "body" to it.body, "createdAt" to it.createdAt,
          "updatedAt" to it.updatedAt?.takeIf { updated -> updated != it.createdAt })
    }
    return write(obj("number" to number, "comments" to comments))
  }

  suspend fun getPullRequestChecks(repository: String?, number: Long): String {
    checkNumber(number)
    val target = readTarget(repository)
    val pr = target.api.getPullRequest(target.repository, number)
    val sha = pr.head?.sha ?: throw GiteaMcpException("Pull request #$number has no head commit.")
    val status = target.api.loadCombinedStatus(target.repository, sha)
    val checks = status.statuses.map { s ->
      val job = s.targetUrl?.let { actionsJob(target.repository, it) }
      obj("context" to s.context, "state" to s.status, "description" to s.description?.takeIf { it.isNotBlank() }, "url" to s.targetUrl,
          "runId" to job?.first, "jobId" to job?.second, "updatedAt" to s.updatedAt)
    }
    return write(obj("number" to number, "sha" to sha, "state" to status.state.takeIf { checks.isNotEmpty() }, "checks" to checks))
  }

  suspend fun getReviewThreads(repository: String?, number: Long, includeResolved: Boolean): String {
    checkNumber(number)
    val target = readTarget(repository)
    val threads = loadThreads(target, number).filter { !it.isPending && (includeResolved || !it.isResolved) }.map(::thread)
    return write(obj("number" to number, "threads" to threads))
  }

  suspend fun getReactions(repository: String?, number: Long, commentId: Long?): String {
    checkNumber(number)
    val target = readTarget(repository)
    val reactionTarget = reactionTarget(number, commentId)
    val (reactions, allowed) = coroutineScope {
      val allowed = async { target.api.loadAllowedReactions() }
      target.api.loadReactions(target.repository, reactionTarget) to allowed.await()
    }
    val grouped = reactions.groupBy { it.content }.map { (content, list) -> obj("reaction" to content, "users" to list.mapNotNull { login(it.user) }) }
    return write(obj("number" to number, "commentId" to commentId, "reactions" to grouped, "allowedReactions" to allowed))
  }

  // ---- write ----

  suspend fun addComment(repository: String?, number: Long, body: String): String {
    checkNumber(number)
    checkText(body, "body")
    val target = writeTarget(repository)
    val comment = target.api.createIssueComment(target.repository, number, body)
    target.onPullRequestChanged(number)
    return write(obj("number" to number, "commentId" to comment.id, "url" to comment.htmlUrl))
  }

  suspend fun replyToReviewComment(repository: String?, number: Long, commentId: Long, body: String): String {
    checkNumber(number)
    checkNumber(commentId, "commentId")
    checkText(body, "body")
    val target = writeTarget(repository)
    target.api.replyToReviewComment(target.repository, number, commentId, body)
    target.onPullRequestChanged(number)
    return write(obj("number" to number, "repliedTo" to commentId))
  }

  suspend fun setThreadResolved(repository: String?, number: Long, commentId: Long, resolved: Boolean): String {
    checkNumber(number)
    checkNumber(commentId, "commentId")
    val target = writeTarget(repository)
    // Gitea resolves by the thread's first comment; accept any comment of the thread
    val thread = loadThreads(target, number).firstOrNull { t -> t.comments.any { it.id == commentId } }
                 ?: throw GiteaMcpException("Comment $commentId is not part of a review thread of pull request #$number.")
    target.api.setReviewThreadResolved(target.repository, thread.id, resolved)
    target.onPullRequestChanged(number)
    return write(obj("number" to number, "threadId" to thread.id, "resolved" to resolved))
  }

  suspend fun addReaction(repository: String?, number: Long, reaction: String, commentId: Long?): String =
    changeReaction(repository, number, reaction, commentId, add = true)

  suspend fun removeReaction(repository: String?, number: Long, reaction: String, commentId: Long?): String =
    changeReaction(repository, number, reaction, commentId, add = false)

  /**
   * Submits a review. On the connected repository the [comments] go through the IDE's pending review (together with
   * comments queued by [addPendingReviewComment] and a pending review started on the server), elsewhere in one request.
   */
  @Suppress("TooGenericExceptionCaught") // whatever failed, the comments are queued now – the assistant must not send them again
  suspend fun submitReview(repository: String?, number: Long, event: String, body: String, comments: List<GiteaMcpReviewComment>): String {
    checkNumber(number)
    val reviewEvent = reviewEvent(event)
    val inline = comments.map { validComment(it) }
    val target = writeTarget(repository)
    val headSha = headSha(target, number)
    val reviewData = target.reviewData?.invoke(number)
    if (reviewData == null) {
      checkReviewContent(reviewEvent, body, inline.size, "inline `comments`")
      val newComments = inline.map { newComment(it) }
      target.api.createReview(target.repository, number, GiteaCreateReviewRequest(reviewEvent, body, headSha, newComments))
      return write(obj("number" to number, "event" to reviewEvent.name, "inlineComments" to newComments.size))
    }
    val pendingBefore = awaitState(reviewData).threads.count { it.isPending }
    checkReviewContent(reviewEvent, body, inline.size + pendingBefore, "inline `comments` or queued pending comments")
    for (comment in inline) {
      reviewData.addComment(comment.path, comment.side, comment.line, headSha, comment.body, pending = true)
    }
    try {
      reviewData.submitReview(reviewEvent, body, headSha)
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      throw GiteaMcpException("Submitting the review failed: ${e.message}. The inline comments are kept as pending review comments in " +
                              "the IDE (see giteapr_list_pending_review_comments) – submit again without `comments`, don't send them twice.", e)
    }
    target.onPullRequestChanged(number)
    return write(obj("number" to number, "event" to reviewEvent.name, "inlineComments" to pendingBefore + inline.size))
  }

  suspend fun addPendingReviewComment(repository: String?, number: Long, comment: GiteaMcpReviewComment): String {
    checkNumber(number)
    val valid = validComment(comment)
    val target = writeTarget(repository)
    val reviewData = reviewData(target, number)
    reviewData.addComment(valid.path, valid.side, valid.line, headSha(target, number), valid.body, pending = true)
    val pending = awaitState(reviewData).threads.filter { it.isPending }
    return write(obj("number" to number, "pendingComments" to pending.size))
  }

  suspend fun listPendingReviewComments(repository: String?, number: Long): String {
    checkNumber(number)
    val target = readTarget(repository)
    val state = awaitState(reviewData(target, number))
    val pending = state.threads.filter { it.isPending }.flatMap { thread ->
      thread.comments.map {
        obj("id" to it.id, "path" to thread.path, "line" to thread.line, "side" to thread.side.name, "body" to it.body,
            "onServer" to (it.id > 0))
      }
    }
    return write(obj("number" to number, "pendingReviewId" to state.pendingReview?.id, "comments" to pending))
  }

  suspend fun discardPendingReview(repository: String?, number: Long): String {
    checkNumber(number)
    val target = writeTarget(repository)
    val reviewData = reviewData(target, number)
    val count = awaitState(reviewData).threads.count { it.isPending }
    reviewData.discardPendingReview()
    target.onPullRequestChanged(number)
    return write(obj("number" to number, "discardedComments" to count))
  }

  @Suppress("LongParameterList") // the tool's arguments
  suspend fun createPullRequest(repository: String?, head: String, base: String?, title: String, body: String?, draft: Boolean): String {
    checkText(head, "head")
    checkText(title, "title")
    val target = writeTarget(repository)
    val baseBranch = base?.trim()?.takeIf { it.isNotEmpty() }
                     ?: target.api.getRepository(target.repository).defaultBranch
                     ?: throw GiteaMcpException("${target.repository} has no default branch, pass `base`.")
    val request = GiteaCreatePullRequestRequest(title = GiteaPullRequestCreateDefaults.withDraft(title.trim(), draft), body = body,
                                                head = head.trim(), base = baseBranch)
    val pr = target.api.createPullRequest(target.repository, request)
    return write(obj("number" to pr.number, "title" to pr.title, "head" to pr.head?.ref, "base" to pr.base?.ref, "url" to pr.htmlUrl))
  }

  @Suppress("LongParameterList") // the tool's arguments
  suspend fun mergePullRequest(repository: String?, number: Long, style: String, title: String?, message: String?, deleteBranch: Boolean?,
                               headSha: String?, whenChecksSucceed: Boolean): String {
    checkNumber(number)
    val mergeStyle = GiteaMergeStyle.of(style.lowercase())
                     ?: throw GiteaMcpException("`style` must be one of ${GiteaMergeStyle.entries.joinToString { it.value }}, got \"$style\".")
    val target = writeTarget(repository)
    val request = GiteaMergePullRequestRequest(mergeStyle.value, title?.takeIf { it.isNotBlank() }, message?.takeIf { it.isNotBlank() },
                                               deleteBranch, headSha?.takeIf { it.isNotBlank() }, whenChecksSucceed.takeIf { it })
    val mergedNow = target.api.mergePullRequest(target.repository, number, request)
    target.onPullRequestChanged(number)
    return write(obj("number" to number, "merged" to mergedNow, "scheduled" to (!mergedNow).takeIf { it }, "style" to mergeStyle.value))
  }

  suspend fun setPullRequestState(repository: String?, number: Long, state: String): String {
    checkNumber(number)
    val newState = state.lowercase()
    if (newState !in listOf("open", "closed")) {
      throw GiteaMcpException("`state` must be \"open\" or \"closed\", got \"$state\".")
    }
    val target = writeTarget(repository)
    val pr = target.api.editPullRequest(target.repository, number, GiteaEditPullRequestRequest(state = newState))
    target.onPullRequestChanged(number)
    return write(obj("number" to number, "state" to pr.state))
  }

  suspend fun setPullRequestDraft(repository: String?, number: Long, draft: Boolean): String {
    checkNumber(number)
    val target = writeTarget(repository)
    val pr = target.api.getPullRequest(target.repository, number)
    val title = GiteaPullRequestCreateDefaults.withDraft(pr.title, draft)
    if (title != pr.title) {
      target.api.editPullRequest(target.repository, number, GiteaEditPullRequestRequest(title = title))
      target.onPullRequestChanged(number)
    }
    return write(obj("number" to number, "draft" to draft, "title" to title))
  }

  // ---- helpers ----

  private suspend fun readTarget(repository: String?): GiteaMcpTarget {
    GiteaMcpGates.checkRead(env)
    return env.target(repository)
  }

  private suspend fun writeTarget(repository: String?): GiteaMcpTarget {
    GiteaMcpGates.checkRead(env)
    return env.target(repository).also { GiteaMcpGates.checkWrite(env, it) }
  }

  private suspend fun changeReaction(repository: String?, number: Long, reaction: String, commentId: Long?, add: Boolean): String {
    checkNumber(number)
    checkText(reaction, "reaction")
    val target = writeTarget(repository)
    val allowed = target.api.loadAllowedReactions()
    val content = reaction.trim().removeSurrounding(":")
    if (allowed.isNotEmpty() && content !in allowed) {
      throw GiteaMcpException("The server doesn't allow the reaction \"$reaction\". Allowed: ${allowed.joinToString()}")
    }
    val reactionTarget = reactionTarget(number, commentId)
    if (add) {
      target.api.addReaction(target.repository, reactionTarget, content)
    }
    else {
      target.api.removeReaction(target.repository, reactionTarget, content)
    }
    target.onPullRequestChanged(number)
    return write(obj("number" to number, "commentId" to commentId, "reaction" to content, "added" to add))
  }

  private fun reactionTarget(number: Long, commentId: Long?): GiteaReactionTarget = when (commentId) {
    null -> GiteaReactionTarget.Issue(number)
    else -> {
      checkNumber(commentId, "commentId")
      GiteaReactionTarget.Comment(commentId)
    }
  }

  private fun reviewData(target: GiteaMcpTarget, number: Long): GiteaPullRequestReviewData {
    val reviewData = target.reviewData
                     ?: throw GiteaMcpException("Pending review comments live in the IDE and work only for the repository the Gitea tool " +
                                                "windows are connected to, not for ${target.repository}. Use giteapr_submit_review with " +
                                                "`comments` instead.")
    return reviewData(number)
  }

  private suspend fun awaitState(reviewData: GiteaPullRequestReviewData): GiteaPullRequestReviewState =
    reviewData.state.filterNotNull().first().getOrElse { throw GiteaMcpException("Could not load the reviews: ${it.message}") }

  private suspend fun headSha(target: GiteaMcpTarget, number: Long): String =
    target.api.getPullRequest(target.repository, number).head?.sha ?: throw GiteaMcpException("Pull request #$number has no head commit.")

  private suspend fun loadThreads(target: GiteaMcpTarget, number: Long): List<GiteaReviewThread> = coroutineScope {
    val reviews = target.api.loadPullRequestReviews(target.repository, number)
    val withComments = reviews.filter { it.state != "REQUEST_REVIEW" && (it.commentsCount > 0 || it.state == "PENDING") }
    val comments = withComments.map { review -> async { review.id to target.api.loadReviewComments(target.repository, number, review.id) } }
      .awaitAll().toMap()
    GiteaReviewThread.build(reviews, comments)
  }

  private data class ValidComment(val path: String, val side: Side, val line: Int, val body: String)

  private fun validComment(comment: GiteaMcpReviewComment): ValidComment {
    checkText(comment.path, "path")
    checkText(comment.body, "body")
    if (comment.line < 1) {
      throw GiteaMcpException("`line` is 1-based, got ${comment.line} for ${comment.path}.")
    }
    val side = when (comment.side.uppercase()) {
      "RIGHT", "NEW" -> Side.RIGHT
      "LEFT", "OLD" -> Side.LEFT
      else -> throw GiteaMcpException("`side` must be RIGHT (new version: added or unchanged line) or LEFT (old version: removed line), " +
                                      "got \"${comment.side}\".")
    }
    return ValidComment(comment.path.trim().trimStart('/'), side, comment.line, comment.body)
  }

  private fun newComment(comment: ValidComment) = when (comment.side) {
    Side.LEFT -> GiteaNewReviewComment(comment.path, comment.body, oldPosition = comment.line)
    Side.RIGHT -> GiteaNewReviewComment(comment.path, comment.body, newPosition = comment.line)
  }

  /** Gitea needs a text or inline comments for anything but an approval. */
  private fun checkReviewContent(event: GiteaReviewEvent, body: String, comments: Int, what: String) {
    if (event != GiteaReviewEvent.APPROVED && body.isBlank() && comments == 0) {
      throw GiteaMcpException("A $event review needs a `body` or $what.")
    }
  }

  companion object {
    private val PR_STATES = listOf("open", "closed", "all")
    private val CONVERSATION_TYPES = setOf("comment", "review")
    private val JOB_LINK = Regex("""/actions/runs/(\d+)/jobs/(\d+)/?$""")

    /** Diffs of single files are cut here – an assistant can't use more anyway. */
    internal const val MAX_DIFF_CHARS: Int = 60_000

    internal fun reviewEvent(event: String): GiteaReviewEvent = when (event.trim().uppercase().replace(' ', '_')) {
      "APPROVED", "APPROVE" -> GiteaReviewEvent.APPROVED
      "REQUEST_CHANGES", "CHANGES_REQUESTED" -> GiteaReviewEvent.REQUEST_CHANGES
      "COMMENT", "COMMENTED" -> GiteaReviewEvent.COMMENT
      else -> throw GiteaMcpException("`event` must be APPROVED, REQUEST_CHANGES or COMMENT, got \"$event\".")
    }

    /** The section of [path] in a unified diff (`diff --git a/… b/…` up to the next file), `null` if it isn't there. */
    internal fun fileDiff(diff: String, path: String): String? {
      val sections = diff.split(Regex("(?m)^(?=diff --git )")).filter { it.startsWith("diff --git ") }
      return sections.firstOrNull { section ->
        val header = section.substringBefore('\n')
        header.endsWith(" b/$path") || header.startsWith("diff --git a/$path ")
      }?.trimEnd('\n')
    }

    /** Run and job id of a Gitea Actions job link of [repository] (the `target_url` of its commit status). */
    internal fun actionsJob(repository: GiteaRepositoryPath, url: String): Pair<Long, Long>? {
      val path = runCatching { java.net.URI.create(url.trim()).path }.getOrNull() ?: return null
      if (!path.contains("/${repository.owner}/${repository.name}/actions/", ignoreCase = true)) {
        return null
      }
      val match = JOB_LINK.find(path) ?: return null
      return match.groupValues[1].toLong() to match.groupValues[2].toLong()
    }

    internal fun pullRequestSummary(pr: GiteaPullRequestDTO): Map<String, Any?> =
      obj("number" to pr.number, "title" to pr.title, "state" to pr.state, "draft" to pr.draft.takeIf { it }, "merged" to pr.merged.takeIf { it },
          "author" to login(pr.user), "head" to pr.head?.ref, "base" to pr.base?.ref, "labels" to pr.labels.map { it.name }.ifEmpty { null },
          "mergeable" to pr.mergeable.takeIf { pr.state == "open" }, "updatedAt" to pr.updatedAt)

    internal fun issueSummary(issue: GiteaIssueDTO): Map<String, Any?> =
      obj("number" to issue.number, "title" to issue.title, "state" to issue.state, "draft" to issue.pullRequest?.draft?.takeIf { it },
          "merged" to issue.pullRequest?.merged?.takeIf { it }, "author" to login(issue.user),
          "labels" to issue.labels.map { it.name }.ifEmpty { null }, "updatedAt" to issue.updatedAt)

    internal fun pullRequestDetails(pr: GiteaPullRequestDTO, reviews: List<GiteaReviewDTO>): Map<String, Any?> {
      // the latest review of each user counts; `REQUEST_REVIEW` entries are open requests, `PENDING` ones aren't submitted
      val verdicts = reviews.filter { it.state != "REQUEST_REVIEW" && it.state != "PENDING" }
        .mapNotNull { review -> review.user?.let { it.login to review } }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        .map { (user, list) ->
          val latest = list.last()
          obj("user" to user, "state" to latest.state, "submittedAt" to latest.submittedAt, "stale" to latest.stale.takeIf { it },
              "dismissed" to latest.dismissed.takeIf { it })
        }
      return obj(
        "number" to pr.number, "title" to pr.title, "body" to pr.body, "state" to pr.state, "draft" to pr.draft, "merged" to pr.merged,
        "mergedAt" to pr.mergedAt, "mergedBy" to login(pr.mergedBy), "mergeCommitSha" to pr.mergeCommitSha,
        "mergeable" to pr.mergeable.takeIf { pr.state == "open" }, "author" to login(pr.user),
        "head" to pr.head?.let { obj("ref" to it.ref, "sha" to it.sha, "repository" to it.repo?.fullName) },
        "base" to pr.base?.let { obj("ref" to it.ref, "sha" to it.sha) }, "mergeBase" to pr.mergeBase,
        "labels" to pr.labels.map { it.name }, "milestone" to pr.milestone?.title, "assignees" to pr.assignees.map { it.login },
        "requestedReviewers" to pr.requestedReviewers.map { it.login } + pr.requestedReviewersTeams.map { it.name },
        "reviews" to verdicts, "additions" to pr.additions, "deletions" to pr.deletions, "changedFiles" to pr.changedFiles,
        "comments" to pr.comments, "url" to pr.htmlUrl, "createdAt" to pr.createdAt, "updatedAt" to pr.updatedAt, "closedAt" to pr.closedAt,
      )
    }

    internal fun timelineItem(event: GiteaTimelineEventDTO): Map<String, Any?> {
      val push = when (event.type) {
        "pull_push" -> GiteaPushData.parse(event.body)
        else -> null
      }
      return obj(
        "id" to event.id, "type" to event.type, "author" to login(event.user), "createdAt" to event.createdAt,
        "body" to event.body?.takeIf { it.isNotBlank() && push == null },
        "pushedCommits" to push?.commitIds?.takeIf { !push.isForcePush }, "forcePush" to push?.takeIf { it.isForcePush }?.commitIds,
        "reviewId" to event.reviewId.takeIf { it > 0 }, "label" to event.label?.name, "assignee" to login(event.assignee),
        // Gitea sends "" for the fields an event type doesn't use
        "removed" to event.removedAssignee.takeIf { it }, "milestone" to event.milestone?.title, "oldTitle" to event.oldTitle.ifNotBlank(),
        "newTitle" to event.newTitle.ifNotBlank(), "oldRef" to event.oldRef.ifNotBlank(), "newRef" to event.newRef.ifNotBlank(),
        "commit" to event.refCommitSha.ifNotBlank(),
        "resolvedBy" to login(event.resolveDoer),
      )
    }

    private fun String?.ifNotBlank(): String? = this?.takeIf { it.isNotBlank() }

    internal fun thread(thread: GiteaReviewThread): Map<String, Any?> =
      obj("id" to thread.id, "path" to thread.path, "line" to thread.line, "side" to thread.side.name, "resolved" to thread.isResolved,
          "resolvedBy" to login(thread.resolver), "reviewId" to thread.reviewId, "diffHunk" to thread.diffHunk,
          "comments" to thread.comments.map { obj("id" to it.id, "author" to login(it.author), "body" to it.body, "createdAt" to it.createdAt) })
  }
}
