/*
 * Mupen64PlusAE RetroAchievements Integration
 * JNI Bridge for rcheevos library
 */

#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include "rc_client.h"
#include "rc_hash.h"
#include "rc_consoles.h"

#define LOG_TAG "RCheevosJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Safe NewStringUTF that returns empty string on NULL/OOM instead of crashing
static jstring safe_new_string_utf(JNIEnv* env, const char* str) {
    jstring result = (*env)->NewStringUTF(env, str ? str : "");
    if (result == NULL) {
        // OOM - clear pending exception and try empty string
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        result = (*env)->NewStringUTF(env, "");
        if (result == NULL && (*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
    }
    return result;
}

// Global rc_client instance
static rc_client_t* g_client = NULL;
static JavaVM* g_jvm = NULL;
static jobject g_callback_handler = NULL;

static void notify_session_callback(const char* method_name, jlong request_id, int success, const char* error_message) {
    if (g_jvm == NULL || g_callback_handler == NULL) {
        return;
    }

    JNIEnv* env;
    jint result = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    int attached = 0;

    if (result == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != 0) {
            LOGE("Failed to attach thread for %s", method_name);
            return;
        }
        attached = 1;
    } else if (result != JNI_OK) {
        LOGE("Failed to get JNIEnv for %s", method_name);
        return;
    }

    jclass cls = (*env)->GetObjectClass(env, g_callback_handler);
    if (cls == NULL) {
        if (attached) {
            (*g_jvm)->DetachCurrentThread(g_jvm);
        }
        return;
    }

    jmethodID mid = (*env)->GetMethodID(env, cls, method_name, "(JZLjava/lang/String;)V");
    if (mid == NULL) {
        LOGE("Could not find %s method", method_name);
        (*env)->DeleteLocalRef(env, cls);
        if (attached) {
            (*g_jvm)->DetachCurrentThread(g_jvm);
        }
        return;
    }

    jstring jerror = (error_message != NULL) ? safe_new_string_utf(env, error_message) : NULL;
    (*env)->CallVoidMethod(env, g_callback_handler, mid, request_id, success ? JNI_TRUE : JNI_FALSE, jerror);

    if (jerror != NULL) {
        (*env)->DeleteLocalRef(env, jerror);
    }
    (*env)->DeleteLocalRef(env, cls);

    if (attached) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

// Memory read callback - will be called by rcheevos to read emulator memory
static uint32_t memory_read_callback(uint32_t address, uint8_t* buffer, uint32_t num_bytes, rc_client_t* client) {
    JNIEnv* env;
    jint result = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    
    if (result == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != 0) {
            LOGE("Failed to attach thread for memory read");
            return 0;
        }
    }
    
    if (g_callback_handler == NULL) {
        LOGE("No callback handler set");
        return 0;
    }
    
    jclass cls = (*env)->GetObjectClass(env, g_callback_handler);
    jmethodID mid = (*env)->GetMethodID(env, cls, "onMemoryRead", "(I[BI)I");
    
    if (mid == NULL) {
        LOGE("Could not find onMemoryRead method");
        return 0;
    }
    
    jbyteArray jbuffer = (*env)->NewByteArray(env, num_bytes);
    jint bytes_read = (*env)->CallIntMethod(env, g_callback_handler, mid, (jint)address, jbuffer, (jint)num_bytes);
    
    if (bytes_read > 0) {
        (*env)->GetByteArrayRegion(env, jbuffer, 0, bytes_read, (jbyte*)buffer);
    }
    
    (*env)->DeleteLocalRef(env, jbuffer);
    (*env)->DeleteLocalRef(env, cls);
    
    return (uint32_t)bytes_read;
}

// Server callback - will be called by rcheevos to make HTTP requests
static void server_call_callback(const rc_api_request_t* request,
                                 rc_client_server_callback_t callback,
                                 void* callback_data,
                                 rc_client_t* client) {
    JNIEnv* env;
    jint result = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    
    if (result == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != 0) {
            LOGE("Failed to attach thread for server call");
            return;
        }
    }
    
    if (g_callback_handler == NULL) {
        LOGE("No callback handler set");
        return;
    }
    
    jclass cls = (*env)->GetObjectClass(env, g_callback_handler);
    jmethodID mid = (*env)->GetMethodID(env, cls, "onServerCall", 
                                       "(Ljava/lang/String;Ljava/lang/String;JJ)V");
    
    if (mid == NULL) {
        LOGE("Could not find onServerCall method");
        return;
    }
    
    jstring jurl = safe_new_string_utf(env, request->url);
    jstring jpost_data = request->post_data ? safe_new_string_utf(env, request->post_data) : NULL;
    
    (*env)->CallVoidMethod(env, g_callback_handler, mid, jurl, jpost_data, 
                          (jlong)(intptr_t)callback, (jlong)(intptr_t)callback_data);
    
    (*env)->DeleteLocalRef(env, jurl);
    if (jpost_data) (*env)->DeleteLocalRef(env, jpost_data);
    (*env)->DeleteLocalRef(env, cls);
}

// Log message callback
static void log_message_callback(const char* message, const rc_client_t* client) {
    LOGI("%s", message);
}

// Helper to get a JNIEnv, attaching if needed. Sets *attached = 1 if newly attached.
static JNIEnv* get_jni_env(int* attached) {
    *attached = 0;
    JNIEnv* env;
    jint result = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    if (result == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != 0) return NULL;
        *attached = 1;
    } else if (result != JNI_OK) {
        return NULL;
    }
    return env;
}

