package de.hendrikfrahmann.giteaplugin.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.not
import com.intellij.ui.layout.selectedValueMatches
import de.hendrikfrahmann.giteaplugin.actions.workflow.run.GiteaLocalRunnerUi
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle

/** `Settings | Version Control | Gitea | Settings` – pull requests, refresh, Gitea Actions, local workflow runs; child of [GiteaSettingsConfigurable]. */
class GiteaGeneralSettingsConfigurable(private val project: Project)
  : BoundConfigurable(GiteaBundle.message("settings.general.display.name"), HELP_TOPIC) {

  override fun createPanel(): DialogPanel {
    val settings = GiteaSettings.getInstance()
    return panel {
      pullRequestsGroup(settings)
      refreshGroup(settings)
      actionsGroup(settings)
      localRunsGroup(settings)
    }
  }

  private fun Panel.pullRequestsGroup(settings: GiteaSettings) {
    group(GiteaBundle.message("settings.pull.requests.group")) {
      row {
        checkBox(GiteaBundle.message("settings.reviewers.all.users"))
          .bindSelected({ settings.reviewersFromAllUsers }, { settings.reviewersFromAllUsers = it })
      }
      row {
        checkBox(GiteaBundle.message("settings.open.timeline.from.list"))
          .bindSelected({ settings.openTimelineFromList }, { settings.openTimelineFromList = it })
          .comment(GiteaBundle.message("settings.open.timeline.from.list.comment"))
      }
    }
  }

  private fun Panel.refreshGroup(settings: GiteaSettings) {
    group(GiteaBundle.message("settings.refresh.group")) {
      row(GiteaBundle.message("settings.refresh.fast")) {
        intTextField(GiteaSettings.REFRESH_FAST_RANGE)
          .columns(NUMBER_COLUMNS)
          .bindIntText({ settings.refreshFastSeconds }, { settings.refreshFastSeconds = it })
          .gap(RightGap.SMALL)
        label(GiteaBundle.message("settings.seconds"))
      }
      row(GiteaBundle.message("settings.refresh.slow")) {
        intTextField(GiteaSettings.REFRESH_SLOW_RANGE)
          .columns(NUMBER_COLUMNS)
          .bindIntText({ settings.refreshSlowSeconds }, { settings.refreshSlowSeconds = it })
          .gap(RightGap.SMALL)
        label(GiteaBundle.message("settings.seconds"))
      }
      row {
        checkBox(GiteaBundle.message("settings.refresh.pause"))
          .bindSelected({ settings.pauseRefreshInBackground }, { settings.pauseRefreshInBackground = it })
          .comment(GiteaBundle.message("settings.refresh.pause.comment"))
      }
    }
  }

  private fun Panel.actionsGroup(settings: GiteaSettings) {
    group(GiteaBundle.message("settings.actions.group")) {
      row {
        checkBox(GiteaBundle.message("settings.actions.notify"))
          .bindSelected({ settings.notifyRunFinished }, { settings.notifyRunFinished = it })
      }
      row(GiteaBundle.message("settings.actions.page.size")) {
        intTextField(GiteaSettings.RUNS_PAGE_SIZE_RANGE)
          .columns(NUMBER_COLUMNS)
          .bindIntText({ settings.runsPageSize }, { settings.runsPageSize = it })
          .comment(GiteaBundle.message("settings.actions.page.size.comment"))
      }
      row(GiteaBundle.message("settings.actions.artifacts.directory")) {
        textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor()
                                    .withTitle(GiteaBundle.message("settings.actions.artifacts.directory.title")), project)
          .bindText({ settings.artifactsDirectory }, { settings.artifactsDirectory = it })
          .comment(GiteaBundle.message("settings.actions.artifacts.directory.comment"))
          .align(AlignX.FILL)
      }
      row {
        checkBox(GiteaBundle.message("settings.actions.artifacts.extract"))
          .bindSelected({ settings.extractArtifacts }, { settings.extractArtifacts = it })
      }
    }
  }

  private fun Panel.localRunsGroup(settings: GiteaSettings) {
    group(GiteaBundle.message("settings.local.runs.group")) {
      lateinit var runner: Cell<ComboBox<GiteaLocalRunner>>
      row(GiteaBundle.message("settings.local.runs.runner")) {
        runner = comboBox(GiteaLocalRunner.entries, GiteaLocalRunnerUi.renderer<GiteaLocalRunner> { GiteaLocalRunnerUi.text(it) })
          .bindItem({ settings.localRunner }, { settings.localRunner = it ?: GiteaLocalRunner.DOCKER })
          .comment(GiteaBundle.message("settings.local.runs.runner.comment"))
      }
      val docker = runner.component.selectedValueMatches { it == GiteaLocalRunner.DOCKER }
      row(GiteaBundle.message("settings.local.runs.docker")) {
        textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFileOrExecutableAppDescriptor()
                                    .withTitle(GiteaBundle.message("settings.local.runs.docker.title")), project)
          .bindText({ settings.dockerExecutable }, { settings.dockerExecutable = it })
          .comment(GiteaBundle.message("settings.local.runs.path.comment"))
          .align(AlignX.FILL)
      }.visibleIf(docker)
      row(GiteaBundle.message("settings.local.runs.image")) {
        textField()
          .bindText({ settings.runnerImage }, { settings.runnerImage = it })
          .comment(GiteaBundle.message("settings.local.runs.image.comment"))
          .align(AlignX.FILL)
      }.visibleIf(docker)
      row(GiteaBundle.message("settings.local.runs.binary")) {
        textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFileOrExecutableAppDescriptor()
                                    .withTitle(GiteaBundle.message("settings.local.runs.binary.title")), project)
          .bindText({ settings.runnerBinary }, { settings.runnerBinary = it })
          .comment(GiteaBundle.message("settings.local.runs.binary.comment"))
          .align(AlignX.FILL)
      }.visibleIf(docker.not())
      row(GiteaBundle.message("settings.local.runs.job.image")) {
        textField()
          .bindText({ settings.jobImage }, { settings.jobImage = it })
          .comment(GiteaBundle.message("settings.local.runs.job.image.comment"))
          .align(AlignX.FILL)
      }
    }
  }

  companion object {
    const val HELP_TOPIC: String = "settings.gitea.general"

    private const val NUMBER_COLUMNS = 4
  }
}
