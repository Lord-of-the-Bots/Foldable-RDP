#include <jni.h>
#include <limits.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <freerdp/client/cliprdr.h>
#include <freerdp/event.h>
#include "clipboard_channel.h"

/* FreeRDP owns the interface until ChannelDisconnected. A read lock pins it just for a
 * Client* call, which serializes the PDU and queues its write. Never enter Java with this lock. */
typedef struct ClipboardSession {
    freerdp* instance;
    CliprdrClientContext* channel;
    jobject peer;
    jlong token;
    UINT32 flags;
    BOOL subscribed;
    struct ClipboardSession* next;
} ClipboardSession;

static pthread_rwlock_t registry = PTHREAD_RWLOCK_INITIALIZER;
static ClipboardSession* sessions;
static JavaVM* vm;
static jmethodID ready, formats, acknowledged, format_request, format_data, file_request, file_data, lock_data, offline;
static const UINT32 supported = CB_USE_LONG_FORMAT_NAMES | CB_STREAM_FILECLIP_ENABLED |
        CB_FILECLIP_NO_FILE_PATHS | CB_CAN_LOCK_CLIPDATA | CB_HUGE_FILE_SUPPORT_ENABLED;

static ClipboardSession* by_instance(freerdp* instance) {
    for (ClipboardSession* s = sessions; s; s = s->next) if (s->instance == instance) return s;
    return NULL;
}

static ClipboardSession* by_channel(CliprdrClientContext* channel) {
    for (ClipboardSession* s = sessions; s; s = s->next) if (s->channel == channel) return s;
    return NULL;
}

static CliprdrClientContext* by_token(jlong token) {
    for (ClipboardSession* s = sessions; s; s = s->next) if (s->token == token) return s->channel;
    return NULL;
}

typedef struct {
    JNIEnv* env;
    BOOL attached;
    jobject peer;
} Callback;

