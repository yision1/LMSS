/*
 * Copyright 2013 Luciad (http://www.luciad.com)
 * Licensed under the Apache License, Version 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 * Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * Modified for LMSS: retain only default JNI decoding and use explicit lifetime management.
 */
package com.luciad.imageio.webp;

final class WebPDecoderOptions implements AutoCloseable {
    final long pointer;

    WebPDecoderOptions() {
        pointer = createDecoderOptions();
        if (pointer == 0) throw new OutOfMemoryError("WebP decoder allocation failed");
    }

    @Override
    public void close() {
        deleteDecoderOptions(pointer);
    }

    private static native long createDecoderOptions();
    private static native void deleteDecoderOptions(long pointer);
}
