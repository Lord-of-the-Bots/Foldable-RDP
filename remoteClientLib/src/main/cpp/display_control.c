#include <jni.h>
#include <stdint.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <freerdp/freerdp.h>
#include <freerdp/error.h>
#include <freerdp/settings.h>
#include <freerdp/event.h>
#include <freerdp/client/disp.h>
#include <freerdp/client/rdpei.h>
#include <freerdp/gdi/gdi.h>
#include <freerdp/update.h>
#include "clipboard_channel.h"

typedef struct DisplayState {
    freerdp* instance;
    pPreConnect pre_connect;
    pPostConnect post_connect;
    pDesktopResize desktop_resize;
    _Atomic(DispClientContext*) display;
    RdpeiClientContext* touch;
    pthread_mutex_t lock;
    unsigned users;
    BOOL retiring;
    pcDispCaps previous_caps;
    UINT32 width, height, desktop_scale;
    jlong last_layout;
    UINT64 max_area;
    BOOL ready, subscribed, sent_on_channel;
    UINT last_send_error;
    struct DisplayState* next;
} DisplayState;

static DisplayState* states;
static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t released = PTHREAD_COND_INITIALIZER;

static DisplayState* find_instance(freerdp* instance) {
    for (DisplayState* state = states; state; state = state->next)
        if (state->instance == instance) return state;
    return NULL;
}

/* Pin under the registry lock, then serialize only operations belonging to this desktop. */
static DisplayState* acquire_state(freerdp* instance, DispClientContext* display) {
    if (!instance && !display) return NULL;
    pthread_mutex_lock(&mutex);
    DisplayState* state;
    if (instance) state = find_instance(instance);
    else {
        state = states;
        while (state && state->display != display) state = state->next;
    }
    if (state) state->users++;
    pthread_mutex_unlock(&mutex);
    if (state) pthread_mutex_lock(&state->lock);
    return state;
}

static void release_state(DisplayState* state) {
    if (!state) return;
    pthread_mutex_unlock(&state->lock);
    pthread_mutex_lock(&mutex);
    if (--state->users == 0 && state->retiring) pthread_cond_broadcast(&released);
    pthread_mutex_unlock(&mutex);
}

static UINT send_layout(DisplayState* state) {
    if (!state->ready || !state->display || !state->width || !state->height) return 0;
    if ((UINT64)state->width * state->height > state->max_area) {
        state->last_send_error = 1;
        return state->last_send_error;
    }
    jlong layout = ((jlong)state->width << 32) | ((jlong)state->height << 16) | state->desktop_scale;
    if (state->sent_on_channel && state->last_layout == layout) {
        state->last_send_error = 0;
        return 0;
    }
    DISPLAY_CONTROL_MONITOR_LAYOUT monitor = {0};
    monitor.Flags = DISPLAY_CONTROL_MONITOR_PRIMARY;
    monitor.Width = state->width;
    monitor.Height = state->height;
    /* The Android window has no reliable physical dimensions. MS-RDPEDISP specifies zero as ignored. */
    monitor.Orientation = ORIENTATION_LANDSCAPE;
    monitor.DesktopScaleFactor = state->desktop_scale;
    monitor.DeviceScaleFactor = 100;
    state->last_send_error = state->display->SendMonitorLayout(state->display, 1, &monitor);
    if (!state->last_send_error) {
        state->last_layout = layout;
        state->sent_on_channel = TRUE;
    }
    return state->last_send_error;
}

static BOOL desktop_resize(rdpContext* context) {
    if (!context || !context->instance || !context->settings || !context->gdi) return FALSE;
    DisplayState* state = acquire_state(context->instance, NULL);
    pDesktopResize original = state ? state->desktop_resize : NULL;
    if (state && state->sent_on_channel) {
        UINT32 width = (UINT32)(state->last_layout >> 32);
        UINT32 height = (UINT32)(state->last_layout >> 16) & 0xffff;
        if ((UINT32)context->gdi->width == width && (UINT32)context->gdi->height == height &&
            (context->settings->DesktopWidth != width || context->settings->DesktopHeight != height))
            state->sent_on_channel = FALSE;
    }
    release_state(state);
    if (!original) return FALSE;

    /* Android's callback only notifies Java; its GDI buffer still has the old size. */
    if (!gdi_resize(context->gdi, context->settings->DesktopWidth, context->settings->DesktopHeight))
        return FALSE;
    return original(context);
}