static Callback begin_callback(CliprdrClientContext* channel) {
    Callback cb = {0};
    if (!vm) return cb;
    if ((*vm)->GetEnv(vm, (void**)&cb.env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if ((*vm)->AttachCurrentThread(vm, &cb.env, NULL) != JNI_OK) { cb.env = NULL; return cb; }
        cb.attached = TRUE;
    }
    if (cb.env && channel) {
        pthread_rwlock_rdlock(&registry);
        ClipboardSession* s = by_channel(channel);
        if (s) cb.peer = (*cb.env)->NewLocalRef(cb.env, s->peer);
        pthread_rwlock_unlock(&registry);
    }
    return cb;
}

static void end_callback(Callback* cb) {
    if (!cb->env) return;
    if ((*cb->env)->ExceptionCheck(cb->env)) {
        (*cb->env)->ExceptionDescribe(cb->env);
        (*cb->env)->ExceptionClear(cb->env);
    }
    if (cb->peer) (*cb->env)->DeleteLocalRef(cb->env, cb->peer);
    if (cb->attached) (*vm)->DetachCurrentThread(vm);
}

static UINT server_caps(CliprdrClientContext* c, const CLIPRDR_CAPABILITIES* caps) {
    UINT32 flags = 0;
    const BYTE* data = (const BYTE*)caps->capabilitySets;
    for (UINT32 i = 0; data && i < caps->cCapabilitiesSets; i++) {
        const CLIPRDR_CAPABILITY_SET* set = (const CLIPRDR_CAPABILITY_SET*)data;
        if (set->capabilitySetLength < sizeof(*set)) break;
        if (set->capabilitySetType == CB_CAPSTYPE_GENERAL && set->capabilitySetLength >= CB_CAPSTYPE_GENERAL_LEN) {
            flags = ((const CLIPRDR_GENERAL_CAPABILITY_SET*)set)->generalFlags & supported;
            break;
        }
        data += set->capabilitySetLength;
    }
    pthread_rwlock_wrlock(&registry);
    ClipboardSession* s = by_channel(c);
    if (s) s->flags = flags;
    pthread_rwlock_unlock(&registry);
    return 0;
}

static UINT monitor_ready(CliprdrClientContext* c, const CLIPRDR_MONITOR_READY* msg) {
    Callback cb = begin_callback(c);
    if (cb.peer) {
        pthread_rwlock_rdlock(&registry);
        ClipboardSession* s = by_channel(c);
        UINT32 flags = s ? s->flags : 0;
        CLIPRDR_GENERAL_CAPABILITY_SET general = {CB_CAPSTYPE_GENERAL, CB_CAPSTYPE_GENERAL_LEN, CB_CAPS_VERSION_2, flags};
        CLIPRDR_CAPABILITIES caps = {0};
        caps.msgType = CB_CLIP_CAPS;
        caps.cCapabilitiesSets = 1;
        caps.capabilitySets = (CLIPRDR_CAPABILITY_SET*)&general;
        BOOL sent = s && c->ClientCapabilities(c, &caps) == 0;
        pthread_rwlock_unlock(&registry);
        if (sent) (*cb.env)->CallVoidMethod(cb.env, cb.peer, ready, (jint)flags);
    }
    end_callback(&cb);
    return 0;
}

/* Only standard ASCII format names are interpreted by the bridge; foreign registered
 * formats with non-ASCII names remain unadvertised. Format payloads are always binary. */
static BOOL ascii_name(const char* name) {
    if (!name || !*name) return FALSE;
    for (size_t i = 0; i < 256; i++) {
        unsigned char ch = (unsigned char)name[i];
        if (!ch) return TRUE;
        if (ch < 32 || ch > 126) return FALSE;
    }
    return FALSE;
}

static UINT server_formats(CliprdrClientContext* c, const CLIPRDR_FORMAT_LIST* list) {
    Callback cb = begin_callback(c);
    if (cb.peer) {
        JNIEnv* env = cb.env;
        jclass string = (*env)->FindClass(env, "java/lang/String");
        jintArray ids = NULL;
        jobjectArray names = NULL;
        BOOL ok = list->numFormats <= 16384 && (!list->numFormats || list->formats);
        if (ok && string) {
            ids = (*env)->NewIntArray(env, (jsize)list->numFormats);
            if (ids) names = (*env)->NewObjectArray(env, (jsize)list->numFormats, string, NULL);
        }
        ok = ok && ids && names;
        for (UINT32 i = 0; ok && i < list->numFormats; i++) {
            jint id = (jint)list->formats[i].formatId;
            (*env)->SetIntArrayRegion(env, ids, (jsize)i, 1, &id);
            if (ascii_name(list->formats[i].formatName)) {
                jstring name = (*env)->NewStringUTF(env, list->formats[i].formatName);
                if (name) {
                    (*env)->SetObjectArrayElement(env, names, (jsize)i, name);
                    (*env)->DeleteLocalRef(env, name);
                }
            }
            ok = !(*env)->ExceptionCheck(env);
        }
        CLIPRDR_FORMAT_LIST_RESPONSE ack = {0};
        ack.msgType = CB_FORMAT_LIST_RESPONSE;
        ack.msgFlags = ok ? CB_RESPONSE_OK : CB_RESPONSE_FAIL;
        c->ClientFormatListResponse(c, &ack);
        if (ok) (*env)->CallVoidMethod(env, cb.peer, formats, ids, names);
        if (ids) (*env)->DeleteLocalRef(env, ids);
        if (names) (*env)->DeleteLocalRef(env, names);
        if (string) (*env)->DeleteLocalRef(env, string);
    }
    end_callback(&cb);
    return 0;
}

static UINT server_ack(CliprdrClientContext* c, const CLIPRDR_FORMAT_LIST_RESPONSE* msg) {
    Callback cb = begin_callback(c);
    if (cb.peer) (*cb.env)->CallVoidMethod(cb.env, cb.peer, acknowledged, (jboolean)((msg->msgFlags & CB_RESPONSE_OK) != 0));
    end_callback(&cb);
    return 0;
}

static UINT server_format_request(CliprdrClientContext* c, const CLIPRDR_FORMAT_DATA_REQUEST* msg) {
    Callback cb = begin_callback(c);
    if (cb.peer) (*cb.env)->CallVoidMethod(cb.env, cb.peer, format_request, (jint)msg->requestedFormatId);
    end_callback(&cb);
    return 0;
}

static jbyteArray payload(JNIEnv* env, UINT16 flags, const BYTE* bytes, UINT32 size) {
    // This limit is per clipboard PDU, independent of the total size of a streamed file.
    if (!(flags & CB_RESPONSE_OK) || (size && !bytes) || size > 64U * 1024U * 1024U) return NULL;
    jbyteArray data = (*env)->NewByteArray(env, (jsize)size);
    if (data && size) (*env)->SetByteArrayRegion(env, data, 0, (jsize)size, (const jbyte*)bytes);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return NULL; }
    return data;
}

