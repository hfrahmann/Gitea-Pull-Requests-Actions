package de.hendrikfrahmann.giteaplugin.pullrequest

import de.hendrikfrahmann.giteaplugin.api.GiteaJson
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaPullRequestDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaTimelineEventDTO
import de.hendrikfrahmann.giteaplugin.api.dto.hasConflicts
import de.hendrikfrahmann.giteaplugin.api.request.GiteaEditPullRequestRequest
import de.hendrikfrahmann.giteaplugin.api.request.GiteaMergePullRequestRequest
import de.hendrikfrahmann.giteaplugin.api.request.GiteaMergeStyle
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.GiteaMergeDialog
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaPullRequestMergeViewModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Merge pieces without a server: request JSON, draft prefix, auto merge state from the timeline. */
class GiteaMergeTest {
  @Test
  fun `merge request uses gitea's field names and leaves out defaults`() {
    val json = String(GiteaJson.toJsonBytes(GiteaMergePullRequestRequest("squash", "Title", null, true, "abc", null)))
    assertEquals("""{"Do":"squash","merge_title_field":"Title","delete_branch_after_merge":true,"head_commit_id":"abc"}""", json)
    assertEquals("""{"state":"closed"}""", String(GiteaJson.toJsonBytes(GiteaEditPullRequestRequest(state = "closed"))))
  }

  @Test
  fun `draft prefix is added once and removed in both spellings`() {
    assertEquals("WIP: Fix it", GiteaPullRequestMergeViewModel.draftTitle("Fix it", true))
    assertEquals("WIP: Fix it", GiteaPullRequestMergeViewModel.draftTitle("WIP: Fix it", true))
    assertEquals("Fix it", GiteaPullRequestMergeViewModel.draftTitle("WIP: Fix it", false))
    assertEquals("Fix it", GiteaPullRequestMergeViewModel.draftTitle("[wip] Fix it", false))
    assertEquals("Fix it", GiteaPullRequestMergeViewModel.draftTitle("Fix it", false))
  }

  @Test
  fun `auto merge is scheduled until cancelled or merged`() {
    fun events(vararg types: String) = types.mapIndexed { i, t -> GiteaTimelineEventDTO(i.toLong(), t) }
    assertFalse(GiteaPullRequestMergeViewModel.isAutoMergeScheduled(events("pull_push", "comment")))
    assertTrue(GiteaPullRequestMergeViewModel.isAutoMergeScheduled(events("pull_push", "pull_scheduled_merge", "comment")))
    assertFalse(GiteaPullRequestMergeViewModel.isAutoMergeScheduled(events("pull_scheduled_merge", "pull_cancel_scheduled_merge")))
    assertFalse(GiteaPullRequestMergeViewModel.isAutoMergeScheduled(events("pull_scheduled_merge", "merge_pull")))
    assertTrue(GiteaPullRequestMergeViewModel.isAutoMergeScheduled(events("pull_scheduled_merge", "pull_cancel_scheduled_merge", "pull_scheduled_merge")))
  }

  @Test
  fun `mergeable false means conflicts only for open non-draft PRs`() {
    fun pr(state: String, draft: Boolean, mergeable: Boolean?) = GiteaPullRequestDTO(1, 1, "t", state = state, draft = draft, mergeable = mergeable)
    assertTrue(pr("open", false, false).hasConflicts)
    assertFalse(pr("open", true, false).hasConflicts, "Gitea reports every draft as not mergeable")
    assertFalse(pr("closed", false, false).hasConflicts)
    assertFalse(pr("open", false, null).hasConflicts)
  }

  @Test
  fun `only styles with a merge commit take a commit text`() {
    assertEquals(listOf(GiteaMergeStyle.MERGE, GiteaMergeStyle.REBASE_MERGE, GiteaMergeStyle.SQUASH),
                 GiteaMergeStyle.entries.filter(GiteaMergeDialog::usesCommitText))
    assertEquals(GiteaMergeStyle.FAST_FORWARD_ONLY, GiteaMergeStyle.of("fast-forward-only"))
  }
}
