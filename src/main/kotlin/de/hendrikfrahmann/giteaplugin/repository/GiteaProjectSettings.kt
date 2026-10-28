package de.hendrikfrahmann.giteaplugin.repository

import com.intellij.collaboration.ui.codereview.diff.DiscussionsViewOption
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount

/**
 * Per-project, per-user state (workspace file): the last repository/account the user connected with and view
 * preferences. PR list filters are kept by `GiteaPullRequestsPersistentFiltersHistory`.
 */
@Service(Service.Level.PROJECT)
@State(name = "GiteaProjectSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)], reportStatistic = false)
class GiteaProjectSettings : PersistentStateComponent<GiteaProjectSettings.SettingsState> {
  class SettingsState {
    var selectedRemoteUrl: String? = null
    var selectedAccountId: String? = null
    /** [com.intellij.collaboration.ui.codereview.diff.DiscussionsViewOption] name in the review diff. */
    var diffDiscussionsViewOption: String? = null
    /** "Timestamps" in the Actions job log. */
    var actionsLogTimestamps: Boolean = false
    /** "Soft-Wrap" in the Actions job log. */
    var actionsLogSoftWraps: Boolean = false
  }

  private var state = SettingsState()

  override fun getState(): SettingsState = state

  override fun loadState(state: SettingsState) {
    this.state = state
  }

  val selectedRemoteUrl: String? get() = state.selectedRemoteUrl
  val selectedAccountId: String? get() = state.selectedAccountId

  fun rememberSelection(repo: GiteaProjectMapping, account: GiteaAccount) {
    state.selectedRemoteUrl = repo.remote.url
    state.selectedAccountId = account.id
  }

  var diffDiscussionsViewOption: DiscussionsViewOption
    get() = state.diffDiscussionsViewOption?.let { runCatching { DiscussionsViewOption.valueOf(it) }.getOrNull() } ?: DiscussionsViewOption.ALL
    set(value) {
      state.diffDiscussionsViewOption = value.name
    }

  var actionsLogTimestamps: Boolean
    get() = state.actionsLogTimestamps
    set(value) {
      state.actionsLogTimestamps = value
    }

  var actionsLogSoftWraps: Boolean
    get() = state.actionsLogSoftWraps
    set(value) {
      state.actionsLogSoftWraps = value
    }

  /** "Switch repository or account": don't reconnect automatically next time. */
  fun forgetSelection() {
    state.selectedRemoteUrl = null
    state.selectedAccountId = null
  }

  companion object {
    fun getInstance(project: Project): GiteaProjectSettings = project.service()
  }
}
