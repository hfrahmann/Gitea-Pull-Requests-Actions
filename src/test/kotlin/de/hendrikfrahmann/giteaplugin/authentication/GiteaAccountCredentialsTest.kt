package de.hendrikfrahmann.giteaplugin.authentication

import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountCredentials
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.MessageDigest
import java.util.Base64

class GiteaAccountCredentialsTest {
  private val mapper = GiteaAccountCredentials.Mapper

  @Test
  fun `token round trip and plain token fallback`() {
    val restored = mapper.deserialize(mapper.serialize(GiteaAccountCredentials.Token("pat123")))
    assertTrue(restored is GiteaAccountCredentials.Token)
    assertEquals("pat123", restored.accessToken)
    // e.g. a value written by hand or by an older version
    assertEquals("raw-token", (mapper.deserialize("raw-token") as GiteaAccountCredentials.Token).accessToken)
  }

  @Test
  fun `oauth round trip`() {
    val oauth = GiteaAccountCredentials.OAuth("at", "rt", "client", 1_900_000_000)
    val restored = mapper.deserialize(mapper.serialize(oauth)) as GiteaAccountCredentials.OAuth
    assertEquals(listOf("at", "rt", "client", 1_900_000_000L), listOf(restored.accessToken, restored.refreshToken, restored.clientId, restored.expiresAt))
    assertEquals("Bearer at", restored.toApiCredentials().authorizationHeaderValue)
    assertEquals("token pat", GiteaAccountCredentials.Token("pat").toApiCredentials().authorizationHeaderValue)
  }

  @Test
  fun `expiry uses a safety margin`() {
    val oauth = GiteaAccountCredentials.OAuth("at", "rt", "c", expiresAt = 1000)
    assertTrue(oauth.isAccessTokenValid(nowSeconds = 900))
    assertFalse(oauth.isAccessTokenValid(nowSeconds = 950))
    assertFalse(oauth.isAccessTokenValid(nowSeconds = 1001))
  }

  @Test
  fun `toString hides secrets`() {
    assertFalse(GiteaAccountCredentials.Token("secret-pat").toString().contains("secret-pat"))
    val oauth = GiteaAccountCredentials.OAuth("secret-at", "secret-rt", "c", 0).toString()
    assertFalse(oauth.contains("secret-at") || oauth.contains("secret-rt"))
  }

  @Test
  fun `pkce challenge is base64url sha256 of the verifier`() {
    val verifier = GiteaOAuthService.randomToken(32)
    assertEquals(43, verifier.length)
    assertTrue(verifier.matches(Regex("[A-Za-z0-9_-]+")))
    val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
    assertEquals(expected, GiteaOAuthService.codeChallengeS256(verifier))
    // RFC 7636 appendix B
    assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", GiteaOAuthService.codeChallengeS256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
  }

  @Test
  fun `server url from user input`() {
    assertEquals("https://gitea.com", GiteaServerPath.fromUserInput("gitea.com").uri)
    assertEquals("https://git.example.com/sub", GiteaServerPath.fromUserInput(" git.example.com/sub/ ").uri)
    assertEquals("http://127.0.0.1:3000", GiteaServerPath.fromUserInput("http://127.0.0.1:3000/api/v1/").uri)
    assertEquals("https://example.com", GiteaServerPath.fromUserInput("https://example.com/api/swagger").uri)
    assertEquals("https://example.com/gitea", GiteaServerPath.fromUserInput("https://example.com/gitea/api/v1?x=1").uri)
    assertThrows<IllegalArgumentException> { GiteaServerPath.fromUserInput("ftp://x") }
    assertThrows<IllegalArgumentException> { GiteaServerPath.fromUserInput("  ") }
  }

  @Test
  fun `missing scopes`() {
    assertEquals(emptyList<String>(), GiteaSecurityUtil.missingScopes(listOf("all")))
    assertEquals(emptyList<String>(), GiteaSecurityUtil.missingScopes(listOf("write:repository", "write:issue", "write:user", "write:organization")))
    assertEquals(listOf("write:issue", "read:organization"), GiteaSecurityUtil.missingScopes(listOf("write:repository", "read:issue", "read:user")))
  }

  @Test
  fun `loopback query parsing`() {
    assertEquals(mapOf("code" to "a b", "state" to "x=y", "empty" to ""), LoopbackListener.parseQuery("code=a+b&state=x%3Dy&empty"))
    assertEquals(emptyMap<String, String>(), LoopbackListener.parseQuery(null))
  }
}
