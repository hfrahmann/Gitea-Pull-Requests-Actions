package de.hendrikfrahmann.giteaplugin.share

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import de.hendrikfrahmann.giteaplugin.api.GiteaApiManager
import de.hendrikfrahmann.giteaplugin.api.request.getCurrentUser
import de.hendrikfrahmann.giteaplugin.api.request.loadCurrentUserOrganizations
import de.hendrikfrahmann.giteaplugin.authentication.GiteaConnectionTester
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.authentication.accounts.getClient
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JList

/** Where the new repository goes: the user's namespace or one of their organizations. */
data class GiteaShareOwner(val login: String, val isOrganization: Boolean) {
  override fun toString(): String = login
}

data class GiteaShareSettings(
  val account: GiteaAccount,
  val owner: GiteaShareOwner,
  val name: String,
  val description: String,
  val isPrivate: Boolean,
  val remoteName: String,
)

/** "Share Project on Gitea": account, owner, name, visibility, description, remote name. */
class GiteaShareDialog(
  project: Project,
  accounts: List<GiteaAccount>,
  defaultAccount: GiteaAccount?,
  defaultName: String,
  private val existingRemotes: Set<String>,
) : DialogWrapper(project) {

  private val cs = CoroutineScope(SupervisorJob() + Dispatchers.EDT + ModalityState.any().asContextElement())
  private var ownersJob: Job? = null

  private val accountBox = ComboBox(DefaultComboBoxModel(accounts.toTypedArray())).apply {
    renderer = object : SimpleListCellRenderer<GiteaAccount>() {
      override fun customize(list: JList<out GiteaAccount>, value: GiteaAccount?, index: Int, selected: Boolean, hasFocus: Boolean) {
        text = value?.let { "${it.name}@${it.server.host}${it.server.pathPrefix}" } ?: ""
      }
    }
    selectedItem = defaultAccount?.takeIf { it in accounts } ?: accounts.first()
  }
  private val ownerBox = ComboBox(DefaultComboBoxModel<GiteaShareOwner>())
  private val nameField = JBTextField(sanitizeName(defaultName))
  private val descriptionField = JBTextField()
  private val privateBox = JBCheckBox(GiteaBundle.message("share.private"), true)
  private val remoteField = JBTextField(defaultRemoteName(existingRemotes))
  private var ownersError: String? = null

  init {
    title = GiteaBundle.message("share.dialog.title")
    setOKButtonText(GiteaBundle.message("share.button"))
    Disposer.register(disposable) { cs.cancel() }
    accountBox.addActionListener { loadOwners() }
    init()
    loadOwners()
  }

  val result: GiteaShareSettings
    get() = GiteaShareSettings(
      accountBox.selectedItem as GiteaAccount,
      ownerBox.selectedItem as GiteaShareOwner,
      nameField.text.trim(),
      descriptionField.text.trim(),
      privateBox.isSelected,
      remoteField.text.trim(),
    )

  override fun createCenterPanel(): JComponent = panel {
    row(GiteaBundle.message("share.account")) { cell(accountBox).align(AlignX.FILL) }
    row(GiteaBundle.message("share.owner")) { cell(ownerBox).align(AlignX.FILL) }
    row(GiteaBundle.message("share.name")) { cell(nameField).columns(COLUMNS_LARGE).align(AlignX.FILL) }
    row("") { cell(privateBox) }
    row(GiteaBundle.message("share.remote")) { cell(remoteField).align(AlignX.FILL) }
    row(GiteaBundle.message("share.description")) { cell(descriptionField).align(AlignX.FILL) }
  }

  override fun getPreferredFocusedComponent(): JComponent = nameField

  override fun doValidate(): ValidationInfo? {
    ownersError?.let { return ValidationInfo(it, accountBox) }
    if (ownerBox.selectedItem == null) {
      return ValidationInfo(GiteaBundle.message("share.validation.owner"), ownerBox).withOKEnabled()
    }
    val name = nameField.text.trim()
    if (name.isEmpty() || !NAME_PATTERN.matches(name)) {
      return ValidationInfo(GiteaBundle.message("share.validation.name"), nameField)
    }
    val remote = remoteField.text.trim()
    if (remote.isEmpty() || remote.any { it.isWhitespace() }) {
      return ValidationInfo(GiteaBundle.message("share.validation.remote.empty"), remoteField)
    }
    if (remote in existingRemotes) {
      return ValidationInfo(GiteaBundle.message("share.validation.remote.exists", remote), remoteField)
    }
    return null
  }

  /** Fills the owner box for the selected account; an error shows as validation message. */
  private fun loadOwners() {
    val account = accountBox.selectedItem as? GiteaAccount ?: return
    ownersJob?.cancel()
    ownerBox.model = DefaultComboBoxModel()
    ownerBox.isEnabled = false
    ownersError = null
    ownersJob = cs.launch {
      try {
        val owners = loadOwners(account)
        ownerBox.model = DefaultComboBoxModel(owners.toTypedArray())
        ownerBox.isEnabled = true
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (e: Exception) {
        ownersError = GiteaConnectionTester.describe(e)
      }
      initValidation()
    }
  }

  /** The user's own namespace first, then their organizations. */
  private suspend fun loadOwners(account: GiteaAccount): List<GiteaShareOwner> {
    val api = GiteaApiManager.getInstance().getClient(account)
    val user = api.getCurrentUser()
    val organizations = api.loadCurrentUserOrganizations().mapNotNull { (it.username ?: it.name)?.let { login -> GiteaShareOwner(login, true) } }
    return listOf(GiteaShareOwner(user.login, false)) + organizations
  }

  companion object {
    /** Gitea repository names: letters, digits, `-`, `_`, `.` (not `.` or `..` alone). */
    private val NAME_PATTERN = Regex("^(?!\\.{1,2}$)[A-Za-z0-9_.-]{1,100}$")

    /** "origin", unless the project already has one (e.g. a GitHub remote). */
    private fun defaultRemoteName(existingRemotes: Set<String>): String = when {
      "origin" in existingRemotes -> "gitea"
      else -> "origin"
    }

    fun sanitizeName(projectName: String): String =
      projectName.trim().replace(Regex("[^A-Za-z0-9_.-]+"), "-").trim('-').ifEmpty { "project" }
  }
}
