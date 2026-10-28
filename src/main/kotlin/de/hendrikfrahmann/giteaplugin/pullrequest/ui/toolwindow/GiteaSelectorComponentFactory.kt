package de.hendrikfrahmann.giteaplugin.pullrequest.ui.toolwindow

import com.intellij.collaboration.messages.CollaborationToolsBundle
import com.intellij.collaboration.ui.ExceptionUtil
import com.intellij.collaboration.ui.codereview.list.error.ErrorStatusPresenter
import com.intellij.collaboration.util.URIUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.NlsSafe
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.isGiteaUnauthorized
import de.hendrikfrahmann.giteaplugin.authentication.GiteaLoginMethod
import de.hendrikfrahmann.giteaplugin.authentication.GiteaLoginResult
import de.hendrikfrahmann.giteaplugin.authentication.GiteaLoginUtil
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountManager
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaProjectDefaultAccountHolder
import de.hendrikfrahmann.giteaplugin.authentication.ui.GiteaAccountsDetailsProvider
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectMapping
import de.hendrikfrahmann.giteaplugin.repository.ui.GiteaRepositoryAndAccountSelectorViewModel
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import git4idea.remote.hosting.ui.RepositoryAndAccountSelectorComponentFactory
import git4idea.remote.hosting.ui.RepositoryAndAccountSelectorViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent

/** Not connected yet: pick repository and account (or log in), then "View Pull Requests". */
object GiteaSelectorComponentFactory {
  fun create(cs: CoroutineScope, project: Project, vm: GiteaRepositoryAndAccountSelectorViewModel): JComponent =
    SelectorPanel(cs, project, vm).component

  private class SelectorPanel(
    cs: CoroutineScope,
    private val project: Project,
    private val vm: GiteaRepositoryAndAccountSelectorViewModel,
  ) {
    private val accountManager = GiteaAccountManager.getInstance()

    // public repositories can be read without an account
    private val browseLink = ActionLink(GiteaBundle.message("anonymous.browse")) { vm.browseAnonymously() }.apply {
      isVisible = false
      autoHideOnDisable = false
    }

    /** Offered while the selected repository's server has no account. */
    private val loginButtons: List<JButton> = listOf(
      JButton(GiteaBundle.message("login.action.oauth")).apply {
        isOpaque = false
        isVisible = false
        addActionListener { logIn(GiteaLoginMethod.OAUTH, null, submit = true) }
      },
      ActionLink(GiteaBundle.message("login.action.token")) { logIn(GiteaLoginMethod.TOKEN, null, submit = true) }.apply {
        isVisible = false
        autoHideOnDisable = false
      },
      browseLink,
    )

    val component: JComponent = RepositoryAndAccountSelectorComponentFactory(vm).create(
      scope = cs,
      defaultAccountHolder = GiteaProjectDefaultAccountHolder.getInstance(project),
      repoNamer = { mapping -> repoName(vm.repositoriesState.value, mapping) },
      detailsProvider = GiteaAccountsDetailsProvider(cs, accountManager),
      accountsPopupActionsSupplier = {
        listOf(action(GiteaBundle.message("login.action.oauth")) { logIn(GiteaLoginMethod.OAUTH, null, submit = false) },
               action(GiteaBundle.message("login.action.token")) { logIn(GiteaLoginMethod.TOKEN, null, submit = false) })
      },
      submitActionText = GiteaBundle.message("pull.request.toolwindow.view.button"),
      loginButtons = loginButtons,
      errorPresenter = ErrorPresenter { account -> logIn(GiteaLoginMethod.OAUTH, account, submit = true) },
    )

    init {
      cs.launch(Dispatchers.EDT) {
        combine(vm.repoSelectionState, vm.accountsState, vm.busyState, ::Triple).collect { (repo, accounts, busy) ->
          val visible = repo != null && accounts.isEmpty()
          loginButtons.forEach {
            it.isVisible = visible
            it.isEnabled = !busy
          }
          vm.anonymousRefused.value = false
        }
      }
      cs.launch(Dispatchers.EDT) {
        vm.anonymousRefused.collect { refused ->
          if (refused && browseLink.isShowing) {
            showAnonymousRefused()
          }
        }
      }
    }

    /** A new login for the selected repository's server, or a new one for [account]; [submit] connects right away. */
    private fun logIn(method: GiteaLoginMethod, account: GiteaAccount?, submit: Boolean) {
      val repo = vm.repoSelectionState.value ?: return
      val unique = { server: GiteaServerPath, name: String -> accountManager.isAccountUnique(server, name) }
      val result = when {
        account != null -> GiteaLoginUtil.reLogIn(project, component, account, method, unique)
        method == GiteaLoginMethod.OAUTH -> GiteaLoginUtil.logInViaOAuth(project, component, repo.repository.serverPath, unique)
        else -> GiteaLoginUtil.logInViaToken(project, component, repo.repository.serverPath, unique)
      }
      if (result is GiteaLoginResult.Success) {
        vm.authenticateAccount(result.account, result.credentials, submit)
      }
    }

    /** The repository is private or the server requires sign-in. */
    private fun showAnonymousRefused() {
      JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(GiteaBundle.message("anonymous.refused"), MessageType.WARNING, null)
        .setFadeoutTime(BALLOON_FADEOUT_MS)
        .createBalloon()
        .show(RelativePoint.getSouthWestOf(browseLink), Balloon.Position.below)
    }
  }

