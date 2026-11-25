package com.snipsearch.ipc;

parcelable OcrTextBlockParcelable {
    String text;
    // Exclusive UTF-16 end offsets and one left/top/right/bottom tuple per word.
    int[] wordStarts;
    int[] wordEnds;
    int[] wordBounds;
}
