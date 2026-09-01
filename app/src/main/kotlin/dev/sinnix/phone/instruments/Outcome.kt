package dev.sinnix.phone.instruments

/**
 * What an engine hands back when a run ends.
 *
 * [primary] is the one number the result screen shows, and [primaryLabel]
 * names the field it came from. The same object is what [RunRecord.write]
 * persists as `primary_metric`/`primary_value`, so the headline the operator
 * saw and the headline a reader downstream computes over are the same
 * quantity by construction. A second, reader-side table of "which field is the
 * headline for this engine" can only drift from this one.
 *
 * A null [primary] is a run with no on-device headline — a trace prime scores
 * later, or an attempt that failed. It is persisted as null rather than
 * omitted, because "this run had no headline" and "this record predates the
 * field" are different facts.
 */
data class Outcome(
    val primaryLabel: String,
    val primary: Double?,
    val primaryUnit: String,
    val lowerIsBetter: Boolean,
    val fields: Map<String, Any?>,
    val note: String = "",
)
