package com.snipsearch.security

/**
 * Owned secret bytes. Callers must not retain or mutate the array passed to [read]
 * after [read] returns. Close the container to zero the bytes.
 */
class SecretBytes private constructor(
    bytes: ByteArray,
) : AutoCloseable {
    private var bytes: ByteArray? = bytes

    val isClosed: Boolean
        @Synchronized get() = bytes == null

    val size: Int
        @Synchronized get() = requireOpen().size

    /**
     * Passes the internal array to [block] without copying.
     * Callers must not retain or mutate the array beyond [block].
     */
    @Synchronized
    fun <R> read(block: (ByteArray) -> R): R = block(requireOpen())

    @Synchronized
    fun copyOf(): ByteArray = requireOpen().copyOf()

    @Synchronized
    override fun close() {
        MemoryScrubber.zero(bytes)
        bytes = null
    }

    override fun toString(): String = "SecretBytes(<redacted>)"

    private fun requireOpen(): ByteArray = bytes ?: throw IllegalStateException("SecretBytes is closed")

    companion object {
        fun adopt(owned: ByteArray): SecretBytes = SecretBytes(owned)
    }
}
