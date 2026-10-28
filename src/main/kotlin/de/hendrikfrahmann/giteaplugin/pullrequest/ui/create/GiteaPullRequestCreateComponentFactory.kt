package de.hendrikfrahmann.giteaplugin.pullrequest.ui.create

import com.intellij.collaboration.ui.CollaborationToolsUIUtil
import com.intellij.collaboration.ui.HorizontalListPanel
import com.intellij.collaboration.ui.LabeledListComponentsFactory
import com.intellij.collaboration.ui.SimpleHtmlPane
import com.intellij.collaboration.ui.VerticalListPanel
import com.intellij.collaboration.ui.codereview.avatar.Avatar
import com.intellij.collaboration.ui.codereview.changes.CodeReviewChangeListComponentFactory
import com.intellij.collaboration.ui.codereview.create.CodeReviewTitleDescriptionComponentFactory
import com.intellij.collaboration.ui.codereview.details.CodeReviewDetailsCommitInfoComponentFactory
import com.intellij.collaboration.ui.codereview.details.CodeReviewDetailsCommitsComponentFactory
import com.intellij.collaboration.ui.codereview.details.CommitPresentation
import com.intellij.collaboration.ui.codereview.details.model.CodeReviewChangeListViewModel
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SideBorder
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBOptionButton
import com.intellij.ui.components.panels.Wrapper
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.SingleComponentCenteringLayout
import com.intellij.util.ui.UIUtil
import com.intellij.vcs.log.VcsCommitMetadata
import com.intellij.vcs.log.util.VcsUserUtil
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaLabelDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaMilestoneDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.create.model.GiteaPullRequestCreateBranchState
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.create.model.GiteaPullRequestCreateChangesViewModel
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.create.model.GiteaPullRequestCreateDirectionModel
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.create.model.GiteaPullRequestCreateError
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.create.model.GiteaPullRequestCreateViewModel
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.GiteaChooserPopups
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.GiteaChooserPopups.Item
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectMapping
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import git4idea.ui.branch.MergeDirectionComponentFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.miginfocom.layout.CC
import net.miginfocom.layout.LC
import net.miginfocom.swing.MigLayout
import org.jetbrains.annotations.Nls
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import java.util.Date
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants

/**
 * "New Pull Request" tab like GitHub's create tab: a splitter with direction and changes ("Changes from N commits ▾" +
 * change tree) on top, and title/description, metadata, statuses and the create button (with "Create Draft Pull
 * Request") below.
 */
object GiteaPullRequestCreateComponentFactory {
  private const val SIDE_GAP_L = 12
  private const val SIDE_GAP_M = 8
  private const val SECTION_GAP = 10
  private const val STATUS_GAP = 4
  private const val SPLIT_PROPORTION = 0.5f

  fun create(project: Project, cs: CoroutineScope, vm: GiteaPullRequestCreateViewModel): JComponent {
    val directionModel = GiteaPullRequestCreateDirectionModel(vm)
    vm.branchState.value?.let(directionModel::reset)
    directionModel.addDirectionChangesListener { vm.updateBranchState(directionModel.state) }

    val top = JPanel(MigLayout(LC().gridGap("0", "0").insets("0").flowY().fill())).apply {
      isOpaque = false
      add(createDirectionSelector(directionModel), CC().pushX().gap("$SIDE_GAP_L", "$SIDE_GAP_L", "$SIDE_GAP_M", "$SIDE_GAP_M"))
      add(createChanges(project, cs, vm).apply { border = IdeBorderFactory.createBorder(SideBorder.TOP) }, CC().grow().push())
    }
    val bottom = JPanel(MigLayout(LC().gridGap("0", "0").insets("0").flowY().fill())).apply {
      isOpaque = false
      border = IdeBorderFactory.createBorder(SideBorder.TOP)
      add(createTextPanel(project, cs, vm), CC().grow().push())
      add(createMetadata(cs, vm).apply {
        isOpaque = true
        background = JBColor.lazy { EditorColorsManager.getInstance().globalScheme.defaultBackground }
        border = JBUI.Borders.compound(IdeBorderFactory.createBorder(SideBorder.TOP or SideBorder.BOTTOM), JBUI.Borders.empty(SIDE_GAP_M, SIDE_GAP_L))
      }, CC().growX().pushX())
      add(VerticalListPanel(SECTION_GAP).apply {
        border = JBUI.Borders.empty(SIDE_GAP_L - 2)
        add(createStatus(cs, vm))
        add(createButtons(cs, vm))
      }, CC().pushX())
    }
    return JBSplitter(true, SPLIT_PROPORTION).apply {
      background = UIUtil.getListBackground()
      isFocusCycleRoot = true
      firstComponent = top
      secondComponent = bottom
      setHonorComponentsMinimumSize(true)
    }
  }

