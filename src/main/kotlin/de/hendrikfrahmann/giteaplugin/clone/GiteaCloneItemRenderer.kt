package de.hendrikfrahmann.giteaplugin.clone

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import de.hendrikfrahmann.giteaplugin.util.GiteaIcons
import javax.swing.JList

/** `owner/name  description  [account@host]` with a padlock for private repositories; shared by "Get from VCS" and the Repositories tab. */
class GiteaCloneItemRenderer(private val showAccount: () -> Boolean) : ColoredListCellRenderer<GiteaCloneItem>() {
  override fun customizeCellRenderer(list: JList<out GiteaCloneItem>, value: GiteaCloneItem, index: Int, selected: Boolean, hasFocus: Boolean) {
    when (value) {
      is GiteaCloneItem.Repository -> {
        // the padlock is the only sign of a private repository: its tooltip is also announced by screen readers
        icon = when {
          value.repository.isPrivate -> GiteaIcons.withToolTip(AllIcons.Nodes.Padlock, GiteaBundle.message("repositories.private"))
          else -> AllIcons.Vcs.Branch
        }
        append(value.repository.fullName)
        value.repository.description?.takeIf { it.isNotBlank() }?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
      }
      is GiteaCloneItem.Error -> {
        icon = AllIcons.General.Error
        append(value.message, SimpleTextAttributes.ERROR_ATTRIBUTES)
      }
    }
    if (showAccount()) {
      append("  ${value.account.name}@${value.account.server.host}", SimpleTextAttributes.GRAY_SMALL_ATTRIBUTES)
    }
  }
}
