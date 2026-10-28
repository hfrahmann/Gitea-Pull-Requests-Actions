package de.hendrikfrahmann.giteaplugin

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Checks the JUnit 5 + MockWebServer setup that the API tests build on. */
class MockWebServerSmokeTest {
  private lateinit var server: MockWebServer

  @BeforeEach
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @AfterEach
  fun tearDown() {
    server.close()
  }

  @Test
  fun `serves version endpoint`() {
    server.enqueue(MockResponse.Builder().body("""{"version":"28.1.0"}""").addHeader("Content-Type", "application/json").build())

    val request = HttpRequest.newBuilder(URI.create(server.url("/api/v1/version").toString())).build()
    val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

    assertEquals(200, response.statusCode())
    assertEquals("""{"version":"28.1.0"}""", response.body())
    assertEquals("/api/v1/version", server.takeRequest().target)
  }
}
