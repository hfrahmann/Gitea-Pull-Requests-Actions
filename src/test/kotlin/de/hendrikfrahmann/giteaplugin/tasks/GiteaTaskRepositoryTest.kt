package de.hendrikfrahmann.giteaplugin.tasks

import com.intellij.openapi.util.JDOMUtil
import com.intellij.tasks.TaskRepositoryType
import com.intellij.tasks.TaskType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountCredentials
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountManager
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.net.URLDecoder

class GiteaTaskRepositoryTest : BasePlatformTestCase() {
  private lateinit var mock: MockWebServer
  private lateinit var account: GiteaAccount
  private val manager get() = GiteaAccountManager.getInstance()

  override fun setUp() {
    super.setUp()
    mock = MockWebServer()
    mock.start()
    account = GiteaAccount("intellij-dev", GiteaServerPath(mock.url("/").toString()))
    runBlocking { manager.updateAccount(account, GiteaAccountCredentials.Token("t")) }
  }

  override fun tearDown() {
    try {
      runBlocking { manager.updateAccounts(emptyMap()) }
      mock.close()
    }
    finally {
      super.tearDown()
    }
  }

  private fun json(body: String, code: Int = 200) =
    MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

  private fun issue(number: Int, state: String = "open", labels: String = "[]") =
    """{"id":$number,"number":$number,"title":"Issue $number","body":"Body $number","state":"$state","labels":$labels,
       "created_at":"2026-10-20T10:00:00Z","updated_at":"2026-10-21T10:00:00Z"}"""

  private fun repository() = (type().createRepository() as GiteaTaskRepository).apply {
    accountId = account.id
    repositoryPath = "intellij-dev/plugin-playground"
  }

  private fun type() = TaskRepositoryType.getRepositoryTypes().filterIsInstance<GiteaTaskRepositoryType>().single()

  private fun takeQuery(): Pair<String, Map<String, String>> {
    val request = mock.takeRequest()
    val target = request.target
    val params = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
      URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
    }
    return "${request.method} ${target.substringBefore('?')}" to params
  }

  fun `test gitea is offered as tasks server type`() {
    assertEquals("Gitea", type().name)
    assertFalse(GiteaTaskRepository().isConfigured)
    assertTrue(repository().isConfigured)
    assertEquals("${account.server}/intellij-dev/plugin-playground", repository().presentableName)
  }

  fun `test issues are loaded as tasks`() {
    mock.enqueue(json("[${issue(8)},${issue(7, labels = """[{"id":1,"name":"Kind/Bug"}]""")}]"))
    val repository = repository().apply { assignedToMeOnly = true }
    val tasks = repository.getIssues("greeting", 0, 20, false)

    val (path, params) = takeQuery()
    assertEquals("GET /api/v1/repos/intellij-dev/plugin-playground/issues", path)
    assertEquals(mapOf("type" to "issues", "state" to "open", "q" to "greeting", "assigned_by" to "intellij-dev", "page" to "1", "limit" to "20"),
                 params)
    assertEquals(listOf("plugin-playground-8", "plugin-playground-7"), tasks.map { it.id })
    val task = tasks[1]
    assertEquals("7", task.number)
    assertEquals("Issue 7", task.summary)
    assertEquals("Body 7", task.description)
    assertEquals(TaskType.BUG, task.type)
    assertFalse(task.isClosed)
    assertTrue(task.isIssue)
    assertEquals("${account.server.uri}/intellij-dev/plugin-playground/issues/7", task.issueUrl)
  }

  fun `test find task, extract id and change state`() {
    val repository = repository()
    assertEquals("plugin-playground-12", repository.extractId("plugin-playground-12 fix greeting"))
    assertNull(repository.extractId("other-repo-12"))

    mock.enqueue(json(issue(12, state = "closed")))
    val task = repository.findTask("plugin-playground-12")!!
    assertEquals("GET /api/v1/repos/intellij-dev/plugin-playground/issues/12", takeQuery().first)
    assertTrue(task.isClosed)

    mock.enqueue(json("""{"message":"not found"}""", 404))
    assertNull(repository.findTask("plugin-playground-99"))
    takeQuery()

    mock.enqueue(json(issue(12)))
    assertEquals(setOf("open", "closed"), repository.getAvailableTaskStates(task).map { it.id }.toSet())
    repository.setTaskState(task, GiteaTaskRepository.openState())
    val request = mock.takeRequest()
    assertEquals("PATCH", request.method)
    assertEquals("""{"state":"open"}""", request.body!!.utf8())
  }

  fun `test server errors are shown with gitea's message`() {
    mock.enqueue(json("""{"message":"user does not have permission"}""", 403))
    val e = runCatching { repository().getIssues(null, 0, 10, true) }.exceptionOrNull()
    assertNotNull(e)
    assertTrue(e!!.message, e.message!!.contains("user does not have permission"))
  }

  fun `test settings survive xml without secrets`() {
    val repository = repository().apply { assignedToMeOnly = true }
    val xml = JDOMUtil.write(XmlSerializer.serialize(repository))
    assertTrue(xml, xml.startsWith("<Gitea"))
    val restored = XmlSerializer.deserialize(JDOMUtil.load(xml), GiteaTaskRepository::class.java)
    assertEquals(account.id, restored.accountId)
    assertEquals("intellij-dev/plugin-playground", restored.repositoryPath)
    assertTrue(restored.assignedToMeOnly)
    assertEquals(repository.clone(), repository)
    assertEquals("plugin-playground-3", restored.extractId("plugin-playground-3"))
  }

  fun `test pages are capped at 50 and sliced by offset`() = runBlocking {
    val requested = mutableListOf<Pair<Int, Int>>()
    val all = (1..120).toList()
    val load: suspend (Int, Int) -> List<Int> = { page, size ->
      requested += page to size
      all.drop((page - 1) * size).take(size)
    }
    assertEquals((1..100).toList(), GiteaTaskRepository.loadIssues(100, 0, load))
    assertEquals(listOf(1 to 50, 2 to 50), requested)

    requested.clear()
    assertEquals((31..40).toList(), GiteaTaskRepository.loadIssues(10, 30, load))
    assertEquals(listOf(4 to 10), requested)

    requested.clear()
    assertEquals((101..120).toList(), GiteaTaskRepository.loadIssues(50, 100, load))
    assertEquals(listOf(3 to 50), requested)
  }
}
