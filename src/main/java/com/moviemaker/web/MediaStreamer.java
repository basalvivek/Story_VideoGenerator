package com.moviemaker.web;

import com.moviemaker.repo.MediaFileRepository;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Serves files stored in PostgreSQL, with HTTP Range support so the browser can seek inside videos. */
@Component
public class MediaStreamer {
    private static final long MAX_CHUNK = 4L * 1024 * 1024;

    private final MediaFileRepository files;
    private final JdbcTemplate jdbc;

    public MediaStreamer(MediaFileRepository files, JdbcTemplate jdbc) {
        this.files = files;
        this.jdbc = jdbc;
    }

    public ResponseEntity<byte[]> serve(UUID id, String rangeHeader, boolean download, boolean cache) {
        MediaFileRepository.Meta meta = files.findMeta(id).orElseThrow(() -> ApiException.notFound("File not found"));
        long size = meta.getSizeBytes();
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType(meta.getContentType()));
        h.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        h.set("X-Content-Type-Options", "nosniff");
        h.setCacheControl(cache ? "private, max-age=3600" : "no-cache");
        h.setContentDisposition((download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(meta.getFilename(), StandardCharsets.UTF_8).build());

        long start = 0, end = size - 1;
        boolean partial = false;
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            try {
                String[] r = rangeHeader.substring(6).split(",")[0].split("-", -1);
                if (r[0].isEmpty()) {                       // suffix range: last N bytes
                    start = Math.max(0, size - Long.parseLong(r[1]));
                } else {
                    start = Long.parseLong(r[0]);
                    if (!r[1].isEmpty()) end = Math.min(end, Long.parseLong(r[1]));
                }
                partial = true;
            } catch (RuntimeException e) {
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).build();
            }
            if (start > end || start >= size) {
                h.set(HttpHeaders.CONTENT_RANGE, "bytes */" + size);
                return new ResponseEntity<>(h, HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
            }
            end = Math.min(end, start + MAX_CHUNK - 1);
        }
        byte[] body = slice(id, start, end - start + 1);
        if (!partial) {
            h.setContentLength(body.length);
            return new ResponseEntity<>(body, h, HttpStatus.OK);
        }
        h.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size);
        h.setContentLength(body.length);
        return new ResponseEntity<>(body, h, HttpStatus.PARTIAL_CONTENT);
    }

    private byte[] slice(UUID id, long start, long length) {
        if (length <= MAX_CHUNK) {
            return jdbc.queryForObject("select substring(data from ? for ?) from media_file where id = ?", byte[].class,
                    (int) start + 1, (int) length, id);
        }
        // un-ranged request for a large file: stream in a single response (images/thumbnails are small)
        return jdbc.queryForObject("select data from media_file where id = ?", byte[].class, id);
    }
}
