package de.hendrikfrahmann.giteaplugin.pullrequest.ui.details

import com.intellij.collaboration.ui.CollaborationToolsUIUtil
import com.intellij.collaboration.ui.SimpleHtmlPane
import com.intellij.collaboration.ui.codereview.CodeReviewProgressTreeModelFromDetails
import com.intellij.collaboration.ui.codereview.avatar.Avatar
import com.intellij.collaboration.ui.codereview.changes.CodeReviewChangeListComponentFactory
import com.intellij.collaboration.ui.codereview.details.CodeReviewDetailsBranchComponentFactory
import com.intellij.collaboration.ui.codereview.details.CodeReviewDetailsCommitInfoComponentFactory
import com.intellij.collaboration.ui.codereview.details.CodeReviewDetailsCommitsComponentFactory
import com.intellij.collaboration.ui.codereview.details.CodeReviewDetailsStatusComponentFactory
import com.intellij.collaboration.ui.codereview.details.CodeReviewDetailsTitleComponentFactory
import com.intellij.collaboration.ui.codereview.details.CommitPresentation
import com.intellij.collaboration.ui.codereview.details.ReviewDetailsUIUtil
import com.intellij.collaboration.ui.codereview.details.model.CodeReviewChangeListViewModel
import com.intellij.collaboration.ui.codereview.list.error.ErrorStatusPanelFactory
import com.intellij.collaboration.ui.codereview.list.error.ErrorStatusPresenter
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.EDT
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.Wrapper
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.SingleComponentCenteringLayout
import com.intellij.util.ui.UIUtil
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaMilestoneDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.pullrequest.action.GiteaPullRequestsActionKeys
import de.hendrikfrahmann.giteaplugin.pullrequest.action.GiteaPullRequestsActionPlaces
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.GiteaChooserPopups.Item
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaCommitViewModel
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaPullRequestChangesViewModel
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaPullRequestDetailsViewModel
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaReviewerState
import de.hendrikfrahmann.giteaplugin.settings.GiteaSettings
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import de.hendrikfrahmann.giteaplugin.util.mapScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import net.miginfocom.layout.CC
import net.miginfocom.layout.LC
import net.miginfocom.swing.MigLayout
import java.awt.Dimension
import java.awt.Insets
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/** The PR tab: title, metadata, description, commits/branches, changes tree and statuses – like GitLab's details. */
object GiteaPullRequestDetailsComponentFactory {
  const val DETAILS_POPUP_GROUP: String = "Gitea.PullRequest.Details.Popup"
  const val CHANGES_POPUP_GROUP: String = "Gitea.PullRequest.Changes.Popup"

  fun create(cs: CoroutineScope, vm: GiteaPullRequestDetailsViewModel): JComponent {
    val actionGroup = ActionManager.getInstance().getAction(DETAILS_POPUP_GROUP) as ActionGroup
    val title = CodeReviewDetailsTitleComponentFactory.create(cs, vm, GiteaBundle.message("pull.request.open.in.browser"), actionGroup,
                                                              htmlPaneFactory = { SimpleHtmlPane() })

    val commitsAndBranches = JPanel(MigLayout(LC().insets("0").fill())).apply {
      isOpaque = false
      add(CodeReviewDetailsCommitsComponentFactory.create(cs, vm.changesVm, ::commitPresentation))
      add(CodeReviewDetailsBranchComponentFactory.create(cs, vm.branchesVm), CC().pushX().alignX("right"))
    }
    val commitInfo = CodeReviewDetailsCommitInfoComponentFactory.create(cs, vm.changesVm.selectedCommit, ::commitPresentation,
                                                                        htmlPaneFactory = { SimpleHtmlPane() })

    val panel = JPanel(MigLayout(LC().insets("0").gridGap("0", "0").fill().flowY().hideMode(3))).apply {
      isOpaque = true
      background = UIUtil.getListBackground()
      val timelineLink = ActionLink(GiteaBundle.message("pull.request.details.view.timeline")) { vm.showTimeline() }
      add(ReviewDetailsUIUtil.createTitlePanel(title, timelineLink), CC().growX().insetsGap(ReviewDetailsUIUtil.TITLE_GAPS))
      add(commitsAndBranches, CC().growX().insetsGap(ReviewDetailsUIUtil.COMMIT_POPUP_BRANCHES_GAPS))
      add(commitInfo, CC().growX().insetsGap(ReviewDetailsUIUtil.COMMIT_INFO_GAPS))
      add(createChanges(cs, vm), CC().grow().push().shrinkPrioY(200))
      add(createStatuses(cs, vm), CC().growX().insetsGap(ReviewDetailsUIUtil.STATUSES_GAPS).maxHeight("${ReviewDetailsUIUtil.STATUSES_MAX_HEIGHT}"))
      add(GiteaPullRequestActionsComponentFactory.create(cs, vm), CC().growX().insetsGap(ReviewDetailsUIUtil.ACTIONS_GAPS).minHeight("pref"))
    }
    PopupHandler.installPopupMenu(panel, actionGroup, GiteaPullRequestsActionPlaces.DETAILS_POPUP)
    return UiDataProvider.wrapComponent(CollaborationToolsUIUtil.wrapWithProgressStripe(cs, vm.isLoading, panel)) { sink ->
      sink[GiteaPullRequestsActionKeys.DETAILS_VM] = vm
      sink[CodeReviewChangeListViewModel.DATA_KEY] = vm.changesVm.changeListVm.value.result?.getOrNull()
    }
  }

