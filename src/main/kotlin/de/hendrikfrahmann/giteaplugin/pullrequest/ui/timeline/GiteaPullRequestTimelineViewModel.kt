package de.hendrikfrahmann.giteaplugin.pullrequest.ui.timeline

import com.intellij.openapi.actionSystem.DataKey
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestDataProvider
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaReviewThread
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaTimelineItem
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.comment.GiteaCommentContext
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaPullRequestDetailsViewModel
import de.hendrikfrahmann.giteaplugin.settings.GiteaSettings
import de.hendrikfrahmann.giteaplugin.util.childScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The conversation of one PR (timeline editor): header data from [detailsVm], items from the timeline merged with the
 * reviews' inline threads, and the comment actions.
 */
class GiteaPullRequestTimelineViewModel(
  parentCs: CoroutineScope,
  val dataProvider: GiteaPullRequestDataProvider,
  val detailsVm: GiteaPullRequestDetailsViewModel,
  val commentContext: GiteaCommentContext,
  private val onShowThread: (GiteaReviewThread) -> Unit,
) {
  private val cs = parentCs.childScope()
  private val timelineData = dataProvider.timelineData
  val reviewData = dataProvider.reviewData

  val number: Long get() = dataProvider.number

  /**
   * `null` while loading. Review threads are added once the reviews are loaded; if those fail the timeline is shown
   * without them.
   */
  val items: StateFlow<Result<List<GiteaTimelineItem>>?> = combine(timelineData.state, reviewData.state) { timeline, reviews ->
    when {
      timeline == null || reviews == null -> null
      timeline.isFailure -> Result.failure(checkNotNull(timeline.exceptionOrNull()))
      else -> {
        val loaded = timeline.getOrThrow()
        val state = reviews.getOrNull()
        Result.success(GiteaTimelineItem.build(loaded.events, state?.reviews.orEmpty(), state?.threads.orEmpty(), loaded.commitTitles))
      }
    }
  }.stateIn(cs, SharingStarted.Eagerly, null)

  val isLoading: StateFlow<Boolean> = combine(dataProvider.isLoading, timelineData.isLoading) { a, b -> a || b }
    .stateIn(cs, SharingStarted.Eagerly, false)

  val showEvents: StateFlow<Boolean> = GiteaSettings.getInstance().showTimelineEventsState

  fun refresh() = dataProvider.refresh()

  suspend fun addComment(body: String) = timelineData.addComment(body)
  suspend fun editComment(commentId: Long, body: String) = timelineData.editComment(commentId, body)
  suspend fun deleteComment(commentId: Long) = timelineData.deleteComment(commentId)

  /** Opens the review diff at the thread's file and line. */
  fun showThread(thread: GiteaReviewThread) = onShowThread(thread)

  companion object {
    val DATA_KEY: DataKey<GiteaPullRequestTimelineViewModel> = DataKey.create("Gitea.PullRequest.Timeline.ViewModel")
  }
}