static UINT server_format_data(CliprdrClientContext* c, const CLIPRDR_FORMAT_DATA_RESPONSE* msg) {
    Callback cb = begin_callback(c);
    if (cb.peer) {
        jbyteArray data = payload(cb.env, msg->msgFlags, msg->requestedFormatData, msg->dataLen);
        (*cb.env)->CallVoidMethod(cb.env, cb.peer, format_data, data);
        if (data) (*cb.env)->DeleteLocalRef(cb.env, data);
    }
    end_callback(&cb);
    return 0;
}

static UINT server_file_request(CliprdrClientContext* c, const CLIPRDR_FILE_CONTENTS_REQUEST* r) {
    Callback cb = begin_callback(c);
    if (cb.peer) (*cb.env)->CallVoidMethod(cb.env, cb.peer, file_request, (jint)r->streamId, (jint)r->listIndex,
        (jint)r->dwFlags, (jint)r->nPositionLow, (jint)r->nPositionHigh, (jint)r->cbRequested,
        (jboolean)(r->haveClipDataId != 0), (jint)r->clipDataId);
    end_callback(&cb);
    return 0;
}

static UINT server_file_data(CliprdrClientContext* c, const CLIPRDR_FILE_CONTENTS_RESPONSE* r) {
    Callback cb = begin_callback(c);
    if (cb.peer) {
        jbyteArray data = payload(cb.env, r->msgFlags, r->requestedData, r->cbRequested);
        (*cb.env)->CallVoidMethod(cb.env, cb.peer, file_data, (jint)r->streamId, data);
        if (data) (*cb.env)->DeleteLocalRef(cb.env, data);
    }
    end_callback(&cb);
    return 0;
}

static UINT server_lock(CliprdrClientContext* c, const CLIPRDR_LOCK_CLIPBOARD_DATA* r) {
    Callback cb = begin_callback(c);
    if (cb.peer) (*cb.env)->CallVoidMethod(cb.env, cb.peer, lock_data, (jint)r->clipDataId, JNI_TRUE);
    end_callback(&cb);
    return 0;
}

static UINT server_unlock(CliprdrClientContext* c, const CLIPRDR_UNLOCK_CLIPBOARD_DATA* r) {
    Callback cb = begin_callback(c);
    if (cb.peer) (*cb.env)->CallVoidMethod(cb.env, cb.peer, lock_data, (jint)r->clipDataId, JNI_FALSE);
    end_callback(&cb);
    return 0;
}

static void connected(void* context, ChannelConnectedEventArgs* e) {
    if (!context || !e || !e->name || strcmp(e->name, CLIPRDR_SVC_CHANNEL_NAME) || !e->pInterface) return;
    CliprdrClientContext* c = e->pInterface;
    pthread_rwlock_wrlock(&registry);
    ClipboardSession* s = by_instance(((rdpContext*)context)->instance);
    if (s && c->custom) {
        s->channel = c;
        s->flags = 0;
        c->ServerCapabilities = server_caps;
        c->MonitorReady = monitor_ready;
        c->ServerFormatList = server_formats;
        c->ServerFormatListResponse = server_ack;
        c->ServerFormatDataRequest = server_format_request;
        c->ServerFormatDataResponse = server_format_data;
        c->ServerFileContentsRequest = server_file_request;
        c->ServerFileContentsResponse = server_file_data;
        c->ServerLockClipboardData = server_lock;
        c->ServerUnlockClipboardData = server_unlock;
    }
    pthread_rwlock_unlock(&registry);
}

static void disconnected(void* context, ChannelDisconnectedEventArgs* e) {
    if (!context || !e || !e->name || strcmp(e->name, CLIPRDR_SVC_CHANNEL_NAME)) return;
    Callback cb = begin_callback(NULL);
    pthread_rwlock_wrlock(&registry);
    ClipboardSession* s = by_instance(((rdpContext*)context)->instance);
    if (s && (!e->pInterface || s->channel == e->pInterface)) {
        s->channel = NULL;
        if (cb.env) cb.peer = (*cb.env)->NewLocalRef(cb.env, s->peer);
    }
    pthread_rwlock_unlock(&registry);
    if (cb.peer) (*cb.env)->CallVoidMethod(cb.env, cb.peer, offline, JNI_FALSE);
    end_callback(&cb);
}

