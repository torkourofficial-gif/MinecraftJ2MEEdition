package mcj2me;

import java.io.IOException;
import java.io.InputStream;
import javax.microedition.lcdui.Image;

/**
 * Loads images from the MIDlet JAR and reports the exact resource that failed.
 * A leading slash means the resource path is relative to the JAR root.
 */
final class ResourceImages {
    private ResourceImages() {
    }

    static Image load(String path) throws IOException {
        InputStream input = ResourceImages.class.getResourceAsStream(path);
        if (input == null) {
            throw new IOException("Missing image resource in JAR: " + path);
        }

        try {
            Image image = Image.createImage(input);
            if (image == null) {
                throw new IOException("Image decoder returned null for: " + path);
            }
            return image;
        } catch (IOException e) {
            throw new IOException("Cannot read/decode image " + path + ": " + e.toString());
        } finally {
            try {
                input.close();
            } catch (IOException ignored) {
                // The image has already been decoded; a close failure is not fatal.
            }
        }
    }
}
