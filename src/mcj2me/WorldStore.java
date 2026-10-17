package mcj2me;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;

/** Persistent world catalogue stored by MIDP Record Management System. */
final class WorldStore {
    private static final String STORE = "mcj2me-worlds";

    static final class WorldInfo {
        final String name;
        final int seed;
        WorldInfo(String n, int s) { name = n; seed = s; }
    }

    static synchronized WorldInfo[] list() throws Exception {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, true);
            int count = rs.getNumRecords();
            WorldInfo[] result = new WorldInfo[count];
            int out = 0;
            for (int id = 1; id <= count; id++) {
                try {
                    DataInputStream in = new DataInputStream(
                        new ByteArrayInputStream(rs.getRecord(id)));
                    String name = in.readUTF();
                    int seed = in.readInt();
                    result[out++] = new WorldInfo(name, seed);
                    in.close();
                } catch (Exception badRecord) {
                    // Ignore an incomplete record rather than breaking the world list.
                }
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
        try {
            rs = RecordStore.openRecordStore(STORE, true);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF(name);
            out.writeInt(seed);
            out.flush();
            byte[] data = bytes.toByteArray();
            rs.addRecord(data, 0, data.length);
        } finally {
            if (rs != null) try { rs.closeRecordStore(); } catch (RecordStoreException ignored) { }
        }
        return new WorldInfo(name, seed);
    }

    private static boolean contains(WorldInfo[] worlds, String name) {
        for (int i = 0; i < worlds.length; i++)
            if (worlds[i] != null && worlds[i].name.equalsIgnoreCase(name)) return true;
        return false;
    }
}
