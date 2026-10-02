/*
 * Copyright 2013 Luciad (http://www.luciad.com)
 * Licensed under the Apache License, Version 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 * Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * Modified for LMSS: direct RGBA codec, bounded dimensions and private native loading.
 */
package com.luciad.imageio.webp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public final class WebP {
    static {
        try {
            loadNative();
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private WebP() {}

    public static byte[] encodeLossless(int width, int height, byte[] rgba) throws IOException {
        int expected = imageBytes(width, height);
        if (rgba.length != expected) throw new IOException("WebP RGBA length mismatch");
        try (var options = new WebPEncoderOptions()) {
            options.lossless();
            byte[] encoded = encodeRGBA(options.pointer, rgba, width, height, width * 4);
            if (encoded == null || encoded.length == 0) throw new IOException("WebP encode failed");
            return encoded;
        }
    }

    public static byte[] decodeLossless(int width, int height, byte[] body) throws IOException {
        int expected = imageBytes(width, height);
        int[] info = new int[2];
        if (body.length == 0 || getInfo(body, 0, body.length, info) == 0)
            throw new IOException("Invalid WebP header");
        if (info[0] != width || info[1] != height) throw new IOException("WebP dimensions mismatch");
        int[] flags = new int[4];
        int[] pixels;
        try (var options = new WebPDecoderOptions()) {
            pixels = decode(options.pointer, body, 0, body.length, flags,
                    ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN);
        }
        if (flags[0] == 1) throw new OutOfMemoryError("WebP decode allocation failed");
        if (flags[0] != 0 || pixels == null || pixels.length != width * height
                || flags[1] != width || flags[2] != height)
            throw new IOException("WebP decode failed: " + flags[0]);
        byte[] rgba = new byte[expected];
        for (int i = 0, p = 0; i < pixels.length; i++) {
            int argb = pixels[i];
            if ((argb >>> 24) == 0) argb = 0;
            rgba[p++] = (byte) (argb >>> 16);
            rgba[p++] = (byte) (argb >>> 8);
            rgba[p++] = (byte) argb;
            rgba[p++] = (byte) (argb >>> 24);
        }
        return rgba;
    }

    private static int imageBytes(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > 256 || height > 256)
            throw new IOException("Invalid WebP dimensions");
        return width * height * 4;
    }

    private static void loadNative() throws IOException {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String os = osName.startsWith("windows") ? "Windows"
                : osName.contains("mac") || osName.contains("darwin") ? "Mac"
                : osName.contains("linux") ? "Linux" : "unsupported";
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        arch = switch (arch) {
            case "amd64", "x64", "x86_64" -> "x86_64";
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            case "arm64", "aarch64" -> "aarch64";
            case "armv7l", "armv7" -> "armv7";
            case "armv6l", "armv6" -> "armv6";
            case "arm" -> System.getProperty("sun.arch.abi", "").contains("gnueabihf") ? "armv7" : "arm";
            default -> arch;
        };
        String library = os.equals("Windows") ? "webp-imageio.dll"
                : os.equals("Mac") ? "libwebp-imageio.dylib" : "libwebp-imageio.so";
        String resource = "/META-INF/lmss-webp/native/" + os + "/" + arch + "/" + library;
        try (InputStream input = WebP.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("WebP native unavailable for " + os + "/" + arch);
            Path extracted = Files.createTempFile("lmss-webp-", "-" + library);
            extracted.toFile().deleteOnExit();
            Files.copy(input, extracted, StandardCopyOption.REPLACE_EXISTING);
            System.load(extracted.toAbsolutePath().toString());
        }
    }

    private static native int[] decode(long options, byte[] data, int offset, int length, int[] flags, boolean bigEndian);
    private static native int getInfo(byte[] data, int offset, int length, int[] info);
    private static native byte[] encodeRGBA(long options, byte[] rgba, int width, int height, int stride);
}
