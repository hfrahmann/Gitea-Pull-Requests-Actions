package de.hendrikfrahmann.giteaplugin.tasks

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.tasks.TaskManager
import com.intellij.tasks.config.TaskRepositoryEditor
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.Consumer
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountManager
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnectionManager
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectsManager
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JTextField
import javax.swing.event.DocumentEvent

/**
 * Account and repository of a [GiteaTaskRepository]. A new one is filled in from the project's Gitea connection (or its
 * first Gitea remote), so adding "Gitea" usually needs no typing.
 */
internal class GiteaTaskRepositoryEditor(
  private val project: Project,
  private val repository: GiteaTaskRepository,
  private val changeListener: Consumer<in GiteaTaskRepository>,
) : TaskRepositoryEditor() {
  private val accounts = GiteaAccountManager.getInstance().accountsState.value.filterNot { it.isAnonymous }.sortedBy { it.toString() }
  private val accountModel = CollectionComboBoxModel(accounts)
  private val accountCombo = ComboBox(accountModel).apply {
    renderer = object : SimpleListCellRenderer<GiteaAccount?>() {
      override fun customize(list: JList<out GiteaAccount?>, value: GiteaAccount?, index: Int, selected: Boolean, hasFocus: Boolean) {
        text = value?.let { "${it.name} (${it.server})" } ?: GiteaBundle.message("tasks.account.none")
      }
    }
  }
  private val repositoryModel = CollectionComboBoxModel<String>()
  private val repositoryCombo = ComboBox(repositoryModel).apply { isEditable = true }
  private val assignedCheckBox = JBCheckBox(GiteaBundle.message("tasks.assigned.only"), repository.assignedToMeOnly)
  private var updating = false

  init {
    if (repository.account() == null) {
      prefill()
    }
    accountModel.selectedItem = repository.account()
    updateRepositorySuggestions()
    repositoryCombo.editor.item = repository.repositoryPath
    writeToRepository()
    installListeners()
  }

  override fun createComponent(): JComponent = panel {
    row(GiteaBundle.message("tasks.account")) {
      cell(accountCombo).align(AlignX.FILL)
    }
    if (accounts.isEmpty()) {
      row("") { comment(GiteaBundle.message("tasks.account.add")) }
    }
    row(GiteaBundle.message("tasks.repository")) {
      cell(repositoryCombo).align(AlignX.FILL).comment(GiteaBundle.message("tasks.repository.comment"))
    }
    row("") { cell(assignedCheckBox) }
    row("") {
      button(GiteaBundle.message("tasks.test")) { TaskManager.getManager(project).testConnection(repository) }
    }
  }

  override fun getPreferredFocusedComponent(): JComponent = repositoryCombo

  private fun installListeners() {
    accountCombo.addActionListener {
      updateRepositorySuggestions()
      writeToRepository()
    }
    repositoryCombo.addActionListener { writeToRepository() }
    (repositoryCombo.editor.editorComponent as? JTextField)?.document?.addDocumentListener(object : DocumentAdapter() {
      override fun textChanged(e: DocumentEvent) = writeToRepository()
    })
    assignedCheckBox.addActionListener { writeToRepository() }
  }

  /** The connected repository with its account; else the first account with a known remote of its server. */
  private fun prefill() {
    val connection = GiteaProjectConnectionManager.getInstance(project).connectionState.value?.takeIf { !it.account.isAnonymous }
    if (connection != null) {
      repository.accountId = connection.account.id
      repository.repositoryPath = connection.repo.repository.path.toString()
      return
    }
    val account = accounts.firstOrNull { knownRepositories(it).isNotEmpty() } ?: accounts.firstOrNull() ?: return
    repository.accountId = account.id
    repository.repositoryPath = knownRepositories(account).firstOrNull().orEmpty()
  }

  private fun knownRepositories(account: GiteaAccount): List<String> =
    GiteaProjectsManager.getInstance(project).knownRepositoriesState.value
      .filter { it.repository.serverPath == account.server }
      .map { it.repository.path.toString() }
      .distinct().sorted()

  private fun updateRepositorySuggestions() {
    updating = true
    try {
      val text = repositoryCombo.editor.item?.toString().orEmpty()
      repositoryModel.replaceAll((accountModel.selected?.let(::knownRepositories)).orEmpty())
      repositoryCombo.editor.item = text
    }
    finally {
      updating = false
    }
  }

  /** Copies the fields into [repository] and tells the Tasks settings about the change. */
  private fun writeToRepository() {
    if (updating) {
      return
    }
    val account = accountModel.selected
    repository.accountId = account?.id.orEmpty()
    repository.url = account?.server?.toString().orEmpty()
    repository.repositoryPath = ((repositoryCombo.editor.editorComponent as? JTextField)?.text ?: repositoryCombo.editor.item?.toString()).orEmpty()
    repository.assignedToMeOnly = assignedCheckBox.isSelected
    changeListener.consume(repository)
  }
}