  private fun createDirectionSelector(model: GiteaPullRequestCreateDirectionModel): JComponent =
    MergeDirectionComponentFactory(
      model,
      getBaseRepoPresentation = { m ->
        val branch = m.baseBranch ?: return@MergeDirectionComponentFactory null
        val acrossRepositories = m.headRepo != null && m.headRepo?.repository != m.baseRepo.repository
        branchText(m.baseRepo, acrossRepositories, branch.nameForRemoteOperations)
      },
      getHeadRepoPresentation = { m ->
        val branch = m.headBranch ?: return@MergeDirectionComponentFactory null
        val head = m.headRepo ?: return@MergeDirectionComponentFactory null
        branchText(head, head.repository != m.baseRepo.repository, branch.name)
      },
    ).create()

  /** `name:branch`, or `owner/name:branch` when head and base are in different repositories (fork). */
  private fun branchText(repo: GiteaProjectMapping, withOwner: Boolean, branch: String): String {
    val name = when {
      withOwner -> repo.repositoryPath
      else -> repo.repository.path.name
    }
    return "$name:$branch"
  }

  /** Spinner while git compares the branches, then the commit chooser and the change tree. */
  private fun createChanges(project: Project, cs: CoroutineScope, vm: GiteaPullRequestCreateViewModel): JComponent {
    val wrapper = Wrapper()
    cs.launch(Dispatchers.EDT) {
      vm.comparison.collectLatest { result ->
        coroutineScope {
          val comparison = result?.getOrNull()
          wrapper.setContent(when {
            result == null -> centered(JBLabel(AnimatedIcon.Default()))
            comparison == null ->
              centered(hint(GiteaBundle.message("pull.request.create.compare.failed", result.exceptionOrNull()?.localizedMessage.orEmpty())))
            comparison.commits.isEmpty() -> centered(hint(GiteaBundle.message("pull.request.create.commits.empty")))
            else -> createChangesPanel(this, GiteaPullRequestCreateChangesViewModel(this, project, comparison, vm::loadCommitChanges))
          })
          wrapper.revalidate()
          wrapper.repaint()
          awaitCancellation()
        }
      }
    }
    return wrapper
  }

  private fun createChangesPanel(cs: CoroutineScope, changesVm: GiteaPullRequestCreateChangesViewModel): JComponent {
    val commits = CodeReviewDetailsCommitsComponentFactory.create(cs, changesVm, ::commitPresentation)
    val commitInfo = CodeReviewDetailsCommitInfoComponentFactory.create(cs, changesVm.selectedCommit, ::commitPresentation,
                                                                        htmlPaneFactory = { SimpleHtmlPane() })
    val header = VerticalListPanel(SIDE_GAP_M).apply {
      border = JBUI.Borders.empty(SIDE_GAP_M, SIDE_GAP_L)
      add(commits)
      add(commitInfo)
    }
    val scrollPane = ScrollPaneFactory.createScrollPane(null, true).apply {
      isOpaque = false
      viewport.isOpaque = false
      horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }
    cs.launch(Dispatchers.EDT) {
      changesVm.changeListVm.collectLatest { result ->
        coroutineScope {
          val changeListVm = result?.getOrNull()
          scrollPane.setViewportView(when {
            result == null -> centered(JBLabel(AnimatedIcon.Default()))
            changeListVm == null ->
              centered(hint(GiteaBundle.message("pull.request.create.changes.failed", result.exceptionOrNull()?.localizedMessage.orEmpty())))
            else -> CodeReviewChangeListComponentFactory.createIn(this, changeListVm, null, GiteaBundle.message("pull.request.create.changes.empty"))
          })
          awaitCancellation()
        }
      }
    }
    val panel = JPanel(BorderLayout()).apply {
      isOpaque = false
      add(header, BorderLayout.NORTH)
      add(scrollPane, BorderLayout.CENTER)
    }
    return UiDataProvider.wrapComponent(panel) { sink ->
      sink[CodeReviewChangeListViewModel.DATA_KEY] = changesVm.changeListVm.value?.getOrNull()
    }
  }

  private fun commitPresentation(commit: VcsCommitMetadata) = CommitPresentation(
    titleHtml = StringUtil.escapeXmlEntities(commit.subject),
    descriptionHtml = StringUtil.escapeXmlEntities(commit.fullMessage.substringAfter("\n\n", "").trim()).replace("\n", "<br/>"),
    author = VcsUserUtil.getShortPresentation(commit.author),
    committedDate = Date(commit.authorTime),
  )

  private fun hint(text: String): JComponent = SimpleHtmlPane(StringUtil.escapeXmlEntities(text)).apply {
    foreground = UIUtil.getContextHelpForeground()
  }

