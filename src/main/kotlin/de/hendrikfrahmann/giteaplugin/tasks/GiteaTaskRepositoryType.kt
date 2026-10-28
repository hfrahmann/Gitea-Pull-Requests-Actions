package de.hendrikfrahmann.giteaplugin.tasks

import com.intellij.openapi.project.Project
import com.intellij.tasks.TaskRepository
import com.intellij.tasks.config.TaskRepositoryEditor
import com.intellij.tasks.impl.BaseRepositoryType
import com.intellij.util.Consumer
import de.hendrikfrahmann.giteaplugin.util.GiteaIcons
import javax.swing.Icon

/**
 * "Gitea" in `Settings | Tools | Tasks | Servers`. Registered from `gitea-tasks.xml` only, so the plugin loads
 * without the "Issue Trackers" plugin; nothing outside this package may use `com.intellij.tasks`.
 */
class GiteaTaskRepositoryType : BaseRepositoryType<GiteaTaskRepository>() {
  override fun getName(): String = "Gitea"

  override fun getIcon(): Icon = GiteaIcons.Gitea

  override fun createRepository(): TaskRepository = GiteaTaskRepository(this)

  override fun getRepositoryClass(): Class<GiteaTaskRepository> = GiteaTaskRepository::class.java

  override fun createEditor(repository: GiteaTaskRepository, project: Project, changeListener: Consumer<in GiteaTaskRepository>)
    : TaskRepositoryEditor = GiteaTaskRepositoryEditor(project, repository, changeListener)
}
