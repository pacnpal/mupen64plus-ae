/*
 * Mupen64PlusAE, an N64 emulator for the Android platform
 * 
 * RetroAchievements Integration - Native Bridge
 * 
 * This class provides the JNI interface to the rcheevos native library.
 */
package paulscode.android.mupen64plusae.retroachievements;

import android.util.Log;

/**
 * JNI Bridge to the rcheevos native library
 */
public class RCheevosNative {
    private static final String TAG = "RCheevosNative";
    private static volatile boolean sLibraryLoaded = false;
    
    static {
        try {
            System.loadLibrary("rcheevos");
            sLibraryLoaded = true;
            Log.i(TAG, "rcheevos library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load rcheevos library", e);
            throw e;
        }
    }
    
    /**
     * Check if native library is loaded
     * @return true if library loaded successfully
     */
    public static boolean isLibraryLoaded() {
        return sLibraryLoaded;
    }
    
    /**
     * Callback interface for native interactions
     */
    public interface RCheevosCallback {
        /**
         * Called when rcheevos needs to read emulator memory
         * @param address Memory address to read from
         * @param buffer Buffer to fill with memory data
         * @param numBytes Number of bytes to read
         * @return Number of bytes actually read
         */
        int onMemoryRead(int address, byte[] buffer, int numBytes);
        
        /**
         * Called when rcheevos needs to make an HTTP request
         * @param url URL to request
         * @param postData POST data (null for GET requests)
         * @param callbackPtr Native callback pointer
         * @param callbackDataPtr Native callback data pointer
         */
        void onServerCall(String url, String postData, long callbackPtr, long callbackDataPtr);

        /**
         * Called when login request completes.
         * @param requestId Session request id passed when the login was queued
         * @param success True if login succeeded
         * @param errorMessage Error detail when login fails (nullable)
         */
        void onLoginResult(long requestId, boolean success, String errorMessage);

        /**
         * Called when game load request completes.
         * @param requestId Session request id passed when the load was queued
         * @param success True if game load succeeded
         * @param errorMessage Error detail when game load fails (nullable)
         */
        void onGameLoadResult(long requestId, boolean success, String errorMessage);

        /**
         * Called when an achievement is triggered (unlocked).
         */
        void onAchievementTriggered(int id, String title, String description, String badgeUrl, int points);

        /**
         * Called when achievement progress should be shown or updated.
         */
        void onAchievementProgressUpdated(int id, String title, String measuredProgress, float measuredPercent);

        /**
         * Called when achievement progress indicator should be hidden.
         */
        void onAchievementProgressHidden();

        /**
         * Called when all achievements for the game have been earned.
         */
        void onGameCompleted();

        /**
         * Called when all achievements for a subset have been earned.
         */
        void onSubsetCompleted(String subsetTitle);

        /**
         * Called when hardcore mode triggers a reset.
         */
        void onHardcoreReset();

        /**
         * Called when a game session starts successfully with game info.
         */
        void onGameSessionStarted(String gameTitle, String badgeUrl);

        /**
         * Called when a challenge achievement indicator should be shown.
         */
        void onAchievementChallengeIndicatorShow(int id, String title, String badgeUrl);

        /**
         * Called when a challenge achievement indicator should be hidden.
         */
        void onAchievementChallengeIndicatorHide(int id);

        /**
         * Called when a leaderboard attempt has started.
         */
        void onLeaderboardStarted(String title, String description);

        /**
         * Called when a leaderboard attempt has failed.
         */
        void onLeaderboardFailed(String title);

        /**
         * Called when a leaderboard score has been submitted.
         */
        void onLeaderboardSubmitted(String title, String score, String bestScore, int newRank, int numEntries);

        /**
         * Called when a leaderboard tracker should be shown.
         */
        void onLeaderboardTrackerShow(int trackerId, String display);

        /**
         * Called when a leaderboard tracker should be hidden.
         */
        void onLeaderboardTrackerHide(int trackerId);

        /**
         * Called when a leaderboard tracker display value has changed.
         */
        void onLeaderboardTrackerUpdate(int trackerId, String display);

        /**
         * Called when a server error occurs.
         */
        void onServerError(String api, String errorMessage);

        /**
         * Called when connection state changes (disconnected or reconnected).
         */
        void onConnectionChanged(boolean connected);
    }
    
    /**
     * Create a new rcheevos client instance
     * @return Client pointer, or 0 on failure
     */
    public native long nativeCreateClient();
    
    /**
     * Destroy an rcheevos client instance
     * @param clientPtr Client pointer from nativeCreateClient()
     */
    public native void nativeDestroyClient(long clientPtr);
    
