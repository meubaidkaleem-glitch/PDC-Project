package org.districompute.app.task;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.districompute.app.api.ApiException;
import org.districompute.app.config.AppProperties;
import org.districompute.app.persistence.AppUser;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

/**
 * Uploaded numeric inputs (CSV or plain text). Files are parsed immediately into numbers, stored under a generated
 * id (never the uploaded name) as a compact binary file plus metadata, and owned by the uploading user.
 */
@Service
public class InputStore {

    private static final Pattern ID = Pattern.compile("[0-9a-f-]{36}");
    private static final Pattern SEPARATORS = Pattern.compile("[,;\\t ]+");
    private static final List<String> ACCEPTED_TYPES = List.of("text/csv", "text/plain", "application/csv",
            "application/vnd.ms-excel", "application/octet-stream");

    private final Path directory;
    private final long maxBytes;
    private final JsonMapper json;
    private final Clock clock;

    InputStore(AppProperties properties, JsonMapper json, Clock clock) {
        this.directory = properties.dataDir().resolve("inputs");
        this.maxBytes = properties.storage().maxUploadBytes();
        this.json = json;
        this.clock = clock;
    }

    /**
     * Metadata of an uploaded input.
     *
     * @param id upload id
     * @param ownerId owner
     * @param algorithm Core algorithm the input was uploaded for
     * @param parameter Core array parameter it fills
     * @param elementType {@code INTEGER} or {@code DECIMAL}
     * @param count number of values
     * @param rows rows in the file
     * @param columns values per row (equal for every row)
     * @param fileName uploaded file name (display only)
     * @param bytes uploaded size
     * @param sha256 SHA-256 of the uploaded bytes
     * @param uploadedAt upload time
     */
    public record InputMeta(String id, Long ownerId, String algorithm, String parameter, String elementType,
            int count, int rows, int columns, String fileName, long bytes, String sha256, Instant uploadedAt) {
    }

