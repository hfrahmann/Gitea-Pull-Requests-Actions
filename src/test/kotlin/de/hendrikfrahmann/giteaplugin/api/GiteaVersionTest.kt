package de.hendrikfrahmann.giteaplugin.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GiteaVersionTest {
  @Test
  fun `parses release versions`() {
    assertEquals(GiteaVersion(1, 26, 0), GiteaVersion.parse("1.26.0"))
    assertEquals(GiteaVersion(28, 1, 0), GiteaVersion.parse("28.1.0"))
    assertEquals(GiteaVersion(28, 0, 0), GiteaVersion.parse("28"))
    assertEquals("28.1.0", GiteaVersion.parse("28.1.0").toString())
  }

  @Test
  fun `ignores build metadata and pre-release suffixes`() {
    assertEquals(GiteaVersion(1, 23, 0), GiteaVersion.parse("1.23.0+dev-12-gabc"))
    assertEquals(GiteaVersion(28, 2, 0), GiteaVersion.parse("28.2.0-rc1"))
    assertEquals("1.23.0+dev-12-gabc", GiteaVersion.parse("1.23.0+dev-12-gabc").toString())
  }

  @Test
  fun `forgejo uses the gitea compatibility version`() {
    val version = GiteaVersion.parse("7.0.0+gitea-1.21.0")
    assertEquals(GiteaVersion(1, 21, 0), version)
    assertTrue(version.isForgejo)
    assertFalse(GiteaVersion.parse("28.1.0").isForgejo)
  }

  @Test
  fun `compares numerically major first`() {
    assertTrue(GiteaVersion.parse("1.26.0") < GiteaVersion.parse("28.1.0"))
    assertTrue(GiteaVersion.parse("1.9.0") < GiteaVersion.parse("1.26.0"))
    assertTrue(GiteaVersion.parse("28.0.1") > GiteaVersion.parse("28.0.0"))
    assertEquals(0, GiteaVersion.parse("28.1.0+abc").compareTo(GiteaVersion(28, 1, 0)))
  }

  @Test
  fun `rejects garbage`() {
    assertThrows<IllegalArgumentException> { GiteaVersion.parse("development") }
    assertNull(GiteaVersion.parseOrNull(""))
    assertNull(GiteaVersion.parseOrNull(null))
  }

  @Test
  fun `minimum version gate`() {
    assertTrue(GiteaServerMetadata(GiteaVersion.parse("28.0.0")).isSupported)
    assertTrue(GiteaServerMetadata(GiteaVersion.parse("28.1.0")).isSupported)
    assertFalse(GiteaServerMetadata(GiteaVersion.parse("1.26.0")).isSupported)
    assertFalse(GiteaServerMetadata(GiteaVersion.parse("7.0.0+gitea-1.21.0")).isSupported)
    assertThrows<GiteaUnsupportedServerException> { GiteaServerMetadata(GiteaVersion.parse("1.26.0")).checkSupported(acceptPreReleases = false) }
  }

  @Test
  fun `recognizes pre-releases`() {
    for (v in listOf("29.0.0-rc1", "28.2.0-dev", "1.23.0+dev-12-gabc", "v29.0.0-rc1", "28.1.0+DEV-3")) {
      assertTrue(GiteaVersion.parse(v).isPreRelease, v)
    }
    for (v in listOf("28.1.0", "28", "28.1.0+abc", "7.0.0+gitea-1.21.0")) {
      assertFalse(GiteaVersion.parse(v).isPreRelease, v)
    }
    assertFalse(GiteaVersion(29, 0, 0).isPreRelease)
  }

  @Test
  fun `pre-releases need the setting and gate like their release`() {
    val rc = GiteaServerMetadata(GiteaVersion.parse("29.0.0-rc1"))
    val e = assertThrows<GiteaUnsupportedServerException> { rc.checkSupported(acceptPreReleases = false) }
    assertTrue(e.preRelease)
    assertTrue(e.localizedMessage.contains("Accept Gitea pre-release versions"), e.localizedMessage)
    assertEquals(rc, rc.checkSupported(acceptPreReleases = true))
    assertEquals(0, rc.version.compareTo(GiteaVersion(29, 0, 0)))
    assertTrue(rc.version >= GiteaVersion(29, 0, 0))

    // the minimum still applies to the numeric part, with its own message
    val old = assertThrows<GiteaUnsupportedServerException> { GiteaServerMetadata(GiteaVersion.parse("1.26.0-rc1")).checkSupported(true) }
    assertFalse(old.preRelease)
    GiteaServerMetadata(GiteaVersion.parse("28.1.0")).checkSupported(acceptPreReleases = false)
  }
}
