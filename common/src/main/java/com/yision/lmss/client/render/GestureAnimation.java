package com.yision.lmss.client.render;

public final class GestureAnimation {
    public record Target(double u, double v, float press, boolean chat, long typingElapsed) {}
    public record Sample(double u, double v, float press, float weight, float chatMix, long typingElapsed) {}

    private Target last;
    private boolean open, chat, hasTyping;
    private float weightFrom, chatFrom, press;
    private double u, v;
    private long openNanos, chatNanos, sampledNanos, typingNanos;

    public Sample sample(Target target, long now) {
        float weight = blend(weightFrom, open ? 1 : 0, now - openNanos, open ? 300_000_000L : 350_000_000L);
        float chatMix = blend(chatFrom, chat ? 1 : 0, now - chatNanos, 200_000_000L);
        if (open != (target != null)) {
            weightFrom = weight;
            open = target != null;
            openNanos = now;
        }
        if (target != null) {
            if (last == null) {
                u = target.u();
                v = target.v();
                press = target.press();
                chatMix = target.chat() ? 1 : 0;
            } else {
                double follow = 1 - Math.exp(-Math.max(0, now - sampledNanos) / 60_000_000.0);
                u += (target.u() - u) * follow;
                v += (target.v() - v) * follow;
                press += (target.press() - press) * follow;
            }
            if (last == null || chat != target.chat()) {
                chatFrom = chatMix;
                chat = target.chat();
                chatNanos = now;
            }
            if (target.chat()) {
                hasTyping = target.typingElapsed() != Long.MAX_VALUE;
                if (hasTyping) {
                    typingNanos = now - target.typingElapsed();
                }
            }
            last = target;
        }
        sampledNanos = now;
        if (last == null || (!open && weight == 0)) {
            return null;
        }
        return new Sample(u, v, press, weight, chatMix, hasTyping ? now - typingNanos : Long.MAX_VALUE);
    }

    private static float blend(float from, float to, long elapsed, long duration) {
        return from + (to - from) * (float) PanelGeometry.ease((double) elapsed / duration);
    }
}
