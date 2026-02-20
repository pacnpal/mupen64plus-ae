/*
 * Mupen64PlusAE, an N64 emulator for the Android platform
 * 
 * RetroAchievements Integration - Main Manager
 * 
 * Central manager for all RetroAchievements functionality.
 */
package paulscode.android.mupen64plusae.retroachievements;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import paulscode.android.mupen64plusae.R;
import paulscode.android.mupen64plusae.persistent.AppData;

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
    private String mPassword;
    private boolean mUseTokenLogin = false;
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
     * Listener interface for achievement events, implemented by GameActivity.
     */
    public interface AchievementEventListener {
        void onAchievementTriggered(int id, String title, String description, String badgeUrl, int points);
        void onAchievementProgressUpdated(int id, String title, String measuredProgress, float measuredPercent);
        void onAchievementProgressHidden();
        void onAchievementChallengeIndicatorShow(int id, String title, String badgeUrl);
        void onAchievementChallengeIndicatorHide(int id);
        void onGameCompleted(String gameTitle);
        void onSubsetCompleted(String subsetTitle);
        void onGameSessionStarted(String gameTitle, String badgeUrl, int numAchievements, int numUnlocked);
        void onHardcoreReset();
        void onLeaderboardStarted(String title, String description);
        void onLeaderboardFailed(String title);
        void onLeaderboardSubmitted(String title, String score, String bestScore, int newRank, int numEntries);
        void onLeaderboardTrackerShow(int trackerId, String display);
        void onLeaderboardTrackerHide(int trackerId);
        void onLeaderboardTrackerUpdate(int trackerId, String display);
        void onServerError(String api, String errorMessage);
        void onConnectionChanged(boolean connected);
    }

    private volatile AchievementEventListener mEventListener;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private String mCurrentGameTitle;

    /**
     * Set the achievement event listener (typically GameActivity).
     */
    public void setEventListener(AchievementEventListener listener) {
        mEventListener = listener;
    }

    /**
     * Clear the achievement event listener.
     */
    public void clearEventListener() {
        mEventListener = null;
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
            // Unload the game before destroying the client
            if (mSessionActive) {
                mNative.nativeUnloadGame(mClientPtr);
                mSessionActive = false;
            }
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

            boolean loginQueued;
            if (mUseTokenLogin) {
                loginQueued = mNative.nativeBeginLoginWithToken(mClientPtr, mUsername, mPassword, requestId);
            } else {
                loginQueued = mNative.nativeBeginLoginWithPassword(mClientPtr, mUsername, mPassword, requestId);
            }
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
     * Process achievements - called every emulation frame.
     * Invoked from CoreService.onFrameRendered() so rcheevos can
     * detect transient memory conditions for achievement evaluation.
     */
    public void doFrame() {
        if (mInitialized && mClientPtr != 0 && mSessionActive) {
            mNative.nativeDoFrame(mClientPtr);
        }
    }
    
    /**
     * Set user credentials
     */
    public synchronized void setCredentials(String username, String password) {
        mUsername = username;
        mPassword = password;
        mUseTokenLogin = false;
        clearSessionRequestStateLocked();
        if (username == null || password == null) {
            Log.i(TAG, "Credentials cleared");
        } else {
            Log.i(TAG, "Password credentials set for user: " + username);
        }
    }

    /**
     * Set user credentials using an API token.
     */
    public synchronized void setTokenCredentials(String username, String token) {
        mUsername = username;
        mPassword = token;
        mUseTokenLogin = username != null && token != null;
        clearSessionRequestStateLocked();
        if (username == null || token == null) {
            Log.i(TAG, "Credentials cleared");
        } else {
            Log.i(TAG, "Token credentials set for user: " + username);
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
        return mUsername != null && mPassword != null;
    }
    
    // ========== Session Lifecycle ==========

    /**
     * Reset achievement and leaderboard tracking state. Call when the emulator resets.
     */
    public void reset() {
        if (mInitialized && mClientPtr != 0 && mSessionActive) {
            mNative.nativeReset(mClientPtr);
            Log.i(TAG, "Achievement state reset");
        }
    }

    /**
     * Unload the current game from rcheevos. Call when the game session ends.
     */
    public void unloadGame() {
        if (mInitialized && mClientPtr != 0) {
            mSessionActive = false;
            mNative.nativeUnloadGame(mClientPtr);
            mCurrentGameTitle = null;
            Log.i(TAG, "Game unloaded from rcheevos");
        }
    }

    /**
     * Get the API token for the logged-in user. Available after successful login.
     * @return API token string, or null
     */
    public String getUserToken() {
        if (!mInitialized || mClientPtr == 0) return null;
        return mNative.nativeGetUserToken(mClientPtr);
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
            
            // Remove from pending and deliver a client transport error so native can retry or fail cleanly
            synchronized (mPendingRequests) {
                Long storedCallbackPtr = mPendingRequests.remove(callbackDataPtr);
                if (storedCallbackPtr != null) {
                    // Use status code 0 to indicate client-side error
                    mNative.nativeServerResponse(
                            storedCallbackPtr,
                            callbackDataPtr,
                            0,
                            "HTTP client unavailable");
                }
            }
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
            // Persist API token so future sessions can use token login
            if (!mUseTokenLogin) {
                String token = getUserToken();
                if (token != null && !token.isEmpty()) {
                    try {
                        AppData appData = new AppData(mContext);
                        appData.setRetroAchievementsToken(token);
                        Log.i(TAG, "API token saved for future sessions");
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to save API token", e);
                    }
                }
            }
        } else {
            Log.w(TAG, "RetroAchievements login failed (request " + requestId + "): " + errorMessage);
            final String msg = errorMessage;
            mMainHandler.post(() -> Toast.makeText(mContext,
                    mContext.getString(R.string.ra_login_failed, msg != null ? msg : "unknown error"),
                    Toast.LENGTH_LONG).show());
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
            final String msg = errorMessage;
            mMainHandler.post(() -> Toast.makeText(mContext,
                    mContext.getString(R.string.ra_game_load_failed, msg != null ? msg : "unknown error"),
                    Toast.LENGTH_LONG).show());
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

    // ========== Save State Serialization ==========

    /**
     * Serialize achievement progress for embedding in save states.
     * @return Byte array of serialized progress, or null on failure
     */
    public byte[] serializeProgress() {
        if (!mInitialized || mClientPtr == 0 || !mSessionActive) return null;
        return mNative.nativeSerializeProgress(mClientPtr);
    }

    /**
     * Deserialize achievement progress after loading a save state.
     * @param data Byte array previously returned by serializeProgress()
     * @return true if deserialization succeeded
     */
    public synchronized boolean deserializeProgress(byte[] data) {
        if (!mInitialized || mClientPtr == 0 || data == null) return false;
        final boolean sessionStarting = mActiveSessionRequestId != 0 && (!mLoginCompleted || !mLoadCompleted);
        final boolean sessionFailed = (mLoginCompleted && !mLoginSucceeded)
                || (mLoadCompleted && !mLoadSucceeded);
        if (!mSessionActive && (!sessionStarting || sessionFailed)) return false;
        return mNative.nativeDeserializeProgress(mClientPtr, data);
    }

    /**
     * Check if it is safe to pause the emulator without affecting achievement evaluation.
     * @return true if pausing is safe (or if RA is not active)
     */
    public boolean canPause() {
        if (!mInitialized || mClientPtr == 0 || !mSessionActive) return true;
        return mNative.nativeCanPause(mClientPtr);
    }

    /**
     * Get user game summary with achievement counts and points.
     * @return int array [numCore, numUnlocked, pointsCore, pointsUnlocked], or null
     */
    public int[] getUserGameSummary() {
        if (!mInitialized || mClientPtr == 0 || !mSessionActive) return null;
        return mNative.nativeGetUserGameSummary(mClientPtr);
    }

    /**
     * Get current rich presence message describing what the player is doing.
     * @return Rich presence string, or null if unavailable
     */
    public String getRichPresenceMessage() {
        if (!mInitialized || mClientPtr == 0 || !mSessionActive) return null;
        return mNative.nativeGetRichPresenceMessage(mClientPtr);
    }

    /**
     * Get the achievement list as a JSON string for display in the achievement list screen.
     * @return JSON string with buckets and achievements, or null
     */
    public String getAchievementListJson() {
        if (!mInitialized || mClientPtr == 0 || !mSessionActive) return null;
        return mNative.nativeGetAchievementListJson(mClientPtr);
    }

    /**
     * Check if a RetroAchievements session is currently active.
     */
    public boolean isSessionActive() {
        return mInitialized && mSessionActive;
    }

    // ========== Achievement Event Callbacks ==========

    @Override
    public void onAchievementTriggered(int id, String title, String description, String badgeUrl, int points) {
        Log.i(TAG, "Achievement triggered: " + title + " (" + points + " pts)");
        final AchievementEventListener listener = mEventListener;
        if (listener != null) {
            mMainHandler.post(() -> listener.onAchievementTriggered(id, title, description, badgeUrl, points));
        }
    }

    @Override
    public void onAchievementProgressUpdated(int id, String title, String measuredProgress, float measuredPercent) {
        Log.d(TAG, "Achievement progress: " + title + " - " + measuredProgress);
        final AchievementEventListener listener = mEventListener;
        if (listener != null) {
            mMainHandler.post(() -> listener.onAchievementProgressUpdated(id, title, measuredProgress, measuredPercent));
        }
    }

    @Override
    public void onAchievementProgressHidden() {
        Log.d(TAG, "Achievement progress hidden");
        final AchievementEventListener listener = mEventListener;
        if (listener != null) {
            mMainHandler.post(listener::onAchievementProgressHidden);
        }
    }

    @Override
    public void onGameCompleted() {
        Log.i(TAG, "Game completed!");
        final AchievementEventListener listener = mEventListener;
        if (listener != null) {
            final String title = mCurrentGameTitle;
            mMainHandler.post(() -> listener.onGameCompleted(title != null ? title : ""));
        }
    }

    @Override
    public void onSubsetCompleted(String subsetTitle) {
        Log.i(TAG, "Subset completed: " + subsetTitle);
        final AchievementEventListener listener = mEventListener;
        if (listener != null) {
            mMainHandler.post(() -> listener.onSubsetCompleted(subsetTitle));
        }
    }

    @Override
    public void onHardcoreReset() {
        Log.i(TAG, "Hardcore reset requested");
        final AchievementEventListener listener = mEventListener;
        if (listener != null) {
            mMainHandler.post(listener::onHardcoreReset);
        }
    }

    @Override
    public void onGameSessionStarted(String gameTitle, String badgeUrl) {
        Log.i(TAG, "Game session started: " + gameTitle);
        mCurrentGameTitle = gameTitle;
        int numAchievements = 0;
        int numUnlocked = 0;
        int[] summary = getUserGameSummary();
        if (summary != null) {
            numAchievements = summary[0];
            numUnlocked = summary[1];
        }
        final int totalAchievements = numAchievements;
        final int unlockedAchievements = numUnlocked;
        final AchievementEventListener listener = mEventListener;
        if (listener != null) {
            mMainHandler.post(() -> listener.onGameSessionStarted(gameTitle, badgeUrl, totalAchievements, unlockedAchievements));
        }
    }

    @Override
    public void onAchievementChallengeIndicatorShow(int id, String title, String badgeUrl) {
        Log.i(TAG, "Challenge indicator show: " + title);
        final AchievementEventListener listener = mEventListener;
        if (listener != null) {
            mMainHandler.post(() -> listener.onAchievementChallengeIndicatorShow(id, title, badgeUrl));
        }
    }

    @Override
    public void onAchievementChallengeIndicatorHide(int id) {
        Log.d(TAG, "Challenge indicator hide: " + id);
        final AchievementEventListener listener = mEventListener;
        if (listener != null) {
            mMainHandler.post(() -> listener.onAchievementChallengeIndicatorHide(id));
        }
    }

    @Override
    public void onLeaderboardStarted(String title, String description) {
        Log.i(TAG, "Leaderboard started: " + title);
        final AchievementEventListener listener = mEventListener;
        if (listener != null) {
            mMainHandler.post(() -> listener.onLeaderboardStarted(title, description));
        }
    }

    @Override
    public void onLeaderboardFailed(String title) {
        Log.i(TAG, "Leaderboard failed: " + title);
        final AchievementEventListener listener = mEventListener;
        if (listener != null) {
            mMainHandler.post(() -> listener.onLeaderboardFailed(title));
        }
    }

    @Override
    public void onLeaderboardSubmitted(String title, String score, String bestScore, int newRank, int numEntries) {
        Log.i(TAG, "Leaderboard submitted: " + title + " - " + score);
        final AchievementEventListener listener = mEventListener;
        if (listener != null) {
            mMainHandler.post(() -> listener.onLeaderboardSubmitted(title, score, bestScore, newRank, numEntries));
        }
    }

    @Override
    public void onLeaderboardTrackerShow(int trackerId, String display) {
        Log.d(TAG, "Leaderboard tracker show: " + trackerId + " " + display);
        final AchievementEventListener listener = mEventListener;
        if (listener != null) {
            mMainHandler.post(() -> listener.onLeaderboardTrackerShow(trackerId, display));
        }
    }

    @Override
    public void onLeaderboardTrackerHide(int trackerId) {
        Log.d(TAG, "Leaderboard tracker hide: " + trackerId);
        final AchievementEventListener listener = mEventListener;
        if (listener != null) {
            mMainHandler.post(() -> listener.onLeaderboardTrackerHide(trackerId));
        }
    }

    @Override
    public void onLeaderboardTrackerUpdate(int trackerId, String display) {
        Log.d(TAG, "Leaderboard tracker update: " + trackerId + " " + display);
        final AchievementEventListener listener = mEventListener;
        if (listener != null) {
            mMainHandler.post(() -> listener.onLeaderboardTrackerUpdate(trackerId, display));
        }
    }

    @Override
    public void onServerError(String api, String errorMessage) {
        Log.e(TAG, "Server error [" + api + "]: " + errorMessage);
        final AchievementEventListener listener = mEventListener;
        if (listener != null) {
            mMainHandler.post(() -> listener.onServerError(api, errorMessage));
        }
    }

    @Override
    public void onConnectionChanged(boolean connected) {
        Log.i(TAG, "Connection " + (connected ? "restored" : "lost"));
        final AchievementEventListener listener = mEventListener;
        if (listener != null) {
            mMainHandler.post(() -> listener.onConnectionChanged(connected));
        }
    }
}
