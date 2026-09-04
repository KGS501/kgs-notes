package com.kgs.notes.nextcloud

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class NextcloudApiException(
    val statusCode: Int,
) : IOException("NEXTCLOUD_HTTP_$statusCode")

class HttpNextcloudNotesApi(
    credentials: NextcloudCredentials,
    private val client: OkHttpClient = OkHttpClient(),
) : NextcloudNotesApi {
    private val serverUrl: HttpUrl = canonicalNextcloudServerAddress(credentials.serverUrl).toHttpUrlOrNull()
        ?: throw NextcloudConnectionException(NextcloudConnectionFailure.INVALID_SERVER_URL)
    private val authorization = Credentials.basic(credentials.loginName, credentials.appPassword)

    override suspend fun capabilities(): NotesCapabilities = withContext(Dispatchers.IO) {
        val endpoint = requireNotNull(serverUrl.resolve("ocs/v2.php/cloud/capabilities"))
            .newBuilder()
            .addQueryParameter("format", "json")
            .build()
        val request = authenticatedRequest(endpoint)
            .header("OCS-APIRequest", "true")
            .get()
            .build()
        val root = executeObject(request)
        val versions = try {
            root.getValue("ocs").jsonObject
                .getValue("data").jsonObject
                .getValue("capabilities").jsonObject
                .getValue("notes").jsonObject
                .getValue("api_version").jsonArray
                .map { it.jsonPrimitive.content }
        } catch (failure: Exception) {
            throw NextcloudConnectionException(NextcloudConnectionFailure.INVALID_RESPONSE, failure)
        }
        NotesCapabilities(
            apiVersion = versions
                .filter { it.substringBefore('.') == "1" }
                .maxWithOrNull(::compareApiVersions)
                ?: "0",
        )
    }

    override suspend fun notes(): List<RemoteNote> = withContext(Dispatchers.IO) {
        val result = mutableListOf<RemoteNote>()
        var cursor: String? = null
        var chunks = 0
        do {
            val endpointBuilder = requireNotNull(serverUrl.resolve("index.php/apps/notes/api/v1/notes"))
                .newBuilder()
                .addQueryParameter("chunkSize", CHUNK_SIZE.toString())
            cursor?.let { endpointBuilder.addQueryParameter("chunkCursor", it) }
            val request = authenticatedRequest(endpointBuilder.build()).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw NextcloudApiException(response.code)
                result += parseNotes(response.body.string())
                cursor = response.header("X-Notes-Chunk-Cursor")
            }
            chunks += 1
            if (chunks > MAX_CHUNKS) {
                throw NextcloudConnectionException(NextcloudConnectionFailure.INVALID_RESPONSE)
            }
        } while (cursor != null)
        result
    }

    override suspend fun note(id: Long): RemoteNote = withContext(Dispatchers.IO) {
        val endpoint = requireNotNull(serverUrl.resolve("index.php/apps/notes/api/v1/notes/$id"))
        parseNote(executeObject(authenticatedRequest(endpoint).get().build()))
    }

    override suspend fun create(note: RemoteNoteDraft): RemoteNote = withContext(Dispatchers.IO) {
        val endpoint = requireNotNull(serverUrl.resolve("index.php/apps/notes/api/v1/notes"))
        val body = note.requestBody()
        val request = authenticatedRequest(endpoint).post(body).build()
        parseNote(executeObject(request))
    }

    override suspend fun update(
        id: Long,
        expectedEtag: String,
        note: RemoteNoteDraft,
    ): RemoteNote = withContext(Dispatchers.IO) {
        val endpoint = requireNotNull(serverUrl.resolve("index.php/apps/notes/api/v1/notes/$id"))
        val request = authenticatedRequest(endpoint)
            .header("If-Match", expectedEtag)
            .put(note.requestBody())
            .build()
        parseNote(executeObject(request))
    }

    override suspend fun delete(id: Long): Unit = withContext(Dispatchers.IO) {
        val endpoint = requireNotNull(serverUrl.resolve("index.php/apps/notes/api/v1/notes/$id"))
        val request = authenticatedRequest(endpoint).delete().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw NextcloudApiException(response.code)
        }
    }

    override suspend fun updateSettings(fileSuffix: String): NotesSettings = withContext(Dispatchers.IO) {
        val endpoint = requireNotNull(serverUrl.resolve("index.php/apps/notes/api/v1/settings"))
        val body = buildJsonObject { put("fileSuffix", fileSuffix) }
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val root = executeObject(authenticatedRequest(endpoint).put(body).build())
        NotesSettings(root.requiredString("fileSuffix"))
    }

    private fun RemoteNoteDraft.requestBody() = buildJsonObject {
        put("title", title)
        put("content", markdown)
        put("category", category)
        put("favorite", favorite)
        put("modified", modifiedAt.epochSecond)
    }.toString().toRequestBody(JSON_MEDIA_TYPE)

    private fun authenticatedRequest(url: HttpUrl): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .header("Authorization", authorization)
        .header("User-Agent", USER_AGENT)

    private fun executeObject(request: Request): JsonObject = client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw NextcloudApiException(response.code)
        parseElement(response.body.string()).jsonObject
    }

    private fun parseNotes(value: String): List<RemoteNote> {
        val notes = try {
            parseElement(value).jsonArray
        } catch (failure: Exception) {
            throw NextcloudConnectionException(NextcloudConnectionFailure.INVALID_RESPONSE, failure)
        }
        return notes.map(::parseNote)
    }

    private fun parseNote(element: kotlinx.serialization.json.JsonElement): RemoteNote {
        val note = try {
            element.jsonObject
        } catch (failure: Exception) {
            throw NextcloudConnectionException(NextcloudConnectionFailure.INVALID_RESPONSE, failure)
        }
        return try {
            RemoteNote(
                id = note.requiredLong("id"),
                etag = note.requiredString("etag"),
                title = note.requiredString("title"),
                markdown = note.requiredString("content"),
                category = note.optionalString("category"),
                favorite = note["favorite"]?.jsonPrimitive?.booleanOrNull ?: false,
                modifiedAt = Instant.ofEpochSecond(note.requiredLong("modified")),
                readOnly = note["readonly"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        } catch (failure: NextcloudConnectionException) {
            throw failure
        } catch (failure: Exception) {
            throw NextcloudConnectionException(NextcloudConnectionFailure.INVALID_RESPONSE, failure)
        }
    }

    private fun parseElement(value: String) = try {
        JSON.parseToJsonElement(value)
    } catch (failure: Exception) {
        throw NextcloudConnectionException(NextcloudConnectionFailure.INVALID_RESPONSE, failure)
    }

    private fun JsonObject.requiredString(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.optionalString(name: String): String = get(name)?.jsonPrimitive?.content.orEmpty()

    private fun JsonObject.requiredLong(name: String): Long = getValue(name).jsonPrimitive.longOrNull
        ?: throw NextcloudConnectionException(NextcloudConnectionFailure.INVALID_RESPONSE)

    private companion object {
        const val USER_AGENT = "KGS Notes Android"
        const val CHUNK_SIZE = 100
        const val MAX_CHUNKS = 10_000
        val JSON = Json { ignoreUnknownKeys = true }
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun compareApiVersions(left: String, right: String): Int {
            val leftParts = left.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val rightParts = right.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val length = maxOf(leftParts.size, rightParts.size)
            repeat(length) { index ->
                val comparison = (leftParts.getOrNull(index) ?: 0).compareTo(rightParts.getOrNull(index) ?: 0)
                if (comparison != 0) return comparison
            }
            return 0
        }
    }
}
