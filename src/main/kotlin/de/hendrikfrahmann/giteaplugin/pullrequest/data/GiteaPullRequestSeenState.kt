package de.hendrikfrahmann.giteaplugin.pullrequest.data

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.MapAnnotation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * When each PR (`server owner/name#n`, see [GiteaPullRequestViewedState.key]) was last opened, as epoch millis – for
 * the "unseen" dot in the list and on the tool window icon, like GitHub's. Gitea has no read state for PRs, so it
 * stays local.
 */
@Service(Service.Level.PROJECT)
@State(name = "GiteaPullRequestSeenState", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)], reportStatistic = false)
class GiteaPullRequestSeenState : PersistentStateComponent<GiteaPullRequestSeenState.SeenState> {
  class SeenState {
    @get:MapAnnotation(surroundWithTag = false)
    var lastSeen: MutableMap<String, Long> = mutableMapOf()
  }

  private var state = SeenState()
  private val _updates = MutableStateFlow(0)

  /** Changes whenever a PR is marked as seen. */
  val updates: StateFlow<Int> = _updates.asStateFlow()

  override fun getState(): SeenState = synchronized(this) { state }

  override fun loadState(state: SeenState) {
    synchronized(this) { this.state = state }
    _updates.update { it + 1 }
  }

  fun lastSeen(pullRequestKey: String): Long? = synchronized(this) { state.lastSeen[pullRequestKey] }

  /**
   * Seen now – or at [updatedAt] if the server's clock is ahead, so the PR doesn't look updated again right away.
   * Entries not touched for [FORGET_AFTER_DAYS] are dropped.
   */
  fun markSeen(pullRequestKey: String, updatedAt: Date?) {
    val now = System.currentTimeMillis()
    val seen = maxOf(now, updatedAt?.time ?: 0)
    synchronized(this) {
      val old = state.lastSeen[pullRequestKey]
      if (old != null && old >= seen) {
        return
      }
      state.lastSeen[pullRequestKey] = seen
      state.lastSeen.values.removeIf { it < now - TimeUnit.DAYS.toMillis(FORGET_AFTER_DAYS) }
    }
    _updates.update { it + 1 }
  }

  companion object {
    private const val FORGET_AFTER_DAYS = 180L

    fun getInstance(project: Project): GiteaPullRequestSeenState = project.service()
  }
}
