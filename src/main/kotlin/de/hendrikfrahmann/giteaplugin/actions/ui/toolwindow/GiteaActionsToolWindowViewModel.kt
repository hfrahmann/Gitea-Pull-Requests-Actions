package de.hendrikfrahmann.giteaplugin.actions.ui.toolwindow

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnectionManager
import de.hendrikfrahmann.giteaplugin.util.childScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.annotations.NonNls

/** What a tab of the "Gitea Actions" tool window shows. */
sealed interface GiteaActionsTab {
  @get:NonNls val id: String
  val isClosable: Boolean get() = true

  /** Runs of the checked-out branch. */
  data object CurrentBranch : GiteaActionsTab {
    override val id: String = "CurrentBranch"
    override val isClosable: Boolean = false
  }

  /** Runs grouped by workflow. */
  data object Workflows : GiteaActionsTab {
    override val id: String = "Workflows"
    override val isClosable: Boolean = false
  }

  /** Runs for the head of PR [number]. */
  data class PullRequest(val number: Long) : GiteaActionsTab {
    override val id: String = "PullRequest!$number"
  }

  /** One run, e.g. opened from a PR's CI status or a notification. */
  data class Run(val runId: Long) : GiteaActionsTab {
    override val id: String = "Run!$runId"
  }
}

/**
 * State of the Actions tool window, kept in a project service so navigation works before the tool window was ever
 * opened: the extra (closable) tabs, which tab to select, activation requests. Uses the project's single repository
 * connection, shared with the PR tool window.
 */
@Service(Service.Level.PROJECT)
class GiteaActionsToolWindowViewModel(project: Project, parentCs: CoroutineScope) {
  private val cs = parentCs.childScope(Dispatchers.Default)

  val connection: StateFlow<GiteaProjectConnection?> = GiteaProjectConnectionManager.getInstance(project).connectionState

  private val _extraTabs = MutableStateFlow<List<GiteaActionsTab>>(emptyList())
  val extraTabs: StateFlow<List<GiteaActionsTab>> = _extraTabs.asStateFlow()

  private val _selectedTab = MutableStateFlow<GiteaActionsTab>(GiteaActionsTab.CurrentBranch)

  /** Requested selection; the tool window follows it and reports user selections back via [tabSelected]. */
  val selectedTab: StateFlow<GiteaActionsTab> = _selectedTab.asStateFlow()

  private val _activationRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  val activationRequests: Flow<Unit> = _activationRequests.asSharedFlow()

  init {
    // tabs belong to a connection: PR numbers and run ids mean nothing in another repository
    var lastId = connection.value?.id
    cs.launch {
      connection.collect { current ->
        if (current?.id == lastId) {
          return@collect
        }
        lastId = current?.id
        _extraTabs.value = emptyList()
        _selectedTab.value = GiteaActionsTab.CurrentBranch
      }
    }
  }

  /** Opens (or selects) [tab] and shows the tool window. */
  fun showTab(tab: GiteaActionsTab) {
    if (tab.isClosable) {
      _extraTabs.update {
        if (tab in it) {
          it
        }
        else {
          it + tab
        }
      }
    }
    _selectedTab.value = tab
    _activationRequests.tryEmit(Unit)
  }

  /** A job to select in the tab of run [runId] (the tab shows its log); `null` = its first job. */
  data class JobRequest(val runId: Long, val jobId: Long?)

  private val _jobRequests = MutableStateFlow<JobRequest?>(null)
  val jobRequests: StateFlow<JobRequest?> = _jobRequests.asStateFlow()

  fun requestJob(runId: Long, jobId: Long?) {
    _jobRequests.value = JobRequest(runId, jobId)
  }

  /** The tab took the request. */
  fun jobRequestHandled(request: JobRequest) {
    _jobRequests.compareAndSet(request, null)
  }

  private val _refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

  /** Every tab reloads (the tool window is shown again). */
  val refreshRequests: Flow<Unit> = _refreshRequests.asSharedFlow()

  fun refreshAll() {
    _refreshRequests.tryEmit(Unit)
  }

  fun closeTab(tab: GiteaActionsTab) {
    _extraTabs.update { it - tab }
    if (_selectedTab.value == tab) {
      _selectedTab.value = GiteaActionsTab.CurrentBranch
    }
  }

  fun tabSelected(tab: GiteaActionsTab) {
    _selectedTab.value = tab
  }

  companion object {
    fun getInstance(project: Project): GiteaActionsToolWindowViewModel = project.service()
  }
}
