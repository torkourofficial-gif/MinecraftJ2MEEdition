package mcj2me;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;
import javax.microedition.rms.RecordStoreNotFoundException;

/** Persistent world catalogue and chunked world snapshots in MIDP RMS. */
final class WorldStore {
    private static final String STORE = "mcj2me-worlds";
    private static final int SNAPSHOT_CHUNK = 6000;

    static final class WorldInfo {
        final String name;
        final int seed;
        final int id;
        final int mode;
        WorldInfo(String n, int s, int recordId, int gameMode) { name = n; seed = s; id = recordId; mode = gameMode; }
    }

    static synchronized WorldInfo[] list() throws Exception {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, true);
            int count = rs.getNumRecords();
            WorldInfo[] result = new WorldInfo[count];
            int out = 0;
            for (int id = 1; id <= rs.getNextRecordID() - 1; id++) {
                try {
                    DataInputStream in = new DataInputStream(new ByteArrayInputStream(rs.getRecord(id)));
                    String name = in.readUTF();
                    int seed = in.readInt();
                    int mode = in.available() >= 4 ? in.readInt() : IndevViewerMIDlet.MODE_CREATIVE;
                    if (mode != IndevViewerMIDlet.MODE_SURVIVAL) mode = IndevViewerMIDlet.MODE_CREATIVE;
                    result[out++] = new WorldInfo(name, seed, id, mode);
                    in.close();
                } catch (Exception badRecord) { /* Ignore incomplete records. */ }
            }
            if (out == result.length) return result;
            WorldInfo[] compact = new WorldInfo[out];
            System.arraycopy(result, 0, compact, 0, out);
            return compact;
        } finally {
            if (rs != null) try { rs.closeRecordStore(); } catch (RecordStoreException ignored) { }
        }
    }

    static synchronized WorldInfo create(String requestedName) throws Exception {
        return create(requestedName, IndevViewerMIDlet.MODE_CREATIVE);
    }

    static synchronized WorldInfo create(String requestedName, int mode) throws Exception {
        String name = requestedName == null ? "" : requestedName.trim();
        if (name.length() == 0) name = "World";
        if (name.length() > 24) name = name.substring(0, 24);
        WorldInfo[] worlds = list();
        String base = name;
        int suffix = 2;
        while (contains(worlds, name)) {
            String ending = " (" + suffix++ + ")";
            int keep = 24 - ending.length();
            name = (base.length() > keep ? base.substring(0, keep) : base) + ending;
        }
        long now = System.currentTimeMillis();
        int seed = (int)(now ^ (now >>> 32) ^ (now * 1103515245L));
        RecordStore rs = null;
        int id;
        try {
            rs = RecordStore.openRecordStore(STORE, true);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF(name);
            out.writeInt(seed);
            out.writeInt(mode == IndevViewerMIDlet.MODE_SURVIVAL ? IndevViewerMIDlet.MODE_SURVIVAL : IndevViewerMIDlet.MODE_CREATIVE);
            out.flush();
            byte[] data = bytes.toByteArray();
            id = rs.addRecord(data, 0, data.length);
        } finally {
            if (rs != null) try { rs.closeRecordStore(); } catch (RecordStoreException ignored) { }
        }
        return new WorldInfo(name, seed, id, mode == IndevViewerMIDlet.MODE_SURVIVAL ? IndevViewerMIDlet.MODE_SURVIVAL : IndevViewerMIDlet.MODE_CREATIVE);
    }

    static synchronized void saveState(int worldId, byte[] state) throws Exception {
        // SD snapshots are opt-in. If the optional file protocol fails, keep
        // the existing RMS path as a compatibility fallback.
        try {
            if (IndevViewerMIDlet.isSdStorageEnabled() && FileWorldStore.isAvailable()) {
                FileWorldStore.save(worldId, state);
                return;
            }
        } catch (Throwable unavailable) {
            // Remove a possibly partial/stale card snapshot before writing the
            // latest state to RMS, otherwise a later load could roll back.
            try { FileWorldStore.delete(worldId); } catch (Throwable ignored) { }
        }
        String name = stateStoreName(worldId);
        try { RecordStore.deleteRecordStore(name); } catch (RecordStoreNotFoundException ignored) { }
        if (state == null || state.length == 0) return;
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(name, true);
            for (int offset = 0; offset < state.length; offset += SNAPSHOT_CHUNK) {
                int length = Math.min(SNAPSHOT_CHUNK, state.length - offset);
                rs.addRecord(state, offset, length);
            }
        } finally {
            if (rs != null) try { rs.closeRecordStore(); } catch (RecordStoreException ignored) { }
        }
    }

    static synchronized byte[] loadState(int worldId) throws Exception {
        try {
            if (IndevViewerMIDlet.isSdStorageEnabled() && FileWorldStore.isAvailable()) {
                byte[] state = FileWorldStore.load(worldId);
                if (state != null) return state;
            }
        } catch (Throwable missingOrUnavailable) {
            // Old worlds and unsupported devices continue using RMS.
        }
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(stateStoreName(worldId), false);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int id = 1; id <= rs.getNextRecordID() - 1; id++) {
                try { bytes.write(rs.getRecord(id)); } catch (Exception ignored) { }
            }
            return bytes.toByteArray();
        } catch (RecordStoreNotFoundException missing) {
            return null;
        } finally {
            if (rs != null) try { rs.closeRecordStore(); } catch (RecordStoreException ignored) { }
        }
    }

    static synchronized void delete(int worldId) throws Exception {
        try { FileWorldStore.delete(worldId); } catch (Throwable ignored) { }
        RecordStore catalogue = null;
        try {
            catalogue = RecordStore.openRecordStore(STORE, false);
            try { catalogue.deleteRecord(worldId); } catch (Exception ignored) { }
        } finally {
            if (catalogue != null) try { catalogue.closeRecordStore(); } catch (RecordStoreException ignored) { }
        }
        try { RecordStore.deleteRecordStore(stateStoreName(worldId)); }
        catch (RecordStoreNotFoundException ignored) { }
    }

    private static String stateStoreName(int id) {
        return "mcw" + Integer.toString(id, 36);
    }

    private static boolean contains(WorldInfo[] worlds, String name) {
        for (int i = 0; i < worlds.length; i++)
            if (worlds[i] != null && worlds[i].name.equalsIgnoreCase(name)) return true;
        return false;
    }
}
