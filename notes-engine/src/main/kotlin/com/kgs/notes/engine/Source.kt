package com.kgs.notes.engine

@JvmInline
value class SourceId(val value: String)

data class SourceCapabilities(
    val markdown: Boolean,
    val categories: Boolean,
    val favorites: Boolean,
    val attachments: Boolean,
    val writable: Boolean,
)

data class SourceDescriptor(
    val id: SourceId,
    val name: String,
    val capabilities: SourceCapabilities,
)

/** External synchronization seam. Implementations own protocol choreography. */
interface Source {
    val descriptor: SourceDescriptor

    suspend fun refresh()
}