static BOOL post_connect(freerdp* instance) {
    DisplayState* state = acquire_state(instance, NULL);
    pPostConnect original = state ? state->post_connect : NULL;
    release_state(state);
    if (!original || !original(instance) || !instance->update) return FALSE;
    state = acquire_state(instance, NULL);
    if (!state) return FALSE;
    if (instance->update->DesktopResize != desktop_resize) {
        state->desktop_resize = instance->update->DesktopResize;
        instance->update->DesktopResize = desktop_resize;
    }
    release_state(state);
    return TRUE;
}

static UINT display_caps(DispClientContext* display, UINT32 monitors, UINT32 a, UINT32 b) {
    DisplayState* state = acquire_state(NULL, display);
    pcDispCaps original = NULL;
    if (state && state->display == display) {
        original = state->previous_caps;
        state->ready = TRUE;
        /* Each usable layout is at most 8192 squared. Saturate the product before multiplying again. */
        UINT64 area = (UINT64)monitors * a;
        if (area > 8192U * 8192U) area = 8192U * 8192U;
        state->max_area = area * b;
        send_layout(state);
    }
    release_state(state);
    return original ? original(display, monitors, a, b) : 0;
}

static void channel_connected(void* context, ChannelConnectedEventArgs* event) {
    if (!context || !event || !event->name || !event->pInterface) return;
    if (strcmp(event->name, RDPEI_DVC_CHANNEL_NAME) == 0) {
        DisplayState* state = acquire_state(((rdpContext*)context)->instance, NULL);
        if (state) state->touch = event->pInterface;
        release_state(state);
        return;
    }
    if (strcmp(event->name, DISP_DVC_CHANNEL_NAME) != 0) return;
    DisplayState* state = acquire_state(((rdpContext*)context)->instance, NULL);
    if (state && state->display != event->pInterface) {
        if (state->display) state->display->DisplayControlCaps = state->previous_caps;
        state->display = event->pInterface;
        state->ready = state->sent_on_channel = FALSE;
        state->last_send_error = 0;
        state->previous_caps = state->display->DisplayControlCaps;
        state->display->DisplayControlCaps = display_caps;
    }
    release_state(state);
}

static void channel_disconnected(void* context, ChannelDisconnectedEventArgs* event) {
    if (!context || !event || !event->name) return;
    if (strcmp(event->name, RDPEI_DVC_CHANNEL_NAME) == 0) {
        DisplayState* state = acquire_state(((rdpContext*)context)->instance, NULL);
        if (state && (!event->pInterface || state->touch == event->pInterface)) state->touch = NULL;
        release_state(state);
        return;
    }
    if (strcmp(event->name, DISP_DVC_CHANNEL_NAME) != 0) return;
    DisplayState* state = acquire_state(((rdpContext*)context)->instance, NULL);
    if (state && (!event->pInterface || state->display == event->pInterface)) {
        if (state->display) state->display->DisplayControlCaps = state->previous_caps;
        state->display = NULL;
        state->ready = state->sent_on_channel = FALSE;
        state->last_send_error = 0;
    }
    release_state(state);
}

static BOOL pre_connect(freerdp* instance) {
    DisplayState* state = acquire_state(instance, NULL);
    pPreConnect original = state ? state->pre_connect : NULL;
    BOOL subscribed = state && state->subscribed;
    release_state(state);
    if (!original) return FALSE;
    freerdp_settings_set_bool(instance->settings, FreeRDP_MultiTouchInput, TRUE);
    if (!freerdp_settings_set_bool(instance->settings, FreeRDP_SupportDisplayControl, TRUE) ||
        !freerdp_settings_set_bool(instance->settings, FreeRDP_DynamicResolutionUpdate, TRUE)) return FALSE;
    if (subscribed) return original(instance);
    if (PubSub_SubscribeChannelConnected(instance->context->pubSub, channel_connected) != 0) return FALSE;
    if (PubSub_SubscribeChannelDisconnected(instance->context->pubSub, channel_disconnected) != 0) {
        PubSub_UnsubscribeChannelConnected(instance->context->pubSub, channel_connected);
        return FALSE;
    }
    state = acquire_state(instance, NULL);
    if (state) state->subscribed = TRUE;
    release_state(state);
    BOOL ok = original(instance);
    // Android installs its cliprdr callbacks in PreConnect. Register our listener after it.
    if (ok) clipboard_subscribe(instance);
    return ok;
}