// Event handler callback - dispatches rcheevos events to Java
static void event_handler_callback(const rc_client_event_t* event, rc_client_t* client) {
    if (g_jvm == NULL || g_callback_handler == NULL) return;

    int attached = 0;
    JNIEnv* env = get_jni_env(&attached);
    if (env == NULL) return;

    jclass cls = (*env)->GetObjectClass(env, g_callback_handler);
    if (cls == NULL) {
        if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
        return;
    }

    switch (event->type) {
        case RC_CLIENT_EVENT_ACHIEVEMENT_TRIGGERED: {
            const rc_client_achievement_t* ach = event->achievement;
            if (ach == NULL) break;
            jmethodID mid = (*env)->GetMethodID(env, cls, "onAchievementTriggered",
                "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V");
            if (mid == NULL) break;
            jstring jtitle = safe_new_string_utf(env, ach->title);
            jstring jdesc = safe_new_string_utf(env, ach->description);
            jstring jbadge = safe_new_string_utf(env, ach->badge_url);
            (*env)->CallVoidMethod(env, g_callback_handler, mid,
                (jint)ach->id, jtitle, jdesc, jbadge, (jint)ach->points);
            (*env)->DeleteLocalRef(env, jtitle);
            (*env)->DeleteLocalRef(env, jdesc);
            (*env)->DeleteLocalRef(env, jbadge);
            break;
        }
        case RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_SHOW:
        case RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_UPDATE: {
            const rc_client_achievement_t* ach = event->achievement;
            if (ach == NULL) break;
            jmethodID mid = (*env)->GetMethodID(env, cls, "onAchievementProgressUpdated",
                "(ILjava/lang/String;Ljava/lang/String;F)V");
            if (mid == NULL) break;
            jstring jtitle = safe_new_string_utf(env, ach->title);
            jstring jprogress = safe_new_string_utf(env, ach->measured_progress);
            (*env)->CallVoidMethod(env, g_callback_handler, mid,
                (jint)ach->id, jtitle, jprogress, (jfloat)ach->measured_percent);
            (*env)->DeleteLocalRef(env, jtitle);
            (*env)->DeleteLocalRef(env, jprogress);
            break;
        }
        case RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_HIDE: {
            jmethodID mid = (*env)->GetMethodID(env, cls, "onAchievementProgressHidden", "()V");
            if (mid != NULL) {
                (*env)->CallVoidMethod(env, g_callback_handler, mid);
            }
            break;
        }
        case RC_CLIENT_EVENT_GAME_COMPLETED: {
            jmethodID mid = (*env)->GetMethodID(env, cls, "onGameCompleted", "()V");
            if (mid != NULL) {
                (*env)->CallVoidMethod(env, g_callback_handler, mid);
            }
            break;
        }
        case RC_CLIENT_EVENT_SUBSET_COMPLETED: {
            const rc_client_subset_t* subset = event->subset;
            if (subset == NULL) break;
            jmethodID mid = (*env)->GetMethodID(env, cls, "onSubsetCompleted",
                "(Ljava/lang/String;)V");
            if (mid == NULL) break;
            jstring jtitle = safe_new_string_utf(env, subset->title);
            (*env)->CallVoidMethod(env, g_callback_handler, mid, jtitle);
            (*env)->DeleteLocalRef(env, jtitle);
            break;
        }
        case RC_CLIENT_EVENT_RESET: {
            jmethodID mid = (*env)->GetMethodID(env, cls, "onHardcoreReset", "()V");
            if (mid != NULL) {
                (*env)->CallVoidMethod(env, g_callback_handler, mid);
            }
            break;
        }
        case RC_CLIENT_EVENT_ACHIEVEMENT_CHALLENGE_INDICATOR_SHOW: {
            const rc_client_achievement_t* ach = event->achievement;
            if (ach == NULL) break;
            jmethodID mid = (*env)->GetMethodID(env, cls, "onAchievementChallengeIndicatorShow",
                "(ILjava/lang/String;Ljava/lang/String;)V");
            if (mid == NULL) break;
            jstring jtitle = safe_new_string_utf(env, ach->title);
            jstring jbadge = safe_new_string_utf(env, ach->badge_url);
            (*env)->CallVoidMethod(env, g_callback_handler, mid, (jint)ach->id, jtitle, jbadge);
            (*env)->DeleteLocalRef(env, jtitle);
            (*env)->DeleteLocalRef(env, jbadge);
            break;
        }
        case RC_CLIENT_EVENT_ACHIEVEMENT_CHALLENGE_INDICATOR_HIDE: {
            const rc_client_achievement_t* ach = event->achievement;
            if (ach == NULL) break;
            jmethodID mid = (*env)->GetMethodID(env, cls, "onAchievementChallengeIndicatorHide", "(I)V");
            if (mid != NULL) {
                (*env)->CallVoidMethod(env, g_callback_handler, mid, (jint)ach->id);
            }
            break;
        }
        case RC_CLIENT_EVENT_LEADERBOARD_STARTED: {
            const rc_client_leaderboard_t* lb = event->leaderboard;
            if (lb == NULL) break;
            jmethodID mid = (*env)->GetMethodID(env, cls, "onLeaderboardStarted",
                "(Ljava/lang/String;Ljava/lang/String;)V");
            if (mid == NULL) break;
            jstring jtitle = safe_new_string_utf(env, lb->title);
            jstring jdesc = safe_new_string_utf(env, lb->description);
            (*env)->CallVoidMethod(env, g_callback_handler, mid, jtitle, jdesc);
            (*env)->DeleteLocalRef(env, jtitle);
            (*env)->DeleteLocalRef(env, jdesc);
            break;
        }
        case RC_CLIENT_EVENT_LEADERBOARD_FAILED: {
            const rc_client_leaderboard_t* lb = event->leaderboard;
            if (lb == NULL) break;
            jmethodID mid = (*env)->GetMethodID(env, cls, "onLeaderboardFailed", "(Ljava/lang/String;)V");
            if (mid == NULL) break;
            jstring jtitle = safe_new_string_utf(env, lb->title);
            (*env)->CallVoidMethod(env, g_callback_handler, mid, jtitle);
            (*env)->DeleteLocalRef(env, jtitle);
            break;
        }
        case RC_CLIENT_EVENT_LEADERBOARD_SUBMITTED: {
            // Score submitted; ranking info arrives later via LEADERBOARD_SCOREBOARD
            const rc_client_leaderboard_t* lb = event->leaderboard;
            if (lb == NULL) break;
            jmethodID mid = (*env)->GetMethodID(env, cls, "onLeaderboardSubmitted",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;II)V");
            if (mid == NULL) break;
            jstring jtitle = safe_new_string_utf(env, lb->title);
            jstring jscore = safe_new_string_utf(env, lb->tracker_value);
            jstring jbest = safe_new_string_utf(env, "");
            (*env)->CallVoidMethod(env, g_callback_handler, mid, jtitle, jscore, jbest, (jint)0, (jint)0);
            (*env)->DeleteLocalRef(env, jtitle);
            (*env)->DeleteLocalRef(env, jscore);
            (*env)->DeleteLocalRef(env, jbest);
            break;
        }
        case RC_CLIENT_EVENT_LEADERBOARD_SCOREBOARD: {
            const rc_client_leaderboard_t* lb = event->leaderboard;
            const rc_client_leaderboard_scoreboard_t* sb = event->leaderboard_scoreboard;
            if (lb == NULL || sb == NULL) break;
            jmethodID mid = (*env)->GetMethodID(env, cls, "onLeaderboardSubmitted",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;II)V");
            if (mid == NULL) break;
            jstring jtitle = safe_new_string_utf(env, lb->title);
            jstring jscore = safe_new_string_utf(env, sb->submitted_score);
            jstring jbest = safe_new_string_utf(env, sb->best_score);
            (*env)->CallVoidMethod(env, g_callback_handler, mid,
                jtitle, jscore, jbest, (jint)sb->new_rank, (jint)sb->num_entries);
            (*env)->DeleteLocalRef(env, jtitle);
            (*env)->DeleteLocalRef(env, jscore);
            (*env)->DeleteLocalRef(env, jbest);
            break;
        }
        case RC_CLIENT_EVENT_LEADERBOARD_TRACKER_SHOW: {
            const rc_client_leaderboard_tracker_t* tracker = event->leaderboard_tracker;
            if (tracker == NULL) break;
            jmethodID mid = (*env)->GetMethodID(env, cls, "onLeaderboardTrackerShow",
                "(ILjava/lang/String;)V");
            if (mid == NULL) break;
            jstring jdisplay = safe_new_string_utf(env, tracker->display);
            (*env)->CallVoidMethod(env, g_callback_handler, mid, (jint)tracker->id, jdisplay);
            (*env)->DeleteLocalRef(env, jdisplay);
            break;
        }
        case RC_CLIENT_EVENT_LEADERBOARD_TRACKER_HIDE: {
            const rc_client_leaderboard_tracker_t* tracker = event->leaderboard_tracker;
            if (tracker == NULL) break;
            jmethodID mid = (*env)->GetMethodID(env, cls, "onLeaderboardTrackerHide", "(I)V");
            if (mid != NULL) {
                (*env)->CallVoidMethod(env, g_callback_handler, mid, (jint)tracker->id);
            }
            break;
        }
        case RC_CLIENT_EVENT_LEADERBOARD_TRACKER_UPDATE: {
            const rc_client_leaderboard_tracker_t* tracker = event->leaderboard_tracker;
            if (tracker == NULL) break;
            jmethodID mid = (*env)->GetMethodID(env, cls, "onLeaderboardTrackerUpdate",
                "(ILjava/lang/String;)V");
            if (mid == NULL) break;
            jstring jdisplay = safe_new_string_utf(env, tracker->display);
            (*env)->CallVoidMethod(env, g_callback_handler, mid, (jint)tracker->id, jdisplay);
            (*env)->DeleteLocalRef(env, jdisplay);
            break;
        }
        case RC_CLIENT_EVENT_SERVER_ERROR: {
            const rc_client_server_error_t* err = event->server_error;
            if (err == NULL) break;
            jmethodID mid = (*env)->GetMethodID(env, cls, "onServerError",
                "(Ljava/lang/String;Ljava/lang/String;)V");
            if (mid == NULL) break;
            jstring japi = safe_new_string_utf(env, err->api);
            jstring jerror = safe_new_string_utf(env, err->error_message);
            (*env)->CallVoidMethod(env, g_callback_handler, mid, japi, jerror);
            (*env)->DeleteLocalRef(env, japi);
            (*env)->DeleteLocalRef(env, jerror);
            break;
        }
        case RC_CLIENT_EVENT_DISCONNECTED: {
            jmethodID mid = (*env)->GetMethodID(env, cls, "onConnectionChanged", "(Z)V");
            if (mid != NULL) {
                (*env)->CallVoidMethod(env, g_callback_handler, mid, JNI_FALSE);
            }
            break;
        }
        case RC_CLIENT_EVENT_RECONNECTED: {
            jmethodID mid = (*env)->GetMethodID(env, cls, "onConnectionChanged", "(Z)V");
            if (mid != NULL) {
                (*env)->CallVoidMethod(env, g_callback_handler, mid, JNI_TRUE);
            }
            break;
        }
        default:
            break;
    }

    (*env)->DeleteLocalRef(env, cls);
    if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
}

