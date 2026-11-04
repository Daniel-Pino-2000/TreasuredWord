package com.application.bibleapp.utils

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How long a "No thanks" to an update lasts before the app asks again (#202). */
internal const val UPDATE_DECLINE_SNOOZE_MS = 3L * 24 * 60 * 60 * 1000

/**
 * Whether to show Play's update dialog for [availableVersionCode] (#202). A version the person
 * turned down isn't offered again until [UPDATE_DECLINE_SNOOZE_MS] has passed, so it doesn't nag
 * on every launch; a newer version than the declined one is offered straight away. A clock that
 * went backwards ends the snooze rather than stretching it.
 */
internal fun shouldOfferUpdate(
    availableVersionCode: Int,
    declinedVersionCode: Int?,
    declinedAtMs: Long,
    nowMs: Long
): Boolean =
    availableVersionCode != declinedVersionCode ||
        nowMs < declinedAtMs ||
        nowMs - declinedAtMs >= UPDATE_DECLINE_SNOOZE_MS

/**
 * Google Play's in-app updates, flexible flow (#202). Play itself doesn't tell people about new
 * versions, and its auto-update can wait days for Wi-Fi + charging, so each time the app comes to
 * the front this asks Play whether a newer version exists and, if so, shows Play's own "Update
 * available" dialog. The download runs in the background while the person keeps reading;
 * [updateReady] then turns true so the UI can offer a restart ([completeUpdate]).
 *
 * Only an app installed from Play can see updates: debug builds, offline starts and phones
 * without the Play Store fail the check, and that's silently ignored.
 *
 * Create it in `onCreate`, before the activity starts: it registers an activity-result launcher.
 */
class InAppUpdates(private val activity: ComponentActivity) : DefaultLifecycleObserver {
    private val manager = AppUpdateManagerFactory.create(activity)
    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _updateReady = MutableStateFlow(false)
    /** True once a new version has downloaded and only needs a restart to install. */
    val updateReady: StateFlow<Boolean> = _updateReady.asStateFlow()

    /** Play's dialog is up (or being opened), so returning from it doesn't open it again. */
    private var dialogShowing = false
    private var pendingVersionCode = 0

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        dialogShowing = false
        // RESULT_CANCELED is "No thanks" (or back); a failed download isn't the person's choice.
        if (result.resultCode == Activity.RESULT_CANCELED) {
            prefs.edit {
                putInt(KEY_DECLINED_VERSION, pendingVersionCode)
                putLong(KEY_DECLINED_AT, System.currentTimeMillis())
            }
        }
    }

    private val installListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) _updateReady.value = true
    }

    init {
        activity.lifecycle.addObserver(this)    }

    override fun onStart(owner: LifecycleOwner) {
        manager.registerListener(installListener)
        manager.appUpdateInfo
            .addOnSuccessListener(::onUpdateInfo)
            // Not installed from Play, offline, or no Play Store: nothing to offer.
            .addOnFailureListener { }
    }

    override fun onStop(owner: LifecycleOwner) {
        manager.unregisterListener(installListener)
    }

    private fun onUpdateInfo(info: AppUpdateInfo) {
        when {
            // Downloaded on an earlier visit and never installed: offer the restart again.
            info.installStatus() == InstallStatus.DOWNLOADED -> _updateReady.value = true
            info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) &&
                !info.isDownloadInProgress() &&
                !dialogShowing &&
                shouldOfferUpdate(
                    availableVersionCode = info.availableVersionCode(),
                    declinedVersionCode = prefs.getInt(KEY_DECLINED_VERSION, 0).takeIf { it != 0 },
                    declinedAtMs = prefs.getLong(KEY_DECLINED_AT, 0L),
                    nowMs = System.currentTimeMillis()
                ) -> {
                pendingVersionCode = info.availableVersionCode()
                dialogShowing = runCatching {
                    manager.startUpdateFlowForResult(
                        info,
                        launcher,
                        AppUpdateOptions.defaultOptions(AppUpdateType.FLEXIBLE)
                    )
                }.getOrDefault(false)
            }
        }
    }

    private fun AppUpdateInfo.isDownloadInProgress(): Boolean =
        installStatus() == InstallStatus.PENDING ||
            installStatus() == InstallStatus.DOWNLOADING ||
            installStatus() == InstallStatus.INSTALLING

    /** Installs the downloaded version; Play restarts the app into it. */
    fun completeUpdate() {
        _updateReady.value = false
        manager.completeUpdate()
    }

    /** The restart snackbar went away unused: it comes back the next time the app opens. */
    fun postponeRestart() {
        _updateReady.value = false
    }

    private companion object {
        const val PREFS = "in_app_updates"
        const val KEY_DECLINED_VERSION = "declined_version_code"
        const val KEY_DECLINED_AT = "declined_at_ms"
    }
}
