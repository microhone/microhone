package com.microhone.app.audio

/**
 * Single shared capture engine used by both the UI and the foreground service,
 * so streaming survives the app going to the background.
 */
object AudioEngine {
    val streamer = AudioStreamer()

    /** Last error reported by the streamer, surfaced in the UI after a stop. */
    @Volatile
    var lastError: String? = null
}
