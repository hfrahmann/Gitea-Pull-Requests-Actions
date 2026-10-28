package de.hendrikfrahmann.giteaplugin.pullrequest.ui.details

import com.intellij.collaboration.messages.CollaborationToolsBundle
import com.intellij.collaboration.ui.Either
import com.intellij.collaboration.ui.codereview.details.CodeReviewDetailsStatusComponentFactory
import com.intellij.collaboration.ui.icon.CIBuildStatusIcons
import com.intellij.dvcs.repo.Repository
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.components.ActionLink
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaPRBranchInfoDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaPullRequestDTO
import de.hendrikfrahmann.giteaplugin.api.request.httpCloneUrl
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaPullRequestDetailsViewModel
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import git4idea.remote.hosting.HostedGitRepositoryRemote
import git4idea.remote.hosting.ui.ResolveConflictsLocallyCoordinates
import git4idea.remote.hosting.ui.ResolveConflictsLocallyDialogComponentFactory
import git4idea.remote.hosting.ui.ResolveConflictsLocallyViewModel
import git4idea.repo.GitRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls
import java.awt.event.ActionListener
import javax.swing.JComponent
import javax.swing.JList

/**
 * Merge-related statuses of an open PR: conflicts (with "Resolve" locally), required approvals, merge restriction,
 * branch behind its base, scheduled auto merge. All but the conflicts are [GiteaStatusRow]s, which wrap long texts.
 */
object GiteaPullRequestMergeStatusComponentFactory {
  /** Why the conflicts can't be resolved locally right now. */
  private enum class ResolveError { NOT_LOADED, MERGE_IN_PROGRESS, HEAD_GONE }

  /**
   * The platform's conflicts status; with write access its "Resolve" link merges or rebases the base into the head
   * locally (git4idea's flow: fetch both, check out the head, merge/rebase, the user pushes).
   */
  fun createConflicts(cs: CoroutineScope, vm: GiteaPullRequestDetailsViewModel): JComponent {
    val hasConflicts = vm.statusVm.hasConflicts
    if (!vm.mergeVm.canChangeState) {
      return CodeReviewDetailsStatusComponentFactory.createConflictsComponent(cs, hasConflicts)
    }

    val gitRepository = vm.connection.repo.gitRepository
    val server = vm.connection.repo.repository.serverPath
    val resolveVm = ResolveConflictsLocallyViewModel.createIn<ResolveError>(
      cs, vm.project, gitRepository, hasConflicts,
      vm.snapshot.map { snapshot -> resolveCoordinates(gitRepository, snapshot.pullRequest, server) },
      Either.left(ResolveError.NOT_LOADED),
    )
    val resolveActions = resolveVm.requestOrError.map { requestOrError ->
      requestOrError.bimap(::resolveErrorText) { request ->
        ActionListener {
          resolveVm.performResolveConflicts {
            withContext(Dispatchers.EDT) { ResolveConflictsLocallyDialogComponentFactory.showBranchUpdateDialog(request.headRefName, request.baseRefName) }
          }
        }
      }
    }
    return CodeReviewDetailsStatusComponentFactory.createConflictsComponent(cs, resolveVm.hasConflicts, resolveActions, resolveVm.isBusy)
  }

  private fun resolveCoordinates(gitRepository: GitRepository, pr: GiteaPullRequestDTO,
                                 server: GiteaServerPath): Either<ResolveError, ResolveConflictsLocallyCoordinates> {
    if (gitRepository.state == Repository.State.MERGING || gitRepository.state == Repository.State.REBASING) {
      return Either.left(ResolveError.MERGE_IN_PROGRESS)
    }
    return coordinates(pr, server)?.let { Either.right(it) } ?: Either.left(ResolveError.HEAD_GONE)
  }

  private fun resolveErrorText(error: ResolveError): @Nls String = when (error) {
    ResolveError.NOT_LOADED -> CollaborationToolsBundle.message("review.details.resolveConflicts.error.detailsNotLoaded")
    ResolveError.MERGE_IN_PROGRESS -> CollaborationToolsBundle.message("review.details.resolveConflicts.error.mergeInProgress")
    ResolveError.HEAD_GONE -> GiteaBundle.message("pull.request.resolve.conflicts.head.gone")
  }

  /** Head and base as remotes the platform can find or add (URLs on the configured server, not Gitea's `ROOT_URL`). */
  private fun coordinates(pr: GiteaPullRequestDTO, server: GiteaServerPath): ResolveConflictsLocallyCoordinates? {
    val head = pr.head?.takeIf { !it.ref.startsWith("refs/pull/") } ?: return null
    val base = pr.base ?: return null
    return ResolveConflictsLocallyCoordinates(remote(head, server) ?: return null, head.ref, remote(base, server) ?: return null, base.ref)
  }

