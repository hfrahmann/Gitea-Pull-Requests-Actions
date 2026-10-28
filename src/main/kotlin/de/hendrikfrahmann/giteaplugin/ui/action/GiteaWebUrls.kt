package de.hendrikfrahmann.giteaplugin.ui.action

import java.net.URI
import java.net.URLEncoder

/**
 * Gitea web links below a repository's web URI ([de.hendrikfrahmann.giteaplugin.repository.GiteaRepositoryCoordinates.getWebURI],
 * e.g. `https://host/gitea/owner/repo`), so subpath installs work. Path segments are percent-encoded one by one,
 * `/` inside file paths and branch names is kept.
 */
object GiteaWebUrls {
  /** `<repo>/commit/<sha>` */
  fun commit(repository: URI, sha: String): URI = repository.child("commit", sha)

  /** `<repo>/src/commit/<sha>/<path>#L10-L20` – a permalink. [lineRange] is 0-based, inclusive. */
  fun fileAtCommit(repository: URI, sha: String, relativePath: String, lineRange: IntRange? = null): URI =
    repository.child("src", "commit", sha, *relativePath.pathSegments()).withLines(lineRange)

  /** `<repo>/src/branch/<branch>/<path>#L10-L20` – follows the branch. [lineRange] is 0-based, inclusive. */
  fun fileOnBranch(repository: URI, branch: String, relativePath: String, lineRange: IntRange? = null): URI =
    repository.child("src", "branch", *branch.pathSegments(), *relativePath.pathSegments()).withLines(lineRange)

  /** `<repo>/pulls/<number>` */
  fun pullRequest(repository: URI, number: Long): URI = repository.child("pulls", number.toString())

  fun issue(repository: URI, number: Long): URI = repository.child("issues", number.toString())

  /** `<repo>/actions/runs/<id>` – the run id as used by the API; on 28.1 the API's `html_url` has the same form. */
  fun actionsRun(repository: URI, runId: Long): URI = repository.child("actions", "runs", runId.toString())

  /** `<repo>/actions/runs/<run id>/jobs/<job id>` – the form of Gitea Actions commit status `target_url`s on 28.1. */
  fun actionsJob(repository: URI, runId: Long, jobId: Long): URI = repository.child("actions", "runs", runId.toString(), "jobs", jobId.toString())

  /** `<repo>/actions?workflow=<file>` – the runs of one workflow. */
  fun actionsWorkflow(repository: URI, workflowFile: String): URI = URI.create(repository.child("actions").toString() + "?workflow=" + encode(workflowFile))

  private fun URI.child(vararg segments: String): URI {
    val base = toString().removeSuffix("/")
    return URI.create(base + segments.joinToString("") { "/" + encode(it) })
  }

  private fun String.pathSegments(): Array<String> = split('/').filter { it.isNotEmpty() }.toTypedArray()

  private fun encode(segment: String): String = URLEncoder.encode(segment, Charsets.UTF_8).replace("+", "%20")

  /** Gitea's fragment: `#L5` for one line, `#L5-L9` for a range (1-based). */
  private fun URI.withLines(lineRange: IntRange?): URI {
    if (lineRange == null) {
      return this
    }
    val first = lineRange.first + 1
    val last = lineRange.last + 1
    val fragment = when {
      last > first -> "L$first-L$last"
      else -> "L$first"
    }
    return URI.create("$this#$fragment")
  }
}
