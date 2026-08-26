package com.kitsumed.shizucallrecorder;

/**
 * Streams raw volume-key transitions read from /dev/input by the shell process.
 * Delivery is one-way (oneway) so the shell reader thread never blocks on the app.
 */
oneway interface IKeyEventCallback {
    /**
     * @param keyCode Linux key code: 114 = KEY_VOLUMEDOWN, 115 = KEY_VOLUMEUP.
     * @param action 1 = key down, 0 = key up. Auto-repeats (2) are not forwarded.
     * @param timestampMillis Kernel event timestamp in milliseconds.
     */
    void onVolumeKeyEvent(int keyCode, int action, long timestampMillis);
}
