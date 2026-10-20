package mcj2me;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import javax.microedition.io.Connector;
import javax.microedition.io.StreamConnection;

/**
 * Optional microSD snapshot backend. Uses only MIDP Connector stream types so
 * the game can still compile on profiles where the JSR-75 FileConnection API
 * is not on the compile classpath. The file: protocol itself is optional at
 * runtime; callers must fall back to RMS when it is unavailable.
 *
 * This stores the existing world snapshot externally; it is not virtual RAM
 * and does not serialize live M3G objects.
 */
final class FileWorldStore {
    private static final String PREFIX = "MinecraftIndev-world-";
    private static final int MAGIC = 0x4D434A32; // MCJ2
    private static final int VERSION = 1;

    private FileWorldStore() { }

    static boolean isAvailable() {
        try {
            String root = System.getProperty("fileconn.dir.memorycard");
            return root != null && root.length() > 0;
        } catch (Exception unavailable) {
            return false;
        }
    }

    static synchronized void save(int worldId, byte[] state) throws Exception {
        if (state == null) state = new byte[0];
        String url = url(worldId);
        StreamConnection connection = null;
        DataOutputStream out = null;
        try {
            connection = (StreamConnection) Connector.open(url, Connector.WRITE, true);
            OutputStream stream = connection.openOutputStream();
            out = new DataOutputStream(stream);
            // A length-prefixed record makes stale trailing bytes harmless on
            // implementations whose output stream does not truncate the file.
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(state.length);
            out.write(state);
            out.flush();
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) { }
            if (connection != null) try { connection.close(); } catch (Exception ignored) { }
        }
    }

    static synchronized byte[] load(int worldId) throws Exception {
        StreamConnection connection = null;
        DataInputStream in = null;
        try {
            connection = (StreamConnection) Connector.open(url(worldId), Connector.READ, true);
            InputStream stream = connection.openInputStream();
            in = new DataInputStream(stream);
            if (in.readInt() != MAGIC) throw new Exception("Invalid SD world file");
            if (in.readInt() != VERSION) throw new Exception("Unsupported SD world version");
            int length = in.readInt();
            if (length < 0 || length > 16 * 1024 * 1024)
                throw new Exception("Invalid SD world size");
            byte[] state = new byte[length];
            in.readFully(state);
            return state;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
            if (connection != null) try { connection.close(); } catch (Exception ignored) { }
        }
    }

    private static String url(int worldId) throws Exception {
        String root = System.getProperty("fileconn.dir.memorycard");
        if (root == null || root.length() == 0)
            throw new Exception("This Java ME runtime does not expose the memory card");
        if (root.charAt(root.length() - 1) != '/') root += "/";
        return root + PREFIX + Integer.toString(worldId, 36) + ".dat";
    }
}
