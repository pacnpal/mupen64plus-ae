/*
 * Mupen64PlusAE, an N64 emulator for the Android platform
 * 
 * RetroAchievements Integration - HTTP Client
 * 
 * Handles HTTP communication with RetroAchievements servers.
 */
package paulscode.android.mupen64plusae.retroachievements;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP client for RetroAchievements API communication
 */
public class RetroAchievementsHttpClient {
    private static final String TAG = "RAHttpClient";
    private static final int TIMEOUT_MS = 30000; // 30 seconds
    private static final String USER_AGENT = "Mupen64PlusAE/1.0";
    
    private final ExecutorService mExecutor;
    private final Handler mMainHandler;

    private static String sanitizeUrlForLog(String url) {
        if (url == null) {
            return "null";
        }
        int queryIndex = url.indexOf('?');
        if (queryIndex >= 0) {
            return url.substring(0, queryIndex) + "?<redacted>";
        }
        return url;
    }
    
    public interface HttpCallback {
        void onResponse(int statusCode, String responseBody);
        void onError(String errorMessage);
    }
    
    public RetroAchievementsHttpClient() {
        mExecutor = Executors.newCachedThreadPool();
        mMainHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * Make a GET request
     */
    public void get(final String urlString, final HttpCallback callback) {
        mExecutor.execute(() -> {
            try {
                URL url = new URL(urlString);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                
                try {
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(TIMEOUT_MS);
                    connection.setReadTimeout(TIMEOUT_MS);
                    connection.setRequestProperty("User-Agent", USER_AGENT);
                    connection.setRequestProperty("Accept", "application/json");
                    
                    int statusCode = connection.getResponseCode();
                    String responseBody = readResponse(connection, statusCode);
                    
                    // Post result on main thread
                    mMainHandler.post(() -> callback.onResponse(statusCode, responseBody));
                    
                } finally {
                    connection.disconnect();
                }
                
            } catch (IOException e) {
                Log.e(TAG, "HTTP GET error: " + sanitizeUrlForLog(urlString), e);
                mMainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }
    
    /**
     * Make a POST request
     */
    public void post(final String urlString, final String postData, final HttpCallback callback) {
        mExecutor.execute(() -> {
            try {
                URL url = new URL(urlString);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                
                try {
                    connection.setRequestMethod("POST");
                    connection.setConnectTimeout(TIMEOUT_MS);
                    connection.setReadTimeout(TIMEOUT_MS);
                    connection.setRequestProperty("User-Agent", USER_AGENT);
                    connection.setRequestProperty("Accept", "application/json");
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    connection.setDoOutput(true);
                    
                    // Write POST data
                    if (postData != null && !postData.isEmpty()) {
                        try (OutputStream os = connection.getOutputStream()) {
                            byte[] input = postData.getBytes(StandardCharsets.UTF_8);
                            os.write(input, 0, input.length);
                        }
                    }
                    
                    int statusCode = connection.getResponseCode();
                    String responseBody = readResponse(connection, statusCode);
                    
                    // Post result on main thread
                    mMainHandler.post(() -> callback.onResponse(statusCode, responseBody));
                    
                } finally {
                    connection.disconnect();
                }
                
            } catch (IOException e) {
                Log.e(TAG, "HTTP POST error: " + sanitizeUrlForLog(urlString), e);
                mMainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }
    
    /**
     * Read response body from connection
     */
    private String readResponse(HttpURLConnection connection, int statusCode) throws IOException {
        StringBuilder response = new StringBuilder();
        
        // Choose appropriate stream - error stream for 4xx/5xx, input stream otherwise
        InputStream stream = null;
        try {
            if (statusCode >= 400) {
                stream = connection.getErrorStream();
                // If error stream is null, try input stream as fallback
                if (stream == null) {
                    stream = connection.getInputStream();
                }
            } else {
                stream = connection.getInputStream();
            }
            
            // If we still don't have a stream, return empty response
            if (stream == null) {
                return "";
            }
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
        } finally {
            // Close stream if we opened it
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            }
        }
        
        return response.toString();
    }
    
    /**
     * Shutdown the executor
     */
    public void shutdown() {
        mExecutor.shutdown();
    }
}
