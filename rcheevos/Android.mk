JNI_LOCAL_PATH := $(call my-dir)
include $(JNI_LOCAL_PATH)/../build_common/native_common.mk

include $(CLEAR_VARS)
LOCAL_MODULE := rcheevos
LOCAL_C_INCLUDES := $(JNI_LOCAL_PATH)/src/main/cpp/include
LOCAL_SRC_FILES := \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rapi/rc_api_common.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rapi/rc_api_editor.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rapi/rc_api_info.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rapi/rc_api_runtime.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rapi/rc_api_user.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rc_client.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rc_client_external.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rc_client_raintegration.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rc_compat.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rc_libretro.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rc_util.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rc_version.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rcheevos/alloc.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rcheevos/condition.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rcheevos/condset.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rcheevos/consoleinfo.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rcheevos/format.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rcheevos/lboard.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rcheevos/memref.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rcheevos/operand.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rcheevos/rc_validate.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rcheevos/richpresence.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rcheevos/runtime.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rcheevos/runtime_progress.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rcheevos/trigger.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rcheevos/value.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rhash/aes.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rhash/cdreader.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rhash/hash.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rhash/hash_disc.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rhash/hash_encrypted.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rhash/hash_rom.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rhash/hash_zip.c \
    $(JNI_LOCAL_PATH)/src/main/cpp/src/rhash/md5.c \
    $(JNI_LOCAL_PATH)/src/main/jni/rcheevos_jni.c

LOCAL_CFLAGS := $(COMMON_CFLAGS) -DRC_DISABLE_LUA
LOCAL_CPPFLAGS := $(COMMON_CPPFLAGS)
LOCAL_LDFLAGS := $(COMMON_LDFLAGS)
LOCAL_LDLIBS := -llog -lz
include $(BUILD_SHARED_LIBRARY)
