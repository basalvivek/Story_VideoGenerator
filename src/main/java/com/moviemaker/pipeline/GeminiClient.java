package com.moviemaker.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moviemaker.config.AppProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Minimal Google Gemini REST client: JSON text generation and image generation. */
@Component
public class GeminiClient {
    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final AppProperties.Gemini cfg;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public GeminiClient(AppProperties props) {
        this.cfg = props.gemini();
        log.info("AI scene images: {}", enabled() ? "ENABLED (" + cfg.imageModel() + ")" : "disabled (set GEMINI_API_KEY to enable)");
    }

    public boolean enabled() { return cfg != null && cfg.enabled(); }

    /** Text model asked for a JSON document; returns the parsed JSON. */
    public JsonNode generateJson(String prompt) throws IOException, InterruptedException {
        ObjectNode body = json.createObjectNode();
        body.putArray("contents").addObject().putArray("parts").addObject().put("text", prompt);
        body.putObject("generationConfig").put("responseMimeType", "application/json").put("temperature", 0.6);
        JsonNode resp = post(cfg.textModel(), body);
        for (JsonNode part : resp.path("candidates").path(0).path("content").path("parts")) {
            if (part.has("text")) return json.readTree(stripFence(part.get("text").asText()));
        }
        throw new IOException("Gemini returned no text");
    }

    /** @return encoded image bytes (PNG/JPEG) for the prompt, 16:9 when the model supports it. */
    public byte[] generateImage(String prompt) throws IOException, InterruptedException {
        for (boolean withAspect : new boolean[]{true, false}) {
            ObjectNode body = json.createObjectNode();
            body.putArray("contents").addObject().putArray("parts").addObject().put("text", prompt);
            ObjectNode gc = body.putObject("generationConfig");
            ArrayNode mods = gc.putArray("responseModalities");
            mods.add("TEXT");
            mods.add("IMAGE");
            if (withAspect) gc.putObject("imageConfig").put("aspectRatio", "16:9");
            try {
                JsonNode resp = post(cfg.imageModel(), body);
                for (JsonNode part : resp.path("candidates").path(0).path("content").path("parts")) {
                    JsonNode d = part.has("inlineData") ? part.get("inlineData") : part.get("inline_data");
                    if (d != null && d.has("data")) return Base64.getDecoder().decode(d.get("data").asText());
                }
                String why = resp.path("candidates").path(0).path("finishReason").asText(resp.path("promptFeedback").toString());
                throw new IOException("Gemini returned no image (" + why + ")");
            } catch (BadRequest e) {
                if (!withAspect) throw e;   // retry once without the aspect-ratio hint
            }
        }
        throw new IOException("unreachable");
    }

    private static class BadRequest extends IOException {
        BadRequest(String m) { super(m); }
    }

    private JsonNode post(String model, ObjectNode body) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + model + ":generateContent"))
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", cfg.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        long[] backoff = {3000, 8000, 20000};
        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            int sc = r.statusCode();
            if (sc == 200) return json.readTree(r.body());
            if ((sc == 429 || sc >= 500) && attempt < backoff.length) {
                Thread.sleep(backoff[attempt]);
                continue;
            }
            String msg = errorMessage(r.body());
            if (sc == 400) throw new BadRequest("Gemini 400: " + msg);
            throw new IOException("Gemini " + sc + ": " + msg);
        }
    }

    private String errorMessage(String body) {
        try {
            return json.readTree(body).path("error").path("message").asText(body);
        } catch (IOException e) {
            return body;
        }
    }

    private static String stripFence(String s) {
        String t = s.strip();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("```\\s*$", "");
        }
        return t;
    }
}
