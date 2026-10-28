package de.hendrikfrahmann.giteaplugin.pullrequest.data

import com.intellij.openapi.diagnostic.logger
import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaReactionDTO
import de.hendrikfrahmann.giteaplugin.api.request.GiteaReactionTarget
import de.hendrikfrahmann.giteaplugin.api.request.addReaction
import de.hendrikfrahmann.giteaplugin.api.request.loadReactions
import de.hendrikfrahmann.giteaplugin.api.request.removeReaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val LOG = logger<GiteaReactionsModel>()

/** Reactions of one comment (or the PR description), loaded when created; [toggle] adds or removes the user's own. */
class GiteaReactionsModel(
  cs: CoroutineScope,
  private val api: GiteaApi,
  private val repository: GiteaRepositoryPath,
  private val target: GiteaReactionTarget,
  /** `null` without a login (anonymous connection): nothing is own. */
  private val currentUserLogin: String?,
) {
  private val mutex = Mutex()
  private val _reactions = MutableStateFlow<List<GiteaReactionDTO>>(emptyList())
  val reactions: StateFlow<List<GiteaReactionDTO>> = _reactions.asStateFlow()

  init {
    cs.launch { load() }
  }

  fun isOwn(content: String): Boolean =
    currentUserLogin != null && reactions.value.any { it.content == content && it.user?.login.equals(currentUserLogin, ignoreCase = true) }

  /** Adds [content] for the current user, or removes it if they already reacted with it; errors are rethrown after reloading. */
  suspend fun toggle(content: String) {
    try {
      if (isOwn(content)) {
        api.removeReaction(repository, target, content)
      }
      else {
        api.addReaction(repository, target, content)
      }
    }
    finally {
      load()
    }
  }

  private suspend fun load() = mutex.withLock {
    try {
      _reactions.value = api.loadReactions(repository, target)
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      LOG.info("Could not load the reactions of $target", e)
    }
  }

  companion object {
    /** Gitea's default `[ui] REACTIONS`, used when the server's list can't be loaded. */
    val DEFAULT_REACTIONS: List<String> = listOf("+1", "-1", "laugh", "hooray", "confused", "heart", "rocket", "eyes")

    private val EMOJI = mapOf(
      "+1" to "👍", "-1" to "👎", "laugh" to "😄", "hooray" to "🎉",
      "confused" to "😕", "heart" to "❤️", "rocket" to "🚀", "eyes" to "👀",
    )

    /** The emoji of a reaction name; custom names (server config) are shown as `:name:`. */
    fun emoji(content: String): String = EMOJI[content] ?: ":$content:"
  }
}
