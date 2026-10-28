package de.hendrikfrahmann.giteaplugin.actions

import de.hendrikfrahmann.giteaplugin.actions.data.GiteaActionStatus
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaRun
import de.hendrikfrahmann.giteaplugin.actions.ui.GiteaActionsUiUtil
import de.hendrikfrahmann.giteaplugin.actions.ui.tree.GiteaActionsNode
import de.hendrikfrahmann.giteaplugin.actions.ui.tree.GiteaActionsTreeController
import de.hendrikfrahmann.giteaplugin.actions.ui.tree.GiteaActionsTreeModel
import de.hendrikfrahmann.giteaplugin.actions.ui.tree.childNodes
import de.hendrikfrahmann.giteaplugin.actions.ui.tree.data
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaActionRunDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaActionRunsDTO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.SwingUtilities

/** Actions logic below the UI: job links, status bar summary, tree reconciliation, download file handling. */
class GiteaActionsUiLogicTest {
  private fun run(id: Long, status: GiteaActionStatus, workflow: String = "ci.yml") =
    GiteaRun(id, id, 1, "t$id", workflow, null, "push", status, "main", null, null, null, null, null, null, null)

  @Test
  fun `job links of the repository are recognized, others are not`() {
    val repo = URI("https://git.example.com/sub/intellij-dev/plugin-playground")
    assertEquals(24L to 49L, GiteaActionsNavigator.parseJobLink(repo, "https://git.example.com/sub/intellij-dev/plugin-playground/actions/runs/24/jobs/49"))
    assertEquals(24L to 49L, GiteaActionsNavigator.parseJobLink(repo, "http://127.0.0.1:3000/sub/intellij-dev/plugin-playground/actions/runs/24/jobs/49/"))
    assertNull(GiteaActionsNavigator.parseJobLink(repo, "https://git.example.com/sub/someone/else/actions/runs/24/jobs/49"))
    assertNull(GiteaActionsNavigator.parseJobLink(repo, "https://ci.example.com/build/24"))
    assertNull(GiteaActionsNavigator.parseJobLink(repo, "https://git.example.com/sub/intellij-dev/plugin-playground/actions/runs/24"))
  }

  @Test
  fun `summary counts running runs and workflows whose latest finished run failed`() {
    val summary = GiteaActionsSummary.of(listOf(
      run(5, GiteaActionStatus.RUNNING),
      run(4, GiteaActionStatus.SUCCESS),
      run(3, GiteaActionStatus.FAILURE),
      run(2, GiteaActionStatus.FAILURE, workflow = "lint.yml"),
      run(1, GiteaActionStatus.SUCCESS, workflow = "lint.yml"),
    ))
    assertEquals(GiteaActionsSummary(running = 1, failed = 1, total = 5), summary)
  }

  @Test
  fun `durations are short`() {
    assertEquals("850ms", GiteaActionsUiUtil.formatDuration(850))
    assertEquals("42s", GiteaActionsUiUtil.formatDuration(42_000))
    assertEquals("3m 5s", GiteaActionsUiUtil.formatDuration(185_000))
    assertEquals("1h 2m", GiteaActionsUiUtil.formatDuration(3_720_000))
  }

  @Test
  fun `tree model keeps nodes by key, inserts new ones and drops missing ones`() = SwingUtilities.invokeAndWait {
    val model = GiteaActionsTreeModel()
    val root = model.rootNode
    model.setChildren(root, listOf(GiteaActionsNode.Run(run(2, GiteaActionStatus.RUNNING), "CI", false), GiteaActionsNode.Run(run(1, GiteaActionStatus.SUCCESS), "CI", false)))
    val kept = root.childNodes()[0]
    assertTrue(model.isLoadPending(kept), "expandable rows get a loading placeholder")

    model.setChildren(root, listOf(GiteaActionsNode.Run(run(3, GiteaActionStatus.QUEUED), "CI", false), GiteaActionsNode.Run(run(2, GiteaActionStatus.SUCCESS), "CI", false)))
    assertEquals(listOf("run:3", "run:2"), root.childNodes().map { it.data?.key })
    assertSame(kept, root.childNodes()[1], "the row for run 2 is the same tree node")
    assertEquals(GiteaActionStatus.SUCCESS, (kept.data as GiteaActionsNode.Run).run.status)

    model.setChildren(kept, listOf(GiteaActionsNode.Message("none", false, "run:2")))
    model.resetChildren(kept)
    assertTrue(model.isLoadPending(kept))
  }

