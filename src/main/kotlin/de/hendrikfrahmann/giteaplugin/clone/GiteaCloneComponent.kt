package de.hendrikfrahmann.giteaplugin.clone

import com.intellij.dvcs.ui.CloneDvcsValidationUtils
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.CheckoutProvider
import com.intellij.openapi.vcs.ui.cloneDialog.VcsCloneDialogExtensionComponent
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StatusText
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.authentication.GiteaLoginResult
import de.hendrikfrahmann.giteaplugin.authentication.GiteaLoginUtil
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountManager
import de.hendrikfrahmann.giteaplugin.settings.GiteaSettings
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import git4idea.checkout.GitCheckoutProvider
import git4idea.commands.Git
import git4idea.remote.GitRememberedInputs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.CardLayout
import java.nio.file.Paths
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/** The Gitea page of "Get from VCS": login if there's no account, else the repository list. */
class GiteaCloneComponent(
  private val project: Project,
  private val cs: CoroutineScope,
  private val vm: GiteaCloneViewModel,
) : VcsCloneDialogExtensionComponent() {

  private val settings = GiteaSettings.getInstance()
  private val listModel = CollectionListModel<GiteaCloneItem>()
  private val list = JBList(listModel).apply {
    selectionMode = ListSelectionModel.SINGLE_SELECTION
    cellRenderer = GiteaCloneItemRenderer { vm.accounts.value.size > 1 }
    emptyText.text = GiteaBundle.message("clone.list.empty")
  }
  private val search = SearchTextField(false)
  private val protocol = ComboBox(DefaultComboBoxModel(arrayOf(PROTOCOL_HTTPS, PROTOCOL_SSH))).apply {
    selectedItem = when {
      settings.cloneUsingSsh -> PROTOCOL_SSH
      else -> PROTOCOL_HTTPS
    }
  }
  private val directory = TextFieldWithBrowseButton().apply {
    addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle(GiteaBundle.message("clone.directory.title")))
  }
  private val parentDirectory: String =
    GitRememberedInputs.getInstance().cloneParentDir.takeIf { it.isNotBlank() } ?: ProjectUtil.getBaseDir()

  private val cards = JPanel(CardLayout())

  init {
    cards.add(createLoginPanel(), CARD_LOGIN)
    cards.add(createListPanel(), CARD_LIST)
    installListeners()
    observeViewModel()
  }

  override fun getView(): JComponent = cards

  override fun getPreferredFocusedComponent(): JComponent = search

  override fun onComponentSelected() {
    dialogStateListener.onOkActionNameChanged(GiteaBundle.message("clone.button"))
    updateOkState()
  }

  override fun doValidateAll(): List<ValidationInfo> {
    val errors = mutableListOf<ValidationInfo>()
    if (selectedUrl() == null) {
      errors += ValidationInfo(GiteaBundle.message("clone.validation.no.repository"), list)
    }
    CloneDvcsValidationUtils.checkDirectory(directory.text, directory.textField as JComponent)?.let { errors += it }
    return errors
  }

  override fun doClone(checkoutListener: CheckoutProvider.Listener) {
    val url = selectedUrl() ?: return
    val path = Paths.get(directory.text.trim()).toAbsolutePath()
    val parent = path.parent ?: return
    if (CloneDvcsValidationUtils.createDestination(parent.toString()) != null) {
      return
    }
    val parentDir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(parent) ?: return
    GitRememberedInputs.getInstance().addUrl(url)
    GitRememberedInputs.getInstance().cloneParentDir = parent.toString()
    GitCheckoutProvider.clone(project, Git.getInstance(), checkoutListener, parentDir, url, path.fileName.toString(), parent.toString())
  }

  private fun installListeners() {
    search.addDocumentListener(object : DocumentAdapter() {
      override fun textChanged(e: DocumentEvent) {
        updateList()
        updateOkState()
      }
    })
    list.addListSelectionListener {
      val item = list.selectedValue as? GiteaCloneItem.Repository
      if (item != null) {
        directory.text = FileUtil.join(parentDirectory, item.repository.name)
      }
      updateOkState()
    }
    protocol.addActionListener {
      settings.cloneUsingSsh = protocol.selectedItem == PROTOCOL_SSH
    }
  }

  /** The login card without accounts, else the list – filled as the repositories arrive. */
  private fun observeViewModel() {
    cs.launch {
      vm.accounts.collect { accounts ->
        val card = when {
          accounts.isEmpty() -> CARD_LOGIN
          else -> CARD_LIST
        }
        (cards.layout as CardLayout).show(cards, card)
        dialogStateListener.onListItemChanged()
      }
    }
    cs.launch {
      vm.items.collect {
        updateList()
        dialogStateListener.onListItemChanged()
      }
    }
    cs.launch {
      vm.isLoading.collect { loading ->
        list.setPaintBusy(loading)
        list.emptyText.text = when {
          loading -> StatusText.getDefaultEmptyText()
          else -> GiteaBundle.message("clone.list.empty")
        }
      }
    }
  }

  private fun createLoginPanel(): JComponent = panel {
    row { label(GiteaBundle.message("clone.login.title")).applyToComponent { font = JBUI.Fonts.label(LOGIN_TITLE_FONT_SIZE) } }
    row { comment(GiteaBundle.message("clone.login.comment")) }
    row {
      button(GiteaBundle.message("login.action.oauth")) { login(oauth = true) }
      button(GiteaBundle.message("login.action.token")) { login(oauth = false) }
    }
  }.apply { border = JBUI.Borders.empty(LOGIN_PANEL_INSET) }

  private fun createListPanel(): JComponent = panel {
    row { cell(search).align(AlignX.FILL) }
    row { cell(JBScrollPane(list)).align(Align.FILL) }.resizableRow()
    row(GiteaBundle.message("clone.protocol.label")) { cell(protocol) }
    row(GiteaBundle.message("clone.directory.label")) { cell(directory).align(AlignX.FILL) }
  }.apply { border = JBUI.Borders.empty(LIST_PANEL_INSET) }

  private fun login(oauth: Boolean) {
    val accountManager = GiteaAccountManager.getInstance()
    val unique = { server: GiteaServerPath, name: String -> accountManager.isAccountUnique(server, name) }
    val result = when {
      oauth -> GiteaLoginUtil.logInViaOAuth(project, cards, uniqueAccountPredicate = unique)
      else -> GiteaLoginUtil.logInViaToken(project, cards, uniqueAccountPredicate = unique)
    }
    if (result is GiteaLoginResult.Success) {
      cs.launch { accountManager.updateAccount(result.account, result.credentials) }
    }
  }

  private fun updateList() {
    val query = search.text
    val selected = list.selectedValue
    // a pasted URL is cloned as is, the list doesn't apply
    val visible = when {
      GiteaCloneViewModel.isUrl(query) -> emptyList()
      else -> vm.items.value.filter { GiteaCloneViewModel.matches(it, query) }
    }
    listModel.replaceAll(visible)
    if (selected != null && selected in visible) {
      list.setSelectedValue(selected, true)
    }
  }

  /** The URL to clone: a pasted URL, else the selected repository's. */
  private fun selectedUrl(): String? {
    val query = search.text.trim()
    if (GiteaCloneViewModel.isUrl(query)) {
      return query
    }
    return (list.selectedValue as? GiteaCloneItem.Repository)?.cloneUrl(protocol.selectedItem == PROTOCOL_SSH)
  }

  private fun updateOkState() {
    if (GiteaCloneViewModel.isUrl(search.text) && directory.text.isBlank()) {
      directory.text = FileUtil.join(parentDirectory, search.text.trim().substringAfterLast('/').substringAfterLast(':').removeSuffix(".git"))
    }
    dialogStateListener.onOkActionEnabled(selectedUrl() != null)
  }

  companion object {
    private const val CARD_LOGIN = "login"
    private const val CARD_LIST = "list"
    private const val PROTOCOL_HTTPS = "HTTPS"
    private const val PROTOCOL_SSH = "SSH"
    private const val LOGIN_TITLE_FONT_SIZE = 18f
    private const val LOGIN_PANEL_INSET = 16
    private const val LIST_PANEL_INSET = 8
  }
}
