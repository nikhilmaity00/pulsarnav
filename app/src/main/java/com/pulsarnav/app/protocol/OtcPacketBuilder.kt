package com.pulsarnav.app.protocol

import android.os.Build
import com.pulsarnav.app.model.Maneuver
import java.nio.charset.StandardCharsets

enum class CallState(val value: Int) {
    NO_CALL(0),
    INCOMING_CALL(1),
    OUTGOING_CALL(2),
    ACTIVE_CALL(3),
    END_CALL(4)
}

enum class AlertType(val value: Byte) {
    SMS(1),
    WHATSAPP(2)
}

/**
 * Encodes telemetry, turn-by-turn navigation, caller ID, and WhatsApp notifications
 * into OTC Engineering BLE packet frames for Bajaj Pulsar NS200 / Dominar consoles.
 */
object OtcPacketBuilder {

    const val TBT_PACKET_SIZE = 48
    const val GENERAL_PACKET_SIZE = 89
    const val ALERT_PACKET_SIZE = 44
    const val MISSED_CALL_PACKET_SIZE = 63

    private const val MAX_STREET_LEN = 31
    private const val MAX_NAME_LEN = 32
    private const val MAX_PHONE_LEN = 18

    private var heartbeatCounter: Byte = 0

    /**
     * Builds 48-byte Turn-by-Turn Navigation Packet for characteristic 0110.
     */
    fun buildTbtPacket(
        maneuver: Maneuver,
        distanceMeters: Int,
        streetName: String,
        etaMinutes: Int = 0
    ): ByteArray {
        val packet = ByteArray(TBT_PACKET_SIZE)

        // Byte 0: Header
        packet[0] = 0x01.toByte()

        // Bytes 1..6: Reserved (0x00)

        // Byte 7: Maneuver Opcode
        packet[7] = maneuver.opcode

        // Bytes 8..11: Distance in meters (Big Endian uint32)
        val clampedDist = distanceMeters.coerceAtLeast(0)
        packet[8] = ((clampedDist shr 24) and 0xFF).toByte()
        packet[9] = ((clampedDist shr 16) and 0xFF).toByte()
        packet[10] = ((clampedDist shr 8) and 0xFF).toByte()
        packet[11] = (clampedDist and 0xFF).toByte()

        // Bytes 12..13: ETA in minutes (Big Endian uint16)
        val clampedEta = etaMinutes.coerceAtLeast(0)
        packet[12] = ((clampedEta shr 8) and 0xFF).toByte()
        packet[13] = (clampedEta and 0xFF).toByte()

        // Byte 14: Street length
        val cleanStreet = sanitizeAscii(streetName, MAX_STREET_LEN)
        val streetBytes = cleanStreet.toByteArray(StandardCharsets.US_ASCII)
        val streetLen = streetBytes.size.coerceAtMost(MAX_STREET_LEN)
        packet[14] = streetLen.toByte()

        // Bytes 15..15+streetLen-1: Street name ASCII
        System.arraycopy(streetBytes, 0, packet, 15, streetLen)

        // Byte 47: Checksum of bytes 0..46
        var checksum = 0
        for (i in 0 until 47) {
            checksum = (checksum + (packet[i].toInt() and 0xFF)) and 0xFF
        }
        packet[47] = checksum.toByte()

        return packet
    }

    /**
     * Builds 89-byte General Status Packet for characteristic 0210.
     * Mirrors phone battery %, cellular signal bars, incoming caller ID, and call state.
     */
    fun buildGeneralPacketV2(
        batteryPercent: Int,
        isCharging: Boolean,
        signalBars: Int,
        callState: CallState,
        callerName: String? = null,
        callerNumber: String? = null,
        callAcceptAck: Boolean = false,
        callRejectAck: Boolean = false,
        missedCallCount: Int = 0
    ): ByteArray {
        val packet = ByteArray(GENERAL_PACKET_SIZE)

        // Byte 0: Volume / Status (0x00 for default)
        packet[0] = 0x00

        // Byte 1: Charging (bit 7), Battery 0..3 (bits 4..6), CallState 0..4 (bits 0..3)
        var byte1 = 0
        if (isCharging) {
            byte1 = byte1 or 0x80
        }
        // Map 0..100 battery into 0..3 bars for cluster indicator
        val batteryLevel = when {
            batteryPercent >= 75 -> 3
            batteryPercent >= 50 -> 2
            batteryPercent >= 20 -> 1
            else -> 0
        }
        byte1 = byte1 or ((batteryLevel and 0x07) shl 4)
        byte1 = byte1 or (callState.value and 0x0F)
        packet[1] = byte1.toByte()

        // Byte 2: Cellular signal bars (0..5)
        val clampedSignal = signalBars.coerceIn(0, 5)
        packet[2] = (clampedSignal and 0x07).toByte()

        // Byte 3: Protocol feature flags
        packet[3] = 0x0F.toByte()

        // Byte 4: Call accept ack
        if (callAcceptAck) packet[4] = 0x01

        // Byte 5: Call reject ack
        if (callRejectAck) packet[5] = 0x01

        // Byte 13: Missed call count
        packet[13] = (missedCallCount.coerceIn(0, 255)).toByte()

        // Byte 18: Caller presence flag
        val hasActiveCall = (callState == CallState.INCOMING_CALL || callState == CallState.ACTIVE_CALL)
        if (hasActiveCall && (!callerName.isNullOrBlank() || !callerNumber.isNullOrBlank())) {
            packet[18] = 0x01

            // Byte 19: Caller Name length
            val cleanName = sanitizeAscii(callerName ?: callerNumber.orEmpty(), MAX_NAME_LEN)
            val nameBytes = cleanName.toByteArray(StandardCharsets.UTF_8)
            val nameLen = nameBytes.size.coerceAtMost(MAX_NAME_LEN)
            packet[19] = nameLen.toByte()

            // Bytes 20..51: Caller Name (32 bytes)
            System.arraycopy(nameBytes, 0, packet, 20, nameLen)

            // Byte 52: Caller Number length
            val cleanPhone = sanitizePhone(callerNumber.orEmpty())
            val phoneBytes = cleanPhone.toByteArray(StandardCharsets.US_ASCII)
            val phoneLen = phoneBytes.size.coerceAtMost(MAX_PHONE_LEN)
            packet[52] = phoneLen.toByte()

            // Bytes 53..70: Caller Number (18 bytes)
            if (phoneLen > 0) {
                System.arraycopy(phoneBytes, 0, packet, 53, phoneLen)
            }
        }

        // Byte 72: Heartbeat counter
        heartbeatCounter = ((heartbeatCounter + 1) and 0xFF).toByte()
        packet[72] = heartbeatCounter

        // Byte 73: Phone Model Name length
        val model = sanitizeAscii(Build.MODEL, 14)
        val modelBytes = model.toByteArray(StandardCharsets.US_ASCII)
        val modelLen = modelBytes.size.coerceAtMost(14)
        packet[73] = modelLen.toByte()
        System.arraycopy(modelBytes, 0, packet, 74, modelLen)

        // Byte 88: Checksum of bytes 0..87
        var checksum = 0
        for (i in 0 until 88) {
            checksum = (checksum + (packet[i].toInt() and 0xFF)) and 0xFF
        }
        packet[88] = checksum.toByte()

        return packet
    }

