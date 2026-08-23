package com.deskcubby.app.ui.structuredrecords

import com.deskcubby.app.data.structuredrecords.StructuredField
import com.deskcubby.app.data.structuredrecords.StructuredRecordTemplate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * Process-local projection of the current structured-record workspace.
 *
 * `.deskcubby` remains the durable source of truth. This store only prevents multiple
 * destination-scoped ViewModels (Home, manager, etc.) from maintaining contradictory copies of the
 * same fields/templates while they are alive in the same process.
 */
@Singleton
class StructuredRecordsUiStore @Inject constructor() {
    internal val fields = MutableStateFlow<List<StructuredField>>(emptyList())
    internal val templates = MutableStateFlow<List<StructuredRecordTemplate>>(emptyList())
    internal val mutationMutex = Mutex()
    internal var rootInitialized = false
    internal var rootUri: String? = null
}
