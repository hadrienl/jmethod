import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Icônes de l'app ordinateur à partir du dessin de l'icône Android (ic_launcher_foreground.xml sur
 * fond violet #6D28D9) : j-method.png (512), j-method.ico (Windows) et un dossier .iconset pour
 * iconutil (macOS). Usage : java IconGen.java <dossier de sortie>
 */
public class IconGen {
    static BufferedImage render(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double margin = size * 0.08;
        double side = size - 2 * margin;
        g.setColor(new Color(0x6D, 0x28, 0xD9));
        g.fill(new RoundRectangle2D.Double(margin, margin, side, side, side * 0.44, side * 0.44));
        // Repère 108 x 108 du calque avant Android, posé sur le carré.
        g.translate(margin, margin);
        g.scale(side / 108.0, side / 108.0);
        Path2D j = new Path2D.Double();
        j.moveTo(68, 33);
        j.lineTo(68, 61);
        j.append(new Arc2D.Double(40, 47, 28, 28, 0, -180, Arc2D.OPEN), true);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(11f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(j);
        g.setColor(new Color(0xD9, 0xF9, 0x9D));
        g.fill(new Ellipse2D.Double(35, 32, 16, 16));
        g.dispose();
        return image;
    }

    static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : ".");
        ImageIO.write(render(512), "png", new File(dir, "j-method.png"));

        // ICO : entrées PNG (Windows Vista et suivants).
        int[] sizes = {16, 24, 32, 48, 64, 128, 256};
        List<byte[]> images = new ArrayList<>();
        for (int s : sizes) images.add(png(render(s)));
        ByteBuffer header = ByteBuffer.allocate(6 + 16 * sizes.length).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int offset = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            header.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s)).put((byte) 0).put((byte) 0);
            header.putShort((short) 1).putShort((short) 32).putInt(images.get(i).length).putInt(offset);
            offset += images.get(i).length;
        }
        try (FileOutputStream out = new FileOutputStream(new File(dir, "j-method.ico"))) {
            out.write(header.array());
            for (byte[] data : images) out.write(data);
        }

        File iconset = new File(dir, "j-method.iconset");
        iconset.mkdirs();
        for (int s : new int[] {16, 32, 128, 256, 512}) {
            ImageIO.write(render(s), "png", new File(iconset, "icon_" + s + "x" + s + ".png"));
            ImageIO.write(render(s * 2), "png", new File(iconset, "icon_" + s + "x" + s + "@2x.png"));
        }
    }
}
