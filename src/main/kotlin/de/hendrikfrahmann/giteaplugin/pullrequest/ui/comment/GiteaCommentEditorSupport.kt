package de.hendrikfrahmann.giteaplugin.pullrequest.ui.comment

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.collaboration.ui.codereview.avatar.Avatar
import com.intellij.collaboration.ui.icon.IconsProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.patterns.StandardPatterns
import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaIssueDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.api.request.getIssue
import de.hendrikfrahmann.giteaplugin.api.request.loadAssignees
import de.hendrikfrahmann.giteaplugin.api.request.searchIssues
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val LOG = logger<GiteaCommentEditorSupport>()

/**
 * What a comment editor of one PR needs beyond plain text: `@user` and `#123` completion (no image upload: the
 * GitHub/GitLab plugins don't have it either). Put on the editor under [KEY]; [GiteaCommentCompletionContributor] only
 * acts on editors that have it.
 */
class GiteaCommentEditorSupport(
  val project: Project,
  private val api: GiteaApi,
  private val repository: GiteaRepositoryPath,
  private val author: GiteaUserDTO?,
  val avatarIconsProvider: IconsProvider<GiteaUserDTO>,
) {
  private val usersMutex = Mutex()
  private var users: List<GiteaUserDTO>? = null

  /** PR author first, then everybody with write access (Gitea has no participants endpoint). */
  suspend fun users(): List<GiteaUserDTO> = usersMutex.withLock {
    users ?: (listOfNotNull(author) + api.loadAssignees(repository)).distinctBy { it.login }.also { users = it }
  }

  /** `#` completion: a number is looked up directly, other text goes to the issue search. */
  suspend fun issues(text: String): List<GiteaIssueDTO> {
    text.toLongOrNull()?.let { number ->
      return listOfNotNull(runCatching { api.getIssue(repository, number) }.getOrNull())
    }
    return api.searchIssues(repository, text)
  }

  companion object {
    val KEY: Key<GiteaCommentEditorSupport> = Key.create("Gitea.Comment.Editor.Support")
  }
}

/** `@login` and `#number` completion in Gitea comment editors. */
class GiteaCommentCompletionContributor : CompletionContributor(), DumbAware {
  override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
    val support = parameters.editor.getUserData(GiteaCommentEditorSupport.KEY) ?: return
    val text = parameters.position.text.substringBefore(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED).takeLastWhile { !it.isWhitespace() }
    when {
      text.startsWith("@") -> completeUsers(support, text, result)
      text.startsWith("#") -> completeIssues(support, text, result)
    }
  }

  private fun completeUsers(support: GiteaCommentEditorSupport, text: String, result: CompletionResultSet) {
    val users = try {
      runBlockingCancellable { support.users() }
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      LOG.info("Could not load users for completion", e)
      return
    }
    val custom = result.withPrefixMatcher(text).caseInsensitive()
    custom.addAllElements(users.mapIndexed { index, user ->
      val element = LookupElementBuilder.create(user, "@${user.login}")
        .withIcon(support.avatarIconsProvider.getIcon(user, Avatar.Sizes.SMALL))
        .withTailText(user.fullName?.takeIf { it.isNotBlank() }?.let { " $it" }, true)
        .withInsertHandler { context, _ -> insertSpace(context.editor, context.selectionEndOffset) }
        .let { b -> user.fullName?.takeIf { it.isNotBlank() }?.let { b.withLookupString("@$it") } ?: b }
      PrioritizedLookupElement.withPriority(element, when {
        index == 0 -> 10.0
        else -> 1.0
      })
    })
  }

  private fun completeIssues(support: GiteaCommentEditorSupport, text: String, result: CompletionResultSet) {
    val query = text.removePrefix("#")
    result.restartCompletionOnPrefixChange(StandardPatterns.string().startsWith("#"))
    val issues = try {
      runBlockingCancellable { support.issues(query) }
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      LOG.info("Could not search issues for completion", e)
      return
    }
    // the server already matched the text (title/body); keep all its hits
    val custom = result.withPrefixMatcher(StandardPrefixAll(text))
    custom.addAllElements(issues.map { issue ->
      val isPull = issue.pullRequest != null
      LookupElementBuilder.create(issue, "#${issue.number}")
        .withLookupString("#${issue.title}")
        .withIcon(when {
          isPull -> AllIcons.Vcs.Merge
          else -> AllIcons.General.Information
        })
        .withTailText(" ${issue.title}", true)
        .withTypeText(GiteaBundle.message(when {
          isPull -> "comment.completion.pull.request"
          else -> "comment.completion.issue"
        }))
        .withInsertHandler { context, _ -> insertSpace(context.editor, context.selectionEndOffset) }
    })
  }

  private fun insertSpace(editor: Editor, offset: Int) {
    val chars = editor.document.charsSequence
    if (offset == chars.length || chars[offset] != ' ') {
      editor.document.insertString(offset, " ")
      editor.caretModel.moveToOffset(offset + 1)
    }
  }

  /** Accepts every item for a non-empty `#` prefix (the server did the matching). */
  private class StandardPrefixAll(prefix: String) : com.intellij.codeInsight.completion.PrefixMatcher(prefix) {
    override fun prefixMatches(name: String): Boolean = true
    override fun cloneWithPrefix(prefix: String): com.intellij.codeInsight.completion.PrefixMatcher = StandardPrefixAll(prefix)
  }
}
