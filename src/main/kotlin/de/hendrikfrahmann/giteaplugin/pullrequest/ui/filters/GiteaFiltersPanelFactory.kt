package de.hendrikfrahmann.giteaplugin.pullrequest.ui.filters

import com.intellij.collaboration.ui.codereview.avatar.Avatar
import com.intellij.collaboration.ui.codereview.avatar.CodeReviewAvatarUtils
import com.intellij.collaboration.ui.codereview.list.error.ErrorStatusPresenter
import com.intellij.collaboration.ui.codereview.list.search.ChooserPopupUtil
import com.intellij.collaboration.ui.codereview.list.search.DropDownComponentFactory
import com.intellij.collaboration.ui.codereview.list.search.PopupConfig
import com.intellij.collaboration.ui.codereview.list.search.ReviewListSearchPanelFactory
import com.intellij.collaboration.ui.util.popup.PopupItemPresentation
import com.intellij.collaboration.util.IncrementallyComputedValue
import com.intellij.ui.ColorUtil
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.ColorIcon
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestState
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.filters.GiteaPullRequestsFiltersValue.UserFilterValue
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.annotations.Nls
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.JComponent

/** Search field with quick filters and the State / Author / Assignee / Reviewer / Label / Milestone drop-downs. */
class GiteaFiltersPanelFactory(vm: GiteaPullRequestsFiltersViewModel)
  : ReviewListSearchPanelFactory<GiteaPullRequestsFiltersValue, GiteaPullRequestsQuickFilter, GiteaPullRequestsFiltersViewModel>(vm) {

  /** Text shown in the search field for the current filters, e.g. `state:"Open" author:"intellij-dev"`. */
  override fun getShortText(searchValue: GiteaPullRequestsFiltersValue): @Nls String = shortText(searchValue)

  override fun createFilters(viewScope: CoroutineScope): List<JComponent> = listOf(
    DropDownComponentFactory(vm.stateFilterState).create(
      viewScope,
      filterName = GiteaBundle.message("pull.request.list.filter.state"),
      items = listOf(GiteaPullRequestState.OPEN, GiteaPullRequestState.MERGED, GiteaPullRequestState.CLOSED),
      onSelect = {},
      valuePresenter = ::stateText,
    ),
    userFilter(viewScope, vm.authorFilterState, GiteaBundle.message("pull.request.list.filter.author"), vm.users),
    DropDownComponentFactory(vm.labelFilterState).create(
      viewScope,
      filterName = GiteaBundle.message("pull.request.list.filter.label"),
      valuePresenter = { it },
      chooseValue = { point ->
        ChooserPopupUtil.showChooserPopupWithIncrementalLoading(point, vm.labels, { label ->
          val color = label.color?.let { runCatching { ColorUtil.fromHex(it) }.getOrNull() }
          PopupItemPresentation.Simple(label.name, color?.let { ColorIcon(12, it) }, null)
        }, popupConfig(vm))?.name
      },
    ),
    userFilter(viewScope, vm.assigneeFilterState, GiteaBundle.message("pull.request.list.filter.assignee"), vm.users),
    // Gitea can only search review requests of the current user, others are filtered on the loaded PRs
    userFilter(viewScope, vm.reviewerFilterState, GiteaBundle.message("pull.request.list.filter.reviewer"), vm.users),
    DropDownComponentFactory(vm.milestoneFilterState).create(
      viewScope,
      filterName = GiteaBundle.message("pull.request.list.filter.milestone"),
      valuePresenter = { it },
      chooseValue = { point ->
        ChooserPopupUtil.showChooserPopupWithIncrementalLoading(point, vm.milestones, { milestone ->
          PopupItemPresentation.Simple(milestone.title, null, null)
        }, popupConfig(vm))?.title
      },
    ),
  )

  override fun GiteaPullRequestsQuickFilter.getQuickFilterTitle(): @Nls String = when (this) {
    is GiteaPullRequestsQuickFilter.Open -> GiteaBundle.message("pull.request.list.filter.quick.open")
    is GiteaPullRequestsQuickFilter.CreatedByMe -> GiteaBundle.message("pull.request.list.filter.quick.created.by.me")
    is GiteaPullRequestsQuickFilter.AssignedToMe -> GiteaBundle.message("pull.request.list.filter.quick.assigned.to.me")
    is GiteaPullRequestsQuickFilter.ReviewRequested -> GiteaBundle.message("pull.request.list.filter.quick.review.requested")
    is GiteaPullRequestsQuickFilter.Closed -> GiteaBundle.message("pull.request.list.filter.quick.closed")
  }

  private fun userFilter(
    viewScope: CoroutineScope,
    state: MutableStateFlow<UserFilterValue?>,
    filterName: @Nls String,
    users: StateFlow<IncrementallyComputedValue<List<GiteaUserDTO>>>,
  ): JComponent = DropDownComponentFactory(state).create(
    viewScope,
    filterName,
    valuePresenter = { it.presentableName },
    chooseValue = { point -> chooseUser(point, users)?.let(UserFilterValue::of) },
  )

  private suspend fun chooseUser(point: RelativePoint, users: StateFlow<IncrementallyComputedValue<List<GiteaUserDTO>>>): GiteaUserDTO? =
    ChooserPopupUtil.showChooserPopupWithIncrementalLoading(point, users, { user ->
      val icon = vm.avatarIconsProvider.getIcon(user, CodeReviewAvatarUtils.expectedIconHeight(Avatar.Sizes.BASE))
      PopupItemPresentation.Simple(user.login, icon, user.fullName?.takeIf { it.isNotBlank() })
    }, popupConfig(vm))

  companion object {
    fun stateText(state: GiteaPullRequestState): @Nls String = when (state) {
      GiteaPullRequestState.OPEN -> GiteaBundle.message("pull.request.state.open")
      GiteaPullRequestState.MERGED -> GiteaBundle.message("pull.request.state.merged")
      GiteaPullRequestState.CLOSED -> GiteaBundle.message("pull.request.state.closed")
    }

    @Suppress("HardCodedStringLiteral")
    fun shortText(value: GiteaPullRequestsFiltersValue): @Nls String = buildString {
      with(value) {
        if (searchQuery != null) {
          append("\"$searchQuery\" ")
        }
        if (state != null) {
          append("state:\"${stateText(state)}\" ")
        }
        if (author != null) {
          append("author:\"${author.login}\" ")
        }
        if (assignee != null) {
          append("assignee:\"${assignee.login}\" ")
        }
        if (reviewer != null) {
          append("reviewer:\"${reviewer.login}\" ")
        }
        if (label != null) {
          append("label:\"$label\" ")
        }
        if (milestone != null) {
          append("milestone:\"$milestone\" ")
        }
      }
    }

    private fun popupConfig(vm: GiteaPullRequestsFiltersViewModel): PopupConfig = PopupConfig(
      errorPresenter = ErrorStatusPresenter.simple(
        GiteaBundle.message("pull.request.list.filter.error"),
        descriptionProvider = { null },
        actionProvider = {
          object : AbstractAction(GiteaBundle.message("pull.request.list.filter.error.retry")) {
            override fun actionPerformed(e: ActionEvent?) = vm.reloadData()
          }
        },
      ),
    )
  }
}
