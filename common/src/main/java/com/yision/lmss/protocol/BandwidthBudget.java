package com.yision.lmss.protocol;

public final class BandwidthBudget {
    private final double rate, burst;
    private double tokens;
    private long updated;

    public BandwidthBudget(int rate, int burst, long now) {
        this.rate = rate;
        this.burst = burst;
        tokens = burst;
        updated = now;
    }

    public boolean reserve(long bytes, long now) {
        refill(now);
        if (bytes > tokens) {
            return false;
        }
        tokens -= bytes;
        return true;
    }

    public void charge(long bytes, long now) {
        refill(now);
        tokens -= bytes;
    }

    public void refund(long bytes, long now) {
        refill(now);
        tokens = Math.min(burst, tokens + bytes);
    }

    public int waitMs(long bytes, long now) {
        refill(now);
        return (int) Math.clamp(Math.ceil((bytes - tokens) * 1000 / rate), 0, 10_000);
    }

    private void refill(long now) {
        tokens = Math.min(burst, tokens + Math.max(0, now - updated) * rate / 1_000_000_000.0);
        updated = now;
    }
}