  private fun remote(branch: GiteaPRBranchInfoDTO, server: GiteaServerPath): HostedGitRepositoryRemote? {
    val repo = branch.repo ?: return null
    val owner = repo.owner?.login ?: repo.fullName.substringBefore('/')
    return HostedGitRepositoryRemote(owner, server.toURI(), repo.fullName, server.httpCloneUrl(repo), repo.sshUrl)
  }

  /**
   * "This branch is N commits behind <base>" with one Update link: a direct link when the repository allows one update
   * style, otherwise "Update" with a merge / rebase popup (keeps the line short; the "more" menu has both, too).
   */
  fun createBehind(cs: CoroutineScope, vm: GiteaPullRequestDetailsViewModel): JComponent {
    val mergeVm = vm.mergeVm
    val styles = mergeVm.updateStyles
    val links = when {
      mergeVm.canUpdateBranch && styles.isNotEmpty() -> listOf(createUpdateLink(styles) { rebase -> mergeVm.updateBranch(rebase) })
      else -> emptyList()
    }
    val row = GiteaStatusRow("Code review status: behind", links).apply { icon = AllIcons.General.Warning }
    cs.launch(Dispatchers.EDT) {
      combine(mergeVm.behindBy, mergeVm.requestState, vm.snapshot) { behind, _, snapshot -> behind to snapshot.pullRequest.base?.ref.orEmpty() }
        .collect { (behind, base) ->
          row.text = GiteaBundle.message("pull.request.status.behind", behind, base)
          row.isVisible = behind > 0
        }
    }
    return row
  }

  /** "Will be merged when all checks succeed" with Cancel (verified on 28.1: only required checks are waited for). */
  fun createAutoMerge(cs: CoroutineScope, vm: GiteaPullRequestDetailsViewModel): JComponent {
    val mergeVm = vm.mergeVm
    val links = when {
      mergeVm.canMerge.value -> listOf(ActionLink(GiteaBundle.message("pull.request.status.auto.merge.cancel")) { mergeVm.cancelAutoMerge() })
      else -> emptyList()
    }
    val row = GiteaStatusRow("Code review status: auto merge", links).apply {
      icon = AllIcons.General.Information
      text = GiteaBundle.message("pull.request.status.auto.merge")
    }
    cs.launch(Dispatchers.EDT) { mergeVm.isAutoMergeScheduled.collect { row.isVisible = it } }
    return row
  }

  /** "At least N approving reviews are required…" – the platform's text and icon, but wrapping. */
  fun createRequiredReviews(cs: CoroutineScope, vm: GiteaPullRequestDetailsViewModel): JComponent {
    val mergeVm = vm.mergeVm
    val row = GiteaStatusRow("Code review status: required reviews").apply { icon = CIBuildStatusIcons.failed }
    cs.launch(Dispatchers.EDT) {
      combine(mergeVm.missingApprovals, mergeVm.isDraft, ::Pair).collect { (missing, draft) ->
        row.text = CollaborationToolsBundle.message("review.details.status.reviewer.required", missing)
        row.isVisible = missing > 0 && !draft
      }
    }
    return row
  }

  /** "You are not authorized to merge…" – the platform's text and icon, but wrapping. */
  fun createRestriction(cs: CoroutineScope, vm: GiteaPullRequestDetailsViewModel): JComponent {
    val mergeVm = vm.mergeVm
    val row = GiteaStatusRow("Code review status: restricted rights").apply {
      icon = CIBuildStatusIcons.failed
      text = CollaborationToolsBundle.message("review.details.status.not.authorized.to.merge")
    }
    cs.launch(Dispatchers.EDT) {
      combine(mergeVm.isMergeRestricted, mergeVm.isDraft, ::Pair).collect { (restricted, draft) -> row.isVisible = restricted && !draft }
    }
    return row
  }

  private fun createUpdateLink(styles: List<Boolean>, update: (rebase: Boolean) -> Unit): ActionLink {
    val single = styles.singleOrNull()
    if (single != null) {
      return ActionLink(updateStyleText(single)) { update(single) }
    }
    return ActionLink(GiteaBundle.message("pull.request.status.behind.update")) { event ->
      JBPopupFactory.getInstance().createPopupChooserBuilder(styles)
        // not SimpleListCellRenderer.create: both overloads are scheduled for removal in 2026.2
        .setRenderer(object : ColoredListCellRenderer<Boolean>() {
          override fun customizeCellRenderer(list: JList<out Boolean>, rebase: Boolean, index: Int, selected: Boolean, hasFocus: Boolean) {
            append(updateStyleText(rebase))
          }
        })
        .setItemChosenCallback { update(it) }
        .createPopup()
        .showUnderneathOf(event.source as JComponent)
    }
  }

  private fun updateStyleText(rebase: Boolean): @Nls String = when {
    rebase -> GiteaBundle.message("pull.request.status.behind.rebase")
    else -> GiteaBundle.message("pull.request.status.behind.merge")
  }
}
