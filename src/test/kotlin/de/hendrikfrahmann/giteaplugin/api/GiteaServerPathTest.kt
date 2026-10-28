package de.hendrikfrahmann.giteaplugin.api

import com.intellij.util.xmlb.XmlSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI

class GiteaServerPathTest {
  @Test
  fun `normalizes scheme, host, default port and trailing slash`() {
    assertEquals("https://git.example.com", GiteaServerPath("HTTPS://Git.Example.com:443/").uri)
    assertEquals("http://127.0.0.1:3000", GiteaServerPath("http://127.0.0.1:3000").uri)
    assertEquals("https://example.com/gitea", GiteaServerPath(" https://example.com/gitea// ").uri)
    assertEquals(GiteaServerPath("https://example.com/gitea"), GiteaServerPath("https://example.com/gitea/"))
    assertEquals(GiteaServerPath("https://example.com").hashCode(), GiteaServerPath("https://example.com/").hashCode())
  }

  @Test
  fun `rejects invalid urls`() {
    assertThrows<IllegalArgumentException> { GiteaServerPath("") }
    assertThrows<IllegalArgumentException> { GiteaServerPath("example.com") }
    assertThrows<IllegalArgumentException> { GiteaServerPath("ftp://example.com") }
    assertThrows<IllegalArgumentException> { GiteaServerPath("https://") }
  }

  @Test
  fun `parts`() {
    val server = GiteaServerPath("https://example.com:8443/gitea")
    assertEquals("https", server.scheme)
    assertEquals("example.com", server.host)
    assertEquals(8443, server.port)
    assertEquals("/gitea", server.pathPrefix)
    assertEquals("", GiteaServerPath("https://example.com").pathPrefix)
  }

  @Test
  fun `api and web uris`() {
    val server = GiteaServerPath("https://example.com/gitea")
    assertEquals(URI("https://example.com/gitea/api/v1/"), server.toApiUri())
    assertEquals(URI("https://example.com/gitea/"), server.toWebUri())
    assertEquals(URI("https://example.com/gitea/api/v1/repos/o%20w/r%C3%A9po/pulls"), server.apiUri("repos", "o w", "répo", "pulls"))
    assertEquals(URI("https://example.com/gitea/owner/repo/pulls/1"), server.webUri("owner", "repo", "pulls", "1"))
    assertEquals(URI("https://example.com/gitea/api/v1/repos/feature%2Fx"), server.apiUri("repos", "feature/x"))
  }

  @Test
  fun `rebase with known remote root`() {
    val server = GiteaServerPath("http://127.0.0.1:3000")
    val root = URI("https://one.intranet/")
    assertEquals(
      URI("http://127.0.0.1:3000/intellij-dev/plugin-playground/pulls/1"),
      server.rebase("https://one.intranet/intellij-dev/plugin-playground/pulls/1", root),
    )
    // Different subpaths on both sides
    val sub = GiteaServerPath("https://proxy.example.com/git")
    assertEquals(
      URI("https://proxy.example.com/git/o/r/actions/artifacts/5/zip?x=1"),
      sub.rebase("http://internal:3000/gitea/o/r/actions/artifacts/5/zip?x=1", URI("http://internal:3000/gitea/")),
    )
  }

  @Test
  fun `rebase without remote root swaps the origin`() {
    val server = GiteaServerPath("https://example.com/gitea")
    assertEquals(URI("https://example.com/gitea/o/r"), server.rebase("http://other:3000/o/r"))
    assertEquals(URI("https://example.com/gitea/o/r"), server.rebase("http://other:3000/gitea/o/r"))
    assertEquals(URI("https://example.com/gitea/o/r#issuecomment-1"), server.rebase("/o/r#issuecomment-1"))
  }

  @Test
  fun `remote root from known suffix`() {
    assertEquals(URI("https://one.intranet/"), GiteaServerPath.remoteRootOf("https://one.intranet/o/r", "o/r"))
    assertEquals(URI("http://h:3000/gitea/"), GiteaServerPath.remoteRootOf("http://h:3000/gitea/o/r/", "/o/r"))
    assertNull(GiteaServerPath.remoteRootOf("https://one.intranet/x/y", "o/r"))
  }

  @Test
  fun `owns only same origin below prefix`() {
    val server = GiteaServerPath("https://example.com/gitea")
    assertTrue(server.owns(URI("https://example.com/gitea/api/v1/user")))
    assertTrue(server.owns(URI("https://EXAMPLE.com:443/gitea/")))
    assertFalse(server.owns(URI("https://example.com/giteax/api")))
    assertFalse(server.owns(URI("https://example.com/other")))
    assertFalse(server.owns(URI("http://example.com/gitea/api")))
    assertFalse(server.owns(URI("https://example.com:8443/gitea/api")))
    assertFalse(server.owns(URI("https://evil.com/gitea/api")))
    assertFalse(server.owns(URI("/gitea/api")))
  }

  @Test
  fun `xml round trip`() {
    val element = XmlSerializer.serialize(GiteaServerPath("https://example.com/gitea"))
    assertEquals("server", element.name)
    assertEquals("https://example.com/gitea", element.getAttributeValue("uri"))
    assertEquals(GiteaServerPath("https://example.com/gitea"), XmlSerializer.deserialize(element, GiteaServerPath::class.java))
  }
}
