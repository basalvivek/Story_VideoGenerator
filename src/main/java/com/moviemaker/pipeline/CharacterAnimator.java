package com.moviemaker.pipeline;

import com.moviemaker.pipeline.CharacterCast.Kind;
import com.moviemaker.pipeline.CharacterCast.Member;
import com.moviemaker.pipeline.CharacterCast.Staging;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Draws animated, shaded cartoon characters (people and animals) onto a transparent 1280x720 frame.
 * People are staged from the story: a speaker gestures and talks while listeners stand around attentively
 * (nodding, blinking, breathing); otherwise they stroll. Animals wander.
 */
public final class CharacterAnimator {
    public static final int W = 1280, H = 720;

    private enum Role { SPEAKER, LISTENER, WALKER, ANIMAL }

    private static final class Actor {
        Kind kind; Look look; Role role;
        double x, lo, hi, period, phase, ground, scale, face, seed;
        int style;
    }

    private final List<Actor> actors = new ArrayList<>();

    public CharacterAnimator(List<Kind> cast, int sceneIndex) {
        this(new Staging(cast.stream().map(k -> new Member(k, false)).toList(), false), sceneIndex);
    }

    public CharacterAnimator(Staging staging, int sceneIndex) {
        Random rnd = new Random(staging.members().hashCode() * 31L + sceneIndex);
        List<Member> humans = new ArrayList<>(), animals = new ArrayList<>();
        for (Member m : staging.members()) (m.kind().human() ? humans : animals).add(m);

        if (staging.conversation()) layoutConversation(humans, sceneIndex, rnd);
        else layoutStroll(humans, rnd);
        for (int i = 0; i < animals.size(); i++) {
            Kind k = animals.get(i).kind();
            Actor a = new Actor();
            a.kind = k; a.role = Role.ANIMAL; a.seed = rnd.nextDouble();
            double centre = animals.size() == 1 ? 0.5 : 0.30 + 0.40 * i;
            a.lo = W * Math.max(0.10, centre - 0.22); a.hi = W * Math.min(0.90, centre + 0.22);
            a.period = 11 + rnd.nextDouble() * 6; a.phase = rnd.nextDouble() * Math.PI * 2;
            a.ground = H * (0.915 + 0.02 * i);
            a.scale = switch (k) {
                case ELEPHANT -> 1.3;
                case HORSE, COW, LION, BEAR -> 1.05;
                case RABBIT, CAT, FOX -> 0.7;
                default -> 0.9;
            };
            actors.add(a);
        }
        actors.sort((p, q) -> Double.compare(p.ground, q.ground));
    }

    private void layoutConversation(List<Member> humans, int sceneIndex, Random rnd) {
        boolean mirror = sceneIndex % 2 == 1;
        double dir = mirror ? -1 : 1;
        Member speaker = humans.stream().filter(Member::speaker).findFirst().orElse(humans.get(0));
        List<Member> listeners = new ArrayList<>(humans);
        listeners.remove(speaker);
        int n = listeners.size(), front = Math.min(n, 4), back = n - front;

        Actor s = human(speaker.kind(), 0, Role.SPEAKER, rnd);
        s.x = fx(W * 0.26, mirror); s.ground = H * 0.83; s.face = dir; s.scale *= 1.04;
        actors.add(s);
        for (int i = 0; i < n; i++) {
            Actor a = human(listeners.get(i).kind(), i + 1, Role.LISTENER, rnd);
            boolean frontRow = i < front;
            int idx = frontRow ? i : i - front;
            double x = frontRow ? W * (0.50 + 0.125 * idx + (front < 4 ? 0.03 * (4 - front) : 0)) : W * (0.56 + 0.125 * idx);
            a.x = fx(x, mirror);
            a.ground = frontRow ? H * 0.83 : H * 0.765;
            if (!frontRow) a.scale *= 0.93;
            a.face = -dir;
            actors.add(a);
        }
    }

    private void layoutStroll(List<Member> humans, Random rnd) {
        int n = humans.size();
        double period = 12 + rnd.nextDouble() * 4, phase = rnd.nextDouble() * Math.PI * 2;
        double half = n > 3 ? 0.17 * W : 0.24 * W, step = n > 4 ? 112 : 150;
        for (int i = 0; i < n; i++) {
            Actor a = human(humans.get(i).kind(), i, Role.WALKER, rnd);
            a.lo = W * 0.5 + (i - (n - 1) / 2.0) * step - half;
            a.hi = W * 0.5 + (i - (n - 1) / 2.0) * step + half;
            a.period = period; a.phase = phase;
            a.ground = H * (0.81 + 0.025 * (i % 2));
            actors.add(a);
        }
    }

    private static double fx(double x, boolean mirror) { return mirror ? W - x : x; }

    private static Actor human(Kind k, int idx, Role role, Random rnd) {
        Actor a = new Actor();
        a.kind = k; a.role = role; a.seed = rnd.nextDouble(); a.style = rnd.nextInt(4);
        a.look = Look.of(k, idx, rnd);
        a.scale = a.look.height * 1.25;
        return a;
    }

    public void draw(BufferedImage frame, double t) {
        Graphics2D g = frame.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (Actor a : actors) {
            var old = g.getTransform();
            if (a.role == Role.ANIMAL) animal(g, a, t);
            else person(g, a, t);
            g.setTransform(old);
        }
        g.dispose();
    }

    // ---- looks ---------------------------------------------------------------------------------------------------

    private static final class Look {
        Kind kind;
        Color skin, hair, eye, top, top2, bottom, accent;
        int hairStyle;
        boolean female, child, beard, moustache, glasses;
        double height = 1.0, headScale = 1.0;

