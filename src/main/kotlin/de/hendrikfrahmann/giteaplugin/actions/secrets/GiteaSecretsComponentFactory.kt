package de.hendrikfrahmann.giteaplugin.actions.secrets

import com.intellij.collaboration.ui.VerticalListPanel
import com.intellij.collaboration.ui.icon.CIBuildStatusIcons
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.TableView
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.UIUtil
import de.hendrikfrahmann.giteaplugin.api.GiteaHttpStatusError
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaSecretDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaVariableDTO
import de.hendrikfrahmann.giteaplugin.api.request.GiteaActionsScope
import de.hendrikfrahmann.giteaplugin.api.request.createVariable
import de.hendrikfrahmann.giteaplugin.api.request.deleteSecret
import de.hendrikfrahmann.giteaplugin.api.request.deleteVariable
import de.hendrikfrahmann.giteaplugin.api.request.loadOrganizationPermissions
import de.hendrikfrahmann.giteaplugin.api.request.loadSecrets
import de.hendrikfrahmann.giteaplugin.api.request.loadVariables
import de.hendrikfrahmann.giteaplugin.api.request.putSecret
import de.hendrikfrahmann.giteaplugin.api.request.updateVariable
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

private val LOG = logger<GiteaSecretsComponentFactory>()

/**
 * Content of the "Runners, Secrets & Variables" dialog: tabs with the runners that serve the repository
 * (display only), Actions secrets (names only, values can be set but never read) and variables (add / edit / delete).
 * Secrets and variables are kept per level, chosen in a "Level" box per tab: the repository (repo admins)
 * and its organization (org owners) for both, the user's own account for variables only – Gitea can't list user
 * secrets, so they aren't offered.
 */
object GiteaSecretsComponentFactory {
  private const val GAP = 6
  private const val TAB_CONTENT_TOP_INSET = 8

  /** Every logged-in user has the user level for variables; the other levels depend on permissions. */
  fun isAvailable(connection: GiteaProjectConnection): Boolean = !connection.isAnonymous && connection.currentUser != null

  fun create(cs: CoroutineScope, project: Project, connection: GiteaProjectConnection, parent: () -> Component): JComponent {
    val panel = SecretsAndVariablesPanel(cs, project, connection, parent)
    val repository = connection.repo.repository.path
    val user = connection.currentUser?.login.orEmpty()
    return JBTabbedPane().apply {
      addTab(GiteaBundle.message("actions.runners.title"), withHint(GiteaBundle.message("actions.runners.hint"), createRunners(cs, connection)))
      addTab(GiteaBundle.message("actions.secrets.title"),
             withHint(GiteaBundle.message("actions.secrets.hint"), panel.secretsPanel, scopeBox(panel.secretScopes, repository, user)))
      addTab(GiteaBundle.message("actions.variables.title"),
             withHint(GiteaBundle.message("actions.variables.hint"), panel.variablesPanel, scopeBox(panel.variableScopes, repository, user)))
    }
  }

