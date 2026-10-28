package de.hendrikfrahmann.giteaplugin.api

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest

// Building blocks for the request functions in the api/request package

suspend inline fun <reified T : Any> GiteaApi.loadJsonValue(
  request: HttpRequest,
  requestName: String = request.logName(),
): GiteaResponse<T> = sendAndRead(request, requestName) { stream, _ -> GiteaJson.mapper.readValue(stream, T::class.java) }

suspend inline fun <reified T : Any> GiteaApi.loadJsonList(
  request: HttpRequest,
  requestName: String = request.logName(),
): GiteaResponse<List<T>> = sendAndRead(request, requestName) { stream, _ ->
  // Gitea answers some empty lists with `null`
  GiteaJson.mapper.readValue<List<T>?>(stream, GiteaJson.listType(T::class.java)) ?: emptyList()
}

/** Plain-text body (diffs, rendered markdown), UTF-8. */
suspend fun GiteaApi.loadText(request: HttpRequest, requestName: String = request.logName()): String =
  sendAndRead(request, requestName) { stream, _ -> stream.readBytes().toString(Charsets.UTF_8) }.body

suspend fun GiteaApi.loadText(uri: URI): String = loadText(request(uri).header("Accept", "text/plain").GET().build())

/** For endpoints answering `204 No Content` (or a body we don't need). */
suspend fun GiteaApi.sendIgnoringBody(request: HttpRequest, requestName: String = request.logName()): GiteaResponse<Unit> =
  sendAndRead<Unit>(request, requestName) { stream, _ -> stream.skip(Long.MAX_VALUE) }

suspend inline fun <reified T : Any> GiteaApi.getJson(uri: URI): T = loadJsonValue<T>(request(uri).GET().build()).body

suspend inline fun <reified T : Any> GiteaApi.getJsonList(uri: URI): GiteaResponse<List<T>> = loadJsonList<T>(request(uri).GET().build())

/** Sets a JSON body (serialized with [GiteaJson]) and the matching `Content-Type`. */
fun HttpRequest.Builder.jsonBody(method: String, body: Any): HttpRequest.Builder =
  header(CONTENT_TYPE_HEADER, JSON_CONTENT_TYPE).method(method, HttpRequest.BodyPublishers.ofByteArray(GiteaJson.toJsonBytes(body)))

/**
 * Query string builder; `null` values are skipped, lists become repeated keys (`labels=1&labels=2`).
 *
 * ```
 * server.apiUri("repos", owner, repo, "pulls").withQuery { "state" eq "open"; "page" eq 1 }
 * ```
 */
class GiteaQueryBuilder {
  private val params = mutableListOf<Pair<String, String>>()

  infix fun String.eq(value: String?) {
    if (value != null) {
      params += this to value
    }
  }

  infix fun String.eq(value: Number?) = eq(value?.toString())

  infix fun String.eq(value: Boolean?) = eq(value?.toString())

  infix fun String.eq(values: Collection<Any>?) {
    values?.forEach { params += this to it.toString() }
  }

  fun build(): String = params.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }

  private fun encode(s: String): String = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
}

fun URI.withQuery(block: GiteaQueryBuilder.() -> Unit): URI {
  val query = GiteaQueryBuilder().apply(block).build()
  if (query.isEmpty()) {
    return this
  }
  val base = toString().substringBefore('#')
  val separator = when (rawQuery) {
    null -> "?"
    else -> "&"
  }
  return URI.create(base + separator + query)
}
