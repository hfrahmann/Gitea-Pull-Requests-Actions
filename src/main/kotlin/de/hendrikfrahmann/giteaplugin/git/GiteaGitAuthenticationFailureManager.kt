package de.hendrikfrahmann.giteaplugin.git

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountManager
import git4idea.remote.hosting.http.HostedGitAuthenticationFailureManager
import kotlinx.coroutines.CoroutineScope

/** Accounts whose token git rejected for a URL; skipped until their credentials change. */
@Service(Service.Level.PROJECT)
class GiteaGitAuthenticationFailureManager(cs: CoroutineScope)
  : HostedGitAuthenticationFailureManager<GiteaAccount>({ GiteaAccountManager.getInstance() }, cs) {

  companion object {
    fun getInstance(project: Project): GiteaGitAuthenticationFailureManager = project.service()
  }
}
