package de.hendrikfrahmann.giteaplugin.pullrequest.ui.timeline

import com.intellij.collaboration.ui.CollaborationToolsUIUtil
import com.intellij.collaboration.ui.SimpleHtmlPane
import com.intellij.collaboration.ui.VerticalListPanel
import com.intellij.collaboration.ui.codereview.CodeReviewChatItemUIUtil
import com.intellij.collaboration.ui.codereview.CodeReviewChatItemUIUtil.ComponentType
import com.intellij.collaboration.ui.codereview.CodeReviewTimelineUIUtil
import com.intellij.collaboration.ui.codereview.comment.CommentInputActionsComponentFactory
import com.intellij.collaboration.ui.codereview.details.CodeReviewDetailsTitleComponentFactory
import com.intellij.collaboration.ui.codereview.list.error.ErrorStatusPanelFactory
import com.intellij.collaboration.ui.codereview.list.error.ErrorStatusPresenter
import com.intellij.collaboration.ui.codereview.timeline.CollapsibleTimelineItemViewModel
import com.intellij.collaboration.ui.codereview.timeline.StatusMessageComponentFactory
import com.intellij.collaboration.ui.codereview.timeline.StatusMessageType
import com.intellij.collaboration.ui.codereview.timeline.TimelineDiffComponentFactory
import com.intellij.collaboration.ui.setHtmlBody
import com.intellij.diff.util.LineRange
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diff.impl.patch.PatchHunk
import com.intellij.openapi.diff.impl.patch.PatchHunkUtil
import com.intellij.openapi.diff.impl.patch.PatchReader
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.Wrapper
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.hendrikfrahmann.giteaplugin.api.request.GiteaReactionTarget
import de.hendrikfrahmann.giteaplugin.authentication.ui.GiteaAnonymousLoginLink
import de.hendrikfrahmann.giteaplugin.pullrequest.action.GiteaPullRequestsActionKeys
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaReviewThread
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaTimelineItem
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaTimelineItem.EventKind
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.comment.GiteaCommentComponents
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.GiteaPullRequestDetailsComponentFactory
import de.hendrikfrahmann.giteaplugin.ui.action.GiteaWebUrls
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import de.hendrikfrahmann.giteaplugin.util.childScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import kotlin.time.Duration.Companion.seconds

private val LOG = logger<GiteaPullRequestTimelineComponentFactory>()

/**
 * The timeline editor (like GitLab's/GitHub's): title and metadata, the description, comments, reviews with their
 * inline threads (diff snippet, reply, resolve), events and a new comment field at the bottom.
 */
object GiteaPullRequestTimelineComponentFactory {
  const val POPUP_GROUP: String = "Gitea.PullRequest.Timeline.Popup"
  const val ERROR_POPUP_GROUP: String = "Gitea.PullRequest.Timeline.Error.Popup"
  const val POPUP_PLACE: String = "Gitea.PullRequest.Timeline.Popup"

  /** Lines of context above the commented line in a thread's diff snippet (like the platform's timelines). */
  private const val DIFF_CONTEXT_LINES = 3

  /** How long the first show waits for the markdown of the timeline. */
  private val PRERENDER_TIMEOUT = 10.seconds

  /** Events of one user closer than this are shown as one entry (GitHub's threshold). */
  private const val EVENT_MERGE_MS = 120_000L

  private const val SMALL_GAP = 4
  private const val REACTIONS_GAP = 6

  private val itemType = ComponentType.FULL
  private val threadType = ComponentType.COMPACT

  /** Timeline texts are limited to the platform's reading width, like in the GitHub and GitLab plugins. */
  private val contentWidth: Int? = CodeReviewChatItemUIUtil.TEXT_CONTENT_WIDTH

