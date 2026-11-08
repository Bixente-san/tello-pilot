package com.vincent.tellopilot.drone

import org.junit.Assert.assertEquals
import org.junit.Test

/** Vecteurs de référence calculés avec TelloPy / Gobot. */
class ProtocolTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test fun takeoff() = assertEquals(
        "cc58007c685400e5011a0f",
        hex(Protocol.packet(Protocol.TAKEOFF_CMD, 0x68, ByteArray(0), 0x01e5)),
    )

    @Test fun land() = assertEquals(
        "cc600027685500e60100de28",
        hex(Protocol.packet(Protocol.LAND_CMD, 0x68, byteArrayOf(0), 0x01e6)),
    )

    @Test fun flip() = assertEquals(
        "cc600027705c000700036c62",
        hex(Protocol.packet(Protocol.FLIP_CMD, 0x70, byteArrayOf(3), 7)),
    )

    @Test fun logAck() = assertEquals(
        "cc7000cb50501009000034125141",
        hex(Protocol.packet(Protocol.LOG_HEADER_MSG, 0x50, byteArrayOf(0, 0x34, 0x12), 9)),
    )

    @Test fun stick() = assertEquals(
        "ccb0007f60500000004a650ba501180c22381503821b",
        hex(Protocol.stick(0.5f, -1f, 1f, 0f, true, 12, 34, 56, 789)),
    )

    @Test fun throwAndGo() = assertEquals(
        "cc600027485d00230100b9c4",
        hex(Protocol.packet(Protocol.THROW_AND_GO_CMD, 0x48, byteArrayOf(0), 0x0123)),
    )

    @Test fun connReq() = assertEquals(
        "636f6e6e5f7265713a9617",
        hex(Protocol.connReq()),
    )
}
