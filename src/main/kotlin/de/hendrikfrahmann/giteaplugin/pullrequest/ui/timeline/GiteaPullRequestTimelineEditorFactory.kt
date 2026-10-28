package de.hendrikfrahmann.giteaplugin.pullrequest.ui.timeline

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.panels.Wrapper
import com.intellij.util.ui.JBUI
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.GiteaPullRequestDetailsComponentFactory
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.toolwindow.GiteaReviewTabComponentFactory
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.toolwindow.GiteaToolWindowProjectViewModel
import de.hendrikfrahmann.giteaplugin.util.childScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.swing.JComponent

/** Builds the content of timeline editors; it follows the tool window's connection (a file of another one is invalid). */
@Service(Service.Level.PROJECT)
class GiteaPullRequestTimelineEditorFactory(private val project: Project, private val cs: CoroutineScope) {
  fun createComponent(connectionId: String, number: Long, disposable: Disposable): JComponent {
    val scope = cs.childScope()
    Disposer.register(disposable) { scope.cancel() }
    val wrapper = Wrapper(GiteaPullRequestDetailsComponentFactory.createLoadingSpinner())
    scope.launch(Dispatchers.EDT) {
      GiteaToolWindowProjectViewModel.getInstance(project).connectedProjectVm.collectLatest { projectVm ->
        if (projectVm == null || projectVm.connection.id != connectionId) {
          return@collectLatest
        }
        coroutineScope {
          val content = GiteaReviewTabComponentFactory.createPullRequestComponent(this, this, project, projectVm, number) { detailsVm ->
            val vm = GiteaPullRequestTimelineViewModel(this, projectVm.dataProvider(number), detailsVm, projectVm.commentContext(number)) { thread ->
              projectVm.openReviewDiff(number, thread)
            }
            GiteaPullRequestTimelineComponentFactory.create(this, vm)
          }
          wrapper.setContent(content)
          wrapper.revalidate()
          wrapper.repaint()
          try {
            awaitCancellation()
          }
          finally {
            wrapper.setContent(null)
          }
        }
      }
    }
    return wrapper.apply { border = JBUI.Borders.empty() }
  }
}
