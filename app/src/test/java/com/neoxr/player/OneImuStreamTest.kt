package com.neoxr.player

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OneImuStreamTest {
    private fun frame(type: Int = 0x0b, time: Long = 123_000_000, alternate: Boolean = false): ByteArray {
        val data = ByteArray(ImuFrame.SIZE)
        ImuFrame.MAGIC.copyInto(data)
        if (alternate) data[0] = 0x27
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(14, time); putInt(30, type)
            putFloat(34, 0.1f); putFloat(38, 0.2f); putFloat(42, 0.3f)
            putFloat(54, 9.8f)
        }
        // Deliberately zero at offset 78: 1S does not promise the old marker.
        return data
    }

    @Test fun recognizes1sTypeWithoutFirmwareSpecificPaddingAndAcceptsBothHeaders() {
        for (alternate in listOf(false, true)) {
            val data = frame(alternate = alternate)
            assertTrue(ImuFrame.magicAt(data, 0))
            assertEquals(0.2f, ImuFrame.parse(data, 0)!![1], 0.0001f)
            assertEquals(123_000_000, ImuFrame.timestamp(data, 0))
        }
        assertNull(ImuFrame.parse(frame(type = 4), 0))
        assertNull(ImuFrame.parse(ByteArray(10), 0))
        assertFalse(ImuFrame.magicAt(frame(), -1))
    }

    @Test fun resynchronizesFragmentedTcpAndInterleavedMagnetometerReports() {
        val raw = ByteArray(161) { 0x55 } + frame(4) + frame(time = 5000) + frame(time = 6000, alternate = true)
        val input = object : ByteArrayInputStream(raw) {
            override fun read(buffer: ByteArray, off: Int, len: Int): Int = super.read(buffer, off, minOf(len, 7))
        }
        val reader = OneImuStream(input)
        assertEquals(9.8f, reader.next()[5], 0.0001f); assertEquals(5000, reader.timestampNs)
        assertEquals(0.1f, reader.next()[0], 0.0001f); assertEquals(6000, reader.timestampNs)
        assertThrows(EOFException::class.java) { reader.next() }
    }

    @Test fun aConnectedSocketWithNoValidSensorReportsTimesOutInsteadOfClaimingSuccess() {
        val input = object : InputStream() {
            override fun read(): Int = throw SocketTimeoutException()
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw SocketTimeoutException()
        }
        assertThrows(SocketTimeoutException::class.java) { OneImuStream(input).next(timeoutMs = 5) }
    }

    @Test fun prefersThePresentUsbSubnetAndNeverBindsItsAddressToTheOtherSubnet() {
        assertEquals(listOf("169.254.1.1", "169.254.2.1"), OneImuEndpoints.hosts(listOf("169.254.1.10")))
        assertEquals(listOf("169.254.2.1", "169.254.1.1"), OneImuEndpoints.hosts(emptyList()))
        assertTrue(OneImuEndpoints.matches("169.254.1.10", "169.254.1.1"))
        assertFalse(OneImuEndpoints.matches("169.254.2.10", "169.254.1.1"))
    }
}
