package de.hendrikfrahmann.giteaplugin.pullrequest.ui.details

import com.intellij.collaboration.ui.HorizontalListPanel
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import net.miginfocom.layout.AC
import net.miginfocom.layout.CC
import net.miginfocom.layout.LC
import net.miginfocom.swing.MigLayout
import org.jetbrains.annotations.Nls
import java.awt.Dimension
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import kotlin.math.ceil

/**
 * One status line of the PR details: icon, text, links right after the text. Unlike the platform's status labels,
 * which cut long texts off with "…", the text wraps in a narrow tool window; the icon and the links stay next to its
 * first line. Hidden until [isVisible] is set, like the other statuses.
 */
internal class GiteaStatusRow(accessibleName: String, links: List<JComponent> = emptyList()) : JPanel() {
  private val textArea = WrappingText()
  private val iconLabel = FirstLineIcon(textArea)

  var icon: Icon?
    get() = iconLabel.icon
    set(value) {
      iconLabel.icon = value
    }

  var text: @Nls String
    get() = textArea.text
    set(value) {
      textArea.text = value
      revalidate()
      repaint()
    }

  init {
    name = accessibleName
    isOpaque = false
    isVisible = false
    border = JBUI.Borders.empty(ROW_GAP, 0)
    // the text column may shrink to nothing (it wraps); icon and links keep their width
    layout = MigLayout(LC().insets("0").gridGap("0", "0").hideMode(3),
                       AC().gap("${iconLabel.iconTextGap}").gap("${JBUI.scale(LINK_GAP)}"))
    add(iconLabel, CC().alignY("top").minWidth("pref"))
    add(textArea, CC().alignY("top").minWidth("0"))
    if (links.isNotEmpty()) {
      add(HorizontalListPanel(JBUI.scale(LINK_GAP)).apply {
        isOpaque = false
        links.forEach(::add)
      }, CC().alignY("top").minWidth("pref"))
    }
  }

  /** Read-only label-looking text that wraps at word boundaries; wants one line, gets as many as its width needs. */
  private class WrappingText : JTextArea() {
    init {
      lineWrap = true
      wrapStyleWord = true
      isEditable = false
      isFocusable = false
    }

    override fun updateUI() {
      super.updateUI()
      isOpaque = false
      border = null
      font = JBFont.label()
      foreground = UIUtil.getLabelForeground()
    }

    /** One line wide (the wrapped view would keep the narrowest width it ever had), as high as the current width needs. */
    override fun getPreferredSize(): Dimension {
      // exact (fractional) text width plus the caret, or the wrapped view moves the last word to a second line
      val metrics = getFontMetrics(font)
      val textWidth = ceil(font.getStringBounds(text, metrics.fontRenderContext).width).toInt() + CARET_WIDTH
      return Dimension(textWidth + insets.left + insets.right, super.getPreferredSize().height)
    }

    override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)

    val lineHeight: Int
      get() = getFontMetrics(font).height

    private companion object {
      const val CARET_WIDTH = 1
    }
  }

  /** The icon centered on the text's first line, so it lines up with it however many lines follow. */
  private class FirstLineIcon(private val text: WrappingText) : JBLabel() {
    override fun getPreferredSize(): Dimension {
      val size = super.getPreferredSize()
      return Dimension(size.width, maxOf(size.height, text.lineHeight))
    }
  }

  companion object {
    // same spacing as the platform's status rows
    private const val ROW_GAP = 5
    private const val LINK_GAP = 8
  }
}
