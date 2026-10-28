package de.hendrikfrahmann.giteaplugin.notification

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import de.hendrikfrahmann.giteaplugin.authentication.GiteaLoginMethod
import de.hendrikfrahmann.giteaplugin.authentication.GiteaLoginResult
import de.hendrikfrahmann.giteaplugin.authentication.GiteaLoginUtil
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountManager
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Notification groups and display ids – one place, as required by the coding conventions. Two groups, so they
 * can be configured separately under Settings | Appearance & Behavior | Notifications: [GROUP] (balloons: accounts,
 * sharing, pull requests) and [ACTIONS_GROUP] (shown at the "Gitea Actions" tool window: runs, operations, downloads).
 */
object GiteaNotificationIds {
  const val GROUP: String = "Gitea"
  const val ACTIONS_GROUP: String = "Gitea Actions"

  const val AUTH_FAILED: String = "gitea.auth.failed"
  const val SHARE_SUCCESS: String = "gitea.share.success"
  const val SHARE_FAILED: String = "gitea.share.failed"
  const val PULL_REQUEST_NOT_FOUND: String = "gitea.annotation.pull.request.not.found"
  const val PULL_REQUEST_EDIT_FAILED: String = "gitea.pull.request.edit.failed"
  const val PULL_REQUEST_ACTION_FAILED: String = "gitea.pull.request.action.failed"
  const val PULL_REQUEST_MERGE: String = "gitea.pull.request.merge"
  const val REVIEW_CHANGE_FAILED: String = "gitea.review.change.failed"
  const val PULL_REQUEST_CREATED: String = "gitea.pull.request.created"
  const val PUSH_PULL_REQUEST: String = "gitea.push.pull.request"
  const val ACTIONS_RUN_FINISHED: String = "gitea.actions.run.finished"
  const val ACTIONS_OPERATION_FAILED: String = "gitea.actions.operation.failed"
  const val ACTIONS_DOWNLOAD: String = "gitea.actions.download"
  const val ACTIONS_WORKFLOW_DISPATCHED: String = "gitea.actions.workflow.dispatched"
}

object GiteaNotifications {
  /** "Gitea: log in again" with actions to re-login via browser or token; stores the new credentials. */
  fun notifyAuthFailed(project: Project, account: GiteaAccount, details: String? = null) {
    NotificationGroupManager.getInstance().getNotificationGroup(GiteaNotificationIds.GROUP)
      .createNotification(GiteaBundle.message("notification.auth.failed.title", account),
                          details ?: GiteaBundle.message("notification.auth.failed.text"), NotificationType.WARNING)
      .setDisplayId(GiteaNotificationIds.AUTH_FAILED)
      .addAction(reloginAction(project, account, GiteaLoginMethod.OAUTH, GiteaBundle.message("login.action.oauth")))
      .addAction(reloginAction(project, account, GiteaLoginMethod.TOKEN, GiteaBundle.message("login.action.token")))
      .notify(project)
  }

  private fun reloginAction(project: Project, account: GiteaAccount, method: GiteaLoginMethod, text: String) =
    NotificationAction.createSimpleExpiring(text) {
      val accountManager = GiteaAccountManager.getInstance()
      val result = GiteaLoginUtil.reLogIn(project, null, account, method) { server, name -> accountManager.isAccountUnique(server, name) }
      if (result is GiteaLoginResult.Success) {
        service<GiteaNotificationsScope>().cs.launch { accountManager.updateAccount(result.account, result.credentials) }
      }
    }
}

/** Application scope for fire-and-forget work started from notifications. */
@Service(Service.Level.APP)
internal class GiteaNotificationsScope(val cs: CoroutineScope)
