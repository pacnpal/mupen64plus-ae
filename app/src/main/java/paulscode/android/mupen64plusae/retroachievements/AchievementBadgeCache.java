/*
 * Mupen64PlusAE, an N64 emulator for the Android platform
 *
 * RetroAchievements Badge Image Cache
 *
 * Downloads and caches achievement badge images with disk + LRU memory cache.
 */
package paulscode.android.mupen64plusae.retroachievements;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AchievementBadgeCache {
    private static final String TAG = "RABadgeCache";
    private static final String CACHE_DIR = "ra_badges";
    private static final int MEMORY_CACHE_SIZE = 20;
    private static final int TIMEOUT_MS = 10000;

    private final File mDiskCacheDir;
    private final LruCache<String, Bitmap> mMemoryCache;
    private final ExecutorService mExecutor;

    public interface BadgeCallback {
        void onBadgeLoaded(Bitmap badge);
    }

    public AchievementBadgeCache(Context context) {
        mDiskCacheDir = new File(context.getCacheDir(), CACHE_DIR);
        if (!mDiskCacheDir.exists()) {
            mDiskCacheDir.mkdirs();
        }
        mMemoryCache = new LruCache<>(MEMORY_CACHE_SIZE);
        mExecutor = Executors.newFixedThreadPool(2);
    }

    public void getBadge(String badgeUrl, BadgeCallback callback) {
        if (badgeUrl == null || badgeUrl.isEmpty()) {
            callback.onBadgeLoaded(null);
            return;
        }

        // Check memory cache
        String key = getCacheKey(badgeUrl);
        Bitmap cached = mMemoryCache.get(key);
        if (cached != null) {
            Log.d(TAG, "Memory cache hit: " + key);
            callback.onBadgeLoaded(cached);
            return;
        }

        // Check disk cache
        File diskFile = new File(mDiskCacheDir, key + ".png");
        if (diskFile.exists()) {
            mExecutor.execute(() -> {
                Bitmap bitmap = BitmapFactory.decodeFile(diskFile.getAbsolutePath());
                if (bitmap != null) {
                    Log.d(TAG, "Disk cache hit: " + key);
                    mMemoryCache.put(key, bitmap);
                }
                callback.onBadgeLoaded(bitmap);
            });
            return;
        }

        // Download
        mExecutor.execute(() -> {
            Bitmap bitmap = downloadBadge(badgeUrl, diskFile);
            if (bitmap != null) {
                mMemoryCache.put(key, bitmap);
            }
            callback.onBadgeLoaded(bitmap);
        });
    }

    private Bitmap downloadBadge(String urlString, File outputFile) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            try {
                int statusCode = connection.getResponseCode();
                if (statusCode != 200) {
                    Log.w(TAG, "Badge download failed: HTTP " + statusCode);
                    return null;
                }

                try (InputStream is = connection.getInputStream()) {
                    Bitmap bitmap = BitmapFactory.decodeStream(is);
                    if (bitmap != null) {
                        // Save to disk cache
                        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                        }
                        Log.d(TAG, "Badge downloaded and cached: " + urlString);
                    }
                    return bitmap;
                }
            } finally {
                connection.disconnect();
            }
        } catch (IOException e) {
            Log.e(TAG, "Badge download error: " + urlString, e);
            return null;
        }
    }

    private String getCacheKey(String url) {
        // Use a simple hash of the URL as the cache key
        return String.valueOf(url.hashCode());
    }

    public void shutdown() {
        mExecutor.shutdown();
    }
}
