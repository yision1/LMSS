package com.yision.lmss.protocol;

import java.util.List;
import java.util.function.Consumer;

public final class PacedSender {
    public static final long MAX_SPREAD_NANOS = 500_000_000L;
    public static final long SMALL_UPDATE_SPREAD_NANOS = 100_000_000L;
    private List<byte[]> packets;
    private Runnable completed;
    private int index, sentBytes, beforeLast;
    private long started, spread;

    public static int wireBytes(byte[] message) {
        return message.length + GuiPayloads.unsignedBytes(message.length);
    }

    public boolean busy() {
        return packets != null;
    }

    public void start(List<byte[]> packets, long now, boolean initial, boolean smallUpdate, Runnable completed) {
        if (busy() || packets.isEmpty()) {
            throw new IllegalStateException("Sender busy or empty frame");
        }
        this.packets = List.copyOf(packets);
        this.completed = completed;
        index = sentBytes = 0;
        started = now;
        int total = packets.stream().mapToInt(PacedSender::wireBytes).sum();
        beforeLast = total - wireBytes(packets.getLast());
        spread = initial && total <= 32 * 1024 ? 0
                : Math.min(MAX_SPREAD_NANOS, total * 1_000_000_000L / (8 * 1024));
        if (smallUpdate) {
            spread = Math.min(spread, SMALL_UPDATE_SPREAD_NANOS);
        }
    }

    public void pump(long now, Consumer<byte[]> send) {
        while (busy()) {
            long due = started + (beforeLast == 0 ? 0 : spread * sentBytes / beforeLast);
            if (now < due) {
                return;
            }
            byte[] packet = packets.get(index);
            send.accept(packet);
            sentBytes += wireBytes(packet);
            if (++index == packets.size()) {
                Runnable done = completed;
                clear();
                done.run();
            }
        }
    }

    public void clear() {
        packets = null;
        completed = null;
        index = 0;
    }
}
