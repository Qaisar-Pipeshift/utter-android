package io.github.qaisarpipeshift.utter.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** One selectable input device: [id] is the platform `AudioDeviceInfo.id` persisted in settings. */
data class Mic(val id: Int, val label: String)

/**
 * The phone's input devices, for the microphone setting (docs/spec.md §15.1, docs/plan.md Task 6).
 *
 * Why list devices instead of using the default: a headset that is plugged in but not chosen is a common cause of
 * "it recorded silence". Auto (null in settings) stays the default and lets Android route.
 */
object Mics {

    fun list(context: Context): List<Mic> {
        val manager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return emptyList()
        val devices = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val out = ArrayList<Mic>(devices.size)
        for (device in devices) {
            val label = when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB headset"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
                else -> device.productName.toString().ifBlank { "Mic ${device.id}" }
            }
            out.add(Mic(device.id, label))
        }
        return out.distinctBy { it.id }
    }
}
