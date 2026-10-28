package de.hendrikfrahmann.giteaplugin.tasks

import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.openapi.util.text.StringUtil
import com.intellij.tasks.Comment
import com.intellij.tasks.CustomTaskState
import com.intellij.tasks.Task
import com.intellij.tasks.TaskRepository
import com.intellij.tasks.TaskRepositoryType
import com.intellij.tasks.TaskType
import com.intellij.tasks.impl.BaseRepository
import com.intellij.util.xmlb.annotations.Tag
import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaApiManager
import de.hendrikfrahmann.giteaplugin.api.GiteaHttpStatusError
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaIssueDTO
import de.hendrikfrahmann.giteaplugin.api.nullOnHttpStatus
import de.hendrikfrahmann.giteaplugin.api.request.getIssue
import de.hendrikfrahmann.giteaplugin.api.request.getRepository
import de.hendrikfrahmann.giteaplugin.api.request.loadIssuesPage
import de.hendrikfrahmann.giteaplugin.api.request.setIssueState
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountManager
import de.hendrikfrahmann.giteaplugin.authentication.accounts.getClient
import de.hendrikfrahmann.giteaplugin.ui.action.GiteaWebUrls
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import de.hendrikfrahmann.giteaplugin.util.GiteaIcons
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import java.util.Date
import javax.swing.Icon

/**
 * Issues of one Gitea repository as tasks. Authenticates with a Gitea **account** of the plugin (its token stays in
 * the plugin's credential store) instead of a password of its own, so [getPassword] is unused. The Tasks API is blocking
 * and called off the EDT; requests go through our [GiteaApi].
 *
 * Task ids are `<repository name>-<number>` like GitHub's, so branch and changelist names stay readable.
 */
@Tag("Gitea")
class GiteaTaskRepository : BaseRepository {
  /** [GiteaAccount.id]; per user, so a shared repository needs an account on the same server in every project. */
  var accountId: String = ""

  /** `owner/name` */
  var repositoryPath: String = ""
    set(value) {
      field = value.trim()
      idPattern = repositoryPath()?.let { Regex("\\b(" + Regex.escape(it.name) + "-\\d+)\\b") }
    }

  var assignedToMeOnly: Boolean = false

  private var idPattern: Regex? = null

  /** Serialization */
  constructor() : super()

  constructor(type: TaskRepositoryType<*>) : super(type)

  private constructor(other: GiteaTaskRepository) : super(other) {
    accountId = other.accountId
    repositoryPath = other.repositoryPath
    assignedToMeOnly = other.assignedToMeOnly
  }

  override fun clone(): BaseRepository = GiteaTaskRepository(this)

  fun account(): GiteaAccount? = GiteaAccountManager.getInstance().accountsState.value.find { it.id == accountId && !it.isAnonymous }

  fun repositoryPath(): GiteaRepositoryPath? = GiteaRepositoryPath.parse(repositoryPath)

  override fun isConfigured(): Boolean = account() != null && repositoryPath() != null

  override fun getPresentableName(): String {
    val server = account()?.server?.toString() ?: getUrl().orEmpty()
    return listOf(server, repositoryPath).filter { it.isNotBlank() }.joinToString("/").ifEmpty { super.getPresentableName() }
  }

  override fun getIssues(query: String?, offset: Int, limit: Int, withClosed: Boolean): Array<Task> = request { api, account, repository ->
    loadIssues(limit, offset) { page, pageSize ->
      api.loadIssuesPage(repository, withClosed, query, account.name.takeIf { assignedToMeOnly }, page, pageSize)
    }.map { createTask(it, repository, account) }.toTypedArray()
  }

  override fun findTask(id: String): Task? {
    val number = id.substringAfterLast('-').toLongOrNull() ?: return null
    return request { api, account, repository ->
      nullOnHttpStatus { createTask(api.getIssue(repository, number), repository, account) }
    }
  }

  override fun extractId(taskName: String): String? = idPattern?.find(taskName)?.groupValues?.get(1)

  /** Gitea issues are open or closed – offered as "Open" / "Closed" when a task is started or finished. */
  override fun getAvailableTaskStates(task: Task): Set<CustomTaskState> = setOf(openState(), closedState())

  override fun setTaskState(task: Task, state: CustomTaskState) {
    val open = when (state.id) {
      OPEN_STATE_ID -> true
      CLOSED_STATE_ID -> false
      else -> throw IllegalArgumentException("Unsupported state: ${state.id}")
    }
    val number = task.number.toLongOrNull() ?: return
    request { api, _, repository -> api.setIssueState(repository, number, open) }
  }

