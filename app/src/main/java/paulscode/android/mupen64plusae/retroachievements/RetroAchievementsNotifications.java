/*
 * Mupen64PlusAE, an N64 emulator for the Android platform
 *
 * RetroAchievements Notification Helper
 *
 * Displays toast notifications for RetroAchievements state changes.
 */
package paulscode.android.mupen64plusae.retroachievements;

import android.content.Context;
import android.widget.Toast;

import paulscode.android.mupen64plusae.R;

public class RetroAchievementsNotifications {
    private final Context mContext;

    public RetroAchievementsNotifications(Context context) {
        mContext = context;
    }

    /**
     * Show hardcore mode notification
     */
    public void showHardcoreModeActive() {
        Toast.makeText(mContext, mContext.getString(R.string.retroachievements_hardcore_active), Toast.LENGTH_LONG).show();
    }
}