static void client_login_callback(int result, const char* error_message,
                                  rc_client_t* client, void* userdata) {
    (void)client;
    const jlong request_id = (jlong)(intptr_t)userdata;

    if (result == RC_OK) {
        LOGI("RetroAchievements login successful");
        notify_session_callback("onLoginResult", request_id, 1, NULL);
    } else {
        LOGE("RetroAchievements login failed (%d): %s",
             result, error_message ? error_message : "unknown error");
        notify_session_callback("onLoginResult", request_id, 0, error_message);
    }
}

static void client_load_game_callback(int result, const char* error_message,
                                      rc_client_t* client, void* userdata) {
    const jlong request_id = (jlong)(intptr_t)userdata;

    if (result == RC_OK) {
        const rc_client_game_t* game = rc_client_get_game_info(client);
        if (game && game->title) {
            LOGI("RetroAchievements game loaded: %s", game->title);
        } else {
            LOGI("RetroAchievements game loaded");
        }
        notify_session_callback("onGameLoadResult", request_id, 1, NULL);

        // Notify Java with game session info
        if (g_jvm != NULL && g_callback_handler != NULL && game != NULL) {
            int attached = 0;
            JNIEnv* env = get_jni_env(&attached);
            if (env != NULL) {
                jclass cls = (*env)->GetObjectClass(env, g_callback_handler);
                if (cls != NULL) {
                    jmethodID mid = (*env)->GetMethodID(env, cls, "onGameSessionStarted",
                        "(Ljava/lang/String;Ljava/lang/String;)V");
                    if (mid != NULL) {
                        jstring jtitle = safe_new_string_utf(env, game->title);
                        jstring jbadge = safe_new_string_utf(env, game->badge_url);
                        (*env)->CallVoidMethod(env, g_callback_handler, mid, jtitle, jbadge);
                        (*env)->DeleteLocalRef(env, jtitle);
                        (*env)->DeleteLocalRef(env, jbadge);
                    }
                    (*env)->DeleteLocalRef(env, cls);
                }
                if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
            }
        }
    } else {
        LOGE("RetroAchievements game load failed (%d): %s",
             result, error_message ? error_message : "unknown error");
        notify_session_callback("onGameLoadResult", request_id, 0, error_message);
    }
}