void clipboard_subscribe(freerdp* instance) {
    pthread_rwlock_rdlock(&registry);
    ClipboardSession* s = by_instance(instance);
    BOOL needed = s && !s->subscribed;
    pthread_rwlock_unlock(&registry);
    if (!needed) return;
    wPubSub* pub = instance->context->pubSub;
    if (PubSub_SubscribeChannelConnected(pub, connected) != 0) return;
    if (PubSub_SubscribeChannelDisconnected(pub, disconnected) != 0) {
        PubSub_UnsubscribeChannelConnected(pub, connected);
        return;
    }
    pthread_rwlock_wrlock(&registry);
    s = by_instance(instance);
    BOOL registered = s != NULL;
    if (s) s->subscribed = TRUE;
    pthread_rwlock_unlock(&registry);
    if (!registered) {
        PubSub_UnsubscribeChannelConnected(pub, connected);
        PubSub_UnsubscribeChannelDisconnected(pub, disconnected);
    }
}

void clipboard_detach(freerdp* instance) {
    pthread_rwlock_wrlock(&registry);
    ClipboardSession** link = &sessions;
    while (*link && (*link)->instance != instance) link = &(*link)->next;
    ClipboardSession* s = *link;
    if (s) *link = s->next;
    pthread_rwlock_unlock(&registry);
    if (!s) return;
    if (s->subscribed) {
        PubSub_UnsubscribeChannelConnected(instance->context->pubSub, connected);
        PubSub_UnsubscribeChannelDisconnected(instance->context->pubSub, disconnected);
    }
    Callback cb = begin_callback(NULL);
    if (cb.env) {
        (*cb.env)->CallVoidMethod(cb.env, s->peer, offline, JNI_TRUE);
        (*cb.env)->DeleteGlobalRef(cb.env, s->peer);
    }
    end_callback(&cb);
    free(s);
}

