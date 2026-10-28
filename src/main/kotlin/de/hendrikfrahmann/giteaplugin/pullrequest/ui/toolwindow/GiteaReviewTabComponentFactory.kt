package de.hendrikfrahmann.giteaplugin.pullrequest.ui.toolwindow

import com.intellij.collaboration.ui.toolwindow.ReviewTabsComponentFactory
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.Wrapper
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.authentication.GiteaLoginMethod
import de.hendrikfrahmann.giteaplugin.authentication.ui.GiteaAnonymousLoginLink
import de.hendrikfrahmann.giteaplugin.notification.GiteaNotifications
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.create.GiteaPullRequestCreateComponentFactory
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.GiteaPullRequestDetailsComponentFactory
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaPullRequestDetailsViewModel
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.list.GiteaPullRequestsPanelFactory
import de.hendrikfrahmann.giteaplugin.refresh.GiteaRefreshService
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import de.hendrikfrahmann.giteaplugin.util.mapScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

class GiteaReviewTabComponentFactory(
  private val project: Project,
  private val vm: GiteaToolWindowProjectViewModel,
) : ReviewTabsComponentFactory<GiteaReviewTabViewModel, GiteaToolWindowConnectedProjectViewModel> {

  override fun createReviewListComponent(cs: CoroutineScope, projectVm: GiteaToolWindowConnectedProjectViewModel): JComponent {
    val connection = projectVm.connection
    val server = connection.repo.repository.serverPath
    val list = GiteaPullRequestsPanelFactory.create(cs, projectVm.listVm, logIn = {
      if (connection.isAnonymous) {
        GiteaAnonymousLoginLink.logIn(project, null, server, GiteaLoginMethod.OAUTH)
      }
      else {
        GiteaNotifications.notifyAuthFailed(project, connection.account)
      }
    }).apply {
      border = JBUI.Borders.emptyTop(8)
    }
    if (!connection.isAnonymous) {
      return list
    }
    return JPanel(BorderLayout()).apply {
      isOpaque = false
      add(createAnonymousBanner(server), BorderLayout.NORTH)
      add(list, BorderLayout.CENTER)
    }
  }

  /** "Read-only, not logged in to <host>. Log in…" above the list of an anonymous connection. */
  private fun createAnonymousBanner(server: GiteaServerPath): JComponent =
    JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
      isOpaque = false
      border = JBUI.Borders.empty(8, 10, 0, 10)
      add(JBLabel(GiteaBundle.message("anonymous.banner", server.host), AllIcons.General.Information, SwingConstants.LEFT).apply {
        foreground = UIUtil.getContextHelpForeground()
      })
      add(GiteaAnonymousLoginLink.create(project, server, GiteaBundle.message("anonymous.banner.login")))
    }

  override fun createTabComponent(cs: CoroutineScope, projectVm: GiteaToolWindowConnectedProjectViewModel, tabVm: GiteaReviewTabViewModel)
    : JComponent = when (tabVm) {
    is GiteaReviewTabViewModel.PullRequest ->
      createPullRequestComponent(cs, tabVm.cs, project, projectVm, tabVm.number) { GiteaPullRequestDetailsComponentFactory.create(cs, it) }
    is GiteaReviewTabViewModel.NewPullRequest ->
      GiteaPullRequestCreateComponentFactory.create(project, cs, tabVm.createVm)
    is GiteaReviewTabViewModel.Repositories ->
      GiteaRepositoriesComponentFactory.create(cs, project, tabVm.repositoriesVm, projectVm.connection.repo.repository.serverPath)
  }

  override fun createEmptyTabContent(cs: CoroutineScope): JComponent = JPanel(BorderLayout()).apply {
    background = UIUtil.getListBackground()
    cs.launch(Dispatchers.EDT) {
      vm.selectorVm.mapScoped { selectorVm -> selectorVm?.let { GiteaSelectorComponentFactory.create(this, project, it) } }
        .collect { selector ->
          removeAll()
          if (selector != null) {
            add(selector, BorderLayout.NORTH)
          }
          revalidate()
          repaint()
        }
    }
  }

  companion object {
    /**
     * Loading → [content] for a details view model (created once the first snapshot arrives, in [vmCs] so it outlives
     * refresh errors, and updated by refreshes) or an error with retry. Showing it reloads the PR, like GitLab.
     */
    fun createPullRequestComponent(cs: CoroutineScope, vmCs: CoroutineScope, project: Project, projectVm: GiteaToolWindowConnectedProjectViewModel,
                                   number: Long, content: (GiteaPullRequestDetailsViewModel) -> JComponent): JComponent {
      val dataProvider = projectVm.dataProvider(number)
      val detailsVm = dataProvider.snapshot.mapNotNull { it?.getOrNull() }.map { snapshot ->
        GiteaPullRequestDetailsViewModel(vmCs, project, projectVm.connection, dataProvider, snapshot, projectVm.avatarIconsProvider,
                                         onShowTimeline = { projectVm.openTimeline(number) },
                                         onShowDiff = { changes -> projectVm.openReviewDiff(number, changes) })
      }
      val wrapper = Wrapper(GiteaPullRequestDetailsComponentFactory.createLoadingSpinner())
      cs.launch(Dispatchers.EDT) {
        dataProvider.snapshot.collect { result ->
          val error = result?.exceptionOrNull()
          if (error != null && wrapper.targetComponent !is DetailsMarker) {
            wrapper.setContent(GiteaPullRequestDetailsComponentFactory.createError(cs, error) { dataProvider.refresh() })
            wrapper.repaint()
          }
        }
      }
      cs.launch(Dispatchers.EDT) {
        val vm = detailsVm.first()
        wrapper.setContent(DetailsMarker(content(vm)))
        wrapper.revalidate()
        wrapper.repaint()
      }
      dataProvider.refresh()
      // changes made elsewhere (web view, other people): reload at the slow refresh interval while the PR is visible
      cs.launch(Dispatchers.EDT) {
        while (true) {
          GiteaRefreshService.scheduler.awaitNextPoll("Gitea pull request #$number", active = false)
          if (wrapper.isShowing) {
            dataProvider.refresh()
          }
        }
      }
      return wrapper.apply { background = UIUtil.getListBackground() }
    }

    private class DetailsMarker(content: JComponent) : Wrapper(content)
  }
}
