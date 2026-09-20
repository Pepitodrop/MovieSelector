package com.luisbenedikt.movieselector.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Same wire format as the backend's Routes.kt DTOs -- see that file for the source of truth. */
@Serializable
data class MovieDto(
    val id: String,
    val title: String,
    val year: Int? = null,
    val runtimeMinutes: Int? = null,
    val rating: Double? = null,
    val posterUrl: String? = null,
    val providers: List<String> = emptyList(),
)

@Serializable
data class SessionStateDto(
    val sessionId: String,
    val remaining: Int,
    val isExhausted: Boolean,
    val currentMovie: MovieDto? = null,
)

@Serializable
data class AcceptedDto(val movie: MovieDto)

@Serializable
data class ErrorDto(val error: String)

@Serializable
data class FiltersRequest(val providers: List<String>, val runtime: String)

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val message: String) : ApiResult<Nothing>
}

interface MovieApi {
    suspend fun startSession(providers: List<String>, runtime: String): ApiResult<SessionStateDto>
    suspend fun action(sessionId: String, action: String): ApiResult<SessionStateDto>
    suspend fun accept(sessionId: String): ApiResult<AcceptedDto>
}

/** Talks only to our own backend (see [BuildConfig.API_BASE_URL]); never touches Wencke directly. */
class MovieSelectorApi(private val baseUrl: String) : MovieApi {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) { requestTimeoutMillis = 10_000 }
    }

    override suspend fun startSession(providers: List<String>, runtime: String): ApiResult<SessionStateDto> =
        call { client.post("$baseUrl/session") { contentType(ContentType.Application.Json); setBody(FiltersRequest(providers, runtime)) } }

    override suspend fun action(sessionId: String, action: String): ApiResult<SessionStateDto> =
        call { client.post("$baseUrl/session/$sessionId/$action") }

    override suspend fun accept(sessionId: String): ApiResult<AcceptedDto> =
        call { client.post("$baseUrl/session/$sessionId/accept") }

    fun health(): String = "$baseUrl/health"

    private suspend inline fun <reified T> call(request: () -> HttpResponse): ApiResult<T> {
        val response = try {
            request()
        } catch (e: Exception) {
            return ApiResult.Failure("Could not reach the server. Check your connection and try again.")
        }
        return if (response.status.isSuccess()) {
            try {
                ApiResult.Success(response.body<T>())
            } catch (e: Exception) {
                ApiResult.Failure("The server sent back something unexpected.")
            }
        } else {
            val message = try {
                response.body<ErrorDto>().error
            } catch (e: Exception) {
                "Request failed (HTTP ${response.status.value})"
            }
            ApiResult.Failure(message)
        }
    }
}
