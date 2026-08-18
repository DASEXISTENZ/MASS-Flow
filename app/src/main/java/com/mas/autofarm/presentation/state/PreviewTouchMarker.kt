package com.mas.autofarm.presentation.state

data class PreviewTouchMarker(
    val id: Long,
    val x: Int,
    val y: Int,
    val action: Int,
    val createdAtMs: Long,
) {
    companion object {
        const val MAX_ACTIVE_MARKERS = 8
        const val TTL_MS = 600L
        const val CLEANUP_INTERVAL_MS = 100L
    }
}