        static Look of(Kind k, int idx, Random rnd) {
            Look l = new Look();
            l.kind = k;
            Color[] skins = {new Color(0xF6D2B0), new Color(0xE8B58A), new Color(0xD59A6C), new Color(0xB57A4E), new Color(0x8D5A3A)};
            l.skin = skins[rnd.nextInt(skins.length)];
            Color[] hairs = {new Color(0x1E140D), new Color(0x2E1D12), new Color(0x4A3020), new Color(0x6B3E1F)};
            l.hair = hairs[rnd.nextInt(hairs.length)];
            l.eye = new Color[]{new Color(0x3A2415), new Color(0x4F3A22), new Color(0x2C4A6B)}[rnd.nextInt(3)];
            l.female = k == Kind.QUEEN || k == Kind.PRINCESS || k == Kind.GIRL || k == Kind.WOMAN || k == Kind.MOTHER;
            l.child = k == Kind.BOY || k == Kind.GIRL;
            Color[] boyTops = {new Color(0x2F8F9D), new Color(0xD9572B), new Color(0x4E8F3A), new Color(0x7B4FC4), new Color(0xC9A227), new Color(0x3A63B8)};
            switch (k) {
                case KING -> { l.top = new Color(0xB3202A); l.top2 = new Color(0xF4C430); l.bottom = new Color(0x4A1218); l.beard = true; l.moustache = true;
                    l.hairStyle = 1; l.height = 1.06; }
                case QUEEN -> { l.top = new Color(0x6A2C91); l.top2 = new Color(0xF4C430); l.bottom = new Color(0x4D1D69); l.hairStyle = 2; l.height = 1.0; }
                case PRINCE -> { l.top = new Color(0x1F5FB3); l.top2 = new Color(0xF4C430); l.bottom = new Color(0x2B2F55); l.hairStyle = 1; l.height = 0.96; }
                case PRINCESS -> { l.top = new Color(0xE0558F); l.top2 = new Color(0xF8D6E4); l.bottom = new Color(0xC23C74); l.hairStyle = 2; l.hair = new Color(0x6B3E1F); l.height = 0.94; }
                case FATHER -> { l.top = new Color(0x3F6FA8); l.top2 = new Color(0xE9E2D0); l.bottom = new Color(0x3A3A4F); l.moustache = true; l.hairStyle = 0; l.height = 1.0; }
                case MOTHER -> { l.top = new Color(0x1F8C7A); l.top2 = new Color(0xF2B233); l.bottom = new Color(0x17695C); l.hairStyle = 3; l.height = 0.95; }
                case BOY -> { l.top = boyTops[idx % boyTops.length]; l.top2 = Color.WHITE; l.bottom = new Color(0x3C4A6B); l.hairStyle = idx % 2;
                    l.height = 0.70 + 0.04 * (idx % 3); l.headScale = 1.2; }
                case GIRL -> { l.top = new Color[]{new Color(0xF2A03D), new Color(0xE0558F), new Color(0x4EA5D9)}[idx % 3]; l.top2 = Color.WHITE;
                    l.bottom = l.top; l.hairStyle = idx % 2 == 0 ? 4 : 2; l.height = 0.70 + 0.03 * (idx % 3); l.headScale = 1.2; }
                case WOMAN -> { l.top = new Color(0x2E8B57); l.top2 = new Color(0xF5F0E6); l.bottom = new Color(0x3C4A6B); l.hairStyle = 2; l.height = 0.97; }
                case ELDER -> { l.top = new Color(0xE9E1CF); l.top2 = new Color(0xB48A52); l.bottom = new Color(0xCFC6B0); l.hair = new Color(0xEDEDED);
                    l.hairStyle = 5; l.beard = true; l.moustache = true; l.glasses = true; l.height = 0.94; }
                case FARMER -> { l.top = new Color(0x6B8E3A); l.top2 = new Color(0xF0E2B6); l.bottom = new Color(0x8A6B3F); l.moustache = true; l.hairStyle = 0; }
                case SOLDIER -> { l.top = new Color(0x8C2F2F); l.top2 = new Color(0x9AA0A8); l.bottom = new Color(0x3B3B3B); l.hairStyle = 0; l.height = 1.04; }
                case MERCHANT -> { l.top = new Color(0xD9822B); l.top2 = new Color(0x7A3B1E); l.bottom = new Color(0xF2E3C2); l.beard = true; l.hairStyle = 0; }
                default -> { l.top = new Color[]{new Color(0x3A5FCD), new Color(0x8A4F9E), new Color(0xB5651D)}[idx % 3]; l.top2 = Color.WHITE;
                    l.bottom = new Color(0x3C3A55); l.hairStyle = idx % 2; }
            }
            l.accent = l.top2;
            return l;
        }
    }

    // ---- people --------------------------------------------------------------------------------------------------

    /** Everything that varies from frame to frame for one person. */
    private static final class Pose {
        double lookX, mouth, smile = 0.6, blink, brow, nod, lean, breathe;
        double liftL, liftR, strideL, strideR, bounce;
        double aR = 0.12, fR = 0.12, aL = 0.12, fL = 0.12;   // absolute arm angles from "straight down"
    }

    private void person(Graphics2D g, Actor a, double t) {
        Pose p = new Pose();
        double x = a.x;
        double blinkPeriod = 3.4 + a.seed * 1.8;
        p.blink = blinkAt(t + a.seed * 9, blinkPeriod);
        p.breathe = Math.sin(t * 1.9 + a.seed * 6) * 0.5 + 0.5;
        switch (a.role) {
            case SPEAKER -> {
                double sp = speech(t, a.seed);
                p.lookX = a.face; p.mouth = sp; p.smile = 0.45; p.brow = 0.35 * sp;
                p.nod = 0.035 * Math.sin(t * 3.1) * sp; p.lean = 0.012 * Math.sin(t * 1.3);
                boolean rightFacing = a.face > 0;
                double w1 = Math.sin(t * 2.6 + a.seed), w2 = Math.sin(t * 2.6 + 1.1 + a.seed);
                double ga = 0.95 + 0.22 * w1, gf = 2.0 + 0.5 * w2;                // gesturing arm: raised, hand open toward listeners
                double ra = 0.14 + 0.03 * w2, rf = -0.45 + 0.12 * w1;             // other arm: relaxed, hand near the belt
                if (rightFacing) { p.aR = ga; p.fR = gf; p.aL = ra; p.fL = rf; }
                else { p.aL = ga; p.fL = gf; p.aR = ra; p.fR = rf; }
            }
            case LISTENER -> {
                p.lookX = a.face;
                double nodEnv = Math.pow(Math.max(0, Math.sin(t * 0.55 + a.seed * 12)), 3);
                p.nod = 0.07 * Math.sin(t * 5.2 + a.seed) * nodEnv + 0.02 * Math.sin(t * 0.9 + a.seed * 4);
                p.lean = 0.01 * Math.sin(t * 0.8 + a.seed * 5);
                p.smile = 0.55 + 0.25 * Math.sin(t * 0.6 + a.seed * 3); p.brow = 0.15 + 0.1 * Math.sin(t * 0.7 + a.seed);
                switch (a.style) {
                    case 0 -> { p.aR = 0.10; p.fR = 0.10; p.aL = 0.10; p.fL = 0.10; }                                   // arms at sides
                    case 1 -> { p.aR = 0.45; p.fR = -1.25; p.aL = 0.45; p.fL = -1.25; }                                 // hands clasped in front
                    case 2 -> { p.aR = 0.08; p.fR = 0.08; p.aL = 0.55; p.fL = -2.3 + 0.05 * Math.sin(t * 0.8); }         // hand to chin, thinking
                    default -> { p.aR = 0.05; p.fR = -0.15; p.aL = 0.05; p.fL = -0.15; }                                // hands behind back
                }
            }
            default -> {   // WALKER
                double arg = 2 * Math.PI / a.period * t + a.phase;
                double mid = (a.lo + a.hi) / 2, half = (a.hi - a.lo) / 2;
                x = mid - half * Math.cos(arg);
                double dir = Math.sin(arg) >= 0 ? 1 : -1, speed = Math.abs(Math.sin(arg));
                double moving = Math.min(1, speed * 4), walk = x / 17.0, s = Math.sin(walk);
                p.lookX = dir; p.smile = 0.5; p.lean = 0.02 * dir * moving;
                p.liftL = Math.max(0, s) * 16 * moving; p.liftR = Math.max(0, -s) * 16 * moving;
                p.strideL = s * 9 * moving; p.strideR = -s * 9 * moving;
                p.bounce = Math.abs(s) * 4 * moving;
                p.aR = 0.12 - 0.45 * s * moving; p.fR = p.aR - 0.25 * moving;
                p.aL = 0.12 + 0.45 * s * moving; p.fL = p.aL - 0.25 * moving;
            }
        }
        shadow(g, x, a.ground, a.look.child ? 34 : 44, a.scale);
        g.translate(x, a.ground - p.bounce * a.scale);
        g.scale(a.scale, a.scale);
        renderHuman(g, a.look, p, a.style);
    }