// Initialize rcheevos client
JNIEXPORT jlong JNICALL
Java_paulscode_android_mupen64plusae_retroachievements_RCheevosNative_nativeCreateClient(
    JNIEnv* env, jobject thiz) {
    
    if (g_client != NULL) {
        LOGD("Client already exists, destroying old instance");
        rc_client_destroy(g_client);
    }
    
    g_client = rc_client_create(memory_read_callback, server_call_callback);
    
    if (g_client == NULL) {
        LOGE("Failed to create rc_client");
        return 0;
    }
    
    // Enable logging
    rc_client_enable_logging(g_client, RC_CLIENT_LOG_LEVEL_INFO, log_message_callback);

    // Register event handler for achievement notifications
    rc_client_set_event_handler(g_client, event_handler_callback);

    // Store JavaVM for callbacks
    (*env)->GetJavaVM(env, &g_jvm);

    LOGI("RC Client created successfully");
    return (jlong)(intptr_t)g_client;
}

// Destroy rcheevos client
JNIEXPORT void JNICALL
Java_paulscode_android_mupen64plusae_retroachievements_RCheevosNative_nativeDestroyClient(
    JNIEnv* env, jobject thiz, jlong client_ptr) {
    
    rc_client_t* client = (rc_client_t*)(intptr_t)client_ptr;
    if (client != NULL) {
        rc_client_destroy(client);
        if (client == g_client) {
            g_client = NULL;
        }
        LOGI("RC Client destroyed");
    }

    // Clean up the callback handler global ref to prevent leaks
    if (g_callback_handler != NULL) {
        (*env)->DeleteGlobalRef(env, g_callback_handler);
        g_callback_handler = NULL;
    }
}

// Set callback handler
JNIEXPORT void JNICALL
Java_paulscode_android_mupen64plusae_retroachievements_RCheevosNative_nativeSetCallbackHandler(
    JNIEnv* env, jobject thiz, jobject handler) {
    
    if (g_callback_handler != NULL) {
        (*env)->DeleteGlobalRef(env, g_callback_handler);
    }
    
    if (handler != NULL) {
        g_callback_handler = (*env)->NewGlobalRef(env, handler);
        LOGD("Callback handler set");
    } else {
        g_callback_handler = NULL;
        LOGD("Callback handler cleared");
    }
}

