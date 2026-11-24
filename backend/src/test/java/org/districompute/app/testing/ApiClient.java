package org.districompute.app.testing;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An HTTP client that behaves like the browser application: it keeps the session cookie, reads the
 * {@code XSRF-TOKEN} cookie and sends it as {@code X-XSRF-TOKEN} on every state-changing request.
 */
public final class ApiClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String base;
    private final CookieManager cookies = new CookieManager();
    private final HttpClient http;

    /**
     * Creates a client for a server.
     *
     * @param port the server's port
     */
    public ApiClient(int port) {
        this.base = "http://127.0.0.1:" + port;
        this.http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
    }

    /**
     * A response.
     *
     * @param status HTTP status
     * @param body body text
     * @param contentType content type header
     * @param disposition content disposition header
     */
    public record Response(int status, String body, String contentType, String disposition) {

        /**
         * The body as JSON.
         *
         * @return the tree
         */
        public JsonNode json() {
            return JSON.readTree(body);
        }
    }

    /**
     * Signs in with the form login, as the SPA does.
     *
     * @param username user
     * @param password password
     * @return the response
     */
    public Response login(String username, String password) {
        get("/api/v1/auth/session");
        String form = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8) + "&password="
                + URLEncoder.encode(password, StandardCharsets.UTF_8);
        return send(request("/api/v1/auth/login").header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)));
    }

    /**
     * GET.
     *
     * @param path path
     * @return the response
     */
    public Response get(String path) {
        return send(request(path).GET());
    }

    /**
     * GET of a binary download.
     *
     * @param path path
     * @return the body bytes (empty for a status other than 200)
     */
    public byte[] bytes(String path) {
        try {
            HttpResponse<byte[]> response = http.send(request(path).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            return response.statusCode() == 200 ? response.body() : new byte[0];
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * POST with a JSON body.
     *
     * @param path path
     * @param body body (serialized with Jackson), or {@code null}
     * @return the response
     */
    public Response post(String path, Object body) {
        return send(request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : JSON.writeValueAsString(body))));
    }

    /**
     * PATCH with a JSON body.
     *
     * @param path path
     * @param body body
     * @return the response
     */
    public Response patch(String path, Object body) {
        return send(request(path).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
    }

    /**
     * DELETE.
     *
     * @param path path
     * @return the response
     */
    public Response delete(String path) {
        return send(request(path).DELETE());
    }

    /**
     * POST without the CSRF header (to test CSRF protection).
     *
     * @param path path
     * @param body body
     * @return the response
     */
    public Response postWithoutCsrf(String path, Object body) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
    }

    /**
     * Uploads a file as multipart form data.
     *
     * @param path path (with query parameters)
     * @param fileName file name
     * @param contentType file content type
     * @param content file content
     * @return the response
     */
    public Response upload(String path, String fileName, String contentType, String content) {
        String boundary = "----districompute" + UUID.randomUUID();
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
                + "\"\r\nContent-Type: " + contentType + "\r\n\r\n" + content + "\r\n--" + boundary + "--\r\n";
        return send(request(path).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    /**
     * Opens a Server-Sent-Events stream.
     *
     * @param path path
     * @return the open stream
     */
    public InputStream stream(String path) {
        try {
            HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(URI.create(base + path))
                    .header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Stream " + path + " answered " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60));
        String token = cookies.getCookieStore().getCookies().stream().filter(c -> "XSRF-TOKEN".equals(c.getName()))
                .map(HttpCookie::getValue).findFirst().orElse(null);
        if (token != null) {
            builder.header("X-XSRF-TOKEN", token);
        }
        return builder;
    }

    private Response send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(),
                    response.headers().firstValue("Content-Type").orElse(""),
                    response.headers().firstValue("Content-Disposition").orElse(""));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
