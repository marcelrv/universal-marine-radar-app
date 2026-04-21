package com.marineyachtradar.mayara.data.model

// ---------------------------------------------------------------------------
// Connection
// ---------------------------------------------------------------------------

/** How the app connects to the radar server. */
sealed interface ConnectionMode {
    /** mayara-server runs in-process via JNI on localhost:[port]. */
    data class Embedded(val port: Int = 6502, val emulator: Boolean = false) : ConnectionMode

    /** Connect to a remote mayara-server or SignalK node. */
    data class Network(val baseUrl: String) : ConnectionMode

    /** Replay a PCAP file using the embedded server for demo/testing. */
    data class PcapDemo(val pcapPath: String, val port: Int = 6502, val repeat: Boolean = true) : ConnectionMode
}

data class DiscoveredServer(
    val name: String,
    val host: String,
    val port: Int,
    val isSignalK: Boolean,
) {
    val baseUrl: String get() = "http://$host:$port"
}

// ---------------------------------------------------------------------------
// Radar discovery
// ---------------------------------------------------------------------------

data class RadarInfo(
    val id: String,
    val name: String,
    val brand: String,
    val spokeDataUrl: String,
)

// ---------------------------------------------------------------------------
// Capabilities (from GET /radars/{id}/capabilities)
// ---------------------------------------------------------------------------

data class RadarCapabilities(
    val radarId: String,
    /** Available range steps in metres (ordered ascending). */
    val ranges: List<Int>,
    val spokesPerRevolution: Int,
    val maxSpokeLength: Int,
    val controls: Map<String, ControlDefinition>,
    /** Server-provided colour legend for mapping spoke byte indices to RGBA. */
    val legend: RadarLegend? = null,
)

/**
 * Server-provided colour legend for the radar display.
 *
 * Each spoke byte is an index into [pixels]. Index 0 is always transparent (no return).
 * The server generates a blue→green→red gradient for normal returns, plus special
 * entries for Doppler/history if supported by the hardware.
 */
data class RadarLegend(
    /** Indexed colour table: spoke byte value → RGBA colour. */
    val pixels: List<LegendPixel>,
    /** Number of "normal intensity" colour entries (excludes special entries). */
    val pixelColors: Int,
    val lowReturn: Int,
    val mediumReturn: Int,
    val strongReturn: Int,
)

data class LegendPixel(
    /** CSS-style hex colour string, e.g. "#ff0000ff". */
    val color: String,
    /** Pixel type: "Normal", "DopplerApproaching", "DopplerReceding", "History", etc. */
    val type: String,
)

data class ControlDefinition(
    val id: String,
    val name: String,
    val type: ControlType,
    val category: String? = null,
    val units: String? = null,
    val minValue: Float? = null,
    val maxValue: Float? = null,
    val supportsAuto: Boolean = false,
    val options: List<String> = emptyList(),
)

enum class ControlType { RANGE_SLIDER, ENUM, BOOLEAN, STRING }

// ---------------------------------------------------------------------------
// Spoke data (decoded from RadarMessage protobuf)
// ---------------------------------------------------------------------------

data class SpokeData(
    /** Spoke angle in range [0, spokesPerRevolution). */
    val angle: Int,
    /** Optional true bearing in range [0, spokesPerRevolution). */
    val bearing: Int?,
    /** Range in metres of last pixel in [data]. */
    val rangeMetres: Int,
    /** Raw radar intensity bytes: index 0 = nearest, last = farthest. */
    val data: ByteArray,
    /** Latitude of the radar at spoke generation time (degrees). */
    val lat: Double? = null,
    /** Longitude of the radar at spoke generation time (degrees). */
    val lon: Double? = null,
) {
    // ByteArray requires manual equals/hashCode to avoid identity comparison.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpokeData) return false
        return angle == other.angle &&
                bearing == other.bearing &&
                rangeMetres == other.rangeMetres &&
                lat == other.lat &&
                lon == other.lon &&
                data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = angle
        result = 31 * result + (bearing ?: 0)
        result = 31 * result + rangeMetres
        result = 31 * result + (lat?.hashCode() ?: 0)
        result = 31 * result + (lon?.hashCode() ?: 0)
        result = 31 * result + data.contentHashCode()
        return result
    }
}

// ---------------------------------------------------------------------------
// UI State
// ---------------------------------------------------------------------------

/** Top-level state observed by all Compose screens. */
sealed interface RadarUiState {
    /** No connection yet / initial state. */
    data object Loading : RadarUiState

    /** Connection established; capabilities loaded; radar ready. */
    data class Connected(
        val radar: RadarInfo,
        val capabilities: RadarCapabilities,
        val controls: ControlsState,
        val powerState: PowerState,
        val currentRangeIndex: Int,
        val navigationData: NavigationData?,
        val connectionLabel: String = "",
    ) : RadarUiState

    /** Server unreachable or fatal error. */
    data class Error(val message: String) : RadarUiState
}

data class ControlsState(
    val gain: SliderControlState,
    val seaClutter: SliderControlState,
    val rainClutter: SliderControlState?,   // null → not supported by radar
    val interferenceRejection: EnumControlState?,
    val palette: ColorPalette,
    val orientation: RadarOrientation,
    /** Fill angular gaps between received spokes by repeating spoke data. Default on. */
    val spokeGapFill: Boolean = true,
)

data class SliderControlState(
    val value: Float,
    val isAuto: Boolean,
    val isSupported: Boolean = true,
)

data class EnumControlState(
    val selectedIndex: Int,
    val options: List<String>,
)

enum class PowerState {
    OFF, WARMUP, STANDBY, TRANSMIT;
}

enum class ColorPalette { GREEN, YELLOW, MULTI_COLOR, NIGHT_RED }

enum class RadarOrientation { HEAD_UP, NORTH_UP, COURSE_UP }

data class NavigationData(
    val headingDeg: Float?,
    val sogKnots: Float?,
    val cogDeg: Float?,
    val latDeg: Double? = null,
    val lonDeg: Double? = null,
)

/** Distance unit preference for range display (spec §3.5 — Units & Formats). */
enum class DistanceUnit(val label: String) {
    NM("NM"),
    KM("km"),
    SM("SM"),
}

/** Bearing reference type preference (spec §3.5 — Units & Formats). */
enum class BearingMode(val label: String) {
    TRUE("True"),
    MAGNETIC("Magnetic"),
}
