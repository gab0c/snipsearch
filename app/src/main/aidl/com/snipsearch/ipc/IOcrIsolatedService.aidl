package com.snipsearch.ipc;

import android.content.res.AssetFileDescriptor;
import android.os.SharedMemory;
import com.snipsearch.ipc.IOcrProgressCallback;
import com.snipsearch.ipc.OcrTextBlockParcelable;

interface IOcrIsolatedService {
    void initializeModels(in AssetFileDescriptor detector, in AssetFileDescriptor recognizer,
                          in AssetFileDescriptor dictionary);

    // Native-endian ARGB ints. Stride is in pixels. Result boxes are relative to the crop.
    List<OcrTextBlockParcelable> recognizeCrop(
        long requestId,
        in SharedMemory argbPixels,
        int width,
        int height,
        int stride,
        in IOcrProgressCallback callback
    );

    oneway void cancelRecognition(long requestId);
}
