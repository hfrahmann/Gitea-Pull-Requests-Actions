package de.hendrikfrahmann.giteaplugin.api.request

import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaPagination
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaBranchDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaLabelDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaMilestoneDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaRepositoryDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.api.getJson
import de.hendrikfrahmann.giteaplugin.api.getJsonList
import de.hendrikfrahmann.giteaplugin.api.jsonBody
import de.hendrikfrahmann.giteaplugin.api.loadJsonValue
import de.hendrikfrahmann.giteaplugin.api.loadText
import de.hendrikfrahmann.giteaplugin.api.nullOnHttpStatus
import de.hendrikfrahmann.giteaplugin.api.page
import de.hendrikfrahmann.giteaplugin.api.repoApiUri
import de.hendrikfrahmann.giteaplugin.api.withQuery
import kotlinx.coroutines.flow.Flow

/** `GET /repos/{owner}/{repo}` – includes the caller's permissions and the merge settings. */
suspend fun GiteaApi.getRepository(repository: GiteaRepositoryPath): GiteaRepositoryDTO =
  getJson(server.repoApiUri(repository))

/** `GET /user/repos` – repositories the user owns or is a member of, page by page. */
fun GiteaApi.currentUserRepositoriesFlow(pageSize: Int = GiteaPagination.DEFAULT_PAGE_SIZE): Flow<List<GiteaRepositoryDTO>> =
  GiteaPagination.pagesFlow(pageSize) { page, limit ->
    getJsonList(server.apiUri("user", "repos").withQuery { page(page, limit) })
  }

/** `GET /repos/{owner}/{repo}/branches` – all pages. */
suspend fun GiteaApi.loadBranches(repository: GiteaRepositoryPath): List<GiteaBranchDTO> =
  GiteaPagination.loadAllPages { page, limit ->
    getJsonList(server.repoApiUri(repository, "branches").withQuery { page(page, limit) })
  }

/** `GET /repos/{owner}/{repo}/labels` – page by page (repository labels only, no org labels). */
fun GiteaApi.labelsFlow(repository: GiteaRepositoryPath): Flow<List<GiteaLabelDTO>> =
  GiteaPagination.pagesFlow { page, limit ->
    getJsonList(server.repoApiUri(repository, "labels").withQuery { page(page, limit) })
  }

/** `GET /repos/{owner}/{repo}/milestones?state=all` – page by page. */
fun GiteaApi.milestonesFlow(repository: GiteaRepositoryPath): Flow<List<GiteaMilestoneDTO>> =
  GiteaPagination.pagesFlow { page, limit ->
    getJsonList(server.repoApiUri(repository, "milestones").withQuery { "state" eq "all"; page(page, limit) })
  }

/** `GET /repos/{owner}/{repo}/assignees` – users with write access (not paged). */
suspend fun GiteaApi.loadAssignees(repository: GiteaRepositoryPath): List<GiteaUserDTO> =
  getJsonList<GiteaUserDTO>(server.repoApiUri(repository, "assignees")).body

private data class GiteaUserSearchResult(val ok: Boolean = false, val data: List<GiteaUserDTO> = emptyList())

/** `GET /users/search` – all users of the instance the caller can see (paged; the list is wrapped in `{ok, data}`). */
suspend fun GiteaApi.searchUsers(): List<GiteaUserDTO> =
  GiteaPagination.loadAllPages { page, limit ->
    loadJsonValue<GiteaUserSearchResult>(request(server.apiUri("users", "search").withQuery { page(page, limit) }).GET().build()).map { it.data }
  }

/** Body of `POST /user/repos` and `POST /orgs/{org}/repos`. */
data class GiteaCreateRepositoryRequest(
  val name: String,
  val description: String? = null,
  val private: Boolean = true,
  /** `false`: empty repository, the IDE pushes the first commit. */
  val autoInit: Boolean = false,
)

/** Creates a repository for the current user, or in [organization] when given. */
suspend fun GiteaApi.createRepository(request: GiteaCreateRepositoryRequest, organization: String? = null): GiteaRepositoryDTO {
  val uri = when {
    organization == null -> server.apiUri("user", "repos")
    else -> server.apiUri("orgs", organization, "repos")
  }
  return loadJsonValue<GiteaRepositoryDTO>(request(uri).jsonBody("POST", request).build()).body
}

/**
 * HTTPS clone URL of [repository] on the configured server. Gitea builds `clone_url` from its `ROOT_URL`, which may
 * be another host than the one the user configured; the coordinates are always right.
 */
fun GiteaServerPath.httpCloneUrl(repository: GiteaRepositoryDTO): String =
  webUri(repository.owner?.login ?: repository.fullName.substringBefore('/'), repository.name).toString() + ".git"

/**
 * `GET /repos/{o}/{r}/branches/{branch}` – with the effective protection (`required_approvals`, required status checks)
 * and `user_can_merge` for the caller. Readable with read access, unlike `/branch_protections` (`403` for non-admins).
 * The route takes the rest of the path, so `feature/x` is sent as two segments.
 */
suspend fun GiteaApi.getBranch(repository: GiteaRepositoryPath, branch: String): GiteaBranchDTO =
  getJson(server.repoApiUri(repository, "branches", *branch.split('/').toTypedArray()))

/** `GET /repos/{o}/{r}/raw/{path}?ref=` – a text file of [ref] (branch, tag or sha), `null` if it doesn't exist. */
suspend fun GiteaApi.loadRawFile(repository: GiteaRepositoryPath, path: String, ref: String): String? = nullOnHttpStatus {
  loadText(server.repoApiUri(repository, "raw", *path.split('/').toTypedArray()).withQuery { "ref" eq ref })
}

private data class GiteaCompareDTO(val totalCommits: Int = 0)

/** `GET /repos/{o}/{r}/compare/{base}...{head}` – number of commits in [head] that aren't in [base] (shas or branches). */
suspend fun GiteaApi.countCommitsBetween(repository: GiteaRepositoryPath, base: String, head: String): Int =
  getJson<GiteaCompareDTO>(server.repoApiUri(repository, "compare", "$base...$head")).totalCommits