JNIEXPORT jboolean JNICALL
Java_com_undatech_opaque_RdpDisplayControl_attach(JNIEnv* env, jclass cls, jlong handle) {
    freerdp* instance = (freerdp*)(uintptr_t)handle;
    if (!instance || !instance->context) return JNI_FALSE;
    pthread_mutex_lock(&mutex);
    DisplayState* state = find_instance(instance);
    if (!state) {
        state = calloc(1, sizeof(*state));
        if (!state || pthread_mutex_init(&state->lock, NULL) != 0) {
            free(state);
            pthread_mutex_unlock(&mutex);
            return JNI_FALSE;
        }
        state->instance = instance;
        atomic_init(&state->display, NULL);
        state->pre_connect = instance->PreConnect;
        state->post_connect = instance->PostConnect;
        state->next = states;
        states = state;
        instance->PreConnect = pre_connect;
        instance->PostConnect = post_connect;
    }
    pthread_mutex_unlock(&mutex);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_undatech_opaque_RdpDisplayControl_resize(JNIEnv* env, jclass cls, jlong handle, jint width, jint height, jint scale) {
    if (width < 200 || height < 200 || width > 8192 || height > 8192) return JNI_FALSE;
    if (scale < 100 || scale > 500) return JNI_FALSE;
    DisplayState* state = acquire_state((freerdp*)(uintptr_t)handle, NULL);
    UINT result = 1;
    if (state) {
        state->width = (UINT32)width & ~1U;
        state->height = (UINT32)height;
        state->desktop_scale = (UINT32)scale;
        result = send_layout(state);
    }
    release_state(state);
    return result == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_undatech_opaque_RdpDisplayControl_status(JNIEnv* env, jclass cls, jlong handle) {
    DisplayState* state = acquire_state((freerdp*)(uintptr_t)handle, NULL);
    int status = !state ? -1 : state->last_send_error ? -2 : !state->display ? 0 : !state->ready ? 1 : 2;
    release_state(state);
    return status;
}

/* Last successful channel write, retained through channel teardown for the disconnect dialog. */
JNIEXPORT jlong JNICALL
Java_com_undatech_opaque_RdpDisplayControl_lastSentLayout(JNIEnv* env, jclass cls, jlong handle) {
    DisplayState* state = acquire_state((freerdp*)(uintptr_t)handle, NULL);
    jlong layout = state ? state->last_layout : 0;
    release_state(state);
    return layout;
}

JNIEXPORT jboolean JNICALL
Java_com_undatech_opaque_RdpDisplayControl_hasHorizontalWheel(JNIEnv* env, jclass cls, jlong handle) {
    DisplayState* state = acquire_state((freerdp*)(uintptr_t)handle, NULL);
    BOOL enabled = state && state->instance->settings->HasHorizontalWheel;
    release_state(state);
    return enabled ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_undatech_opaque_RdpDisplayControl_touch(JNIEnv* env, jclass cls, jlong handle, jint action,
                                                 jint id, jint x, jint y) {
    DisplayState* state = acquire_state((freerdp*)(uintptr_t)handle, NULL);
    RdpeiClientContext* touch = state ? state->touch : NULL;
    INT32 slot = -1;
    UINT error = ERROR_NOT_SUPPORTED;
    if (touch) {
        switch (action) {
            case 0: error = touch->TouchBegin(touch, id, x, y, &slot); break;
            case 1: error = touch->TouchEnd(touch, id, x, y, &slot); break;
            case 2: error = touch->TouchUpdate(touch, id, x, y, &slot); break;
            case 3:
                error = touch->TouchUpdate(touch, id, x, y, &slot);
                if (!error && slot >= 0) {
                    RDPINPUT_CONTACT_DATA contact = {0};
                    contact.contactId = (UINT32)slot;
                    contact.x = x;
                    contact.y = y;
                    contact.contactFlags = CONTACT_FLAG_UP | CONTACT_FLAG_CANCELED;
                    error = touch->AddContact(touch, &contact);
                }
                break;
        }
    }
    release_state(state);
    return !error && slot >= 0 ? JNI_TRUE : JNI_FALSE;
}

/* Called synchronously from the native connection thread, before detaching the handle. */
enum { OTHER_FAILURE, NETWORK_FAILURE, CREDENTIALS_REQUIRED, DISPLAY_DRIVER_FAILURE };

JNIEXPORT jlong JNICALL
Java_com_undatech_opaque_RdpDisplayControl_errorCodes(JNIEnv* env, jclass cls, jlong handle) {
    DisplayState* state = acquire_state((freerdp*)(uintptr_t)handle, NULL);
    if (!state) return 0;
    UINT32 info = freerdp_error_info(state->instance);
    UINT32 error = freerdp_get_last_error(state->instance->context);
    release_state(state);
    if (info == ERRINFO_NONE) info = 0;
    if (error == FREERDP_ERROR_NONE) error = 0;
    return (jlong)(((UINT64)info << 32) | error);
}

JNIEXPORT jint JNICALL
Java_com_undatech_opaque_RdpDisplayControl_disconnectReason(JNIEnv* env, jclass cls, jlong handle,
                                                          jboolean was_connected) {
    DisplayState* state = acquire_state((freerdp*)(uintptr_t)handle, NULL);
    jint reason = OTHER_FAILURE;
    if (state) {
        UINT32 info = freerdp_error_info(state->instance);
        UINT32 error = freerdp_get_last_error(state->instance->context);
        if (info == ERRINFO_CLOSE_STACK_ON_DRIVER_FAILURE) {
            reason = DISPLAY_DRIVER_FAILURE;
        } else if (info == ERRINFO_SUCCESS || info == ERRINFO_NONE) {
            switch (error) {
                case FREERDP_ERROR_CLOSE_STACK_ON_DRIVER_FAILURE:
                    reason = DISPLAY_DRIVER_FAILURE;
                    break;
                /* Some established sockets close without an extended error. */
                case FREERDP_ERROR_SUCCESS:
                case FREERDP_ERROR_NONE:
                    if (was_connected) reason = NETWORK_FAILURE;
                    break;
                case FREERDP_ERROR_DNS_ERROR:
                case FREERDP_ERROR_DNS_NAME_NOT_FOUND:
                case FREERDP_ERROR_CONNECT_FAILED:
                case FREERDP_ERROR_CONNECT_TRANSPORT_FAILED:
                case FREERDP_ERROR_CONNECT_KDC_UNREACHABLE:
                    reason = NETWORK_FAILURE;
                    break;
                case FREERDP_ERROR_AUTHENTICATION_FAILED:
                case FREERDP_ERROR_CONNECT_WRONG_PASSWORD:
                case FREERDP_ERROR_CONNECT_LOGON_FAILURE:
                case FREERDP_ERROR_CONNECT_NO_OR_MISSING_CREDENTIALS:
                    reason = CREDENTIALS_REQUIRED;
                    break;
            }
        }
    }
    release_state(state);
    return reason;
}

JNIEXPORT void JNICALL
Java_com_undatech_opaque_RdpDisplayControl_detach(JNIEnv* env, jclass cls, jlong handle) {
    freerdp* instance = (freerdp*)(uintptr_t)handle;
    clipboard_detach(instance);
    pthread_mutex_lock(&mutex);
    DisplayState** link = &states;
    while (*link && (*link)->instance != instance) link = &(*link)->next;
    DisplayState* state = *link;
    if (state) {
        *link = state->next;
        state->retiring = TRUE;
        while (state->users) pthread_cond_wait(&released, &mutex);
        if (state->display) state->display->DisplayControlCaps = state->previous_caps;
        instance->PreConnect = state->pre_connect;
        instance->PostConnect = state->post_connect;
        if (instance->update && instance->update->DesktopResize == desktop_resize)
            instance->update->DesktopResize = state->desktop_resize;
    }
    pthread_mutex_unlock(&mutex);
    if (state && state->subscribed) {
        PubSub_UnsubscribeChannelConnected(instance->context->pubSub, channel_connected);
        PubSub_UnsubscribeChannelDisconnected(instance->context->pubSub, channel_disconnected);
    }
    if (state) {
        pthread_mutex_destroy(&state->lock);
        free(state);
    }
}
