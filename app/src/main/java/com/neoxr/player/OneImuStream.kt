package com.neoxr.player

import java.io.EOFException
import java.io.InputStream
import java.net.SocketTimeoutException

/** Bounded framing: TCP may split, combine or interleave IMU/magnetometer reports. */
internal class OneImuStream(private val input: InputStream) {
    private val bytes = ByteArray(ImuFrame.SIZE * 16)
    private var offset = 0
    private var count = 0
    var timestampNs = 0L
        private set

    fun next(timeoutMs: Long = 2500): FloatArray {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            while (count - offset >= ImuFrame.SIZE) {
                if (!ImuFrame.magicAt(bytes, offset)) { offset++; continue }
                val start = offset
                offset += ImuFrame.SIZE
                val sample = ImuFrame.parse(bytes, start) ?: continue
                timestampNs = ImuFrame.timestamp(bytes, start)
                return sample
            }
            System.arraycopy(bytes, offset, bytes, 0, count - offset)
            count -= offset; offset = 0
            val read = try { input.read(bytes, count, bytes.size - count) }
                catch (_: SocketTimeoutException) { continue }
            if (read < 0) throw EOFException("IMU stream closed")
            count += read
        }
        throw SocketTimeoutException("No valid IMU report")
    }
}

internal object OneImuEndpoints {
    fun hosts(localAddresses: List<String>): List<String> =
        listOf("169.254.2.1", "169.254.1.1").sortedByDescending { host ->
            localAddresses.any { it.substringBeforeLast('.') == host.substringBeforeLast('.') }
        }

    fun matches(local: String, host: String): Boolean =
        local.substringBeforeLast('.') == host.substringBeforeLast('.')
}
