package de.hendrikfrahmann.giteaplugin.actions

import de.hendrikfrahmann.giteaplugin.actions.data.GiteaActionStatus
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaRun
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaActionRunDTO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Which runs "Current Branch" shows: also those of branch commits on Gitea that aren't pulled yet. */
class GiteaCurrentBranchRunsTest {
  private fun run(headSha: String?) =
    GiteaRun(1, 1, 1, "t", "ci.yml", null, "pull_request", GiteaActionStatus.SUCCESS, null, headSha, null, 6, null, null, null, null)

  @Test
  fun `the branch tip on Gitea is queried next to the local head and the PR head`() {
    assertEquals(setOf("local", "pr", "remote"), GiteaCurrentBranchRuns.heads(setOf("local", "pr"), "remote"))
    // up to date: one query per commit
    assertEquals(setOf("local"), GiteaCurrentBranchRuns.heads(setOf("local"), "local"))
    // branch not on Gitea (404)
    assertEquals(setOf("local"), GiteaCurrentBranchRuns.heads(setOf("local"), null))
  }

  @Test
  fun `the tip is looked up for the tracked branch on the connected remote only`() {
    assertEquals("feature/x", GiteaCurrentBranchRuns.remoteBranch("x", "origin", "feature/x", "origin"))
    assertEquals("local-name", GiteaCurrentBranchRuns.remoteBranch("local-name", null, null, "origin"))
    assertNull(GiteaCurrentBranchRuns.remoteBranch("x", "fork", "x", "origin"))
  }

  @Test
  fun `results of all queries are merged without duplicates, newest first, up to the page size`() {
    val byLocalHead = listOf(GiteaActionRunDTO(3), GiteaActionRunDTO(1))
    val byRemoteTip = listOf(GiteaActionRunDTO(7), GiteaActionRunDTO(5))
    val byBranch = listOf(GiteaActionRunDTO(7), GiteaActionRunDTO(3), GiteaActionRunDTO(2))
    assertEquals(listOf(7L, 5L, 3L, 2L, 1L), GiteaCurrentBranchRuns.merge(listOf(byLocalHead, byRemoteTip, byBranch), 30).map { it.id })
    assertEquals(listOf(7L, 5L), GiteaCurrentBranchRuns.merge(listOf(byLocalHead, byRemoteTip, byBranch), 2).map { it.id })
  }

  @Test
  fun `only runs of the checked-out commit are marked`() {
    assertTrue(GiteaCurrentBranchRuns.isCheckedOut(run("local"), "local"))
    assertFalse(GiteaCurrentBranchRuns.isCheckedOut(run("remote"), "local"))
    assertFalse(GiteaCurrentBranchRuns.isCheckedOut(run(null), null))
  }
}
