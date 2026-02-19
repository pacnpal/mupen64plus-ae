/*
 * Mupen64PlusAE, an N64 emulator for the Android platform
 * 
 * RetroAchievements Notification Helper
 * 
 * Displays achievement unlock notifications and game placards.
 */
package paulscode.android.mupen64plusae.retroachievements;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import paulscode.android.mupen64plusae.R;

public class RetroAchievementsNotifications {
    private static final String TAG = "RANotifications";
    private static final String CHANNEL_ID = "retroachievements_channel";
    private static final String CHANNEL_NAME = "RetroAchievements";
    private static final int NOTIFICATION_ID_BASE = 10000;

    private final Context mContext;
    private final NotificationManager mNotificationManager;
    private int mNotificationCounter = 0;

    public RetroAchievementsNotifications(Context context) {
        mContext = context;
        mNotificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("RetroAchievements notifications for unlocked achievements");
            channel.enableVibration(true);
            if (mNotificationManager != null) {
                mNotificationManager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Show achievement unlock notification
     * @param achievementTitle Achievement title
     * @param achievementDescription Achievement description
     * @param points Points earned
     */
    public void showAchievementUnlock(String achievementTitle, String achievementDescription, int points) {
        // Show as toast for immediate feedback
        String message = achievementTitle + " (" + points + " points)";
        Toast.makeText(mContext, "🏆 " + message, Toast.LENGTH_LONG).show();

        // Also show as notification for history
        if (mNotificationManager != null) {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_controller)
                    .setContentTitle("Achievement Unlocked!")
                    .setContentText(achievementTitle)
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .bigText(achievementTitle + "\n" + achievementDescription + "\n+" + points + " points"))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true);

            mNotificationManager.notify(NOTIFICATION_ID_BASE + (mNotificationCounter++), builder.build());
        }
    }

    /**
     * Show game loaded placard
     * @param gameTitle Game title
     * @param unlockedCount Number of unlocked achievements
     * @param totalCount Total number of achievements
     */
    public void showGamePlacard(String gameTitle, int unlockedCount, int totalCount) {
        String message;
        if (totalCount == 0) {
            message = gameTitle + " - No achievements available";
        } else {
            message = gameTitle + " - " + unlockedCount + "/" + totalCount + " achievements unlocked";
        }
        Toast.makeText(mContext, message, Toast.LENGTH_LONG).show();
    }

    /**
     * Show leaderboard submission notification
     * @param leaderboardTitle Leaderboard title
     * @param score Score achieved
     */
    public void showLeaderboardSubmission(String leaderboardTitle, String score) {
        String message = "Leaderboard: " + leaderboardTitle + " - " + score;
        Toast.makeText(mContext, message, Toast.LENGTH_SHORT).show();
    }

    /**
     * Show hardcore mode notification
     */
    public void showHardcoreModeActive() {
        Toast.makeText(mContext, "Hardcore Mode Active - Save states disabled", Toast.LENGTH_LONG).show();
    }

    /**
     * Clear all RetroAchievements notifications
     */
    public void clearAll() {
        if (mNotificationManager != null) {
            mNotificationManager.cancelAll();
        }
    }
}
