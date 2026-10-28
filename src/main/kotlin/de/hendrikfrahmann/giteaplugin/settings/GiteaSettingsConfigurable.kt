package de.hendrikfrahmann.giteaplugin.settings

import com.intellij.collaboration.auth.ui.AccountsPanelFactory
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.util.ui.UIUtil
import de.hendrikfrahmann.giteaplugin.api.GiteaApiManager
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.authentication.GiteaConnectionTester
import de.hendrikfrahmann.giteaplugin.authentication.GiteaLoginUtil
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountManager
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaProjectDefaultAccountHolder
import de.hendrikfrahmann.giteaplugin.authentication.accounts.getClient
import de.hendrikfrahmann.giteaplugin.authentication.ui.GiteaAccountsDetailsProvider
import de.hendrikfrahmann.giteaplugin.authentication.ui.GiteaAccountsListModel
import de.hendrikfrahmann.giteaplugin.authentication.ui.GiteaAccountsPanelActionsController
import de.hendrikfrahmann.giteaplugin.repository.GiteaHostMappings
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * `Settings | Version Control | Gitea` – accounts, test connection, how to reach servers (default server, timeout, SSH,
 * pre-releases, remote hosts, custom OAuth apps). Behaviour options are on the child page [GiteaGeneralSettingsConfigurable].
 */
class GiteaSettingsConfigurable(private val project: Project) : BoundConfigurable("Gitea", HELP_TOPIC) {
  private var testJob: Job? = null

