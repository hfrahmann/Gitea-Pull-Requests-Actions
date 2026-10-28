package de.hendrikfrahmann.giteaplugin.authentication.accounts

import com.intellij.collaboration.auth.AccountManager
import com.intellij.collaboration.auth.PersistentDefaultAccountHolder
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

/** The account to use in this project when several match (workspace file, not shared). */
@Service(Service.Level.PROJECT)
@State(name = "GiteaDefaultAccount", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)], reportStatistic = false)
class GiteaProjectDefaultAccountHolder(project: Project, cs: CoroutineScope)
  : PersistentDefaultAccountHolder<GiteaAccount>(project, cs) {

  override fun accountManager(): AccountManager<GiteaAccount, *> = service<GiteaAccountManager>()

  override fun notifyDefaultAccountMissing() = Unit

  companion object {
    fun getInstance(project: Project): GiteaProjectDefaultAccountHolder = project.service()
  }
}