  private fun centered(component: JComponent): JComponent = JPanel(SingleComponentCenteringLayout()).apply {
    isOpaque = false
    add(component)
  }

  private fun createTextPanel(project: Project, cs: CoroutineScope, vm: GiteaPullRequestCreateViewModel): JComponent {
    val title = CodeReviewTitleDescriptionComponentFactory.createTitleEditorIn(project, cs, vm, GiteaBundle.message("pull.request.create.title.placeholder"))
    val description = CodeReviewTitleDescriptionComponentFactory.createDescriptionEditorIn(
      project, cs, vm, GiteaBundle.message("pull.request.create.description.placeholder"))
    return CodeReviewTitleDescriptionComponentFactory.createIn(cs, vm, title, description)
  }

  private fun createMetadata(cs: CoroutineScope, vm: GiteaPullRequestCreateViewModel): JComponent {
    val milestones: StateFlow<List<GiteaMilestoneDTO>> = vm.milestone.map { listOfNotNull(it) }.stateIn(cs, SharingStarted.Eagerly, emptyList())
    return LabeledListComponentsFactory.createGrid(listOf(
      usersRow(cs, vm, vm.reviewers, reviewers = true, "pull.request.create.reviewers", "pull.request.create.no.reviewers", vm::setReviewers),
      usersRow(cs, vm, vm.assignees, reviewers = false, "pull.request.create.assignees", "pull.request.create.no.assignees", vm::setAssignees),
      LabeledListComponentsFactory.createLabelPanel(vm.labels.isEmptyState(cs), GiteaBundle.message("pull.request.create.no.labels"),
                                                    GiteaBundle.message("pull.request.create.labels")) to
        LabeledListComponentsFactory.createListPanel(vm.labels, { comp, _ -> chooseLabels(comp, vm) }, { vm.setLabels(emptyList()) }) { label ->
          labelChip(label)
        },
      LabeledListComponentsFactory.createLabelPanel(milestones.isEmptyState(cs), GiteaBundle.message("pull.request.create.no.milestone"),
                                                    GiteaBundle.message("pull.request.create.milestone")) to
        LabeledListComponentsFactory.createListPanel(milestones, { comp, _ -> chooseMilestone(comp, vm) }, { vm.setMilestone(null) }) { milestone ->
          JBLabel(milestone.title, AllIcons.Nodes.Tag, SwingConstants.LEFT).apply { border = JBUI.Borders.empty(0, UIUtil.DEFAULT_HGAP / 2) }
        },
    ))
  }

  private fun usersRow(cs: CoroutineScope, vm: GiteaPullRequestCreateViewModel, users: StateFlow<List<GiteaUserDTO>>, reviewers: Boolean, labelKey: String,
                       emptyKey: String, set: (List<GiteaUserDTO>) -> Unit): Pair<JComponent, JComponent> {
    val label = LabeledListComponentsFactory.createLabelPanel(users.isEmptyState(cs),
                                                              GiteaBundle.message(emptyKey), GiteaBundle.message(labelKey))
    val list = LabeledListComponentsFactory.createListPanel(users, { comp, _ ->
      val all = vm.loadUsers(reviewers).filter { !reviewers || it.login != vm.connection.currentUser?.login }
      val current = users.value.map { it.login }.toSet()
      val chosen = GiteaChooserPopups.chooseMultiple(RelativePoint.getNorthEastOf(comp), GiteaBundle.message(labelKey), all,
                                                     all.filter { it.login in current }.toSet()) { user ->
        Item(user.login, user.fullName?.takeIf { it.isNotBlank() }, vm.avatarIconsProvider.getIcon(user, Avatar.Sizes.BASE))
      }
      if (chosen != null) {
        set(chosen)
      }
    }, { set(emptyList()) }) { user ->
      JBLabel(user.login, vm.avatarIconsProvider.getIcon(user, Avatar.Sizes.BASE), SwingConstants.LEFT).apply {
        border = JBUI.Borders.empty(0, UIUtil.DEFAULT_HGAP / 2)
      }
    }
    return label to list
  }

  private suspend fun chooseLabels(comp: JComponent, vm: GiteaPullRequestCreateViewModel) {
    val all = vm.loadLabels()
    val current = vm.labels.value.map { it.id }.toSet()
    val chosen = GiteaChooserPopups.chooseMultiple(RelativePoint.getNorthEastOf(comp), GiteaBundle.message("pull.request.create.labels"),
                                                   all, all.filter { it.id in current }.toSet()) { Item(it.name, it.description) }
    if (chosen != null) {
      vm.setLabels(chosen)
    }
  }

