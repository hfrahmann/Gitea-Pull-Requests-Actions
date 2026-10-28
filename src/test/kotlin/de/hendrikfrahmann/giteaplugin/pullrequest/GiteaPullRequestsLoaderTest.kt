package de.hendrikfrahmann.giteaplugin.pullrequest

import com.intellij.util.ui.EmptyIcon
import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaApiImpl
import de.hendrikfrahmann.giteaplugin.api.GiteaCredentials
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.GiteaServerMetadataCache
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestState
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestsLoader
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestsLoader.Source
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.filters.GiteaFiltersPanelFactory
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.filters.GiteaPullRequestsFiltersHistoryModel
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.filters.GiteaPullRequestsFiltersValue
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.filters.GiteaPullRequestsFiltersValue.UserFilterValue
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.filters.GiteaPullRequestsFiltersViewModel
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.filters.GiteaPullRequestsPersistentFiltersHistory
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.list.GiteaPullRequestsListViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.net.http.HttpClient

class GiteaPullRequestsLoaderTest {
  private lateinit var mock: MockWebServer
  private lateinit var api: GiteaApi
  private val repo = GiteaRepositoryPath("intellij-dev", "pr-list-playground")
  private val me = UserFilterValue("intellij-reviewer", "Reviewer")

  @BeforeEach
  fun setUp() {
    mock = MockWebServer()
    mock.start()
    val server = GiteaServerPath(mock.url("/").toString())
    api = GiteaApiImpl(server, HttpClient.newHttpClient(), { GiteaCredentials.AccessToken("t") }, "test", GiteaServerMetadataCache()::getMetadata)
  }

  @AfterEach
  fun tearDown() = mock.close()

  private fun loader(pageSize: Int = 2) = GiteaPullRequestsLoader(api, repo, me.login, pageSize)

  private fun page(body: String, total: Int) =
    MockResponse.Builder().code(200).body(body).addHeader("Content-Type", "application/json").addHeader("X-Total-Count", total.toString()).build()

  private fun pr(number: Int, state: String = "open", merged: Boolean = false, reviewers: String = "[]", title: String = "PR $number") =
    """{"id":$number,"number":$number,"title":"$title","state":"$state","merged":$merged,"user":{"id":3,"login":"intellij-dev"},
       "requested_reviewers":$reviewers,"labels":[{"id":1,"name":"bug","color":"ee0701"}],"mergeable":false}"""

  private fun issue(number: Int, repoName: String = "intellij-dev/pr-list-playground", merged: Boolean = false, state: String = "open") =
    """{"id":$number,"number":$number,"title":"PR $number","state":"$state","user":{"id":3,"login":"intellij-dev"},
       "pull_request":{"merged":$merged,"draft":true},"repository":{"full_name":"$repoName"},"assignees":[{"id":4,"login":"intellij-reviewer"}],
       "labels":[{"id":1,"name":"bug"}],"milestone":{"id":1,"title":"v1.0"}}"""

