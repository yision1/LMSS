/*
 * Copyright 2013 Luciad (http://www.luciad.com)
 * Licensed under the Apache License, Version 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 * Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * Modified for LMSS: retain only lossless JNI settings and use explicit lifetime management.
 */
package com.luciad.imageio.webp;

final class WebPEncoderOptions implements AutoCloseable {
    final long pointer;

    WebPEncoderOptions() {
        pointer = createConfig();
        if (pointer == 0) throw new OutOfMemoryError("WebP config allocation failed");
    }

    void lossless() {
        setLossless(pointer, true);
        setQuality(pointer, 75f);
        setMethod(pointer, 6);
    }

    @Override
    public void close() {
        deleteConfig(pointer);
    }

    private static native long createConfig();
    private static native void deleteConfig(long pointer);
    private static native void setLossless(long pointer, boolean lossless);
    private static native void setQuality(long pointer, float quality);
    private static native void setMethod(long pointer, int method);
}
