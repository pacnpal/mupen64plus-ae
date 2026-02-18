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
    
    static {
        try {
            System.loadLibrary("rcheevos");
            Log.i(TAG, "rcheevos library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load rcheevos library", e);
        }
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
}