  private fun takeQuery(): Pair<String, Map<String, String>> {
    val target = mock.takeRequest().target
    val params = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
      URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
    }
    return target.substringBefore('?') to params
  }

  @Test
  fun `source follows what gitea can filter`() {
    assertEquals(Source.PULLS, Source.choose(GiteaPullRequestsFiltersValue(state = GiteaPullRequestState.OPEN, author = me), me.login))
    assertEquals(Source.ISSUES, Source.choose(GiteaPullRequestsFiltersValue(searchQuery = "avatar"), me.login))
    assertEquals(Source.ISSUES, Source.choose(GiteaPullRequestsFiltersValue(assignee = me), me.login))
    assertEquals(Source.ISSUES, Source.choose(GiteaPullRequestsFiltersValue(label = "bug"), me.login))
    assertEquals(Source.REVIEW_REQUESTED_SEARCH, Source.choose(GiteaPullRequestsFiltersValue(reviewer = UserFilterValue("Intellij-Reviewer", null)), me.login))
    assertEquals(Source.PULLS, Source.choose(GiteaPullRequestsFiltersValue(reviewer = UserFilterValue("someone", null), searchQuery = "x"), me.login))
  }

  @Test
  fun `pulls endpoint pages with poster and state`() = runBlocking {
    mock.enqueue(page("[${pr(4)},${pr(3)}]", 3))
    mock.enqueue(page("[${pr(2)}]", 3))
    val pager = loader().createPager(GiteaPullRequestsFiltersValue(state = GiteaPullRequestState.OPEN, author = UserFilterValue("intellij-dev", null)))

    val first = pager.loadMore()
    assertEquals(listOf(4L, 3L), first.map { it.number })
    assertEquals(listOf("bug"), first[0].labels.map { it.name })
    assertEquals(false, first[0].mergeable)
    assertTrue(pager.hasMore)
    val (path, params) = takeQuery()
    assertEquals("/api/v1/repos/intellij-dev/pr-list-playground/pulls", path)
    assertEquals(mapOf("state" to "open", "poster" to "intellij-dev", "page" to "1", "limit" to "2"), params)

    assertEquals(listOf(2L), pager.loadMore().map { it.number })
    assertFalse(pager.hasMore)
    assertEquals("2", takeQuery().second["page"])
  }

  @Test
  fun `merged is closed on the server and filtered here, skipping pages without a match`() = runBlocking {
    mock.enqueue(page("[${pr(6, "closed")},${pr(5, "closed")}]", 4))
    mock.enqueue(page("[${pr(4, "closed", merged = true)},${pr(3, "closed")}]", 4))
    val pager = loader().createPager(GiteaPullRequestsFiltersValue(state = GiteaPullRequestState.MERGED))

    val result = pager.loadMore()
    assertEquals(listOf(4L), result.map { it.number })
    assertEquals(GiteaPullRequestState.MERGED, result.single().state)
    assertNull(result.single().mergeable)
    assertEquals("closed", takeQuery().second["state"])
    assertEquals("2", takeQuery().second["page"])
    assertFalse(pager.hasMore)
    assertTrue(pager.loadMore().isEmpty())
  }

  @Test
  fun `issues endpoint for search, assignee, label and milestone`() = runBlocking {
    mock.enqueue(page("[${issue(7)}]", 1))
    val filter = GiteaPullRequestsFiltersValue(searchQuery = "avatar", assignee = me, label = "bug", milestone = "v1.0",
                                               author = UserFilterValue("intellij-dev", null))
    val result = loader().createPager(filter).loadMore()
    assertEquals(listOf(7L), result.map { it.number })
    assertTrue(result.single().isDraft)
    assertNull(result.single().reviewers)

    val (path, params) = takeQuery()
    assertEquals("/api/v1/repos/intellij-dev/pr-list-playground/issues", path)
    assertEquals(mapOf("type" to "pulls", "state" to "all", "q" to "avatar", "labels" to "bug", "milestones" to "v1.0",
                       "created_by" to "intellij-dev", "assigned_by" to "intellij-reviewer", "page" to "1", "limit" to "2"), params)
  }

  @Test
  fun `review requested from me searches the owner and keeps this repository only`() = runBlocking {
    mock.enqueue(page("[${issue(9, "intellij-dev/other")},${issue(8)}]", 2))
    val result = loader().createPager(GiteaPullRequestsFiltersValue(state = GiteaPullRequestState.OPEN, reviewer = me)).loadMore()
    assertEquals(listOf(8L), result.map { it.number })
    val (path, params) = takeQuery()
    assertEquals("/api/v1/repos/issues/search", path)
    assertEquals("true", params["review_requested"])
    assertEquals("intellij-dev", params["owner"])
    assertEquals("open", params["state"])
  }

  @Test
  fun `another reviewer is filtered on pulls items, with title search`() = runBlocking {
    val other = """[{"id":5,"login":"someone"}]"""
    mock.enqueue(page("[${pr(3, reviewers = other, title = "Fix Avatar")},${pr(2, reviewers = other)},${pr(1)}]", 3))
    val result = loader(pageSize = 3).createPager(
      GiteaPullRequestsFiltersValue(reviewer = UserFilterValue("someone", null), searchQuery = "avatar")).loadMore()
    assertEquals(listOf(3L), result.map { it.number })
  }

  @Test
  fun `filter history survives xml beans`() {
    val value = GiteaPullRequestsFiltersValue("avatar", GiteaPullRequestState.MERGED, me, UserFilterValue("a", null), me, "bug", "v1.0")
    val history = GiteaPullRequestsPersistentFiltersHistory()
    history.lastFilter = value
    history.history = listOf(value, GiteaPullRequestsFiltersValue.EMPTY)
    val copy = GiteaPullRequestsPersistentFiltersHistory().apply { loadState(history.state) }
    assertEquals(value, copy.lastFilter)
    assertEquals(listOf(value, GiteaPullRequestsFiltersValue.EMPTY), copy.history)
    assertEquals(6, value.filterCount)
  }

  @Test
  fun `search field text`() {
    assertEquals("\"avatar\" author:\"intellij-dev\" label:\"bug\" ",
                 GiteaFiltersPanelFactory.shortText(GiteaPullRequestsFiltersValue("avatar", author = UserFilterValue("intellij-dev", null), label = "bug")))
  }

  @Test
  fun `list view model pages, restarts on filter change and refreshes`() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val user = GiteaUserDTO(4, "intellij-reviewer")
      val filterVm = GiteaPullRequestsFiltersViewModel(
        scope, GiteaPullRequestsFiltersHistoryModel(GiteaPullRequestsPersistentFiltersHistory()), user, { _, _ -> EmptyIcon.ICON_16 },
        loadUsers = { emptyList() }, labelsBatches = { emptyFlow() }, milestonesBatches = { emptyFlow() },
      )
      val cached = mutableListOf<Long>()
      // default filter "Open" → /pulls?state=open
      mock.enqueue(page("[${pr(4)},${pr(3)}]", 3))
      mock.enqueue(page("[${pr(2)}]", 3))
      val listVm = GiteaPullRequestsListViewModel(scope, filterVm, repo.toString(), { _, _ -> EmptyIcon.ICON_16 }, loader()::createPager,
                                                  onLoaded = { page -> cached += page.map { it.number } })
      withTimeout(5000) { listVm.items.first { it.size == 2 } }
      assertEquals("open", takeQuery().second["state"])

      retrying({ listVm.requestMore() }) { listVm.items.value.size == 3 }
      assertFalse(listVm.hasMore.value)
      assertEquals(listOf(4L, 3L, 2L), cached)
      takeQuery()

      mock.enqueue(page("[${pr(9, "closed")}]", 1))
      filterVm.searchState.value = GiteaPullRequestsFiltersValue(state = GiteaPullRequestState.CLOSED)
      withTimeout(5000) { listVm.items.first { it.map { pr -> pr.number } == listOf(9L) } }
      assertEquals("closed", takeQuery().second["state"])

      mock.enqueue(MockResponse.Builder().code(500).body("""{"message":"boom"}""").build())
      listVm.refresh()
      withTimeout(5000) { listVm.error.first { it != null } }
      assertTrue(listVm.items.value.isEmpty())

      mock.enqueue(page("[${pr(9, "closed")}]", 1))
      listVm.refresh()
      withTimeout(5000) { listVm.items.first { it.size == 1 } }
      assertNull(listVm.error.value)
    }
    finally {
      scope.cancel()
    }
  }

  @Test
  fun `list view model reloads in place without emptying the list`() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val filterVm = GiteaPullRequestsFiltersViewModel(
        scope, GiteaPullRequestsFiltersHistoryModel(GiteaPullRequestsPersistentFiltersHistory()), GiteaUserDTO(4, "intellij-reviewer"),
        { _, _ -> EmptyIcon.ICON_16 }, loadUsers = { emptyList() }, labelsBatches = { emptyFlow() }, milestonesBatches = { emptyFlow() },
      )
      mock.enqueue(page("[${pr(4)},${pr(3)}]", 3))
      mock.enqueue(page("[${pr(2)}]", 3))
      val listVm = GiteaPullRequestsListViewModel(scope, filterVm, repo.toString(), { _, _ -> EmptyIcon.ICON_16 }, loader()::createPager)
      withTimeout(5000) { listVm.items.first { it.size == 2 } }
      retrying({ listVm.requestMore() }) { listVm.items.value.size == 3 }
      repeat(2) { takeQuery() }

      val emitted = mutableListOf<List<Long>>()
      val collector = scope.launch { listVm.items.collect { items -> synchronized(emitted) { emitted += items.map { it.number } } } }
      // a new PR on top: as many as shown are reloaded (two pages), then swapped in at once
      mock.enqueue(page("[${pr(5)},${pr(4)}]", 4))
      mock.enqueue(page("[${pr(3)},${pr(2)}]", 4))
      retrying({ listVm.reloadInPlace() }) { listVm.items.value.size == 4 }
      collector.cancel()
      assertEquals(listOf(5L, 4L, 3L, 2L), listVm.items.value.map { it.number })
      assertTrue(synchronized(emitted) { emitted.none { it.isEmpty() } })
      assertEquals(listOf("1", "2"), List(2) { takeQuery().second["page"] })

      // a failing reload keeps what is shown
      mock.enqueue(MockResponse.Builder().code(500).body("""{"message":"boom"}""").build())
      val requests = mock.requestCount
      retrying({ listVm.reloadInPlace() }) { mock.requestCount > requests }
      takeQuery()
      delay(200)
      assertEquals(4, listVm.items.value.size)
      assertNull(listVm.error.value)
    }
    finally {
      scope.cancel()
    }
  }

  /**
   * Repeats [action] until [done]: the list view model ignores `requestMore`/`reloadInPlace` while the previous load job
   * is still finishing – its items are already visible then, so waiting for them alone races with the job's end.
   */
  private suspend fun retrying(action: () -> Unit, done: () -> Boolean) = withTimeout(5000) {
    while (!done()) {
      action()
      delay(20)
    }
  }
}
