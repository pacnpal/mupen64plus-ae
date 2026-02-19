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
    private RetroAchievementsHttpClient mHttpClient;
    private final RetroAchievementsNotifications mNotifications;
    
    private long mClientPtr = 0;
    private boolean mInitialized = false;
    private boolean mShuttingDown = false;
    private boolean mHardcoreEnabled = false;
    private String mUsername;
    private String mToken;
    
    // Memory read callback - will be set by CoreService
    private MemoryReadCallback mMemoryReadCallback;
    
    // Pending HTTP requests (callback data ptr -> callback ptr)
    // Using callbackDataPtr as key since it's unique per request
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
        mNotifications = new RetroAchievementsNotifications(context);
        // Don't create HttpClient here - create it in initialize()
        mHttpClient = null;
    }
    
    /**
     * Initialize RetroAchievements system
     */
    public synchronized boolean initialize() {
        if (mInitialized) {
            Log.w(TAG, "Already initialized");
            return true;
        }
        
        // Check if native library loaded
        if (!RCheevosNative.isLibraryLoaded()) {
            Log.e(TAG, "Native library not loaded");
            return false;
        }
        
        // Create HttpClient for this session
        if (mHttpClient == null) {
            mHttpClient = new RetroAchievementsHttpClient();
        }
        
        // Create native client
        mClientPtr = mNative.nativeCreateClient();
        if (mClientPtr == 0) {
            Log.e(TAG, "Failed to create native client");
            return false;
        }
        
        // Set callback handler
        mNative.nativeSetCallbackHandler(this);
        
        mInitialized = false;
        mShuttingDown = false;
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
        
        mShuttingDown = true;
        
        // Clear pending requests
        synchronized (mPendingRequests) {
            mPendingRequests.clear();
        }
        
        if (mClientPtr != 0) {
            mNative.nativeDestroyClient(mClientPtr);
            mClientPtr = 0;
        }
        
        // Shutdown and recreate HttpClient for next session
        if (mHttpClient != null) {
            mHttpClient.shutdown();
            mHttpClient = null;
        }
        
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
     * Process achievements - should be called periodically during emulation
     * Currently called every 500ms via CoreService periodic handler
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
        
        // Store pending request using callbackDataPtr as key (unique per request)
        synchronized (mPendingRequests) {
            if (mShuttingDown) {
                Log.w(TAG, "Ignoring server call during shutdown");
                return;
            }
            mPendingRequests.put(callbackDataPtr, callbackPtr);
        }
        
        // Make HTTP request
        RetroAchievementsHttpClient.HttpCallback httpCallback = new RetroAchievementsHttpClient.HttpCallback() {
            @Override
            public void onResponse(int statusCode, String responseBody) {
                // Guard against shutdown
                if (mShuttingDown || !mInitialized) {
                    Log.w(TAG, "Dropping response - manager shut down");
                    return;
                }
                
                Log.d(TAG, "Server response: " + statusCode);
                
                // Remove from pending using callbackDataPtr
                synchronized (mPendingRequests) {
                    Long storedCallbackPtr = mPendingRequests.remove(callbackDataPtr);
                    if (storedCallbackPtr != null) {
                        // Deliver response to native
                        mNative.nativeServerResponse(storedCallbackPtr, callbackDataPtr, statusCode, responseBody);
                    }
                }
            }
            
            @Override
            public void onError(String errorMessage) {
                // Guard against shutdown
                if (mShuttingDown || !mInitialized) {
                    Log.w(TAG, "Dropping error - manager shut down");
                    return;
                }
                
                Log.e(TAG, "Server error: " + errorMessage);
                
                // Remove from pending and deliver error (status code 0)
                synchronized (mPendingRequests) {
                    Long storedCallbackPtr = mPendingRequests.remove(callbackDataPtr);
                    if (storedCallbackPtr != null) {
                        mNative.nativeServerResponse(storedCallbackPtr, callbackDataPtr, 0, errorMessage);
                    }
                }
            }
        };
        
        // Execute request
        if (mHttpClient != null) {
            if (postData != null && !postData.isEmpty()) {
                mHttpClient.post(url, postData, httpCallback);
            } else {
                mHttpClient.get(url, httpCallback);
            }
        } else {
            Log.e(TAG, "HttpClient is null, cannot make request");
        }
    }
}
