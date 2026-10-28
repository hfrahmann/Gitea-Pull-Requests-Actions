package de.hendrikfrahmann.giteaplugin.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.authentication.GiteaOAuthService
import de.hendrikfrahmann.giteaplugin.repository.GiteaHostMappings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Files
import java.nio.file.Path

/** What runs local workflow runs. */
enum class GiteaLocalRunner { DOCKER, BINARY }

/** Application-level Gitea settings (`gitea.xml`). Values are range-checked on read and write. */
@Service(Service.Level.APP)
@State(name = "GiteaSettings", storages = [Storage("gitea.xml")], category = SettingsCategory.TOOLS)
class GiteaSettings : PersistentStateComponent<GiteaSettings.SettingsState> {
  class SettingsState {
    /**
     * OAuth client ids for servers where the built-in `tea` app is disabled, one `<server url>=<client id>` per line.
     * The OAuth app must allow the redirect URI `http://127.0.0.1` (any port) and be a public client.
     */
    var oauthClientIds: String = ""

    /** Git remote hosts and the Gitea server behind them, see [GiteaHostMappings]. */
    var hostMappings: String = ""

    /** Clone and share with the SSH URL instead of HTTPS. */
    var cloneUsingSsh: Boolean = false

    /** Show review threads in regular editors when the current branch is a PR's head branch. */
    var reviewModeInEditors: Boolean = true

    /** Connect timeout of Gitea API requests. */
    var connectionTimeoutSeconds: Int = 20

    /** Offer every user of the instance as reviewer, not just users with write access. */
    var reviewersFromAllUsers: Boolean = false

    /** Enter / double-click in the PR list also opens the PR's timeline in an editor tab, like the GitHub plugin. */
    var openTimelineFromList: Boolean = true

    /** Show system events (pushes, labels, title changes, …) in PR timelines, not just comments and reviews. */
    var showTimelineEvents: Boolean = true

    /** Notify when a Gitea Actions run of the current branch finishes. */
    var notifyRunFinished: Boolean = true

    /** Where artifacts and run logs are downloaded to; empty = the user's Downloads folder. */
    var artifactsDirectory: String = ""

    /** Unpack downloaded artifacts into a folder next to the zip. */
    var extractArtifacts: Boolean = false

    /** Server preset in login dialogs; empty = gitea.com. */
    var defaultServerUrl: String = ""

    /** Runs per request in the Actions tool window and the current-branch runs; Gitea caps it at 50. */
    var runsPageSize: Int = 30

    /** Refresh interval while a visible run or check is queued or running. */
    var refreshFastSeconds: Int = 10

    /** Refresh interval otherwise. */
    var refreshSlowSeconds: Int = 60

    /** No polling while no IDE window is focused; the next refresh runs when the IDE is activated again. */
    var pauseRefreshInBackground: Boolean = true

    /** Accept `-rc`/`+dev` builds; feature gates treat a pre-release of X as X. */
    var acceptPreReleases: Boolean = false

    /** Default runner of local workflow runs: Docker image or binary; run configurations can override it. */
    var localRunner: GiteaLocalRunner = GiteaLocalRunner.DOCKER

    /** `docker` executable; empty = from `PATH`. */
    var dockerExecutable: String = ""

    /** Image with `gitea-runner`, for [GiteaLocalRunner.DOCKER]. */
    var runnerImage: String = DEFAULT_RUNNER_IMAGE

    /** `gitea-runner` (or `act_runner`) binary for [GiteaLocalRunner.BINARY]; empty = from `PATH`. */
    var runnerBinary: String = ""

    /** Image the jobs run in (`exec --image`); empty = the runner's default. */
    var jobImage: String = ""
  }

  private var state = SettingsState()

  override fun getState(): SettingsState = state

  override fun loadState(state: SettingsState) {
    this.state = state
    hostMappingsFlow.value = GiteaHostMappings.parse(state.hostMappings)
    reviewModeFlow.value = state.reviewModeInEditors
    timelineEventsFlow.value = state.showTimelineEvents
    pauseRefreshFlow.value = state.pauseRefreshInBackground
  }

  private val hostMappingsFlow = MutableStateFlow(GiteaHostMappings.EMPTY)

  /** Parsed [SettingsState.hostMappings]; changes are picked up by the repository discovery right away. */
  val hostMappings: StateFlow<GiteaHostMappings> = hostMappingsFlow.asStateFlow()

  var hostMappingsText: String
    get() = state.hostMappings
    set(value) {
      state.hostMappings = value.trim()
      hostMappingsFlow.value = GiteaHostMappings.parse(state.hostMappings)
    }

  var oauthClientIdsText: String
    get() = state.oauthClientIds
    set(value) {
      state.oauthClientIds = value.trim()
    }

  var cloneUsingSsh: Boolean
    get() = state.cloneUsingSsh
    set(value) {
      state.cloneUsingSsh = value
    }

  private val reviewModeFlow = MutableStateFlow(true)

  /** See [SettingsState.reviewModeInEditors]; editors follow changes right away. */
  val reviewModeInEditorsState: StateFlow<Boolean> = reviewModeFlow.asStateFlow()

  var reviewModeInEditors: Boolean
    get() = state.reviewModeInEditors
    set(value) {
      state.reviewModeInEditors = value
      reviewModeFlow.value = value
    }

  var connectionTimeoutSeconds: Int
    get() = state.connectionTimeoutSeconds.coerceIn(CONNECTION_TIMEOUT_RANGE)
    set(value) {
      state.connectionTimeoutSeconds = value.coerceIn(CONNECTION_TIMEOUT_RANGE)
    }

