package org.districompute.app.report;

import java.util.Locale;
import org.districompute.app.api.ApiException;
import org.springframework.http.MediaType;

/** Report file formats. */
public enum ReportFormat {
    /** Printable document with tables and charts. */
    PDF(MediaType.APPLICATION_PDF, "pdf"),
    /** Table for spreadsheets. */
    CSV(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8), "csv"),
    /** Every value, for programs. */
    JSON(MediaType.APPLICATION_JSON, "json");

    private final MediaType mediaType;
    private final String extension;

    ReportFormat(MediaType mediaType, String extension) {
        this.mediaType = mediaType;
        this.extension = extension;
    }

    /** @return the media type */
    public MediaType mediaType() {
        return mediaType;
    }

    /** @return the file extension */
    public String extension() {
        return extension;
    }

    /**
     * Parses a format name.
     *
     * @param value pdf, csv or json (any case)
     * @return the format
     * @throws ApiException 400 for anything else
     */
    public static ReportFormat parse(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.badRequest("INVALID_FORMAT", "Use format pdf, csv or json");
        }
    }
}
