/*
 * Mupen64PlusAE, an N64 emulator for the Android platform
 * 
 * RetroAchievements Integration - Main Manager
 * 
 * Central manager for all RetroAchievements functionality.
 */
package paulscode.android.mupen64plusae.retroachievements;

import android.content.Context;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Singleton manager for RetroAchievements integration
 */
public class RetroAchievementsManager implements RCheevosNative.RCheevosCallback {
    private static final String TAG = "RAManager";
    
    private static RetroAchievementsManager sInstance;
    
    private final Context mContext;
    private final RCheevosNative mNative;
    private final RetroAchievementsHttpClient mHttpClient;
    private final RetroAchievementsNotifications mNotifications;
    
    private long mClientPtr = 0;
    private boolean mInitialized = false;
    private boolean mHardcoreEnabled = false;
    private String mUsername;
    private String mToken;
    
    // Memory read callback - will be set by CoreService
    private MemoryReadCallback mMemoryReadCallback;
    
    // Pending HTTP requests (callback ptr -> callback data ptr)
    private final Map<Long, Long> mPendingRequests = new HashMap<>();
    
    /**
     * Callback interface for reading emulator memory
     */
    public interface MemoryReadCallback {
        int readMemory(int address, byte[] buffer, int numBytes);
    }
    
    /**
     * Get singleton instance
     */
    public static synchronized RetroAchievementsManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new RetroAchievementsManager(context.getApplicationContext());
        }
        return sInstance;
    }
    
    private RetroAchievementsManager(Context context) {
        mContext = context;
        mNative = new RCheevosNative();
        mHttpClient = new RetroAchievementsHttpClient();
        mNotifications = new RetroAchievementsNotifications(context);
    }
    
    /**
     * Initialize RetroAchievements system
     */
    public synchronized boolean initialize() {
        if (mInitialized) {
            Log.w(TAG, "Already initialized");
            return true;
        }
        
        // Create native client
        mClientPtr = mNative.nativeCreateClient();
        if (mClientPtr == 0) {
            Log.e(TAG, "Failed to create native client");
            return false;
        }
        
        // Set callback handler
        mNative.nativeSetCallbackHandler(this);
        
        mInitialized = true;
        Log.i(TAG, "RetroAchievements initialized successfully");
        return true;
    }
    
    /**
     * Shutdown RetroAchievements system
     */
    public synchronized void shutdown() {
        if (!mInitialized) {
            return;
        }
        
        if (mClientPtr != 0) {
            mNative.nativeDestroyClient(mClientPtr);
            mClientPtr = 0;
        }
        
        mHttpClient.shutdown();
        mInitialized = false;
        Log.i(TAG, "RetroAchievements shut down");
    }
    
    /**
     * Set memory read callback
     */
    public void setMemoryReadCallback(MemoryReadCallback callback) {
        mMemoryReadCallback = callback;
    }
    
    /**
     * Set hardcore mode
     */
    public void setHardcoreEnabled(boolean enabled) {
        mHardcoreEnabled = enabled;
        if (mInitialized && mClientPtr != 0) {
            mNative.nativeSetHardcoreEnabled(mClientPtr, enabled);
            Log.i(TAG, "Hardcore mode " + (enabled ? "enabled" : "disabled"));
            
            // Show notification if enabling hardcore
            if (enabled) {
                mNotifications.showHardcoreModeActive();
            }
        }
    }
    
    /**
     * Check if hardcore mode is enabled
     */
    public boolean isHardcoreEnabled() {
        return mHardcoreEnabled;
    }
    
    /**
     * Generate hash for a ROM file
     */
    public String generateHash(String romPath) {
        if (!mInitialized) {
            Log.w(TAG, "Not initialized, cannot generate hash");
            return null;
        }
        
        int consoleId = mNative.nativeGetN64ConsoleId();
        return mNative.nativeGenerateHash(consoleId, romPath, null);
    }
    
    /**
     * Generate hash for ROM data in memory
     */
    public String generateHash(byte[] romData) {
        if (!mInitialized) {
            Log.w(TAG, "Not initialized, cannot generate hash");
            return null;
        }
        
        int consoleId = mNative.nativeGetN64ConsoleId();
        return mNative.nativeGenerateHash(consoleId, null, romData);
    }
    
    /**
     * Process a frame - should be called every emulation frame
     */
    public void doFrame() {
        if (mInitialized && mClientPtr != 0) {
            mNative.nativeDoFrame(mClientPtr);
        }
    }
    
    /**
     * Set user credentials
     */
    public void setCredentials(String username, String token) {
        mUsername = username;
        mToken = token;
        Log.i(TAG, "Credentials set for user: " + username);
    }
    
    /**
     * Get username
     */
    public String getUsername() {
        return mUsername;
    }
    
    /**
     * Check if user is logged in
     */
    public boolean isLoggedIn() {
        return mUsername != null && mToken != null;
    }
    
    /**
     * Get notifications helper
     */
    public RetroAchievementsNotifications getNotifications() {
        return mNotifications;
    }
    
    // ========== RCheevosCallback Implementation ==========
    
    @Override
    public int onMemoryRead(int address, byte[] buffer, int numBytes) {
        if (mMemoryReadCallback != null) {
            return mMemoryReadCallback.readMemory(address, buffer, numBytes);
        }
        return 0;
    }
    
    @Override
    public void onServerCall(String url, String postData, long callbackPtr, long callbackDataPtr) {
        Log.d(TAG, "Server call: " + url);
        
        // Store pending request
        synchronized (mPendingRequests) {
            mPendingRequests.put(callbackPtr, callbackDataPtr);
        }
        
        // Make HTTP request
        RetroAchievementsHttpClient.HttpCallback httpCallback = new RetroAchievementsHttpClient.HttpCallback() {
            @Override
            public void onResponse(int statusCode, String responseBody) {
                Log.d(TAG, "Server response: " + statusCode);
                
                // Remove from pending
                synchronized (mPendingRequests) {
                    Long dataPtr = mPendingRequests.remove(callbackPtr);
                    if (dataPtr != null) {
                        // Deliver response to native
                        mNative.nativeServerResponse(callbackPtr, dataPtr, statusCode, responseBody);
                    }
                }
            }
            
            @Override
            public void onError(String errorMessage) {
                Log.e(TAG, "Server error: " + errorMessage);
                
                // Remove from pending and deliver error (status code 0)
                synchronized (mPendingRequests) {
                    Long dataPtr = mPendingRequests.remove(callbackPtr);
                    if (dataPtr != null) {
                        mNative.nativeServerResponse(callbackPtr, dataPtr, 0, errorMessage);
                    }
                }
            }
        };
        
        // Execute request
        if (postData != null && !postData.isEmpty()) {
            mHttpClient.post(url, postData, httpCallback);
        } else {
            mHttpClient.get(url, httpCallback);
        }
    }
}
