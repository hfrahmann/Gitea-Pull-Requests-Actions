package de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model

import com.intellij.collaboration.ui.codereview.details.data.ReviewRequestState
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import de.hendrikfrahmann.giteaplugin.actions.GiteaActionsCurrentBranchService
import de.hendrikfrahmann.giteaplugin.api.GiteaHttpStatusError
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaPullRequestDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaRepositoryDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaTimelineEventDTO
import de.hendrikfrahmann.giteaplugin.api.dto.hasConflicts
import de.hendrikfrahmann.giteaplugin.api.request.GiteaEditPullRequestRequest
import de.hendrikfrahmann.giteaplugin.api.request.GiteaMergePullRequestRequest
import de.hendrikfrahmann.giteaplugin.api.request.GiteaMergeStyle
import de.hendrikfrahmann.giteaplugin.api.request.cancelAutoMerge
import de.hendrikfrahmann.giteaplugin.api.request.editPullRequest
import de.hendrikfrahmann.giteaplugin.api.request.getRepository
import de.hendrikfrahmann.giteaplugin.api.request.mergePullRequest
import de.hendrikfrahmann.giteaplugin.api.request.updatePullRequestBranch
import de.hendrikfrahmann.giteaplugin.notification.GiteaNotificationIds
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestCreateDefaults
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestDataProvider
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestSnapshot
import de.hendrikfrahmann.giteaplugin.pullrequest.review.GiteaCurrentBranchPullRequestService
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import de.hendrikfrahmann.giteaplugin.util.childScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private val LOG = logger<GiteaPullRequestMergeViewModel>()

