package de.hendrikfrahmann.giteaplugin.repository

import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountCredentials
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountManager
import de.hendrikfrahmann.giteaplugin.settings.GiteaSettings
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

/** Remote detection with a real git repository (needs the `git` executable). */
class GiteaProjectsManagerTest : HeavyPlatformTestCase() {
  private lateinit var repoDir: File

  override fun setUp() {
    super.setUp()
    repoDir = Files.createTempDirectory("gitea-detect").toFile()
    git("init", "-q")
    git("remote", "add", "origin", "http://127.0.0.1:3000/intellij-dev/plugin-playground.git")
    git("remote", "add", "intranet", "git@one.intranet:intellij-dev/plugin-playground.git")
    git("remote", "add", "github", "https://github.com/JetBrains/intellij-community.git")
    val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repoDir)!!
    ProjectLevelVcsManager.getInstance(project).setDirectoryMappings(listOf(VcsDirectoryMapping(root.path, "Git")))
    GiteaServersManager.getInstance().probe = { false } // no network: only accounts and mappings count
  }

  override fun tearDown() {
    try {
      runBlocking { GiteaAccountManager.getInstance().updateAccounts(emptyMap()) }
      GiteaSettings.getInstance().hostMappingsText = ""
      GiteaServersManager.getInstance().resetProbe()
      repoDir.deleteRecursively()
    }
    finally {
      super.tearDown()
    }
  }

  private fun git(vararg args: String) {
    val process = ProcessBuilder(listOf("git") + args).directory(repoDir).redirectErrorStream(true).start()
    check(process.waitFor() == 0) { process.inputStream.bufferedReader().readText() }
  }

  private fun awaitRepositories(count: Int): Set<GiteaProjectMapping> {
    val manager = GiteaProjectsManager.getInstance(project)
    var result: Set<GiteaProjectMapping> = emptySet()
    PlatformTestUtil.waitWithEventsDispatching("Gitea repositories not detected: ${manager.knownRepositoriesState.value}", {
      result = manager.knownRepositoriesState.value
      result.size == count && GitRepositoryManager.getInstance(project).repositories.isNotEmpty()
    }, 20)
    return result
  }

  fun `test remotes of account servers and mapped hosts are detected`() {
    val server = GiteaServerPath("http://127.0.0.1:3000")
    runBlocking { GiteaAccountManager.getInstance().updateAccount(GiteaAccount("intellij-dev", server), GiteaAccountCredentials.Token("t")) }

    val byAccount = awaitRepositories(1).single()
    assertEquals("origin", byAccount.gitRemote.name)
    assertEquals(server, byAccount.repository.serverPath)
    assertEquals(GiteaRepositoryPath("intellij-dev", "plugin-playground"), byAccount.repository.path)

    GiteaSettings.getInstance().hostMappingsText = "one.intranet=http://127.0.0.1:3000"
    val all = awaitRepositories(2)
    assertEquals(setOf("origin", "intranet"), all.map { it.gitRemote.name }.toSet())
    assertTrue(all.all { it.repository.serverPath == server })
  }
}
