package com.deskcubby.plugin.api.core.api

/** Version token used for optimistic conflict checks; resource ids remain opaque to plugins. */
data class ContentVersion(
    val sha256: String,
    val size: Long,
    val lastModifiedMillis: Long,
)