  /**
   * A spinner until the timeline is loaded and its markdown rendered (at most [PRERENDER_TIMEOUT]) – otherwise the raw
   * text flashes up until Gitea answers.
   */
  fun create(cs: CoroutineScope, vm: GiteaPullRequestTimelineViewModel): JComponent {
    val content = Wrapper(GiteaPullRequestDetailsComponentFactory.createLoadingSpinner()).apply {
      background = JBColor.lazy { EditorColorsManager.getInstance().globalScheme.defaultBackground }
    }
    cs.launch(Dispatchers.EDT) {
      withTimeoutOrNull(PRERENDER_TIMEOUT) {
        val items = vm.items.filterNotNull().first().getOrNull().orEmpty()
        val description = vm.detailsVm.snapshot.value.pullRequest.body.orEmpty()
        GiteaCommentComponents.prerender(vm.commentContext, listOf(description) + items.flatMap(::bodies))
      }
      content.setContent(createTimeline(cs, vm))
      content.revalidate()
      content.repaint()
    }
    return UiDataProvider.wrapComponent(content) { sink ->
      sink[GiteaPullRequestsActionKeys.DETAILS_VM] = vm.detailsVm
      sink[GiteaPullRequestTimelineViewModel.DATA_KEY] = vm
    }
  }

  private fun bodies(item: GiteaTimelineItem): List<String> = when (item) {
    is GiteaTimelineItem.Comment -> listOf(item.body)
    is GiteaTimelineItem.Review -> listOf(item.body) + item.threads.flatMap { thread -> thread.comments.map { it.body } }
    is GiteaTimelineItem.Event -> emptyList()
  }

  private fun createTimeline(cs: CoroutineScope, vm: GiteaPullRequestTimelineViewModel): JComponent {
    val detailsVm = vm.detailsVm
    val popupGroup = ActionManager.getInstance().getAction(POPUP_GROUP) as ActionGroup
    val title = CodeReviewDetailsTitleComponentFactory.create(cs, detailsVm, GiteaBundle.message("pull.request.open.in.browser"), popupGroup,
                                                              htmlPaneFactory = { SimpleHtmlPane() })
    // full width like the comment field below, so the state badge sits at the right edge
    val header = Wrapper(title).apply {
      isOpaque = false
      border = JBUI.Borders.empty(CodeReviewTimelineUIUtil.HEADER_VERT_PADDING, CodeReviewTimelineUIUtil.ITEM_HOR_PADDING)
    }

    val panel = VerticalListPanel(0).apply {
      add(header)
      add(createDescription(cs, vm))
      add(createItems(cs, vm))
      add(createNewCommentField(cs, vm))
    }
    PopupHandler.installPopupMenu(panel, popupGroup, POPUP_PLACE)

    val scroll = ScrollPaneFactory.createScrollPane(panel, true).apply {
      horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
      viewport.isOpaque = false
      background = JBColor.lazy { EditorColorsManager.getInstance().globalScheme.defaultBackground }
    }
    return CollaborationToolsUIUtil.wrapWithProgressStripe(cs, vm.isLoading, scroll)
  }

  fun createError(cs: CoroutineScope, error: Throwable, retry: () -> Unit): JComponent {
    val presenter = ErrorStatusPresenter.simple<Throwable>(
      GiteaBundle.message("pull.request.timeline.error"),
      descriptionProvider = { it.localizedMessage },
      actionProvider = { swingAction(GiteaBundle.message("pull.request.list.error.retry"), retry) },
    )
    return ErrorStatusPanelFactory.create(cs, flowOf(error), presenter).also {
      CollaborationToolsUIUtil.moveToCenter(it)
      PopupHandler.installPopupMenu(it, ActionManager.getInstance().getAction(ERROR_POPUP_GROUP) as ActionGroup, POPUP_PLACE)
    }
  }

