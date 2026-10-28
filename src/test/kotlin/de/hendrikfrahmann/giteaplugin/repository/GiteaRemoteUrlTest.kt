package de.hendrikfrahmann.giteaplugin.repository

import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.repository.ui.GiteaSelection
import git4idea.remote.GitRemoteUrlCoordinates
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.net.URI

class GiteaRemoteUrlTest {
  private val playground = GiteaRepositoryPath("intellij-dev", "plugin-playground")

  @Test
  fun `parses https, ssh and scp-like remotes`() {
    GiteaRemoteUrl.parse("https://Git.Example.com/intellij-dev/plugin-playground.git")!!.let {
      assertEquals("https", it.scheme); assertEquals("git.example.com", it.host); assertEquals(-1, it.port)
      assertEquals("", it.pathPrefix); assertEquals(playground, it.repositoryPath); assertTrue(it.isHttp)
    }
    GiteaRemoteUrl.parse("http://127.0.0.1:3000/intellij-dev/plugin-playground")!!.let {
      assertEquals(3000, it.port); assertEquals(playground, it.repositoryPath)
    }
    GiteaRemoteUrl.parse("https://user@example.com/gitea/sub/intellij-dev/plugin-playground.git/")!!.let {
      assertEquals("gitea/sub", it.pathPrefix); assertEquals(playground, it.repositoryPath)
    }
    GiteaRemoteUrl.parse("git@one.intranet:intellij-dev/plugin-playground.git")!!.let {
      assertEquals("ssh", it.scheme); assertEquals("one.intranet", it.host); assertFalse(it.isHttp); assertEquals(playground, it.repositoryPath)
    }
    GiteaRemoteUrl.parse("ssh://git@one.intranet:2222/intellij-dev/plugin-playground.git")!!.let {
      assertEquals("ssh", it.scheme); assertEquals(2222, it.port); assertEquals(playground, it.repositoryPath)
    }
  }

  @Test
  fun `rejects urls without owner and repo`() {
    assertNull(GiteaRemoteUrl.parse("https://example.com/only-one"))
    assertNull(GiteaRemoteUrl.parse("/local/path/repo"))
    assertNull(GiteaRemoteUrl.parse("not a url"))
    assertNull(GiteaRemoteUrl.parse("https://example.com/owner/.git"))
  }

  @Test
  fun `server matching respects host, port and prefix for http remotes`() {
    val dev = GiteaServerPath("http://127.0.0.1:3000")
    val sub = GiteaServerPath("https://example.com/gitea")
    fun serves(server: GiteaServerPath, url: String) = GiteaServerResolver.serves(server, GiteaRemoteUrl.parse(url)!!)
    assertTrue(serves(dev, "http://127.0.0.1:3000/o/r.git"))
    assertFalse(serves(dev, "http://127.0.0.1:4000/o/r.git"))
    assertFalse(serves(dev, "http://127.0.0.2:3000/o/r.git"))
    assertTrue(serves(dev, "git@127.0.0.1:o/r.git")) // SSH: host is enough
    assertTrue(serves(sub, "https://example.com/gitea/o/r.git"))
    assertTrue(serves(sub, "https://example.com:443/gitea/o/r"))
    assertFalse(serves(sub, "https://example.com/o/r.git"))
    assertFalse(serves(sub, "https://example.com/other/o/r.git"))
  }

  @Test
  fun `host mapping wins over known servers`() {
    val dev = GiteaServerPath("http://127.0.0.1:3000")
    val mappings = GiteaHostMappings.parse("one.intranet=http://127.0.0.1:3000\n# comment\nGit.Corp")
    assertEquals(dev, GiteaServerResolver.resolve("https://one.intranet/intellij-dev/plugin-playground.git", emptySet(), mappings))
    assertEquals(dev, GiteaServerResolver.resolve("git@one.intranet:intellij-dev/plugin-playground.git", emptySet(), mappings))
    assertEquals(GiteaServerPath("https://git.corp"), GiteaServerResolver.resolve("git@git.corp:o/r.git", emptySet(), mappings))
    assertEquals(dev, GiteaServerResolver.resolve("http://127.0.0.1:3000/o/r", setOf(dev), GiteaHostMappings.EMPTY))
    assertNull(GiteaServerResolver.resolve("https://github.com/o/r.git", setOf(dev), mappings))
  }

