package de.hendrikfrahmann.giteaplugin.pullrequest.ui.details

import com.intellij.collaboration.ui.HorizontalListPanel
import com.intellij.collaboration.ui.codereview.details.CodeReviewDetailsActionsComponentFactory
import com.intellij.collaboration.ui.codereview.details.data.ReviewRequestState
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.Wrapper
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaPullRequestDetailsViewModel
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaPullRequestMergeViewModel
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent

/**
 * Action row at the bottom of the PR details, built from the platform's review actions: open → Merge…,
 * Request Review… and a "more" menu (show runs, update branch, draft, cancel auto merge, close); draft → Ready for Review;
 * closed → Reopen; merged → nothing to do. Empty for users who can't change the PR (e.g. anonymous connections).
 */
object GiteaPullRequestActionsComponentFactory {
  fun create(cs: CoroutineScope, vm: GiteaPullRequestDetailsViewModel): JComponent {
    val mergeVm = vm.mergeVm
    if (!mergeVm.canChangeState && !mergeVm.canMerge.value) {
      return Wrapper().apply { isVisible = false }
    }

    val requestReview = action(GiteaBundle.message("pull.request.action.request.review")) { e ->
      (e?.source as? JComponent)?.let { GiteaPullRequestDetailsComponentFactory.editReviewers(cs, RelativePoint.getSouthWestOf(it), vm) }
    }
    val reopen = action(GiteaBundle.message("pull.request.action.reopen")) { mergeVm.reopen() }
    val ready = action(GiteaBundle.message("pull.request.action.ready")) { mergeVm.setDraft(false) }

    val opened = HorizontalListPanel(CodeReviewDetailsActionsComponentFactory.BUTTONS_GAP).apply {
      add(createMergeButton(cs, vm))
      if (vm.editVm.canEdit) {
        add(JButton(requestReview).apply { isOpaque = false })
      }
      add(CodeReviewDetailsActionsComponentFactory.createMoreButton(moreActions(mergeVm)))
    }
    val draft = CodeReviewDetailsActionsComponentFactory.createActionsForDraftReview(ready).apply {
      add(CodeReviewDetailsActionsComponentFactory.createMoreButton(moreActions(mergeVm)))
    }
    val closed = when {
      mergeVm.canChangeState -> CodeReviewDetailsActionsComponentFactory.createActionsForClosedReview(reopen)
      else -> Wrapper()
    }

    val row = CodeReviewDetailsActionsComponentFactory.createActionsComponent(
      cs, mergeVm.requestState, opened, CodeReviewDetailsActionsComponentFactory.createActionsForMergedReview(), closed, draft)

    return withBusyIndicator(cs, row, mergeVm, actionsDisabledWhileBusy = listOf(requestReview, reopen, ready))
  }

  /** A spinner next to the row while an operation runs; the row is hidden when there is nothing to do. */
  private fun withBusyIndicator(cs: CoroutineScope, row: JComponent, mergeVm: GiteaPullRequestMergeViewModel,
                                actionsDisabledWhileBusy: List<Action>): JComponent {
    val busy = JBLabel(AnimatedIcon.Default())
    val wrapper = Wrapper().apply {
      isOpaque = false
      layout = BorderLayout()
      add(row, BorderLayout.CENTER)
      add(busy, BorderLayout.EAST)
    }
    cs.launch(Dispatchers.EDT) {
      mergeVm.isBusy.collect { isBusy ->
        busy.isVisible = isBusy
        actionsDisabledWhileBusy.forEach { it.isEnabled = !isBusy }
      }
    }
    // nothing to do for a merged PR (or a closed one the user can't reopen): no empty row with its gaps
    cs.launch(Dispatchers.EDT) {
      mergeVm.requestState.collect { state ->
        wrapper.isVisible = state != ReviewRequestState.MERGED && (state != ReviewRequestState.CLOSED || mergeVm.canChangeState)
      }
    }
    return wrapper
  }

  /** "Merge…": only for users who may merge; disabled (with the reason) while there are conflicts or something runs. */
  private fun createMergeButton(cs: CoroutineScope, vm: GiteaPullRequestDetailsViewModel): JButton {
    val mergeVm = vm.mergeVm
    val merge = action(GiteaBundle.message("pull.request.action.merge")) { cs.launch(Dispatchers.EDT) { showMergeDialog(vm) } }
    val button = JButton(merge).apply { isOpaque = false }
    cs.launch(Dispatchers.EDT) { mergeVm.canMerge.collect { button.isVisible = it } }
    cs.launch(Dispatchers.EDT) {
      combine(mergeVm.hasConflicts, mergeVm.isBusy, ::Pair).collect { (conflicts, busy) ->
        merge.isEnabled = !conflicts && !busy
        button.toolTipText = GiteaBundle.message("pull.request.action.merge.conflicts").takeIf { conflicts }
      }
    }
    return button
  }

  /** Reads the repository's merge settings first, so changes made on the server since connecting are honoured. */
  suspend fun showMergeDialog(vm: GiteaPullRequestDetailsViewModel) {
    val mergeVm = vm.mergeVm
    val (styles, deleteBranch) = mergeVm.loadMergeOptions()
    if (styles.isEmpty()) {
      return
    }
    val dialog = GiteaMergeDialog(vm.project, vm.dataProvider.number, styles, styles.first(), deleteBranch,
                                  offerAutoMerge = mergeVm.checksPending.value)
    if (dialog.showAndGet()) {
      mergeVm.merge(dialog.style, dialog.commitTitle, dialog.commitMessage, dialog.deleteBranch, dialog.whenChecksSucceed)
    }
  }

  /** The "more" menu; each entry checks the PR's state when the menu opens. */
  private fun moreActions(vm: GiteaPullRequestMergeViewModel): DefaultActionGroup = DefaultActionGroup().apply {
    // finds the PR through the details' data context and hides itself where Gitea Actions is unavailable
    ActionManager.getInstance().getAction(SHOW_RUNS_ACTION_ID)?.let {
      add(it)
      addSeparator()
    }
    for (rebase in listOf(false, true)) {
      val text = when {
        rebase -> GiteaBundle.message("pull.request.action.update.rebase")
        else -> GiteaBundle.message("pull.request.action.update.merge")
      }
      add(menuAction(text, { rebase in vm.updateStyles && vm.canUpdateBranch && vm.behindBy.value > 0 && !vm.isBusy.value }) {
        vm.updateBranch(rebase)
      })
    }
    add(menuAction(GiteaBundle.message("pull.request.action.auto.merge.cancel"),
                   { vm.canMerge.value && vm.isAutoMergeScheduled.value && !vm.isBusy.value }) { vm.cancelAutoMerge() })
    add(menuAction(GiteaBundle.message("pull.request.action.draft"),
                   { vm.canChangeState && !vm.isDraft.value && !vm.isBusy.value }) { vm.setDraft(true) })
    add(menuAction(GiteaBundle.message("pull.request.action.close"), { vm.canChangeState && !vm.isBusy.value }) { vm.close() })
  }

  private fun menuAction(text: String, visible: () -> Boolean, perform: () -> Unit) = object : DumbAwareAction(text) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
      e.presentation.isEnabledAndVisible = visible()
    }

    override fun actionPerformed(e: AnActionEvent) = perform()
  }

  private fun action(text: String, perform: (ActionEvent?) -> Unit): Action = object : AbstractAction(text) {
    override fun actionPerformed(e: ActionEvent?) = perform(e)
  }

  private const val SHOW_RUNS_ACTION_ID = "Gitea.PullRequest.ShowActions"
}
