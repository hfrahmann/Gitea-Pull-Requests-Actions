package de.hendrikfrahmann.giteaplugin.pullrequest.ui.details

import com.intellij.collaboration.ui.HorizontalListPanel
import com.intellij.collaboration.ui.codereview.avatar.Avatar
import com.intellij.collaboration.ui.codereview.avatar.CodeReviewAvatarUtils
import com.intellij.collaboration.ui.codereview.details.ReviewDetailsUIUtil
import com.intellij.collaboration.ui.codereview.details.data.ReviewState
import com.intellij.openapi.application.EDT
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.Wrapper
import com.intellij.util.ui.JBUI
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaTeamDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaPullRequestDetailsViewModel
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaReviewerState
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import net.miginfocom.layout.AC
import net.miginfocom.layout.CC
import net.miginfocom.layout.LC
import net.miginfocom.swing.MigLayout
import org.jetbrains.annotations.Nls
import java.awt.Dimension
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Reviewers in the PR details' statuses. One reviewer (or team) gets a line like the platform's reviewer status;
 * several get one summary line ("2 approved, 3 pending") with a "…" link that lists them in a popup, so ten reviewers
 * don't push the merge buttons out of sight.
 */
internal object GiteaReviewersComponentFactory {
  fun create(cs: CoroutineScope, vm: GiteaPullRequestDetailsViewModel): JComponent {
    val holder = Wrapper().apply { isOpaque = false }
    cs.launch(Dispatchers.EDT) {
      combine(vm.reviewers, vm.requestedTeams, ::Pair).collect { (reviewers, teams) ->
        holder.setContent(createContent(vm, reviewers, teams))
        holder.revalidate()
        holder.repaint()
      }
    }
    return holder
  }

  /** "Reviewers: 2 approved, 1 requested changes, 3 pending" – teams count as pending. */
  @Nls
  fun summaryText(states: Collection<GiteaReviewerState>, teamCount: Int): String {
    val counts = states.groupingBy { it }.eachCount()
    val parts = listOf(
      GiteaReviewerState.APPROVED to "pull.request.details.reviewers.approved",
      GiteaReviewerState.CHANGES_REQUESTED to "pull.request.details.reviewers.changes",
      GiteaReviewerState.COMMENTED to "pull.request.details.reviewers.commented",
    ).mapNotNull { (state, key) -> counts[state]?.let { GiteaBundle.message(key, it) } }
    val pending = (counts[GiteaReviewerState.PENDING] ?: 0) + teamCount
    val all = when {
      pending > 0 -> parts + GiteaBundle.message("pull.request.details.reviewers.pending", pending)
      else -> parts
    }
    return GiteaBundle.message("pull.request.details.reviewers.summary", all.joinToString(", "))
  }

  /** Requested changes win over everything, then waiting reviews; approved only when every review is an approval. */
  fun overallState(states: Collection<GiteaReviewerState>, teamCount: Int): ReviewState = when {
    GiteaReviewerState.CHANGES_REQUESTED in states -> ReviewState.WAIT_FOR_UPDATES
    teamCount == 0 && states.isNotEmpty() && states.all { it == GiteaReviewerState.APPROVED } -> ReviewState.ACCEPTED
    else -> ReviewState.NEED_REVIEW
  }

  /**
   * The icon at the right of a reviewer in the popup: green tick, red for requested changes, yellow for a comment,
   * nothing while the review is still awaited (also for teams, [state] `null`).
   */
  fun popupIconState(state: GiteaReviewerState?): ReviewState? = when (state) {
    GiteaReviewerState.APPROVED -> ReviewState.ACCEPTED
    GiteaReviewerState.CHANGES_REQUESTED -> ReviewState.WAIT_FOR_UPDATES
    GiteaReviewerState.COMMENTED -> ReviewState.NEED_REVIEW
    GiteaReviewerState.PENDING, null -> null
  }

  /** A reviewer or a requested team (no avatar, no review of its own: [state] `null`). */
  private class Entry(@Nls val text: String, val icon: Icon, @Nls val tooltip: String, val state: GiteaReviewerState?)

  private fun createContent(vm: GiteaPullRequestDetailsViewModel, reviewers: Map<GiteaUserDTO, GiteaReviewerState>,
                            teams: List<GiteaTeamDTO>): JComponent? {
    val entries = reviewers.map { (user, state) -> reviewerEntry(vm, user, state) } + teams.map { teamEntry(vm, it) }
    return when (entries.size) {
      0 -> null
      1 -> statusRow(entries.single())
      else -> summaryRow(reviewers.values, teams.size, entries)
    }
  }

  private fun summaryRow(states: Collection<GiteaReviewerState>, teamCount: Int, entries: List<Entry>): JComponent {
    lateinit var link: ActionLink
    link = ActionLink(GiteaBundle.message("pull.request.details.reviewers.show")) { showPopup(link, entries) }.apply {
      toolTipText = GiteaBundle.message("pull.request.details.reviewers.show.tooltip")
    }
    return GiteaStatusRow("Code review status: reviewers", listOf(link)).apply {
      icon = ReviewDetailsUIUtil.getReviewStateIcon(overallState(states, teamCount))
      text = summaryText(states, teamCount)
      isVisible = true
    }
  }

