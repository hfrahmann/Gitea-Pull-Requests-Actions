package de.hendrikfrahmann.giteaplugin.pullrequest.ui.comment

import com.intellij.collaboration.ui.SimpleHtmlPane
import com.intellij.collaboration.ui.VerticalListPanel
import com.intellij.collaboration.ui.codereview.CodeReviewChatItemUIUtil
import com.intellij.collaboration.ui.codereview.CodeReviewTimelineUIUtil
import com.intellij.collaboration.ui.codereview.comment.CodeReviewCommentTextFieldFactory
import com.intellij.collaboration.ui.codereview.comment.CodeReviewCommentUIUtil
import com.intellij.collaboration.ui.codereview.comment.CodeReviewSubmittableTextViewModelBase
import com.intellij.collaboration.ui.codereview.comment.CommentInputActionsComponentFactory
import com.intellij.collaboration.ui.codereview.reactions.CodeReviewReactionComponent
import com.intellij.collaboration.ui.codereview.reactions.CodeReviewReactionPillPresentation
import com.intellij.collaboration.ui.codereview.reactions.CodeReviewReactionsUIUtil
import com.intellij.collaboration.ui.codereview.timeline.comment.CommentTextFieldFactory
import com.intellij.collaboration.ui.icon.IconsProvider
import com.intellij.collaboration.ui.setHtmlBody
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.InplaceButton
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaReactionDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.api.request.GiteaReactionTarget
import de.hendrikfrahmann.giteaplugin.api.request.loadAllowedReactions
import de.hendrikfrahmann.giteaplugin.notification.GiteaNotificationIds
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestReviewData
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaReactionsModel
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaReviewComment
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaReviewThread
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import de.hendrikfrahmann.giteaplugin.util.childScope
import icons.CollaborationToolsIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.event.HyperlinkEvent

