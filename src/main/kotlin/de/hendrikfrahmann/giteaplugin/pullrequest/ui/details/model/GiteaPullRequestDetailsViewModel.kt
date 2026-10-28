package de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model

import com.intellij.collaboration.ui.codereview.details.data.ReviewRequestState
import com.intellij.collaboration.ui.codereview.details.data.ReviewState
import com.intellij.collaboration.ui.codereview.details.model.CodeReviewDetailsViewModel
import com.intellij.collaboration.ui.icon.IconsProvider
import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.openapi.ListSelection
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaLabelDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaPullRequestDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaReviewDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaTeamDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestDataProvider
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestSnapshot
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.comment.GiteaMarkdown
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import de.hendrikfrahmann.giteaplugin.ui.action.GiteaWebUrls
import de.hendrikfrahmann.giteaplugin.util.childScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import java.util.Date

private val LOG = logger<GiteaPullRequestDetailsViewModel>()

/** Details of one PR for the tool window tab; all parts follow [GiteaPullRequestDataProvider.snapshot]. */
@OptIn(ExperimentalCoroutinesApi::class)
class GiteaPullRequestDetailsViewModel(
  parentCs: CoroutineScope,
  val project: Project,
  val connection: GiteaProjectConnection,
  val dataProvider: GiteaPullRequestDataProvider,
  initial: GiteaPullRequestSnapshot,
  val avatarIconsProvider: IconsProvider<GiteaUserDTO>,
  private val onShowTimeline: () -> Unit = {},
  private val onShowDiff: (ListSelection<RefComparisonChange>) -> Unit = {},
) : CodeReviewDetailsViewModel {
  private val cs = parentCs.childScope()

  /** For popups opened by actions on this PR (edit labels, …); cancelled with the details. */
  val uiScope: CoroutineScope get() = cs

  val snapshot: StateFlow<GiteaPullRequestSnapshot> = dataProvider.snapshot.mapNotNull { it?.getOrNull() }
    .stateIn(cs, SharingStarted.Eagerly, initial)
  private val pullRequest: StateFlow<GiteaPullRequestDTO> = snapshot.map { it.pullRequest }.stateIn(cs, SharingStarted.Eagerly, initial.pullRequest)

  val isLoading: StateFlow<Boolean> = dataProvider.isLoading

  override val number: String = "#${dataProvider.number}"
  override val url: String = GiteaWebUrls.pullRequest(connection.repo.repository.getWebURI(), dataProvider.number).toString()

  override val title: Flow<String> = pullRequest.map { StringUtil.escapeXmlEntities(it.title) }.distinctUntilChanged()

  /** Rendered by [GiteaMarkdown] (Gitea's `POST /markdown` when logged in), so `#1`/`@user` links match the web UI; escaped plain text if that fails. */
  override val description: SharedFlow<String> = pullRequest.map { it.body.orEmpty() }.distinctUntilChanged()
    .mapLatest(::renderDescription)
    .shareIn(cs, SharingStarted.Eagerly, 1)

  override val reviewRequestState: Flow<ReviewRequestState> = pullRequest.map(::requestState).distinctUntilChanged()

  val author: GiteaUserDTO? get() = pullRequest.value.user
  val createdAt: Date? get() = pullRequest.value.createdAt
  val labels: StateFlow<List<GiteaLabelDTO>> = pullRequest.map { it.labels }.stateIn(cs, SharingStarted.Eagerly, initial.pullRequest.labels)
  val assignees: StateFlow<List<GiteaUserDTO>> = pullRequest.map { pr -> pr.assignees.ifEmpty { listOfNotNull(pr.assignee) } }
    .stateIn(cs, SharingStarted.Eagerly, emptyList())
  val milestone: StateFlow<String?> = pullRequest.map { it.milestone?.title }.stateIn(cs, SharingStarted.Eagerly, null)

  /** Reviewer → latest review state; see [reviewerStates]. */
  val reviewers: StateFlow<Map<GiteaUserDTO, GiteaReviewerState>> = snapshot.map { reviewerStates(it.pullRequest, it.reviews) }
    .stateIn(cs, SharingStarted.Eagerly, reviewerStates(initial.pullRequest, initial.reviews))
  val requestedTeams: StateFlow<List<GiteaTeamDTO>> = pullRequest.map { it.requestedReviewersTeams }
    .stateIn(cs, SharingStarted.Eagerly, initial.pullRequest.requestedReviewersTeams)
  val reviewStates: Flow<Map<GiteaUserDTO, ReviewState>> = reviewers.map { map -> map.mapValues { it.value.reviewState } }

  val branchesVm: GiteaPullRequestBranchesViewModel = GiteaPullRequestBranchesViewModel(cs, connection, pullRequest)
  val statusVm: GiteaPullRequestStatusViewModel = GiteaPullRequestStatusViewModel(cs, connection, snapshot)
  val changesVm: GiteaPullRequestChangesViewModel = GiteaPullRequestChangesViewModel(cs, project, connection, dataProvider, onShowDiff)
  val editVm: GiteaPullRequestEditViewModel = GiteaPullRequestEditViewModel(cs, connection, dataProvider, pullRequest)

  /** Approvals that count for branch protection: latest review per user is an approval. */
  private val approvals: StateFlow<Int> = reviewers.map { map -> map.values.count { it == GiteaReviewerState.APPROVED } }
    .stateIn(cs, SharingStarted.Eagerly, 0)
  val mergeVm: GiteaPullRequestMergeViewModel = GiteaPullRequestMergeViewModel(cs, connection, dataProvider, snapshot, approvals)

  fun refresh() = dataProvider.refresh()

  /** Opens the timeline editor tab. */
  fun showTimeline() = onShowTimeline()

  private suspend fun renderDescription(body: String): String {
    if (body.isBlank()) {
      return ""
    }
    return try {
      GiteaMarkdown.render(project, connection, body)
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      LOG.info("Could not render the description of $number", e)
      "<pre>${StringUtil.escapeXmlEntities(body)}</pre>"
    }
  }

  companion object {
    fun requestState(pr: GiteaPullRequestDTO): ReviewRequestState = when {
      pr.merged -> ReviewRequestState.MERGED
      pr.state != "open" -> ReviewRequestState.CLOSED
      pr.draft -> ReviewRequestState.DRAFT
      else -> ReviewRequestState.OPENED
    }

    /**
     * Latest review per user (dismissed and pending own drafts ignored), plus requested reviewers who haven't reviewed
     * since the request. The author is never a reviewer.
     */
    fun reviewerStates(pr: GiteaPullRequestDTO, reviews: List<GiteaReviewDTO>): Map<GiteaUserDTO, GiteaReviewerState> {
      val byLogin = linkedMapOf<String, Pair<GiteaUserDTO, GiteaReviewerState>>()
      for (review in reviews.sortedBy { it.submittedAt ?: it.updatedAt }.filterNot { it.dismissed }) {
        val user = review.user ?: continue
        val state = reviewerState(review.state) ?: continue
        val previous = byLogin[user.login]?.second
        if (!isPlainCommentAfterVerdict(previous, state)) {
          byLogin[user.login] = user to state
        }
      }
      for (user in pr.requestedReviewers) {
        if (byLogin[user.login]?.second == null) {
          byLogin[user.login] = user to GiteaReviewerState.PENDING
        }
      }
      pr.user?.login?.let { byLogin.remove(it) }
      return byLogin.values.associate { it }
    }

    /** `null` for pending drafts and unknown states. */
    private fun reviewerState(reviewState: String?): GiteaReviewerState? = when (reviewState) {
      "APPROVED" -> GiteaReviewerState.APPROVED
      "REQUEST_CHANGES" -> GiteaReviewerState.CHANGES_REQUESTED
      "COMMENT" -> GiteaReviewerState.COMMENTED
      "REQUEST_REVIEW" -> GiteaReviewerState.PENDING
      else -> null
    }

    /** A plain comment doesn't replace an approval or a change request. */
    private fun isPlainCommentAfterVerdict(previous: GiteaReviewerState?, state: GiteaReviewerState): Boolean =
      state == GiteaReviewerState.COMMENTED && (previous == GiteaReviewerState.APPROVED || previous == GiteaReviewerState.CHANGES_REQUESTED)
  }
}

enum class GiteaReviewerState(val reviewState: ReviewState) {
  APPROVED(ReviewState.ACCEPTED),
  CHANGES_REQUESTED(ReviewState.WAIT_FOR_UPDATES),
  COMMENTED(ReviewState.NEED_REVIEW),
  PENDING(ReviewState.NEED_REVIEW),
}
