/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.recording

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.kitsumed.shizucallrecorder.IKeyEventCallback
import com.kitsumed.shizucallrecorder.IShellService
import com.kitsumed.shizucallrecorder.utils.AppLogger
import java.lang.reflect.Proxy

/**
 * Registers the system-wide volume key long-press listener from the MAIN app process
 * using the hidden @SystemApi
 * [android.media.session.MediaSessionManager.setOnVolumeKeyLongPressListener].
 *
 * Requirements:
 *  - android.permission.SET_VOLUME_KEY_LONG_PRESS_LISTENER must be granted to this
 *    package. It is a "development" protection level permission, so it can be granted
 *    via `pm grant` executed from our privileged Shizuku shell process
 *    (see [com.kitsumed.shizucallrecorder.services.shell.ShellCommandExecutor.grantRuntimePermission]).
 *    The grant persists across reboots.
 *  - The method is on the hidden API greylist (not blocklist), so reflective access
 *    from a regular app process is allowed. This is the same mechanism Tasker uses
 *    for its "Volume Long Press" event.
 *
 * While the listener is registered, volume key LONG presses are delivered to us
 * INSTEAD of changing the volume (short presses keep working normally). During a
 * phone call, volume key events are typically consumed by the in-call volume
 * handler before they reach MediaSessionService, so during calls the kernel
 * /dev/input path (see [shellMonitorCallback]) is the PRIMARY trigger and this
 * reflective listener is a secondary path for when it does fire.
 *
 * The registration is automatically cleared by the system when our process dies
 * (the framework links our binder to death).
 */
