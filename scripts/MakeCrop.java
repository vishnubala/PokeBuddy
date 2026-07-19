import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.zip.GZIPOutputStream;

/**
 * Offline fixture generator: crops the appraisal bar band out of a real device capture
 * and writes it as gzipped raw RGB, so the unit test can read EXACT pixels using only
 * java.util.zip (AWT/ImageIO are unavailable when compiling against android.jar).
 *
 * Header (all int32 big-endian): originX, originY, width, height. Then w*h*3 bytes RGB.
 */
public class MakeCrop {
    public static void main(String[] a) throws Exception {
        BufferedImage img = ImageIO.read(new File(a[0]));
        int ox = Integer.parseInt(a[1]), oy = Integer.parseInt(a[2]);
        int w = Integer.parseInt(a[3]), h = Integer.parseInt(a[4]);
        File out = new File(a[5]);
        try (DataOutputStream d = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(out)))) {
            d.writeInt(ox); d.writeInt(oy); d.writeInt(w); d.writeInt(h);
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int rgb = img.getRGB(ox + x, oy + y);
                    d.write((rgb >> 16) & 0xFF); d.write((rgb >> 8) & 0xFF); d.write(rgb & 0xFF);
                }
        }
        System.out.println("crop " + w + "x" + h + " at (" + ox + "," + oy + ") -> " + out.length() + " bytes");
    }
}