  private fun showPopup(link: JComponent, entries: List<Entry>) {
    // two columns: reviewer, then the state icons right-aligned under each other
    val list = JPanel(MigLayout(LC().insets("${JBUI.scale(POPUP_INSET)}").gridGap("${JBUI.scale(POPUP_ICON_GAP)}", "${JBUI.scale(POPUP_ROW_GAP)}").fillX(),
                                AC().grow().gap().align("right"))).apply {
      isOpaque = false
      for (entry in entries) {
        add(entryLabel(entry), CC().growX())
        val iconState = popupIconState(entry.state)
        add(JBLabel(iconState?.let(ReviewDetailsUIUtil::getReviewStateIcon)).apply { toolTipText = entry.tooltip }, CC().wrap())
      }
    }
    val scroll = ScrollPaneFactory.createScrollPane(list, true).apply {
      // tall lists scroll instead of covering the whole IDE
      preferredSize = Dimension(list.preferredSize.width + JBUI.scale(SCROLLBAR_ALLOWANCE),
                                minOf(list.preferredSize.height, JBUI.scale(POPUP_MAX_HEIGHT)))
    }
    JBPopupFactory.getInstance().createComponentPopupBuilder(scroll, null)
      .setTitle(GiteaBundle.message("pull.request.details.reviewers"))
      .setRequestFocus(true)
      .setCancelOnClickOutside(true)
      .setResizable(true)
      .createPopup()
      .show(RelativePoint.getSouthWestOf(link))
  }

  /** Avatar with a state-coloured outline and "<name> approved" (the platform's helpers, same in 261–263). */
  private fun reviewerEntry(vm: GiteaPullRequestDetailsViewModel, user: GiteaUserDTO, state: GiteaReviewerState): Entry {
    val reviewState = state.reviewState
    val avatar = CodeReviewAvatarUtils.createIconWithOutline(vm.avatarIconsProvider.getIcon(user, Avatar.Sizes.OUTLINED),
                                                             ReviewDetailsUIUtil.getReviewStateIconBorder(reviewState))
    return Entry(ReviewDetailsUIUtil.getReviewStateText(reviewState, user.login), avatar,
                 GiteaBundle.message(reviewStateKey(state), user.login), state)
  }

  /**
   * Teams have no avatar of their own: the organization's, like a reviewer's (same size and outline), or the default
   * avatar when Gitea leaves the organization out.
   */
  private fun teamEntry(vm: GiteaPullRequestDetailsViewModel, team: GiteaTeamDTO): Entry {
    val text = GiteaBundle.message("pull.request.details.review.pending.team", team.name)
    val organization = team.organization?.let { GiteaUserDTO(it.id, it.username ?: it.name.orEmpty(), avatarUrl = it.avatarUrl) }
    val avatar = CodeReviewAvatarUtils.createIconWithOutline(vm.avatarIconsProvider.getIcon(organization, Avatar.Sizes.OUTLINED),
                                                             ReviewDetailsUIUtil.getReviewStateIconBorder(ReviewState.NEED_REVIEW))
    return Entry(text, avatar, text, null)
  }

  /** One reviewer in the statuses, like the platform's reviewer status: state icon, then the reviewer. */
  private fun statusRow(entry: Entry): JComponent = HorizontalListPanel(JBUI.scale(REVIEWER_GAP)).apply {
    border = JBUI.Borders.empty(REVIEWER_BORDER, 0)
    add(JBLabel(ReviewDetailsUIUtil.getReviewStateIcon(entry.state?.reviewState ?: ReviewState.NEED_REVIEW)))
    add(entryLabel(entry))
  }

  private fun entryLabel(entry: Entry): JBLabel = JBLabel(entry.text, entry.icon, JBLabel.LEFT).apply {
    iconTextGap = JBUI.scale(REVIEWER_GAP)
    toolTipText = entry.tooltip
  }

  private fun reviewStateKey(state: GiteaReviewerState): String = when (state) {
    GiteaReviewerState.APPROVED -> "pull.request.details.review.approved"
    GiteaReviewerState.CHANGES_REQUESTED -> "pull.request.details.review.changes"
    GiteaReviewerState.COMMENTED -> "pull.request.details.review.commented"
    GiteaReviewerState.PENDING -> "pull.request.details.review.pending"
  }

  private const val REVIEWER_GAP = 8
  private const val REVIEWER_BORDER = 3
  private const val POPUP_INSET = 8
  private const val POPUP_ICON_GAP = 16

  /** Room between the avatars, twice the reviewer rows' spacing in the statuses. */
  private const val POPUP_ROW_GAP = 4 * REVIEWER_BORDER
  private const val POPUP_MAX_HEIGHT = 400
  private const val SCROLLBAR_ALLOWANCE = 16
}
