package de.hendrikfrahmann.giteaplugin.pullrequest.ui.details

import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer
import com.intellij.openapi.vcs.changes.ui.ChangeDiffRequestChain
import com.intellij.util.concurrency.annotations.RequiresEdt
import git4idea.GitContentRevision

/**
 * Opens PR changes in the platform diff viewer (editor tab or window, per the user's diff settings), with
 * next/previous file navigation. File contents come from the local repository, where the loader fetched the revisions.
 * The review diff with inline comments is [de.hendrikfrahmann.giteaplugin.pullrequest.ui.diff].
 */
object GiteaPullRequestDiffUtil {
  @RequiresEdt
  fun showDiff(project: Project, changes: List<RefComparisonChange>, selectedIndex: Int) {
    if (changes.isEmpty()) {
      return
    }
    val producers = changes.mapNotNull { ChangeDiffRequestProducer.create(project, toVcsChange(project, it)) }
    if (producers.isEmpty()) {
      return
    }
    val chain = ChangeDiffRequestChain(producers, selectedIndex.coerceIn(0, producers.lastIndex))
    DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT)
  }

  fun toVcsChange(project: Project, change: RefComparisonChange): Change {
    val before = change.filePathBefore?.let { GitContentRevision.createRevision(it, change.revisionNumberBefore, project) }
    val after = change.filePathAfter?.let { GitContentRevision.createRevision(it, change.revisionNumberAfter, project) }
    return Change(before, after)
  }
}
