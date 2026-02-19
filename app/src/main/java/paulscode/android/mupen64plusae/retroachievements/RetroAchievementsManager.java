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
    private static final int HTTP_STATUS_RETRYABLE_CLIENT_ERROR = -2;
    
    private static RetroAchievementsManager sInstance;
    
    private final Context mContext;
    private final RCheevosNative mNative;
    private RetroAchievementsHttpClient mHttpClient;
    private final RetroAchievementsNotifications mNotifications;
    
    private long mClientPtr = 0;
    private volatile boolean mInitialized = false;
    private volatile boolean mShuttingDown = false;
    private volatile boolean mHardcoreEnabled = false;
    private volatile boolean mSessionActive = false;
    private String mUsername;
    private String mToken;
    private long mSessionRequestCounter = 0;
    private long mActiveSessionRequestId = 0;
    private boolean mLoginCompleted = false;
    private boolean mLoadCompleted = false;
    private boolean mLoginSucceeded = false;
    private boolean mLoadSucceeded = false;
    
    // Memory read callback - will be set by CoreService
    private volatile MemoryReadCallback mMemoryReadCallback;
    
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
        
        mInitialized = true;
        mShuttingDown = false;
        clearSessionRequestStateLocked();
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
        
        mMemoryReadCallback = null;
        clearSessionRequestStateLocked();
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
     * Check if hardcore restrictions should be enforced for the current session.
     */
    public boolean isHardcoreSessionActive() {
        return mHardcoreEnabled && mInitialized && mSessionActive && isLoggedIn();
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
     * Start a RetroAchievements game session for the provided ROM.
     */
    public synchronized boolean startSession(String romPath) {
        return startSession(romPath, null);
    }

    /**
     * Start a RetroAchievements game session for the provided ROM.
     */
    public synchronized boolean startSession(String romPath, byte[] romData) {
        if (!mInitialized || mClientPtr == 0) {
            Log.w(TAG, "Not initialized, cannot start session");
            return false;
        }

        if (!isLoggedIn()) {
            Log.w(TAG, "No credentials set, cannot start session");
            return false;
        }

        String gameHash = null;
        if (romData != null && romData.length > 0) {
            gameHash = generateHash(romData);
            if (gameHash == null || gameHash.isEmpty()) {
                Log.w(TAG, "Failed to generate game hash from ROM data, falling back to path");
            }
        }

        if ((gameHash == null || gameHash.isEmpty()) && romPath != null && !romPath.isEmpty()) {
            gameHash = generateHash(romPath);
        }

        if (gameHash == null || gameHash.isEmpty()) {
            Log.w(TAG, "Failed to generate game hash");
            return false;
        }

        try {
            long requestId = ++mSessionRequestCounter;
            resetSessionRequestStateLocked(requestId);

            boolean loginQueued = mNative.nativeBeginLoginWithToken(mClientPtr, mUsername, mToken, requestId);
            boolean loadQueued = mNative.nativeBeginIdentifyAndLoadGame(
                    mClientPtr, mNative.nativeGetN64ConsoleId(), gameHash, requestId);

            if (!loginQueued || !loadQueued) {
                // Mark unqueued operations as failed so any callback from the queued operation cannot
                // accidentally transition this request to active.
                if (!loginQueued) {
                    mLoginCompleted = true;
                    mLoginSucceeded = false;
                }
                if (!loadQueued) {
                    mLoadCompleted = true;
                    mLoadSucceeded = false;
                }
                mSessionActive = false;
                Log.w(TAG, "Failed to queue RetroAchievements session request");
                return false;
            }

            Log.i(TAG, "RetroAchievements session queued for hash: " + gameHash + " (request " + requestId + ")");
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to start RetroAchievements session", e);
            clearSessionRequestStateLocked();
            return false;
        }
    }
    
    /**
     * Process achievements - should be called periodically during emulation
     * Currently called every 500ms via CoreService periodic handler
     */
    public void doFrame() {
        if (mInitialized && mClientPtr != 0 && mSessionActive) {
            mNative.nativeDoFrame(mClientPtr);
        }
    }
    
    /**
     * Set user credentials
     */
    public synchronized void setCredentials(String username, String token) {
        mUsername = username;
        mToken = token;
        clearSessionRequestStateLocked();
        if (username == null || token == null) {
            Log.i(TAG, "Credentials cleared");
        } else {
            Log.i(TAG, "Credentials set for user: " + username);
        }
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
                String safeErrorMessage = errorMessage != null ? errorMessage : "Client request failed";
                
                // Remove from pending and deliver a client transport error for proper retry handling.
                synchronized (mPendingRequests) {
                    Long storedCallbackPtr = mPendingRequests.remove(callbackDataPtr);
                    if (storedCallbackPtr != null) {
                        mNative.nativeServerResponse(
                                storedCallbackPtr,
                                callbackDataPtr,
                                HTTP_STATUS_RETRYABLE_CLIENT_ERROR,
                                safeErrorMessage);
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

    @Override
    public synchronized void onLoginResult(long requestId, boolean success, String errorMessage) {
        if (!isActiveSessionRequestLocked(requestId)) {
            return;
        }

        mLoginCompleted = true;
        mLoginSucceeded = success;
        updateSessionActiveLocked();

        if (success) {
            Log.i(TAG, "RetroAchievements login succeeded (request " + requestId + ")");
        } else {
            Log.w(TAG, "RetroAchievements login failed (request " + requestId + "): " + errorMessage);
        }
    }

    @Override
    public synchronized void onGameLoadResult(long requestId, boolean success, String errorMessage) {
        if (!isActiveSessionRequestLocked(requestId)) {
            return;
        }

        mLoadCompleted = true;
        mLoadSucceeded = success;
        updateSessionActiveLocked();

        if (success) {
            Log.i(TAG, "RetroAchievements game load succeeded (request " + requestId + ")");
        } else {
            Log.w(TAG, "RetroAchievements game load failed (request " + requestId + "): " + errorMessage);
        }
    }

    private void resetSessionRequestStateLocked(long requestId) {
        mActiveSessionRequestId = requestId;
        mLoginCompleted = false;
        mLoadCompleted = false;
        mLoginSucceeded = false;
        mLoadSucceeded = false;
        mSessionActive = false;
    }

    private void clearSessionRequestStateLocked() {
        mActiveSessionRequestId = 0;
        mLoginCompleted = false;
        mLoadCompleted = false;
        mLoginSucceeded = false;
        mLoadSucceeded = false;
        mSessionActive = false;
    }

    private boolean isActiveSessionRequestLocked(long requestId) {
        return requestId != 0 && requestId == mActiveSessionRequestId && mInitialized && !mShuttingDown;
    }

    private void updateSessionActiveLocked() {
        // A session is active only after both async operations have completed successfully.
        mSessionActive = mLoginCompleted && mLoadCompleted && mLoginSucceeded && mLoadSucceeded;
    }
}
