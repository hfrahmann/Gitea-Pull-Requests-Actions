package de.hendrikfrahmann.giteaplugin.pullrequest.data

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.MapAnnotation
import com.intellij.util.xmlb.annotations.XCollection
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Files marked as viewed, per PR (`server owner/name#n`), as `path@sha` of the last commit that changed the file –
 * a new commit to the file makes it unviewed again. Gitea has no API for this (unlike GitHub), so it stays local.
 */
@Service(Service.Level.PROJECT)
@State(name = "GiteaPullRequestViewedState", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)], reportStatistic = false)
class GiteaPullRequestViewedState : PersistentStateComponent<GiteaPullRequestViewedState.ViewedState> {
  class ViewedState {
    @get:MapAnnotation(surroundWithTag = false)
    var pullRequests: MutableMap<String, ViewedFiles> = mutableMapOf()
  }

  class ViewedFiles {
    @get:XCollection(style = XCollection.Style.v2)
    var files: MutableSet<String> = mutableSetOf()
  }

  private var state = ViewedState()
  private val _updates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  val updates: SharedFlow<Unit> = _updates.asSharedFlow()

  override fun getState(): ViewedState = state

  override fun loadState(state: ViewedState) {
    this.state = state
  }

  fun isViewed(pullRequestKey: String, path: String, sha: String): Boolean =
    state.pullRequests[pullRequestKey]?.files?.contains("$path@$sha") == true

  fun setViewed(pullRequestKey: String, files: Collection<Pair<String, String>>, viewed: Boolean) {
    val entry = state.pullRequests.getOrPut(pullRequestKey) { ViewedFiles() }
    for ((path, sha) in files) {
      // one entry per path: drop older revisions of the same file
      entry.files.removeIf { it.substringBeforeLast('@') == path }
      if (viewed) {
        entry.files += "$path@$sha"
      }
    }
    if (entry.files.isEmpty()) {
      state.pullRequests.remove(pullRequestKey)
    }
    _updates.tryEmit(Unit)
  }

  companion object {
    fun getInstance(project: Project): GiteaPullRequestViewedState = project.service()

    fun key(server: String, repository: String, number: Long): String = "$server $repository#$number"
  }
}
