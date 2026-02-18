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

// Global rc_client instance
static rc_client_t* g_client = NULL;
static JavaVM* g_jvm = NULL;
static jobject g_callback_handler = NULL;

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
    
    jstring jurl = (*env)->NewStringUTF(env, request->url);
    jstring jpost_data = request->post_data ? (*env)->NewStringUTF(env, request->post_data) : NULL;
    
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
    return (*env)->NewStringUTF(env, hash);
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
