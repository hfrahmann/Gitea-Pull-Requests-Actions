package de.hendrikfrahmann.giteaplugin.pullrequest

import de.hendrikfrahmann.giteaplugin.pullrequest.ui.comment.GiteaHtmlImageLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** `width`/`height` of images in rendered markdown (the platform's image view ignores them). */
class GiteaHtmlImageSizeTest {
  @Test
  fun `pixel sizes travel in the src fragment`() {
    val html = """<a href="x"><img width="400" alt="x" src="http://h/attachments/def" loading="lazy"/></a>""" +
               """<img src="http://h/a.png" alt="plain"/><img height="50%" src="http://h/b.png"/><img height="120px" src="/c.png"/>"""
    assertEquals(
      """<a href="x"><img width="400" alt="x" src="http://h/attachments/def#gitea-size=400x" loading="lazy"/></a>""" +
      """<img src="http://h/a.png" alt="plain"/><img height="50%" src="http://h/b.png"/><img height="120px" src="/c.png#gitea-size=x120"/>""",
      GiteaHtmlImageLoader.withSizeHints(html),
    )
  }

  @Test
  fun `hint is parsed and stripped`() {
    assertEquals(GiteaHtmlImageLoader.SizeHint("http://h/d", 400, null), GiteaHtmlImageLoader.parseSizeHint("http://h/d#gitea-size=400x"))
    assertEquals(GiteaHtmlImageLoader.SizeHint("/c.png", 30, 120), GiteaHtmlImageLoader.parseSizeHint("/c.png#gitea-size=30x120"))
    assertEquals(GiteaHtmlImageLoader.SizeHint("http://h/e#frag", null, null), GiteaHtmlImageLoader.parseSizeHint("http://h/e#frag"))
  }

  @Test
  fun `missing side keeps the aspect ratio`() {
    assertEquals(400 to 300, GiteaHtmlImageLoader.targetSize(800, 600, 400, null))
    assertEquals(200 to 150, GiteaHtmlImageLoader.targetSize(800, 600, null, 150))
    assertEquals(10 to 20, GiteaHtmlImageLoader.targetSize(800, 600, 10, 20))
    assertNull(GiteaHtmlImageLoader.targetSize(800, 600, null, null))
    assertNull(GiteaHtmlImageLoader.targetSize(-1, -1, 400, null))
  }

  @Test
  fun `svg images use their declared size, not the viewBox`() {
    // Renovate's age/confidence badges
    val badge = """<svg width="44.7" height="20" viewBox="0 0 447 200" xmlns="http://www.w3.org/2000/svg" role="img"><image width="447"/></svg>"""
    assertEquals(45 to 20, GiteaHtmlImageLoader.svgSize(badge.toByteArray()))
    assertEquals(16 to 16, GiteaHtmlImageLoader.svgSize("""<?xml version="1.0"?><svg xmlns="x" height='16px' width='16px'/>""".toByteArray()))
    // only a viewBox, a relative size or no SVG at all: the decoder's size stays
    assertNull(GiteaHtmlImageLoader.svgSize("""<svg viewBox="0 0 10 10" stroke-width="2"></svg>""".toByteArray()))
    assertNull(GiteaHtmlImageLoader.svgSize("""<svg width="100%" height="20"></svg>""".toByteArray()))
    assertNull(GiteaHtmlImageLoader.svgSize(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
  }
}
