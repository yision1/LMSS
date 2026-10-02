package com.yision.lmss.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class WireTransport {
    public record Binding(UUID owner, UUID session) {}
    public record Incoming(Binding binding, byte[] message) {}
    private final Map<Integer, Binding> bindings = new HashMap<>();
    private final Map<Binding, Integer> ids = new HashMap<>();
    private int next = 1;

    public GuiWirePayload.Clientbound clientbound(UUID owner, byte[] message) throws IOException {
        var parsed = WireMessages.read(message);
        if (parsed.session() == null) {
            return new GuiWirePayload.Clientbound(owner, message);
        }
        Binding binding = new Binding(owner, parsed.session());
        Integer id = ids.get(binding);
        boolean fresh = id == null;
        if (fresh) {
            id = next++;
            bind(id, binding);
        }
        return new GuiWirePayload.Clientbound(id, fresh ? owner : null, fresh ? binding.session() : null,
                compact(message));
    }

    public Incoming inbound(GuiWirePayload.Clientbound payload) throws IOException {
        if (payload.stream() == 0) {
            return new Incoming(new Binding(payload.publisher(), null), payload.data());
        }
        if (payload.publisher() != null) {
            bind(payload.stream(), new Binding(payload.publisher(), payload.session()));
        }
        Binding binding = bindings.get(payload.stream());
        if (binding == null) {
            throw new IOException("Unknown stream");
        }
        return new Incoming(binding, expand(payload.data(), binding));
    }

    public byte[] serverbound(UUID self, byte[] message) throws IOException {
        var parsed = WireMessages.read(message);
        UUID owner = parsed.target() == null ? self : parsed.target();
        Integer id = parsed.session() == null ? null : ids.get(new Binding(owner, parsed.session()));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        GuiPayloads.writeUnsigned(out, id == null ? 0 : id);
        out.write(id == null ? message : compact(message));
        return bytes.toByteArray();
    }

    public Incoming inbound(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        int id = (int) GuiPayloads.readUnsigned(in, 4);
        byte[] message = in.readAllBytes();
        if (id == 0) {
            var parsed = WireMessages.read(message);
            if (parsed.type() != WireMessages.HELLO && parsed.type() != WireMessages.OPEN && parsed.type() != WireMessages.CLOSE) {
                throw new IOException("Unbound stream message");
            }
            return new Incoming(null, message);
        }
        Binding binding = bindings.get(id);
        if (binding == null) {
            throw new IOException("Unknown stream");
        }
        return new Incoming(binding, expand(message, binding));
    }

    public int outboundBytes(UUID owner, byte[] message) throws IOException {
        UUID session = WireMessages.read(message).session();
        Binding binding = new Binding(owner, session);
        Integer id = ids.get(binding);
        byte[] body = compact(message);
        return GuiPayloads.unsignedBytes((id == null ? next : id) * 2L) + (id == null ? 32 : 0) + PacedSender.wireBytes(body);
    }

    public void clear() {
        bindings.clear();
        ids.clear();
        next = 1;
    }

    public void forget(UUID owner) {
        bindings.entrySet().removeIf(entry -> entry.getValue().owner().equals(owner));
        ids.keySet().removeIf(binding -> binding.owner().equals(owner));
    }

    public long uploadBytes(UUID owner, UUID session, long revision, int bytes) throws IOException {
        Integer id = ids.get(new Binding(owner, session));
        if (id == null) {
            throw new IOException("Unbound upload");
        }
        return frameBytes(id, revision, bytes, false);
    }

    public long downloadBytes(UUID owner, UUID session, long revision, int bytes) throws IOException {
        Integer id = ids.get(new Binding(owner, session));
        return frameBytes(id == null ? next : id, revision, bytes, true) + (id == null ? 32 : 0);
    }

    private static long frameBytes(int id, long revision, int bytes, boolean download) {
        int count = (bytes + GuiPayloads.MAX_CHUNK_BYTES - 1) / GuiPayloads.MAX_CHUNK_BYTES;
        long total = 0;
        for (int i = 0; i < count; i++) {
            int body = 1 + GuiPayloads.unsignedBytes(revision) + GuiPayloads.unsignedBytes(i) + GuiPayloads.unsignedBytes(bytes)
            + Math.min(GuiPayloads.MAX_CHUNK_BYTES, bytes - i * GuiPayloads.MAX_CHUNK_BYTES);
            int route = GuiPayloads.unsignedBytes(download ? id * 2L : id);
            total += body + route + GuiPayloads.unsignedBytes(download ? body : body + route);
        }
        return total;
    }

    private void bind(int id, Binding binding) throws IOException {
        if (id < 1 || id > 0x0fffffff || binding.owner() == null || binding.session() == null) {
            throw new IOException("Invalid stream binding");
        }
        Binding old = bindings.putIfAbsent(id, binding);
        if (old != null && !old.equals(binding)) {
            throw new IOException("Conflicting stream binding");
        }
        Integer oldId = ids.putIfAbsent(binding, id);
        if (oldId != null && oldId != id) {
            throw new IOException("Duplicate stream binding");
        }
    }

    private static byte[] compact(byte[] bytes) throws IOException {
        var m = WireMessages.read(bytes);
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(result);
        out.writeByte(m.type());
        if (m.type() == WireMessages.CHUNK) {
            out.write(m.chunk(), 16, m.chunk().length - 16);
        } else if (m.type() == WireMessages.MOUSE) {
            GuiPayloads.writeUnsigned(out, m.sequence());
            out.writeShort(m.u());
            out.writeShort(m.v());
            out.writeByte(m.flags());
        } else if (m.type() == WireMessages.ACK || m.type() == WireMessages.DISPLAYED || m.type() == WireMessages.STATUS || m.type() == WireMessages.STATE) {
            GuiPayloads.writeUnsigned(out, m.sequence());
        } else if (m.type() == WireMessages.RESYNC || m.type() == WireMessages.CACHE_READY || m.type() == WireMessages.INTEREST) {
            GuiPayloads.writeUnsigned(out, m.sequence());
            out.writeByte(m.flags());
        } else if (m.type() == WireMessages.OFFER || m.type() == WireMessages.GRANT) {
            GuiPayloads.writeUnsigned(out, m.sequence());
            GuiPayloads.writeUnsigned(out, m.width());
            GuiPayloads.writeUnsigned(out, m.height());
            if (m.type() == WireMessages.OFFER) {
                out.writeByte(m.flags());
            }
        } else {
            out.write(bytes, 17, bytes.length - 17);
        }
        return result.toByteArray();
    }

    private static byte[] expand(byte[] bytes, Binding binding) throws IOException {
        if (bytes.length < 1) {
            throw new IOException("Empty stream message");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        int type = in.readUnsignedByte();
        UUID session = binding.session();
        byte[] result;
        if (type == WireMessages.MOUSE) {
            result = WireMessages.mouse(session, GuiPayloads.readUnsigned(in, 9),
                    in.readUnsignedShort(), in.readUnsignedShort(), in.readUnsignedByte());
        } else if (type == WireMessages.ACK) {
            result = WireMessages.ack(session, GuiPayloads.readUnsigned(in, 9));
        } else if (type == WireMessages.DISPLAYED) {
            result = WireMessages.displayed(binding.owner(), session, GuiPayloads.readUnsigned(in, 9));
        } else if (type == WireMessages.STATUS) {
            result = WireMessages.status(session, GuiPayloads.readUnsigned(in, 9));
        } else if (type == WireMessages.STATE) {
            result = WireMessages.state(session, GuiPayloads.readUnsigned(in, 9));
        } else if (type == WireMessages.RESYNC || type == WireMessages.CACHE_READY || type == WireMessages.INTEREST) {
            long revision = GuiPayloads.readUnsigned(in, 9);
            int flags = in.readUnsignedByte();
            result = type == WireMessages.RESYNC ? WireMessages.resync(binding.owner(), session, revision, flags)
                    : type == WireMessages.CACHE_READY ? WireMessages.cacheReady(binding.owner(), session, revision)
                    : WireMessages.interest(binding.owner(), session, revision, (flags & 1) != 0, (flags & 2) != 0);
            if (type == WireMessages.INTEREST && flags > 3) {
                throw new IOException("Invalid interest");
            }
        } else if (type == WireMessages.OFFER || type == WireMessages.GRANT) {
            long revision = GuiPayloads.readUnsigned(in, 9);
            int size = (int) GuiPayloads.readUnsigned(in, 3), delay = (int) GuiPayloads.readUnsigned(in, 3);
            int flags = type == WireMessages.OFFER ? in.readUnsignedByte() : 0;
            if (flags > 1) {
                throw new IOException("Invalid offer flags");
            }
            result = type == WireMessages.OFFER ? WireMessages.offer(session, revision, size, delay, flags != 0)
                    : WireMessages.grant(session, revision, size, delay);
        } else {
            ByteArrayOutputStream resultBytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(resultBytes);
            out.writeByte(type);
            out.writeLong(session.getMostSignificantBits());
            out.writeLong(session.getLeastSignificantBits());
            out.write(in.readAllBytes());
            result = resultBytes.toByteArray();
        }
        if (in.available() != 0) {
            throw new IOException("Trailing stream data");
        }
        WireMessages.read(result);
        return result;
    }
}
