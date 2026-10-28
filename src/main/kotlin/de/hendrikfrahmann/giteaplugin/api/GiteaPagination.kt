package de.hendrikfrahmann.giteaplugin.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList

/**
 * Gitea list endpoints take `?page=N&limit=M` (1-based) and answer with `X-Total-Count` and, if there are more pages,
 * a `Link` header. The server caps `limit` (`MAX_RESPONSE_ITEMS`, default 50), so a page can be shorter than requested.
 */
object GiteaPagination {
  const val DEFAULT_PAGE_SIZE: Int = 50

  /**
   * Streams pages from [loadPage] (called with page = 1, 2, …), one list per page, until the last one.
   * End of data, in order of preference: `Link` header without `next`; all `X-Total-Count` items loaded;
   * a page shorter than [pageSize]. An empty page always ends it.
   */
  fun <T> pagesFlow(
    pageSize: Int = DEFAULT_PAGE_SIZE,
    loadPage: suspend (page: Int, limit: Int) -> GiteaResponse<List<T>>,
  ): Flow<List<T>> = flow {
    var page = 1
    var loaded = 0
    while (true) {
      val response = loadPage(page, pageSize)
      val items = response.body
      if (items.isEmpty()) {
        break
      }
      emit(items)
      loaded += items.size
      if (!hasNextPage(response, loaded, pageSize)) {
        break
      }
      page++
    }
  }

  suspend fun <T> loadAllPages(
    pageSize: Int = DEFAULT_PAGE_SIZE,
    loadPage: suspend (page: Int, limit: Int) -> GiteaResponse<List<T>>,
  ): List<T> = pagesFlow(pageSize, loadPage).toList().flatten()

  internal fun hasNextPage(response: GiteaResponse<List<*>>, loadedSoFar: Int, pageSize: Int): Boolean {
    if (response.hasLinkHeader) {
      return response.nextPageUri != null
    }
    response.totalCount?.let { return loadedSoFar < it }
    return response.body.size >= pageSize
  }
}

/** Adds `page` and `limit` query parameters. */
fun GiteaQueryBuilder.page(page: Int, limit: Int) {
  "page" eq page
  "limit" eq limit
}
