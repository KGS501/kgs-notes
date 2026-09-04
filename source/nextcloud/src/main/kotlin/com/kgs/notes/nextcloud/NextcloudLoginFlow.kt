package com.kgs.notes.nextcloud

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class NextcloudLoginChallenge(
    val serverUrl: String,
    val loginUrl: String,
    val pollUrl: String,
    val token: String,
)

class NextcloudCredentials(
    val serverUrl: String,
    val loginName: String,
    val appPassword: String,
) {
    override fun toString(): String = "NextcloudCredentials(<redacted>)"
}

enum class NextcloudConnectionFailure {
    INVALID_SERVER_URL,
    INSECURE_SERVER,
    INVALID_RESPONSE,
    SERVER_REJECTED_REQUEST,
}

class NextcloudConnectionException(
    val failure: NextcloudConnectionFailure,
    cause: Throwable? = null,
) : IOException(failure.name, cause)

interface NextcloudLoginFlow {
    suspend fun begin(serverUrl: String): NextcloudLoginChallenge

    /** Returns null while authorization is still pending. */
    suspend fun poll(challenge: NextcloudLoginChallenge): NextcloudCredentials?
}

fun canonicalNextcloudServerAddress(value: String): String = canonicalServerUrl(value).toString()

class HttpNextcloudLoginFlow(
    private val client: OkHttpClient = OkHttpClient(),
) : NextcloudLoginFlow {
    override suspend fun begin(serverUrl: String): NextcloudLoginChallenge = withContext(Dispatchers.IO) {
        val canonicalServer = canonicalServerUrl(serverUrl)
        val endpoint = canonicalServer.resolve("index.php/login/v2")
            ?: throw NextcloudConnectionException(NextcloudConnectionFailure.INVALID_SERVER_URL)
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .post(ByteArray(0).toRequestBody())
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw NextcloudConnectionException(NextcloudConnectionFailure.SERVER_REJECTED_REQUEST)
            }
            val root = parseObject(response.body.string())
            val poll = root["poll"]?.jsonObject
                ?: throw NextcloudConnectionException(NextcloudConnectionFailure.INVALID_RESPONSE)
            val token = poll.string("token")
            val pollUrl = requireHttpsUrl(poll.string("endpoint"))
            val loginUrl = requireHttpsUrl(root.string("login"))
            NextcloudLoginChallenge(
                serverUrl = canonicalServer.toString(),
                loginUrl = loginUrl.toString(),
                pollUrl = pollUrl.toString(),
                token = token,
            )
        }
    }

    override suspend fun poll(challenge: NextcloudLoginChallenge): NextcloudCredentials? = withContext(Dispatchers.IO) {
        val pollUrl = requireHttpsUrl(challenge.pollUrl)
        val body = "token=${java.net.URLEncoder.encode(challenge.token, Charsets.UTF_8.name())}"
            .toRequestBody(FORM_MEDIA_TYPE)
        val request = Request.Builder()
            .url(pollUrl)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) {
                throw NextcloudConnectionException(NextcloudConnectionFailure.SERVER_REJECTED_REQUEST)
            }
            val root = parseObject(response.body.string())
            NextcloudCredentials(
                serverUrl = requireHttpsUrl(root.string("server")).toString(),
                loginName = root.string("loginName"),
                appPassword = root.string("appPassword"),
            )
        }
    }

    private fun requireHttpsUrl(value: String): HttpUrl {
        val parsed = value.toHttpUrlOrNull()
            ?: throw NextcloudConnectionException(NextcloudConnectionFailure.INVALID_RESPONSE)
        if (parsed.scheme != "https" || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw NextcloudConnectionException(NextcloudConnectionFailure.INVALID_RESPONSE)
        }
        return parsed
    }

    private fun parseObject(value: String) = try {
        JSON.parseToJsonElement(value).jsonObject
    } catch (failure: Exception) {
        throw NextcloudConnectionException(NextcloudConnectionFailure.INVALID_RESPONSE, failure)
    }

    private fun kotlinx.serialization.json.JsonObject.string(name: String): String = try {
        getValue(name).jsonPrimitive.content.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException()
    } catch (failure: Exception) {
        throw NextcloudConnectionException(NextcloudConnectionFailure.INVALID_RESPONSE, failure)
    }

    private companion object {
        const val USER_AGENT = "KGS Notes Android"
        val JSON = Json { ignoreUnknownKeys = true }
        val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
    }
}

private fun canonicalServerUrl(value: String): HttpUrl {
    val trimmed = value.trim()
    val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val parsed = candidate.toHttpUrlOrNull()
        ?: throw NextcloudConnectionException(NextcloudConnectionFailure.INVALID_SERVER_URL)
    if (parsed.scheme != "https") {
        throw NextcloudConnectionException(NextcloudConnectionFailure.INSECURE_SERVER)
    }
    if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.query != null || parsed.fragment != null) {
        throw NextcloudConnectionException(NextcloudConnectionFailure.INVALID_SERVER_URL)
    }
    val path = parsed.encodedPath.trimEnd('/') + "/"
    return parsed.newBuilder().encodedPath(path).build()
}