/**
 * What can be done with an open PR besides reviewing: merge in one of the repository's allowed styles (or
 * schedule the merge for when the required checks succeed), close / reopen, draft (`WIP:` prefix) and update the
 * branch when it is behind. Permissions come from the repository (`permissions.push`) and the target branch
 * (`user_can_merge`, branch protection); Gitea's own refusals are shown as notifications.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GiteaPullRequestMergeViewModel(
  parentCs: CoroutineScope,
  private val connection: GiteaProjectConnection,
  private val dataProvider: GiteaPullRequestDataProvider,
  val snapshot: StateFlow<GiteaPullRequestSnapshot>,
  approvals: StateFlow<Int>,
) {
  private val cs = parentCs.childScope()
  private val api = connection.api
  private val repository = connection.repo.repository.path
  /** Repository settings as of the last refresh (fall back to the ones from connecting). */
  private val settings: GiteaRepositoryDTO get() = snapshot.value.repository ?: connection.repository

  private val pullRequest: GiteaPullRequestDTO get() = snapshot.value.pullRequest
  private val canPush: Boolean get() = canPush(settings)
  private val isAuthor: Boolean
    get() = connection.currentUser?.login?.let { pullRequest.user?.login.equals(it, ignoreCase = true) } == true

  private val _isBusy = MutableStateFlow(false)
  val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

  /** Styles the repository allows, the default one first. */
  val mergeStyles: List<GiteaMergeStyle> get() = mergeStyles(settings)

  val deleteBranchByDefault: Boolean get() = settings.defaultDeleteBranchAfterMerge

  /** Allowed styles (default first) and the delete-branch default, read from the server right now (for the merge dialog). */
  suspend fun loadMergeOptions(): Pair<List<GiteaMergeStyle>, Boolean> {
    val current = try {
      api.getRepository(repository)
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      LOG.info("Could not reload the merge settings of $repository", e)
      settings
    }
    return mergeStyles(current) to current.defaultDeleteBranchAfterMerge
  }

  /** Merging: write access, and the target branch's protection doesn't exclude the user. */
  val canMerge: StateFlow<Boolean> = snapshot.map {
    val settings = it.repository ?: connection.repository
    canPush(settings) && it.baseBranch?.userCanMerge != false && mergeStyles(settings).isNotEmpty()
  }
    .stateIn(cs, SharingStarted.Eagerly, false)

  /**
   * Gitea found conflicts with the base (`mergeable = false`, not a draft). Verified on 28.1: merging such a PR is refused
   * with a misleading `405 Please try again later`, so the merge is disabled instead.
   */
  val hasConflicts: StateFlow<Boolean> = snapshot.map { it.pullRequest.hasConflicts }
    .stateIn(cs, SharingStarted.Eagerly, false)

  /** Close / reopen / draft: the author or anyone with write access. */
  val canChangeState: Boolean get() = canPush || (!connection.isAnonymous && isAuthor)

  val requestState: StateFlow<ReviewRequestState> = snapshot.map { GiteaPullRequestDetailsViewModel.requestState(it.pullRequest) }
    .stateIn(cs, SharingStarted.Eagerly, GiteaPullRequestDetailsViewModel.requestState(snapshot.value.pullRequest))
  val isDraft: StateFlow<Boolean> = requestState.map { it == ReviewRequestState.DRAFT }.stateIn(cs, SharingStarted.Eagerly, false)

  /** Commits the head is behind its base; `0` when up to date or closed. */
  val behindBy: StateFlow<Int> = snapshot.map { it.behindBy ?: 0 }.stateIn(cs, SharingStarted.Eagerly, 0)

  /** `true` for rebase, `false` for merge – the update styles the repository allows, its default first. */
  val updateStyles: List<Boolean> get() = buildList {
    val rebaseFirst = settings.defaultUpdateStyle == "rebase"
    if (rebaseFirst && settings.allowRebaseUpdate) {
      add(true)
    }
    if (settings.allowMergeUpdate) {
      add(false)
    }
    if (!rebaseFirst && settings.allowRebaseUpdate) {
      add(true)
    }
  }

  /** Updating rewrites the head branch: the author (maintainer edits) or anyone with write access. */
  val canUpdateBranch: Boolean get() = canChangeState && updateStyles.isNotEmpty()

  /** Approvals still needed by the target branch's protection (Gitea only counts approvals of users allowed to approve). */
  val missingApprovals: StateFlow<Int> = snapshot.map { it.baseBranch?.requiredApprovals?.toInt() ?: 0 }
    .flatMapLatest { required -> approvals.map { (required - it).coerceAtLeast(0) } }
    .stateIn(cs, SharingStarted.Eagerly, 0)

  /** The protection excludes the user from merging although they can write. */
  val isMergeRestricted: StateFlow<Boolean> = snapshot.map { canPush(it.repository ?: connection.repository) && it.baseBranch?.userCanMerge == false }
    .stateIn(cs, SharingStarted.Eagerly, false)

  /** Head commit has pending or failed checks, so merging now may be refused – offer "merge when checks succeed". */
  val checksPending: StateFlow<Boolean> = snapshot.map { it.status?.state == "pending" }.stateIn(cs, SharingStarted.Eagerly, false)

  /**
   * A merge is scheduled for when the checks succeed. Gitea has no field for it; verified on 28.1 the timeline has
   * `pull_scheduled_merge` / `pull_cancel_scheduled_merge` (followed by `merge_pull` once merged).
   */
  val isAutoMergeScheduled: StateFlow<Boolean> = requestState.flatMapLatest { state ->
    when (state) {
      ReviewRequestState.OPENED, ReviewRequestState.DRAFT ->
        dataProvider.timelineData.state.map { result -> isAutoMergeScheduled(result?.getOrNull()?.events.orEmpty()) }
      else -> flowOf(false)
    }
  }.stateIn(cs, SharingStarted.Eagerly, false)

  /** Merges with [style]; [whenChecksSucceed] schedules it instead. Blank texts leave Gitea's defaults. */
  fun merge(style: GiteaMergeStyle, title: String?, message: String?, deleteBranch: Boolean, whenChecksSucceed: Boolean) =
    run(GiteaBundle.message("pull.request.merge.failed")) {
      val request = GiteaMergePullRequestRequest(style.value, title?.takeIf { it.isNotBlank() }, message?.takeIf { it.isNotBlank() },
                                                 deleteBranch, pullRequest.head?.sha, whenChecksSucceed.takeIf { it })
      if (!api.mergePullRequest(repository, dataProvider.number, request)) {
        notify(GiteaBundle.message("pull.request.merge.scheduled"), "", NotificationType.INFORMATION)
      }
    }

  fun cancelAutoMerge() = run(GiteaBundle.message("pull.request.auto.merge.cancel.failed")) {
    api.cancelAutoMerge(repository, dataProvider.number)
  }

  fun close() = edit(GiteaEditPullRequestRequest(state = "closed"))
  fun reopen() = edit(GiteaEditPullRequestRequest(state = "open"))

  /** Gitea marks drafts by a `WIP:` title prefix (the `draft` field is read-only). */
  fun setDraft(draft: Boolean) = edit(GiteaEditPullRequestRequest(title = draftTitle(pullRequest.title, draft)))

  fun updateBranch(rebase: Boolean) = run(GiteaBundle.message("pull.request.update.branch.failed")) {
    api.updatePullRequestBranch(repository, dataProvider.number, rebase)
    // verified on 28.1: the PR's `merge_base` follows about a second later; reloading at once would still show "behind"
    delay(UPDATE_SETTLE)
    // the new head is only on Gitea: look up the branch's PR head and its runs ("Current Branch") now, not at the next poll
    val project = connection.repo.gitRepository.project
    GiteaCurrentBranchPullRequestService.getInstance(project).refresh()
    GiteaActionsCurrentBranchService.getInstance(project).refresh()
  }

  /** Styles [settings] allow, the default one first. */
  private fun mergeStyles(settings: GiteaRepositoryDTO): List<GiteaMergeStyle> = buildList {
    if (settings.allowMergeCommits) {
      add(GiteaMergeStyle.MERGE)
    }
    if (settings.allowRebase) {
      add(GiteaMergeStyle.REBASE)
    }
    if (settings.allowRebaseExplicit) {
      add(GiteaMergeStyle.REBASE_MERGE)
    }
    if (settings.allowSquashMerge) {
      add(GiteaMergeStyle.SQUASH)
    }
    if (settings.allowFastForwardOnlyMerge) {
      add(GiteaMergeStyle.FAST_FORWARD_ONLY)
    }
  }.sortedByDescending { it == GiteaMergeStyle.of(settings.defaultMergeStyle) }

  private fun canPush(settings: GiteaRepositoryDTO): Boolean = !connection.isAnonymous && settings.permissions?.push == true

  private fun edit(request: GiteaEditPullRequestRequest) = run(GiteaBundle.message("pull.request.edit.failed")) {
    api.editPullRequest(repository, dataProvider.number, request)
  }

  /** Runs [block] busy, reports a failure with Gitea's message and reloads the PR (and its timeline) either way. */
  private fun run(failureTitle: String, block: suspend () -> Unit) {
    if (_isBusy.value) {
      return
    }
    cs.launch {
      _isBusy.value = true
      try {
        block()
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (e: Exception) {
        LOG.info("Pull request #${dataProvider.number}: $failureTitle", e)
        notify(failureTitle, (e as? GiteaHttpStatusError)?.apiMessage?.takeIf { it.isNotBlank() } ?: e.localizedMessage.orEmpty(),
               NotificationType.ERROR)
      }
      finally {
        _isBusy.value = false
        dataProvider.refresh()
        if (requestState.value == ReviewRequestState.OPENED || requestState.value == ReviewRequestState.DRAFT) {
          dataProvider.timelineData.reload()
        }
      }
    }
  }

  private fun notify(title: String, content: String, type: NotificationType) {
    NotificationGroupManager.getInstance().getNotificationGroup(GiteaNotificationIds.GROUP)
      .createNotification(title, content, type)
      .setDisplayId(when (type) {
        NotificationType.ERROR -> GiteaNotificationIds.PULL_REQUEST_ACTION_FAILED
        else -> GiteaNotificationIds.PULL_REQUEST_MERGE
      })
      .notify(connection.repo.gitRepository.project)
  }

  companion object {
    private val UPDATE_SETTLE = 1500.milliseconds
    /** Adds or removes Gitea's default work-in-progress prefix. */
    fun draftTitle(title: String, draft: Boolean): String = GiteaPullRequestCreateDefaults.withDraft(title, draft)

    fun isAutoMergeScheduled(events: List<GiteaTimelineEventDTO>): Boolean =
      events.lastOrNull { it.type == "pull_scheduled_merge" || it.type == "pull_cancel_scheduled_merge" || it.type == "merge_pull" }
        ?.type == "pull_scheduled_merge"
  }
}
