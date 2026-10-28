package de.hendrikfrahmann.giteaplugin.refresh

import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.wm.IdeFrame
import de.hendrikfrahmann.giteaplugin.settings.GiteaSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val LOG = logger<GiteaRefreshScheduler>()

/**
 * When pollers ask the server again: every subscriber waits its own interval – fast while something it shows is
 * queued or running, slow otherwise – or until it is woken up, and not at all while the IDE is in the background (the
 * next refresh then runs when an IDE window is focused again). Debug logging (`#de.hendrikfrahmann.giteaplugin.refresh`)
 * shows every wait and pause.
 */
class GiteaRefreshScheduler(
  private val ideActive: StateFlow<Boolean>,
  private val pauseInBackground: StateFlow<Boolean>,
  private val fast: () -> Duration,
  private val slow: () -> Duration,
) {
  /** The adaptive interval: [fast] while [active], [slow] otherwise. */
  fun interval(active: Boolean): Duration = when {
    active -> fast()
    else -> slow()
  }

  /** Waits for [interval] or until [wakeUp] returns, then while polling is paused. */
  suspend fun awaitNextPoll(subscriber: String, interval: Duration, wakeUp: (suspend () -> Unit)? = null) {
    LOG.debug { "$subscriber: next refresh in $interval" }
    val wokenUp = withTimeoutOrNull(interval) { wakeUp?.invoke() ?: awaitCancellation() } != null
    if (wokenUp) {
      LOG.debug { "$subscriber: woken up" }
    }
    awaitPollingAllowed(subscriber)
  }

  /** [awaitNextPoll] with the adaptive [interval]. */
  suspend fun awaitNextPoll(subscriber: String, active: Boolean, wakeUp: (suspend () -> Unit)? = null) =
    awaitNextPoll(subscriber, interval(active), wakeUp)

  /** Returns at once in the foreground or when pausing is switched off; otherwise when either changes. */
  suspend fun awaitPollingAllowed(subscriber: String) {
    if (isPollingAllowed()) {
      return
    }
    LOG.debug { "$subscriber: refresh paused while the IDE is in the background" }
    combine(ideActive, pauseInBackground) { active, pause -> active || !pause }.first { it }
    LOG.debug { "$subscriber: refresh resumed" }
  }

  fun isPollingAllowed(): Boolean = ideActive.value || !pauseInBackground.value
}

/** The application's [GiteaRefreshScheduler], fed by IDE activation and [GiteaSettings]. */
@Service(Service.Level.APP)
class GiteaRefreshService(cs: CoroutineScope) {
  private val ideActive = MutableStateFlow(ApplicationManager.getApplication().isActive)

  /** `true` while an IDE window has the focus. */
  val ideActiveState: StateFlow<Boolean> = ideActive.asStateFlow()

  val scheduler: GiteaRefreshScheduler = GiteaRefreshScheduler(
    ideActive, GiteaSettings.getInstance().pauseRefreshInBackgroundState,
    fast = { GiteaSettings.getInstance().refreshFastSeconds.seconds },
    slow = { GiteaSettings.getInstance().refreshSlowSeconds.seconds },
  )

  init {
    ApplicationManager.getApplication().messageBus.connect(cs).subscribe(ApplicationActivationListener.TOPIC, object : ApplicationActivationListener {
      override fun applicationActivated(ideFrame: IdeFrame) {
        ideActive.value = true
      }

      override fun applicationDeactivated(ideFrame: IdeFrame) {
        ideActive.value = false
      }
    })
  }

  companion object {
    fun getInstance(): GiteaRefreshService = service()

    /** Shortcut for `getInstance().scheduler`. */
    val scheduler: GiteaRefreshScheduler get() = getInstance().scheduler
  }
}
