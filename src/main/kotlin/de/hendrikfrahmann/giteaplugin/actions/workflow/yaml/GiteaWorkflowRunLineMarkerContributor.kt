package de.hendrikfrahmann.giteaplugin.actions.workflow.yaml

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import de.hendrikfrahmann.giteaplugin.actions.workflow.GiteaWorkflowFiles
import de.hendrikfrahmann.giteaplugin.actions.workflow.run.GiteaWorkflowConfigurationType
import de.hendrikfrahmann.giteaplugin.actions.workflow.run.GiteaWorkflowRunConfiguration
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * What a key of a workflow file runs: the top-level `jobs:` key all jobs ([job] `null`), a key directly below it
 * that job. Everything touching the YAML plugin lives in this package and is registered in `gitea-yaml.xml`.
 */
internal data class GiteaWorkflowTarget(val job: String?) {
  companion object {
    private const val JOBS = "jobs"

    fun of(keyValue: YAMLKeyValue): GiteaWorkflowTarget? {
      if (keyValue.isTopLevel()) {
        return when {
          keyValue.keyText == JOBS -> GiteaWorkflowTarget(null)
          else -> null
        }
      }
      val parent = keyValue.parentMapping?.parent as? YAMLKeyValue ?: return null
      return when {
        parent.keyText == JOBS && parent.isTopLevel() -> GiteaWorkflowTarget(keyValue.keyText)
        else -> null
      }
    }

    /** The innermost job (or `jobs:`) around [element]. */
    fun around(element: PsiElement): GiteaWorkflowTarget? {
      var keyValue = element.parentOfType<YAMLKeyValue>(withSelf = true)
      while (keyValue != null) {
        of(keyValue)?.let { return it }
        keyValue = keyValue.parentOfType<YAMLKeyValue>(withSelf = false)
      }
      return null
    }

    private fun YAMLKeyValue.isTopLevel(): Boolean = parentMapping?.parent is YAMLDocument
  }
}

/** Play icons on job keys and `jobs:` of active workflow files: Run (local configuration) and "Run Workflow on Gitea…". */
class GiteaWorkflowRunLineMarkerContributor : RunLineMarkerContributor() {
  override fun getInfo(element: PsiElement): Info? {
    if (element.node?.elementType != YAMLTokenTypes.SCALAR_KEY) {
      return null
    }
    val keyValue = element.parent as? YAMLKeyValue ?: return null
    val target = GiteaWorkflowTarget.of(keyValue) ?: return null
    val file = element.containingFile?.virtualFile ?: return null
    if (GiteaWorkflowFiles.find(element.project, file)?.isActive != true) {
      return null
    }
    val remote = ActionManager.getInstance().getAction(REMOTE_ACTION_ID)
    val actions = ExecutorAction.getActions(0) + listOfNotNull(remote)
    val tooltip = when {
      target.job == null -> GiteaBundle.message("workflow.marker.all.jobs")
      else -> GiteaBundle.message("workflow.marker.job", target.job)
    }
    return Info(AllIcons.RunConfigurations.TestState.Run, actions) { tooltip }
  }

  companion object {
    const val REMOTE_ACTION_ID: String = "Gitea.Workflow.Run.Remote"
  }
}

/** Creates "Gitea Workflow" configurations for a job (or `jobs:`) of an active workflow file, or the whole file. */
class GiteaWorkflowRunConfigurationProducer : LazyRunConfigurationProducer<GiteaWorkflowRunConfiguration>() {
  override fun getConfigurationFactory(): ConfigurationFactory = GiteaWorkflowConfigurationType.getInstance().factory

  override fun setupConfigurationFromContext(configuration: GiteaWorkflowRunConfiguration, context: ConfigurationContext,
                                             sourceElement: Ref<PsiElement>): Boolean {
    val (workflow, target) = find(context) ?: return false
    configuration.workflowFile = workflow.file.path
    configuration.job = target.job.orEmpty()
    configuration.name = GiteaWorkflowRunConfiguration.suggestedName(workflow.workflowId, target.job)
    return true
  }

  override fun isConfigurationFromContext(configuration: GiteaWorkflowRunConfiguration, context: ConfigurationContext): Boolean {
    val (workflow, target) = find(context) ?: return false
    return configuration.workflowFile == workflow.file.path && configuration.job == target.job.orEmpty()
  }

  private fun find(context: ConfigurationContext): Pair<GiteaWorkflowFiles.WorkflowFile, GiteaWorkflowTarget>? {
    val element = context.psiLocation ?: return null
    val file = element.containingFile?.virtualFile ?: return null
    val workflow = GiteaWorkflowFiles.find(context.project, file)?.takeIf { it.isActive } ?: return null
    return workflow to (GiteaWorkflowTarget.around(element) ?: GiteaWorkflowTarget(null))
  }
}
