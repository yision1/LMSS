package com.yision.lmss.client.render;

public final class CursorPixels {
    public static final int HOTSPOT = 3;
    public static final int WIDTH = 16, HEIGHT = 21;
    private CursorPixels() {}

    public static byte[] softened() {
        return PreviewAppearance.blur(WIDTH, HEIGHT, PreviewAppearance.blur(WIDTH, HEIGHT, create()));
    }

    public static byte[] create() {
        String[] rows = {"X.........", "XX........", "XOX.......", "XOOX......", "XOOOX.....",
            "XOOOOX....", "XOOOOOX...", "XOOOOOOX..", "XOOOOOOOX.", "XOOOOXXXXX",
            "XOOXOX....", "XOX.OX....", "XX..XOX...", "X...XOX...", ".....XX..."};
        byte[] rgba = new byte[WIDTH * HEIGHT * 4];
        for (int y = 0; y < rows.length; y++) {
            for (int x = 0; x < rows[y].length(); x++) {
                char pixel = rows[y].charAt(x);
                int offset = ((y + HOTSPOT) * WIDTH + x + HOTSPOT) * 4;
                if (pixel == 'O') {
                    rgba[offset] = rgba[offset + 1] = rgba[offset + 2] = (byte) 255;
                }
                if (pixel != '.') {
                    rgba[offset + 3] = (byte) 255;
                }
            }
        }
        return rgba;
    }
}
