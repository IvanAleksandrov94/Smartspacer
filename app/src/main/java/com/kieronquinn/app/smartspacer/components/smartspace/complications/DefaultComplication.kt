package com.kieronquinn.app.smartspacer.components.smartspace.complications

import android.graphics.drawable.Icon
import android.os.Bundle
import com.kieronquinn.app.smartspacer.BuildConfig
import com.kieronquinn.app.smartspacer.R
import com.kieronquinn.app.smartspacer.components.notifications.NotificationId
import android.content.Intent
import com.kieronquinn.app.smartspacer.components.smartspace.widgets.GoogleWeatherWidget
import com.kieronquinn.app.smartspacer.repositories.GoogleWeatherRepository
import com.kieronquinn.app.smartspacer.utils.extensions.getGoogleWeatherIntent
import com.kieronquinn.app.smartspacer.repositories.NotificationRepository
import com.kieronquinn.app.smartspacer.repositories.ShizukuServiceRepository
import com.kieronquinn.app.smartspacer.repositories.SmartspaceRepository
import com.kieronquinn.app.smartspacer.repositories.SmartspacerSettingsRepository
import com.kieronquinn.app.smartspacer.sdk.model.CompatibilityState
import com.kieronquinn.app.smartspacer.sdk.model.SmartspaceAction
import com.kieronquinn.app.smartspacer.sdk.model.UiSurface
import com.kieronquinn.app.smartspacer.sdk.provider.SmartspacerComplicationProvider
import com.kieronquinn.app.smartspacer.sdk.utils.ComplicationTemplate
import com.kieronquinn.app.smartspacer.ui.activities.TrampolineActivity
import com.kieronquinn.app.smartspacer.utils.extensions.getDefaultSmartspaceComponent
import org.koin.android.ext.android.inject

class DefaultComplication: SmartspacerComplicationProvider() {

    companion object {
        const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.complication.default"
    }

    private val smartspaceRepository by inject<SmartspaceRepository>()
    private val settingsRepository by inject<SmartspacerSettingsRepository>()
    private val notificationRepository by inject<NotificationRepository>()
    private val shizukuServiceRepository by inject<ShizukuServiceRepository>()

    private val smartspaceComponent by lazy {
        provideContext().getDefaultSmartspaceComponent()
    }

    private val googleWeatherRepository by inject<GoogleWeatherRepository>()

    override fun getSmartspaceActions(smartspacerId: String): List<SmartspaceAction> {
        val home = smartspaceRepository.getDefaultHomeActions().value.applyChanges().map {
            it.copy(limitToSurfaces = setOf(UiSurface.HOMESCREEN))
        }
        val lock = smartspaceRepository.getDefaultLockActions().value.applyChanges().map {
            it.copy(limitToSurfaces = setOf(UiSurface.LOCKSCREEN))
        }
        val actions = home + lock
        if (actions.isNotEmpty()) return actions

        val weather = googleWeatherRepository.getTodayState() ?: return emptyList()
        val fallbackAction = ComplicationTemplate.Basic(
            id = "google_weather_fallback_${System.currentTimeMillis()}",
            icon = com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.Icon(
                Icon.createWithBitmap(weather.icon),
                shouldTint = false
            ),
            content = com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.Text(weather.temperature),
            onClick = com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.TapAction(
                intent = provideContext().getGoogleWeatherIntent().apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
            )
        ).create()
        return listOf(fallbackAction)
    }

    private fun List<SmartspaceAction>.applyChanges() = onEach {
        it.extras = Bundle().apply {
            putAll(it.extras)
            ComplicationTemplate.setSubcardTypeToWeather(this)
        }
    }

    private fun showShizukuNotificationIfNeeded(): Boolean {
        if(false){
            notificationRepository.showShizukuNotification(
                R.string.notification_shizuku_content_at_a_glance_complication
            )
            return true
        }else{
            notificationRepository.cancelNotification(NotificationId.SHIZUKU)
        }
        return false
    }

    override fun getConfig(smartspacerId: String?): Config {
        val description = if(smartspacerId == null){
            R.string.complication_default_description_recommended
        }else{
            R.string.complication_default_description
        }
        return Config(
            label = resources.getString(R.string.complication_default_label),
            description = resources.getText(description),
            icon = Icon.createWithResource(provideContext(), R.drawable.ic_target_default),
            compatibilityState = getCompatibilityState(),
            configActivity = TrampolineActivity.createAsiTrampolineIntent(provideContext()),
            allowAddingMoreThanOnce = true,
            widgetProvider = GoogleWeatherWidget.AUTHORITY
        )
    }

    private fun getCompatibilityState(): CompatibilityState {
        return CompatibilityState.Compatible
    }

}