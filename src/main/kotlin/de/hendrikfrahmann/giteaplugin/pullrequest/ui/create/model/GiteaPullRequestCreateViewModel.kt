package de.hendrikfrahmann.giteaplugin.pullrequest.ui.create.model

import com.intellij.collaboration.ui.ListenableProgressIndicator
import com.intellij.collaboration.ui.codereview.create.CodeReviewTitleDescriptionViewModel
import com.intellij.collaboration.ui.icon.IconsProvider
import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.vcs.log.VcsCommitMetadata
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaLabelDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaMilestoneDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaPullRequestDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.api.request.GiteaCreatePullRequestRequest
import de.hendrikfrahmann.giteaplugin.api.request.createPullRequest
import de.hendrikfrahmann.giteaplugin.api.request.findOpenPullRequest
import de.hendrikfrahmann.giteaplugin.api.request.labelsFlow
import de.hendrikfrahmann.giteaplugin.api.request.loadAssignees
import de.hendrikfrahmann.giteaplugin.api.request.milestonesFlow
import de.hendrikfrahmann.giteaplugin.api.request.searchUsers
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestCreateDefaults
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectMapping
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectsManager
import de.hendrikfrahmann.giteaplugin.settings.GiteaSettings
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import de.hendrikfrahmann.giteaplugin.util.childScope
import git4idea.GitBranch
import git4idea.GitLocalBranch
import git4idea.GitPushUtil
import git4idea.GitRemoteBranch
import git4idea.GitRevisionNumber
import git4idea.changes.GitChangeUtils
import git4idea.history.GitHistoryUtils
import git4idea.remote.hosting.changesSignalFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LOG = logger<GiteaPullRequestCreateViewModel>()

/** Base (always a branch of the connected repository on its remote) and head of the new PR. */
data class GiteaPullRequestCreateBranchState(
  val baseRepo: GiteaProjectMapping,
  val baseBranch: GitRemoteBranch,
  val headRepo: GiteaProjectMapping,
  val headBranch: GitBranch,
)

enum class GiteaPullRequestCreateError { WRONG_DIRECTION, NO_CHANGES }

/**
 * Commits of `base..head`, newest first. `GitHistoryUtils.collectCommitsMetadata` takes single hashes (no range: with
 * one it happens to work, with more it returns `null`), so the range is walked first.
 */
@RequiresBackgroundThread
internal fun collectCommitsBetween(project: Project, root: VirtualFile, base: String, head: String): List<VcsCommitMetadata> {
  ThreadingAssertions.assertBackgroundThread()
  val hashes = GitHistoryUtils.collectTimedCommits(project, root, "$base..$head").map { it.id.asString() }
  if (hashes.isEmpty()) {
    return emptyList()
  }
  return GitHistoryUtils.collectCommitsMetadata(project, root, *hashes.toTypedArray())?.toList()
         ?: throw VcsException(GiteaBundle.message("pull.request.create.commits.unreadable", base, head))
}

/** Commits and changes between base and head, computed locally. */
data class GiteaPullRequestCreateComparison(val commits: List<VcsCommitMetadata>, val changes: List<RefComparisonChange>)

