package dev.normal99.cockpit

/** Release builds have no demo host. */
object Demo {
    const val URL = ""
    const val available = false
    fun ensureStarted() = Unit
}
