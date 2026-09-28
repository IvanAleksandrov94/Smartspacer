package com.kieronquinn.app.smartspacer.components.smartspace

import android.app.smartspace.SmartspaceConfig
import android.app.smartspace.SmartspaceSessionId
import android.app.smartspace.SmartspaceTarget
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.service.smartspace.ISmartspaceService
import com.kieronquinn.app.smartspacer.components.smartspace.SmartspaceSession.OnTargetsAvailableListener
import com.kieronquinn.app.smartspacer.repositories.SystemSmartspaceRepository
import com.kieronquinn.app.smartspacer.sdk.model.UiSurface
import com.kieronquinn.app.smartspacer.utils.extensions.getDefaultSmartspaceComponent
import com.kieronquinn.app.smartspacer.utils.extensions.suspendCoroutineWithTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 *  Direct system client for Smartspace (Google ASI or system service).
 *  Runs natively as privileged system app with android.permission.MANAGE_SMARTSPACE.
 */
class SmartspacerSmartspaceManager(private val context: Context): KoinComponent {

    companion object {
        private const val BIND_TIMEOUT = 5000L
    }

    private val systemSmartspaceRepository by inject<SystemSmartspaceRepository>()

    private val serviceIntent = context.getDefaultSmartspaceComponent()?.let {
        Intent("android.service.smartspace.SmartspaceService").apply {
            component = it
        }
    }

    private val bindLock = Mutex()
    private var serviceConnection: ServiceConnection? = null
    private var service: ISmartspaceService? = null
    private val executor = Executors.newSingleThreadExecutor()

    private val deathRecipient = IBinder.DeathRecipient {
        serviceConnection = null
        service = null
        systemSmartspaceRepository.onAsiStopped()
    }

    val isAvailable = serviceIntent != null

    suspend fun createSmartspaceSessions(
        onTargetsAvailable: (surface: UiSurface, targets: List<SmartspaceTarget>) -> Unit
    ) {
        runWithServiceLocked {
            UiSurface.entries.forEach { surface ->
                val config = SmartspaceConfig.Builder(context, surface.surface)
                    .setSmartspaceTargetCount(5)
                    .build()
                SmartspaceSession(this, context, config).apply {
                    addOnTargetsAvailableListener(
                        executor, createCallback(surface, onTargetsAvailable)
                    )
                }
            }
        }
    }

    private fun createCallback(
        surface: UiSurface,
        callback: (surface: UiSurface, targets: List<SmartspaceTarget>) -> Unit
    ): OnTargetsAvailableListener {
        return object: OnTargetsAvailableListener {
            override fun onTargetsAvailable(targets: List<SmartspaceTarget?>) {
                callback(surface, targets.filterNotNull())
            }
        }
    }

    suspend fun destroySmartspaceSession(sessionId: SmartspaceSessionId) {
        runWithServiceLocked {
            onDestroySmartspaceSession(sessionId)
        }
    }

    private suspend fun <T> runWithServiceLocked(
        block: ISmartspaceService.() -> T
    ): T? = bindLock.withLock {
        runWithService(block)
    }

    private suspend fun <T> runWithService(
        block: ISmartspaceService.() -> T
    ): T? = suspendCoroutineWithTimeout(BIND_TIMEOUT) { resume ->
        var hasResumed = false
        service?.let {
            try {
                hasResumed = true
                resume.resume(block(it))
                return@suspendCoroutineWithTimeout
            } catch (e: DeadObjectException) {
                service = null
            }
        }
        val targetIntent = serviceIntent
        if (targetIntent == null) {
            if (!hasResumed) {
                hasResumed = true
                resume.resume(null)
            }
            return@suspendCoroutineWithTimeout
        }
        val conn = object: ServiceConnection {
            override fun onServiceConnected(name: ComponentName, serviceBinder: IBinder) {
                try {
                    serviceBinder.linkToDeath(deathRecipient, 0)
                } catch (e: Exception) {
                    // Ignore
                }
                val connection = ISmartspaceService.Stub.asInterface(serviceBinder)
                this@SmartspacerSmartspaceManager.service = connection
                if (!hasResumed) {
                    hasResumed = true
                    resume.resume(block(connection))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                serviceConnection = null
            }
        }
        this@SmartspacerSmartspaceManager.serviceConnection = conn
        try {
            val bound = context.bindService(targetIntent, conn, Context.BIND_AUTO_CREATE)
            if (!bound && !hasResumed) {
                hasResumed = true
                resume.resume(null)
            }
        } catch (e: Exception) {
            if (!hasResumed) {
                hasResumed = true
                resume.resume(null)
            }
        }
    }

}