    private static double speech(double t, double seed) {
        double gate = clamp01((Math.sin(t * 1.1 + seed * 6) + 0.35) * 3);   // phrases with short pauses
        double syllables = Math.abs(Math.sin(t * 9.5 + seed)) * (0.6 + 0.4 * Math.sin(t * 3.7));
        return gate * syllables;
    }

    private static double blinkAt(double t, double period) {
        double u = t % period;
        return u < 0.15 ? Math.sin(u / 0.15 * Math.PI) : 0;
    }

    private static void renderHuman(Graphics2D g, Look l, Pose p, int variant) {
        Kind k = l.kind;
        boolean gown = k == Kind.QUEEN || k == Kind.PRINCESS || k == Kind.MOTHER || k == Kind.WOMAN;
        boolean frock = k == Kind.GIRL;
        boolean robe = k == Kind.KING || k == Kind.ELDER || k == Kind.MERCHANT;
        double hem = switch (k) { case QUEEN, PRINCESS -> -6; case MOTHER, WOMAN -> -16; case GIRL -> -58; case KING, ELDER -> -20; case MERCHANT -> -48; default -> 0; };

        if (k == Kind.KING) cape(g);

        // legs
        if (!(gown || frock || robe)) {
            leg(g, -13, p.strideL, p.liftL, l);
            leg(g, 13, p.strideR, p.liftR, l);
        } else {
            // visible shins and shoes under the hem
            double hy = hem;
            shoe(g, -14 + p.strideL, -p.liftL, l);
            shoe(g, 14 + p.strideR, -p.liftR, l);
            if (hy < -30) {
                tube(g, -13, hy, -13 + p.strideL, -12 - p.liftL, 14, 12, l.skin);
                tube(g, 13, hy, 13 + p.strideR, -12 - p.liftR, 14, 12, l.skin);
            }
        }

        // upper body pivots about the hips (leaning / swaying)
        g.rotate(p.lean, 0, -100);
        double br = p.breathe * 1.6;
        if (gown || frock) skirt(g, l, hem, p);
        if (robe) robeBody(g, l, hem);
        torso(g, l, p, br, gown || frock || robe);

        // arms
        arm(g, -1, p.aL, p.fL, l, p, br);
        arm(g, 1, p.aR, p.fR, l, p, br);

        // neck and head
        g.setPaint(new GradientPaint(-9, -200, shade(l.skin, 0.8f), 9, -200, l.skin));
        g.fill(new RoundRectangle2D.Double(-9, -206 - br, 18, 22, 8, 8));
        var pre = g.getTransform();
        g.translate(0, -br);
        g.rotate(p.nod, 0, -196);
        g.translate(0, -196);
        g.scale(l.headScale, l.headScale);
        g.translate(0, 196);
        head(g, l, p);
        g.setTransform(pre);

        if (k == Kind.ELDER) {   // walking stick in the hand nearest the front
            g.setColor(new Color(0x7A5230));
            g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(48, -96, 52, 0);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        }
    }

    private static void cape(Graphics2D g) {
        Path2D c = new Path2D.Double();
        c.moveTo(-34, -184);
        c.curveTo(-60, -120, -66, -50, -58, -4);
        c.lineTo(58, -4);
        c.curveTo(66, -50, 60, -120, 34, -184);
        c.closePath();
        fillShaded(g, c, new Color(0x7E141C));
    }

    private static void leg(Graphics2D g, double hipX, double stride, double lift, Look l) {
        double ay = -14 - lift;
        tube(g, hipX, -104, hipX + stride, ay, 25, 17, l.bottom);
        if (l.kind == Kind.FARMER) {   // rolled trousers
            g.setColor(shade(l.bottom, 0.7f));
            g.fill(new Rectangle2D.Double(hipX + stride - 9, ay - 14, 18, 5));
        }
        shoe(g, hipX + stride, -lift, l);
    }

    private static void shoe(Graphics2D g, double x, double y, Look l) {
        Color c = switch (l.kind) { case SOLDIER -> new Color(0x2A2A2A); case FARMER -> new Color(0x5A3B22); case BOY, GIRL -> new Color(0xF2F2F2); default -> new Color(0x3A2A20); };
        fillShaded(g, new Ellipse2D.Double(x - 14, y - 12, 30, 14), c);
    }

