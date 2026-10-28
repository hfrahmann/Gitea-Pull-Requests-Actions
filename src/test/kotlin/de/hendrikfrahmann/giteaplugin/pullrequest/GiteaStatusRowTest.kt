package de.hendrikfrahmann.giteaplugin.pullrequest

import com.intellij.icons.AllIcons
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import de.hendrikfrahmann.giteaplugin.forbidSlowOperations
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.GiteaStatusRow
import javax.swing.JComponent
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/** Layout of the PR details' status rows: long texts wrap, icon and links stay at the first line. */
class GiteaStatusRowTest : BasePlatformTestCase() {
  fun `test a short text stays on one line with the link right after it`(): Unit = forbidSlowOperations {
    val link = ActionLink("Details")
    val row = row("Checks in progress…", link)

    layOut(row, WIDE)

    val text = textOf(row)
    assertEquals(lineHeight(text), text.height)
    assertTrue("link right after the text", xIn(row, link) < text.x + text.width + LINK_GAP_TOLERANCE)
  }

  fun `test a long text wraps and keeps the icon and the link at its first line`(): Unit = forbidSlowOperations {
    val link = ActionLink("Details")
    val row = row(LONG_TEXT, link)

    layOut(row, NARROW)

    val text = textOf(row)
    val icon = UIUtil.findComponentOfType(row, JBLabel::class.java)!!
    assertTrue("wrapped: ${text.height} > ${lineHeight(text)}", text.height > lineHeight(text))
    assertEquals("icon at the first line", text.y, icon.y)
    assertTrue("link at the first line", yIn(row, link) < text.y + lineHeight(text))
    assertTrue("nothing cut off", xIn(row, link) + link.width <= NARROW)
  }

  fun `test a wrapped text goes back to one line when there is room again`(): Unit = forbidSlowOperations {
    val row = row(LONG_TEXT)

    layOut(row, NARROW)
    layOut(row, WIDE * 2)

    assertEquals(lineHeight(textOf(row)), textOf(row).height)
  }

  private fun row(text: String, vararg links: JComponent) = GiteaStatusRow("test", links.toList()).apply {
    icon = AllIcons.General.Warning
    this.text = text
    isVisible = true
  }

  /** Twice, like Swing does after the wrapped text asks for more height. */
  private fun layOut(row: GiteaStatusRow, width: Int) {
    repeat(2) {
      row.setSize(width, row.preferredSize.height)
      row.doLayout()
      UIUtil.uiTraverser(row).filter(JComponent::class.java).forEach { it.doLayout() }
    }
  }

  private fun textOf(row: GiteaStatusRow): JTextArea = UIUtil.findComponentOfType(row, JTextArea::class.java)!!

  private fun lineHeight(text: JTextArea): Int = text.getFontMetrics(text.font).height

  private fun xIn(row: GiteaStatusRow, component: JComponent): Int = SwingUtilities.convertPoint(component.parent, component.location, row).x

  private fun yIn(row: GiteaStatusRow, component: JComponent): Int = SwingUtilities.convertPoint(component.parent, component.location, row).y

  private companion object {
    const val NARROW = 220
    const val WIDE = 600
    const val LINK_GAP_TOLERANCE = 20
    const val LONG_TEXT = "At least 1 approving review is required by reviewers with write access"
  }
}
