package de.hendrikfrahmann.giteaplugin.actions

import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaArtifact
import de.hendrikfrahmann.giteaplugin.api.request.artifactZipUri
import de.hendrikfrahmann.giteaplugin.api.request.download
import de.hendrikfrahmann.giteaplugin.api.request.runLogsZipUri
import de.hendrikfrahmann.giteaplugin.notification.GiteaNotificationIds
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import de.hendrikfrahmann.giteaplugin.settings.GiteaSettings
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import kotlinx.coroutines.CancellationException
import org.jetbrains.annotations.Nls
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

private val LOG = logger<GiteaActionsDownloads>()

/**
 * Artifact and run log downloads as cancellable background tasks into the folder from the settings
 * (`~/Downloads` by default). Artifacts can be unpacked next to the zip. The API redirects artifact downloads to a
 * signed URL; [download] follows it and only sends credentials to the configured server.
 */
@Service(Service.Level.PROJECT)
class GiteaActionsDownloads(private val project: Project) {
  fun downloadArtifact(connection: GiteaProjectConnection, artifact: GiteaArtifact) {
    val uri = connection.api.artifactZipUri(connection.repo.repository.path, artifact.id)
    start(connection, uri, "${artifact.name}.zip", GiteaBundle.message("actions.download.artifact.progress", artifact.name),
          extract = GiteaSettings.getInstance().extractArtifacts)
  }

  fun downloadRunLogs(connection: GiteaProjectConnection, runId: Long) {
    val uri = connection.api.runLogsZipUri(connection.repo.repository.path, runId)
    start(connection, uri, "${connection.repo.repository.path.name}-run-$runId-logs.zip",
          GiteaBundle.message("actions.download.logs.progress", runId), extract = false)
  }

  /**
   * A classic background task rather than `withBackgroundProgress` + `reportRawProgress`: the latter is inline and
   * compiles an `@Internal` platform class into the plugin (verifier).
   */
  private fun start(connection: GiteaProjectConnection, uri: URI, fileName: String, title: @Nls String, extract: Boolean) {
    object : Task.Backgroundable(project, title, true) {
      override fun run(indicator: ProgressIndicator) {
        try {
          val target = download(connection, uri, fileName, indicator)
          val extracted = when {
            extract -> unzip(target)
            else -> null
          }
          notifyDownloaded(extracted ?: target, fileName)
        }
        catch (e: ProcessCanceledException) {
          throw e
        }
        catch (e: CancellationException) {
          throw e
        }
        catch (e: Exception) {
          LOG.info("Download of $uri failed", e)
          notifyFailed(fileName, e)
        }
      }
    }.queue()
  }

  /** Downloads into a new file in the download folder and returns it. */
  private fun download(connection: GiteaProjectConnection, uri: URI, fileName: String, indicator: ProgressIndicator): Path {
    val target = uniqueFile(GiteaSettings.getInstance().artifactsDirectoryPath, safeFileName(fileName))
    runBlockingCancellable {
      connection.api.download(uri, target) { read, total -> showProgress(indicator, read, total) }
    }
    return target
  }

  /** A fraction and `read / total` when the size is known, otherwise only the bytes read so far. */
  private fun showProgress(indicator: ProgressIndicator, read: Long, total: Long) {
    indicator.isIndeterminate = total <= 0
    if (total > 0) {
      indicator.fraction = read.toDouble() / total
    }
    indicator.text2 = when {
      total > 0 -> "${StringUtil.formatFileSize(read)} / ${StringUtil.formatFileSize(total)}"
      else -> StringUtil.formatFileSize(read)
    }
  }

  private fun notifyDownloaded(path: Path, fileName: String) {
    notificationGroup()
      .createNotification(GiteaBundle.message("actions.download.done", fileName), path.toString(), NotificationType.INFORMATION)
      .setDisplayId(GiteaNotificationIds.ACTIONS_DOWNLOAD)
      .addAction(NotificationAction.createSimpleExpiring(RevealFileAction.getActionName()) { RevealFileAction.openFile(path) })
      .notify(project)
  }

  private fun notifyFailed(fileName: String, error: Exception) {
    notificationGroup()
      .createNotification(GiteaBundle.message("actions.download.failed", fileName), error.localizedMessage ?: error.toString(), NotificationType.ERROR)
      .setDisplayId(GiteaNotificationIds.ACTIONS_DOWNLOAD)
      .notify(project)
  }

  private fun notificationGroup() = NotificationGroupManager.getInstance().getNotificationGroup(GiteaNotificationIds.ACTIONS_GROUP)

  companion object {
    fun getInstance(project: Project): GiteaActionsDownloads = project.service()

    fun downloadRunLogs(project: Project, connection: GiteaProjectConnection, runId: Long) =
      getInstance(project).downloadRunLogs(connection, runId)

    internal fun safeFileName(name: String): String = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().ifEmpty { "download" }

    /** `name.zip`, or `name (1).zip`, … if it exists. */
    internal fun uniqueFile(directory: Path, fileName: String): Path {
      val base = fileName.substringBeforeLast('.', fileName)
      val extension = fileName.substringAfterLast('.', "").let {
        when {
          it.isEmpty() -> ""
          else -> ".$it"
        }
      }
      var candidate = directory.resolve(fileName)
      var i = 1
      while (Files.exists(candidate)) {
        candidate = directory.resolve("$base (${i++})$extension")
      }
      return candidate
    }

    /** Unpacks [zip] into a folder named like it (without `.zip`); entries escaping that folder are rejected. */
    @RequiresBackgroundThread
    internal fun unzip(zip: Path): Path {
      ThreadingAssertions.assertBackgroundThread()
      val target = uniqueFile(zip.parent, zip.fileName.toString().removeSuffix(".zip"))
      Files.createDirectories(target)
      val root = target.toRealPath()
      ZipInputStream(Files.newInputStream(zip)).use { input ->
        while (true) {
          val entry = input.nextEntry ?: break
          val destination = root.resolve(entry.name).normalize()
          if (!destination.startsWith(root)) {
            throw IOException(GiteaBundle.message("actions.download.zip.outside", entry.name))
          }
          if (entry.isDirectory) {
            Files.createDirectories(destination)
          }
          else {
            Files.createDirectories(destination.parent)
            Files.copy(input, destination)
          }
        }
      }
      return target
    }
  }
}