class VolumeKeyTriggerController(
    private val context: Context,
    private val onTrigger: () -> Unit
) {

    companion object {
        /** The development-level permission needed to register the listener. */
        const val PERMISSION = "android.permission.SET_VOLUME_KEY_LONG_PRESS_LISTENER"

        /** MediaSessionManager hidden listener interface (inner interface of the manager class). */
        private const val LISTENER_INTERFACE = "android.media.session.MediaSessionManager\$OnVolumeKeyLongPressListener"

        /** Context.getSystemService() name for the media session service. */
        private const val MEDIA_SESSION_SERVICE = "media_session"
    }

    /** True if the reflective listener is currently registered. */
    var isRegistered = false
        private set

    /** Keeps a strong reference to the proxy so it is not garbage collected while registered. */
    private var listenerProxy: Any? = null

    /** The privileged shell service used for the screen-off /dev/input monitor. */
    private var shellService: IShellService? = null

    /** True while the shell-side screen-off monitor is active. */
    var isShellMonitorActive = false
        private set

    /**
     * Binder callback receiving raw volume-key down/up transitions read from
     * /dev/input by the shell process. The kernel delivers input events regardless of
     * the display state, so this path works with the screen off (phone at the ear).
     */
    private val shellMonitorCallback = object : IKeyEventCallback.Stub() {
        override fun onVolumeKeyEvent(keyCode: Int, action: Int, timestampMillis: Long) {
            // Kernel events are delivered regardless of the display state, so this path
            // works both with the screen off (phone at the ear) and on. During an active
            // call the framework routes volume keys to the in-call volume handler and the
            // reflective MediaSession listener often never fires — so the kernel path is
            // the PRIMARY trigger, not a fallback.
            //
            // De-duplication: if the reflective listener already handled a press (it
            // delivers on long-press detection, i.e. mid-hold), the matching kernel
            // key-up must not fire the toggle a second time. We track the kernel
            // timestamp of the down event that the framework path already consumed.
            if (action == 1) {
                // Key down: start timing the hold.
                AppLogger.i("VolumeKeyTrigger: kernel key-down (key=$keyCode ts=$timestampMillis)")
                lastDownTimeMs.set(timestampMillis)
                lastDownKey.set(keyCode)
            } else {
                // Key up: hold finished — trigger if it was long enough.
                val downAt = lastDownTimeMs.get()
                val downKey = lastDownKey.get()
                if (downAt > 0 && downKey == keyCode) {
                    val holdMs = timestampMillis - downAt
                    if (holdMs >= HOLD_THRESHOLD_MS) {
                        if (downAt == lastFrameworkHandledDownMs.get()) {
                            AppLogger.i("VolumeKeyTrigger: kernel long-press ignored (already handled by framework listener)")
                        } else {
                            AppLogger.i("VolumeKeyTrigger: screen-off long-press detected (key=$keyCode, hold=${holdMs}ms)")
                            Handler(Looper.getMainLooper()).post { onTrigger() }
                        }
                    } else {
                        AppLogger.d("VolumeKeyTrigger: screen-off press too short (${holdMs}ms < $HOLD_THRESHOLD_MS)")
                    }
                } else {
                    AppLogger.d("VolumeKeyTrigger: kernel key-up without matching down (key=$keyCode ts=$timestampMillis)")
                }
                lastDownTimeMs.set(0)
            }
        }
    }

    /** Re-usable atomic holders so binder threads can't race the trigger. */
    private val lastDownTimeMs = java.util.concurrent.atomic.AtomicLong(0)
    private val lastDownKey = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Kernel timestamp of the key-down whose long-press the reflective framework
     * listener already fired on — used to suppress the duplicate kernel key-up trigger.
     */
    private val lastFrameworkHandledDownMs = java.util.concurrent.atomic.AtomicLong(-1)

    /** Hold duration that counts as a long-press on the screen-off path. */
    private val HOLD_THRESHOLD_MS = 600L

    /**
     * Starts the shell-side monitor for the screen-off path. Requires the already
     * bound [shell][shizukuManager.getShellService]; a null or failed call leaves the
     * controller unchanged (screen-on listener still works).
     *
     * @return True if the shell monitor started.
     */
    fun startShellMonitor(service: IShellService): Boolean {
        return try {
            val ok = service.startVolumeKeyMonitor(shellMonitorCallback)
            isShellMonitorActive = ok
            if (ok) {
                shellService = service
                AppLogger.i("VolumeKeyTrigger: shell screen-off monitor started")
            } else {
                AppLogger.w("VolumeKeyTrigger: shell monitor refused to start")
            }
            ok
        } catch (e: Exception) {
            AppLogger.w("VolumeKeyTrigger: failed to start shell monitor (${e.message})")
            isShellMonitorActive = false
            false
        }
    }

    /** Stops the shell-side monitor and detaches from the shell service. */
    fun stopShellMonitor() {
        val service = shellService
        shellService = null
        isShellMonitorActive = false
        lastDownTimeMs.set(0)
        lastFrameworkHandledDownMs.set(-1)
        if (service != null) {
            try {
                service.stopVolumeKeyMonitor()
            } catch (_: Exception) {
                // Shell process is gone — nothing to stop.
            }
        }
    }

    /**
     * Checks whether this package currently holds the volume key listener permission
     * (granted earlier via the Shizuku shell process).
     */
    fun hasPermission(): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * Registers the volume key long-press listener.
     *
     * @return True on success, false if the permission is missing or the reflective call failed.
     */
    fun register(): Boolean {
        if (isRegistered) return true

        if (!hasPermission()) {
            AppLogger.w("VolumeKeyTrigger: permission not granted yet, cannot register listener")
            return false
        }

        return try {
            val mediaSessionManager = context.getSystemService(MEDIA_SESSION_SERVICE)
                ?: throw IllegalStateException("MediaSessionManager service not available")

            val listenerInterface = Class.forName(LISTENER_INTERFACE)
            val proxy = Proxy.newProxyInstance(
                context.classLoader,
                arrayOf(listenerInterface)
            ) { proxyInstance, method, args ->
                // Guard against java.lang.Object methods dispatched through the proxy.
                when (method.name) {
                    "hashCode" -> return@newProxyInstance System.identityHashCode(proxyInstance)
                    "equals" -> return@newProxyInstance (proxyInstance === args?.getOrNull(0))
                    "toString" -> return@newProxyInstance "VolumeKeyLongPressListenerProxy"
                }
                // Interface has a single method: void onVolumeKeyLongPress(KeyEvent event)
                if (method.name == "onVolumeKeyLongPress" && args != null && args.size == 1) {
                    val event = args[0] as? KeyEvent
                    // The system delivers the initial long-press ACTION_DOWN (repeatCount == 0),
                    // then repeated ACTION_DOWNs and a final ACTION_UP. Trigger exactly once
                    // per physical long-press.
                    if (event != null &&
                        event.action == KeyEvent.ACTION_DOWN &&
                        event.repeatCount == 0
                    ) {
                        AppLogger.i("VolumeKeyTrigger: long-press detected (keyCode=${event.keyCode})")
                        // Snapshot the kernel timestamp of the in-flight hold (the kernel
                        // key-down has already been delivered by now) so the subsequent
                        // kernel key-up is recognised as a duplicate and does not re-trigger.
                        lastFrameworkHandledDownMs.set(lastDownTimeMs.get())
                        Handler(Looper.getMainLooper()).post { onTrigger() }
                    }
                }
                null // void method
            }

            val setMethod = mediaSessionManager.javaClass.getMethod(
                "setOnVolumeKeyLongPressListener",
                listenerInterface,
                Handler::class.java
            )
            setMethod.invoke(mediaSessionManager, proxy, Handler(Looper.getMainLooper()))

            listenerProxy = proxy
            isRegistered = true
            AppLogger.i("VolumeKeyTrigger: listener registered")
            true
        } catch (e: Exception) {
            AppLogger.e("VolumeKeyTrigger: failed to register listener (${e.javaClass.simpleName}: ${e.message})", e)
            isRegistered = false
            listenerProxy = null
            false
        }
    }

    /**
     * Unregisters the listener, restoring normal volume key behaviour.
     */
    fun unregister() {
        if (!isRegistered) return
        try {
            val mediaSessionManager = context.getSystemService(MEDIA_SESSION_SERVICE) ?: return
            val listenerInterface = Class.forName(LISTENER_INTERFACE)
            val setMethod = mediaSessionManager.javaClass.getMethod(
                "setOnVolumeKeyLongPressListener",
                listenerInterface,
                Handler::class.java
            )
            setMethod.invoke(mediaSessionManager, null, (Handler(Looper.getMainLooper())))
            AppLogger.i("VolumeKeyTrigger: listener unregistered")
        } catch (e: Exception) {
            AppLogger.e("VolumeKeyTrigger: failed to unregister listener: ${e.message}", e)
        } finally {
            isRegistered = false
            listenerProxy = null
        }
    }
}
