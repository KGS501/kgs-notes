package com.kgs.notes.nextcloud

import okhttp3.ResponseBody
import okio.Buffer

/**
 * Upper bounds for what a Nextcloud server may make this app hold in memory. A larger
 * response, whether announced or streamed without a length (or expanded from gzip), is
 * rejected as [NextcloudConnectionFailure.INVALID_RESPONSE] before it is fully read, so a
 * malicious or broken server cannot exhaust the heap; the cached Notes stay untouched.
 */
internal object ResponseLimits {
    /** Login flow and capability documents are small JSON objects. */
    const val SMALL_DOCUMENT_BYTES: Long = 1L shl 20

    /** One page of Notes or one Note, after decompression. */
    const val NOTES_RESPONSE_BYTES: Long = 32L shl 20

    /** Every page of one listing together. */
    const val NOTES_LISTING_BYTES: Long = 128L shl 20
}

private const val READ_STEP = 8L * 1024

/** Reads at most [limit] decompressed bytes as text; one byte more fails the request. */
internal fun ResponseBody.boundedString(limit: Long): String {
    if (contentLength() > limit) throw NextcloudConnectionException(NextcloudConnectionFailure.INVALID_RESPONSE)
    val source = source()
    val buffer = Buffer()
    while (true) {
        val read = source.read(buffer, READ_STEP)
        if (read == -1L) break
        if (buffer.size > limit) throw NextcloudConnectionException(NextcloudConnectionFailure.INVALID_RESPONSE)
    }
    return buffer.readString(contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
}
