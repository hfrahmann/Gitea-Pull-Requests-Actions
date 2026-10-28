package de.hendrikfrahmann.giteaplugin.clone

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.hendrikfrahmann.giteaplugin.api.GiteaApiImpl
import de.hendrikfrahmann.giteaplugin.api.GiteaApiManager
import de.hendrikfrahmann.giteaplugin.api.GiteaCredentials
import de.hendrikfrahmann.giteaplugin.api.GiteaJson
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaRepositoryDTO
import de.hendrikfrahmann.giteaplugin.api.request.GiteaCreateRepositoryRequest
import de.hendrikfrahmann.giteaplugin.api.request.createRepository
import de.hendrikfrahmann.giteaplugin.api.request.httpCloneUrl
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountCredentials
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountManager
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.toolwindow.GiteaRepositoriesComponentFactory
import de.hendrikfrahmann.giteaplugin.share.GiteaShareDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.net.http.HttpClient
import kotlin.time.Duration.Companion.seconds

class GiteaCloneTest : BasePlatformTestCase() {
  private lateinit var mock: MockWebServer
  private lateinit var server: GiteaServerPath

  override fun setUp() {
    super.setUp()
    mock = MockWebServer()
    mock.start()
    server = GiteaServerPath(mock.url("/gitea").toString())
  }

  override fun tearDown() {
    try {
      runBlocking { GiteaAccountManager.getInstance().updateAccounts(emptyMap()) }
      GiteaApiManager.getInstance().invalidateMetadata(server)
      mock.close()
    }
    finally {
      super.tearDown()
    }
  }

  private fun json(body: String, code: Int = 200, vararg headers: Pair<String, String>) =
    MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body)
      .apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build()

  private fun repo(fullName: String, private: Boolean = true) =
    """{"id":${fullName.hashCode()},"name":"${fullName.substringAfter('/')}","full_name":"$fullName","private":$private,
       |"owner":{"id":1,"login":"${fullName.substringBefore('/')}"},
       |"clone_url":"https://one.intranet/$fullName.git","ssh_url":"git@one.intranet:$fullName.git"}""".trimMargin()

  fun `test clone urls use the configured server, ssh as given`() {
    val dto = GiteaJson.fromJson<GiteaRepositoryDTO>(repo("intellij-dev/plugin-playground"))
    assertEquals("${server.uri}/intellij-dev/plugin-playground.git", server.httpCloneUrl(dto))
    val item = GiteaCloneItem.Repository(GiteaAccount("intellij-dev", server), dto)
    assertEquals("${server.uri}/intellij-dev/plugin-playground.git", item.cloneUrl(useSsh = false))
    assertEquals("git@one.intranet:intellij-dev/plugin-playground.git", item.cloneUrl(useSsh = true))
  }

  fun `test repositories tab links to the configured server, also with a subpath`() {
    val dto = GiteaJson.fromJson<GiteaRepositoryDTO>(repo("intellij-dev/plugin-playground"))
    assertEquals("${server.uri}/intellij-dev/plugin-playground",
                 GiteaRepositoriesComponentFactory.webUrl(GiteaCloneItem.Repository(GiteaAccount("intellij-dev", server), dto)))
    val subpath = GiteaServerPath("http://192.168.2.10/gitea")
    assertEquals("http://192.168.2.10/gitea/intellij-dev/plugin-playground",
                 GiteaRepositoriesComponentFactory.webUrl(GiteaCloneItem.Repository(GiteaAccount("intellij-dev", subpath), dto)))
  }

  fun `test repositories of an account are listed page by page, errors as items`() {
    val account = GiteaAccount("intellij-dev", server)
    mock.enqueue(json("[${repo("b/zeta")},${repo("a/alpha", false)}]", 200, "X-Total-Count" to "3"))
    mock.enqueue(json("[${repo("c/mid")}]", 200, "X-Total-Count" to "3"))
    runBlocking { GiteaAccountManager.getInstance().updateAccount(account, GiteaAccountCredentials.Token("t")) }

    val cs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val vm = GiteaCloneViewModel(cs, GiteaAccountManager.getInstance())
      // separately: a combined condition is only re-checked when items change, not when loading ends
      val items = runBlocking {
        withTimeout(10.seconds) {
          vm.items.first { it.size == 3 }
          vm.isLoading.first { !it }
          vm.items.value
        }
      }
      assertEquals(listOf("a/alpha", "b/zeta", "c/mid").sorted(), items.map { (it as GiteaCloneItem.Repository).repository.fullName })
      assertEquals("token t", mock.takeRequest().headers["Authorization"])
      assertTrue(GiteaCloneViewModel.matches(items[0], "ALP"))
      assertFalse(GiteaCloneViewModel.matches(items[0], "zeta"))
    }
    finally {
      cs.cancel()
    }
  }

  fun `test failing account shows an error item`() {
    val account = GiteaAccount("intellij-dev", server)
    mock.enqueue(json("""{"message":"invalid token"}""", 401))
    runBlocking { GiteaAccountManager.getInstance().updateAccount(account, GiteaAccountCredentials.Token("bad")) }
    val cs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val vm = GiteaCloneViewModel(cs, GiteaAccountManager.getInstance())
      val error = runBlocking { withTimeout(10.seconds) { vm.items.first { it.isNotEmpty() } } }.single()
      assertTrue(error is GiteaCloneItem.Error)
      assertTrue((error as GiteaCloneItem.Error).message.contains("invalid", ignoreCase = true))
    }
    finally {
      cs.cancel()
    }
  }

  fun `test pasted urls`() {
    assertTrue(GiteaCloneViewModel.isUrl("https://example.com/o/r.git"))
    assertTrue(GiteaCloneViewModel.isUrl("git@example.com:o/r.git"))
    assertTrue(GiteaCloneViewModel.isUrl("ssh://git@example.com:2222/o/r.git"))
    assertFalse(GiteaCloneViewModel.isUrl("plugin-playground"))
    assertFalse(GiteaCloneViewModel.isUrl("owner/repo"))
  }

  fun `test create repository for user and organization`() {
    val api = GiteaApiImpl(server, HttpClient.newHttpClient(), { GiteaCredentials.AccessToken("t") }, "test", { error("unused") })
    mock.enqueue(json(repo("intellij-dev/new-one"), 201))
    mock.enqueue(json(repo("my-org/new-two"), 201))
    runBlocking {
      assertEquals("intellij-dev/new-one", api.createRepository(GiteaCreateRepositoryRequest("new-one", "desc", true)).fullName)
      assertEquals("my-org/new-two", api.createRepository(GiteaCreateRepositoryRequest("new-two", null, false), organization = "my-org").fullName)
    }
    val user = mock.takeRequest()
    assertEquals("POST", user.method)
    assertEquals("/gitea/api/v1/user/repos", user.target)
    assertEquals("""{"name":"new-one","description":"desc","private":true,"auto_init":false}""", user.body!!.utf8())
    val org = mock.takeRequest()
    assertEquals("/gitea/api/v1/orgs/my-org/repos", org.target)
    assertEquals("""{"name":"new-two","private":false,"auto_init":false}""", org.body!!.utf8())
  }

  fun `test repository name from project name`() {
    assertEquals("My-Project", GiteaShareDialog.sanitizeName("My Project"))
    assertEquals("a.b_c-d", GiteaShareDialog.sanitizeName("a.b_c-d"))
    assertEquals("project", GiteaShareDialog.sanitizeName("äöü"))
  }
}
