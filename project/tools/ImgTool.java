import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Iterator;

/**
 * 小工具：按像素坐标裁头像、合成启动图标。不依赖任何第三方库，JDK 17 自带 ImageIO。
 *
 * 用法：
 *   info <src>
 *   crop <src> <out> <size> <x> <y> <side> [cropX cropY cropSide]
 *   icon <src> <out> <size> <x> <y> <side> <bgTop> <bgBottom>
 */
public class ImgTool {

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        if ("info".equals(mode)) {
            BufferedImage im = ImageIO.read(new File(args[1]));
            System.out.println(args[1] + " -> " + im.getWidth() + "x" + im.getHeight());
            return;
        }
        if ("stat".equals(mode)) {
            BufferedImage src = ImageIO.read(new File(args[1]));
            int W = src.getWidth(), H = src.getHeight();
            int x0 = W, y0 = H, x1 = -1, y1 = -1;
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    int rgb = src.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                    int lum = (r * 299 + g * 587 + b * 114) / 1000;
                    if (lum < 110) {
                        if (x < x0) x0 = x;
                        if (x > x1) x1 = x;
                        if (y < y0) y0 = y;
                        if (y > y1) y1 = y;
                    }
                }
            }
            System.out.println("bbox " + x0 + "," + y0 + " - " + x1 + "," + y1
                    + "  w=" + (x1 - x0) + " h=" + (y1 - y0));
            for (int band = 0; band < 20; band++) {
                int ya = H * band / 20, yb = H * (band + 1) / 20;
                int bx0 = W, bx1 = -1, cnt = 0;
                for (int y = ya; y < yb; y++) {
                    for (int x = 0; x < W; x++) {
                        int rgb = src.getRGB(x, y);
                        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                        int lum = (r * 299 + g * 587 + b * 114) / 1000;
                        if (lum < 110) { if (x < bx0) bx0 = x; if (x > bx1) bx1 = x; cnt++; }
                    }
                }
                if (cnt > 0) System.out.println("y " + ya + "-" + yb + " x " + bx0 + ".." + bx1 + " cnt " + cnt);
            }
            return;
        }
        if ("crop".equals(mode)) {
            BufferedImage src = ImageIO.read(new File(args[1]));
            int size = Integer.parseInt(args[3]);
            int x = Integer.parseInt(args[4]);
            int y = Integer.parseInt(args[5]);
            int side = Integer.parseInt(args[6]);
            BufferedImage out = scale(crop(src, x, y, side), size, size);
            save(out, new File(args[2]), 0.92f);
            System.out.println("crop -> " + args[2] + " " + size + "px");
            return;
        }
        if ("icon".equals(mode)) {
            BufferedImage src = ImageIO.read(new File(args[1]));
            int size = Integer.parseInt(args[3]);
            int x = Integer.parseInt(args[4]);
            int y = Integer.parseInt(args[5]);
            int side = Integer.parseInt(args[6]);
            Color top = Color.decode(args[7]);
            Color bottom = Color.decode(args[8]);

            BufferedImage canvas = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = g2(canvas);
            g.setPaint(new GradientPaint(0, 0, top, 0, size, bottom));
            g.fillRect(0, 0, size, size);

            // 头像画成一个圆形，留一点内边距，四周加半透明白环
            int pad = Math.round(size * 0.075f);
            int d = size - pad * 2;
            BufferedImage head = scale(crop(src, x, y, side), d, d);
            Ellipse2D circle = new Ellipse2D.Float(pad, pad, d, d);
            Shape old = g.getClip();
            g.setClip(circle);
            g.drawImage(head, pad, pad, null);
            g.setClip(old);
            g.setColor(new Color(255, 255, 255, 90));
            g.setStroke(new BasicStroke(Math.max(2f, size * 0.012f)));
            g.draw(circle);
            g.dispose();
            save(canvas, new File(args[2]), 0.95f);
            System.out.println("icon -> " + args[2] + " " + size + "px");
        }
    }

    private static Graphics2D g2(BufferedImage im) {
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    private static BufferedImage crop(BufferedImage src, int x, int y, int side) {
        x = Math.max(0, Math.min(src.getWidth() - 1, x));
        y = Math.max(0, Math.min(src.getHeight() - 1, y));
        int w = Math.min(side, src.getWidth() - x);
        int h = Math.min(side, src.getHeight() - y);
        return src.getSubimage(x, y, w, h);
    }

    private static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = g2(out);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static void save(BufferedImage im, File out, float quality) throws Exception {
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        String name = out.getName().toLowerCase();
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
            ImageWriter writer = it.next();
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(im, null, null), p);
            }
            writer.dispose();
        } else {
            ImageIO.write(im, "png", out);
        }
    }
}
