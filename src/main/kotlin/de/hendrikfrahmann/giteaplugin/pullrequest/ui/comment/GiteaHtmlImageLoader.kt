package de.hendrikfrahmann.giteaplugin.pullrequest.ui.comment

import com.intellij.collaboration.ui.html.AsyncHtmlImageLoader
import com.intellij.ui.scale.JBUIScale
import com.intellij.ui.scale.ScaleContext
import com.intellij.util.ui.ImageUtil
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Image
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URL
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Images in rendered markdown (descriptions, comments): attachments of private repositories need the token, and their
 * URLs use the server's `ROOT_URL`, which may differ from the configured URL. Those are rebased onto the server and
 * loaded with credentials; images of other hosts are loaded without.
 *
 * The platform's image view ignores `width`/`height` of `<img>` (Gitea sets them e.g. for pasted HiDPI screenshots), so
 * [withSizeHints] passes them in the URL fragment and the image is scaled here.
 */
class GiteaHtmlImageLoader(private val connection: GiteaProjectConnection) : AsyncHtmlImageLoader {
  private val remoteRoot: URI? = connection.repository.htmlUrl?.let {
    GiteaServerPath.remoteRootOf(it, connection.repo.repository.path.toString())
  }

  override suspend fun load(baseUrl: URL?, src: String): Image? {
    val hint = parseSizeHint(src)
    val bytes = loadBytes(baseUrl, hint.src) ?: return null
    val image = withContext(Dispatchers.IO) { ImageIO.read(ByteArrayInputStream(bytes)) } ?: return null
    // the page's width/height win; an SVG otherwise gets its own declared size – decoders render it at its viewBox, which
    // for e.g. Renovate's badges (44.7×20, viewBox 447×200) is ten times too big
    val svg = when {
      hint.width == null && hint.height == null -> svgSize(bytes)
      else -> null
    }
    return sized(image, hint.width ?: svg?.first, hint.height ?: svg?.second)
  }

  private suspend fun loadBytes(baseUrl: URL?, src: String): ByteArray? {
    val api = connection.api
    val uri = runCatching { baseUrl?.toURI()?.resolve(src) ?: URI.create(src) }.getOrNull() ?: return null
    val serverUri = when {
      !uri.isAbsolute -> api.server.rebase(src, remoteRoot)
      api.server.owns(uri) -> uri
      remoteRoot != null && uri.toString().startsWith(remoteRoot.toString()) -> api.server.rebase(uri.toString(), remoteRoot)
      else -> null
    }
    if (serverUri == null) {
      if (uri.scheme != "http" && uri.scheme != "https") {
        return null
      }
      return withContext(Dispatchers.IO) { uri.toURL().openStream().use { it.readAllBytes() } }
    }
    return api.sendAndRead(api.request(serverUri).setHeader("Accept", "image/*").GET().build()) { stream, _ -> stream.readAllBytes() }.body
  }

  /** `src` without the size hint and the size from `width`/`height` (CSS pixels, aspect ratio kept when one is missing). */
  internal data class SizeHint(val src: String, val width: Int?, val height: Int?)

  companion object {
    private const val SIZE_FRAGMENT = "#gitea-size="
    private val IMG_TAG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val WIDTH = Regex("\\swidth\\s*=\\s*\"(\\d+)(?:px)?\"", RegexOption.IGNORE_CASE)
    private val HEIGHT = Regex("\\sheight\\s*=\\s*\"(\\d+)(?:px)?\"", RegexOption.IGNORE_CASE)
    private val SRC = Regex("(\\ssrc\\s*=\\s*\")([^\"#]*)(\")", RegexOption.IGNORE_CASE)
    private val HINT = Regex("${SIZE_FRAGMENT}(\\d*)x(\\d*)$")
    private val SVG_TAG = Regex("<svg\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val SVG_WIDTH = Regex("\\swidth\\s*=\\s*[\"']([0-9.]+)(?:px)?[\"']", RegexOption.IGNORE_CASE)
    private val SVG_HEIGHT = Regex("\\sheight\\s*=\\s*[\"']([0-9.]+)(?:px)?[\"']", RegexOption.IGNORE_CASE)
    private const val SVG_HEAD_BYTES = 4096

    /** Declared pixel size of an SVG's root element (rounded up), `null` for other images or without both sizes. */
    internal fun svgSize(bytes: ByteArray): Pair<Int, Int>? {
      val head = String(bytes, 0, minOf(bytes.size, SVG_HEAD_BYTES), Charsets.UTF_8)
      val tag = SVG_TAG.find(head)?.value ?: return null
      val width = SVG_WIDTH.find(tag)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
      val height = SVG_HEIGHT.find(tag)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
      return ceil(width).toInt() to ceil(height).toInt()
    }

    /** Appends the pixel `width`/`height` of each `<img>` to its `src` as `#gitea-size=WxH` (for [load]); `%` sizes are ignored. */
    fun withSizeHints(html: String): String = IMG_TAG.replace(html) { tag ->
      val width = WIDTH.find(tag.value)?.groupValues?.get(1)
      val height = HEIGHT.find(tag.value)?.groupValues?.get(1)
      if (width == null && height == null) {
        tag.value
      }
      else {
        SRC.replace(tag.value) { "${it.groupValues[1]}${it.groupValues[2]}$SIZE_FRAGMENT${width.orEmpty()}x${height.orEmpty()}${it.groupValues[3]}" }
      }
    }

    internal fun parseSizeHint(src: String): SizeHint {
      val match = HINT.find(src) ?: return SizeHint(src, null, null)
      val width = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
      val height = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 }
      return SizeHint(src.substring(0, match.range.first), width, height)
    }

    /** User-space size for the hint, `null` without one (the image keeps its own size). */
    internal fun targetSize(imageWidth: Int, imageHeight: Int, width: Int?, height: Int?): Pair<Int, Int>? {
      if (imageWidth <= 0 || imageHeight <= 0) {
        return null
      }
      return when {
        width != null && height != null -> width to height
        width != null -> width to (imageHeight.toDouble() * width / imageWidth).roundToInt().coerceAtLeast(1)
        height != null -> (imageWidth.toDouble() * height / imageHeight).roundToInt().coerceAtLeast(1) to height
        else -> null
      }
    }

    /** On HiDPI screens the full image is drawn into the smaller user size, so it stays sharp. */
    private fun sized(image: Image?, width: Int?, height: Int?): Image? {
      if (image == null) {
        return null
      }
      val (w, h) = targetSize(image.getWidth(null), image.getHeight(null), width, height) ?: return image
      val userWidth = JBUIScale.scale(w.toFloat()).toDouble()
      val userHeight = JBUIScale.scale(h.toFloat()).toDouble()
      val hiDpi = ImageUtil.ensureHiDPI(image, ScaleContext.create(), userWidth, userHeight)
      return when {
        hiDpi !== image -> hiDpi
        else -> ImageUtil.scaleImage(image, userWidth.roundToInt(), userHeight.roundToInt())
      }
    }
  }
}