/**
 * "New Pull Request" tab, modelled on GitLab's create view model: direction, commits and changes from local git,
 * prefilled title/description (template from the base repository), metadata, push of the head branch if needed, create.
 * The base is the connected repository; heads can be branches of any Gitea remote of the same git repository (fork →
 * upstream: connect the tool window to the upstream remote and pick the fork's branch as head).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GiteaPullRequestCreateViewModel(
  parentCs: CoroutineScope,
  private val project: Project,
  val connection: GiteaProjectConnection,
  private val projectsManager: GiteaProjectsManager,
  val avatarIconsProvider: IconsProvider<GiteaUserDTO>,
  private val openPullRequest: (number: Long) -> Unit,
  private val onCreated: (GiteaPullRequestDTO) -> Unit,
) : CodeReviewTitleDescriptionViewModel {
  private val cs = parentCs.childScope()
  private val api = connection.api
  private val baseRepository = connection.repo.repository.path
  private val defaultBranch: String? = connection.repository.defaultBranch

  val baseRepo: GiteaProjectMapping = connection.repo

  private val _isBusy = MutableStateFlow(false)
  val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

  private val progressIndicator = ListenableProgressIndicator()
  private val _progressText = MutableStateFlow<String?>(null)
  /** Push progress, then "Creating…" while [isBusy]. */
  val progressText: StateFlow<String?> = combine(_progressText, callbackFlow {
    val disposable = Disposer.newDisposable()
    progressIndicator.addAndInvokeListener(disposable) { trySend(progressIndicator.text) }
    awaitClose { Disposer.dispose(disposable) }
  }) { own, push -> push?.takeIf { it.isNotBlank() } ?: own }.stateIn(cs, SharingStarted.Eagerly, null)

  private val _branchState = MutableStateFlow<GiteaPullRequestCreateBranchState?>(null)
  val branchState: StateFlow<GiteaPullRequestCreateBranchState?> = _branchState.asStateFlow()

  /** `null` while computing. */
  val comparison: StateFlow<Result<GiteaPullRequestCreateComparison>?> =
    combine(baseRepo.gitRepository.changesSignalFlow().onStart { emit(Unit) }, branchState) { _, state -> state?.let(::revisions) }
      .distinctUntilChanged()
      .transformLatest { revisions ->
        if (revisions == null) {
          emit(Result.success(GiteaPullRequestCreateComparison(emptyList(), emptyList())))
          return@transformLatest
        }
        emit(null)
        emit(withContext(Dispatchers.IO) { runCatching { compare(revisions) } })
      }
      .stateIn(cs, SharingStarted.Eagerly, null)

  /** The open PR for the same head and base, if any. */
  val existingPullRequest: StateFlow<GiteaPullRequestDTO?> = branchState.mapLatest { state -> state?.let { findExistingPullRequest(it) } }
    .stateIn(cs, SharingStarted.Eagerly, null)

  val requirementsError: StateFlow<GiteaPullRequestCreateError?> = combine(branchState, comparison, ::requirementsError)
    .stateIn(cs, SharingStarted.Eagerly, null)

  private val _creatingError = MutableStateFlow<Throwable?>(null)
  val creatingError: StateFlow<Throwable?> = _creatingError.asStateFlow()

  private val _title = MutableStateFlow(Generated("", generated = true))
  override val titleText: StateFlow<String> = _title.map { it.text }.stateIn(cs, SharingStarted.Eagerly, "")

  private val _description = MutableStateFlow(Generated("", generated = true))
  override val descriptionText: StateFlow<String> = _description.map { it.text }.stateIn(cs, SharingStarted.Eagerly, "")

  private val template = MutableStateFlow<String?>(null)
  private val _isTemplateLoading = MutableStateFlow(true)
  override val isTemplateLoading: StateFlow<Boolean> = _isTemplateLoading.asStateFlow()

  private val _draft = MutableStateFlow(false)
  val draft: StateFlow<Boolean> = _draft.asStateFlow()

  private val _reviewers = MutableStateFlow<List<GiteaUserDTO>>(emptyList())
  val reviewers: StateFlow<List<GiteaUserDTO>> = _reviewers.asStateFlow()
  private val _assignees = MutableStateFlow<List<GiteaUserDTO>>(emptyList())
  val assignees: StateFlow<List<GiteaUserDTO>> = _assignees.asStateFlow()
  private val _labels = MutableStateFlow<List<GiteaLabelDTO>>(emptyList())
  val labels: StateFlow<List<GiteaLabelDTO>> = _labels.asStateFlow()
  private val _milestone = MutableStateFlow<GiteaMilestoneDTO?>(null)
  val milestone: StateFlow<GiteaMilestoneDTO?> = _milestone.asStateFlow()

  init {
    _branchState.value = initialBranchState()
    cs.launch { loadTemplate() }
    cs.launch { keepGeneratedTextsInSync() }
  }

  /** Gitea remotes of the base's git repository – possible head repositories. */
  fun knownHeadRepositories(): List<GiteaProjectMapping> =
    projectsManager.knownRepositoriesState.value.filter { it.gitRepository.root == baseRepo.gitRepository.root }
      .sortedByDescending { it == baseRepo }

  fun updateBranchState(state: GiteaPullRequestCreateBranchState?) {
    _branchState.value = state
  }

  override fun setTitle(text: String) {
    if (text == _title.value.text) {
      return
    }
    _title.value = Generated(text, generated = false)
    _draft.value = GiteaPullRequestCreateDefaults.isDraft(text)
  }

  override fun setDescription(text: String) {
    if (text == _description.value.text) {
      return
    }
    _description.value = Generated(text, generated = false)
  }

  fun setDraft(draft: Boolean) {
    _draft.value = draft
    val title = _title.value
    _title.value = title.copy(text = GiteaPullRequestCreateDefaults.withDraft(title.text, draft))
  }

  fun setReviewers(users: List<GiteaUserDTO>) { _reviewers.value = users }
  fun setAssignees(users: List<GiteaUserDTO>) { _assignees.value = users }
  fun setLabels(labels: List<GiteaLabelDTO>) { _labels.value = labels }
  fun setMilestone(milestone: GiteaMilestoneDTO?) { _milestone.value = milestone }

  /** Users with write access; reviewers from all users when the setting says so. */
  suspend fun loadUsers(reviewers: Boolean): List<GiteaUserDTO> {
    val users = when {
      reviewers && GiteaSettings.getInstance().reviewersFromAllUsers -> api.searchUsers()
      else -> api.loadAssignees(baseRepository)
    }
    return users.sortedBy { it.login.lowercase() }
  }

  suspend fun loadLabels(): List<GiteaLabelDTO> = api.labelsFlow(baseRepository).toList().flatten()
  suspend fun loadMilestones(): List<GiteaMilestoneDTO> = api.milestonesFlow(baseRepository).toList().flatten().filter { it.state != "closed" }

  fun openExistingPullRequest() {
    existingPullRequest.value?.let { openPullRequest(it.number) }
  }

  /** Changes of one commit against its first parent, for the commit chooser of the changes tree. */
  suspend fun loadCommitChanges(commit: VcsCommitMetadata): List<RefComparisonChange> = withContext(Dispatchers.IO) {
    val parent = commit.parents.firstOrNull() ?: return@withContext emptyList()
    diff(parent.asString(), commit.id.asString())
  }

  /**
   * Pushes the head branch if needed (asking for the remote branch name the first time), then creates the PR.
   * [draft]: "Create Draft Pull Request" of the create button's options.
   */
  fun create(draft: Boolean) {
    if (_isBusy.value) {
      return
    }
    val state = _branchState.value ?: return
    cs.launch {
      _isBusy.value = true
      _creatingError.value = null
      try {
        val remoteBranch = findOrPushRemoteBranch(state) ?: return@launch
        _progressText.value = GiteaBundle.message("pull.request.create.progress")
        onCreated(api.createPullRequest(baseRepository, createRequest(state, remoteBranch, draft || this@GiteaPullRequestCreateViewModel.draft.value)))
      }
      catch (_: ProcessCanceledException) {
        // push dialog cancelled
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (e: Exception) {
        LOG.info("Could not create pull request", e)
        _creatingError.value = e
      }
      finally {
        _progressText.value = null
        progressIndicator.text = ""
        _isBusy.value = false
      }
    }
  }

  private suspend fun loadTemplate() {
    try {
      template.value = defaultBranch?.let { GiteaPullRequestCreateDefaults.loadTemplate(api, baseRepository, it) }
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      LOG.info("Could not load the pull request template", e)
    }
    finally {
      _isTemplateLoading.value = false
    }
  }

  /** Title and description follow the commits (and the template) as long as the user didn't edit them. */
  private suspend fun keepGeneratedTextsInSync() {
    combine(comparison, branchState, template, ::Triple).collect { (comparison, state, template) ->
      val commits = comparison?.getOrNull()?.commits ?: return@collect
      val messages = commits.map { it.fullMessage }
      state ?: return@collect
      val title = _title.value
      if (title.generated) {
        val text = GiteaPullRequestCreateDefaults.withDraft(GiteaPullRequestCreateDefaults.title(messages, remoteHeadName(state)), _draft.value)
        _title.compareAndSet(title, Generated(text, true))
      }
      val description = _description.value
      if (description.generated) {
        _description.compareAndSet(description, Generated(GiteaPullRequestCreateDefaults.description(messages, template), true))
      }
    }
  }

  /** Default branch of the base on its remote; head = current branch, on the remote it tracks if that's a Gitea remote too. */
  private fun initialBranchState(): GiteaPullRequestCreateBranchState? {
    val gitRepository = baseRepo.gitRepository
    val baseBranch = defaultBranch?.let { name ->
      gitRepository.branches.remoteBranches.find { it.remote == baseRepo.gitRemote && it.nameForRemoteOperations == name }
    } ?: return null
    val currentBranch = gitRepository.currentBranch ?: return null
    val trackedRemote = gitRepository.getBranchTrackInfo(currentBranch.name)?.remote
    val headRepo = knownHeadRepositories().find { it.gitRemote == trackedRemote } ?: baseRepo
    return GiteaPullRequestCreateBranchState(baseRepo, baseBranch, headRepo, currentBranch)
  }

  private suspend fun findExistingPullRequest(state: GiteaPullRequestCreateBranchState): GiteaPullRequestDTO? =
    try {
      api.findOpenPullRequest(baseRepository, state.baseBranch.nameForRemoteOperations, state.headRepo.repository.path, remoteHeadName(state))
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      LOG.warn("Could not look for an existing pull request", e)
      null
    }

  /** Head and base the same branch, or no commits between them. */
  private fun requirementsError(state: GiteaPullRequestCreateBranchState?,
                                comparison: Result<GiteaPullRequestCreateComparison>?): GiteaPullRequestCreateError? {
    state ?: return null
    if (state.headRepo.repository == state.baseRepo.repository && remoteHeadName(state) == state.baseBranch.nameForRemoteOperations) {
      return GiteaPullRequestCreateError.WRONG_DIRECTION
    }
    return GiteaPullRequestCreateError.NO_CHANGES.takeIf { comparison?.getOrNull()?.commits?.isEmpty() == true }
  }

  private fun createRequest(state: GiteaPullRequestCreateBranchState, remoteBranch: GitRemoteBranch, draft: Boolean): GiteaCreatePullRequestRequest {
    val headName = remoteBranch.nameForRemoteOperations
    // a branch of another repository (fork) is `owner:branch`
    val head = when (val headPath = state.headRepo.repository.path) {
      baseRepository -> headName
      else -> "${headPath.owner}:$headName"
    }
    return GiteaCreatePullRequestRequest(
      title = GiteaPullRequestCreateDefaults.withDraft(titleText.value.ifBlank { headName }, draft),
      body = descriptionText.value.ifBlank { null },
      head = head,
      base = state.baseBranch.nameForRemoteOperations,
      assignees = assignees.value.map { it.login },
      reviewers = reviewers.value.map { it.login },
      labels = labels.value.map { it.id },
      milestone = milestone.value?.id,
    )
  }

  /** A remote head as is; a local one is pushed when it has no push target or differs from it. `null` if cancelled. */
  private suspend fun findOrPushRemoteBranch(state: GiteaPullRequestCreateBranchState): GitRemoteBranch? =
    when (val head = state.headBranch) {
      is GitRemoteBranch -> head
      is GitLocalBranch -> withContext(Dispatchers.EDT) {
        val messages = GitPushUtil.BranchNameInputDialogMessages(
          GiteaBundle.message("pull.request.create.push.dialog.title"),
          GiteaBundle.message("pull.request.create.push.dialog.message"),
          GiteaBundle.message("pull.request.create.push.dialog.comment"),
        )
        GitPushUtil.findOrPushRemoteBranch(project, progressIndicator, state.headRepo.gitRepository, state.headRepo.gitRemote, head, messages)
      }.await()
      else -> null
    }

  /** Name of the head branch on its remote: a local branch's push target, else the local name. */
  private fun remoteHeadName(state: GiteaPullRequestCreateBranchState): String = when (val head = state.headBranch) {
    is GitRemoteBranch -> head.nameForRemoteOperations
    is GitLocalBranch -> GitPushUtil.findPushTarget(state.headRepo.gitRepository, state.headRepo.gitRemote, head)?.branch?.nameForRemoteOperations
                         ?: head.name
    else -> head.name
  }

  private data class Revisions(val base: String, val head: String)

  private fun revisions(state: GiteaPullRequestCreateBranchState): Revisions? {
    val info = state.baseRepo.gitRepository.info
    val head = when (val branch = state.headBranch) {
      is GitRemoteBranch -> info.remoteBranchesWithHashes[branch]
      else -> info.localBranchesWithHashes[branch]
    } ?: return null
    val base = info.remoteBranchesWithHashes[state.baseBranch] ?: return null
    return Revisions(base.asString(), head.asString())
  }

  private fun compare(revisions: Revisions): GiteaPullRequestCreateComparison {
    val gitRepository = baseRepo.gitRepository
    val commits = collectCommitsBetween(project, gitRepository.root, revisions.base, revisions.head)
    if (commits.isEmpty()) {
      return GiteaPullRequestCreateComparison(emptyList(), emptyList())
    }
    val mergeBase = GitHistoryUtils.getMergeBase(project, gitRepository.root, revisions.base, revisions.head)?.asString() ?: revisions.base
    return GiteaPullRequestCreateComparison(commits, diff(mergeBase, revisions.head))
  }

  private fun diff(from: String, to: String): List<RefComparisonChange> =
    GitChangeUtils.getDiff(baseRepo.gitRepository, from, to, true).orEmpty().map { change ->
      RefComparisonChange(GitRevisionNumber(from), change.beforeRevision?.file, GitRevisionNumber(to), change.afterRevision?.file)
    }

  private data class Generated(val text: String, val generated: Boolean)
}