  /** The secrets and variables tables with their levels, loaders and add / edit / delete operations. */
  private class SecretsAndVariablesPanel(
    private val cs: CoroutineScope,
    private val project: Project,
    private val connection: GiteaProjectConnection,
    private val parent: () -> Component,
  ) {
    private val api = connection.api
    private val repositoryScopes: List<GiteaActionsScope> =
      listOfNotNull(GiteaActionsScope.Repository(connection.repo.repository.path).takeIf { isRepositoryAdmin(connection) })
    val secretScopes = CollectionComboBoxModel<GiteaActionsScope>(repositoryScopes.toMutableList())
    val variableScopes = CollectionComboBoxModel<GiteaActionsScope>((repositoryScopes + GiteaActionsScope.User).toMutableList())

    private val secretsModel = ListTableModel<GiteaSecretDTO>(
      column<GiteaSecretDTO>(GiteaBundle.message("actions.secrets.column.name")) { it.name },
      column<GiteaSecretDTO>(GiteaBundle.message("actions.secrets.column.description")) { it.description.orEmpty() },
      column<GiteaSecretDTO>(GiteaBundle.message("actions.secrets.column.created")) { secret ->
        secret.createdAt?.let { DateFormatUtil.formatPrettyDateTime(it) }.orEmpty()
      },
    )
    private val variablesModel = ListTableModel<GiteaVariableDTO>(
      column<GiteaVariableDTO>(GiteaBundle.message("actions.secrets.column.name")) { it.name },
      // one line per row: line breaks shown as ⏎
      column<GiteaVariableDTO>(GiteaBundle.message("actions.secrets.column.value")) { it.data.replace("\n", " ⏎ ") },
      column<GiteaVariableDTO>(GiteaBundle.message("actions.secrets.column.description")) { it.description.orEmpty() },
    )
    private val secretsTable = table(secretsModel)
    private val variablesTable = table(variablesModel)
    private val secrets = Loader(cs, secretsTable, secretsModel, GiteaBundle.message("actions.secrets.empty")) {
      secretScopes.selected?.let { api.loadSecrets(it) }
    }
    private val variables = Loader(cs, variablesTable, variablesModel, GiteaBundle.message("actions.variables.empty")) {
      variableScopes.selected?.let { api.loadVariables(it) }
    }

    val secretsPanel: JComponent = ToolbarDecorator.createDecorator(secretsTable)
      .setAddAction { addSecret() }
      // no level: neither repo admin nor org owner
      .setAddActionUpdater { secretScopes.selected != null }
      .setEditAction { updateSecret() }
      .setEditActionName(GiteaBundle.message("actions.secrets.update.action"))
      .setRemoveAction { deleteSecret() }
      .addExtraAction(refreshAction(secrets))
      .disableUpDownActions()
      .createPanel()

    val variablesPanel: JComponent = ToolbarDecorator.createDecorator(variablesTable)
      .setAddAction { addVariable() }
      .setEditAction { editVariable() }
      .setRemoveAction { deleteVariable() }
      .addExtraAction(refreshAction(variables))
      .disableUpDownActions()
      .createPanel()

    init {
      onDoubleClick(secretsTable, ::updateSecret)
      onDoubleClick(variablesTable, ::editVariable)
      secretScopes.onSelectionChanged(secrets::reload)
      variableScopes.onSelectionChanged(variables::reload)
      secrets.reload()
      variables.reload()
      cs.launch(Dispatchers.EDT) { addOrganizationLevelIfOwner() }
    }

    private fun addSecret() {
      val scope = secretScopes.selected ?: return
      val dialog = GiteaSecretDialog(project, cs, null, null, secretNames()) { name, value, description ->
        api.putSecret(scope, name, value, description)
      }
      if (dialog.showAndGet()) {
        secrets.reload()
      }
    }

    private fun updateSecret() {
      val scope = secretScopes.selected ?: return
      val secret = secretsTable.selectedObject ?: return
      val dialog = GiteaSecretDialog(project, cs, secret.name, secret.description, secretNames()) { name, value, description ->
        api.putSecret(scope, name, value, description)
      }
      if (dialog.showAndGet()) {
        secrets.reload()
      }
    }

    private fun deleteSecret() {
      val scope = secretScopes.selected ?: return
      val secret = secretsTable.selectedObject ?: return
      confirmAndDelete(GiteaBundle.message("actions.secrets.delete.confirm", secret.name), GiteaBundle.message("actions.secrets.delete.title"), secrets) {
        api.deleteSecret(scope, secret.name)
      }
    }

    private fun addVariable() {
      val scope = variableScopes.selected ?: return
      val dialog = GiteaVariableDialog(project, cs, null, null, null, variableNames()) { name, value, description ->
        api.createVariable(scope, name, value, description)
      }
      if (dialog.showAndGet()) {
        variables.reload()
      }
    }

    private fun editVariable() {
      val scope = variableScopes.selected ?: return
      val variable = variablesTable.selectedObject ?: return
      val dialog = GiteaVariableDialog(project, cs, variable.name, variable.data, variable.description, variableNames()) { name, value, description ->
        api.updateVariable(scope, variable.name, name, value, description)
      }
      if (dialog.showAndGet()) {
        variables.reload()
      }
    }

    private fun deleteVariable() {
      val scope = variableScopes.selected ?: return
      val variable = variablesTable.selectedObject ?: return
      val question = GiteaBundle.message("actions.variables.delete.confirm", variable.name)
      confirmAndDelete(question, GiteaBundle.message("actions.variables.delete.title"), variables) {
        api.deleteVariable(scope, variable.name)
      }
    }

    /** Names in use, upper-case like Gitea stores them – the dialogs refuse duplicates. */
    private fun secretNames() = secretsModel.items.mapTo(HashSet()) { it.name.uppercase() }

    private fun variableNames() = variablesModel.items.mapTo(HashSet()) { it.name.uppercase() }

    /** Runs a delete after the question; reloads, shows the server's message if it fails. */
    private fun confirmAndDelete(question: String, title: String, loader: Loader<*>, operation: suspend () -> Unit) {
      if (Messages.showYesNoDialog(parent(), question, title, Messages.getWarningIcon()) != Messages.YES) {
        return
      }
      cs.launch(Dispatchers.EDT) {
        try {
          withContext(Dispatchers.Default) { operation() }
        }
        catch (e: CancellationException) {
          throw e
        }
        catch (e: Exception) {
          Messages.showErrorDialog(parent(), (e as? GiteaHttpStatusError)?.apiMessage ?: e.localizedMessage, title)
        }
        loader.reload()
      }
    }

    /** The organization level is only for org owners (members of an admin team get 403 too). */
    private suspend fun addOrganizationLevelIfOwner() {
      val owner = connection.repository.owner ?: return
      val user = connection.currentUser?.login.orEmpty()
      if (!owner.type.equals("Organization", ignoreCase = true) || user.isEmpty() || !isOrganizationOwner(user, owner.login)) {
        return
      }
      val organization = GiteaActionsScope.Organization(owner.login)
      for (scopes in listOf(secretScopes, variableScopes)) {
        scopes.add(repositoryScopes.size, organization)
        if (scopes.selected == null) {
          scopes.selectedItem = organization
        }
      }
    }

    private suspend fun isOrganizationOwner(user: String, organization: String): Boolean =
      try {
        withContext(Dispatchers.Default) { api.loadOrganizationPermissions(user, organization).isOwner }
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (e: Exception) {
        LOG.info("Could not read the permissions of $user in $organization", e)
        false
      }
  }

  private fun refreshAction(loader: Loader<*>) =
    object : DumbAwareAction(GiteaBundle.messagePointer("action.Gitea.Actions.Refresh.text"), AllIcons.Actions.Refresh) {
      override fun actionPerformed(e: AnActionEvent) = loader.reload()
    }

  /** Loads one table for the level its box shows; `null` from [load]: no level to show (see `noLevelText`). */
  private class Loader<T>(
    private val cs: CoroutineScope,
    private val table: TableView<T>,
    private val model: ListTableModel<T>,
    private val emptyText: String,
    private val load: suspend () -> List<T>?,
  ) {
    private var job: Job? = null

    fun reload() {
      model.items = emptyList()
      table.emptyText.text = GiteaBundle.message("actions.loading")
      job?.cancel()
      job = cs.launch(Dispatchers.EDT) {
        try {
          val items = withContext(Dispatchers.Default) { load() }
          model.items = items.orEmpty()
          table.emptyText.text = when {
            items == null -> GiteaBundle.message("actions.secrets.no.level")
            else -> emptyText
          }
        }
        catch (e: CancellationException) {
          throw e
        }
        catch (e: Exception) {
          LOG.info("Could not load the secrets/variables", e)
          table.emptyText.text = GiteaBundle.message("actions.load.error", (e as? GiteaHttpStatusError)?.apiMessage ?: e.localizedMessage)
        }
      }
    }
  }

  private fun CollectionComboBoxModel<*>.onSelectionChanged(action: () -> Unit) = addListDataListener(object : ListDataListener {
    // index -1 on both ends: the selection changed
    override fun contentsChanged(e: ListDataEvent) {
      if (e.index0 == -1 && e.index1 == -1) {
        action()
      }
    }
    override fun intervalAdded(e: ListDataEvent) = Unit
    override fun intervalRemoved(e: ListDataEvent) = Unit
  })

  /** "Level:" box over a secrets or variables table. */
  private fun scopeBox(model: CollectionComboBoxModel<GiteaActionsScope>, repository: GiteaRepositoryPath, user: String): JComponent =
    JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), 0)).apply {
      border = JBUI.Borders.emptyBottom(GAP)
      add(JBLabel(GiteaBundle.message("actions.secrets.scope")))
      add(ComboBox(model).apply {
        // the level in gray, then its name: "Repository  owner/repo"
        renderer = object : ColoredListCellRenderer<GiteaActionsScope>() {
          override fun customizeCellRenderer(list: JList<out GiteaActionsScope>, value: GiteaActionsScope?, index: Int, selected: Boolean, hasFocus: Boolean) {
            val (level, name) = when (value) {
              is GiteaActionsScope.Repository -> GiteaBundle.message("actions.secrets.scope.repository") to repository.toString()
              is GiteaActionsScope.Organization -> GiteaBundle.message("actions.secrets.scope.organization") to value.name
              GiteaActionsScope.User -> GiteaBundle.message("actions.secrets.scope.user") to user
              null -> return
            }
            append(level, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append("  ")
            append(name)
          }
        }
      })
    }

  private fun isRepositoryAdmin(connection: GiteaProjectConnection): Boolean = connection.repository.permissions?.admin == true

  /** Runners that can pick up this repository's jobs – display only, no management. */
  private fun createRunners(cs: CoroutineScope, connection: GiteaProjectConnection): JComponent {
    val model = ListTableModel<GiteaRunner>(
      column<GiteaRunner>(GiteaBundle.message("actions.secrets.column.name")) { it.name },
      object : ColumnInfo<GiteaRunner, GiteaRunner>(GiteaBundle.message("actions.runners.column.status")) {
        override fun valueOf(item: GiteaRunner): GiteaRunner = item
        override fun getRenderer(item: GiteaRunner?): TableCellRenderer = RUNNER_STATE_RENDERER
      },
      column<GiteaRunner>(GiteaBundle.message("actions.runners.column.level")) { GiteaBundle.message("actions.runners.level.${it.level.name.lowercase()}") },
      column<GiteaRunner>(GiteaBundle.message("actions.runners.column.labels")) { r ->
        (r.labels + listOfNotNull(GiteaBundle.message("actions.runners.ephemeral").takeIf { r.ephemeral })).joinToString(", ")
      },
    )
    val table = table(model)
    val reload = { cs.launch(Dispatchers.EDT) { loadRunners(connection, table, model) } }
    val refresh = object : DumbAwareAction(GiteaBundle.messagePointer("action.Gitea.Actions.Refresh.text"), AllIcons.Actions.Refresh) {
      override fun actionPerformed(e: AnActionEvent) {
        reload()
      }
    }
    reload()
    return ToolbarDecorator.createDecorator(table)
      .disableAddAction()
      .disableRemoveAction()
      .disableUpDownActions()
      .addExtraAction(refresh)
      .createPanel()
  }

  /** Only site admins see instance runners, the common setup – the empty text says so to everyone else. */
  private suspend fun loadRunners(connection: GiteaProjectConnection, table: TableView<GiteaRunner>, model: ListTableModel<GiteaRunner>) {
    table.emptyText.text = GiteaBundle.message("actions.loading")
    try {
      model.items = withContext(Dispatchers.Default) { GiteaRunners.load(connection, isRepositoryAdmin(connection)) }
      table.emptyText.text = GiteaBundle.message("actions.runners.empty")
      if (connection.currentUser?.isAdmin != true) {
        table.emptyText.appendSecondaryText(GiteaBundle.message("actions.runners.empty.hint"), SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
      }
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      LOG.info("Could not load the runners of ${connection.repo.repository.path}", e)
      table.emptyText.text = GiteaBundle.message("actions.load.error", (e as? GiteaHttpStatusError)?.apiMessage ?: e.localizedMessage)
    }
  }

  private val RUNNER_STATE_RENDERER = object : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
      super.getTableCellRendererComponent(table, null, isSelected, hasFocus, row, column)
      val state = (value as? GiteaRunner)?.state ?: return this
      text = GiteaBundle.message("actions.runners.state.${state.name.lowercase()}")
      icon = when (state) {
        GiteaRunnerState.ONLINE -> CIBuildStatusIcons.success
        GiteaRunnerState.BUSY -> CIBuildStatusIcons.inProgress
        GiteaRunnerState.OFFLINE -> CIBuildStatusIcons.skipped
        GiteaRunnerState.DISABLED -> CIBuildStatusIcons.cancelled
      }
      return this
    }
  }

  private fun <T> column(name: String, value: (T) -> String) = object : ColumnInfo<T, String>(name) {
    override fun valueOf(item: T): String = value(item)
  }

  private fun <T> table(model: ListTableModel<T>) = TableView(model).apply {
    setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
    setShowGrid(false)
    tableHeader.reorderingAllowed = false
  }

  private fun onDoubleClick(table: TableView<*>, action: () -> Unit) {
    object : DoubleClickListener() {
      override fun onDoubleClick(event: MouseEvent): Boolean {
        if (table.selectedRow < 0) {
          return false
        }
        action()
        return true
      }
    }.installOn(table)
  }

  private fun withHint(hint: String, content: JComponent, header: JComponent? = null): JComponent = JPanel(BorderLayout()).apply {
    border = JBUI.Borders.empty(TAB_CONTENT_TOP_INSET, 0, 0, 0)
    add(VerticalListPanel().apply {
      header?.let(::add)
      add(JBLabel(hint).apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.emptyBottom(GAP)
      })
    }, BorderLayout.NORTH)
    add(content, BorderLayout.CENTER)
  }
}
