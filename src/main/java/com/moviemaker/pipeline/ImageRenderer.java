package com.moviemaker.pipeline;

import com.moviemaker.domain.VideoStyle;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Random;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.springframework.stereotype.Component;

/**
 * Procedural scene artwork (Java2D). Deterministic per story text + style, so regenerating gives the same look.
 * The scene is chosen from keywords in the narration (night, sea, forest, mountains, city, desert).
 */
@Component
public class ImageRenderer {
    public static final int W = 1920, H = 1080;

    private enum Setting { NIGHT, SEA, FOREST, MOUNTAINS, CITY, DESERT, DAWN }

    public BufferedImage render(VideoStyle style, String videoTitle, String sceneTitle, String narration, int index) {
        long seed = (style.name() + sceneTitle + narration).hashCode() * 31L + index;
        Random rnd = new Random(seed);
        Setting setting = detect(narration, rnd);

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        switch (style) {
            case CINEMATIC -> cinematic(g, setting, rnd);
            case ANIMATED -> animated(g, setting, rnd);
            case DOCUMENTARY -> documentary(g, img, setting, rnd);
            case MODERN -> modern(g, rnd, index);
        }
        g.dispose();
        return img;
    }

    public static byte[] jpeg(BufferedImage src, int width, float quality) throws IOException {
        int h = Math.round(src.getHeight() * (width / (float) src.getWidth()));
        BufferedImage scaled = new BufferedImage(width, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, width, h, null);
        g.dispose();
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam p = w.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(quality);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            w.setOutput(ios);
            w.write(null, new IIOImage(scaled, null, null), p);
        } finally {
            w.dispose();
        }
        return out.toByteArray();
    }

    // ---- setting detection ---------------------------------------------------------------------------------------

    private static Setting detect(String text, Random rnd) {
        String t = text.toLowerCase(Locale.ROOT);
        if (has(t, "night", "moon", "star", "dark", "midnight")) return Setting.NIGHT;
        if (has(t, "sea", "ocean", "river", "lake", "water", "ship", "boat", "island", "fish", "dolphin", "whale")) return Setting.SEA;
        if (has(t, "forest", "tree", "jungle", "garden", "wood", "leaf")) return Setting.FOREST;
        if (has(t, "city", "town", "street", "market", "palace", "temple", "castle", "kingdom", "village")) return Setting.CITY;
        if (has(t, "desert", "sand", "dune", "camel")) return Setting.DESERT;
        if (has(t, "mountain", "hill", "peak", "valley", "snow", "cave")) return Setting.MOUNTAINS;
        Setting[] all = {Setting.DAWN, Setting.MOUNTAINS, Setting.FOREST, Setting.SEA};
        return all[rnd.nextInt(all.length)];
    }

    private static boolean has(String t, String... keys) {
        for (String k : keys) if (t.contains(k)) return true;
        return false;
    }

    // ---- palettes ------------------------------------------------------------------------------------------------

    private record Sky(Color top, Color mid, Color horizon, Color sun) {}

    private static Sky sky(Setting s, boolean vivid) {
        return switch (s) {
            case NIGHT -> new Sky(new Color(0x050B22), new Color(0x1B2A5E), new Color(0x4B5C9E), new Color(0xE8EEFF));
            case SEA -> new Sky(new Color(0x1E3C72), new Color(0x4A7FB5), new Color(0xF2B968), new Color(0xFFE7A8));
            case DESERT -> new Sky(new Color(0x7B3F2A), new Color(0xE0804A), new Color(0xFFD08A), new Color(0xFFF1C2));
            case CITY -> new Sky(new Color(0x2A2F6B), new Color(0xB5527A), new Color(0xFFB86B), new Color(0xFFE2A0));
            case FOREST, MOUNTAINS, DAWN -> vivid
                    ? new Sky(new Color(0x3F8EDB), new Color(0x8FC5F2), new Color(0xFFF1B8), new Color(0xFFF7D6))
                    : new Sky(new Color(0x2B2350), new Color(0xD9803F), new Color(0xFFCF7A), new Color(0xFFEBB0));
        };
    }

    // ---- styles --------------------------------------------------------------------------------------------------

    private void cinematic(Graphics2D g, Setting s, Random rnd) {
        Sky sky = sky(s, false);
        paintSky(g, sky);
        double sunX = W * (0.25 + 0.5 * rnd.nextDouble()), sunY = H * (0.42 + 0.1 * rnd.nextDouble());
        sun(g, sunX, sunY, 150, sky.sun(), s != Setting.NIGHT || true);
        if (s == Setting.NIGHT) stars(g, rnd, 260);
        rays(g, sunX, sunY, sky.sun());
        Color base = tint(sky.horizon(), 0.45f);
        if (s == Setting.SEA) {
            water(g, sky, rnd, sunX, sunY);
            shore(g, rnd, tint(new Color(0xE8CE98), 0.55f), tint(new Color(0xCDA96C), 0.35f));
        } else {
            ridge(g, rnd, H * 0.58, 150, blend(sky.horizon(), sky.mid(), 0.55f));
            ridge(g, rnd, H * 0.68, 130, blend(base, sky.mid(), 0.30f));
            ridge(g, rnd, H * 0.78, 100, tint(base, 0.55f));
        }
        if (s == Setting.CITY) skyline(g, rnd, H * 0.84, tint(base, 0.30f));
        if (s == Setting.FOREST || s == Setting.DAWN) trees(g, rnd, H * 0.90, 22, tint(base, 0.18f), 1.0);
        if (s == Setting.DESERT) ridge(g, rnd, H * 0.86, 60, tint(base, 0.30f));
        foreground(g, tint(base, 0.10f));
        if (s == Setting.NIGHT || s == Setting.DAWN || s == Setting.MOUNTAINS) birds(g, rnd, 5);
        vignette(g, 0.62f);
        letterbox(g, 70);
        grain(g, rnd, 0.05f);
    }

    private void animated(Graphics2D g, Setting s, Random rnd) {
        Sky sk = sky(s == Setting.NIGHT ? Setting.NIGHT : Setting.DAWN, true);
        if (s == Setting.SEA) sk = new Sky(new Color(0x56B3F5), new Color(0xA5DBFA), new Color(0xE3F6FF), new Color(0xFFE066));
        paintSky(g, sk);
        if (s == Setting.NIGHT) stars(g, rnd, 120);
        g.setColor(s == Setting.NIGHT ? new Color(0xFFF3B0) : new Color(0xFFD23F));
        double sx = W * (0.15 + 0.7 * rnd.nextDouble());
        g.fill(new Ellipse2D.Double(sx - 80, 120, 160, 160));
        if (s != Setting.NIGHT) {
            g.setColor(new Color(255, 210, 63, 90));
            g.setStroke(new BasicStroke(12, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 0; i < 12; i++) {
                double a = i * Math.PI / 6;
                g.drawLine((int) (sx + Math.cos(a) * 110), (int) (200 + Math.sin(a) * 110),
                        (int) (sx + Math.cos(a) * 150), (int) (200 + Math.sin(a) * 150));
            }
        }
        for (int i = 0; i < 5; i++) cloud(g, rnd.nextInt(W), 80 + rnd.nextInt(280), 0.7 + rnd.nextDouble());
        if (s == Setting.SEA) {
            g.setColor(new Color(0x2F8FDB));
            g.fillRect(0, (int) (H * 0.62), W, H);
            g.setColor(new Color(255, 255, 255, 140));
            g.setStroke(new BasicStroke(6, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 0; i < 26; i++) {
                int x = rnd.nextInt(W), y = (int) (H * 0.66) + rnd.nextInt((int) (H * 0.3));
                g.drawArc(x, y, 90, 24, 0, 180);
            }
            shore(g, rnd, new Color(0xF6DFA8), new Color(0xE2BE7E));
        } else {
            ridgeFlat(g, rnd, H * 0.66, 90, new Color(0x7ED36B));
            ridgeFlat(g, rnd, H * 0.78, 70, new Color(0x4FB84E));
            ridgeFlat(g, rnd, H * 0.90, 50, new Color(0x2F9A45));
            for (int i = 0; i < 7; i++) roundTree(g, 100 + rnd.nextInt(W - 200), H * 0.80 + rnd.nextInt(120), 0.8 + rnd.nextDouble());
            if (s == Setting.CITY) {
                for (int i = 0; i < 6; i++) house(g, 150 + i * 280 + rnd.nextInt(60), H * 0.80, rnd);
            }
        }
        grain(g, rnd, 0.015f);
    }

    private void documentary(Graphics2D g, BufferedImage img, Setting s, Random rnd) {
        Sky sky = sky(s, false);
        paintSky(g, sky);
        sun(g, W * 0.7, H * 0.38, 110, sky.sun(), true);
        Color base = tint(sky.horizon(), 0.5f);
        ridge(g, rnd, H * 0.60, 170, blend(sky.horizon(), sky.mid(), 0.5f));
        ridge(g, rnd, H * 0.72, 120, tint(base, 0.6f));
        if (s == Setting.CITY) skyline(g, rnd, H * 0.84, tint(base, 0.35f));
        else trees(g, rnd, H * 0.88, 16, tint(base, 0.25f), 1.1);
        foreground(g, tint(base, 0.12f));
        // sepia / desaturate pass
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int p = img.getRGB(x, y);
                int r = (p >> 16) & 255, gr = (p >> 8) & 255, b = p & 255;
                double l = 0.299 * r + 0.587 * gr + 0.114 * b;
                int nr = clamp((int) (l * 0.55 + r * 0.45 * 0.75 + 22));
                int ng = clamp((int) (l * 0.55 + gr * 0.45 * 0.75 + 12));
                int nb = clamp((int) (l * 0.50 + b * 0.45 * 0.6));
                img.setRGB(x, y, (nr << 16) | (ng << 8) | nb);
            }
        }
        vignette(g, 0.55f);
        grain(g, rnd, 0.10f);
    }

    private void modern(Graphics2D g, Random rnd, int index) {
        g.setPaint(new GradientPaint(0, 0, new Color(0x0B0B2B), W, H, new Color(0x2A0B4D)));
        g.fillRect(0, 0, W, H);
        Color[] pal = {new Color(0xFF3D8B), new Color(0x7B3FF2), new Color(0x2F7BFF), new Color(0xFF8A3D), new Color(0x19D3DA)};
        for (int i = 0; i < 7; i++) {
            Color c = pal[(i + index) % pal.length];
            double cx = rnd.nextDouble() * W, cy = rnd.nextDouble() * H, r = 260 + rnd.nextInt(380);
            g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy), (float) r, new float[]{0f, 1f},
                    new Color[]{new Color(c.getRed(), c.getGreen(), c.getBlue(), 190), new Color(c.getRed(), c.getGreen(), c.getBlue(), 0)}));
            g.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
        }
        g.setStroke(new BasicStroke(3f));
        double cx = W * (0.35 + 0.3 * rnd.nextDouble()), cy = H * (0.35 + 0.3 * rnd.nextDouble());
        for (int i = 0; i < 28; i++) {
            Color c = pal[i % pal.length];
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 120));
            double r = 60 + i * 28;
            g.draw(new Ellipse2D.Double(cx - r + Math.sin(i * 0.5) * 40, cy - r * 0.6, r * 2, r * 1.2));
        }
        grain(g, rnd, 0.03f);
    }

    // ---- drawing helpers -----------------------------------------------------------------------------------------

    private static void paintSky(Graphics2D g, Sky sk) {
        g.setPaint(new LinearGradientPaint(0, 0, 0, H * 0.62f, new float[]{0f, 0.55f, 1f},
                new Color[]{sk.top(), sk.mid(), sk.horizon()}));
        g.fillRect(0, 0, W, H);
    }

    private static void sun(Graphics2D g, double x, double y, double r, Color c, boolean glow) {
        float gr = (float) (r * 4);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), gr, new float[]{0f, 0.25f, 1f},
                new Color[]{new Color(c.getRed(), c.getGreen(), c.getBlue(), 230), new Color(c.getRed(), c.getGreen(), c.getBlue(), 90),
                        new Color(c.getRed(), c.getGreen(), c.getBlue(), 0)}));
        g.fill(new Ellipse2D.Double(x - gr, y - gr, gr * 2, gr * 2));
        g.setColor(c);
        g.fill(new Ellipse2D.Double(x - r / 2, y - r / 2, r, r));
    }

    private static void rays(Graphics2D g, double x, double y, Color c) {
        var old = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver.derive(0.10f));
        g.setColor(c);
        for (int i = -4; i <= 4; i++) {
            Path2D p = new Path2D.Double();
            p.moveTo(x, y);
            p.lineTo(x + i * 330 - 70, H);
            p.lineTo(x + i * 330 + 70, H);
            p.closePath();
            g.fill(p);
        }
        g.setComposite(old);
    }

    private static void stars(Graphics2D g, Random rnd, int n) {
        for (int i = 0; i < n; i++) {
            int a = 120 + rnd.nextInt(135);
            g.setColor(new Color(255, 255, 255, a));
            int s = 1 + rnd.nextInt(3);
            g.fillOval(rnd.nextInt(W), rnd.nextInt((int) (H * 0.5)), s, s);
        }
    }

    private static void ridge(Graphics2D g, Random rnd, double baseY, double amp, Color c) {
        double p1 = rnd.nextDouble() * 6, p2 = rnd.nextDouble() * 6, p3 = rnd.nextDouble() * 6;
        Path2D path = new Path2D.Double();
        path.moveTo(0, H);
        for (int x = 0; x <= W; x += 6) {
            double y = baseY - amp * (0.5 * Math.sin(x * 0.0024 + p1) + 0.3 * Math.sin(x * 0.0071 + p2) + 0.2 * Math.sin(x * 0.019 + p3));
            path.lineTo(x, y);
        }
        path.lineTo(W, H);
        path.closePath();
        g.setPaint(new GradientPaint(0, (float) (baseY - amp), c, 0, (float) (baseY + 200), tint(c, 0.75f)));
        g.fill(path);
    }

    private static void ridgeFlat(Graphics2D g, Random rnd, double baseY, double amp, Color c) {
        double p1 = rnd.nextDouble() * 6, p2 = rnd.nextDouble() * 6;
        Path2D path = new Path2D.Double();
        path.moveTo(0, H);
        for (int x = 0; x <= W; x += 8) {
            path.lineTo(x, baseY - amp * (0.6 * Math.sin(x * 0.0021 + p1) + 0.4 * Math.sin(x * 0.0055 + p2)));
        }
        path.lineTo(W, H);
        path.closePath();
        g.setColor(c);
        g.fill(path);
    }

    private static void water(Graphics2D g, Sky sk, Random rnd, double sunX, double sunY) {
        int hy = (int) (H * 0.60);
        g.setPaint(new GradientPaint(0, hy, tint(sk.horizon(), 0.85f), 0, H, tint(sk.top(), 0.5f)));
        g.fillRect(0, hy, W, H - hy);
        g.setPaint(new GradientPaint(0, hy, new Color(255, 230, 170, 160), 0, H, new Color(255, 230, 170, 0)));
        g.fill(new Ellipse2D.Double(sunX - 90, hy, 180, H - hy));
        g.setStroke(new BasicStroke(3f));
        for (int i = 0; i < 70; i++) {
            int y = hy + 10 + rnd.nextInt(H - hy - 20);
            int x = rnd.nextInt(W);
            int len = 40 + (int) ((y - hy) * 0.35);
            g.setColor(new Color(255, 255, 255, 40 + rnd.nextInt(60)));
            g.drawLine(x, y, x + len, y);
        }
    }

    /** Sandy bank along the bottom so people in water scenes stand on land, not on the water. */
    private static void shore(Graphics2D g, Random rnd, Color top, Color bottom) {
        double base = H * 0.74, p1 = rnd.nextDouble() * 6, p2 = rnd.nextDouble() * 6;
        Path2D p = new Path2D.Double();
        p.moveTo(0, H);
        for (int x = 0; x <= W; x += 8) p.lineTo(x, base + 16 * Math.sin(x * 0.0035 + p1) + 8 * Math.sin(x * 0.011 + p2));
        p.lineTo(W, H);
        p.closePath();
        g.setPaint(new GradientPaint(0, (float) base, top, 0, H, bottom));
        g.fill(p);
        g.setColor(new Color(255, 255, 255, 90));   // surf line
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D edge = new Path2D.Double();
        edge.moveTo(0, base - 6 + 16 * Math.sin(p1));
        for (int x = 8; x <= W; x += 8) edge.lineTo(x, base - 6 + 16 * Math.sin(x * 0.0035 + p1) + 8 * Math.sin(x * 0.011 + p2));
        g.draw(edge);
    }

    private static void skyline(Graphics2D g, Random rnd, double baseY, Color c) {
        g.setColor(c);
        int x = -20;
        while (x < W) {
            int w = 60 + rnd.nextInt(120), h = 120 + rnd.nextInt(300);
            g.fillRect(x, (int) (baseY - h), w, h + 200);
            if (rnd.nextInt(4) == 0) {
                Path2D dome = new Path2D.Double();
                dome.moveTo(x, baseY - h);
                dome.curveTo(x, baseY - h - 70, x + w, baseY - h - 70, x + w, baseY - h);
                g.fill(dome);
            }
            x += w + 6 + rnd.nextInt(14);
        }
        g.setColor(new Color(255, 214, 120, 150));
        for (int i = 0; i < 90; i++) g.fillRect(rnd.nextInt(W), (int) baseY - 20 - rnd.nextInt(300), 6, 9);
    }

    private static void trees(Graphics2D g, Random rnd, double baseY, int count, Color c, double scale) {
        g.setColor(c);
        for (int i = 0; i < count; i++) {
            double x = rnd.nextDouble() * W, h = (160 + rnd.nextInt(260)) * scale;
            g.fill(new java.awt.geom.Rectangle2D.Double(x - 7, baseY - h * 0.55, 14, h * 0.6));
            for (int k = 0; k < 7; k++) {
                double r = h * (0.16 + 0.14 * rnd.nextDouble());
                g.fill(new Ellipse2D.Double(x - h * 0.28 + rnd.nextDouble() * h * 0.5, baseY - h - r + rnd.nextDouble() * h * 0.35, r * 2, r * 1.5));
            }
        }
    }

    private static void foreground(Graphics2D g, Color c) {
        Path2D p = new Path2D.Double();
        p.moveTo(0, H);
        p.lineTo(0, H * 0.93);
        p.curveTo(W * 0.3, H * 0.88, W * 0.7, H * 0.97, W, H * 0.92);
        p.lineTo(W, H);
        p.closePath();
        g.setColor(c);
        g.fill(p);
    }

    private static void birds(Graphics2D g, Random rnd, int n) {
        g.setColor(new Color(20, 20, 30, 190));
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < n; i++) {
            int x = 300 + rnd.nextInt(W - 600), y = 150 + rnd.nextInt(300), s = 14 + rnd.nextInt(14);
            g.drawArc(x - s, y, s, s / 2, 0, 160);
            g.drawArc(x, y, s, s / 2, 20, 160);
        }
    }

    private static void cloud(Graphics2D g, int x, int y, double s) {
        g.setColor(new Color(255, 255, 255, 235));
        g.fill(new Ellipse2D.Double(x, y, 160 * s, 70 * s));
        g.fill(new Ellipse2D.Double(x + 40 * s, y - 36 * s, 110 * s, 90 * s));
        g.fill(new Ellipse2D.Double(x + 90 * s, y - 10 * s, 130 * s, 70 * s));
    }

    private static void roundTree(Graphics2D g, double x, double baseY, double s) {
        g.setColor(new Color(0x8A5A3B));
        g.fill(new java.awt.geom.RoundRectangle2D.Double(x - 12 * s, baseY - 120 * s, 24 * s, 130 * s, 8, 8));
        g.setColor(new Color(0x1F8A3E));
        g.fill(new Ellipse2D.Double(x - 70 * s, baseY - 230 * s, 140 * s, 130 * s));
        g.setColor(new Color(0x2FA84F));
        g.fill(new Ellipse2D.Double(x - 55 * s, baseY - 215 * s, 90 * s, 90 * s));
    }

    private static void house(Graphics2D g, double x, double baseY, Random rnd) {
        Color[] walls = {new Color(0xFFE0B2), new Color(0xFFCCBC), new Color(0xC5E1A5), new Color(0xB3E5FC)};
        g.setColor(walls[rnd.nextInt(walls.length)]);
        g.fillRect((int) x, (int) baseY - 110, 150, 110);
        g.setColor(new Color(0xD84315));
        Path2D roof = new Path2D.Double();
        roof.moveTo(x - 14, baseY - 110);
        roof.lineTo(x + 75, baseY - 190);
        roof.lineTo(x + 164, baseY - 110);
        roof.closePath();
        g.fill(roof);
        g.setColor(new Color(0x5D4037));
        g.fillRect((int) x + 58, (int) baseY - 64, 34, 64);
    }

    private static void vignette(Graphics2D g, float strength) {
        g.setPaint(new RadialGradientPaint(new Point2D.Double(W / 2.0, H / 2.0), (float) (W * 0.62), new float[]{0.55f, 1f},
                new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, (int) (255 * strength))}));
        g.fillRect(0, 0, W, H);
    }

    private static void letterbox(Graphics2D g, int h) {
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, W, h);
        g.fillRect(0, H - h, W, h);
    }

    private static void grain(Graphics2D g, Random rnd, float amount) {
        var old = g.getComposite();
        for (int i = 0; i < 14000; i++) {
            g.setComposite(AlphaComposite.SrcOver.derive(amount * (0.3f + rnd.nextFloat())));
            g.setColor(rnd.nextBoolean() ? Color.WHITE : Color.BLACK);
            g.fillRect(rnd.nextInt(W), rnd.nextInt(H), 2, 2);
        }
        g.setComposite(old);
    }

    private static Color tint(Color c, float f) {
        return new Color(clamp((int) (c.getRed() * f)), clamp((int) (c.getGreen() * f)), clamp((int) (c.getBlue() * f)));
    }

    private static Color blend(Color a, Color b, float t) {
        return new Color(clamp((int) (a.getRed() * (1 - t) + b.getRed() * t)),
                clamp((int) (a.getGreen() * (1 - t) + b.getGreen() * t)),
                clamp((int) (a.getBlue() * (1 - t) + b.getBlue() * t)));
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
