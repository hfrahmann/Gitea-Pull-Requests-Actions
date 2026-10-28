package de.hendrikfrahmann.giteaplugin.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.util.StdDateFormat
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.util.TimeZone

/**
 * Jackson 2 mapper for the Gitea REST API (Jackson comes from the platform; 2026.1 has no Jackson 3).
 *
 * - snake_case property names, unknown properties ignored (newer servers add fields)
 * - dates as `java.util.Date`, ISO-8601 with offset (`2026-10-16T16:59:07+02:00`); 2026.1 ships no jsr310 module
 * - JSON `null` for a Kotlin parameter with a default value uses the default (Gitea sends `null` for empty lists)
 * - `null` fields are not serialized
 */
object GiteaJson {
  val mapper: ObjectMapper = JsonMapper.builder()
    .addModule(
      KotlinModule.Builder()
        .enable(KotlinFeature.NullIsSameAsDefault)
        .enable(KotlinFeature.NullToEmptyCollection)
        .enable(KotlinFeature.NullToEmptyMap)
        .build()
    )
    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false)
    .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE, true)
    .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
    .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
    .defaultDateFormat(StdDateFormat().withColonInTimeZone(true))
    .defaultTimeZone(TimeZone.getDefault())
    .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
    .build()

  fun toJsonBytes(value: Any): ByteArray = mapper.writeValueAsBytes(value)

  fun toJsonString(value: Any): String = mapper.writeValueAsString(value)

  fun <T> fromJson(json: String, type: Class<T>): T = mapper.readValue(json, type)

  fun listType(elementType: Class<*>): JavaType = mapper.typeFactory.constructCollectionType(List::class.java, elementType)

  inline fun <reified T> fromJson(json: String): T = fromJson(json, T::class.java)
}