    /**
     * Builds 44-byte WhatsApp Alert Packet for characteristic 0410.
     */
    fun buildWhatsAppAlertPacket(senderName: String, count: Int = 1): ByteArray {
        val packet = ByteArray(ALERT_PACKET_SIZE)

        // Byte 0: Alert type (WHATSAPP = 2)
        packet[0] = AlertType.WHATSAPP.value

        // Byte 1: Sender Name length
        val cleanName = sanitizeAscii(senderName, MAX_NAME_LEN)
        val nameBytes = cleanName.toByteArray(StandardCharsets.UTF_8)
        val nameLen = nameBytes.size.coerceAtMost(MAX_NAME_LEN)
        packet[1] = nameLen.toByte()

        // Bytes 2..33: Sender name
        System.arraycopy(nameBytes, 0, packet, 2, nameLen)

        // Bytes 34..37: Alert Count (Big Endian uint32)
        val clampedCount = count.coerceAtLeast(1)
        packet[34] = ((clampedCount shr 24) and 0xFF).toByte()
        packet[35] = ((clampedCount shr 16) and 0xFF).toByte()
        packet[36] = ((clampedCount shr 8) and 0xFF).toByte()
        packet[37] = (clampedCount and 0xFF).toByte()

        return packet
    }

    /**
     * Builds 63-byte Missed Call Packet for characteristic 0310.
     */
    fun buildMissedCallPacket(callerName: String, callerNumber: String, count: Int = 1): ByteArray {
        val packet = ByteArray(MISSED_CALL_PACKET_SIZE)

        // Byte 0: Header
        packet[0] = 0x01

        // Byte 1: Caller Name length
        val cleanName = sanitizeAscii(callerName, MAX_NAME_LEN)
        val nameBytes = cleanName.toByteArray(StandardCharsets.UTF_8)
        val nameLen = nameBytes.size.coerceAtMost(MAX_NAME_LEN)
        packet[1] = nameLen.toByte()

        // Bytes 2..33: Caller name
        System.arraycopy(nameBytes, 0, packet, 2, nameLen)

        // Byte 34: Phone length
        val cleanPhone = sanitizePhone(callerNumber)
        val phoneBytes = cleanPhone.toByteArray(StandardCharsets.US_ASCII)
        val phoneLen = phoneBytes.size.coerceAtMost(MAX_PHONE_LEN)
        packet[34] = phoneLen.toByte()

        // Bytes 35..52: Phone number
        System.arraycopy(phoneBytes, 0, packet, 35, phoneLen)

        // Bytes 53..56: Missed Call Count (Big Endian uint32)
        val clampedCount = count.coerceAtLeast(1)
        packet[53] = ((clampedCount shr 24) and 0xFF).toByte()
        packet[54] = ((clampedCount shr 16) and 0xFF).toByte()
        packet[55] = ((clampedCount shr 8) and 0xFF).toByte()
        packet[56] = (clampedCount and 0xFF).toByte()

        return packet
    }

    fun sanitizeAscii(raw: String?, maxLen: Int): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw
            .replace("[áÁ]".toRegex(), "a")
            .replace("[éÉ]".toRegex(), "e")
            .replace("[íÍ]".toRegex(), "i")
            .replace("[óÓ]".toRegex(), "o")
            .replace("[úÚ]".toRegex(), "u")
            .replace("[ñÑ]".toRegex(), "n")
            .replace("[^0-9a-zA-Z. _-]".toRegex(), " ")
            .trim()
            .replace("\\s+".toRegex(), " ")
        if (s.length > maxLen) {
            s = s.substring(0, maxLen)
        }
        return s
    }

    private fun sanitizePhone(phone: String?): String {
        if (phone.isNullOrBlank()) return ""
        var s = phone.replace("[^+0-9]".toRegex(), "").trim()
        if (s.length > MAX_PHONE_LEN) {
            s = s.substring(s.length - MAX_PHONE_LEN)
        }
        return s
    }
}