// Set hardcore enabled
JNIEXPORT void JNICALL
Java_paulscode_android_mupen64plusae_retroachievements_RCheevosNative_nativeSetHardcoreEnabled(
    JNIEnv* env, jobject thiz, jlong client_ptr, jboolean enabled) {
    
    rc_client_t* client = (rc_client_t*)(intptr_t)client_ptr;
    if (client != NULL) {
        rc_client_set_hardcore_enabled(client, enabled ? 1 : 0);
        LOGD("Hardcore mode %s", enabled ? "enabled" : "disabled");
    }
}

// Get current hardcore enabled state
JNIEXPORT jboolean JNICALL
Java_paulscode_android_mupen64plusae_retroachievements_RCheevosNative_nativeGetHardcoreEnabled(
    JNIEnv* env, jobject thiz, jlong client_ptr) {
    (void)env;
    (void)thiz;
    rc_client_t* client = (rc_client_t*)(intptr_t)client_ptr;
    if (client == NULL) {
        return JNI_FALSE;
    }
    return rc_client_get_hardcore_enabled(client) ? JNI_TRUE : JNI_FALSE;
}

// Generate game hash
JNIEXPORT jstring JNICALL
Java_paulscode_android_mupen64plusae_retroachievements_RCheevosNative_nativeGenerateHash(
    JNIEnv* env, jobject thiz, jint console_id, jstring rom_path, jbyteArray rom_data) {
    
    char hash[33] = {0};
    int result;
    
    if (rom_path != NULL) {
        const char* path = (*env)->GetStringUTFChars(env, rom_path, NULL);
        
        rc_hash_iterator_t iterator;
        rc_hash_initialize_iterator(&iterator, path, NULL, 0);
        result = rc_hash_generate(hash, console_id, &iterator);
        rc_hash_destroy_iterator(&iterator);
        
        (*env)->ReleaseStringUTFChars(env, rom_path, path);
    } else if (rom_data != NULL) {
        jsize len = (*env)->GetArrayLength(env, rom_data);
        jbyte* data = (*env)->GetByteArrayElements(env, rom_data, NULL);
        
        rc_hash_iterator_t iterator;
        rc_hash_initialize_iterator(&iterator, NULL, (const uint8_t*)data, len);
        result = rc_hash_generate(hash, console_id, &iterator);
        rc_hash_destroy_iterator(&iterator);
        
        (*env)->ReleaseByteArrayElements(env, rom_data, data, JNI_ABORT);
    } else {
        LOGE("Both rom_path and rom_data are null");
        return NULL;
    }
    
    if (result != 1) {
        LOGE("Failed to generate hash");
        return NULL;
    }
    
    LOGD("Generated hash: %s", hash);
    return safe_new_string_utf(env, hash);
}

// Process frame - called every frame to check achievements
JNIEXPORT void JNICALL
Java_paulscode_android_mupen64plusae_retroachievements_RCheevosNative_nativeDoFrame(
    JNIEnv* env, jobject thiz, jlong client_ptr) {
    
    rc_client_t* client = (rc_client_t*)(intptr_t)client_ptr;
    if (client != NULL) {
        rc_client_do_frame(client);
    }
}

// Get N64 console ID constant
JNIEXPORT jint JNICALL
Java_paulscode_android_mupen64plusae_retroachievements_RCheevosNative_nativeGetN64ConsoleId(
    JNIEnv* env, jobject thiz) {
    return RC_CONSOLE_NINTENDO_64;
}

// Server response callback - called from Java when HTTP request completes
JNIEXPORT void JNICALL
Java_paulscode_android_mupen64plusae_retroachievements_RCheevosNative_nativeServerResponse(
    JNIEnv* env, jobject thiz, jlong callback_ptr, jlong callback_data_ptr,
    jint http_status_code, jstring response_body) {
    
    rc_client_server_callback_t callback = (rc_client_server_callback_t)(intptr_t)callback_ptr;
    void* callback_data = (void*)(intptr_t)callback_data_ptr;
    
    if (callback == NULL) {
        LOGE("Callback is null");
        return;
    }
    
    rc_api_server_response_t server_response;
    memset(&server_response, 0, sizeof(server_response));
    server_response.http_status_code = http_status_code;
    
    if (response_body != NULL) {
        const char* body = (*env)->GetStringUTFChars(env, response_body, NULL);
        server_response.body = body;
        server_response.body_length = strlen(body);
        
        callback(&server_response, callback_data);
        
        (*env)->ReleaseStringUTFChars(env, response_body, body);
    } else {
        server_response.body = "";
        server_response.body_length = 0;
        callback(&server_response, callback_data);
    }
}

// Login callback wrapper - stores callback info for Java
typedef struct {
    JNIEnv* env;
    jobject java_callback;
    jlong java_callback_id;
} login_callback_data_t;

