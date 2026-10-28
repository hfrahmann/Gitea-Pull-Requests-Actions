package de.hendrikfrahmann.giteaplugin.ui.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GiteaRefreshActionPromoterTest {
  private val view = DataKey.create<String>("gitea.test.view")
  private val refresh = object : GiteaRefreshAction() {
    override fun refresher(context: DataContext): (() -> Unit)? = context.getData(view)?.let { {} }
  }
  private val replace = object : AnAction() {
    override fun actionPerformed(e: AnActionEvent) = Unit
  }
  private val rerun = object : AnAction() {
    override fun actionPerformed(e: AnActionEvent) = Unit
  }

  @Test
  fun `inside a Gitea view only its refresh stays`() {
    val inView = DataContext { id -> "details".takeIf { id == view.name } }
    assertEquals(listOf(replace, rerun), GiteaRefreshActionPromoter().suppress(listOf(replace, refresh, rerun), inView))
  }

  @Test
  fun `outside of Gitea views nothing is suppressed`() {
    assertNull(GiteaRefreshActionPromoter().suppress(listOf(replace, refresh, rerun), DataContext.EMPTY_CONTEXT))
  }
}
