package com.deskcubby.app.data.model

/** Controls how FFT bins are mapped across the bottom-navigation visualizer. */
enum class MusicVisualizerFrequencyMode {
    /** Keep the user-selected lower and upper frequency bounds. */
    MANUAL,

    /** Follow the useful frequency band in the currently playing audio. */
    ADAPTIVE,
}