  var reviewersFromAllUsers: Boolean
    get() = state.reviewersFromAllUsers
    set(value) {
      state.reviewersFromAllUsers = value
    }

  var openTimelineFromList: Boolean
    get() = state.openTimelineFromList
    set(value) {
      state.openTimelineFromList = value
    }

  private val timelineEventsFlow = MutableStateFlow(true)

  /** See [SettingsState.showTimelineEvents]; open timelines follow changes right away. */
  val showTimelineEventsState: StateFlow<Boolean> = timelineEventsFlow.asStateFlow()

  var showTimelineEvents: Boolean
    get() = state.showTimelineEvents
    set(value) {
      state.showTimelineEvents = value
      timelineEventsFlow.value = value
    }

  var notifyRunFinished: Boolean
    get() = state.notifyRunFinished
    set(value) {
      state.notifyRunFinished = value
    }

  var artifactsDirectory: String
    get() = state.artifactsDirectory
    set(value) {
      state.artifactsDirectory = value.trim()
    }

  var extractArtifacts: Boolean
    get() = state.extractArtifacts
    set(value) {
      state.extractArtifacts = value
    }

  var defaultServerUrl: String
    get() = state.defaultServerUrl
    set(value) {
      state.defaultServerUrl = value.trim()
    }

  /** [defaultServerUrl], or gitea.com when it's empty or invalid. */
  val defaultServer: String
    get() = state.defaultServerUrl.takeIf { runCatching { GiteaServerPath.fromUserInput(it) }.isSuccess } ?: GiteaServerPath.DEFAULT_SERVER_URL

  var runsPageSize: Int
    get() = state.runsPageSize.coerceIn(RUNS_PAGE_SIZE_RANGE)
    set(value) {
      state.runsPageSize = value.coerceIn(RUNS_PAGE_SIZE_RANGE)
    }

  var refreshFastSeconds: Int
    get() = state.refreshFastSeconds.coerceIn(REFRESH_FAST_RANGE)
    set(value) {
      state.refreshFastSeconds = value.coerceIn(REFRESH_FAST_RANGE)
    }

  var refreshSlowSeconds: Int
    get() = state.refreshSlowSeconds.coerceIn(REFRESH_SLOW_RANGE)
    set(value) {
      state.refreshSlowSeconds = value.coerceIn(REFRESH_SLOW_RANGE)
    }

  private val pauseRefreshFlow = MutableStateFlow(true)

  /** See [SettingsState.pauseRefreshInBackground]; paused refreshes follow changes right away. */
  val pauseRefreshInBackgroundState: StateFlow<Boolean> = pauseRefreshFlow.asStateFlow()

  var pauseRefreshInBackground: Boolean
    get() = state.pauseRefreshInBackground
    set(value) {
      state.pauseRefreshInBackground = value
      pauseRefreshFlow.value = value
    }

  var acceptPreReleases: Boolean
    get() = state.acceptPreReleases
    set(value) {
      state.acceptPreReleases = value
    }

  var localRunner: GiteaLocalRunner
    get() = state.localRunner
    set(value) {
      state.localRunner = value
    }

  var dockerExecutable: String
    get() = state.dockerExecutable
    set(value) {
      state.dockerExecutable = value.trim()
    }

  var runnerImage: String
    get() = state.runnerImage.ifBlank { DEFAULT_RUNNER_IMAGE }
    set(value) {
      state.runnerImage = value.trim()
    }

  var runnerBinary: String
    get() = state.runnerBinary
    set(value) {
      state.runnerBinary = value.trim()
    }

  var jobImage: String
    get() = state.jobImage
    set(value) {
      state.jobImage = value.trim()
    }

  /** [artifactsDirectory], or `~/Downloads` (the home folder if there is none). */
  val artifactsDirectoryPath: Path
    get() {
      val home = Path.of(System.getProperty("user.home"))
      val configured = state.artifactsDirectory.takeIf { it.isNotBlank() }?.let { expandHome(it, home) }
      return configured ?: home.resolve("Downloads").takeIf { Files.isDirectory(it) } ?: home
    }

  fun oauthClientIdFor(server: GiteaServerPath): String =
    parseClientIds(state.oauthClientIds)[server] ?: GiteaOAuthService.BUILT_IN_CLIENT_ID

  companion object {
    const val DEFAULT_RUNNER_IMAGE: String = "gitea/runner:latest"

    val CONNECTION_TIMEOUT_RANGE: IntRange = 1..300
    val RUNS_PAGE_SIZE_RANGE: IntRange = 5..50
    val REFRESH_FAST_RANGE: IntRange = 2..300
    val REFRESH_SLOW_RANGE: IntRange = 10..3600

    fun getInstance(): GiteaSettings = service()

    /** Lines `server=clientId`; invalid lines are skipped (the settings page validates them). */
    fun parseClientIds(text: String): Map<GiteaServerPath, String> = text.lines().mapNotNull(::parseClientIdLine).toMap()

    /** One `server=clientId` line, `null` if it isn't valid. */
    fun parseClientIdLine(line: String): Pair<GiteaServerPath, String>? {
      val server = line.substringBefore('=', "").trim()
      val clientId = line.substringAfter('=', "").trim()
      if (server.isEmpty() || clientId.isEmpty() || clientId.any(Char::isWhitespace)) {
        return null
      }
      return runCatching { GiteaServerPath.fromUserInput(server) }.getOrNull()?.let { it to clientId }
    }

    /** `~` and `~/…` are relative to [home]. */
    private fun expandHome(path: String, home: Path): Path = when {
      path == "~" || path.startsWith("~/") -> home.resolve(path.removePrefix("~").removePrefix("/"))
      else -> Path.of(path)
    }
  }
}
