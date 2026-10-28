package de.hendrikfrahmann.giteaplugin.pullrequest.ui.list

import com.intellij.collaboration.ui.codereview.list.ReviewListViewModel
import com.intellij.collaboration.ui.icon.IconsProvider
import com.intellij.openapi.diagnostic.logger
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestShort
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestsLoader
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.filters.GiteaPullRequestsFiltersValue
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.filters.GiteaPullRequestsFiltersViewModel
import de.hendrikfrahmann.giteaplugin.util.childScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val LOG = logger<GiteaPullRequestsListViewModel>()

/**
 * The PR list: restarts loading when the filter changes or on [refresh], loads further pages on [requestMore]
 * (the list asks when it's scrolled to the end).
 */
class GiteaPullRequestsListViewModel(
  parentCs: CoroutineScope,
  val filterVm: GiteaPullRequestsFiltersViewModel,
  /** `owner/name` for the empty text. */
  val repository: String,
  val avatarIconsProvider: IconsProvider<GiteaUserDTO>,
  private val createPager: (GiteaPullRequestsFiltersValue) -> GiteaPullRequestsLoader.Pager,
  /** Called with every loaded PR, e.g. to cache it by number. */
  private val onLoaded: (List<GiteaPullRequestShort>) -> Unit = {},
) : ReviewListViewModel {
  private val cs = parentCs.childScope()

  private val _items = MutableStateFlow<List<GiteaPullRequestShort>>(emptyList())
  val items: StateFlow<List<GiteaPullRequestShort>> = _items.asStateFlow()

  private val _loading = MutableStateFlow(false)
  val loading: StateFlow<Boolean> = _loading.asStateFlow()

  private val _error = MutableStateFlow<Throwable?>(null)
  val error: StateFlow<Throwable?> = _error.asStateFlow()

  private val _hasMore = MutableStateFlow(true)
  val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

  private val mutex = Mutex()
  private var pager: GiteaPullRequestsLoader.Pager? = null
  private var loadJob: Job? = null
  private var generation = 0

  init {
    cs.launch {
      filterVm.searchState.collect { reset(it) }
    }
  }

  fun requestMore() {
    if (loadJob?.isActive == true || !_hasMore.value || _error.value != null) {
      return
    }
    val currentGeneration = generation
    loadJob = cs.launch { loadNextPage(currentGeneration) }
  }

  override fun refresh() {
    cs.launch { reset(filterVm.searchState.value) }
  }

  /**
   * Reloads as many PRs as are shown and swaps them in when done – no empty list in between, the selection stays (
   * tool window shown again). Skipped while a page is loading or after an error (the error has its own retry).
   */
  fun reloadInPlace() {
    if (loadJob?.isActive == true || _error.value != null) {
      return
    }
    val currentGeneration = generation
    loadJob = cs.launch {
      mutex.withLock {
        if (currentGeneration != generation) {
          return@withLock
        }
        val newPager = createPager(filterVm.searchState.value)
        val wanted = _items.value.size
        val reloaded = mutableListOf<GiteaPullRequestShort>()
        try {
          do {
            reloaded += newPager.loadMore()
          } while (reloaded.size < wanted && newPager.hasMore)
        }
        catch (e: CancellationException) {
          throw e
        }
        catch (e: Exception) {
          // keep what is shown; the next refresh tries again
          LOG.info("Could not reload the pull requests", e)
          return@withLock
        }
        if (currentGeneration != generation) {
          return@withLock
        }
        pager = newPager
        onLoaded(reloaded)
        _items.value = reloaded
        _hasMore.value = newPager.hasMore
      }
    }
  }

  private suspend fun reset(filter: GiteaPullRequestsFiltersValue) {
    loadJob?.cancelAndJoin()
    mutex.withLock {
      generation++
      pager = createPager(filter)
      _items.value = emptyList()
      _error.value = null
      _hasMore.value = true
    }
    requestMore()
  }

  private suspend fun loadNextPage(expectedGeneration: Int) = mutex.withLock {
    val pager = pager ?: return@withLock
    if (expectedGeneration != generation) {
      return@withLock
    }
    _loading.value = true
    try {
      val page = pager.loadMore()
      if (expectedGeneration != generation) {
        return@withLock
      }
      onLoaded(page)
      _items.value += page
      _hasMore.value = pager.hasMore
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      _error.value = e
    }
    finally {
      _loading.value = false
    }
  }
}