#define JNI_CLIP(method) Java_com_undatech_opaque_RdpClipboard_##method

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* machine, void* reserved) {
    vm = machine;
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL JNI_CLIP(bind)(JNIEnv* env, jobject self, jlong handle, jlong token) {
    freerdp* instance = (freerdp*)(uintptr_t)handle;
    if (!instance || !instance->context) return JNI_FALSE;
    jclass cls = (*env)->GetObjectClass(env, self);
    if (!cls) return JNI_FALSE;
    pthread_rwlock_wrlock(&registry);
    if (!offline) {
        ready = (*env)->GetMethodID(env, cls, "onReady", "(I)V");
        formats = (*env)->GetMethodID(env, cls, "onFormats", "([I[Ljava/lang/String;)V");
        acknowledged = (*env)->GetMethodID(env, cls, "onAcknowledged", "(Z)V");
        format_request = (*env)->GetMethodID(env, cls, "onFormatRequest", "(I)V");
        format_data = (*env)->GetMethodID(env, cls, "onFormatData", "([B)V");
        file_request = (*env)->GetMethodID(env, cls, "onFileRequest", "(IIIIIIZI)V");
        file_data = (*env)->GetMethodID(env, cls, "onFileData", "(I[B)V");
        lock_data = (*env)->GetMethodID(env, cls, "onLock", "(IZ)V");
        offline = (*env)->GetMethodID(env, cls, "onOffline", "(Z)V");
    }
    ClipboardSession* s = NULL;
    if (!(*env)->ExceptionCheck(env) && !by_instance(instance)) s = calloc(1, sizeof(*s));
    if (s) {
        s->peer = (*env)->NewGlobalRef(env, self);
        if (!s->peer) { free(s); s = NULL; }
    }
    if (s) { s->instance = instance; s->token = token; s->next = sessions; sessions = s; }
    pthread_rwlock_unlock(&registry);
    return s ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_CLIP(sendFormats)(JNIEnv* env, jclass cls, jlong token, jintArray ids, jobjectArray names) {
    jsize size = (*env)->GetArrayLength(env, ids);
    if (size != (*env)->GetArrayLength(env, names) || size > 16384) return JNI_FALSE;
    CLIPRDR_FORMAT_LIST list = {0};
    list.msgType = CB_FORMAT_LIST;
    list.numFormats = (UINT32)size;
    list.formats = size ? calloc(size, sizeof(*list.formats)) : NULL;
    BOOL ok = !size || list.formats;
    for (jsize i = 0; ok && i < size; i++) {
        jint id = 0;
        (*env)->GetIntArrayRegion(env, ids, i, 1, &id);
        list.formats[i].formatId = (UINT32)id;
        jstring name = (*env)->GetObjectArrayElement(env, names, i);
        if (name) {
            const char* str = (*env)->GetStringUTFChars(env, name, NULL);
            if (str) { list.formats[i].formatName = strdup(str); (*env)->ReleaseStringUTFChars(env, name, str); }
            ok = list.formats[i].formatName != NULL;
            (*env)->DeleteLocalRef(env, name);
        }
    }
    pthread_rwlock_rdlock(&registry);
    CliprdrClientContext* c = by_token(token);
    ok = ok && c && c->ClientFormatList(c, &list) == 0;
    pthread_rwlock_unlock(&registry);
    if (list.formats) for (jsize i = 0; i < size; i++) free(list.formats[i].formatName);
    free(list.formats);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_CLIP(getFormat)(JNIEnv* env, jclass cls, jlong token, jint id) {
    CLIPRDR_FORMAT_DATA_REQUEST r = {0};
    r.msgType = CB_FORMAT_DATA_REQUEST;
    r.requestedFormatId = (UINT32)id;
    pthread_rwlock_rdlock(&registry);
    CliprdrClientContext* c = by_token(token);
    BOOL ok = c && !c->ClientFormatDataRequest(c, &r);
    pthread_rwlock_unlock(&registry);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_CLIP(sendData)(JNIEnv* env, jclass cls, jlong token, jboolean file, jint stream, jbyteArray data) {
    jsize size = data ? (*env)->GetArrayLength(env, data) : 0;
    jbyte* bytes = size ? (*env)->GetByteArrayElements(env, data, NULL) : NULL;
    UINT16 flags = data && (!size || bytes) ? CB_RESPONSE_OK : CB_RESPONSE_FAIL;
    BYTE empty = 0;
    const BYTE* buf = bytes ? (const BYTE*)bytes : &empty;
    if (!bytes) size = 0;
    pthread_rwlock_rdlock(&registry);
    CliprdrClientContext* c = by_token(token);
    if (c && file) {
        CLIPRDR_FILE_CONTENTS_RESPONSE r = {0};
        r.msgType = CB_FILECONTENTS_RESPONSE;
        r.msgFlags = flags; r.streamId = (UINT32)stream; r.cbRequested = (UINT32)size; r.requestedData = buf;
        c->ClientFileContentsResponse(c, &r);
    } else if (c) {
        CLIPRDR_FORMAT_DATA_RESPONSE r = {0};
        r.msgType = CB_FORMAT_DATA_RESPONSE;
        r.msgFlags = flags; r.dataLen = (UINT32)size; r.requestedFormatData = buf;
        c->ClientFormatDataResponse(c, &r);
    }
    pthread_rwlock_unlock(&registry);
    if (bytes) (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
}

JNIEXPORT jboolean JNICALL JNI_CLIP(getFile)(JNIEnv* env, jclass cls, jlong token, jint stream, jint index,
        jint flags, jint low, jint high, jint count, jboolean locked, jint lockId) {
    CLIPRDR_FILE_CONTENTS_REQUEST r = {0};
    r.msgType = CB_FILECONTENTS_REQUEST;
    r.streamId = (UINT32)stream; r.listIndex = (UINT32)index; r.dwFlags = (UINT32)flags;
    r.nPositionLow = (UINT32)low; r.nPositionHigh = (UINT32)high; r.cbRequested = (UINT32)count;
    r.haveClipDataId = locked; r.clipDataId = (UINT32)lockId;
    pthread_rwlock_rdlock(&registry);
    CliprdrClientContext* c = by_token(token);
    BOOL ok = c && !c->ClientFileContentsRequest(c, &r);
    pthread_rwlock_unlock(&registry);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_CLIP(sendLock)(JNIEnv* env, jclass cls, jlong token, jint id, jboolean acquire) {
    pthread_rwlock_rdlock(&registry);
    CliprdrClientContext* c = by_token(token);
    BOOL ok = FALSE;
    if (c && acquire) {
        CLIPRDR_LOCK_CLIPBOARD_DATA r = {0};
        r.msgType = CB_LOCK_CLIPDATA;
        r.clipDataId = (UINT32)id;
        ok = !c->ClientLockClipboardData(c, &r);
    } else if (c) {
        CLIPRDR_UNLOCK_CLIPBOARD_DATA r = {0};
        r.msgType = CB_UNLOCK_CLIPDATA;
        r.clipDataId = (UINT32)id;
        ok = !c->ClientUnlockClipboardData(c, &r);
    }
    pthread_rwlock_unlock(&registry);
    return ok ? JNI_TRUE : JNI_FALSE;
}