    /**
     * Parses and stores an upload.
     *
     * @param file the upload
     * @param owner uploading user
     * @param algorithm Core algorithm id
     * @param parameter Core array parameter
     * @param integral whether the parameter takes integers
     * @param maxElements Core's maximum length for the parameter
     * @return the metadata
     * @throws IOException if storage fails
     */
    public InputMeta store(MultipartFile file, AppUser owner, String algorithm, String parameter, boolean integral,
            int maxElements) throws IOException {
        if (file.isEmpty()) {
            throw ApiException.badRequest("EMPTY_FILE", "The uploaded file is empty");
        }
        if (file.getSize() > maxBytes) {
            throw new ApiException(org.springframework.http.HttpStatus.CONTENT_TOO_LARGE, "UPLOAD_TOO_LARGE",
                    "The file has " + file.getSize() + " bytes; the limit is " + maxBytes, List.of());
        }
        String type = file.getContentType() == null ? "application/octet-stream"
                : file.getContentType().toLowerCase(Locale.ROOT).split(";")[0].trim();
        String name = displayName(file.getOriginalFilename());
        String lower = name.toLowerCase(Locale.ROOT);
        if (!ACCEPTED_TYPES.contains(type) || !(lower.endsWith(".csv") || lower.endsWith(".txt"))) {
            throw ApiException.badRequest("UNSUPPORTED_FILE_TYPE", "Upload a .csv or .txt file of numbers (got "
                    + type + ", " + name + ")");
        }
        byte[] bytes = file.getBytes();
        String text = decodeUtf8(bytes);
        Parsed parsed = parse(text, integral, maxElements);
        String id = UUID.randomUUID().toString();
        InputMeta meta = new InputMeta(id, owner.getId(), algorithm, parameter, integral ? "INTEGER" : "DECIMAL",
                parsed.count, parsed.rows, parsed.columns, name, bytes.length, sha256(bytes), clock.instant());
        Files.createDirectories(directory);
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(directory.resolve(id + ".bin"))))) {
            out.writeInt(parsed.count);
            for (int i = 0; i < parsed.count; i++) {
                if (integral) {
                    out.writeLong(parsed.longs[i]);
                } else {
                    out.writeDouble(parsed.doubles[i]);
                }
            }
        }
        json.writeValue(directory.resolve(id + ".json").toFile(), meta);
        return meta;
    }

    /**
     * Metadata of an upload, checking ownership.
     *
     * @param id upload id
     * @param owner requesting user
     * @param admin whether the user is an administrator
     * @return the metadata
     */
    public InputMeta meta(String id, AppUser owner, boolean admin) {
        if (id == null || !ID.matcher(id).matches()) {
            throw ApiException.notFound("Input", id);
        }
        Path file = directory.resolve(id + ".json");
        if (!Files.exists(file)) {
            throw ApiException.notFound("Input", id);
        }
        InputMeta meta = json.readValue(file.toFile(), InputMeta.class);
        if (!admin && !meta.ownerId().equals(owner.getId())) {
            throw ApiException.notFound("Input", id);
        }
        return meta;
    }

    /**
     * Loads an upload's values as Core expects them (a list of {@code Long} or {@code Double}).
     *
     * @param meta the metadata
     * @return the values
     * @throws IOException if the file cannot be read
     */
    public List<Object> values(InputMeta meta) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(directory.resolve(meta.id() + ".bin"))))) {
            int count = in.readInt();
            List<Object> values = new ArrayList<>(count);
            boolean integral = "INTEGER".equals(meta.elementType());
            for (int i = 0; i < count; i++) {
                values.add(integral ? (Object) in.readLong() : (Object) in.readDouble());
            }
            return values;
        }
    }

    record Parsed(int count, int rows, int columns, long[] longs, double[] doubles) {
    }

    static Parsed parse(String text, boolean integral, int maxElements) {
        String[] lines = text.split("\\r?\\n");
        List<String[]> rows = new ArrayList<>();
        int skippedHeader = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] tokens = SEPARATORS.split(line);
            if (rows.isEmpty() && skippedHeader == 0 && !numeric(tokens[0])) {
                skippedHeader = i + 1;
                continue;
            }
            rows.add(tokens);
        }
        if (rows.isEmpty()) {
            throw ApiException.badRequest("NO_VALUES", "The file contains no numbers");
        }
        int columns = rows.get(0).length;
        long total = 0;
        for (int r = 0; r < rows.size(); r++) {
            if (rows.get(r).length != columns) {
                throw ApiException.badRequest("RAGGED_ROWS", "Row " + (r + 1) + " has " + rows.get(r).length
                        + " values, but the first row has " + columns + "; every row must have the same length");
            }
            total += columns;
        }
        if (total > maxElements) {
            throw ApiException.badRequest("TOO_MANY_VALUES", "The file has " + total + " values; this parameter "
                    + "accepts at most " + maxElements);
        }
        int count = (int) total;
        long[] longs = integral ? new long[count] : null;
        double[] doubles = integral ? null : new double[count];
        int k = 0;
        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < columns; c++) {
                String token = rows.get(r)[c];
                try {
                    if (integral) {
                        longs[k++] = Long.parseLong(token);
                    } else {
                        double value = Double.parseDouble(token);
                        if (!Double.isFinite(value)) {
                            throw new NumberFormatException("not finite");
                        }
                        doubles[k++] = value;
                    }
                } catch (NumberFormatException e) {
                    throw ApiException.badRequest("NOT_A_NUMBER", "Row " + (r + 1) + ", column " + (c + 1) + ": '"
                            + abbreviate(token) + "' is not " + (integral ? "an integer" : "a finite number"));
                }
            }
        }
        return new Parsed(count, rows.size(), columns, longs, doubles);
    }

    private static boolean numeric(String token) {
        try {
            Double.parseDouble(token);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                throw ApiException.badRequest("NOT_TEXT", "The file is not a text file");
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw ApiException.badRequest("NOT_TEXT", "The file is not UTF-8 text");
        }
    }

    private static String displayName(String original) {
        if (original == null || original.isBlank()) {
            return "upload.csv";
        }
        String base = original.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        base = base.replaceAll("[^A-Za-z0-9._ -]", "_");
        return base.length() > 120 ? base.substring(base.length() - 120) : base;
    }

    private static String abbreviate(String token) {
        return token.length() > 30 ? token.substring(0, 27) + "..." : token;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
