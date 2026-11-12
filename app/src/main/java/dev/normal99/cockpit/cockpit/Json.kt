package dev.normal99.cockpit.cockpit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Lenient accessors for the loosely typed JSON that Cockpit and the status files use. */
val json = Json { ignoreUnknownKeys = true; isLenient = true }

fun parseObject(text: String): JsonObject? =
    runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
fun JsonElement?.prim(): JsonPrimitive? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }

fun JsonObject?.str(key: String): String? = this?.get(key).prim()?.content
fun JsonObject?.long(key: String): Long? = this?.get(key).prim()?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
fun JsonObject?.int(key: String): Int? = long(key)?.toInt()
fun JsonObject?.double(key: String): Double? = this?.get(key).prim()?.doubleOrNull
fun JsonObject?.bool(key: String): Boolean? = this?.get(key).prim()?.booleanOrNull
fun JsonObject?.objAt(key: String): JsonObject? = this?.get(key).obj()
fun JsonObject?.arrAt(key: String): JsonArray = this?.get(key).arr()
fun JsonObject?.strings(key: String): List<String> = arrAt(key).mapNotNull { it.prim()?.content }

fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.filter { it.second != null }.associate { it.first to toJson(it.second) })

fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to toJson(it.value) })
    is Iterable<*> -> JsonArray(v.map { toJson(it) })
    is Array<*> -> JsonArray(v.map { toJson(it) })
    else -> JsonPrimitive(v.toString())
}
