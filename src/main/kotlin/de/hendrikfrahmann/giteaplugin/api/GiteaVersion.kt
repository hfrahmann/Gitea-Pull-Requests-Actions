package de.hendrikfrahmann.giteaplugin.api

import org.jetbrains.annotations.NonNls

/**
 * A Gitea version as returned by `GET /version`.
 *
 * Gitea jumped from `1.26` to `28.x`, so comparison is plain numeric, major first (`1.26.0 < 28.1.0`).
 * Build metadata and pre-release suffixes are ignored in comparisons (`1.23.0+dev-12-gabc` → `1.23.0`), so feature
 * gates treat a pre-release of X as X; [isPreRelease] tells them apart.
 * Forgejo reports `<forgejo>+gitea-<gitea>` (e.g. `7.0.0+gitea-1.21.0`); there the Gitea compatibility version is used.
 */
data class GiteaVersion(
  val major: Int,
  val minor: Int = 0,
  val patch: Int = 0,
  val original: String? = null,
) : Comparable<GiteaVersion> {

  /** `-rc1`, `-dev`, `+dev-12-gabc`, … – anything but a release or plain build metadata (`28.1.0+abc`). */
  val isPreRelease: Boolean
    get() {
      val giteaPart = original?.trim()?.removePrefix("v")?.let { it.substringAfter(FORGEJO_MARKER, it) } ?: return false
      val suffix = giteaPart.dropWhile { it.isDigit() || it == '.' }
      return suffix.startsWith("-") || suffix.startsWith("+") && suffix.contains("dev", ignoreCase = true)
    }

  /** `true` if the server is Forgejo (version string contains `+gitea-`). */
  val isForgejo: Boolean get() = original?.contains(FORGEJO_MARKER) == true

  override fun compareTo(other: GiteaVersion): Int =
    compareValuesBy(this, other, GiteaVersion::major, GiteaVersion::minor, GiteaVersion::patch)

  override fun equals(other: Any?): Boolean =
    other is GiteaVersion && major == other.major && minor == other.minor && patch == other.patch

  override fun hashCode(): Int = (major * 31 + minor) * 31 + patch

  override fun toString(): String = original ?: "$major.$minor.$patch"

  companion object {
    private const val FORGEJO_MARKER = "+gitea-"

    /** @throws IllegalArgumentException when [version] doesn't start with a number. */
    fun parse(version: @NonNls String): GiteaVersion {
      val trimmed = version.trim().removePrefix("v")
      val giteaPart = trimmed.substringAfter(FORGEJO_MARKER, trimmed)
      // Cut at the first char that can't be part of "major.minor.patch": '+', '-', ' ', …
      val core = giteaPart.takeWhile { it.isDigit() || it == '.' }
      val parts = core.split('.').filter { it.isNotEmpty() }.map { it.toInt() }
      require(parts.isNotEmpty()) { "Cannot parse Gitea version: $version" }
      return GiteaVersion(parts[0], parts.getOrElse(1) { 0 }, parts.getOrElse(2) { 0 }, version.trim())
    }

    fun parseOrNull(version: String?): GiteaVersion? = version?.let { runCatching { parse(it) }.getOrNull() }
  }
}
