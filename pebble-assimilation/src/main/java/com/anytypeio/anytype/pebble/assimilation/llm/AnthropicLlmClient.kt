package com.anytypeio.anytype.pebble.assimilation.llm

import com.anytypeio.anytype.pebble.assimilation.model.ExtractionResult
import com.anytypeio.anytype.pebble.assimilation.model.ExtractedEntity
import com.anytypeio.anytype.pebble.assimilation.model.ExtractedRelationship
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.net.ConnectException
import java.net.SocketTimeoutException

private const val TAG = "Pebble:Assimilation"
private const val ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages"
private const val ANTHROPIC_VERSION = "2023-06-01"

/**
 * LLM client backed by the Anthropic Messages API (Claude).
 *
 * Uses tool-use / structured JSON output to guarantee a parseable extraction response.
 */
class AnthropicLlmClient(private val config: LlmClientConfig) : LlmClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = config.timeoutMs
            connectTimeoutMillis = config.timeoutMs
        }
    }

    override suspend fun extractEntities(
        systemPrompt: String,
        userInput: String
    ): ExtractionResult {
        if (config.apiKey.isBlank()) throw LlmException.AuthException("Anthropic API key is not configured")

        val requestBody = buildRequest(systemPrompt, userInput)
        Timber.tag(TAG).d("Calling Anthropic API | model=${config.model} | inputLen=${userInput.length}")

        return try {
            val response = httpClient.post(ANTHROPIC_API_URL) {
                contentType(ContentType.Application.Json)
                header("x-api-key", config.apiKey)
                header("anthropic-version", ANTHROPIC_VERSION)
                setBody(requestBody)
            }

            when (response.status) {
                HttpStatusCode.OK -> {
                    val rawBody = response.bodyAsText()
                    parseAnthropicResponse(rawBody, userInput)
                }
                HttpStatusCode.Unauthorized -> throw LlmException.AuthException("Anthropic API key rejected (401)")
                HttpStatusCode.TooManyRequests -> throw LlmException.RateLimitException("Anthropic rate limit exceeded (429)")
                else -> {
                    val body = response.bodyAsText()
                    throw LlmException.ApiException(response.status.value, body.take(200))
                }
            }
        } catch (e: LlmException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw LlmException.TimeoutException("Anthropic request timed out after ${config.timeoutMs}ms", e)
        } catch (e: ConnectException) {
            throw LlmException.NetworkException("Cannot reach Anthropic API: ${e.message}", e)
        } catch (e: Exception) {
            throw LlmException.NetworkException("Unexpected error: ${e.message}", e)
        }
    }

    // ── Request building ─────────────────────────────────────────────────────

    private fun buildRequest(systemPrompt: String, userInput: String): AnthropicRequest {
        return AnthropicRequest(
            model = config.model,
            maxTokens = config.maxTokens,
            system = systemPrompt,
            messages = listOf(AnthropicMessage(role = "user", content = userInput)),
            tools = listOf(extractionTool())
        )
    }

    private fun extractionTool(): AnthropicTool {
        return AnthropicTool(
            name = "extract_entities",
            description = "Extract entities and relationships from user input according to the PKM taxonomy.",
            inputSchema = EXTRACTION_SCHEMA
        )
    }

    // ── Response parsing ────────────────────────────────────────────────────

    private fun parseAnthropicResponse(rawBody: String, originalInput: String): ExtractionResult {
        return try {
            val responseJson = json.parseToJsonElement(rawBody).jsonObject
            val content = responseJson["content"]?.jsonArray
                ?: return emptyResult(rawBody, originalInput)

            // Find the tool_use block
            val toolUseBlock = content.firstOrNull { block ->
                block.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool_use"
            }?.jsonObject

            if (toolUseBlock == null) {
                // Fallback: try to extract from text block
                val textBlock = content.firstOrNull { block ->
                    block.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text"
                }?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                return if (textBlock != null) parseJsonText(textBlock, rawBody) else emptyResult(rawBody, originalInput)
            }

            val toolInput = toolUseBlock["input"]?.jsonObject
                ?: return emptyResult(rawBody, originalInput)

            val entities = parseEntities(toolInput["entities"]?.jsonArray)
            val relationships = parseRelationships(toolInput["relationships"]?.jsonArray)
            val confidence = toolInput["overall_confidence"]?.jsonPrimitive?.floatOrNull ?: 1.0f
            val model = responseJson["model"]?.jsonPrimitive?.contentOrNull ?: config.model

            ExtractionResult(
                entities = entities,
                relationships = relationships,
                overallConfidence = confidence,
                rawResponse = rawBody,
                modelVersion = model
            )
        } catch (e: Exception) {
            throw LlmException.ParseException("Failed to parse Anthropic response: ${e.message}", e)
        }
    }

    private fun parseJsonText(text: String, rawBody: String): ExtractionResult {
        return try {
            val parsed = json.parseToJsonElement(text).jsonObject
            val entities = parseEntities(parsed["entities"]?.jsonArray)
            val relationships = parseRelationships(parsed["relationships"]?.jsonArray)
            ExtractionResult(entities = entities, relationships = relationships, rawResponse = rawBody)
        } catch (e: Exception) {
            throw LlmException.ParseException("Failed to parse JSON text block: ${e.message}", e)
        }
    }

    private fun parseEntities(array: JsonArray?): List<ExtractedEntity> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            runCatching {
                val obj = element.jsonObject
                ExtractedEntity(
                    localRef = obj["local_ref"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    typeKey = obj["type_key"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    attributes = parseStringMap(obj["attributes"]?.jsonObject),
                    confidence = obj["confidence"]?.jsonPrimitive?.floatOrNull ?: 1.0f
                )
            }.getOrNull()
        }
    }

    private fun parseRelationships(array: JsonArray?): List<ExtractedRelationship> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            runCatching {
                val obj = element.jsonObject
                ExtractedRelationship(
                    fromLocalRef = obj["from_local_ref"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    toLocalRef = obj["to_local_ref"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    relationKey = obj["relation_key"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    confidence = obj["confidence"]?.jsonPrimitive?.floatOrNull ?: 1.0f
                )
            }.getOrNull()
        }
    }

    private fun parseStringMap(obj: JsonObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        return obj.entries.mapNotNull { (k, v) ->
            val value = v.jsonPrimitive.contentOrNull ?: return@mapNotNull null
            k to value
        }.toMap()
    }

    private fun emptyResult(rawBody: String, originalInput: String): ExtractionResult =
        ExtractionResult(emptyList(), emptyList(), rawResponse = rawBody)

    // ── Wire serialization models ────────────────────────────────────────────

    @Serializable
    private data class AnthropicRequest(
        val model: String,
        @SerialName("max_tokens") val maxTokens: Int,
        val system: String,
        val messages: List<AnthropicMessage>,
        val tools: List<AnthropicTool>
    )

    @Serializable
    private data class AnthropicMessage(val role: String, val content: String)

    @Serializable
    private data class AnthropicTool(
        val name: String,
        val description: String,
        @SerialName("input_schema") val inputSchema: JsonElement
    )

    companion object {
        /** JSON Schema for the extraction tool — describes expected LLM output structure. */
        private val EXTRACTION_SCHEMA: JsonElement by lazy {
            Json.parseToJsonElement(
                """
                {
                  "type": "object",
                  "required": ["entities", "relationships"],
                  "properties": {
                    "entities": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "required": ["local_ref", "type_key", "name"],
                        "properties": {
                          "local_ref":  { "type": "string" },
                          "type_key":   { "type": "string" },
                          "name":       { "type": "string" },
                          "attributes": { "type": "object", "additionalProperties": { "type": "string" } },
                          "confidence": { "type": "number", "minimum": 0, "maximum": 1 }
                        }
                      }
                    },
                    "relationships": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "required": ["from_local_ref", "to_local_ref", "relation_key"],
                        "properties": {
                          "from_local_ref": { "type": "string" },
                          "to_local_ref":   { "type": "string" },
                          "relation_key":   { "type": "string" },
                          "confidence":     { "type": "number", "minimum": 0, "maximum": 1 }
                        }
                      }
                    },
                    "overall_confidence": { "type": "number", "minimum": 0, "maximum": 1 }
                  }
                }
                """.trimIndent()
            )
        }
    }
}