  /** The PR author's description as the first item; rebuilt when the description changes. */
  private fun createDescription(cs: CoroutineScope, vm: GiteaPullRequestTimelineViewModel): JComponent {
    val wrapper = Wrapper()
    val ctx = vm.commentContext
    cs.launch(Dispatchers.EDT) {
      var itemCs: CoroutineScope? = null
      vm.detailsVm.snapshot.map { it.pullRequest }.map { Triple(it.user, it.createdAt, it.body.orEmpty()) }.distinctUntilChanged()
        .collect { (author, date, body) ->
          itemCs?.cancel()
          val scope = cs.childScope().also { itemCs = it }
          val content = when {
            body.isBlank() -> JBLabel(GiteaBundle.message("pull.request.details.no.description")).apply {
              foreground = UIUtil.getContextHelpForeground()
            }
            else -> GiteaCommentComponents.createBodyPane(scope, ctx, body)
          }
          val reactions = ctx.reactionsModel(scope, GiteaReactionTarget.Issue(vm.number))
          val withReactions = VerticalListPanel(JBUI.scale(REACTIONS_GAP)).apply {
            add(content)
            add(GiteaCommentComponents.createReactionsRow(scope, ctx, reactions))
          }
          wrapper.setContent(CodeReviewChatItemUIUtil.build(itemType, { size -> ctx.avatarIconsProvider.getIcon(author, size) }, withReactions) {
            maxContentWidth = contentWidth
            val title = CodeReviewTimelineUIUtil.createTitleTextPane(author?.login.orEmpty(), null, date)
            if (ctx.canWrite) {
              withHeader(title, GiteaCommentComponents.createReactionPickerButton(scope, ctx, reactions))
            }
            else {
              withHeader(title)
            }
          })
          wrapper.revalidate()
          wrapper.repaint()
        }
    }
    return wrapper.apply { isOpaque = false }
  }

  /**
   * The items, then a spinner while loading or the error. Components are kept for unchanged items (open reply or edit
   * fields survive reloads); changed items get a new component.
   */
  private fun createItems(cs: CoroutineScope, vm: GiteaPullRequestTimelineViewModel): JComponent {
    val itemsPanel = VerticalListPanel(0)
    val status = Wrapper()
    val cache = LinkedHashMap<List<GiteaTimelineItem>, Pair<CoroutineScope, JComponent>>()

    cs.launch(Dispatchers.EDT) {
      combine(vm.items, vm.showEvents, ::Pair).collect { (result, showEvents) ->
        when {
          result == null -> status.setContent(GiteaPullRequestDetailsComponentFactory.createLoadingSpinner().apply {
            border = JBUI.Borders.empty(itemType.paddingInsets)
          })
          result.isFailure -> status.setContent(createError(cs, checkNotNull(result.exceptionOrNull())) { vm.refresh() })
          else -> {
            status.setContent(null)
            val groups = group(result.getOrThrow().filter { showEvents || !isEvent(it) })
            // new or edited comments: rendered before they're shown, like on the first show
            withTimeoutOrNull(PRERENDER_TIMEOUT) { GiteaCommentComponents.prerender(vm.commentContext, groups.flatten().flatMap(::bodies)) }
            showGroups(cs, vm, itemsPanel, cache, groups)
          }
        }
        itemsPanel.revalidate()
        itemsPanel.repaint()
        status.revalidate()
        status.repaint()
      }
    }
    return VerticalListPanel(0).apply {
      add(itemsPanel)
      add(status)
    }
  }

  /** Reuses the cached components of unchanged groups; components of groups that are gone are disposed of. */
  private fun showGroups(cs: CoroutineScope, vm: GiteaPullRequestTimelineViewModel, itemsPanel: JPanel,
                         cache: MutableMap<List<GiteaTimelineItem>, Pair<CoroutineScope, JComponent>>, groups: List<List<GiteaTimelineItem>>) {
    val keep = groups.toSet()
    cache.keys.filter { it !in keep }.forEach { cache.remove(it)?.first?.cancel() }
    itemsPanel.removeAll()
    for (group in groups) {
      val (_, component) = cache.getOrPut(group) {
        val itemCs = cs.childScope()
        itemCs to createGroup(itemCs, vm, group)
      }
      itemsPanel.add(component)
    }
  }