  @Test
  fun `host mappings parsing and validation`() {
    val entries = GiteaHostMappings.parseEntries("a.example=https://x.example/gitea, b.example ,bad host=x,c.example=ftp://nope")
    assertEquals(4, entries.size)
    assertEquals("a.example" to GiteaServerPath("https://x.example/gitea"), entries[0].getOrNull())
    assertEquals("b.example" to GiteaServerPath("https://b.example"), entries[1].getOrNull())
    assertTrue(entries[2].isFailure)
    assertTrue(entries[3].isFailure)
    assertEquals(setOf("a.example", "b.example"), GiteaHostMappings.parse("a.example=https://x.example/gitea,b.example").hosts)
  }

  @Test
  fun `probe candidates`() {
    fun candidates(url: String) = GiteaServerResolver.candidates(GiteaRemoteUrl.parse(url)!!).map { it.uri }
    assertEquals(listOf("http://127.0.0.1:3000", "https://127.0.0.1", "http://127.0.0.1"), candidates("http://127.0.0.1:3000/o/r.git"))
    assertEquals(listOf("https://example.com/gitea", "http://example.com/gitea"), candidates("https://example.com/gitea/o/r.git"))
    assertEquals(listOf("https://host", "http://host", "http://host:3000"), candidates("git@host:o/r.git"))
  }

  @Test
  fun `host of git credential urls`() {
    assertEquals("one.intranet", GiteaServerResolver.hostOf("https://one.intranet"))
    assertEquals("127.0.0.1", GiteaServerResolver.hostOf("http://127.0.0.1:3000"))
    assertEquals("host", GiteaServerResolver.hostOf("git@host:o/r.git"))
  }

  @Test
  fun `coordinates and web uri`() {
    val server = GiteaServerPath("https://example.com/gitea")
    val coordinates = GiteaRepositoryCoordinates.create(server, "git@example.com:o/r.git")!!
    assertEquals(GiteaRepositoryPath("o", "r"), coordinates.path)
    assertEquals(URI("https://example.com/gitea/o/r"), coordinates.getWebURI())
  }

  @Test
  fun `preselection prefers saved, then default, then single`() {
    val server = GiteaServerPath("http://127.0.0.1:3000")
    val other = GiteaServerPath("https://other.example")
    val origin = mapping(server, "http://127.0.0.1:3000/o/r.git", "origin")
    val fork = mapping(server, "http://127.0.0.1:3000/me/r.git", "fork")
    val dev = GiteaAccount("dev", server)
    val reviewer = GiteaAccount("reviewer", server)
    val elsewhere = GiteaAccount("dev", other)

    assertEquals(origin to dev, GiteaSelection.preselect(listOf(origin), listOf(dev, elsewhere), null, null, null))
    assertEquals(origin to null, GiteaSelection.preselect(listOf(origin), listOf(dev, reviewer), null, null, null))
    assertEquals(origin to reviewer, GiteaSelection.preselect(listOf(origin), listOf(dev, reviewer), reviewer, null, null))
    assertEquals(fork to dev, GiteaSelection.preselect(listOf(origin, fork), listOf(dev, reviewer), reviewer, fork.remote.url, dev.id))
    assertEquals(null to null, GiteaSelection.preselect(listOf(origin, fork), listOf(dev), null, null, null))
  }

  @Test
  fun `single repository and account connect without asking`() {
    val server = GiteaServerPath("http://127.0.0.1:3000")
    val origin = mapping(server, "http://127.0.0.1:3000/o/r.git", "origin")
    val fork = mapping(server, "http://127.0.0.1:3000/me/r.git", "fork")
    val dev = GiteaAccount("dev", server)
    val reviewer = GiteaAccount("reviewer", server)
    val elsewhere = GiteaAccount("dev", GiteaServerPath("https://other.example"))

    assertEquals(origin to dev, GiteaSelection.single(listOf(origin), listOf(dev, elsewhere)))
    assertNull(GiteaSelection.single(listOf(origin), listOf(dev, reviewer)))
    assertNull(GiteaSelection.single(listOf(origin), listOf(elsewhere)))
    assertNull(GiteaSelection.single(listOf(origin, fork), listOf(dev)))
  }

  private fun mapping(server: GiteaServerPath, url: String, remoteName: String): GiteaProjectMapping {
    val repository = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(GitRepository::class.java)) { _, method, _ ->
      if (method.name == "toString") {
        "repo"
      }
      else {
        throw UnsupportedOperationException(method.name)
      }
    } as GitRepository
    val remote = GitRemote(remoteName, listOf(url), emptyList(), emptyList(), emptyList())
    return GiteaProjectMapping.create(server, GitRemoteUrlCoordinates(url, remote, repository))!!
  }
}