/** What comment components need from where they are shown (review diff, timeline) – one per PR. */
class GiteaCommentContext(
  val project: Project,
  val connection: GiteaProjectConnection,
  val avatarIconsProvider: IconsProvider<GiteaUserDTO>,
  /** Completion and image upload in the text fields. */
  val editorSupport: GiteaCommentEditorSupport,
) {
  /** `null` without a login (anonymous connection). */
  val currentUser: GiteaUserDTO? get() = connection.currentUser

  /** Comment, reply, edit, resolve and react – not for an anonymous connection. */
  val canWrite: Boolean get() = !connection.isAnonymous
  val imageLoader: GiteaHtmlImageLoader = GiteaHtmlImageLoader(connection)

  /** The server's `allowed_reactions`, loaded once (Gitea's defaults if that fails). */
  val allowedReactions: Deferred<List<String>> by lazy {
    connection.scope.async {
      try {
        connection.api.loadAllowedReactions().ifEmpty { GiteaReactionsModel.DEFAULT_REACTIONS }
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (_: Exception) {
        GiteaReactionsModel.DEFAULT_REACTIONS
      }
    }
  }

  fun reactionsModel(cs: CoroutineScope, target: GiteaReactionTarget): GiteaReactionsModel =
    GiteaReactionsModel(cs, connection.api, connection.repo.repository.path, target, currentUser?.login)

  fun isOwn(user: GiteaUserDTO?): Boolean = currentUser != null && user?.login.equals(currentUser?.login, ignoreCase = true)
}

/** Swing parts of comments and threads, built from the platform's code review UI; shared by the review diff and the timeline. */
object GiteaCommentComponents {
  /**
   * Gitea's markdown rendering (images loaded with credentials, see [GiteaHtmlImageLoader]); escaped plain text until it's
   * rendered, unless [prerender] already did.
   */
  fun createBodyPane(cs: CoroutineScope, ctx: GiteaCommentContext, body: String): JEditorPane {
    val rendered = MarkdownCache.cached(body)
    val pane = SimpleHtmlPane(addBrowserListener = false, customImageLoader = ctx.imageLoader).apply {
      GiteaHtmlLineBreaking.install(this)
      setHtmlBody(rendered ?: plainHtml(body))
      addHyperlinkListener { e ->
        if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
          e.url?.let(BrowserUtil::browse)
        }
      }
    }
    if (rendered == null) {
      cs.launch(Dispatchers.EDT) { MarkdownCache.render(ctx, body)?.let(pane::setHtmlBody) }
    }
    return pane
  }

  /** Renders [bodies] into the cache (a few at a time), so the panes built afterwards show markdown right away. */
  suspend fun prerender(ctx: GiteaCommentContext, bodies: Collection<String>) {
    val permits = Semaphore(PRERENDER_PARALLELISM)
    coroutineScope {
      for (body in bodies.toSet()) {
        if (body.isNotBlank() && MarkdownCache.cached(body) == null) {
          launch { permits.withPermit { MarkdownCache.render(ctx, body) } }
        }
      }
    }
  }

  fun plainHtml(text: String): String = StringUtil.escapeXmlEntities(text).replace("\n", "<br/>")

  /**
   * A comment with author avatar and title; edit/delete buttons when [onEdit]/[onDelete] are given, reactions (picker
   * in the header, pills below the text) for [reactions]. Editing swaps the body for a text field; after saving the
   * saved text is shown until the owner rebuilds the component with fresh data. [maxContentWidth] `null` = full width.
   */
  fun createComment(cs: CoroutineScope, ctx: GiteaCommentContext, type: CodeReviewChatItemUIUtil.ComponentType,
                    author: GiteaUserDTO?, body: String, date: Date?, isPending: Boolean = false,
                    onEdit: (suspend (String) -> Unit)? = null, onDelete: (suspend () -> Unit)? = null,
                    reactions: GiteaReactionTarget? = null,
                    maxContentWidth: Int? = CodeReviewChatItemUIUtil.TEXT_CONTENT_WIDTH): JComponent {
    val editable = createEditableBody(cs, ctx, type, body, onEdit, onDelete)
    val bodyHolder = editable.component

    val actions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(SMALL_GAP), 0)).apply { isOpaque = false }
    if (isPending) {
      actions.add(JBLabel(GiteaBundle.message("review.comment.pending")).apply { foreground = UIUtil.getContextHelpForeground() })
    }
    editable.buttons.forEach(actions::add)
    // pending (draft) comments can't have reactions yet
    val content = when {
      reactions == null || isPending -> bodyHolder
      else -> {
        val model = ctx.reactionsModel(cs, reactions)
        if (ctx.canWrite) {
          actions.add(createReactionPickerButton(cs, ctx, model))
        }
        VerticalListPanel(JBUI.scale(REACTIONS_GAP)).apply {
          add(bodyHolder)
          add(createReactionsRow(cs, ctx, model))
        }
      }
    }
    val title = CodeReviewTimelineUIUtil.createTitleTextPane(author?.login.orEmpty(), null, date)
    return CodeReviewChatItemUIUtil.build(type, { size -> ctx.avatarIconsProvider.getIcon(author, size) }, content) {
      this.maxContentWidth = maxContentWidth
      withHeader(title, actions)
    }
  }

  /** A comment text and the header buttons that edit (swap the text for a text field) or delete it. */
  class EditableBody(val component: JComponent, val buttons: List<JComponent>)

  /**
   * Text of a comment with Edit / Delete buttons for [onEdit] / [onDelete] (none when `null`). After saving, the saved
   * text is shown until the owner rebuilds the component with fresh data.
   */
  fun createEditableBody(cs: CoroutineScope, ctx: GiteaCommentContext, type: CodeReviewChatItemUIUtil.ComponentType, body: String,
                         onEdit: (suspend (String) -> Unit)?, onDelete: (suspend () -> Unit)?): EditableBody {
    val bodyPane = createBodyPane(cs, ctx, body)
    val bodyHolder = verticalPanel().apply { add(bodyPane) }
    val buttons = mutableListOf<JComponent>()
    if (onEdit != null) {
      buttons += CodeReviewCommentUIUtil.createEditButton {
        // not twice: the text field is already open
        if (bodyHolder.getComponent(0) === bodyPane) {
          startEditing(cs, ctx, type, body, bodyHolder, bodyPane, onEdit)
        }
      }
    }
    if (onDelete != null) {
      buttons += CodeReviewCommentUIUtil.createDeleteCommentIconButton { cs.launch { runCatchingUi { onDelete() } } }
    }
    return EditableBody(bodyHolder, buttons)
  }

  /** Swaps the text for a text field; after saving, the saved text is shown (as plain text) until the data reloads. */
  private fun startEditing(cs: CoroutineScope, ctx: GiteaCommentContext, type: CodeReviewChatItemUIUtil.ComponentType, body: String,
                           bodyHolder: JPanel, bodyPane: JEditorPane, onEdit: suspend (String) -> Unit) {
    var saved: String? = null
    val vm = SubmittableText(ctx.project, cs, body) { text ->
      onEdit(text)
      saved = text
    }
    val showBody = { bodyHolder.replaceContent(bodyPane) }
    vm.onSubmitted = {
      saved?.let { bodyPane.setHtmlBody(plainHtml(it)) }
      showBody()
    }
    bodyHolder.replaceContent(createTextField(cs, ctx, type, vm, GiteaBundle.message("review.comment.save"), onCancel = showBody))
    vm.requestFocus()
  }

  /** Smiley button that opens a popup with the server's allowed reactions (like Gitea's web UI). */
  fun createReactionPickerButton(cs: CoroutineScope, ctx: GiteaCommentContext, model: GiteaReactionsModel): JComponent {
    lateinit var button: InplaceButton
    val icon = IconButton(GiteaBundle.message("comment.reaction.add"), CollaborationToolsIcons.AddEmoji, CollaborationToolsIcons.AddEmojiHovered)
    button = InplaceButton(icon, ActionListener {
      cs.launch(Dispatchers.EDT) { showReactionPicker(cs, model, ctx.allowedReactions.await(), button) }
    })
    return button
  }

  private fun showReactionPicker(cs: CoroutineScope, model: GiteaReactionsModel, allowed: List<String>, button: JComponent) {
    lateinit var popup: JBPopup
    val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(SMALL_GAP), 0)).apply {
      isOpaque = false
      border = JBUI.Borders.empty(REACTIONS_GAP)
      for (content in allowed) {
        add(CodeReviewReactionComponent.createPickReactionButton(emojiIcon(content)) {
          popup.closeOk(null)
          cs.launch { runCatchingUi { model.toggle(content) } }
        }.apply { toolTipText = content })
      }
    }
    popup = JBPopupFactory.getInstance().createComponentPopupBuilder(row, null)
      .setRequestFocus(true)
      .setCancelOnClickOutside(true)
      .createPopup()
    popup.show(RelativePoint.getSouthWestOf(button))
  }

  /** Pills "emoji count" per reaction, in the server's order; own reactions highlighted, a click toggles. Hidden while empty. */
  fun createReactionsRow(cs: CoroutineScope, ctx: GiteaCommentContext, model: GiteaReactionsModel): JComponent {
    val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(CodeReviewReactionsUIUtil.HORIZONTAL_GAP), 0)).apply {
      isOpaque = false
      isVisible = false
    }
    cs.launch(Dispatchers.EDT) {
      var rowCs: CoroutineScope? = null
      model.reactions.collect { reactions ->
        rowCs?.cancel()
        val scope = cs.childScope().also { rowCs = it }
        // in the server's order, unknown ones last
        val order = ctx.allowedReactions.await()
        val grouped = reactions.groupBy { it.content }.entries.sortedBy { (content, _) -> order.indexOf(content).takeIf { it >= 0 } ?: Int.MAX_VALUE }
        row.removeAll()
        for ((content, list) in grouped) {
          row.add(CodeReviewReactionComponent.createReactionButtonIn(scope, flowOf(reactionPill(ctx, content, list))) {
            if (ctx.canWrite) {
              cs.launch { runCatchingUi { model.toggle(content) } }
            }
          })
        }
        row.isVisible = grouped.isNotEmpty()
        row.revalidate()
        row.repaint()
      }
    }
    return row
  }

  private fun reactionPill(ctx: GiteaCommentContext, content: String, reactions: List<GiteaReactionDTO>) = object : CodeReviewReactionPillPresentation {
    override val reactionName: String = content
    override val reactors: List<String> = reactions.mapNotNull { it.user?.login }
    override val isOwnReaction: Boolean = reactions.any { ctx.isOwn(it.user) }
    override fun getIcon(size: Int): Icon = CodeReviewReactionsUIUtil.createUnicodeEmojiIcon(GiteaReactionsModel.emoji(content), size)
  }

  private fun emojiIcon(content: String): Icon =
    CodeReviewReactionsUIUtil.createUnicodeEmojiIcon(GiteaReactionsModel.emoji(content), CodeReviewReactionsUIUtil.ICON_SIZE)

  /**
   * The comments of an inline [thread] with Reply / Resolve links; [commentFooter] adds something below a comment (e.g.
   * "Apply Suggestion" in the diff). [maxContentWidth] as in [createComment].
   */
  fun createThreadBody(cs: CoroutineScope, ctx: GiteaCommentContext, reviewData: GiteaPullRequestReviewData, thread: GiteaReviewThread,
                       type: CodeReviewChatItemUIUtil.ComponentType,
                       maxContentWidth: Int? = CodeReviewChatItemUIUtil.TEXT_CONTENT_WIDTH,
                       commentFooter: (GiteaReviewComment) -> JComponent? = { null }): JComponent {
    val content = verticalPanel()
    for (comment in thread.comments) {
      val own = ctx.isOwn(comment.author)
      val onEdit: suspend (String) -> Unit = { text -> reviewData.editComment(comment.id, text) }
      val onDelete: suspend () -> Unit = { reviewData.deleteComment(comment.id) }
      content.add(createComment(cs, ctx, type, comment.author, comment.body, comment.createdAt, comment.isPending,
                                onEdit = onEdit.takeIf { own }, onDelete = onDelete.takeIf { own },
                                reactions = GiteaReactionTarget.Comment(comment.id), maxContentWidth = maxContentWidth))
      commentFooter(comment)?.let(content::add)
    }
    val replyHolder = verticalPanel()
    content.add(threadLinks(cs, ctx, reviewData, thread, type, replyHolder))
    content.add(replyHolder)
    return content
  }

  /** "Reply" and "Resolve"/"Unresolve" (not on drafts, not without a login), and who resolved it. */
  private fun threadLinks(cs: CoroutineScope, ctx: GiteaCommentContext, reviewData: GiteaPullRequestReviewData, thread: GiteaReviewThread,
                          type: CodeReviewChatItemUIUtil.ComponentType, replyHolder: JPanel): JComponent =
    JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(LINK_GAP), 0)).apply {
      isOpaque = false
      border = JBUI.Borders.empty(SMALL_GAP, type.contentLeftShift, SMALL_GAP, 0)
      if (!thread.isPending && ctx.canWrite) {
        add(ActionLink(GiteaBundle.message("review.thread.reply")) {
          // one reply field at a time
          if (replyHolder.componentCount == 0) {
            showReplyField(cs, ctx, reviewData, thread, type, replyHolder)
          }
        })
        val resolveKey = when {
          thread.isResolved -> "review.thread.unresolve"
          else -> "review.thread.resolve"
        }
        add(ActionLink(GiteaBundle.message(resolveKey)) {
          cs.launch { runCatchingUi { reviewData.setResolved(thread, !thread.isResolved) } }
        })
      }
      if (thread.isResolved) {
        add(JBLabel(GiteaBundle.message("review.thread.resolved.by", thread.resolver?.login.orEmpty())).apply {
          foreground = UIUtil.getContextHelpForeground()
        })
      }
    }

  private fun showReplyField(cs: CoroutineScope, ctx: GiteaCommentContext, reviewData: GiteaPullRequestReviewData, thread: GiteaReviewThread,
                             type: CodeReviewChatItemUIUtil.ComponentType, replyHolder: JPanel) {
    val vm = SubmittableText(ctx.project, cs, "") { body -> reviewData.reply(thread, body) }
    val close = { replyHolder.replaceContent(null) }
    vm.onSubmitted = close
    replyHolder.replaceContent(createTextField(cs, ctx, type, vm, GiteaBundle.message("review.thread.reply"), onCancel = close))
    vm.requestFocus()
  }

  private fun verticalPanel(): JPanel = JPanel().apply {
    isOpaque = false
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
  }

  /** Shows only [component] (nothing for `null`) in this panel. */
  private fun JPanel.replaceContent(component: JComponent?) {
    removeAll()
    component?.let(::add)
    revalidate()
    repaint()
  }

  /** Markdown text field with the user's avatar, submit/cancel, plus [secondary]/[additional] actions. */
  fun createTextField(cs: CoroutineScope, ctx: GiteaCommentContext, type: CodeReviewChatItemUIUtil.ComponentType, vm: SubmittableText,
                      submitText: String, onCancel: (() -> Unit)?, secondary: List<Action> = emptyList(),
                      additional: List<Action> = emptyList(), submitHint: String? = null): JComponent {
    val support = ctx.editorSupport
    // disabled while empty or submitting, so neither the button nor the shortcut can send it twice
    val submit = action(submitText) { vm.submitNow() }.apply { isEnabled = false }
    cs.launch(Dispatchers.EDT) {
      combine(vm.text, vm.state) { text, state -> text.isNotBlank() && state?.isInProgress != true }.collect { submit.isEnabled = it }
    }
    val config = CommentInputActionsComponentFactory.Config(
      primaryAction = MutableStateFlow(submit),
      secondaryActions = MutableStateFlow(secondary),
      additionalActions = MutableStateFlow(additional),
      cancelAction = MutableStateFlow(onCancel?.let { action(GiteaBundle.message("review.comment.cancel")) { it() } }),
      submitHint = MutableStateFlow(submitHint ?: GiteaBundle.message("review.comment.submit.hint", CommentInputActionsComponentFactory.submitShortcutText)),
    )
    val user = checkNotNull(ctx.currentUser) { "Comment fields need a login (canWrite)" }
    val icon = CommentTextFieldFactory.IconConfig.of(type, ctx.avatarIconsProvider, user)
    return CodeReviewCommentTextFieldFactory.createIn(cs, vm, config, icon) { it.putUserData(GiteaCommentEditorSupport.KEY, support) }
  }

  fun action(text: String, perform: () -> Unit): Action = object : AbstractAction(text) {
    override fun actionPerformed(e: ActionEvent?) = perform()
  }

  /** Comment/review changes that fail are reported as a notification (the data reloads either way). */
  suspend fun runCatchingUi(block: suspend () -> Unit) {
    try {
      block()
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      NotificationGroupManager.getInstance().getNotificationGroup(GiteaNotificationIds.GROUP)
        .createNotification(GiteaBundle.message("review.change.failed"), e.localizedMessage.orEmpty(), NotificationType.ERROR)
        .setDisplayId(GiteaNotificationIds.REVIEW_CHANGE_FAILED)
        .notify(null)
    }
  }

  /**
   * Text field model; [submitAction] runs on submit, then the field clears and [onSubmitted] runs on the EDT (e.g. to
   * close the editor and show the comment again). Failures stay in the field (shown by the platform) and keep the text.
   */
  class SubmittableText(project: Project, cs: CoroutineScope, initial: String, private val submitAction: suspend (String) -> Unit)
    : CodeReviewSubmittableTextViewModelBase(project, cs, initial) {
    var onSubmitted: () -> Unit = {}

    fun submitNow() = submit { text ->
      if (text.isNotBlank()) {
        submitAction(text)
        this.text.value = ""
        withContext(Dispatchers.EDT) { onSubmitted() }
      }
    }
  }

  private const val PRERENDER_PARALLELISM = 4
  private const val SMALL_GAP = 4
  private const val REACTIONS_GAP = 6
  private const val LINK_GAP = 8

  /** Gitea's markdown rendering of comment bodies (with image size hints), cached per text; `null` if the server can't render it. */
  private object MarkdownCache {
    private val cache = ConcurrentHashMap<String, String>()

    fun cached(text: String): String? = cache[text]

    suspend fun render(ctx: GiteaCommentContext, text: String): String? {
      if (text.isBlank()) {
        return null
      }
      cache[text]?.let { return it }
      return try {
        GiteaHtmlImageLoader.withSizeHints(GiteaMarkdown.render(ctx.project, ctx.connection, text)).also { cache[text] = it }
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (_: Exception) {
        null
      }
    }
  }
}
