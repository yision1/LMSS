package com.yision.lmss.client.stream;

public final class ViewInterest {
    private boolean active;
    private long hiddenAt = -1, sentAt = -1;

    public boolean active() {
        return active;
    }

    public boolean update(boolean visible, long now) {
        boolean previous = active;
        if (visible) {
            active = true;
            hiddenAt = -1;
        } else {
            if (hiddenAt < 0) {
                hiddenAt = now;
            }
            if (now - hiddenAt >= 500_000_000L) {
                active = false;
            }
        }
        boolean send = active != previous || (active && (sentAt < 0 || now - sentAt >= 1_000_000_000L));
        if (send) {
            sentAt = now;
        }
        return send;
    }
}
