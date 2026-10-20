package io.github.qaisarpipeshift.utter.extras

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.qaisarpipeshift.utter.R

/**
 * The "Dictate to clipboard" Quick Settings tile (docs/spec.md §17, docs/plan.md Task 15).
 *
 * Why a tile: a thought with no text field open still deserves dictation, and the tile is one tap from anywhere. The
 * service is bound only while the Quick Settings panel is open — it does no work in the background — and it only
 * launches [QuickRecorderActivity]; the recording itself lives in that translucent activity.
 */
class DictateTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = getString(R.string.extras_tile_label)
            icon = Icon.createWithResource(this@DictateTileService, R.drawable.ic_mic)
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    /**
     * Android 14 requires the `PendingIntent` overload; older releases only have the `Intent` one, which is
     * deprecated but still the only API they know.
     */
    @Suppress("DEPRECATION")
    // The Intent overload is only reached below API 34, where it is the supported call.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, QuickRecorderActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pending)
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