  private const val BALLOON_FADEOUT_MS = 8000L

  /** `owner/name`, with the server when the project has remotes on several servers. */
  private fun repoName(all: Set<GiteaProjectMapping>, mapping: GiteaProjectMapping): @NlsSafe String {
    val servers = all.map { it.repository.serverPath }.distinct()
    val path = mapping.repository.path.toString()
    return when {
      servers.size > 1 -> URIUtil.toStringWithoutScheme(mapping.repository.serverPath.toURI()).removeSuffix("/") + "/" + path
      else -> path
    }
  }

  private fun action(text: String, perform: () -> Unit): Action = object : AbstractAction(text) {
    override fun actionPerformed(e: ActionEvent?) = perform()
  }

  private class ErrorPresenter(private val reLogIn: (GiteaAccount) -> Unit)
    : ErrorStatusPresenter.Text<RepositoryAndAccountSelectorViewModel.Error> {
    override fun getErrorTitle(error: RepositoryAndAccountSelectorViewModel.Error): String = when (error) {
      is RepositoryAndAccountSelectorViewModel.Error.SubmissionError ->
        CollaborationToolsBundle.message("review.list.connection.failed.repository.account", error.repo.repository, error.account)
      is RepositoryAndAccountSelectorViewModel.Error.MissingCredentials -> CollaborationToolsBundle.message("review.list.connection.failed")
    }

    override fun getErrorDescription(error: RepositoryAndAccountSelectorViewModel.Error): String = when (error) {
      is RepositoryAndAccountSelectorViewModel.Error.SubmissionError -> when {
        error.exception.isGiteaUnauthorized -> CollaborationToolsBundle.message("account.token.invalid")
        else -> ExceptionUtil.getPresentableMessage(error.exception)
      }
      is RepositoryAndAccountSelectorViewModel.Error.MissingCredentials -> CollaborationToolsBundle.message("account.token.missing")
    }

    override fun getErrorAction(error: RepositoryAndAccountSelectorViewModel.Error): Action? {
      val account = (error as? RepositoryAndAccountSelectorViewModel.Error.SubmissionError)?.account as? GiteaAccount ?: return null
      return object : AbstractAction(GiteaBundle.message("pull.request.toolwindow.login.again")) {
        override fun actionPerformed(e: ActionEvent?) = reLogIn(account)
      }
    }
  }
}