  private suspend fun chooseMilestone(comp: JComponent, vm: GiteaPullRequestCreateViewModel) {
    val chosen = GiteaChooserPopups.chooseOne(RelativePoint.getNorthEastOf(comp), GiteaBundle.message("pull.request.create.milestone"),
                                              vm.loadMilestones()) { Item(it.title) }
    if (chosen != null) {
      vm.setMilestone(chosen)
    }
  }

  private fun labelChip(label: GiteaLabelDTO): JComponent {
    val background = label.color?.let { runCatching { CollaborationToolsUIUtil.getLabelBackground(it) }.getOrNull() }
    val chip = when (background) {
      null -> JBLabel(label.name)
      else -> CollaborationToolsUIUtil.createTagLabel(label.name, CollaborationToolsUIUtil.getLabelForeground(background), background, compact = false)
    }
    return chip.apply { border = JBUI.Borders.empty(0, UIUtil.DEFAULT_HGAP / 2) }
  }

  /** Creating error, wrong direction / no changes, existing PR with a link, comparison error, progress. */
  private fun createStatus(cs: CoroutineScope, vm: GiteaPullRequestCreateViewModel): JComponent {
    val creatingError = JBLabel(AllIcons.Ide.FatalError).apply { setAllowAutoWrapping(true) }
    val requirementsError = JBLabel(AllIcons.General.Error)
    val existing = HorizontalListPanel(STATUS_GAP).apply {
      add(JBLabel(GiteaBundle.message("pull.request.create.exists"), AllIcons.General.Information, SwingConstants.LEFT))
      add(ActionLink(GiteaBundle.message("pull.request.create.exists.view")) { vm.openExistingPullRequest() })
    }
    val progress = JBLabel(AnimatedIcon.Default())
    cs.launch(Dispatchers.EDT) {
      vm.creatingError.collect { error ->
        creatingError.isVisible = error != null
        creatingError.text = error?.let { GiteaBundle.message("pull.request.create.failed", it.localizedMessage.orEmpty()) }
      }
    }
    cs.launch(Dispatchers.EDT) {
      combine(vm.requirementsError, vm.branchState, ::Pair).collect { (error, state) ->
        requirementsError.isVisible = error != null && state != null
        requirementsError.text = error?.let { requirementsErrorText(it, state) }
      }
    }
    cs.launch(Dispatchers.EDT) { vm.existingPullRequest.collect { existing.isVisible = it != null } }
    cs.launch(Dispatchers.EDT) {
      combine(vm.isBusy, vm.progressText, ::Pair).collect { (busy, text) ->
        progress.isVisible = busy
        progress.text = text ?: GiteaBundle.message("pull.request.create.progress")
      }
    }
    return VerticalListPanel(STATUS_GAP).apply {
      add(creatingError)
      add(requirementsError)
      add(existing)
      add(progress)
    }
  }

  private fun requirementsErrorText(error: GiteaPullRequestCreateError, state: GiteaPullRequestCreateBranchState?): @Nls String = when (error) {
    GiteaPullRequestCreateError.WRONG_DIRECTION -> GiteaBundle.message("pull.request.create.error.direction")
    GiteaPullRequestCreateError.NO_CHANGES ->
      GiteaBundle.message("pull.request.create.error.no.changes", state?.baseBranch?.nameForRemoteOperations.orEmpty(), state?.headBranch?.name.orEmpty())
  }

  /** "Create Pull Request" with "Create Draft Pull Request" in its options, like GitHub. */
  private fun createButtons(cs: CoroutineScope, vm: GiteaPullRequestCreateViewModel): JComponent {
    val create = action(GiteaBundle.message("pull.request.create.button")) { vm.create(false) }
    val createDraft = action(GiteaBundle.message("pull.request.create.draft.button")) { vm.create(true) }
    // the option button needs the platform's default style to be blue, a client property isn't enough
    val button = with(CollaborationToolsUIUtil) { JBOptionButton(create, arrayOf(createDraft)).defaultButton() }
    cs.launch(Dispatchers.EDT) {
      combine(vm.isBusy, vm.branchState, vm.requirementsError, vm.existingPullRequest, vm.comparison) { busy, state, error, existing, comparison ->
        !busy && state != null && error == null && existing == null && comparison?.isSuccess == true
      }.collect { enabled ->
        create.isEnabled = enabled
        createDraft.isEnabled = enabled
      }
    }
    return HorizontalListPanel(SECTION_GAP).apply { add(button) }
  }

  private fun action(text: String, perform: () -> Unit): Action = object : AbstractAction(text) {
    override fun actionPerformed(e: ActionEvent?) = perform()
  }.apply { isEnabled = false }

  private fun <T> StateFlow<List<T>>.isEmptyState(cs: CoroutineScope): StateFlow<Boolean> =
    map { it.isEmpty() }.stateIn(cs, SharingStarted.Eagerly, value.isEmpty())
}