    private static void skirt(Graphics2D g, Look l, double hem, Pose p) {
        double wide = l.kind == Kind.QUEEN || l.kind == Kind.PRINCESS ? 52 : (l.kind == Kind.GIRL ? 40 : 38);
        double sway = Math.sin(p.strideL * 0.2) * 2;
        Path2D s = new Path2D.Double();
        s.moveTo(-23, -112);
        s.curveTo(-26, -90, -wide - sway, -50, -wide - sway, hem);
        s.curveTo(-wide * 0.4, hem + 6, wide * 0.4, hem + 6, wide + sway, hem);
        s.curveTo(wide + sway, -50, 26, -90, 23, -112);
        s.closePath();
        fillShaded(g, s, l.bottom);
        g.setColor(l.top2);
        g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new java.awt.geom.QuadCurve2D.Double(-wide - sway, hem, 0, hem + 10, wide + sway, hem));   // hem trim
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0, 0, 0, 40));
        for (int i = -2; i <= 2; i++) g.drawLine(i * 9, -96, (int) (i * wide * 0.55), (int) hem - 2);   // folds
    }

    private static void robeBody(Graphics2D g, Look l, double hem) {
        Path2D s = new Path2D.Double();
        s.moveTo(-28, -112);
        s.curveTo(-34, -80, -40, -40, -38, hem);
        s.lineTo(38, hem);
        s.curveTo(40, -40, 34, -80, 28, -112);
        s.closePath();
        fillShaded(g, s, l.top);
        g.setColor(l.top2);
        g.setStroke(new BasicStroke(4f));
        g.drawLine(0, -110, 0, (int) hem);                                   // front trim
        g.drawLine(-38, (int) hem, 38, (int) hem);                           // hem trim
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    }

    private static void torso(Graphics2D g, Look l, Pose p, double br, boolean longGarment) {
        double sh = 31, waist = l.female ? 21 : 26, hip = l.female ? 27 : 27;
        Path2D t = new Path2D.Double();
        t.moveTo(-sh, -178 - br);
        t.quadTo(0, -192 - br, sh, -178 - br);
        t.curveTo(sh + 5, -150, waist + 2, -128, waist, -106);
        t.lineTo(hip, -98);
        t.lineTo(-hip, -98);
        t.lineTo(-waist, -106);
        t.curveTo(-waist - 2, -128, -sh - 5, -150, -sh, -178 - br);
        t.closePath();
        Color base = switch (l.kind) { case KING, ELDER, MERCHANT -> l.top; default -> l.top; };
        fillShaded(g, t, base);
        Color line = shade(base, 0.6f);
        g.setColor(line);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (l.kind) {
            case KING -> {   // ermine collar with spots, gold belt
                g.setColor(new Color(0xF4F1EA));
                g.fill(new Ellipse2D.Double(-30, -190 - br, 60, 20));
                g.setColor(new Color(0x222222));
                for (int i = -2; i <= 2; i++) g.fill(new Ellipse2D.Double(i * 11 - 2, -184 - br + (i % 2) * 3, 4, 7));
                g.setColor(new Color(0xF4C430));
                g.fill(new Rectangle2D.Double(-27, -116, 54, 9));
                g.setColor(new Color(0xD62839));
                g.fill(new Ellipse2D.Double(-5, -118, 10, 12));
            }
            case QUEEN, PRINCESS -> {
                g.setColor(l.top2);
                g.fill(new Rectangle2D.Double(-22, -116, 44, 6));   // sash
                necklace(g, br);
            }
            case MOTHER -> {   // scarf (dupatta) across the shoulder
                g.setColor(new Color(0xF2B233));
                Path2D sc = new Path2D.Double();
                sc.moveTo(-sh, -178 - br); sc.lineTo(-sh + 14, -178 - br); sc.lineTo(sh - 10, -110); sc.lineTo(sh - 24, -110); sc.closePath();
                g.fill(sc);
            }
            case SOLDIER -> {   // breastplate and belt
                Path2D plate = new Path2D.Double();
                plate.moveTo(-24, -176 - br); plate.lineTo(24, -176 - br); plate.lineTo(20, -122); plate.lineTo(-20, -122); plate.closePath();
                fillShaded(g, plate, l.top2);
                g.setColor(new Color(0x4A2E1C));
                g.fill(new Rectangle2D.Double(-27, -118, 54, 8));
                g.setColor(l.top2);
                g.fill(new Ellipse2D.Double(-38, -184 - br, 18, 14));
                g.fill(new Ellipse2D.Double(20, -184 - br, 18, 14));
            }
            case FARMER -> {   // open vest over a cream shirt
                g.setColor(l.top2);
                g.fill(new Rectangle2D.Double(-8, -180 - br, 16, 66));
                fillShaded(g, new Polygon2(new double[]{-30, -8, -8, -26}, new double[]{-178 - br, -180 - br, -108, -112}).path(), l.top);
                fillShaded(g, new Polygon2(new double[]{30, 8, 8, 26}, new double[]{-178 - br, -180 - br, -108, -112}).path(), l.top);
            }
            case MERCHANT -> {   // sash
                g.setColor(l.top2);
                g.fill(new Rectangle2D.Double(-27, -118, 54, 9));
            }
            case ELDER -> {      // shawl
                g.setColor(l.top2);
                Path2D sw = new Path2D.Double();
                sw.moveTo(-34, -178 - br); sw.quadTo(0, -196 - br, 34, -178 - br); sw.lineTo(26, -150); sw.quadTo(0, -134, -26, -150); sw.closePath();
                g.fill(sw);
            }
            default -> {
                if (!l.female || l.kind == Kind.GIRL) {   // collar + buttons for plain shirts
                    g.setColor(l.top2);
                    Path2D v = new Path2D.Double();
                    v.moveTo(-10, -182 - br); v.lineTo(0, -166 - br); v.lineTo(10, -182 - br); v.lineTo(14, -180 - br); v.lineTo(0, -158 - br); v.lineTo(-14, -180 - br); v.closePath();
                    g.fill(v);
                    g.setColor(line);
                    for (int i = 0; i < 3; i++) g.fill(new Ellipse2D.Double(-1.5, -150 + i * 14 - br, 3, 3));
                } else {
                    g.setColor(new Color(0x00000000, true));
                    necklace(g, br);
                }
            }
        }
        if (!longGarment && l.kind != Kind.SOLDIER && l.kind != Kind.KING) {   // belt
            g.setColor(new Color(0x2F2118));
            g.fill(new Rectangle2D.Double(-27, -108, 54, 7));
            g.setColor(new Color(0xD8B04A));
            g.fill(new Rectangle2D.Double(-4, -108, 8, 7));
        }
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    }

    private static void necklace(Graphics2D g, double br) {
        g.setColor(new Color(0xF4C430));
        g.setStroke(new BasicStroke(2.5f));
        g.draw(new java.awt.geom.QuadCurve2D.Double(-12, -184 - br, 0, -166 - br, 12, -184 - br));
        g.fill(new Ellipse2D.Double(-3, -171 - br, 6, 6));
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    }

    /** Arm hanging from the shoulder; angles are measured from straight down, positive = outward. */
    private static void arm(Graphics2D g, int side, double a, double f, Look l, Pose p, double br) {
        double sx = side * 31, sy = -176 - br;
        double ex = sx + side * Math.sin(a) * 42, ey = sy + Math.cos(a) * 42;
        double hx = ex + side * Math.sin(f) * 38, hy = ey + Math.cos(f) * 38;
        boolean longSleeve = l.kind == Kind.KING || l.kind == Kind.ELDER || l.kind == Kind.MERCHANT || l.kind == Kind.SOLDIER
                || l.kind == Kind.FARMER || l.kind == Kind.FATHER || l.kind == Kind.MAN || l.kind == Kind.PRINCE || l.kind == Kind.QUEEN;
        Color sleeve = l.kind == Kind.SOLDIER ? l.top : l.top;
        tube(g, sx, sy, ex, ey, 17, 15, sleeve);
        tube(g, ex, ey, hx, hy, 14, 11, longSleeve ? sleeve : l.skin);
        if (longSleeve) {   // cuff
            g.setColor(l.top2);
            double cx = ex + (hx - ex) * 0.82, cy = ey + (hy - ey) * 0.82;
            g.fill(new Ellipse2D.Double(cx - 6, cy - 4, 12, 8));
        }
        fillShaded(g, new Ellipse2D.Double(hx - 7.5, hy - 6, 15, 15), l.skin);
        if (l.kind == Kind.KING || l.kind == Kind.QUEEN) {   // ring
            g.setColor(new Color(0xF4C430));
            g.fill(new Ellipse2D.Double(hx - 2, hy + 1, 5, 4));
        }
    }

    private static void head(Graphics2D g, Look l, Pose p) {
        double lx = p.lookX * 4;
        // hair behind the head
        boolean longHair = l.hairStyle == 2;
        if (longHair) {
            Path2D back = new Path2D.Double();
            back.moveTo(-31, -230); back.curveTo(-40, -200, -36, -150, -26, -142); back.lineTo(26, -142); back.curveTo(36, -150, 40, -200, 31, -230);
            back.curveTo(20, -262, -20, -262, -31, -230); back.closePath();
            fillShaded(g, back, l.hair);
        }
        if (l.hairStyle == 4) {   // pigtails
            for (int s = -1; s <= 1; s += 2) {
                fillShaded(g, new Ellipse2D.Double(s * 33 - 8, -214, 16, 36), l.hair);
                g.setColor(new Color(0xE0558F));
                g.fill(new Ellipse2D.Double(s * 31 - 5, -221, 10, 10));
            }
        }
        // ears
        for (int s = -1; s <= 1; s += 2) fillShaded(g, new Ellipse2D.Double(s * 26 - 5, -226, 10, 16), l.skin);
        // face
        Ellipse2D face = new Ellipse2D.Double(-26, -252, 52, 60);
        Point2D c = new Point2D.Double(-8 + lx, -234);
        g.setPaint(new RadialGradientPaint(c, 52f, new float[]{0f, 1f}, new Color[]{lighten(l.skin, 0.12f), shade(l.skin, 0.80f)}));
        g.fill(face);
        g.setColor(shade(l.skin, 0.55f));
        g.draw(face);
        // cheeks
        g.setColor(new Color(235, 110, 110, l.child ? 70 : 45));
        g.fill(new Ellipse2D.Double(-24 + lx * 0.5, -212, 14, 9));
        g.fill(new Ellipse2D.Double(10 + lx * 0.5, -212, 14, 9));
        // eyes
        double eyeH = 11 * (1 - 0.92 * p.blink);
        for (int s = -1; s <= 1; s += 2) {
            double ex = s * 10 + lx, ey = -226;
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(ex - 6.5, ey - eyeH / 2, 13, eyeH));
            if (p.blink < 0.6) {
                g.setColor(l.eye);
                g.fill(new Ellipse2D.Double(ex - 4.2 + p.lookX * 1.6, ey - 4.2, 8.4, 8.4));
                g.setColor(new Color(0x15100C));
                g.fill(new Ellipse2D.Double(ex - 2.2 + p.lookX * 1.8, ey - 2.2, 4.4, 4.4));
                g.setColor(Color.WHITE);
                g.fill(new Ellipse2D.Double(ex - 0.5 + p.lookX * 1.4, ey - 3.6, 2.4, 2.4));
            }
            g.setColor(shade(l.skin, 0.4f));
            g.setStroke(new BasicStroke(l.female ? 2.2f : 1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new java.awt.geom.Arc2D.Double(ex - 6.5, ey - eyeH / 2 - 1, 13, eyeH + 1, 15, 150, java.awt.geom.Arc2D.OPEN));   // lid / lashes
            // eyebrow
            g.setColor(shade(l.hair, 0.9f));
            g.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double by = ey - 11 - p.brow * 3;
            g.draw(new java.awt.geom.QuadCurve2D.Double(ex - 7, by + 2 - (s > 0 ? p.brow * 1.5 : 0), ex, by - 1, ex + 7, by + 2 - (s > 0 ? 0 : p.brow * 1.5)));
            if (l.glasses) {
                g.setColor(new Color(0x3A3A3A));
                g.setStroke(new BasicStroke(1.8f));
                g.draw(new Ellipse2D.Double(ex - 9, ey - 8, 18, 16));
            }
        }
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        if (l.glasses) {
            g.setColor(new Color(0x3A3A3A));
            g.drawLine((int) (lx - 1), -226, (int) (lx + 1), -226);
        }
        // nose
        g.setColor(shade(l.skin, 0.72f));
        g.fill(new Ellipse2D.Double(-4 + lx * 1.3, -219, 9, 7));
        g.setColor(lighten(l.skin, 0.2f));
        g.fill(new Ellipse2D.Double(-2 + lx * 1.3, -221, 4, 4));
        // moustache / beard
        if (l.beard) {
            Path2D b = new Path2D.Double();
            b.moveTo(-25, -214); b.curveTo(-26, -176, 26, -176, 25, -214); b.curveTo(14, -200, -14, -200, -25, -214); b.closePath();
            fillShaded(g, b, l.kind == Kind.ELDER ? new Color(0xF0F0F0) : shade(l.hair, 1.1f));
        }
        // mouth
        double my = -206, mw = 13;
        double mx = lx * 0.9;
        if (p.mouth > 0.08) {
            double mh = 2 + p.mouth * 9;
            g.setColor(new Color(0x4B1A1A));
            g.fill(new Ellipse2D.Double(mx - mw / 2, my - mh / 2 + 2, mw, mh));
            g.setColor(new Color(0xE8888F));
            g.fill(new Ellipse2D.Double(mx - mw / 4, my + mh * 0.05 + 2, mw / 2, mh * 0.4));
            if (p.mouth > 0.3) { g.setColor(Color.WHITE); g.fill(new Rectangle2D.Double(mx - mw / 2 + 2, my - mh / 2 + 2, mw - 4, 2.5)); }
        } else {
            g.setColor(shade(blend(l.skin, new Color(0xB04545), 0.5f), 0.9f));
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double lift = 2 + 5 * p.smile;
            g.draw(new java.awt.geom.QuadCurve2D.Double(mx - mw / 2, my - 1, mx, my + lift, mx + mw / 2, my - 1));
        }
        if (l.moustache && !l.beard) {
            g.setColor(shade(l.hair, 0.95f));
            Path2D m = new Path2D.Double();
            m.moveTo(mx - 12, -207); m.curveTo(mx - 6, -216, mx - 1, -212, mx, -211); m.curveTo(mx + 1, -212, mx + 6, -216, mx + 12, -207);
            m.curveTo(mx + 6, -210, mx + 2, -207, mx, -208); m.curveTo(mx - 2, -207, mx - 6, -210, mx - 12, -207); m.closePath();
            g.fill(m);
        } else if (l.moustache) {
            g.setColor(shade(l.kind == Kind.ELDER ? new Color(0xF0F0F0) : l.hair, 0.95f));
            g.fill(new Ellipse2D.Double(mx - 10, -212, 20, 7));
        }
        hairFront(g, l);
        headwear(g, l);
    }

    private static void hairFront(Graphics2D g, Look l) {
        Color h = l.hair;
        Path2D cap = new Path2D.Double();
        switch (l.hairStyle) {
            case 5 -> {   // elder: bald crown, white tufts at the sides
                fillShaded(g, new Ellipse2D.Double(-31, -240, 14, 26), h);
                fillShaded(g, new Ellipse2D.Double(17, -240, 14, 26), h);
                return;
            }
            case 1, 2 -> {   // side-parted fringe
                cap.moveTo(-28, -224); cap.curveTo(-34, -268, 34, -272, 28, -224);
                cap.curveTo(26, -238, 20, -246, 6, -246); cap.curveTo(-6, -240, -18, -236, -28, -224); cap.closePath();
            }
            default -> {     // short crop
                cap.moveTo(-27, -226); cap.curveTo(-32, -266, 32, -266, 27, -226);
                cap.curveTo(24, -240, 12, -244, 0, -243); cap.curveTo(-12, -244, -22, -238, -27, -226); cap.closePath();
            }
        }
        fillShaded(g, cap, h);
        if (l.hairStyle == 3) fillShaded(g, new Ellipse2D.Double(-11, -272, 22, 22), h);   // bun
    }

    private static void headwear(Graphics2D g, Look l) {
        switch (l.kind) {
            case KING, QUEEN, PRINCE, PRINCESS -> {
                Color gold = new Color(0xF4C430);
                double top = -250, w = l.kind == Kind.PRINCE || l.kind == Kind.PRINCESS ? 18 : 25;
                Path2D crown = new Path2D.Double();
                crown.moveTo(-w, top); crown.lineTo(-w - 2, top - 26); crown.lineTo(-w / 2, top - 12); crown.lineTo(0, top - 32);
                crown.lineTo(w / 2, top - 12); crown.lineTo(w + 2, top - 26); crown.lineTo(w, top); crown.closePath();
                fillShaded(g, crown, gold);
                g.setColor(new Color(0xD62839));
                g.fill(new Ellipse2D.Double(-4, top - 12, 8, 8));
                g.setColor(new Color(0x3AA0D8));
                g.fill(new Ellipse2D.Double(-w + 3, top - 8, 6, 6));
                g.fill(new Ellipse2D.Double(w - 9, top - 8, 6, 6));
            }
            case FARMER -> {
                Color straw = new Color(0xE3C16F);
                fillShaded(g, new Ellipse2D.Double(-46, -250, 92, 20), straw);
                fillShaded(g, new java.awt.geom.Arc2D.Double(-29, -283, 58, 64, 0, 180, java.awt.geom.Arc2D.CHORD), straw);
                g.setColor(new Color(0x8A5A3B));
                g.fill(new Rectangle2D.Double(-29, -246, 58, 6));
            }
            case SOLDIER -> {
                Color steel = new Color(0x8B9099);
                fillShaded(g, new java.awt.geom.Arc2D.Double(-31, -276, 62, 62, 0, 180, java.awt.geom.Arc2D.CHORD), steel);
                g.setColor(shade(steel, 0.8f));
                g.fill(new Rectangle2D.Double(-33, -246, 66, 7));
                g.setColor(new Color(0xB3202A));
                fillShaded(g, new Ellipse2D.Double(-5, -300, 10, 30), new Color(0xB3202A));
            }
            case MERCHANT -> {
                fillShaded(g, new Ellipse2D.Double(-34, -282, 68, 46), new Color(0xF2E3C2));
                g.setColor(new Color(0xD9822B));
                g.fill(new Rectangle2D.Double(-33, -250, 66, 7));
                g.setColor(new Color(0xB3202A));
                g.fill(new Ellipse2D.Double(-4, -258, 8, 8));
            }
            default -> { }
        }
    }

    // ---- animals -------------------------------------------------------------------------------------------------

    private void animal(Graphics2D g, Actor a, double t) {
        double arg = 2 * Math.PI / a.period * t + a.phase;
        double mid = (a.lo + a.hi) / 2, half = (a.hi - a.lo) / 2;
        double x = mid - half * Math.cos(arg);
        double dir = Math.sin(arg) >= 0 ? 1 : -1, speed = Math.abs(Math.sin(arg));
        if (a.kind == Kind.BIRD) {
            double bx = mid - half * 1.5 * Math.cos(arg * 0.8), by = H * 0.20 + 55 * Math.sin(arg * 1.7 + a.seed * 6) + a.seed * 90;
            g.translate(bx, by);
            g.scale((Math.sin(arg * 0.8) >= 0 ? 1 : -1) * a.scale, a.scale);
            bird(g, t * 9 + a.seed * 5);
        } else if (a.kind == Kind.FISH) {
            g.translate(x, H * 0.76 + 50 * Math.sin(arg * 1.3) + a.seed * 30);
            g.scale(dir * a.scale, a.scale);
            fish(g, t * 6, a.seed);
        } else {
            double walk = x / 20.0;
            double bob = Math.abs(Math.sin(walk)) * 5 * Math.min(1, speed * 3) + Math.sin(t * 2 + a.seed * 6) * 1.5;
            shadow(g, x, a.ground, 70, a.scale);
            g.translate(x, a.ground - bob);
            g.scale(dir * a.scale, a.scale);
            quadruped(g, a.kind, Math.sin(walk) * Math.min(1, speed * 4), t);
        }
    }

    private static void quadruped(Graphics2D g, Kind k, double swing, double t) {
        Color body, accent;
        switch (k) {
            case DOG -> { body = new Color(0xB07A4A); accent = new Color(0x6B4524); }
            case CAT -> { body = new Color(0xE8A24A); accent = new Color(0xA8641E); }
            case HORSE -> { body = new Color(0x7B4B2A); accent = new Color(0x2A1A10); }
            case LION -> { body = new Color(0xD9A441); accent = new Color(0x8A4B14); }
            case RABBIT -> { body = new Color(0xEDEDED); accent = new Color(0xF5B5C5); }
            case ELEPHANT -> { body = new Color(0x8A94A3); accent = new Color(0xB8C0CC); }
            case FOX -> { body = new Color(0xE0702B); accent = new Color(0xFFF2E0); }
            case COW -> { body = new Color(0xF5F0E6); accent = new Color(0x2B2B2B); }
            case MONKEY -> { body = new Color(0x8B5A33); accent = new Color(0xE8C39E); }
            case DEER -> { body = new Color(0xB98350); accent = new Color(0x6E4A2A); }
            default -> { body = new Color(0x5B4636); accent = new Color(0x2E231A); }   // bear
        }
        double leg = k == Kind.RABBIT ? 34 : (k == Kind.ELEPHANT ? 62 : (k == Kind.HORSE || k == Kind.DEER ? 74 : 52));
        double a = swing * 0.55;
        double bodyY = -leg - 26;
        double[] lx = {-46, -30, 32, 48};
        double[] ph = {a, -a, -a, a};
        for (int i = 0; i < 4; i++) {
            Color lc = i % 2 == 0 ? shade(body, 0.78f) : body;
            tube(g, lx[i], bodyY + 10, lx[i] + Math.sin(ph[i]) * leg, bodyY + 10 + Math.cos(ph[i]) * leg, k == Kind.ELEPHANT ? 22 : 14, k == Kind.ELEPHANT ? 20 : 11, lc);
        }
        g.setColor(accent);
        g.setStroke(new BasicStroke(k == Kind.RABBIT ? 5f : 9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double wag = Math.sin(t * 7) * 14;
        if (k == Kind.RABBIT) g.fill(new Ellipse2D.Double(-78, bodyY - 12, 22, 22));
        else if (k != Kind.ELEPHANT) g.drawLine(-62, (int) bodyY - 4, -92, (int) (bodyY - 36 + wag));
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        fillShaded(g, new Ellipse2D.Double(-68, bodyY - 32, 136, 66), body);
        if (k == Kind.COW) {
            g.setColor(accent);
            g.fill(new Ellipse2D.Double(-40, bodyY - 24, 30, 24));
            g.fill(new Ellipse2D.Double(10, bodyY - 6, 28, 22));
        }
        double hx = k == Kind.HORSE || k == Kind.DEER ? 70 : 64, hy = bodyY - (k == Kind.HORSE || k == Kind.DEER ? 52 : 26);
        tube(g, 48, bodyY - 6, hx, hy, k == Kind.HORSE || k == Kind.DEER ? 26 : 32, k == Kind.HORSE || k == Kind.DEER ? 22 : 30, body);
        if (k == Kind.LION) fillShaded(g, new Ellipse2D.Double(hx - 36, hy - 36, 72, 72), accent);
        double hr = k == Kind.ELEPHANT ? 34 : (k == Kind.HORSE || k == Kind.DEER ? 20 : 24);
        fillShaded(g, new Ellipse2D.Double(hx - hr, hy - hr, hr * 2, hr * 2), body);
        switch (k) {
            case ELEPHANT -> {
                fillShaded(g, new Ellipse2D.Double(hx - 62, hy - 22, 36, 62), accent);
                double sw = Math.sin(t * 3) * 10;
                tube(g, hx + 28, hy + 4, hx + 46 + sw, hy + 52, 20, 12, body);
            }
            case RABBIT -> {
                fillShaded(g, new Ellipse2D.Double(hx - 14, hy - 62, 12, 46), body);
                fillShaded(g, new Ellipse2D.Double(hx + 2, hy - 64, 12, 46), body);
                g.setColor(accent);
                g.fill(new Ellipse2D.Double(hx - 11, hy - 54, 6, 32));
            }
            case DEER -> {
                g.setColor(accent);
                g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine((int) hx - 6, (int) hy - 18, (int) hx - 12, (int) hy - 56);
                g.drawLine((int) hx - 12, (int) hy - 40, (int) hx - 28, (int) hy - 52);
                g.drawLine((int) hx + 8, (int) hy - 18, (int) hx + 18, (int) hy - 56);
                g.drawLine((int) hx + 18, (int) hy - 40, (int) hx + 34, (int) hy - 50);
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            }
            case COW -> {
                g.setColor(accent);
                g.fill(new Ellipse2D.Double(hx - 14, hy + 6, 34, 18));
                g.setColor(new Color(0xE8DCC0));
                g.fillArc((int) hx - 16, (int) hy - 36, 12, 22, 0, 180);
                g.fillArc((int) hx + 6, (int) hy - 36, 12, 22, 0, 180);
            }
            default -> {
                Color ec = k == Kind.CAT || k == Kind.FOX || k == Kind.LION || k == Kind.DOG ? shade(body, 0.78f) : accent;
                Path2D ear = new Path2D.Double();
                ear.moveTo(hx - 18, hy - 14); ear.lineTo(hx - 10, hy - 42); ear.lineTo(hx + 2, hy - 20); ear.closePath();
                fillShaded(g, ear, ec);
                Path2D ear2 = new Path2D.Double();
                ear2.moveTo(hx + 2, hy - 20); ear2.lineTo(hx + 14, hy - 40); ear2.lineTo(hx + 20, hy - 10); ear2.closePath();
                fillShaded(g, ear2, ec);
                fillShaded(g, new Ellipse2D.Double(hx + 8, hy + 2, 28, 18), k == Kind.FOX ? accent : shade(body, 0.85f));
            }
        }
        boolean blink = (t % 3.8) < 0.12;
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(hx + 6, hy - 9, 11, blink ? 2 : 11));
        if (!blink) { g.setColor(new Color(0x2B2118)); g.fill(new Ellipse2D.Double(hx + 10, hy - 6, 6, 7)); }
        g.setColor(new Color(0x2B2118));
        if (k != Kind.ELEPHANT) g.fill(new Ellipse2D.Double(hx + 28, hy + 3, 7, 6));
    }

    private static void bird(Graphics2D g, double flap) {
        double f = Math.sin(flap);
        fillShaded(g, new Ellipse2D.Double(-26, -14, 52, 28), new Color(0x3C6EA8));
        fillShaded(g, new Ellipse2D.Double(14, -22, 24, 24), new Color(0x2B5382));
        g.setColor(new Color(0xF2B233));
        Path2D beak = new Path2D.Double();
        beak.moveTo(36, -14); beak.lineTo(52, -9); beak.lineTo(36, -5); beak.closePath();
        g.fill(beak);
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(26, -18, 7, 7));
        g.setColor(new Color(0x2B2118));
        g.fill(new Ellipse2D.Double(29, -16, 4, 4));
        Path2D wing = new Path2D.Double();
        wing.moveTo(-14, -4);
        wing.curveTo(-24, -44 * f - 10, 10, -56 * f - 14, 22, -4);
        wing.closePath();
        fillShaded(g, wing, new Color(0x5C93D1));
        g.setColor(new Color(0x2B5382));
        Path2D tail = new Path2D.Double();
        tail.moveTo(-24, -4); tail.lineTo(-48, -14); tail.lineTo(-48, 8); tail.closePath();
        g.fill(tail);
    }

    private static void fish(Graphics2D g, double t, double seed) {
        Color c = new Color[]{new Color(0xF28C28), new Color(0x3AA0D8), new Color(0xE8546B)}[(int) (seed * 3) % 3];
        double wag = Math.sin(t) * 10;
        Path2D tail = new Path2D.Double();
        tail.moveTo(-40, 0); tail.lineTo(-68, -20 + wag); tail.lineTo(-68, 20 + wag); tail.closePath();
        fillShaded(g, tail, c);
        fillShaded(g, new Ellipse2D.Double(-46, -22, 92, 44), c);
        Path2D fin = new Path2D.Double();
        fin.moveTo(-6, -20); fin.lineTo(8, -40 - wag * 0.4); fin.lineTo(18, -20); fin.closePath();
        fillShaded(g, fin, shade(c, 0.8f));
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(20, -10, 13, 13));
        g.setColor(new Color(0x2B2118));
        g.fill(new Ellipse2D.Double(25, -6, 6, 6));
    }

    // ---- drawing helpers -----------------------------------------------------------------------------------------

    private static void shadow(Graphics2D g, double x, double y, double w, double s) {
        g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), (float) (w * s), new Point2D.Double(x, y), new float[]{0f, 1f},
                new Color[]{new Color(0, 0, 0, 90), new Color(0, 0, 0, 0)}, java.awt.MultipleGradientPaint.CycleMethod.NO_CYCLE,
                java.awt.MultipleGradientPaint.ColorSpaceType.SRGB, new java.awt.geom.AffineTransform(1, 0, 0, 0.22, 0, y * 0.78)));
        g.fill(new Ellipse2D.Double(x - w * s, y - 14 * s, w * 2 * s, 28 * s));
    }

    /** Tapered limb (rounded ends) with cylinder-style shading and a darker outline. */
    private static void tube(Graphics2D g, double x1, double y1, double x2, double y2, double w1, double w2, Color c) {
        double dx = x2 - x1, dy = y2 - y1, len = Math.hypot(dx, dy);
        if (len < 1e-6) return;
        double nx = -dy / len, ny = dx / len;
        Path2D p = new Path2D.Double();
        p.moveTo(x1 + nx * w1 / 2, y1 + ny * w1 / 2);
        p.lineTo(x2 + nx * w2 / 2, y2 + ny * w2 / 2);
        p.lineTo(x2 - nx * w2 / 2, y2 - ny * w2 / 2);
        p.lineTo(x1 - nx * w1 / 2, y1 - ny * w1 / 2);
        p.closePath();
        Area a = new Area(p);
        a.add(new Area(new Ellipse2D.Double(x1 - w1 / 2, y1 - w1 / 2, w1, w1)));
        a.add(new Area(new Ellipse2D.Double(x2 - w2 / 2, y2 - w2 / 2, w2, w2)));
        double mx = (x1 + x2) / 2, my = (y1 + y2) / 2, wm = (w1 + w2) / 2;
        g.setPaint(new GradientPaint((float) (mx - nx * wm / 2), (float) (my - ny * wm / 2), lighten(c, 0.12f),
                (float) (mx + nx * wm / 2), (float) (my + ny * wm / 2), shade(c, 0.78f)));
        g.fill(a);
        g.setColor(shade(c, 0.55f));
        g.draw(a);
    }

    private static void fillShaded(Graphics2D g, Shape s, Color c) {
        Rectangle2D b = s.getBounds2D();
        g.setPaint(new GradientPaint((float) b.getMinX(), (float) b.getMinY(), lighten(c, 0.14f),
                (float) b.getMaxX(), (float) b.getMaxY(), shade(c, 0.76f)));
        g.fill(s);
        g.setColor(shade(c, 0.5f));
        g.draw(s);
    }

    private static Color shade(Color c, float f) {
        return new Color(clamp((int) (c.getRed() * f)), clamp((int) (c.getGreen() * f)), clamp((int) (c.getBlue() * f)), c.getAlpha());
    }

    private static Color lighten(Color c, float f) {
        return new Color(clamp((int) (c.getRed() + (255 - c.getRed()) * f)), clamp((int) (c.getGreen() + (255 - c.getGreen()) * f)),
                clamp((int) (c.getBlue() + (255 - c.getBlue()) * f)), c.getAlpha());
    }

    private static Color blend(Color a, Color b, float t) {
        return new Color(clamp((int) (a.getRed() * (1 - t) + b.getRed() * t)), clamp((int) (a.getGreen() * (1 - t) + b.getGreen() * t)),
                clamp((int) (a.getBlue() * (1 - t) + b.getBlue() * t)));
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }

    /** Tiny polygon builder for the vest panels. */
    private record Polygon2(double[] xs, double[] ys) {
        Path2D path() {
            Path2D p = new Path2D.Double();
            p.moveTo(xs[0], ys[0]);
            for (int i = 1; i < xs.length; i++) p.lineTo(xs[i], ys[i]);
            p.closePath();
            return p;
        }
    }
}
