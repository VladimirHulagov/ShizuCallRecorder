/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.shell

import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.kitsumed.shizucallrecorder.IKeyEventCallback
import com.kitsumed.shizucallrecorder.utils.AppLogger
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads volume-key events directly from `/dev/input/event*` inside the shell process.
 *
 * The shell user (UID 2000) is in the `input` group, so it may open the kernel evdev
 * devices read-only — exactly how `adb shell getevent` works. Unlike the
 * MediaSessionManager long-press listener, the kernel delivers input events regardless
 * of the display state, which makes this monitor the screen-off-capable counterpart to
 * the app-side reflective listener (that one only fires while the screen is on).
 *
 * Deliberately NOT parsing `getevent` output: toolbox getevent printfs without fflush,
 * so a piped stdout is block-buffered and events would arrive seconds late. Reading
 * the evdev devices ourselves has zero buffering and no extra process.
 *
 * All open devices are polled on a single daemon thread; volume-key down/up
 * transitions are streamed to the registered app-side binder callback. While the
 * screen is off the framework does NOT intercept these keys, so in-call volume will
 * change a few steps during the hold — the accepted trade-off of this fallback path.
 */
object VolumeKeyInputMonitor {

    /** evdev event type for key events. */
    private const val EV_KEY = 0x01

    /** Linux key codes (linux-event-codes.h). */
    private const val KEY_VOLUMEDOWN = 114
    private const val KEY_VOLUMEUP = 115

    /**
     * `struct input_event` size depends on the process ABI:
     * 64-bit: timeval(16) + type(2) + code(2) + value(4) = 24 bytes;
     * 32-bit: timeval(8) + type(2) + code(2) + value(4) = 16 bytes.
     */
    private val EVENT_SIZE = if (Process.is64Bit()) 24 else 16

    /** Offset of the `type` field within the struct (= timeval size). */
    private val HEADER_SIZE = if (Process.is64Bit()) 16 else 8

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var callback: IKeyEventCallback? = null

    /**
     * Starts (or re-targets) the volume-key monitor.
     *
     * @param cb The app-side callback that receives the raw transitions.
     * @return True if a monitor loop is running (a previous one is reused if alive).
     */
    fun start(cb: IKeyEventCallback): Boolean {
        callback = cb
        val current = worker
        if (current != null && current.isAlive) {
            AppLogger.d("VolumeKeyInputMonitor: already running, callback updated")
            return true
        }
        val devices = File("/dev/input").listFiles { f -> f.name.startsWith("event") }
        if (devices.isNullOrEmpty()) {
            AppLogger.w("VolumeKeyInputMonitor: no /dev/input/event* devices visible")
            return false
        }
        val thread = Thread({ runLoop(devices.toList()) }, "VolumeKeyInputMonitor")
        thread.isDaemon = true
        worker = thread
        thread.start()
        return true
    }

    /**
     * Stops the monitor loop. The thread exits within one poll timeout (<= 500 ms);
     * devices are closed by the loop itself on exit.
     */
    fun stop() {
        worker = null
        callback = null
    }

    private fun runLoop(devices: List<File>) {
        val self = Thread.currentThread()
        // List of (fd, deviceName) successfully opened.
        val fds = ArrayList<Pair<java.io.FileDescriptor, String>>()
        try {
            for (device in devices) {
                try {
                    fds.add(Os.open(device.absolutePath, OsConstants.O_RDONLY, 0) to device.name)
                } catch (e: Exception) {
                    // Individual devices may refuse reads; skip them silently.
                }
            }
            if (fds.isEmpty()) {
                AppLogger.w("VolumeKeyInputMonitor: failed to open any input device")
                return
            }
            AppLogger.i("VolumeKeyInputMonitor: watching ${fds.size} input devices for volume keys")

            val pollFds = Array(fds.size) { i ->
                StructPollfd().apply {
                    fd = fds[i].first
                    events = OsConstants.POLLIN.toShort()
                }
            }
            val buf = ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)

            while (worker === self) {
                try {
                    Os.poll(pollFds, 500)
                } catch (e: android.system.ErrnoException) {
                    // A spurious signal (EINTR) must not kill the monitor.
                    if (e.errno == OsConstants.EINTR) continue
                    AppLogger.w("VolumeKeyInputMonitor: poll failed (${e.message}), stopping")
                    break
                } catch (e: Exception) {
                    AppLogger.w("VolumeKeyInputMonitor: poll failed (${e.message}), stopping")
                    break
                }

                // The app-side callback disappearing means the app died: stop reading.
                val cb = callback ?: break

                for (i in pollFds.indices) {
                    val p = pollFds[i]
                    if (p.revents.toInt() and OsConstants.POLLIN == 0) continue

                    buf.clear()
                    var total = 0
                    var readOk = true
                    while (total < EVENT_SIZE) {
                        val n = Os.read(p.fd!!, buf.array(), total, EVENT_SIZE - total)
                        if (n <= 0) {
                            readOk = false
                            break
                        }
                        total += n
                    }
                    if (!readOk || total != EVENT_SIZE) continue

                    val type = buf.getShort(HEADER_SIZE).toInt() and 0xFFFF
                    val code = buf.getShort(HEADER_SIZE + 2).toInt() and 0xFFFF
                    val value = buf.getInt(HEADER_SIZE + 4)

                    // Forward only volume-key down (1) / up (0); repeats (2) are noise.
                    if (type == EV_KEY &&
                        (code == KEY_VOLUMEUP || code == KEY_VOLUMEDOWN) &&
                        (value == 0 || value == 1)
                    ) {
                        try {
                            cb.onVolumeKeyEvent(code, value, kernelTimestampMs(buf))
                        } catch (e: Exception) {
                            AppLogger.w("VolumeKeyInputMonitor: callback dead, stopping (${e.message})")
                            return
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e("VolumeKeyInputMonitor: unexpected failure", e)
        } finally {
            for ((fd, _) in fds) {
                runCatching { Os.close(fd) }
            }
            if (worker === self) worker = null
            AppLogger.i("VolumeKeyInputMonitor: stopped")
        }
    }

    /** Rebuilds the kernel event timestamp (timeval, µs) as milliseconds. */
    private fun kernelTimestampMs(buf: ByteBuffer): Long = if (EVENT_SIZE == 24) {
        buf.getLong(0) * 1000L + buf.getLong(8) / 1000L
    } else {
        buf.getInt(0).toLong() * 1000L + buf.getInt(4).toLong() / 1000L
    }
}
