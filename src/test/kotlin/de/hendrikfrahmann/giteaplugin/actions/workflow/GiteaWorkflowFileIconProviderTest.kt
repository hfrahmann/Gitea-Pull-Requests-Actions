package de.hendrikfrahmann.giteaplugin.actions.workflow

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Gitea icon for workflow and action files below `.gitea/`. */
class GiteaWorkflowFileIconProviderTest {
  @Test
  fun `workflows and actions below gitea`() {
    assertTrue(GiteaWorkflowFileIconProvider.isGiteaYaml("/work/repo/.gitea/workflows/ci.yml"))
    assertTrue(GiteaWorkflowFileIconProvider.isGiteaYaml("/work/repo/.gitea/workflows/sub/release.yaml"))
    assertTrue(GiteaWorkflowFileIconProvider.isGiteaYaml("/work/repo/.gitea/actions/setup/action.yml"))
    assertTrue(GiteaWorkflowFileIconProvider.isGiteaYaml("/work/repo/.gitea/action.yaml"))
  }

  @Test
  fun `other files keep their icon`() {
    assertFalse(GiteaWorkflowFileIconProvider.isGiteaYaml("/work/repo/.github/workflows/ci.yml"))
    assertFalse(GiteaWorkflowFileIconProvider.isGiteaYaml("/work/repo/.gitea/issue_template/bug.yml"))
    assertFalse(GiteaWorkflowFileIconProvider.isGiteaYaml("/work/repo/.gitea/workflows/README.md"))
    assertFalse(GiteaWorkflowFileIconProvider.isGiteaYaml("/work/repo/workflows/ci.yml"))
    assertFalse(GiteaWorkflowFileIconProvider.isGiteaYaml("/work/repo/action.yml"))
  }
}
