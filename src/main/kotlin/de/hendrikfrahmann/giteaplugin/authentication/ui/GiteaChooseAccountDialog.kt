package de.hendrikfrahmann.giteaplugin.authentication.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBDimension
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import org.jetbrains.annotations.Nls
import java.awt.Component
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel

/** For flows that need one account when several match. */
class GiteaChooseAccountDialog(
  project: Project?,
  parentComponent: Component?,
  accounts: Collection<GiteaAccount>,
  showHosts: Boolean,
  allowDefault: Boolean,
  private val description: @Nls String? = null,
) : DialogWrapper(project, parentComponent, false, IdeModalityType.IDE) {

  private val accountsList: JBList<GiteaAccount> = JBList(accounts).apply {
    selectionMode = ListSelectionModel.SINGLE_SELECTION
    cellRenderer = object : ColoredListCellRenderer<GiteaAccount>() {
      override fun customizeCellRenderer(list: JList<out GiteaAccount>, value: GiteaAccount, index: Int, selected: Boolean, hasFocus: Boolean) {
        append(value.name)
        if (showHosts) {
          append(" ")
          append(value.server.toString(), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        border = JBUI.Borders.empty(0, UIUtil.DEFAULT_HGAP)
      }
    }
  }
  private val setDefaultCheckBox: JBCheckBox? = JBCheckBox(GiteaBundle.message("account.choose.set.default")).takeIf { allowDefault }

  val account: GiteaAccount get() = accountsList.selectedValue
  val setDefault: Boolean get() = setDefaultCheckBox?.isSelected ?: false

  init {
    title = GiteaBundle.message("account.choose.title")
    setOKButtonText(GiteaBundle.message("account.choose.action"))
    init()
    accountsList.selectedIndex = 0
  }

  override fun doValidate(): ValidationInfo? = when (accountsList.selectedValue) {
    null -> ValidationInfo(GiteaBundle.message("account.choose.not.selected"), accountsList)
    else -> null
  }

  override fun createCenterPanel(): JComponent {
    val panel = JBUI.Panels.simplePanel(UIUtil.DEFAULT_HGAP, UIUtil.DEFAULT_VGAP)
    val listHeight = LIST_ROW_HEIGHT * (accountsList.itemsCount + 1)
    panel.addToCenter(JBScrollPane(accountsList).apply { preferredSize = JBDimension(LIST_WIDTH, listHeight) })
    description?.let { panel.addToTop(JBLabel(it)) }
    setDefaultCheckBox?.let { panel.addToBottom(it) }
    return panel
  }

  override fun getPreferredFocusedComponent(): JComponent = accountsList

  private companion object {
    const val LIST_WIDTH = 150
    const val LIST_ROW_HEIGHT = 20
  }
}
