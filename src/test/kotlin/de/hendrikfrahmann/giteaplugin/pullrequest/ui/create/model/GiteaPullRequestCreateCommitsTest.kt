package de.hendrikfrahmann.giteaplugin.pullrequest.ui.create.model

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.vcs.log.VcsCommitMetadata
import java.io.File
import java.nio.file.Files

/** Commits between base and head for the New Pull Request tab, with a real git repository (needs the `git` executable). */
class GiteaPullRequestCreateCommitsTest : HeavyPlatformTestCase() {
  private lateinit var repoDir: File

  override fun setUp() {
    super.setUp()
    repoDir = Files.createTempDirectory("gitea-create").toFile()
    git("init", "-q", "-b", "main")
    git("config", "user.name", "Test")
    git("config", "user.email", "test@example.com")
    commit("base")
  }

  override fun tearDown() {
    try {
      repoDir.deleteRecursively()
    }
    finally {
      super.tearDown()
    }
  }

  private fun git(vararg args: String): String {
    val process = ProcessBuilder(listOf("git") + args).directory(repoDir).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { output }
    return output.trim()
  }

  private fun commit(subject: String) {
    File(repoDir, "file.txt").appendText("$subject\n")
    git("add", ".")
    git("commit", "-q", "-m", subject)
  }

  fun `test all commits of the range, newest first`() {
    val base = git("rev-parse", "HEAD")
    git("checkout", "-q", "-b", "feature")
    commit("first")
    commit("second")
    commit("third")
    val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repoDir)!!

    val head = git("rev-parse", "HEAD")
    // git commands must not run on the EDT (the test thread)
    val (commits, empty) = ApplicationManager.getApplication().executeOnPooledThread<Pair<List<VcsCommitMetadata>, List<VcsCommitMetadata>>> {
      collectCommitsBetween(project, root, base, head) to collectCommitsBetween(project, root, base, base)
    }.get()
    assertEquals(listOf("third", "second", "first"), commits.map { it.subject })
    assertEmpty(empty)
  }
}
