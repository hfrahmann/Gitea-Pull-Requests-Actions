package de.hendrikfrahmann.giteaplugin.pullrequest.file

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.timeline.GiteaPullRequestTimelineEditorFactory
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import javax.swing.JComponent

/** Opens [GiteaPullRequestTimelineFile]s in a [GiteaPullRequestTimelineFileEditor] (like GitLab's file editor provider). */
class GiteaFileEditorProvider : FileEditorProvider, DumbAware {
  override fun accept(project: Project, file: VirtualFile): Boolean = file is GiteaPullRequestTimelineFile

  override fun acceptRequiresReadAction(): Boolean = false

  override fun createEditor(project: Project, file: VirtualFile): FileEditor =
    GiteaPullRequestTimelineFileEditor(project, file as GiteaPullRequestTimelineFile)

  override fun getEditorTypeId(): String = "GiteaPullRequestTimeline"

  override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/** The timeline of one PR; the component is built on first show and lives until the editor is closed. */
class GiteaPullRequestTimelineFileEditor(private val project: Project, private val file: GiteaPullRequestTimelineFile)
  : UserDataHolderBase(), FileEditor {
  private var disposed = false
  private val propertyChangeSupport = PropertyChangeSupport(this)

  private val lazyComponent by lazy {
    project.service<GiteaPullRequestTimelineEditorFactory>().createComponent(file.connectionId, file.number, this)
  }

  override fun getComponent(): JComponent = lazyComponent
  override fun getPreferredFocusedComponent(): JComponent? = null
  override fun getName(): String = file.presentableName
  override fun getFile(): VirtualFile = file

  // not CheckedDisposable: that interface is @NonExtendable
  override fun isValid(): Boolean = !disposed

  override fun dispose() {
    disposed = true
    propertyChangeSupport.firePropertyChange(FileEditor.getPropValid(), true, false)
  }

  override fun addPropertyChangeListener(listener: PropertyChangeListener) = propertyChangeSupport.addPropertyChangeListener(listener)
  override fun removePropertyChangeListener(listener: PropertyChangeListener) = propertyChangeSupport.removePropertyChangeListener(listener)

  override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE
  override fun setState(state: FileEditorState) {}
  override fun isModified(): Boolean = false
}