  fun createError(cs: CoroutineScope, error: Throwable, retry: () -> Unit): JComponent {
    val presenter = ErrorStatusPresenter.simple<Throwable>(
      GiteaBundle.message("pull.request.details.error"),
      descriptionProvider = { it.localizedMessage },
      actionProvider = {
        object : AbstractAction(GiteaBundle.message("pull.request.list.error.retry")) {
          override fun actionPerformed(e: ActionEvent?) = retry()
        }
      },
    )
    return ErrorStatusPanelFactory.create(cs, flowOf(error), presenter).also { CollaborationToolsUIUtil.moveToCenter(it) }
  }

  /** Popup with the users that can review; the selection replaces the pending review requests. */
  fun editReviewers(cs: CoroutineScope, point: RelativePoint, vm: GiteaPullRequestDetailsViewModel) =
    editUsers(cs, point, GiteaBundle.message("pull.request.details.reviewers"), vm, vm.reviewers.value.keys.filter {
      vm.reviewers.value[it] == GiteaReviewerState.PENDING
    }) { current, selected -> vm.editVm.setReviewers(current, selected) }

  fun editAssignees(cs: CoroutineScope, point: RelativePoint, vm: GiteaPullRequestDetailsViewModel) =
    editUsers(cs, point, GiteaBundle.message("pull.request.details.assignees"), vm, vm.assignees.value) { _, selected ->
      vm.editVm.setAssignees(selected)
    }

  fun editLabels(cs: CoroutineScope, point: RelativePoint, vm: GiteaPullRequestDetailsViewModel) {
    cs.launch(Dispatchers.EDT) {
      val all = vm.editVm.loadLabels()
      val current = vm.labels.value.map { it.id }.toSet()
      val chosen = GiteaChooserPopups.chooseMultiple(point, GiteaBundle.message("pull.request.details.labels"),
                                                     all, all.filter { it.id in current }.toSet()) { Item(it.name, it.description) }
      if (chosen != null) {
        vm.editVm.setLabels(chosen)
      }
    }
  }

  fun editMilestone(cs: CoroutineScope, point: RelativePoint, vm: GiteaPullRequestDetailsViewModel) {
    cs.launch(Dispatchers.EDT) {
      val none = GiteaMilestoneDTO(0, GiteaBundle.message("pull.request.details.milestone.none"))
      val chosen = GiteaChooserPopups.chooseOne(point, GiteaBundle.message("pull.request.details.milestone"),
                                                listOf(none) + vm.editVm.loadMilestones()) { Item(it.title) }
      if (chosen != null) {
        vm.editVm.setMilestone(chosen.takeIf { it.id != 0L })
      }
    }
  }

  /** The platform's animated spinner, centered in whatever space it gets. */
  fun createLoadingSpinner(): JComponent = centered(JBLabel(AnimatedIcon.Default()))

  private fun createChangesTree(cs: CoroutineScope, changeListVm: GiteaPullRequestChangesViewModel.GiteaPullRequestChangeListViewModel): JComponent {
    val tree = CodeReviewChangeListComponentFactory.createIn(cs, changeListVm, CodeReviewProgressTreeModelFromDetails(cs, changeListVm),
                                                             GiteaBundle.message("pull.request.details.changes.empty"))
    val popup = ActionManager.getInstance().getAction(CHANGES_POPUP_GROUP) as ActionGroup
    PopupHandler.installPopupMenu(tree, popup, GiteaPullRequestsActionPlaces.CHANGES_POPUP)
    return ScrollPaneFactory.createScrollPane(tree, true).apply {
      isOpaque = false
      viewport.isOpaque = false
      horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }
  }

  // an HTML pane, so long git messages (whole remote URLs) wrap instead of being cut off
  private val changesErrorPresenter: ErrorStatusPresenter<Throwable> =
    ErrorStatusPresenter.simple(GiteaBundle.message("pull.request.details.changes.error"), descriptionProvider = { it.localizedMessage })

  private fun centered(component: JComponent): JComponent = JPanel(SingleComponentCenteringLayout()).apply {
    isOpaque = false
    add(component)
  }

