package de.hendrikfrahmann.giteaplugin.refresh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class GiteaRefreshSchedulerTest {
  private val ideActive = MutableStateFlow(true)
  private val pause = MutableStateFlow(true)
  private val scheduler = GiteaRefreshScheduler(ideActive, pause, fast = { 50.milliseconds }, slow = { 10.seconds })

  @Test
  fun `interval is fast while something is active`() {
    assertEquals(50.milliseconds, scheduler.interval(active = true))
    assertEquals(10.seconds, scheduler.interval(active = false))
  }

  @Test
  fun `waits the interval or until woken up`() = runBlocking {
    val elapsed = measureTime { scheduler.awaitNextPoll("test", active = true) }
    assertTrue(elapsed >= 50.milliseconds, "$elapsed")

    val wakeUp = CompletableDeferred<Unit>()
    val waiting = async { scheduler.awaitNextPoll("test", active = false) { wakeUp.await() } }
    delay(20)
    assertFalse(waiting.isCompleted)
    wakeUp.complete(Unit)
    withTimeout(1000) { waiting.await() }
  }

  @Test
  fun `no polling in the background until the IDE is active again`() = runBlocking {
    ideActive.value = false
    assertFalse(scheduler.isPollingAllowed())
    val waiting = async { scheduler.awaitNextPoll("test", active = true) }
    delay(200)
    assertFalse(waiting.isCompleted)
    ideActive.value = true
    withTimeout(1000) { waiting.await() }
  }

  @Test
  fun `switching pausing off resumes at once`() = runBlocking {
    ideActive.value = false
    val waiting = async { scheduler.awaitPollingAllowed("test") }
    delay(50)
    assertFalse(waiting.isCompleted)
    pause.value = false
    withTimeout(1000) { waiting.await() }
    assertTrue(scheduler.isPollingAllowed())
  }
}