// Begin login with token
JNIEXPORT jboolean JNICALL
Java_paulscode_android_mupen64plusae_retroachievements_RCheevosNative_nativeBeginLoginWithToken(
    JNIEnv* env, jobject thiz, jlong client_ptr, jstring username, jstring token, jlong callback_ptr) {
    (void)thiz;
    
    rc_client_t* client = (rc_client_t*)(intptr_t)client_ptr;
    if (client == NULL) {
        LOGE("Client is null");
        return JNI_FALSE;
    }

    if (username == NULL || token == NULL) {
        LOGE("Username or token is null");
        return JNI_FALSE;
    }

    const char* c_username = (*env)->GetStringUTFChars(env, username, NULL);
    const char* c_token = (*env)->GetStringUTFChars(env, token, NULL);

    rc_client_async_handle_t* handle = rc_client_begin_login_with_token(
        client, c_username, c_token, client_login_callback, (void*)(intptr_t)callback_ptr);
    if (handle == NULL) {
        LOGE("Failed to queue login request");
    } else {
        LOGI("Login requested for user: %s", c_username);
    }
    
    (*env)->ReleaseStringUTFChars(env, username, c_username);
    (*env)->ReleaseStringUTFChars(env, token, c_token);
    return (handle != NULL) ? JNI_TRUE : JNI_FALSE;
}

// Begin login with password
JNIEXPORT jboolean JNICALL
Java_paulscode_android_mupen64plusae_retroachievements_RCheevosNative_nativeBeginLoginWithPassword(
    JNIEnv* env, jobject thiz, jlong client_ptr, jstring username, jstring password, jlong callback_ptr) {
    (void)thiz;

    rc_client_t* client = (rc_client_t*)(intptr_t)client_ptr;
    if (client == NULL) {
        LOGE("Client is null");
        return JNI_FALSE;
    }

    if (username == NULL || password == NULL) {
        LOGE("Username or password is null");
        return JNI_FALSE;
    }

    const char* c_username = (*env)->GetStringUTFChars(env, username, NULL);
    const char* c_password = (*env)->GetStringUTFChars(env, password, NULL);

    rc_client_async_handle_t* handle = rc_client_begin_login_with_password(
        client, c_username, c_password, client_login_callback, (void*)(intptr_t)callback_ptr);
    if (handle == NULL) {
        LOGE("Failed to queue login request");
    } else {
        LOGI("Login requested for user: %s", c_username);
    }

    (*env)->ReleaseStringUTFChars(env, username, c_username);
    (*env)->ReleaseStringUTFChars(env, password, c_password);
    return (handle != NULL) ? JNI_TRUE : JNI_FALSE;
}

// Begin identify and load game
JNIEXPORT jboolean JNICALL
Java_paulscode_android_mupen64plusae_retroachievements_RCheevosNative_nativeBeginIdentifyAndLoadGame(
    JNIEnv* env, jobject thiz, jlong client_ptr, jint console_id, jstring game_hash, jlong callback_ptr) {
    (void)thiz;
    (void)console_id;
    
    rc_client_t* client = (rc_client_t*)(intptr_t)client_ptr;
    if (client == NULL) {
        LOGE("Client is null");
        return JNI_FALSE;
    }

    if (game_hash == NULL) {
        LOGE("Game hash is null");
        return JNI_FALSE;
    }
    
    const char* c_hash = (*env)->GetStringUTFChars(env, game_hash, NULL);

    rc_client_async_handle_t* handle = rc_client_begin_load_game(
        client, c_hash, client_load_game_callback, (void*)(intptr_t)callback_ptr);
    if (handle == NULL) {
        LOGE("Failed to queue game load request");
    } else {
        LOGI("Game load requested - Console: %d, Hash: %s", console_id, c_hash);
    }
    
    (*env)->ReleaseStringUTFChars(env, game_hash, c_hash);
    return (handle != NULL) ? JNI_TRUE : JNI_FALSE;
}

// Serialize achievement progress into a byte array
JNIEXPORT jbyteArray JNICALL
Java_paulscode_android_mupen64plusae_retroachievements_RCheevosNative_nativeSerializeProgress(
    JNIEnv* env, jobject thiz, jlong client_ptr) {
    (void)thiz;
    rc_client_t* client = (rc_client_t*)(intptr_t)client_ptr;
    if (client == NULL) return NULL;

    size_t size = rc_client_progress_size(client);
    if (size == 0) return NULL;

    uint8_t* buffer = (uint8_t*)malloc(size);
    if (buffer == NULL) return NULL;

    int result = rc_client_serialize_progress_sized(client, buffer, size);
    if (result != RC_OK) {
        LOGE("Failed to serialize progress: %d", result);
        free(buffer);
        return NULL;
    }

    jbyteArray jbuffer = (*env)->NewByteArray(env, (jsize)size);
    (*env)->SetByteArrayRegion(env, jbuffer, 0, (jsize)size, (jbyte*)buffer);
    free(buffer);
    return jbuffer;
}

