package de.hendrikfrahmann.giteaplugin.pullrequest.ui.list

import com.intellij.collaboration.messages.CollaborationToolsBundle
import com.intellij.collaboration.ui.CollaborationToolsUIUtil
import com.intellij.collaboration.ui.codereview.list.ReviewListUtil
import com.intellij.collaboration.ui.codereview.list.error.ErrorStatusPanelFactory
import com.intellij.collaboration.ui.codereview.list.error.ErrorStatusPresenter
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.CompositeShortcutSet
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.EDT
import com.intellij.ui.CollectionListModel
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollableContentBorder
import com.intellij.ui.Side
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.panels.Wrapper
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.SingleComponentCenteringLayout
import com.intellij.util.ui.StatusText
import de.hendrikfrahmann.giteaplugin.api.isGiteaUnauthorized
import de.hendrikfrahmann.giteaplugin.pullrequest.action.GiteaPullRequestsActionKeys
import de.hendrikfrahmann.giteaplugin.pullrequest.action.GiteaPullRequestsActionPlaces
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestShort
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.filters.GiteaFiltersPanelFactory
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.filters.GiteaPullRequestsFiltersValue
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/** Filters on top, then the lazily paged list; an error panel replaces the list while loading fails. */
object GiteaPullRequestsPanelFactory {
  const val LIST_ACTIONS_GROUP: String = "Gitea.PullRequest.List.Actions"
  const val SHOW_ACTION: String = "Gitea.PullRequest.Show"

  /** [logIn]: re-login for the connection's account, `null` hides the action. */
  fun create(scope: CoroutineScope, listVm: GiteaPullRequestsListViewModel, logIn: (() -> Unit)?): JComponent {
    val listModel = CollectionListModel<GiteaPullRequestShort>()
    val list = GiteaPullRequestsListComponentFactory.create(listModel, listVm.avatarIconsProvider, listVm.isSeen)
    scope.launch(Dispatchers.EDT) {
      listVm.items.collect { items -> showItems(list, listModel, items) }
    }
    scope.launch(Dispatchers.EDT) {
      listVm.seenUpdates.collect { list.repaint() }
    }

    val scrollPane = ReviewListUtil.wrapWithLazyVerticalScroll(scope, list, listVm::requestMore)
    val listWrapper = Wrapper(scrollPane)
    val progressStripe = CollaborationToolsUIUtil.wrapWithProgressStripe(scope, listVm.loading, listWrapper)
    ScrollableContentBorder.setup(scrollPane, Side.TOP, progressStripe)

    val content = UiDataProvider.wrapComponent(progressStripe) { sink ->
      sink[GiteaPullRequestsActionKeys.SELECTED] = list.takeIf { it.isShowing }?.selectedValue
      sink[GiteaPullRequestsActionKeys.LIST_VM] = listVm
    }
    ActionUtil.wrap(SHOW_ACTION).registerCustomShortcutSet(CompositeShortcutSet(CommonShortcuts.ENTER, CommonShortcuts.DOUBLE_CLICK_1), list)
    val popupGroup = ActionManager.getInstance().getAction(LIST_ACTIONS_GROUP) as ActionGroup
    PopupHandler.installPopupMenu(list, popupGroup, GiteaPullRequestsActionPlaces.LIST_POPUP)

    val errorPanel = JPanel(SingleComponentCenteringLayout()).apply {
      isOpaque = false
      add(ErrorStatusPanelFactory.create(scope, listVm.error, errorPresenter(listVm, logIn)))
    }
    scope.launch(Dispatchers.EDT) {
      listVm.error.collect { error ->
        listWrapper.setContent(errorPanel.takeIf { error != null } ?: scrollPane)
        listWrapper.repaint()
      }
    }
    scope.launch(Dispatchers.EDT) {
      combine(listVm.loading, listVm.filterVm.searchState, ::Pair).collect { (loading, search) ->
        updateEmptyText(list.emptyText, loading, search, listVm)
      }
    }

    val searchPanel = GiteaFiltersPanelFactory(listVm.filterVm).create(scope).apply {
      border = JBUI.Borders.empty(0, SEARCH_PANEL_INSET)
    }
    return JBUI.Panels.simplePanel(content).addToTop(searchPanel).andTransparent()
  }

  private const val SEARCH_PANEL_INSET = 10

  /** Keeps the selection: appended pages are just added, a list reloaded in place keeps the selected PR by number. */
  private fun showItems(list: JBList<GiteaPullRequestShort>, listModel: CollectionListModel<GiteaPullRequestShort>, items: List<GiteaPullRequestShort>) {
    if (items.size >= listModel.size && items.subList(0, listModel.size) == listModel.items) {
      listModel.add(items.subList(listModel.size, items.size))
      return
    }
    val selected = list.selectedValue?.number
    listModel.replaceAll(items)
    items.indexOfFirst { it.number == selected }.takeIf { it >= 0 }?.let { list.selectedIndex = it }
  }

  private fun updateEmptyText(emptyText: StatusText, loading: Boolean, search: GiteaPullRequestsFiltersValue,
                              listVm: GiteaPullRequestsListViewModel) {
    emptyText.clear()
    when {
      loading -> emptyText.appendText(CollaborationToolsBundle.message("review.list.empty.state.loading"))
      search.filterCount == 0 && search.searchQuery == null ->
        emptyText.appendText(GiteaBundle.message("pull.request.list.empty", listVm.repository))
      else -> emptyText.appendText(GiteaBundle.message("pull.request.list.empty.filtered"))
        .appendSecondaryText(GiteaBundle.message("pull.request.list.clear.filters"), SimpleTextAttributes.LINK_ATTRIBUTES) {
          listVm.filterVm.searchState.value = GiteaPullRequestsFiltersValue.EMPTY
        }
    }
  }

  private fun errorPresenter(listVm: GiteaPullRequestsListViewModel, logIn: (() -> Unit)?): ErrorStatusPresenter<Throwable> =
    ErrorStatusPresenter.simple(
      GiteaBundle.message("pull.request.list.error"),
      descriptionProvider = { it.localizedMessage },
      actionProvider = { error ->
        when {
          error.isGiteaUnauthorized && logIn != null -> action(GiteaBundle.message("pull.request.list.error.login")) { logIn() }
          else -> action(GiteaBundle.message("pull.request.list.error.retry")) { listVm.refresh() }
        }
      },
    )

  private fun action(text: String, perform: () -> Unit): Action = object : AbstractAction(text) {
    override fun actionPerformed(e: ActionEvent?) = perform()
  }
}