  /** Hidden by "Show Events": system events and reviews without body, threads or verdict. */
  private fun isEvent(item: GiteaTimelineItem): Boolean = when (item) {
    is GiteaTimelineItem.Event -> true
    is GiteaTimelineItem.Review -> item.isBare && item.state != "APPROVED" && item.state != "REQUEST_CHANGES"
    is GiteaTimelineItem.Comment -> false
  }

  /** Events of one user within [EVENT_MERGE_MS] of each other become one entry (like GitHub); everything else stays alone. */
  internal fun group(items: List<GiteaTimelineItem>): List<List<GiteaTimelineItem>> {
    val groups = mutableListOf<MutableList<GiteaTimelineItem>>()
    for (item in items) {
      val last = groups.lastOrNull()?.last()
      if (last != null && isSameEventBurst(last, item)) {
        groups.last() += item
      }
      else {
        groups += mutableListOf(item)
      }
    }
    return groups
  }

  private fun isSameEventBurst(previous: GiteaTimelineItem, item: GiteaTimelineItem): Boolean {
    if (previous !is GiteaTimelineItem.Event || item !is GiteaTimelineItem.Event || previous.actor?.login != item.actor?.login) {
      return false
    }
    val previousDate = previous.date ?: return false
    val date = item.date ?: return false
    return date.time - previousDate.time <= EVENT_MERGE_MS
  }

  private fun createGroup(cs: CoroutineScope, vm: GiteaPullRequestTimelineViewModel, group: List<GiteaTimelineItem>): JComponent =
    when (group.size) {
      1 -> createItem(cs, vm, group.single())
      else -> createEvents(vm, group.filterIsInstance<GiteaTimelineItem.Event>())
    }

  private fun createItem(cs: CoroutineScope, vm: GiteaPullRequestTimelineViewModel, item: GiteaTimelineItem): JComponent {
    val ctx = vm.commentContext
    return when (item) {
      is GiteaTimelineItem.Comment -> {
        val own = ctx.isOwn(item.actor)
        GiteaCommentComponents.createComment(cs, ctx, itemType, item.actor, item.body, item.date,
                                             onEdit = editAction(vm, item.id).takeIf { own },
                                             onDelete = deleteAction(vm, item.id).takeIf { own },
                                             reactions = GiteaReactionTarget.Comment(item.id), maxContentWidth = contentWidth)
      }
      is GiteaTimelineItem.Review -> when {
        item.isBare -> createEvent(vm, item, GiteaTimelineTexts.reviewVerdict(item), GiteaTimelineTexts.reviewMessageType(item))
        else -> createReview(cs, vm, item)
      }
      is GiteaTimelineItem.Event -> createEvents(vm, listOf(item))
    }
  }

  private fun editAction(vm: GiteaPullRequestTimelineViewModel, commentId: Long): suspend (String) -> Unit = { text -> vm.editComment(commentId, text) }

  private fun deleteAction(vm: GiteaPullRequestTimelineViewModel, commentId: Long): suspend () -> Unit = { vm.deleteComment(commentId) }

  private fun createEvent(vm: GiteaPullRequestTimelineViewModel, item: GiteaTimelineItem, html: String, type: StatusMessageType): JComponent =
    createEventItem(vm, item, listOf(type to listOf(html)))

  /** Events of one user: consecutive lines of the same kind share one bar, pushes (with their commit list) get their own. */
  private fun createEvents(vm: GiteaPullRequestTimelineViewModel, events: List<GiteaTimelineItem.Event>): JComponent {
    val repoWeb = vm.commentContext.connection.repo.repository.getWebURI()
    val blocks = mutableListOf<Pair<StatusMessageType, MutableList<String>>>()
    var lastWasPush = false
    for (event in events) {
      val type = GiteaTimelineTexts.eventMessageType(event.kind)
      val html = GiteaTimelineTexts.eventHtml(event) { sha -> GiteaWebUrls.commit(repoWeb, sha).toString() }
      val isPush = event.kind is EventKind.Pushed
      if (!isPush && !lastWasPush && blocks.lastOrNull()?.first == type) {
        blocks.last().second += html
      }
      else {
        blocks += type to mutableListOf(html)
      }
      lastWasPush = isPush
    }
    return createEventItem(vm, events.first(), blocks)
  }

