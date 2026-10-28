package de.hendrikfrahmann.giteaplugin.pullrequest

import com.intellij.collaboration.ui.codereview.details.data.ReviewState
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.GiteaReviewersComponentFactory
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaReviewerState.APPROVED
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaReviewerState.CHANGES_REQUESTED
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaReviewerState.COMMENTED
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaReviewerState.PENDING
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The one-line summary that replaces the list of reviewers in the PR details when there are several. */
class GiteaReviewersSummaryTest {
  @Test
  fun `summary counts each state, teams as pending, in a fixed order`() {
    val states = listOf(PENDING, APPROVED, COMMENTED, APPROVED, CHANGES_REQUESTED, PENDING)

    assertEquals("Reviewers: 2 approved, 1 requested changes, 1 commented, 4 pending",
                 GiteaReviewersComponentFactory.summaryText(states, teamCount = 2))
  }

  @Test
  fun `summary leaves out states nobody is in`() {
    assertEquals("Reviewers: 3 approved", GiteaReviewersComponentFactory.summaryText(listOf(APPROVED, APPROVED, APPROVED), teamCount = 0))
    assertEquals("Reviewers: 2 pending", GiteaReviewersComponentFactory.summaryText(emptyList(), teamCount = 2))
  }

  @Test
  fun `requested changes win, approved only when everybody approved`() {
    assertEquals(ReviewState.WAIT_FOR_UPDATES, GiteaReviewersComponentFactory.overallState(listOf(APPROVED, CHANGES_REQUESTED, PENDING), 0))
    assertEquals(ReviewState.ACCEPTED, GiteaReviewersComponentFactory.overallState(listOf(APPROVED, APPROVED), 0))
    assertEquals(ReviewState.NEED_REVIEW, GiteaReviewersComponentFactory.overallState(listOf(APPROVED, PENDING), 0))
    assertEquals(ReviewState.NEED_REVIEW, GiteaReviewersComponentFactory.overallState(listOf(APPROVED, APPROVED), teamCount = 1))
  }

  @Test
  fun `popup shows a state icon only once there is a review`() {
    assertEquals(ReviewState.ACCEPTED, GiteaReviewersComponentFactory.popupIconState(APPROVED))
    assertEquals(ReviewState.WAIT_FOR_UPDATES, GiteaReviewersComponentFactory.popupIconState(CHANGES_REQUESTED))
    assertEquals(ReviewState.NEED_REVIEW, GiteaReviewersComponentFactory.popupIconState(COMMENTED))
    assertNull(GiteaReviewersComponentFactory.popupIconState(PENDING))
    assertNull(GiteaReviewersComponentFactory.popupIconState(null), "teams")
  }
}