  @Test
  fun `rows added to the empty invisible root are shown`() = SwingUtilities.invokeAndWait {
    val model = GiteaActionsTreeModel()
    val tree = javax.swing.JTree(model).apply { isRootVisible = false }
    model.setChildren(model.rootNode, listOf(GiteaActionsNode.Message("No runs", false, "")))
    GiteaActionsTreeModel.ensureRootExpanded(tree, model)
    assertEquals(1, tree.rowCount)
  }

  @Test
  fun `an expanded node stays expanded when its loading placeholder is replaced`() = SwingUtilities.invokeAndWait {
    val model = GiteaActionsTreeModel()
    val tree = javax.swing.JTree(model).apply { isRootVisible = false }
    model.setChildren(model.rootNode, listOf(GiteaActionsNode.Run(run(1, GiteaActionStatus.SUCCESS), "CI", false)))
    GiteaActionsTreeModel.ensureRootExpanded(tree, model)
    val runNode = model.rootNode.childNodes().single()
    val path = javax.swing.tree.TreePath(runNode.path)
    tree.expandPath(path)
    assertTrue(tree.isExpanded(path))

    model.setChildren(runNode, listOf(GiteaActionsNode.Message("no jobs", false, "run:1")))
    assertTrue(tree.isExpanded(path), "removing the placeholder first would collapse the node")
  }

  @Test
  fun `load more pages through more than gitea's 50 item limit`() = kotlinx.coroutines.runBlocking {
    var runs = (70L downTo 1L).map { GiteaActionRunDTO(it) }
    // like Gitea: limit capped at 50, newest first
    val server: suspend (Int, Int) -> GiteaActionRunsDTO = { page, limit ->
      val size = minOf(limit, 50)
      GiteaActionRunsDTO(runs.size.toLong(), runs.drop((page - 1) * size).take(size))
    }
    val (first, moreAfterFirst) = GiteaActionsTreeController.loadRunPages(1, 30, server)
    assertEquals(30, first.size)
    assertTrue(moreAfterFirst)

    val (two, moreAfterTwo) = GiteaActionsTreeController.loadRunPages(2, 30, server)
    assertEquals((70L downTo 11L).toList(), two.map { it.id })
    assertTrue(moreAfterTwo)

    val (all, moreAfterAll) = GiteaActionsTreeController.loadRunPages(3, 30, server)
    assertEquals(70, all.size)
    assertFalse(moreAfterAll, "the last page is short")

    // a new run between two page requests shifts page 2 by one: no duplicate
    var calls = 0
    val shifting: suspend (Int, Int) -> GiteaActionRunsDTO = { page, limit ->
      if (calls++ == 1) {
        runs = listOf(GiteaActionRunDTO(71)) + runs
      }
      server(page, limit)
    }
    val (shifted, _) = GiteaActionsTreeController.loadRunPages(2, 30, shifting)
    assertEquals(shifted.map { it.id }.distinct(), shifted.map { it.id })
    assertEquals(59, shifted.size)
  }

  @Test
  fun `downloads get unique names and unpack only inside their folder`(@TempDir dir: Path) {
    assertEquals("a_b_c.zip", GiteaActionsDownloads.safeFileName("a/b:c.zip"))
    Files.writeString(dir.resolve("build.zip"), "x")
    assertEquals(dir.resolve("build (1).zip"), GiteaActionsDownloads.uniqueFile(dir, "build.zip"))

    val zip = dir.resolve("artifact.zip")
    ZipOutputStream(Files.newOutputStream(zip)).use { out ->
      out.putNextEntry(ZipEntry("out/build-info.txt"))
      out.write("built".toByteArray())
      out.closeEntry()
    }
    val folder = GiteaActionsDownloads.unzip(zip)
    assertEquals(dir.resolve("artifact"), folder)
    assertEquals("built", Files.readString(folder.resolve("out/build-info.txt")))

    val evil = dir.resolve("evil.zip")
    ZipOutputStream(Files.newOutputStream(evil)).use { out ->
      out.putNextEntry(ZipEntry("../escaped.txt"))
      out.write("no".toByteArray())
      out.closeEntry()
    }
    assertThrows(IOException::class.java) { GiteaActionsDownloads.unzip(evil) }
    assertTrue(Files.notExists(dir.resolve("escaped.txt")))
  }
}
