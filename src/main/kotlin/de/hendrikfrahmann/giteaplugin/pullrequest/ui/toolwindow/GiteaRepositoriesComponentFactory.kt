package de.hendrikfrahmann.giteaplugin.pullrequest.ui.toolwindow

import com.intellij.ide.BrowserUtil
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StatusText
import com.intellij.util.ui.UIUtil
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.authentication.GiteaLoginMethod
import de.hendrikfrahmann.giteaplugin.authentication.ui.GiteaAnonymousLoginLink
import de.hendrikfrahmann.giteaplugin.clone.GiteaCloneItem
import de.hendrikfrahmann.giteaplugin.clone.GiteaCloneItemRenderer
import de.hendrikfrahmann.giteaplugin.clone.GiteaCloneViewModel
import de.hendrikfrahmann.giteaplugin.settings.GiteaSettings
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import git4idea.checkout.GitCheckoutProvider
import git4idea.commands.Git
import git4idea.remote.GitRememberedInputs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * The Repositories tab (like the VS Code extension's "all accessible" list): repositories of all accounts with
 * search; double-click/Enter opens one in the browser, the context menu also copies the link or clones it.
 */
object GiteaRepositoriesComponentFactory {
  fun create(cs: CoroutineScope, project: Project, vm: GiteaCloneViewModel, server: GiteaServerPath): JComponent =
    RepositoriesPanel(cs, project, vm, server).component

  private class RepositoriesPanel(
    cs: CoroutineScope,
    private val project: Project,
    private val vm: GiteaCloneViewModel,
    private val server: GiteaServerPath,
  ) {
    private val model = CollectionListModel<GiteaCloneItem>()
    private val list = JBList(model).apply {
      selectionMode = ListSelectionModel.SINGLE_SELECTION
      cellRenderer = GiteaCloneItemRenderer { vm.accounts.value.size > 1 }
    }
    private val search = SearchTextField(false)

    val component: JComponent = JPanel(BorderLayout()).apply {
      background = UIUtil.getListBackground()
      add(JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(SEARCH_INSET)
        add(search)
      }, BorderLayout.NORTH)
      add(ScrollPaneFactory.createScrollPane(list, true), BorderLayout.CENTER)
    }

    init {
      search.addDocumentListener(object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) = updateList()
      })
      installActions()
      cs.launch(Dispatchers.EDT) {
        vm.items.collect { updateList() }
      }
      cs.launch(Dispatchers.EDT) {
        combine(vm.isLoading, vm.accounts) { loading, accounts -> loading to accounts.isEmpty() }.collect { (loading, noAccounts) ->
          list.setPaintBusy(loading)
          updateEmptyText(loading, noAccounts)
        }
      }
    }

    private fun selected(): GiteaCloneItem.Repository? = list.selectedValue as? GiteaCloneItem.Repository

    /** Context menu (open, copy link, clone, refresh); double-click and Enter open the repository in the browser. */
    private fun installActions() {
      val actions = DefaultActionGroup(
        action(GiteaBundle.message("repositories.open.in.browser"), ::selected) { BrowserUtil.browse(webUrl(it)) },
        action(GiteaBundle.message("repositories.copy.link"), ::selected) { CopyPasteManager.getInstance().setContents(StringSelection(webUrl(it))) },
        action(GiteaBundle.message("repositories.clone"), ::selected) { clone(project, list, it) },
        Separator.getInstance(),
        object : DumbAwareAction(GiteaBundle.message("repositories.refresh")) {
          override fun actionPerformed(e: AnActionEvent) = vm.reload()
        },
      )
      PopupHandler.installPopupMenu(list, actions, ActionPlaces.POPUP)
      list.addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
          if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) {
            selected()?.let { BrowserUtil.browse(webUrl(it)) }
          }
        }
      })
      list.registerKeyboardAction({ selected()?.let { BrowserUtil.browse(webUrl(it)) } },
                                  KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED)
    }

    /** The repositories matching the search text; the selection stays if it is still there. */
    private fun updateList() {
      val selected = list.selectedValue
      val visible = vm.items.value.filter { GiteaCloneViewModel.matches(it, search.text) }
      model.replaceAll(visible)
      if (selected != null && selected in visible) {
        list.setSelectedValue(selected, true)
      }
    }

    /** Without accounts: a login link for the connected server. */
    private fun updateEmptyText(loading: Boolean, noAccounts: Boolean) {
      list.emptyText.apply {
        clear()
        when {
          loading -> text = StatusText.getDefaultEmptyText()
          noAccounts -> {
            appendText(GiteaBundle.message("repositories.no.accounts"))
            appendSecondaryText(GiteaBundle.message("anonymous.login.link", server.host), SimpleTextAttributes.LINK_ATTRIBUTES) {
              GiteaAnonymousLoginLink.logIn(project, list, server, GiteaLoginMethod.OAUTH)
            }
          }
          else -> text = GiteaBundle.message("clone.list.empty")
        }
      }
    }
  }

  private const val SEARCH_INSET = 8

  /** The repository page on the configured server (`html_url` uses `ROOT_URL`). */
  internal fun webUrl(item: GiteaCloneItem.Repository): String {
    val repository = item.repository
    return item.account.server.webUri(repository.owner?.login ?: repository.fullName.substringBefore('/'), repository.name).toString()
  }

  /** Asks for the parent directory, clones into `<parent>/<name>` and offers to open it (the platform's checkout listener). */
  private fun clone(project: Project, parent: JComponent, item: GiteaCloneItem.Repository) {
    val remembered = GitRememberedInputs.getInstance()
    val initial = LocalFileSystem.getInstance().findFileByPath(remembered.cloneParentDir.ifBlank { ProjectUtil.getBaseDir() })
    val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle(GiteaBundle.message("clone.directory.title"))
    val parentDir = FileChooser.chooseFile(descriptor, parent, project, initial) ?: return
    val name = item.repository.name
    if (parentDir.findChild(name) != null) {
      Messages.showErrorDialog(parent, GiteaBundle.message("repositories.clone.exists", parentDir.path, name), GiteaBundle.message("repositories.clone"))
      return
    }
    val url = item.cloneUrl(GiteaSettings.getInstance().cloneUsingSsh)
    remembered.addUrl(url)
    remembered.cloneParentDir = parentDir.path
    GitCheckoutProvider.clone(project, Git.getInstance(), ProjectLevelVcsManager.getInstance(project).compositeCheckoutListener,
                              parentDir, url, name, parentDir.path)
  }

  private fun action(text: String, selected: () -> GiteaCloneItem.Repository?, perform: (GiteaCloneItem.Repository) -> Unit) =
    object : DumbAwareAction(text) {
      override fun getActionUpdateThread() = ActionUpdateThread.EDT

      override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = selected() != null
      }

      override fun actionPerformed(e: AnActionEvent) {
        selected()?.let(perform)
      }
    }
}
