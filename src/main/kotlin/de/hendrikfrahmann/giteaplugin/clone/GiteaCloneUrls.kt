package de.hendrikfrahmann.giteaplugin.clone

/**
 * Checks on what the server hands out for cloning, before it reaches git's command line.
 *
 * `git clone <url> -- <directory>` reads a URL that starts with `-` as an option (`--upload-pack=<cmd>` runs a command
 * locally), and a "directory" like `file:///x` turns into the repository to clone. So a server's `ssh_url` is used only
 * when it is a plain SSH URL, and a repository name only when it is a plain file name.
 */
object GiteaCloneUrls {
  /** [url] if it is an `ssh://` or `user@host:path` URL, else null. */
  fun safeSshUrl(url: String?): String? = url?.trim()?.takeIf { SSH_URL.matches(it) || SCP_LIKE_URL.matches(it) }

  /** [name] if it only has the characters Gitea allows in repository names and is not `.` or `..`, else null. */
  fun safeDirectoryName(name: String?): String? = name?.takeIf { NAME.matches(it) && it != "." && it != ".." }

  private const val USER = "\\w[\\w.~+-]*"
  private const val HOST = "\\w[\\w.-]*"
  private val SSH_URL = Regex("^ssh://(?:$USER@)?(?:$HOST|\\[[0-9A-Fa-f:.]+])(?::\\d+)?/\\S+$")
  private val SCP_LIKE_URL = Regex("^$USER@$HOST:[^\\s-]\\S*$")
  private val NAME = Regex("^[\\w.-]+$")
}
