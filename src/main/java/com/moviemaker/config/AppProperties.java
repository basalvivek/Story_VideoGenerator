package com.moviemaker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(String ffmpeg, String ffprobe, String workDir, long maxUploadBytes, Generation generation, Gemini gemini) {
    public record Generation(int threads) {}

    /** Optional Google Gemini access for realistic AI scene images; blank apiKey disables it. */
    public record Gemini(String apiKey, String textModel, String imageModel) {
        public boolean enabled() { return apiKey != null && !apiKey.isBlank(); }
    }

    public boolean aiImages() { return gemini != null && gemini.enabled(); }
}
