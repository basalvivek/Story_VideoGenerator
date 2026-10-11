package com.moviemaker.pipeline;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

/** Extracts plain text from PDF, DOC, DOCX and TXT. Format is detected from magic bytes, not the file extension. */
@Component
public class TextExtractor {

    public enum Format {
        PDF("application/pdf"),
        DOC("application/msword"),
        DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        TXT("text/plain");

        public final String contentType;

        Format(String contentType) { this.contentType = contentType; }
    }

    public static Format detect(byte[] d, String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (d.length > 4 && d[0] == '%' && d[1] == 'P' && d[2] == 'D' && d[3] == 'F') return Format.PDF;
        if (d.length > 4 && d[0] == 'P' && d[1] == 'K' && name.endsWith(".docx")) return Format.DOCX;
        if (d.length > 8 && (d[0] & 0xFF) == 0xD0 && (d[1] & 0xFF) == 0xCF && (d[2] & 0xFF) == 0x11
                && (d[3] & 0xFF) == 0xE0 && name.endsWith(".doc")) return Format.DOC;
        if (d.length > 0 && name.endsWith(".txt") && !hasNul(d)) return Format.TXT;
        return null;
    }

    private static boolean hasNul(byte[] d) {
        int n = Math.min(d.length, 8192);
        for (int i = 0; i < n; i++) if (d[i] == 0) return true;
        return false;
    }

    public String extract(byte[] data, Format format) throws IOException {
        return switch (format) {
            case PDF -> {
                try (PDDocument doc = Loader.loadPDF(data)) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    stripper.setSortByPosition(true);
                    yield stripper.getText(doc);
                }
            }
            case DOCX -> {
                try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data));
                     XWPFWordExtractor ex = new XWPFWordExtractor(doc)) {
                    yield ex.getText();
                }
            }
            case TXT -> {
                String s = new String(data, StandardCharsets.UTF_8);
                yield s.startsWith("﻿") ? s.substring(1) : s;
            }
            case DOC -> {
                try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(data));
                     WordExtractor ex = new WordExtractor(doc)) {
                    yield ex.getText();
                }
            }
        };
    }
}