  override fun createCancellableConnection(): CancellableConnection = object : CancellableConnection() {
    @Volatile
    private var job: Job? = null

    override fun doTest() {
      request { api, _, repository ->
        job = currentCoroutineContext().job
        if (!api.getRepository(repository).hasIssues) {
          throw Exception(GiteaBundle.message("tasks.issues.disabled", repository))
        }
      }
    }

    override fun cancel() {
      job?.cancel()
    }
  }

  override fun getFeatures(): Int = super.getFeatures() or TaskRepository.STATE_UPDATING

  /** Runs [block] with the account's client; Gitea's message becomes the exception text shown by the Tasks UI. */
  private fun <T> request(block: suspend (GiteaApi, GiteaAccount, GiteaRepositoryPath) -> T): T {
    val account = account() ?: throw Exception(GiteaBundle.message("tasks.account.missing"))
    val repository = repositoryPath() ?: throw Exception(GiteaBundle.message("tasks.repository.missing"))
    return runBlockingMaybeCancellable {
      try {
        block(GiteaApiManager.getInstance().getClient(account), account, repository)
      }
      catch (e: GiteaHttpStatusError) {
        throw Exception(e.localizedMessage, e)
      }
    }
  }

  private fun createTask(issue: GiteaIssueDTO, repository: GiteaRepositoryPath, account: GiteaAccount): Task =
    GiteaTask(this, issue, repository, GiteaWebUrls.issue(account.server.webUri(repository.owner, repository.name), issue.number).toString())

  override fun equals(other: Any?): Boolean =
    super.equals(other) && other is GiteaTaskRepository &&
    accountId == other.accountId && repositoryPath == other.repositoryPath && assignedToMeOnly == other.assignedToMeOnly

  override fun hashCode(): Int = StringUtil.stringHashCode(repositoryPath) + 31 * StringUtil.stringHashCode(accountId)

  companion object {
    private const val MAX_PAGE_SIZE = 50
    internal const val OPEN_STATE_ID = "open"
    internal const val CLOSED_STATE_ID = "closed"

    internal fun openState() = CustomTaskState(OPEN_STATE_ID, GiteaBundle.message("tasks.state.open"))
    internal fun closedState() = CustomTaskState(CLOSED_STATE_ID, GiteaBundle.message("tasks.state.closed"))

    /**
     * Items [offset] until `offset + limit`, loaded in pages of at most 50 (Gitea's cap) – the Tasks UI asks for 50 or
     * more from offset 0 and then further pages.
     */
    internal suspend fun <T> loadIssues(limit: Int, offset: Int, loadPage: suspend (page: Int, pageSize: Int) -> List<T>): List<T> {
      val pageSize = limit.coerceIn(1, MAX_PAGE_SIZE)
      val result = mutableListOf<T>()
      var page = offset / pageSize + 1
      var skip = offset % pageSize
      while (result.size < limit) {
        val items = loadPage(page, pageSize)
        result += items.drop(skip)
        if (items.size < pageSize) {
          break
        }
        skip = 0
        page++
      }
      return result.take(limit)
    }
  }
}

private class GiteaTask(
  private val repository: GiteaTaskRepository,
  private val issue: GiteaIssueDTO,
  private val path: GiteaRepositoryPath,
  private val url: String,
) : Task() {
  override fun getId(): String = "${path.name}-${issue.number}"
  override fun getSummary(): String = issue.title
  override fun getDescription(): String? = issue.body
  override fun getComments(): Array<Comment> = Comment.EMPTY_ARRAY
  override fun getIcon(): Icon = GiteaIcons.Gitea

  /** Gitea's default label sets name bugs `bug`/`Kind/Bug` and features `enhancement`/`feature`/`Kind/Feature`. */
  override fun getType(): TaskType {
    val names = issue.labels.map { it.name.substringAfterLast('/').lowercase() }
    return when {
      names.any { it == "bug" } -> TaskType.BUG
      names.any { it == "enhancement" || it == "feature" } -> TaskType.FEATURE
      else -> TaskType.OTHER
    }
  }

  override fun getUpdated(): Date? = issue.updatedAt
  override fun getCreated(): Date? = issue.createdAt
  override fun isClosed(): Boolean = issue.state == "closed"
  override fun isIssue(): Boolean = true
  override fun getIssueUrl(): String = url
  override fun getRepository(): TaskRepository = repository
  override fun getPresentableName(): String = "$id: $summary"
}
