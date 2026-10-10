package dev.sinnix.phone.instruments

/** Result headline rendered on the device and persisted with the run. */
data class Outcome(
    val primaryLabel: String,
    val primary: Double?,
    val primaryUnit: String,
    val lowerIsBetter: Boolean,
    val fields: Map<String, Any?>,
    val note: String = "",
)
