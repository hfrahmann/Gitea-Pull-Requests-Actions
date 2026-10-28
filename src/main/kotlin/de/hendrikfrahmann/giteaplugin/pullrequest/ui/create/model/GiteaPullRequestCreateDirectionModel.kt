package de.hendrikfrahmann.giteaplugin.pullrequest.ui.create.model

import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectMapping
import git4idea.GitBranch
import git4idea.GitRemoteBranch
import git4idea.ui.branch.MergeDirectionModel

/** Model of git4idea's base ← head chooser, kept in sync with [GiteaPullRequestCreateViewModel.branchState]. */
class GiteaPullRequestCreateDirectionModel(private val createVm: GiteaPullRequestCreateViewModel) : MergeDirectionModel<GiteaProjectMapping> {
  private val listeners = mutableListOf<() -> Unit>()

  override val baseRepo: GiteaProjectMapping = createVm.baseRepo

  override var baseBranch: GitRemoteBranch? = null
    set(value) {
      field = value
      changed()
    }

  override var headRepo: GiteaProjectMapping? = null
    private set
  override var headBranch: GitBranch? = null
    private set
  override var headSetByUser: Boolean = false

  /** The current choice, `null` while incomplete. */
  val state: GiteaPullRequestCreateBranchState?
    get() = GiteaPullRequestCreateBranchState(baseRepo, baseBranch ?: return null, headRepo ?: return null, headBranch ?: return null)

  fun addDirectionChangesListener(listener: () -> Unit) {
    listeners += listener
  }

  override fun addAndInvokeDirectionChangesListener(listener: () -> Unit) {
    listeners += listener
    listener()
  }

  override fun getKnownRepoMappings(): List<GiteaProjectMapping> = createVm.knownHeadRepositories()

  override fun setHead(repo: GiteaProjectMapping?, branch: GitBranch?) {
    headRepo = repo
    headBranch = branch
    changed()
  }

  /** Takes over [state] (from the view model); listeners see the intermediate base-only change too. */
  fun reset(state: GiteaPullRequestCreateBranchState) {
    baseBranch = state.baseBranch
    setHead(state.headRepo, state.headBranch)
  }

  private fun changed() = listeners.forEach { it() }
}
