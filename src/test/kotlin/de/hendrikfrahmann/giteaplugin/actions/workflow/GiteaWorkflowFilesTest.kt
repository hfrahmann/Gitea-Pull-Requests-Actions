package de.hendrikfrahmann.giteaplugin.actions.workflow

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import de.hendrikfrahmann.giteaplugin.actions.workflow.run.GiteaWorkflowRunConfiguration
import de.hendrikfrahmann.giteaplugin.actions.workflow.yaml.GiteaWorkflowRunConfigurationProducer
import de.hendrikfrahmann.giteaplugin.actions.workflow.yaml.GiteaWorkflowRunLineMarkerContributor
import git4idea.repo.GitRepositoryManager
import org.jetbrains.yaml.YAMLTokenTypes
import java.io.File
import java.nio.file.Files

/** Which files Gitea runs, gutter icons and run configurations from them (needs `git`). */
class GiteaWorkflowFilesTest : HeavyPlatformTestCase() {
  private lateinit var repoDir: File
  private lateinit var root: VirtualFile

  override fun setUp() {
    super.setUp()
    repoDir = Files.createTempDirectory("gitea-workflows").toFile()
    val process = ProcessBuilder("git", "init", "-q").directory(repoDir).redirectErrorStream(true).start()
    check(process.waitFor() == 0) { process.inputStream.bufferedReader().readText() }
    write(".github/workflows/old.yml", WORKFLOW)
    root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repoDir)!!
    ProjectLevelVcsManager.getInstance(project).setDirectoryMappings(listOf(VcsDirectoryMapping(root.path, "Git")))
    PlatformTestUtil.waitWithEventsDispatching("git repository not detected", {
      GitRepositoryManager.getInstance(project).repositories.isNotEmpty()
    }, 20)
  }

  override fun tearDown() {
    try {
      repoDir.deleteRecursively()
    }
    finally {
      super.tearDown()
    }
  }

  private fun write(path: String, text: String): VirtualFile? {
    val file = File(repoDir, path)
    file.parentFile.mkdirs()
    file.writeText(text)
    return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
  }

  private fun key(file: VirtualFile, text: String): PsiElement = runReadActionBlocking {
    val psi = PsiManager.getInstance(project).findFile(file)!!
    PsiTreeUtil.collectElements(psi) { it.node.elementType == YAMLTokenTypes.SCALAR_KEY && it.text == text }.first()
  }

  fun `test github workflows count until gitea workflows exist`() {
    val old = root.findFileByRelativePath(".github/workflows/old.yml")!!
    assertTrue(GiteaWorkflowFiles.find(project, old)!!.isActive)
    assertEquals(".github/workflows", GiteaWorkflowFiles.activeDirectory(root))

    val ci = write(".gitea/workflows/sub/ci.yaml", WORKFLOW)!!
    write("README.yml", WORKFLOW)
    val active = GiteaWorkflowFiles.find(project, ci)!!
    assertTrue(active.isActive)
    assertEquals(".gitea/workflows/sub/ci.yaml", active.relativePath)
    assertEquals("sub/ci.yaml", active.workflowId)
    val inactive = GiteaWorkflowFiles.find(project, old)!!
    assertFalse(inactive.isActive)
    assertEquals(".gitea/workflows", inactive.activeDirectory)
    assertNull(GiteaWorkflowFiles.find(project, root.findChild("README.yml")!!))
    assertEquals(ci, GiteaWorkflowFiles.findLocal(root, "sub/ci.yaml"))
    assertNull(GiteaWorkflowFiles.findLocal(root, "old.yml"))
  }

  fun `test icons on jobs and job keys of active workflow files only`() {
    val ci = write(".gitea/workflows/ci.yml", WORKFLOW)!!
    val contributor = GiteaWorkflowRunLineMarkerContributor()
    fun hasIcon(file: VirtualFile, key: String) = runReadActionBlocking { contributor.getInfo(key(file, key)) != null }

    assertTrue(hasIcon(ci, "jobs"))
    assertTrue(hasIcon(ci, "build"))
    assertTrue(hasIcon(ci, "test"))
    assertFalse(hasIcon(ci, "name"))
    assertFalse(hasIcon(ci, "runs-on"))
    assertFalse(hasIcon(ci, "steps"))
    // .gitea/workflows exists now: Gitea ignores .github/workflows
    assertFalse(hasIcon(root.findFileByRelativePath(".github/workflows/old.yml")!!, "build"))
  }

  fun `test configurations from job and jobs keys`() {
    val ci = write(".gitea/workflows/ci.yml", WORKFLOW)!!
    val producer = RunConfigurationProducer.getInstance(GiteaWorkflowRunConfigurationProducer::class.java)
    fun configuration(element: PsiElement) =
      producer.createConfigurationFromContext(ConfigurationContext(element))!!.configuration as GiteaWorkflowRunConfiguration

    val job = configuration(key(ci, "test"))
    assertEquals(ci.path, job.workflowFile)
    assertEquals("test", job.job)
    assertEquals("ci.yml: test", job.name)
    // inside a job's body: still that job
    assertEquals("build", configuration(key(ci, "runs-on")).job)

    val all = configuration(key(ci, "jobs"))
    assertEquals("", all.job)
    assertEquals("ci.yml", all.name)
  }

  companion object {
    private val WORKFLOW = """
      name: CI
      on: [push, workflow_dispatch]
      jobs:
        build:
          runs-on: ubuntu-latest
          steps:
            - run: echo build
        test:
          needs: build
          steps:
            - run: echo test
    """.trimIndent()
  }
}