  private fun editUsers(cs: CoroutineScope, point: RelativePoint, title: String, vm: GiteaPullRequestDetailsViewModel, current: Collection<GiteaUserDTO>,
                        apply: (Collection<GiteaUserDTO>, Collection<GiteaUserDTO>) -> Unit) {
    cs.launch(Dispatchers.EDT) {
      val author = vm.author?.login
      val isAssignees = title == GiteaBundle.message("pull.request.details.assignees")
      val all = vm.editVm.loadUsers(allUsers = !isAssignees && GiteaSettings.getInstance().reviewersFromAllUsers)
        .filter { it.login != author || isAssignees }
      val currentLogins = current.map { it.login }.toSet()
      val chosen = GiteaChooserPopups.chooseMultiple(point, title, all,
                                                     all.filter { it.login in currentLogins }.toSet()) { user ->
        Item(user.login, user.fullName?.takeIf { it.isNotBlank() }, vm.avatarIconsProvider.getIcon(user, Avatar.Sizes.BASE))
      }
      if (chosen != null) {
        apply(all.filter { it.login in currentLogins }, chosen)
      }
    }
  }

  /** Loading: a spinner centered in the changes area (like GitHub); then the tree in a scroll pane, or the error. */
  private fun createChanges(cs: CoroutineScope, vm: GiteaPullRequestDetailsViewModel): JComponent {
    val wrapper = Wrapper(createLoadingSpinner())
    cs.launch(Dispatchers.EDT) {
      vm.changesVm.changeListVm.mapScoped { computed ->
        val result = computed.result
        val error = result?.exceptionOrNull()
        when {
          result == null -> createLoadingSpinner()
          error != null -> centered(ErrorStatusPanelFactory.create(error, changesErrorPresenter))
          else -> createChangesTree(this, result.getOrThrow())
        }
      }.collect {
        wrapper.setContent(it)
        wrapper.revalidate()
        wrapper.repaint()
      }
    }
    return wrapper.apply { isOpaque = false }
  }

  private fun createStatuses(cs: CoroutineScope, vm: GiteaPullRequestDetailsViewModel): JComponent {
    // hideMode 3: hidden statuses (most of them, most of the time) take no space
    val statuses = JPanel(MigLayout(LC().insets("0").gridGap("0", "0").flowY().fillX().hideMode(3))).apply {
      isOpaque = false
      add(GiteaPullRequestCiComponentFactory.create(cs, vm.project, vm.statusVm), CC().growX())
      add(GiteaPullRequestMergeStatusComponentFactory.createConflicts(cs, vm), CC().growX())
      add(GiteaPullRequestMergeStatusComponentFactory.createRequiredReviews(cs, vm), CC().growX())
      add(GiteaPullRequestMergeStatusComponentFactory.createRestriction(cs, vm), CC().growX())
      add(GiteaPullRequestMergeStatusComponentFactory.createBehind(cs, vm), CC().growX())
      add(GiteaPullRequestMergeStatusComponentFactory.createAutoMerge(cs, vm), CC().growX())
      add(CodeReviewDetailsStatusComponentFactory.createNeedReviewerComponent(cs, vm.reviewStates), CC().growX())
      add(GiteaReviewersComponentFactory.create(cs, vm), CC().growX())
    }
    return StatusesScrollPane(statuses)
  }

  /**
   * Without a minimum the scroll pane is the first thing to shrink in a low tool window and cuts the statuses off; it
   * keeps their height (up to the platform's maximum) and lets the changes above shrink instead.
   */
  private class StatusesScrollPane(statuses: JPanel) : JBScrollPane(statuses, VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_NEVER) {
    init {
      border = JBUI.Borders.empty()
      viewportBorder = null
      isOpaque = false
      viewport.isOpaque = false
      // a scroll pane is a validate root: a status row hiding itself (e.g. CI after a merge) re-lays out only the
      // viewport, and the details keep the old height until the next layout. Re-lay out the panel around it as well.
      val relayout = object : ComponentAdapter() {
        override fun componentShown(e: ComponentEvent) = relayoutParent()
        override fun componentHidden(e: ComponentEvent) = relayoutParent()
        override fun componentResized(e: ComponentEvent) = relayoutParent()
      }
      statuses.components.forEach { it.addComponentListener(relayout) }
    }

    override fun getMinimumSize(): Dimension =
      Dimension(super.getMinimumSize().width, minOf(preferredSize.height, JBUI.scale(ReviewDetailsUIUtil.STATUSES_MAX_HEIGHT)))

    private fun relayoutParent() {
      val parent = parent as? JComponent ?: return
      parent.revalidate()
      parent.repaint()
    }
  }
  /** MigLayout gaps (left, right, top, bottom) from the platform's review details insets. */
  private fun CC.insetsGap(insets: Insets): CC = gap("${insets.left}", "${insets.right}", "${insets.top}", "${insets.bottom}")

  private fun commitPresentation(commit: GiteaCommitViewModel) = CommitPresentation(commit.titleHtml, commit.descriptionHtml, commit.author, commit.date)
}
