// One line per NNAPI device, "name\ttype\tversion\tfeatureLevel", returned as UTF-8
// byte arrays: NewStringUTF expects modified UTF-8, and driver strings are not ours.
#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <android/NeuralNetworks.h>

JNIEXPORT jobjectArray JNICALL
Java_ai_verispec_data_runtime_litert_NnapiDevices_nativeList(JNIEnv *env, jclass clazz) {
    (void) clazz;
    uint32_t count = 0;
    if (ANeuralNetworks_getDeviceCount(&count) != ANEURALNETWORKS_NO_ERROR) return NULL;

    jclass bytes = (*env)->FindClass(env, "[B");
    if (bytes == NULL) return NULL;
    jobjectArray out = (*env)->NewObjectArray(env, (jsize) count, bytes, NULL);
    if (out == NULL) return NULL;

    for (uint32_t i = 0; i < count; i++) {
        ANeuralNetworksDevice *device = NULL;
        if (ANeuralNetworks_getDevice(i, &device) != ANEURALNETWORKS_NO_ERROR) return NULL;
        const char *name = NULL;
        const char *version = NULL;
        int32_t type = ANEURALNETWORKS_DEVICE_UNKNOWN;
        int64_t level = -1;
        if (ANeuralNetworksDevice_getName(device, &name) != ANEURALNETWORKS_NO_ERROR) name = NULL;
        if (ANeuralNetworksDevice_getType(device, &type) != ANEURALNETWORKS_NO_ERROR) type = ANEURALNETWORKS_DEVICE_UNKNOWN;
        if (ANeuralNetworksDevice_getVersion(device, &version) != ANEURALNETWORKS_NO_ERROR) version = NULL;
        if (ANeuralNetworksDevice_getFeatureLevel(device, &level) != ANEURALNETWORKS_NO_ERROR) level = -1;

        char line[1024];
        int n = snprintf(line, sizeof line, "%s\t%d\t%s\t%lld",
                         name ? name : "", (int) type, version ? version : "", (long long) level);
        if (n < 0) return NULL;
        jsize len = n < (int) sizeof line ? n : (jsize) sizeof line - 1;
        jbyteArray entry = (*env)->NewByteArray(env, len);
        if (entry == NULL) return NULL;
        (*env)->SetByteArrayRegion(env, entry, 0, len, (const jbyte *) line);
        (*env)->SetObjectArrayElement(env, out, (jsize) i, entry);
        (*env)->DeleteLocalRef(env, entry);
    }
    return out;
}
