LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := libtermux
LOCAL_SRC_FILES := termux.c
LOCAL_CFLAGS := -std=c11 -Wall -Wextra -Werror -Os -fno-stack-protector
LOCAL_LDFLAGS := -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
include $(BUILD_SHARED_LIBRARY)