// Deserialize achievement progress from a byte array
JNIEXPORT jboolean JNICALL
Java_paulscode_android_mupen64plusae_retroachievements_RCheevosNative_nativeDeserializeProgress(
    JNIEnv* env, jobject thiz, jlong client_ptr, jbyteArray data) {
    (void)thiz;
    rc_client_t* client = (rc_client_t*)(intptr_t)client_ptr;
    if (client == NULL || data == NULL) return JNI_FALSE;

    jsize len = (*env)->GetArrayLength(env, data);
    jbyte* bytes = (*env)->GetByteArrayElements(env, data, NULL);

    int result = rc_client_deserialize_progress_sized(client, (const uint8_t*)bytes, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);

    if (result != RC_OK) {
        LOGE("Failed to deserialize progress: %d", result);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// Check if it's safe to pause
JNIEXPORT jboolean JNICALL
Java_paulscode_android_mupen64plusae_retroachievements_RCheevosNative_nativeCanPause(
    JNIEnv* env, jobject thiz, jlong client_ptr) {
    (void)thiz;
    (void)env;
    rc_client_t* client = (rc_client_t*)(intptr_t)client_ptr;
    if (client == NULL) return JNI_TRUE;

    return rc_client_can_pause(client, NULL) ? JNI_TRUE : JNI_FALSE;
}

// Get user game summary (achievement counts)
JNIEXPORT jintArray JNICALL
Java_paulscode_android_mupen64plusae_retroachievements_RCheevosNative_nativeGetUserGameSummary(
    JNIEnv* env, jobject thiz, jlong client_ptr) {
    (void)thiz;
    rc_client_t* client = (rc_client_t*)(intptr_t)client_ptr;
    if (client == NULL) return NULL;

    rc_client_user_game_summary_t summary;
    memset(&summary, 0, sizeof(summary));
    rc_client_get_user_game_summary(client, &summary);

    // Return [numCore, numUnlocked, pointsCore, pointsUnlocked]
    jint values[4];
    values[0] = (jint)summary.num_core_achievements;
    values[1] = (jint)summary.num_unlocked_achievements;
    values[2] = (jint)summary.points_core;
    values[3] = (jint)summary.points_unlocked;

    jintArray result = (*env)->NewIntArray(env, 4);
    (*env)->SetIntArrayRegion(env, result, 0, 4, values);
    return result;
}

// Get rich presence message
JNIEXPORT jstring JNICALL
Java_paulscode_android_mupen64plusae_retroachievements_RCheevosNative_nativeGetRichPresenceMessage(
    JNIEnv* env, jobject thiz, jlong client_ptr) {
    (void)thiz;
    rc_client_t* client = (rc_client_t*)(intptr_t)client_ptr;
    if (client == NULL) return NULL;

    if (!rc_client_has_rich_presence(client)) return NULL;

    char buffer[256];
    size_t len = rc_client_get_rich_presence_message(client, buffer, sizeof(buffer));
    if (len == 0) return NULL;

    return safe_new_string_utf(env, buffer);
}

// Reset achievement/leaderboard state (call on emulator reset)
JNIEXPORT void JNICALL
Java_paulscode_android_mupen64plusae_retroachievements_RCheevosNative_nativeReset(
    JNIEnv* env, jobject thiz, jlong client_ptr) {
    (void)env;
    (void)thiz;
    rc_client_t* client = (rc_client_t*)(intptr_t)client_ptr;
    if (client == NULL) return;

    LOGI("Resetting rcheevos achievement state");
    rc_client_reset(client);
}

// Unload the current game
JNIEXPORT void JNICALL
Java_paulscode_android_mupen64plusae_retroachievements_RCheevosNative_nativeUnloadGame(
    JNIEnv* env, jobject thiz, jlong client_ptr) {
    (void)env;
    (void)thiz;
    rc_client_t* client = (rc_client_t*)(intptr_t)client_ptr;
    if (client == NULL) return;

    LOGI("Unloading game from rcheevos");
    rc_client_unload_game(client);
}

// Get the API token for the logged-in user
JNIEXPORT jstring JNICALL
Java_paulscode_android_mupen64plusae_retroachievements_RCheevosNative_nativeGetUserToken(
    JNIEnv* env, jobject thiz, jlong client_ptr) {
    (void)thiz;
    rc_client_t* client = (rc_client_t*)(intptr_t)client_ptr;
    if (client == NULL) return NULL;

    const rc_client_user_t* user = rc_client_get_user_info(client);
    if (user == NULL || user->token == NULL) return NULL;

    return safe_new_string_utf(env, user->token);
}

// ========== JSON Builder Helpers ==========

typedef struct {
    char* buf;
    size_t len;
    size_t cap;
    int failed;
} json_buf_t;

static int json_buf_ensure(json_buf_t* jb, size_t extra) {
    if (jb->failed) return 0;

    if (extra > ((size_t)-1) - jb->len - 1) {
        jb->failed = 1;
        return 0;
    }

    size_t required = jb->len + extra + 1;
    if (required <= jb->cap) {
        return 1;
    }

    size_t new_cap = jb->cap ? jb->cap : 64;
    while (new_cap < required) {
        size_t grown = new_cap * 2;
        if (grown < new_cap) {
            new_cap = required;
            break;
        }
        new_cap = grown;
    }

    char* new_buf = (char*)realloc(jb->buf, new_cap);
    if (new_buf == NULL) {
        jb->failed = 1;
        return 0;
    }

    jb->buf = new_buf;
    jb->cap = new_cap;
    return 1;
}

static int json_buf_append(json_buf_t* jb, const char* str) {
    size_t slen = strlen(str);
    if (!json_buf_ensure(jb, slen)) {
        return 0;
    }
    memcpy(jb->buf + jb->len, str, slen);
    jb->len += slen;
    jb->buf[jb->len] = '\0';
    return 1;
}

static int json_buf_append_escaped(json_buf_t* jb, const char* str) {
    if (str == NULL) return json_buf_append(jb, "null");
    if (!json_buf_append(jb, "\"")) return 0;
    for (const char* p = str; *p; ++p) {
        switch (*p) {
            case '"':  if (!json_buf_append(jb, "\\\"")) return 0; break;
            case '\\': if (!json_buf_append(jb, "\\\\")) return 0; break;
            case '\n': if (!json_buf_append(jb, "\\n")) return 0; break;
            case '\r': if (!json_buf_append(jb, "\\r")) return 0; break;
            case '\t': if (!json_buf_append(jb, "\\t")) return 0; break;
            default: {
                if (!json_buf_ensure(jb, 1)) return 0;
                jb->buf[jb->len++] = *p;
                jb->buf[jb->len] = '\0';
            }
        }
    }
    return json_buf_append(jb, "\"");
}

// Get achievement list as JSON string
JNIEXPORT jstring JNICALL
Java_paulscode_android_mupen64plusae_retroachievements_RCheevosNative_nativeGetAchievementListJson(
    JNIEnv* env, jobject thiz, jlong client_ptr) {
    (void)thiz;
    rc_client_t* client = (rc_client_t*)(intptr_t)client_ptr;
    if (client == NULL) return NULL;

    if (!rc_client_has_achievements(client)) return NULL;

    rc_client_achievement_list_t* list = rc_client_create_achievement_list(
        client, RC_CLIENT_ACHIEVEMENT_CATEGORY_CORE,
        RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_PROGRESS);
    if (list == NULL) return NULL;

    json_buf_t jb = {0};
    jb.cap = 4096;
    jb.buf = (char*)malloc(jb.cap);
    if (jb.buf == NULL) {
        rc_client_destroy_achievement_list(list);
        return NULL;
    }
    jb.buf[0] = '\0';

#define JSON_APPEND_OR_FAIL(expr) do { if (!(expr)) goto json_build_failed; } while (0)
    JSON_APPEND_OR_FAIL(json_buf_append(&jb, "{\"buckets\":["));

    int first_bucket = 1;
    for (uint32_t i = 0; i < list->num_buckets; ++i) {
        const rc_client_achievement_bucket_t* bucket = &list->buckets[i];
        if (bucket->num_achievements == 0) continue;

        if (!first_bucket) JSON_APPEND_OR_FAIL(json_buf_append(&jb, ","));
        first_bucket = 0;

        JSON_APPEND_OR_FAIL(json_buf_append(&jb, "{\"label\":"));
        JSON_APPEND_OR_FAIL(json_buf_append_escaped(&jb, bucket->label));

        char tmp[128];
        snprintf(tmp, sizeof(tmp), ",\"bucket_type\":%u,\"achievements\":[",
                 (unsigned)bucket->bucket_type);
        JSON_APPEND_OR_FAIL(json_buf_append(&jb, tmp));

        for (uint32_t j = 0; j < bucket->num_achievements; ++j) {
            const rc_client_achievement_t* ach = bucket->achievements[j];
            if (j > 0) JSON_APPEND_OR_FAIL(json_buf_append(&jb, ","));

            JSON_APPEND_OR_FAIL(json_buf_append(&jb, "{\"id\":"));
            snprintf(tmp, sizeof(tmp), "%u", (unsigned)ach->id);
            JSON_APPEND_OR_FAIL(json_buf_append(&jb, tmp));

            JSON_APPEND_OR_FAIL(json_buf_append(&jb, ",\"title\":"));
            JSON_APPEND_OR_FAIL(json_buf_append_escaped(&jb, ach->title));

            JSON_APPEND_OR_FAIL(json_buf_append(&jb, ",\"description\":"));
            JSON_APPEND_OR_FAIL(json_buf_append_escaped(&jb, ach->description));

            JSON_APPEND_OR_FAIL(json_buf_append(&jb, ",\"badge_url\":"));
            JSON_APPEND_OR_FAIL(json_buf_append_escaped(&jb, ach->badge_url));

            JSON_APPEND_OR_FAIL(json_buf_append(&jb, ",\"badge_locked_url\":"));
            JSON_APPEND_OR_FAIL(json_buf_append_escaped(&jb, ach->badge_locked_url));

            snprintf(tmp, sizeof(tmp),
                     ",\"points\":%u,\"state\":%u,\"unlocked\":%u",
                     (unsigned)ach->points, (unsigned)ach->state,
                     (unsigned)ach->unlocked);
            JSON_APPEND_OR_FAIL(json_buf_append(&jb, tmp));

            JSON_APPEND_OR_FAIL(json_buf_append(&jb, ",\"measured_progress\":"));
            JSON_APPEND_OR_FAIL(json_buf_append_escaped(&jb, ach->measured_progress));

            snprintf(tmp, sizeof(tmp),
                     ",\"measured_percent\":%.1f,\"rarity\":%.1f,\"rarity_hardcore\":%.1f,\"type\":%u,\"unlock_time\":%ld",
                     ach->measured_percent, ach->rarity, ach->rarity_hardcore,
                     (unsigned)ach->type, (long)ach->unlock_time);
            JSON_APPEND_OR_FAIL(json_buf_append(&jb, tmp));

            JSON_APPEND_OR_FAIL(json_buf_append(&jb, "}"));
        }

        JSON_APPEND_OR_FAIL(json_buf_append(&jb, "]}"));
    }

    JSON_APPEND_OR_FAIL(json_buf_append(&jb, "]}"));

    jstring result = safe_new_string_utf(env, jb.buf);
    rc_client_destroy_achievement_list(list);
    free(jb.buf);
#undef JSON_APPEND_OR_FAIL
    return result;

json_build_failed:
    LOGE("Failed to build achievement list JSON");
    rc_client_destroy_achievement_list(list);
    free(jb.buf);
#undef JSON_APPEND_OR_FAIL
    return NULL;
}
