package com.moviemaker.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Dtos {
    private Dtos() {}

    public record SceneDto(UUID id, int index, String title, String narration, double startSeconds,
                           double durationSeconds, String thumbnailUrl) {}

    public record ProjectDto(UUID id, String title, String status, int progress, String message, String error,
                             int lengthMinutes, String style, boolean narration, boolean music, boolean subtitles,
                             String voiceLanguage, String voiceType, String sourceFilename, long sourceSize,
                             Double durationSeconds, String videoUrl, String downloadUrl, String shareToken,
                             Instant createdAt, List<SceneDto> scenes) {}

    public record LanguageDto(String code, String label) {}

    public record VoiceOptionsDto(List<LanguageDto> languages, boolean ttsAvailable) {}
}
