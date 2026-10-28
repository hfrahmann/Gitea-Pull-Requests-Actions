package de.hendrikfrahmann.giteaplugin.pullrequest.ui.toolwindow

import com.intellij.collaboration.ui.toolwindow.ReviewTab
import com.intellij.collaboration.ui.toolwindow.ReviewTabViewModel
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.NlsSafe
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountManager
import de.hendrikfrahmann.giteaplugin.clone.GiteaCloneViewModel
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestDataProvider
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.create.model.GiteaPullRequestCreateViewModel
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import de.hendrikfrahmann.giteaplugin.util.childScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls

/** Tabs next to the PR list. */
sealed interface GiteaReviewTab : ReviewTab {
  data class PullRequest(val number: Long) : GiteaReviewTab {
    override val id: @NonNls String = "PullRequest!$number"
    override val reuseTabOnRequest: Boolean = true
  }

  /** "New Pull Request"; one at a time. */
  data object NewPullRequest : GiteaReviewTab {
    override val id: @NonNls String = "NewPullRequest"
    override val reuseTabOnRequest: Boolean = true
  }

  /** All repositories the accounts can access. */
  data object Repositories : GiteaReviewTab {
    override val id: @NonNls String = "Repositories"
    override val reuseTabOnRequest: Boolean = true
  }
}

sealed interface GiteaReviewTabViewModel : ReviewTabViewModel {
  /** Details of a PR; tab and timeline editor share the PR's [dataProvider] (cached per connection). */
  sealed class PullRequestTab(parentCs: CoroutineScope, val number: Long, val dataProvider: GiteaPullRequestDataProvider,
                              private val cachedTitle: String?) : GiteaReviewTabViewModel, Disposable {
    val cs: CoroutineScope = parentCs.childScope()

    protected val title: String? get() = dataProvider.snapshot.value?.getOrNull()?.pullRequest?.title ?: cachedTitle

    override fun dispose() {
      cs.cancel()
    }
  }

  /** Repositories of all accounts, loaded like "Get from VCS" ([GiteaCloneViewModel]). */
  class Repositories(parentCs: CoroutineScope) : GiteaReviewTabViewModel, Disposable {
    val cs: CoroutineScope = parentCs.childScope()
    val repositoriesVm: GiteaCloneViewModel = GiteaCloneViewModel(cs, GiteaAccountManager.getInstance())

    override val displayName: @Nls String = GiteaBundle.message("repositories.tab")
    override val description: @Nls String = GiteaBundle.message("repositories.tab.description")

    override fun dispose() {
      cs.cancel()
    }
  }

  /** Creating a PR. */
  class NewPullRequest(parentCs: CoroutineScope, createVm: (CoroutineScope) -> GiteaPullRequestCreateViewModel) : GiteaReviewTabViewModel, Disposable {
    val cs: CoroutineScope = parentCs.childScope()
    val createVm: GiteaPullRequestCreateViewModel = createVm(cs)

    override val displayName: @Nls String = GiteaBundle.message("pull.request.create.tab")
    override val description: @Nls String = GiteaBundle.message("pull.request.create.tab.description")

    override fun dispose() {
      cs.cancel()
    }
  }

  class PullRequest(parentCs: CoroutineScope, number: Long, dataProvider: GiteaPullRequestDataProvider, cachedTitle: String?)
    : PullRequestTab(parentCs, number, dataProvider, cachedTitle) {
    override val displayName: @NlsSafe String = "#$number"
    override val description: @NlsSafe String get() = title ?: displayName
  }
}