    /**
     * Set the callback handler for native callbacks
     * @param handler Callback handler instance
     */
    public native void nativeSetCallbackHandler(RCheevosCallback handler);
    
    /**
     * Enable or disable hardcore mode
     * @param clientPtr Client pointer
     * @param enabled True to enable hardcore mode
     */
    public native void nativeSetHardcoreEnabled(long clientPtr, boolean enabled);

    /**
     * Get current hardcore mode state from native runtime.
     * @param clientPtr Client pointer
     * @return true if hardcore mode is enabled
     */
    public native boolean nativeGetHardcoreEnabled(long clientPtr);
    
    /**
     * Generate a RetroAchievements hash for a game
     * @param consoleId Console ID (use nativeGetN64ConsoleId() for N64)
     * @param romPath Path to ROM file (or null if using romData)
     * @param romData ROM data in memory (or null if using romPath)
     * @return Hash string, or null on failure
     */
    public native String nativeGenerateHash(int consoleId, String romPath, byte[] romData);
    
    /**
     * Process a frame - should be called every emulation frame
     * @param clientPtr Client pointer
     */
    public native void nativeDoFrame(long clientPtr);
    
    /**
     * Get the Nintendo 64 console ID constant
     * @return N64 console ID for use with other methods
     */
    public native int nativeGetN64ConsoleId();
    
    /**
     * Deliver HTTP response to native callback
     * @param callbackPtr Native callback pointer from onServerCall
     * @param callbackDataPtr Native callback data pointer from onServerCall
     * @param httpStatusCode HTTP status code
     * @param responseBody Response body string
     */
    public native void nativeServerResponse(long callbackPtr, long callbackDataPtr,
                                           int httpStatusCode, String responseBody);
    
    /**
     * Begin login with token
     * @param clientPtr Client pointer
     * @param username Username
     * @param token API token
     * @param callbackPtr Callback pointer (will be called on completion)
     */
    public native boolean nativeBeginLoginWithToken(long clientPtr, String username, String token, long callbackPtr);
    
    /**
     * Begin login with password
     * @param clientPtr Client pointer
     * @param username Username
     * @param password Password
     * @param callbackPtr Callback pointer (will be called on completion)
     */
    public native boolean nativeBeginLoginWithPassword(long clientPtr, String username, String password, long callbackPtr);

    /**
     * Begin identify and load game
     * @param clientPtr Client pointer
     * @param consoleId Console ID
     * @param gameHash Game hash string
     * @param callbackPtr Callback pointer (will be called on completion)
     */
    public native boolean nativeBeginIdentifyAndLoadGame(long clientPtr, int consoleId, String gameHash, long callbackPtr);

    /**
     * Serialize achievement progress state for save states
     * @param clientPtr Client pointer
     * @return Byte array of serialized progress, or null on failure
     */
    public native byte[] nativeSerializeProgress(long clientPtr);

    /**
     * Deserialize achievement progress state from a save state
     * @param clientPtr Client pointer
     * @param data Byte array of serialized progress
     * @return true if deserialization succeeded
     */
    public native boolean nativeDeserializeProgress(long clientPtr, byte[] data);

    /**
     * Check if it is safe to pause without affecting achievement evaluation
     * @param clientPtr Client pointer
     * @return true if pausing is safe
     */
    public native boolean nativeCanPause(long clientPtr);

    /**
     * Get user game summary (achievement counts and points)
     * @param clientPtr Client pointer
     * @return int array [numCore, numUnlocked, pointsCore, pointsUnlocked], or null
     */
    public native int[] nativeGetUserGameSummary(long clientPtr);

    /**
     * Get current rich presence message
     * @param clientPtr Client pointer
     * @return Rich presence string, or null if unavailable
     */
    public native String nativeGetRichPresenceMessage(long clientPtr);

    /**
     * Reset achievement and leaderboard state. Call when the emulator is reset.
     * @param clientPtr Client pointer
     */
    public native void nativeReset(long clientPtr);

    /**
     * Unload the current game from rcheevos. Call when the game session ends.
     * @param clientPtr Client pointer
     */
    public native void nativeUnloadGame(long clientPtr);

    /**
     * Get the API token for the logged-in user.
     * @param clientPtr Client pointer
     * @return API token string, or null if not logged in
     */
    public native String nativeGetUserToken(long clientPtr);

    /**
     * Get the achievement list as a JSON string.
     * @param clientPtr Client pointer
     * @return JSON string with buckets and achievements, or null
     */
    public native String nativeGetAchievementListJson(long clientPtr);
}
