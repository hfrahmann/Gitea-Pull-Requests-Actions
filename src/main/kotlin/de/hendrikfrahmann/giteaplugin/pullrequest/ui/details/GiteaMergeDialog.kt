package de.hendrikfrahmann.giteaplugin.pullrequest.ui.details

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import de.hendrikfrahmann.giteaplugin.api.request.GiteaMergeStyle
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JList

/**
 * Confirms a merge: style (only the repository's allowed ones), commit title and message (empty = Gitea's default text),
 * "Delete branch after merge" and, while checks are pending, "Merge when checks succeed".
 */
class GiteaMergeDialog(
  project: Project,
  number: Long,
  styles: List<GiteaMergeStyle>,
  initialStyle: GiteaMergeStyle,
  deleteBranch: Boolean,
  offerAutoMerge: Boolean,
) : DialogWrapper(project) {
  private val styleBox = ComboBox(DefaultComboBoxModel(styles.toTypedArray())).apply {
    selectedItem = initialStyle
    renderer = object : ColoredListCellRenderer<GiteaMergeStyle>() {
      override fun customizeCellRenderer(list: JList<out GiteaMergeStyle>, value: GiteaMergeStyle?, index: Int, selected: Boolean, hasFocus: Boolean) {
        append(styleText(value))
      }
    }
  }
  private val titleField = JBTextField().apply { emptyText.text = GiteaBundle.message("pull.request.merge.dialog.title.default") }
  private val messageArea = JBTextArea(5, 60).apply {
    lineWrap = true
    wrapStyleWord = true
    emptyText.text = GiteaBundle.message("pull.request.merge.dialog.message.default")
  }
  private val deleteBranchBox = JBCheckBox(GiteaBundle.message("pull.request.merge.dialog.delete.branch"), deleteBranch)
  private val autoMergeBox = JBCheckBox(GiteaBundle.message("pull.request.merge.dialog.auto.merge"), offerAutoMerge).apply {
    isVisible = offerAutoMerge
  }

  val style: GiteaMergeStyle get() = styleBox.selectedItem as GiteaMergeStyle
  val commitTitle: String? get() = titleField.text.takeIf { usesCommitText(style) }
  val commitMessage: String? get() = messageArea.text.takeIf { usesCommitText(style) }
  val deleteBranch: Boolean get() = deleteBranchBox.isSelected
  val whenChecksSucceed: Boolean get() = autoMergeBox.isVisible && autoMergeBox.isSelected

  init {
    title = GiteaBundle.message("pull.request.merge.dialog.title", number)
    styleBox.addActionListener { updateState() }
    autoMergeBox.addActionListener { updateState() }
    init()
    updateState()
  }

  override fun createCenterPanel(): JComponent = panel {
    row(GiteaBundle.message("pull.request.merge.dialog.style")) { cell(styleBox) }
    row(GiteaBundle.message("pull.request.merge.dialog.commit.title")) { cell(titleField).align(AlignX.FILL) }
    row(GiteaBundle.message("pull.request.merge.dialog.commit.message")) { scrollCell(messageArea).align(Align.FILL) }.resizableRow()
    row { cell(deleteBranchBox) }
    row { cell(autoMergeBox) }
  }

  override fun getPreferredFocusedComponent(): JComponent = styleBox

  /** Rebase and fast-forward create no merge commit, so there is no text to enter. */
  private fun updateState() {
    val text = usesCommitText(style)
    titleField.isEnabled = text
    messageArea.isEnabled = text
    setOKButtonText(GiteaBundle.message(when {
      whenChecksSucceed -> "pull.request.merge.dialog.ok.auto"
      else -> "pull.request.merge.dialog.ok"
    }))
  }

  companion object {
    fun usesCommitText(style: GiteaMergeStyle): Boolean =
      style == GiteaMergeStyle.MERGE || style == GiteaMergeStyle.REBASE_MERGE || style == GiteaMergeStyle.SQUASH

    fun styleText(style: GiteaMergeStyle?): String = when (style) {
      GiteaMergeStyle.MERGE -> GiteaBundle.message("pull.request.merge.style.merge")
      GiteaMergeStyle.REBASE -> GiteaBundle.message("pull.request.merge.style.rebase")
      GiteaMergeStyle.REBASE_MERGE -> GiteaBundle.message("pull.request.merge.style.rebase.merge")
      GiteaMergeStyle.SQUASH -> GiteaBundle.message("pull.request.merge.style.squash")
      GiteaMergeStyle.FAST_FORWARD_ONLY -> GiteaBundle.message("pull.request.merge.style.fast.forward")
      null -> ""
    }
  }
}
