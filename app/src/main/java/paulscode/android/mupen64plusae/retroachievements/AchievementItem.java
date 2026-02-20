package paulscode.android.mupen64plusae.retroachievements;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable data class representing an achievement or a section header in the achievement list.
 */
public class AchievementItem {
    private static final String TAG = "AchievementItem";

    // Achievement types
    public static final int TYPE_STANDARD = 0;
    public static final int TYPE_MISSABLE = 1;
    public static final int TYPE_PROGRESSION = 2;
    public static final int TYPE_WIN = 3;

    // Achievement states
    public static final int STATE_INACTIVE = 0;
    public static final int STATE_ACTIVE = 1;
    public static final int STATE_UNLOCKED = 2;
    public static final int STATE_DISABLED = 3;

    // Unlocked flags
    public static final int UNLOCKED_NONE = 0;
    public static final int UNLOCKED_SOFTCORE = 1;
    public static final int UNLOCKED_HARDCORE = 2;
    public static final int UNLOCKED_BOTH = 3;

    // Header item fields
    public final boolean isHeader;
    public final String headerLabel;

    // Achievement fields
    public final int id;
    public final String title;
    public final String description;
    public final String badgeUrl;
    public final String badgeLockedUrl;
    public final int points;
    public final int state;
    public final int unlocked;
    public final String measuredProgress;
    public final float measuredPercent;
    public final float rarity;
    public final float rarityHardcore;
    public final int type;
    public final long unlockTime;

    /** Create a section header item */
    private AchievementItem(String label) {
        this.isHeader = true;
        this.headerLabel = label;
        this.id = 0;
        this.title = null;
        this.description = null;
        this.badgeUrl = null;
        this.badgeLockedUrl = null;
        this.points = 0;
        this.state = 0;
        this.unlocked = 0;
        this.measuredProgress = null;
        this.measuredPercent = 0;
        this.rarity = 0;
        this.rarityHardcore = 0;
        this.type = 0;
        this.unlockTime = 0;
    }

    /** Create an achievement item */
    private AchievementItem(int id, String title, String description, String badgeUrl,
                            String badgeLockedUrl, int points, int state, int unlocked,
                            String measuredProgress, float measuredPercent, float rarity,
                            float rarityHardcore, int type, long unlockTime) {
        this.isHeader = false;
        this.headerLabel = null;
        this.id = id;
        this.title = title;
        this.description = description;
        this.badgeUrl = badgeUrl;
        this.badgeLockedUrl = badgeLockedUrl;
        this.points = points;
        this.state = state;
        this.unlocked = unlocked;
        this.measuredProgress = measuredProgress;
        this.measuredPercent = measuredPercent;
        this.rarity = rarity;
        this.rarityHardcore = rarityHardcore;
        this.type = type;
        this.unlockTime = unlockTime;
    }

    /** Returns the appropriate badge URL based on unlock state */
    public String getEffectiveBadgeUrl() {
        if (isUnlocked()) {
            return badgeUrl;
        }
        return badgeLockedUrl != null && !badgeLockedUrl.isEmpty() ? badgeLockedUrl : badgeUrl;
    }

    /** Check if this achievement has been unlocked */
    public boolean isUnlocked() {
        return state == STATE_UNLOCKED;
    }

    /**
     * Parse the JSON string from native into a flat list with headers interleaved.
     * Empty buckets are skipped.
     */
    public static List<AchievementItem> parseJson(String json) {
        List<AchievementItem> items = new ArrayList<>();
        if (json == null || json.isEmpty()) return items;

        try {
            JSONObject root = new JSONObject(json);
            JSONArray buckets = root.getJSONArray("buckets");

            for (int i = 0; i < buckets.length(); i++) {
                JSONObject bucket = buckets.getJSONObject(i);
                JSONArray achievements = bucket.getJSONArray("achievements");

                if (achievements.length() == 0) continue;

                // Add section header
                String label = bucket.optString("label", "");
                items.add(new AchievementItem(label));

                // Add achievement items
                for (int j = 0; j < achievements.length(); j++) {
                    JSONObject ach = achievements.getJSONObject(j);
                    items.add(new AchievementItem(
                            ach.optInt("id", 0),
                            ach.optString("title", ""),
                            ach.optString("description", ""),
                            ach.optString("badge_url", null),
                            ach.optString("badge_locked_url", null),
                            ach.optInt("points", 0),
                            ach.optInt("state", 0),
                            ach.optInt("unlocked", 0),
                            ach.optString("measured_progress", ""),
                            (float) ach.optDouble("measured_percent", 0),
                            (float) ach.optDouble("rarity", 0),
                            (float) ach.optDouble("rarity_hardcore", 0),
                            ach.optInt("type", 0),
                            ach.optLong("unlock_time", 0)
                    ));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse achievement list JSON", e);
        }

        return items;
    }
}
