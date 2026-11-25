package com.snipsearch.ipc;

interface IOcrProgressCallback {
    oneway void onDetectionComplete(int regionCount, long detectMs);
}
