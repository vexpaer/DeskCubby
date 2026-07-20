package com.deskcubby.app

import android.app.Application
import com.deskcubby.app.data.backup.AutoBackupCoordinator
import com.deskcubby.app.data.repository.PoetryRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class DeskCubbyApplication : Application() {
    @Inject lateinit var autoBackupCoordinator: AutoBackupCoordinator
    @Inject lateinit var poetryRepository: PoetryRepository
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        autoBackupCoordinator.start()
        applicationScope.launch {
            try {
                poetryRepository.refresh(force = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Startup must continue with the cached or fallback poem when the network is unavailable.
            }
        }
    }
}
