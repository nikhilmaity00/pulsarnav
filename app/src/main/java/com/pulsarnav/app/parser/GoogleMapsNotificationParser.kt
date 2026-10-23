package com.pulsarnav.app.parser

import com.pulsarnav.app.model.Maneuver

data class ParsedNavigationStep(
    val maneuver: Maneuver,
    val distanceMeters: Int,
    val streetName: String,
    val rawDistanceText: String,
    val etaMinutes: Int
)

object GoogleMapsNotificationParser {

    private val KM_REGEX = "([0-9]+[.,]?[0-9]*)\\s*(?:km|kilometers)".toRegex(RegexOption.IGNORE_CASE)
    private val M_REGEX = "([0-9]+)\\s*(?:m|meters)(?!in)".toRegex(RegexOption.IGNORE_CASE)
    private val MI_REGEX = "([0-9]+[.,]?[0-9]*)\\s*(?:mi|miles)".toRegex(RegexOption.IGNORE_CASE)
    private val FT_REGEX = "([0-9]+)\\s*(?:ft|feet)".toRegex(RegexOption.IGNORE_CASE)
    private val ETA_MIN_REGEX = "([0-9]+)\\s*min".toRegex(RegexOption.IGNORE_CASE)

    fun parse(title: String?, text: String?, subtext: String?): ParsedNavigationStep? {
        val fullContent = "${title.orEmpty()} ${text.orEmpty()} ${subtext.orEmpty()}".lowercase()
        if (fullContent.isBlank()) return null

        // 1. Extract Distance
        var distanceMeters = 0
        var rawDist = ""

        val kmMatch = KM_REGEX.find(fullContent)
        val mMatch = M_REGEX.find(fullContent)
        val miMatch = MI_REGEX.find(fullContent)
        val ftMatch = FT_REGEX.find(fullContent)

        when {
            kmMatch != null -> {
                rawDist = kmMatch.value
                val num = kmMatch.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
                distanceMeters = (num * 1000).toInt()
            }
            mMatch != null -> {
                rawDist = mMatch.value
                distanceMeters = mMatch.groupValues[1].toIntOrNull() ?: 0
            }
            miMatch != null -> {
                rawDist = miMatch.value
                val num = miMatch.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
                distanceMeters = (num * 1609.34).toInt()
            }
            ftMatch != null -> {
                rawDist = ftMatch.value
                val num = ftMatch.groupValues[1].toIntOrNull() ?: 0
                distanceMeters = (num * 0.3048).toInt()
            }
        }

        // 2. Extract Maneuver
        val maneuver = when {
            fullContent.contains("destination") || fullContent.contains("arrived") -> Maneuver.DESTINATION_REACHED
            fullContent.contains("u-turn") || fullContent.contains("uturn") -> {
                if (fullContent.contains("left")) Maneuver.U_TURN_LEFT else Maneuver.U_TURN_RIGHT
            }
            fullContent.contains("roundabout") || fullContent.contains("rotary") -> {
                if (fullContent.contains("left")) Maneuver.ROUNDABOUT_LEFT else Maneuver.ROUNDABOUT_RIGHT
            }
            fullContent.contains("sharp left") -> Maneuver.TURN_SHARP_LEFT
            fullContent.contains("sharp right") -> Maneuver.TURN_SHARP_RIGHT
            fullContent.contains("slight left") || fullContent.contains("bear left") -> Maneuver.TURN_SLIGHT_LEFT
            fullContent.contains("slight right") || fullContent.contains("bear right") -> Maneuver.TURN_SLIGHT_RIGHT
            fullContent.contains("keep left") -> Maneuver.KEEP_LEFT
            fullContent.contains("keep right") -> Maneuver.KEEP_RIGHT
            fullContent.contains("fork") && fullContent.contains("left") -> Maneuver.FORK_LEFT
            fullContent.contains("fork") && fullContent.contains("right") -> Maneuver.FORK_RIGHT
            fullContent.contains("ramp") && fullContent.contains("left") -> Maneuver.RAMP_LEFT
            fullContent.contains("ramp") && fullContent.contains("right") -> Maneuver.RAMP_RIGHT
            fullContent.contains("turn left") || fullContent.contains("take a left") || fullContent.contains("left onto") -> Maneuver.TURN_LEFT
            fullContent.contains("turn right") || fullContent.contains("take a right") || fullContent.contains("right onto") -> Maneuver.TURN_RIGHT
            fullContent.contains("merge") -> Maneuver.MERGE
            else -> Maneuver.STRAIGHT
        }

        // 3. Extract Street / Road Name
        var street = text.orEmpty().trim()
        if (street.startsWith("toward ", ignoreCase = true)) {
            street = street.substring(7)
        } else if (street.startsWith("onto ", ignoreCase = true)) {
            street = street.substring(5)
        }

        // 4. Extract ETA
        val etaMatch = ETA_MIN_REGEX.find(fullContent)
        val etaMinutes = etaMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

        return ParsedNavigationStep(
            maneuver = maneuver,
            distanceMeters = distanceMeters,
            streetName = street,
            rawDistanceText = rawDist,
            etaMinutes = etaMinutes
        )
    }
}
