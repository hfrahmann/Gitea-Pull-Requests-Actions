package de.hendrikfrahmann.giteaplugin.pullrequest.ui.list

import com.intellij.collaboration.ui.codereview.avatar.Avatar
import com.intellij.collaboration.ui.codereview.avatar.CodeReviewAvatarUtils
import com.intellij.collaboration.ui.codereview.list.NamedCollection
import com.intellij.collaboration.ui.codereview.list.ReviewListComponentFactory
import com.intellij.collaboration.ui.codereview.list.ReviewListItemPresentation
import com.intellij.collaboration.ui.codereview.list.TagPresentation
import com.intellij.collaboration.ui.codereview.list.UserPresentation
import com.intellij.collaboration.ui.icon.IconsProvider
import com.intellij.openapi.util.NlsSafe
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.JBList
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestShort
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestState
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import icons.CollaborationToolsIcons
import java.util.Date

object GiteaPullRequestsListComponentFactory {
  /** [isSeen] `false` shows the "unseen" dot, see [GiteaPullRequestsListViewModel.isSeen]. */
  fun create(listModel: CollectionListModel<GiteaPullRequestShort>, avatars: IconsProvider<GiteaUserDTO>,
             isSeen: (GiteaPullRequestShort) -> Boolean? = { null }): JBList<GiteaPullRequestShort> =
    ReviewListComponentFactory(listModel).create { presentation(it, avatars, isSeen(it)) }

  fun presentation(pr: GiteaPullRequestShort, avatars: IconsProvider<GiteaUserDTO>, seen: Boolean? = null): ReviewListItemPresentation =
    ReviewListItemPresentation.Simple(
      title = pr.title,
      id = "#${pr.number}",
      createdDate = pr.createdAt ?: pr.updatedAt ?: Date(0),
      author = pr.author?.let { user(it, avatars) },
      tagGroup = NamedCollection.create(GiteaBundle.message("pull.request.list.labels", pr.labels.size),
                                        pr.labels.map { TagPresentation.Simple(it.name, it.color?.let(::color)) }),
      mergeableStatus = ReviewListItemPresentation.Status(CollaborationToolsIcons.Review.NonMergeable, GiteaBundle.message("pull.request.list.conflicts"))
        .takeIf { pr.mergeable == false },
      buildStatus = null,
      state = stateText(pr),
      userGroup1 = NamedCollection.create(GiteaBundle.message("pull.request.list.assignees", pr.assignees.size),
                                          pr.assignees.map { user(it, avatars) }),
      userGroup2 = pr.reviewers?.let { reviewers ->
        NamedCollection.create(GiteaBundle.message("pull.request.list.reviewers", reviewers.size), reviewers.map { user(it, avatars) })
      },
      // always shown, like GitHub
      commentsCounter = ReviewListItemPresentation.CommentsCounter(pr.comments.toInt(), GiteaBundle.message("pull.request.list.comments", pr.comments)),
      seen = seen,
    )

  /** `null` for open non-draft PRs, like GitHub/GitLab. */
  private fun stateText(pr: GiteaPullRequestShort): @NlsSafe String? = when {
    pr.state == GiteaPullRequestState.MERGED -> GiteaBundle.message("pull.request.state.merged")
    pr.state == GiteaPullRequestState.CLOSED -> GiteaBundle.message("pull.request.state.closed")
    pr.isDraft -> GiteaBundle.message("pull.request.state.draft")
    else -> null
  }

  private fun user(user: GiteaUserDTO, avatars: IconsProvider<GiteaUserDTO>): UserPresentation =
    UserPresentation.Simple(user.login, user.fullName?.takeIf { it.isNotBlank() },
                            avatars.getIcon(user, CodeReviewAvatarUtils.expectedIconHeight(Avatar.Sizes.OUTLINED)))

  private fun color(hex: String) = runCatching { ColorUtil.fromHex(hex) }.getOrNull()
}
