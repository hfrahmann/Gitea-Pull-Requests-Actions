package de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaLabelDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaMilestoneDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaPullRequestDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.api.request.GiteaEditPullRequestRequest
import de.hendrikfrahmann.giteaplugin.api.request.editPullRequest
import de.hendrikfrahmann.giteaplugin.api.request.labelsFlow
import de.hendrikfrahmann.giteaplugin.api.request.loadAssignees
import de.hendrikfrahmann.giteaplugin.api.request.milestonesFlow
import de.hendrikfrahmann.giteaplugin.api.request.removeReviewRequests
import de.hendrikfrahmann.giteaplugin.api.request.requestReviewers
import de.hendrikfrahmann.giteaplugin.api.request.searchUsers
import de.hendrikfrahmann.giteaplugin.api.request.setIssueMilestone
import de.hendrikfrahmann.giteaplugin.notification.GiteaNotificationIds
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestDataProvider
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import de.hendrikfrahmann.giteaplugin.util.childScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

private val LOG = logger<GiteaPullRequestEditViewModel>()

/**
 * Edits reviewers, assignees, labels and milestone. Only offered when the user may write to the repository; the
 * choices are loaded when a popup opens, the details are reloaded after each change.
 */
class GiteaPullRequestEditViewModel(
  parentCs: CoroutineScope,
  private val connection: GiteaProjectConnection,
  private val dataProvider: GiteaPullRequestDataProvider,
  private val pullRequest: StateFlow<GiteaPullRequestDTO>,
) {
  private val cs = parentCs.childScope()
  private val api = connection.api
  private val repository = connection.repo.repository.path

  val canEdit: Boolean get() = connection.repository.permissions?.push == true

  /** Users with write access; with [allUsers] every user of the instance (setting for reviewers, Gitea allows that for reads). */
  suspend fun loadUsers(allUsers: Boolean = false): List<GiteaUserDTO> =
    (when {
      allUsers -> api.searchUsers()
      else -> api.loadAssignees(repository)
    }).sortedBy { it.login.lowercase() }
  suspend fun loadLabels(): List<GiteaLabelDTO> = api.labelsFlow(repository).toList().flatten()
  suspend fun loadMilestones(): List<GiteaMilestoneDTO> = api.milestonesFlow(repository).toList().flatten().filter { it.state != "closed" }

  fun setReviewers(current: Collection<GiteaUserDTO>, selected: Collection<GiteaUserDTO>) = edit {
    val before = current.map { it.login }.toSet()
    val after = selected.map { it.login }.toSet()
    api.removeReviewRequests(repository, dataProvider.number, (before - after).toList())
    api.requestReviewers(repository, dataProvider.number, (after - before).toList())
  }

  fun setAssignees(selected: Collection<GiteaUserDTO>) = edit {
    api.editPullRequest(repository, dataProvider.number, GiteaEditPullRequestRequest(assignees = selected.map { it.login }))
  }

  fun setLabels(selected: Collection<GiteaLabelDTO>) = edit {
    api.editPullRequest(repository, dataProvider.number, GiteaEditPullRequestRequest(labels = selected.map { it.id }))
  }

  /** `null` removes the milestone. */
  fun setMilestone(milestone: GiteaMilestoneDTO?) = edit {
    api.setIssueMilestone(repository, dataProvider.number, milestone?.id)
  }

  private fun edit(block: suspend () -> Unit) {
    cs.launch {
      try {
        block()
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (e: Exception) {
        LOG.info("Could not edit pull request #${dataProvider.number}", e)
        NotificationGroupManager.getInstance().getNotificationGroup(GiteaNotificationIds.GROUP)
          .createNotification(GiteaBundle.message("pull.request.edit.failed"), e.localizedMessage.orEmpty(), NotificationType.ERROR)
          .setDisplayId(GiteaNotificationIds.PULL_REQUEST_EDIT_FAILED)
          .notify(connection.repo.gitRepository.project)
      }
      finally {
        dataProvider.refresh()
      }
    }
  }
}