  override fun createPanel(): DialogPanel {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT + ModalityState.any().asContextElement())
    Disposer.register(checkNotNull(disposable) { "createPanel() is called after the configurable's disposable exists" }) { scope.cancel() }
    val settings = GiteaSettings.getInstance()
    return panel {
      accountsRows(scope, GiteaAccountsListModel())
      connectionGroup(settings)
      if (GiteaLoginUtil.isPasswordSafeMemoryOnly) {
        row { comment(GiteaBundle.message("settings.password.safe.memory.only")) }
      }
      hostMappingsGroup(settings)
      oauthAppsGroup(settings)
    }
  }

  /** The accounts list and "Test Connection" with its result below. */
  private fun Panel.accountsRows(scope: CoroutineScope, accountsModel: GiteaAccountsListModel) {
    val accountManager = GiteaAccountManager.getInstance()
    val accountsPanelFactory = AccountsPanelFactory(scope, accountManager, GiteaProjectDefaultAccountHolder.getInstance(project), accountsModel)
    val detailsProvider = GiteaAccountsDetailsProvider(scope, accountManager, accountsModel)
    val actionsController = GiteaAccountsPanelActionsController(project, accountsModel)
    row {
      accountsPanelFactory.accountsPanelCell(this, detailsProvider, actionsController).align(Align.FILL)
    }.resizableRow()

    val testResult = JBLabel().apply {
      setAllowAutoWrapping(true)
      setCopyable(true)
    }
    row {
      button(GiteaBundle.message("settings.test.connection")) { testConnection(scope, accountsModel, testResult) }
    }
    row { cell(testResult).align(AlignX.FILL) }
  }

  /** Tests the selected (or first) account with the credentials as edited, not yet applied. */
  private fun testConnection(scope: CoroutineScope, accountsModel: GiteaAccountsListModel, result: JBLabel) {
    val account = accountsModel.selectedAccount ?: accountsModel.accounts.firstOrNull()
    if (account == null) {
      result.icon = AllIcons.General.Information
      result.text = GiteaBundle.message("settings.test.connection.no.account")
      return
    }
    testJob?.cancel()
    result.icon = AllIcons.Process.Step_passive
    result.text = GiteaBundle.message("settings.test.connection.running", account)
    testJob = scope.launch {
      val apiManager = GiteaApiManager.getInstance()
      val api = accountsModel.newCredentials[account]?.let { apiManager.getClient(account.server, it.toApiCredentials()) }
                ?: apiManager.getClient(account, GiteaAccountManager.getInstance())
      showTestOutcome(result, GiteaConnectionTester.test(api))
    }
  }

  private fun showTestOutcome(result: JBLabel, outcome: GiteaConnectionTester.Outcome) {
    result.icon = when (outcome) {
      is GiteaConnectionTester.Outcome.Failure -> AllIcons.General.Error
      is GiteaConnectionTester.Outcome.Success -> when {
        outcome.missingScopes.isNotEmpty() -> AllIcons.General.Warning
        else -> AllIcons.General.InspectionsOK
      }
    }
    result.text = UIUtil.toHtml(StringUtil.escapeXmlEntities(outcome.message))
  }

  private fun Panel.connectionGroup(settings: GiteaSettings) {
    group(GiteaBundle.message("settings.connection.group")) {
      row(GiteaBundle.message("settings.default.server")) {
        textField()
          .columns(SERVER_COLUMNS)
          .bindText({ settings.defaultServerUrl }, { settings.defaultServerUrl = it })
          .validationOnApply { validateServer(it) }
          .comment(GiteaBundle.message("settings.default.server.comment", GiteaServerPath.DEFAULT_SERVER_URL))
      }
      row(GiteaBundle.message("settings.connection.timeout")) {
        intTextField(GiteaSettings.CONNECTION_TIMEOUT_RANGE)
          .columns(NUMBER_COLUMNS)
          .bindIntText({ settings.connectionTimeoutSeconds }, { settings.connectionTimeoutSeconds = it })
          .gap(RightGap.SMALL)
        label(GiteaBundle.message("settings.seconds"))
      }
      row {
        checkBox(GiteaBundle.message("settings.clone.ssh"))
          .bindSelected({ settings.cloneUsingSsh }, { settings.cloneUsingSsh = it })
      }
      row {
        checkBox(GiteaBundle.message("settings.pre.releases"))
          .bindSelected({ settings.acceptPreReleases }, { settings.acceptPreReleases = it })
          .comment(GiteaBundle.message("settings.pre.releases.comment"))
      }
    }
  }

  private fun Panel.hostMappingsGroup(settings: GiteaSettings) {
    collapsibleGroup(GiteaBundle.message("settings.hosts.group")) {
      row {
        textArea()
          .rows(TEXT_AREA_ROWS)
          .bindText({ settings.hostMappingsText }, { settings.hostMappingsText = it })
          .validationOnApply { validateHostMappings(it) }
          .comment(GiteaBundle.message("settings.hosts.comment", "example.org=http://127.0.0.1:3000"))
          .align(AlignX.FILL)
      }
    }.apply { expanded = settings.hostMappingsText.isNotEmpty() }
  }

  private fun Panel.oauthAppsGroup(settings: GiteaSettings) {
    collapsibleGroup(GiteaBundle.message("settings.oauth.group")) {
      row {
        textArea()
          .rows(TEXT_AREA_ROWS)
          .bindText({ settings.oauthClientIdsText }, { settings.oauthClientIdsText = it })
          .validationOnApply { validateClientIds(it) }
          .comment(GiteaBundle.message("settings.oauth.comment", "https://git.example.com=0123abcd-…"))
          .align(AlignX.FILL)
      }
    }
  }

  private fun validateServer(field: JBTextField): ValidationInfo? {
    val valid = field.text.isBlank() || runCatching { GiteaServerPath.fromUserInput(field.text) }.isSuccess
    return ValidationInfo(GiteaBundle.message("settings.default.server.invalid"), field).takeIf { !valid }
  }

  private fun validateHostMappings(textArea: JBTextArea): ValidationInfo? {
    val invalid = GiteaHostMappings.parseEntries(textArea.text).firstOrNull { it.isFailure } ?: return null
    return ValidationInfo(GiteaBundle.message("settings.hosts.invalid.line", invalid.exceptionOrNull()?.message ?: ""), textArea)
  }

  private fun validateClientIds(textArea: JBTextArea): ValidationInfo? {
    val invalidLine = textArea.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        .firstOrNull { GiteaSettings.parseClientIdLine(it) == null } ?: return null
    return ValidationInfo(GiteaBundle.message("settings.oauth.invalid.line", invalidLine), textArea)
  }

  companion object {
    const val HELP_TOPIC: String = "settings.gitea"

    private const val SERVER_COLUMNS = 30
    private const val NUMBER_COLUMNS = 4
    private const val TEXT_AREA_ROWS = 3
  }
}
