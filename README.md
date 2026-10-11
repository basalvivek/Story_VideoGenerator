# AI Story to Video Generator

Spring Boot 3 / Java 17 app. Upload a PDF/DOC/DOCX story, pick length/style/voice, get a narrated, captioned MP4.
All uploads, scene thumbnails and generated videos are stored in PostgreSQL (`moviedb`).

## Run
```
docker compose up -d          # PostgreSQL "moviedb" on localhost:5434
mvn -DskipTests package
java -jar target/story-to-video-1.0.0.jar    # http://localhost:1011
```
Requirements on the host: Java 17, `ffmpeg`/`ffprobe` on PATH (or FFMPEG_PATH/FFPROBE_PATH), Windows SAPI voices for narration
(without them narration is skipped, video still renders). DB settings: `MOVIE_DB_HOST/PORT/NAME/USER/PASSWORD`.

API docs: http://localhost:1011/swagger-ui.html · health: /actuator/health · share links: /watch/{token}

## Pipeline
text extraction (PDFBox/POI) -> scene planning (extractive) -> procedural artwork per style -> SAPI narration ->
ffmpeg (Ken Burns, burned-in captions, synthesized music) -> stored in Postgres.
Artwork/narration are local, offline engines; swap `ImageRenderer` / `TtsService` for cloud AI providers if wanted.
