package com.anytypeio.anytype.pebble.assimilation.llm

import com.anytypeio.anytype.pebble.assimilation.model.ExtractionResult
import com.anytypeio.anytype.pebble.assimilation.model.ExtractedEntity
import com.anytypeio.anytype.pebble.assimilation.model.ExtractedRelationship
import io.ktor.client.HttpClient
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
private const val OPENAI_API_URL = "https://api.openai.com/v1/chat/completions"

/**
 * LLM client backed by the OpenAI Chat Completions API (GPT-4o and variants).
 *
 * Uses JSON Schema response_format to guarantee parseable extraction output.
 */
class OpenAiLlmClient(private val config: LlmClientConfig) : LlmClient {

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
        if (config.apiKey.isBlank()) throw LlmException.AuthException("OpenAI API key is not configured")

        val requestBody = buildRequest(systemPrompt, userInput)
        Timber.tag(TAG).d("Calling OpenAI API | model=${config.model} | inputLen=${userInput.length}")

        return try {
            val response = httpClient.post(OPENAI_API_URL) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer ${config.apiKey}")
                setBody(requestBody)
            }

            when (response.status) {
                HttpStatusCode.OK -> {
                    val rawBody = response.bodyAsText()
                    parseOpenAiResponse(rawBody)
                }
                HttpStatusCode.Unauthorized -> throw LlmException.AuthException("OpenAI API key rejected (401)")
                HttpStatusCode.TooManyRequests -> throw LlmException.RateLimitException("OpenAI rate limit exceeded (429)")
                else -> {
                    val body = response.bodyAsText()
                    throw LlmException.ApiException(response.status.value, body.take(200))
                }
            }
        } catch (e: LlmException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw LlmException.TimeoutException("OpenAI request timed out after ${config.timeoutMs}ms", e)
        } catch (e: ConnectException) {
            throw LlmException.NetworkException("Cannot reach OpenAI API: ${e.message}", e)
        } catch (e: Exception) {
            throw LlmException.NetworkException("Unexpected error: ${e.message}", e)
        }
    }

    // ── Request building ─────────────────────────────────────────────────────

    private fun buildRequest(systemPrompt: String, userInput: String): OpenAiRequest {
        return OpenAiRequest(
            model = config.model,
            maxTokens = config.maxTokens,
            messages = listOf(
                OpenAiMessage(role = "system", content = systemPrompt),
                OpenAiMessage(role = "user", content = userInput)
            ),
            responseFormat = RESPONSE_FORMAT
        )
    }

    // ── Response parsing ────────────────────────────────────────────────────

    private fun parseOpenAiResponse(rawBody: String): ExtractionResult {
        return try {
            val responseJson = json.parseToJsonElement(rawBody).jsonObject
            val content = responseJson["choices"]
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")
                ?.jsonObject?.get("content")
                ?.jsonPrimitive?.contentOrNull
                ?: return ExtractionResult(emptyList(), emptyList(), rawResponse = rawBody)

            val parsed = json.parseToJsonElement(content).jsonObject
            val entities = parsed["entities"]?.jsonArray?.mapNotNull { element ->
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
            } ?: emptyList()

            val relationships = parsed["relationships"]?.jsonArray?.mapNotNull { element ->
                runCatching {
                    val obj = element.jsonObject
                    ExtractedRelationship(
                        fromLocalRef = obj["from_local_ref"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        toLocalRef = obj["to_local_ref"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        relationKey = obj["relation_key"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        confidence = obj["confidence"]?.jsonPrimitive?.floatOrNull ?: 1.0f
                    )
                }.getOrNull()
            } ?: emptyList()

            val model = responseJson["model"]?.jsonPrimitive?.contentOrNull ?: config.model
            val confidence = parsed["overall_confidence"]?.jsonPrimitive?.floatOrNull ?: 1.0f

            ExtractionResult(
                entities = entities,
                relationships = relationships,
                overallConfidence = confidence,
                rawResponse = rawBody,
                modelVersion = model
            )
        } catch (e: Exception) {
            throw LlmException.ParseException("Failed to parse OpenAI response: ${e.message}", e)
        }
    }

    private fun parseStringMap(obj: JsonObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        return obj.entries.mapNotNull { (k, v) ->
            val value = v.jsonPrimitive.contentOrNull ?: return@mapNotNull null
            k to value
        }.toMap()
    }

    // ── Wire serialization models ────────────────────────────────────────────

    @Serializable
    private data class OpenAiRequest(
        val model: String,
        @SerialName("max_tokens") val maxTokens: Int,
        val messages: List<OpenAiMessage>,
        @SerialName("response_format") val responseFormat: JsonElement
    )

    @Serializable
    private data class OpenAiMessage(val role: String, val content: String)

    companion object {
        private val RESPONSE_FORMAT: JsonElement by lazy {
            Json.parseToJsonElement(
                """
                {
                  "type": "json_schema",
                  "json_schema": {
                    "name": "extraction_result",
                    "strict": true,
                    "schema": {
                      "type": "object",
                      "required": ["entities", "relationships"],
                      "additionalProperties": false,
                      "properties": {
                        "entities": {
                          "type": "array",
                          "items": {
                            "type": "object",
                            "required": ["local_ref", "type_key", "name", "attributes", "confidence"],
                            "additionalProperties": false,
                            "properties": {
                              "local_ref":  { "type": "string" },
                              "type_key":   { "type": "string" },
                              "name":       { "type": "string" },
                              "attributes": { "type": "object", "additionalProperties": { "type": "string" } },
                              "confidence": { "type": "number" }
                            }
                          }
                        },
                        "relationships": {
                          "type": "array",
                          "items": {
                            "type": "object",
                            "required": ["from_local_ref", "to_local_ref", "relation_key", "confidence"],
                            "additionalProperties": false,
                            "properties": {
                              "from_local_ref": { "type": "string" },
                              "to_local_ref":   { "type": "string" },
                              "relation_key":   { "type": "string" },
                              "confidence":     { "type": "number" }
                            }
                          }
                        },
                        "overall_confidence": { "type": "number" }
                      }
                    }
                  }
                }
                """.trimIndent()
            )
        }
    }
}
