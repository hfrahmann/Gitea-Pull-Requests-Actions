package de.hendrikfrahmann.giteaplugin.ui.action

import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.repository.GiteaRepositoryCoordinates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GiteaWebUrlsTest {
  private val sha = "82600f6529ee08153a6ca9a50f4b66348523cbec"
  private val repo = GiteaRepositoryCoordinates(GiteaServerPath("http://127.0.0.1:3000"),
                                                GiteaRepositoryPath("intellij-dev", "plugin-playground")).getWebURI()
  private val subpathRepo = GiteaRepositoryCoordinates(GiteaServerPath("https://git.example.com/gitea/"),
                                                       GiteaRepositoryPath("intellij-dev", "plugin-playground")).getWebURI()

  @Test
  fun `commit, pull request and actions run`() {
    assertEquals("http://127.0.0.1:3000/intellij-dev/plugin-playground/commit/$sha", GiteaWebUrls.commit(repo, sha).toString())
    assertEquals("http://127.0.0.1:3000/intellij-dev/plugin-playground/pulls/1", GiteaWebUrls.pullRequest(repo, 1).toString())
    assertEquals("http://127.0.0.1:3000/intellij-dev/plugin-playground/actions/runs/15", GiteaWebUrls.actionsRun(repo, 15).toString())
  }

  @Test
  fun `file permalink with lines`() {
    val base = "http://127.0.0.1:3000/intellij-dev/plugin-playground/src/commit/$sha/src/Hello.kt"
    assertEquals(base, GiteaWebUrls.fileAtCommit(repo, sha, "src/Hello.kt").toString())
    assertEquals("$base#L1", GiteaWebUrls.fileAtCommit(repo, sha, "src/Hello.kt", 0..0).toString())
    assertEquals("$base#L10-L20", GiteaWebUrls.fileAtCommit(repo, sha, "src/Hello.kt", 9..19).toString())
  }

  @Test
  fun `file on branch keeps slashes and encodes segments`() {
    assertEquals("http://127.0.0.1:3000/intellij-dev/plugin-playground/src/branch/feature/greeting/src/My%20File%23.kt#L3",
                 GiteaWebUrls.fileOnBranch(repo, "feature/greeting", "src/My File#.kt", 2..2).toString())
  }

  @Test
  fun `subpath server`() {
    assertEquals("https://git.example.com/gitea/intellij-dev/plugin-playground/commit/$sha", GiteaWebUrls.commit(subpathRepo, sha).toString())
    assertEquals("https://git.example.com/gitea/intellij-dev/plugin-playground/src/commit/$sha/a/b.txt#L2-L3",
                 GiteaWebUrls.fileAtCommit(subpathRepo, sha, "a/b.txt", 1..2).toString())
  }
}
