package com.yision.lmss.webp;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;

public final class LosslessWebp {
    private static final MethodHandle ENCODE, DECODE;

    static {
        ClassLoader bindings = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (!name.startsWith("com.luciad.imageio.webp.")) {
                    throw new ClassNotFoundException(name);
                }
                String resource = "/META-INF/lmss-webp/classes/" + name.replace('.', '/') + ".class";
                try (InputStream input = LosslessWebp.class.getResourceAsStream(resource)) {
                    if (input == null) {
                        throw new ClassNotFoundException(name);
                    }
                    byte[] bytes = input.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (IOException failure) {
                    throw new ClassNotFoundException(name, failure);
                }
            }

            @Override
            protected URL findResource(String name) {
                return LosslessWebp.class.getResource("/" + name);
            }
        };
        try {
            Class<?> bridge = Class.forName("com.luciad.imageio.webp.WebP", true, bindings);
            var signature = MethodType.methodType(byte[].class, int.class, int.class, byte[].class);
            ENCODE = MethodHandles.publicLookup().findStatic(bridge, "encodeLossless", signature);
            DECODE = MethodHandles.publicLookup().findStatic(bridge, "decodeLossless", signature);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private LosslessWebp() {}

    public static byte[] encode(int width, int height, byte[] rgba) throws IOException {
        try {
            return (byte[]) ENCODE.invokeExact(width, height, rgba);
        } catch (IOException | RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IOException("WebP encoder failed", failure);
        }
    }

    public static byte[] decode(int width, int height, byte[] body) throws IOException {
        try {
            return (byte[]) DECODE.invokeExact(width, height, body);
        } catch (IOException | RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IOException("WebP decoder failed", failure);
        }
    }
}
