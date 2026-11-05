package com.droiddock.companion.network

/** Transport-independent, bounded, process-lifetime command retry suppression. */
class EnvelopeDeduplicator(private val capacity: Int = 4096) {
    init { require(capacity > 0) }
    private val ids = linkedSetOf<String>()
    @Synchronized fun insert(id: String): Boolean {
        if (!ids.add(id)) return false
        if (ids.size > capacity) ids.remove(ids.first())
        return true
    }
    @Synchronized fun reset() { ids.clear() }
}
