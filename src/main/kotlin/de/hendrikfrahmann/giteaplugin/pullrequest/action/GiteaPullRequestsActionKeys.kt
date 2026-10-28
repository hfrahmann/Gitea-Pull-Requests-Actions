package de.hendrikfrahmann.giteaplugin.pullrequest.action

import com.intellij.openapi.actionSystem.DataKey
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestShort
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaPullRequestDetailsViewModel
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.list.GiteaPullRequestsListViewModel
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.toolwindow.GiteaToolWindowConnectedProjectViewModel
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.toolwindow.GiteaToolWindowProjectViewModel

object GiteaPullRequestsActionKeys {
  @JvmStatic
  val PROJECT_VM: DataKey<GiteaToolWindowProjectViewModel> = DataKey.create("Gitea.PullRequests.Project.ViewModel")

  @JvmStatic
  val CONNECTED_PROJECT_VM: DataKey<GiteaToolWindowConnectedProjectViewModel> = DataKey.create("Gitea.PullRequests.Connected.Project.ViewModel")

  @JvmStatic
  val LIST_VM: DataKey<GiteaPullRequestsListViewModel> = DataKey.create("Gitea.PullRequests.List.ViewModel")

  @JvmStatic
  val DETAILS_VM: DataKey<GiteaPullRequestDetailsViewModel> = DataKey.create("Gitea.PullRequest.Details.ViewModel")

  /** The PR selected in the list. */
  @JvmStatic
  val SELECTED: DataKey<GiteaPullRequestShort> = DataKey.create("Gitea.PullRequests.Selected")
}

object GiteaPullRequestsActionPlaces {
  const val LIST_POPUP: String = "Gitea.PullRequest.List.Popup"
  const val DETAILS_POPUP: String = "Gitea.PullRequest.Details.Popup"
  const val CHANGES_POPUP: String = "Gitea.PullRequest.Changes.Popup"
}
