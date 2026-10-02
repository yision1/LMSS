package com.yision.lmss.protocol;

import com.github.luben.zstd.Zstd;
import com.yision.lmss.webp.LosslessWebp;
import java.io.IOException;
import java.util.Arrays;

final class NativeCodecs {
    private static volatile boolean zstdAvailable = probeZstd();
    private static volatile boolean webpAvailable = probeWebp();

    private NativeCodecs() {}

    static int availableMask() {
        int mask = 0;
        if (zstdAvailable) {
            mask |= 1 << FrameCodec.ZSTD;
        }
        if (webpAvailable) {
            mask |= 1 << FrameCodec.WEBP;
        }
        return mask;
    }

    static byte[] zstd(byte[] raw) {
        if (!zstdAvailable) {
            return null;
        }
        try {
            return Zstd.compress(raw, 6);
        } catch (LinkageError | RuntimeException failure) {
            zstdAvailable = false;
            return null;
        }
    }

    static byte[] unzstd(byte[] compressed, int expected) throws IOException {
        if (!zstdAvailable) {
            throw new IOException("Zstd unavailable");
        }
        try {
            byte[] decoded = Zstd.decompress(compressed, expected);
            if (decoded.length != expected) {
                throw new IOException("Zstd length mismatch");
            }
            return decoded;
        } catch (LinkageError failure) {
            zstdAvailable = false;
            throw new IOException("Zstd decoder unavailable", failure);
        } catch (RuntimeException malformed) {
            throw new IOException("Invalid Zstd data", malformed);
        }
    }

    static byte[] webp(int width, int height, byte[] rgba) {
        if (!webpAvailable) {
            return null;
        }
        for (int i = 0; i < rgba.length; i += 4) {
            if (rgba[i + 3] == 0 && (rgba[i] != 0 || rgba[i + 1] != 0 || rgba[i + 2] != 0)) {
                return null;
            }
        }
        try {
            return LosslessWebp.encode(width, height, rgba);
        } catch (IOException | LinkageError | RuntimeException failure) {
            webpAvailable = false;
            return null;
        }
    }

    static byte[] unwebp(byte[] body, int width, int height) throws IOException {
        if (!webpAvailable) {
            throw new IOException("WebP unavailable");
        }
        try {
            return LosslessWebp.decode(width, height, body);
        } catch (LinkageError failure) {
            webpAvailable = false;
            throw new IOException("WebP decoder unavailable", failure);
        } catch (RuntimeException malformed) {
            throw new IOException("Invalid WebP data", malformed);
        }
    }

    private static boolean probeZstd() {
        try {
            byte[] source = {1, 2, 3, 4};
            return Arrays.equals(source, Zstd.decompress(Zstd.compress(source, 1), source.length));
        } catch (LinkageError | RuntimeException failure) {
            return false;
        }
    }

    private static boolean probeWebp() {
        try {
            byte[] source = {10, 20, 30, (byte) 255, 0, 0, 0, 0,
                70, 80, 90, (byte) 128, 1, 2, 3, (byte) 255};
            return Arrays.equals(source, LosslessWebp.decode(2, 2, LosslessWebp.encode(2, 2, source)));
        } catch (IOException | LinkageError | RuntimeException failure) {
            return false;
        }
    }
}