  private fun createEventItem(vm: GiteaPullRequestTimelineViewModel, first: GiteaTimelineItem,
                              blocks: List<Pair<StatusMessageType, List<String>>>): JComponent {
    val content = VerticalListPanel(JBUI.scale(CodeReviewTimelineUIUtil.VERTICAL_GAP)).apply {
      for ((type, lines) in blocks) {
        add(StatusMessageComponentFactory.create(SimpleHtmlPane().apply { setHtmlBody(GiteaTimelineTexts.eventLinesHtml(lines)) }, type))
      }
    }
    return CodeReviewChatItemUIUtil.build(itemType, { size -> vm.commentContext.avatarIconsProvider.getIcon(first.actor, size) }, content) {
      maxContentWidth = contentWidth
      withHeader(CodeReviewTimelineUIUtil.createTitleTextPane(first.actor?.login.orEmpty(), null, first.date))
    }
  }

  private fun createReview(cs: CoroutineScope, vm: GiteaPullRequestTimelineViewModel, item: GiteaTimelineItem.Review): JComponent {
    val ctx = vm.commentContext
    // the review text is a timeline comment of its own: reactions on its id (verified on 28.1); none without text
    val reactions = item.body.takeIf { it.isNotBlank() }?.let { ctx.reactionsModel(cs, GiteaReactionTarget.Comment(item.id)) }
    // edited and deleted like a comment, on the same id. Verified on 28.1: deleting removes this timeline entry (the
    // review and its verdict stay in /reviews), and the inline threads hang on it – so only text-only reviews can be deleted
    val own = ctx.isOwn(item.actor)
    val editable = item.body.takeIf { it.isNotBlank() }?.let { body ->
      GiteaCommentComponents.createEditableBody(cs, ctx, itemType, body,
                                                onEdit = editAction(vm, item.id).takeIf { own },
                                                onDelete = deleteAction(vm, item.id).takeIf { own && item.threads.isEmpty() })
    }
    val content = VerticalListPanel(JBUI.scale(CodeReviewTimelineUIUtil.VERTICAL_GAP * 2)).apply {
      add(StatusMessageComponentFactory.create(SimpleHtmlPane(addBrowserListener = false).apply { setHtmlBody(GiteaTimelineTexts.reviewVerdict(item)) },
                                               GiteaTimelineTexts.reviewMessageType(item)))
      if (editable != null) {
        add(editable.component)
      }
      reactions?.let { add(GiteaCommentComponents.createReactionsRow(cs, ctx, it)) }
      for (thread in item.threads) {
        add(createThread(cs, vm, thread))
      }
    }
    return CodeReviewChatItemUIUtil.build(itemType, { size -> ctx.avatarIconsProvider.getIcon(item.actor, size) }, content) {
      maxContentWidth = contentWidth
      val title = CodeReviewTimelineUIUtil.createTitleTextPane(item.actor?.login.orEmpty(), null, item.date)
      val buttons = editable?.buttons.orEmpty() + listOfNotNull(reactions?.takeIf { ctx.canWrite }?.let {
        GiteaCommentComponents.createReactionPickerButton(cs, ctx, it)
      })
      if (buttons.isEmpty()) {
        withHeader(title)
      }
      else {
        withHeader(title, JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(SMALL_GAP), 0)).apply {
          isOpaque = false
          buttons.forEach(::add)
        })
      }
    }
  }

  /** File header (click = show in the review diff), diff snippet and the thread's comments; resolved threads start collapsed. */
  private fun createThread(cs: CoroutineScope, vm: GiteaPullRequestTimelineViewModel, thread: GiteaReviewThread): JComponent {
    val collapseVm = object : CollapsibleTimelineItemViewModel {
      val state = MutableStateFlow(thread.isResolved)
      override val collapsible: Flow<Boolean> = flowOf(true)
      override val collapsed: StateFlow<Boolean> = state
      override fun setCollapsed(collapsed: Boolean) {
        state.value = collapsed
      }
    }
    val showInDiff = flowOf(ActionListener { vm.showThread(thread) })
    val diff = TimelineDiffComponentFactory.createDiffWithHeader(cs, collapseVm, thread.path, showInDiff) {
      val hunk = hunkWithAnchor(thread)
      if (hunk != null) {
        TimelineDiffComponentFactory.createDiffComponentIn(this, vm.commentContext.project, EditorFactory.getInstance(), hunk.first, hunk.second)
      }
      else {
        JBLabel(GiteaBundle.message("pull.request.timeline.thread.no.diff")).apply {
          foreground = UIUtil.getContextHelpForeground()
          border = JBUI.Borders.empty(SMALL_GAP, 2 * SMALL_GAP)
        }
      }
    }
    val body = GiteaCommentComponents.createThreadBody(cs, vm.commentContext, vm.reviewData, thread, threadType, contentWidth)
    cs.launch(Dispatchers.EDT) { collapseVm.collapsed.collect { body.isVisible = !it } }
    return VerticalListPanel(JBUI.scale(SMALL_GAP)).apply {
      add(diff)
      add(body)
    }
  }

  /**
   * Gitea's `diff_hunk` ends at the commented line (like GitHub's), so the anchor is the last line; only a few lines of
   * context above it are shown. `null` if there is no hunk or it can't be parsed.
   */
  internal fun hunkWithAnchor(thread: GiteaReviewThread): Pair<PatchHunk, LineRange>? {
    val text = thread.diffHunk?.takeIf { it.isNotBlank() } ?: return null
    val hunk = try {
      PatchReader(PatchHunkUtil.createPatchFromHunk(thread.path, text)).readTextPatches().firstOrNull()?.hunks?.firstOrNull()
    }
    catch (e: Exception) {
      LOG.info("Could not parse the diff hunk of thread ${thread.id}", e)
      null
    } ?: return null
    if (hunk.lines.isEmpty()) {
      return null
    }
    val truncated = PatchHunkUtil.truncateHunkBefore(hunk, hunk.lines.lastIndex - DIFF_CONTEXT_LINES)
    return truncated to LineRange(truncated.lines.lastIndex, truncated.lines.size)
  }

  private fun createNewCommentField(cs: CoroutineScope, vm: GiteaPullRequestTimelineViewModel): JComponent {
    val ctx = vm.commentContext
    if (!ctx.canWrite) {
      // anonymous (read-only) connection
      return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false
        border = JBUI.Borders.empty(itemType.inputPaddingInsets)
        add(GiteaAnonymousLoginLink.create(ctx.project, ctx.connection.repo.repository.serverPath,
                                           GiteaBundle.message("anonymous.login.to.comment", ctx.connection.repo.repository.serverPath.host)))
      }
    }
    val textVm = GiteaCommentComponents.SubmittableText(vm.commentContext.project, cs, "") { vm.addComment(it) }
    val field = GiteaCommentComponents.createTextField(cs, vm.commentContext, itemType, textVm, GiteaBundle.message("pull.request.timeline.comment"),
                                                       onCancel = null,
                                                       submitHint = GiteaBundle.message("pull.request.timeline.comment.hint",
                                                                                        CommentInputActionsComponentFactory.submitShortcutText))
    return JPanel(BorderLayout()).apply {
      isOpaque = false
      border = JBUI.Borders.empty(itemType.inputPaddingInsets)
      add(field)
    }
  }

  private fun swingAction(text: String, perform: () -> Unit) = object : AbstractAction(text) {
    override fun actionPerformed(e: ActionEvent?) = perform()
  }
}
