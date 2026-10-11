package com.moviemaker.web;

import com.moviemaker.pipeline.VoiceCatalog;
import com.moviemaker.service.ProjectService;
import com.moviemaker.web.Dtos.LanguageDto;
import com.moviemaker.web.Dtos.ProjectDto;
import com.moviemaker.web.Dtos.VoiceOptionsDto;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ProjectController {
    private final ProjectService service;
    private final MediaStreamer media;
    private final VoiceCatalog voices;

    public ProjectController(ProjectService service, MediaStreamer media, VoiceCatalog voices) {
        this.service = service;
        this.media = media;
        this.voices = voices;
    }

    @PostMapping(value = "/projects", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ProjectDto create(@RequestParam("file") MultipartFile file,
                             @RequestParam("title") String title,
                             @RequestParam(value = "lengthMinutes", defaultValue = "5") int lengthMinutes,
                             @RequestParam(value = "style", defaultValue = "CINEMATIC") String style,
                             @RequestParam(value = "narration", defaultValue = "true") boolean narration,
                             @RequestParam(value = "music", defaultValue = "true") boolean music,
                             @RequestParam(value = "subtitles", defaultValue = "true") boolean subtitles,
                             @RequestParam(value = "voiceLanguage", defaultValue = "en-GB") String voiceLanguage,
                             @RequestParam(value = "voiceType", defaultValue = "male") String voiceType) {
        return service.create(new ProjectService.CreateCommand(file, title, lengthMinutes, style, narration, music,
                subtitles, voiceLanguage, voiceType));
    }

    @GetMapping("/projects")
    public List<ProjectDto> list(@RequestParam(defaultValue = "20") int limit) {
        return service.recent(limit);
    }

    @GetMapping("/projects/{id}")
    public ProjectDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @DeleteMapping("/projects/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @GetMapping("/projects/{id}/video")
    public ResponseEntity<byte[]> video(@PathVariable UUID id,
                                        @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
                                        @RequestParam(defaultValue = "false") boolean download) {
        return media.serve(service.videoFileId(id), range, download, true);
    }

    @GetMapping("/projects/{id}/source")
    public ResponseEntity<byte[]> source(@PathVariable UUID id) {
        return media.serve(service.sourceFileId(id), null, true, false);
    }

    @GetMapping("/media/{fileId}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@PathVariable UUID fileId) {
        return media.serve(fileId, null, false, true);
    }

    @GetMapping("/voices")
    public VoiceOptionsDto voices() {
        return new VoiceOptionsDto(voices.languages().stream().map(l -> new LanguageDto(l.code(), l.label())).toList(),
                voices.available());
    }

    // ---- public share links --------------------------------------------------------------------------------------

    @GetMapping("/public/{token}")
    public ProjectDto shared(@PathVariable String token) {
        ProjectDto p = service.getByToken(token);
        return p;
    }

    @GetMapping("/public/{token}/video")
    public ResponseEntity<byte[]> sharedVideo(@PathVariable String token,
                                              @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
                                              @RequestParam(defaultValue = "false") boolean download) {
        return media.serve(service.videoFileIdByToken(token), range, download, true);
    }
}
