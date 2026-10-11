package com.moviemaker.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.moviemaker.domain.VideoStyle;
import com.moviemaker.pipeline.StoryPlanner.ScenePlan;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Realistic scene images from Gemini. A "director" call first reads the whole story and writes a character sheet
 * (so a person looks the same in every scene) plus a precise visual prompt per scene (who is present, how many,
 * who is speaking, where everyone stands). Each prompt is then painted by the image model.
 */
@Component
public class AiSceneArtist {
    private static final Logger log = LoggerFactory.getLogger(AiSceneArtist.class);

    private final GeminiClient gemini;

    public AiSceneArtist(GeminiClient gemini) {
        this.gemini = gemini;
    }

    public boolean enabled() { return gemini.enabled(); }

    /** Story-wide visual direction: character sheet and one image prompt per scene index. */
    public record Direction(String characterSheet, Map<Integer, String> scenePrompts) {}

    public Direction direct(List<ScenePlan> scenes, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are the director and cinematographer for a short film adapted from a story. ")
          .append("Read every scene below (the narration may be in any language; write your answer in English).\n\n")
          .append("Return JSON: {\"characters\":[{\"name\":string,\"look\":string}],\"scenes\":[{\"index\":number,\"prompt\":string}]}.\n")
          .append("- \"characters\": every recurring person or animal. \"look\" is a fixed visual description (age, build, face, hair, clothing, ")
          .append("skin tone, culture/era implied by the story) so they look identical in every scene.\n")
          .append("- \"scenes\": one entry per scene index. \"prompt\" describes ONE frozen moment of that scene for an image model: ")
          .append("exactly who is present and how many (e.g. a father and his four sons), who is speaking and who is listening, their ")
          .append("positions, body language, facial expressions, the setting, time of day and lighting. Use the character looks. ")
          .append("Be faithful to the story; do not invent plot. Never put any written text, captions or logos in the image.\n\n")
          .append("Story title: ").append(title).append("\n\nScenes:\n");
        for (ScenePlan s : scenes) {
            String n = s.narration().length() > 700 ? s.narration().substring(0, 700) : s.narration();
            sb.append("[").append(s.index()).append("] ").append(s.title()).append(": ").append(n).append("\n");
        }
        try {
            JsonNode root = gemini.generateJson(sb.toString());
            StringBuilder sheet = new StringBuilder();
            for (JsonNode c : root.path("characters")) {
                sheet.append("- ").append(c.path("name").asText()).append(": ").append(c.path("look").asText()).append("\n");
            }
            Map<Integer, String> prompts = new HashMap<>();
            for (JsonNode s : root.path("scenes")) {
                if (s.has("index") && s.has("prompt")) prompts.put(s.get("index").asInt(), s.get("prompt").asText());
            }
            return new Direction(sheet.toString(), prompts);
        } catch (IOException | RuntimeException e) {
            log.warn("AI scene direction failed, using narration as prompt: {}", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new Direction("", Map.of());
    }

    /** @return the painted frame (1920x1080), or null when the model declined or failed so the caller can fall back. */
    public BufferedImage paint(Direction d, ScenePlan scene, VideoStyle style) {
        String action = d.scenePrompts().getOrDefault(scene.index(), scene.narration());
        StringBuilder p = new StringBuilder();
        p.append(styleLine(style)).append(" Widescreen 16:9 frame.\n");
        if (!d.characterSheet().isBlank()) p.append("Characters (keep their appearance exactly consistent):\n").append(d.characterSheet());
        p.append("Scene: ").append(action).append("\n")
         .append("Natural, believable faces and hands, correct anatomy and the exact number of people described. ")
         .append("No text, no captions, no watermark, no logo.");
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(gemini.generateImage(p.toString())));
            if (img == null) throw new IOException("undecodable image");
            return cover(img, ImageRenderer.W, ImageRenderer.H);
        } catch (IOException | RuntimeException e) {
            log.warn("AI image for scene {} failed, using offline artwork: {}", scene.index(), e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private static String styleLine(VideoStyle s) {
        return switch (s) {
            case CINEMATIC -> "Photorealistic cinematic film still, shot on a full-frame cinema camera, natural skin, dramatic lighting, shallow depth of field.";
            case DOCUMENTARY -> "Photorealistic documentary photograph, natural light, muted authentic colours, candid composition.";
            case ANIMATED -> "High-quality 3D animated feature-film style, expressive characters, warm vivid colours.";
            case MODERN -> "Clean modern editorial illustration with realistic proportions, bold colour palette, soft gradients.";
        };
    }

    /** Scale to fill the target size, cropping the overflow from the centre. */
    static BufferedImage cover(BufferedImage src, int w, int h) {
        double k = Math.max(w / (double) src.getWidth(), h / (double) src.getHeight());
        int sw = (int) Math.ceil(src.getWidth() * k), sh = (int) Math.ceil(src.getHeight() * k);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, (w - sw) / 2, (h - sh) / 2, sw, sh, null);
        g.dispose();
        return out;
    }
}
