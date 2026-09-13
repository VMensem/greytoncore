package me.mensem.minecraftlauncher;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class HttpClientWrapper {

    private final HttpClient httpClient;

    private static final List<String> MANIFEST_URLS = List.of(
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
            "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json"
    );

    public HttpClientWrapper() {
        System.setProperty("java.net.preferIPv4Stack", "true");
        System.setProperty("java.net.useSystemProxies", "true");

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(45))
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public static class HttpResult {
        public final int statusCode;
        public final String body;
        public final JsonObject json;

        public HttpResult(int statusCode, String body, JsonObject json) {
            this.statusCode = statusCode;
            this.body = body;
            this.json = json;
        }
    }

    public HttpResult postForm(String url, Map<String, String> form) throws Exception {
        String body = buildFormBody(form);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        return toHttpResult(response.statusCode(), response.body());
    }

    public HttpResult postJson(String url, JsonObject payload) throws Exception {
        return postJson(url, payload, Map.of());
    }

    public HttpResult postJson(String url, JsonObject payload, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");

        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                    builder.header(key, value);
                }
            }
        }

        HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofString(
                        payload == null ? "{}" : payload.toString(),
                        StandardCharsets.UTF_8
                ))
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        return toHttpResult(response.statusCode(), response.body());
    }

    public HttpResult getJson(String url, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET();

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }

        HttpResponse<String> response = httpClient.send(
                builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        return toHttpResult(response.statusCode(), response.body());
    }

    public void downloadFile(String urlStr, File destination, String description) throws Exception {
        int attempts = 5;
        Exception lastException = null;

        long totalStart = System.currentTimeMillis();

        for (int i = 1; i <= attempts; i++) {
            try {
                destination.getParentFile().mkdirs();

                System.out.println("DEBUG: Downloading " + description + " (attempt " + i + ") -> " + urlStr);

                long startTime = System.currentTimeMillis();

                try {
                    downloadDirect(urlStr, destination);

                    long endTime = System.currentTimeMillis();
                    long seconds = (endTime - startTime) / 1000;

                    System.out.println("DEBUG: SUCCESS " + description + " via main server in " + seconds + " sec");
                    return;

                } catch (Exception firstError) {
                    if (urlStr.contains("resources.download.minecraft.net")) {
                        String mirrorUrl = urlStr.replace(
                                "resources.download.minecraft.net",
                                "bmclapi2.bangbang93.com/assets"
                        );

                        System.out.println("DEBUG: Fallback to mirror -> " + mirrorUrl);

                        downloadDirect(mirrorUrl, destination);

                        long endTime = System.currentTimeMillis();
                        long seconds = (endTime - startTime) / 1000;

                        System.out.println("DEBUG: SUCCESS " + description + " via mirror in " + seconds + " sec");
                        return;
                    }

                    throw firstError;
                }

            } catch (Exception e) {
                lastException = e;
                System.out.println("DEBUG: ERROR: " + e.getMessage());

                if (i < attempts) {
                    long delay = 2000L * i;
                    System.out.println("DEBUG: Waiting " + delay / 1000 + " sec before retry...");
                    Thread.sleep(delay);
                }
            }
        }

        long totalEnd = System.currentTimeMillis();
        long totalSeconds = (totalEnd - totalStart) / 1000;

        throw new IOException(
                "Failed to download " + description + " in " + totalSeconds + " sec after " + attempts + " attempts",
                lastException
        );
    }

    private String getVersion() {
        try (InputStream in = getClass().getResourceAsStream("/version.txt")) {
            if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {}
        return "1.0.1";
    }

    private void downloadDirect(String url, File destination) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("User-Agent", "Pura-Launcher/" + getVersion())
                .header("Accept", "*/*")
                .GET()
                .build();

        HttpResponse<InputStream> response;

        try {
            response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream()
            );
        } catch (Exception e) {
            if (url.contains("libraries.minecraft.net")) {
                String mirrorUrl = url.replace(
                        "https://libraries.minecraft.net",
                        "https://bmclapi2.bangbang93.com/maven"
                );

                System.out.println("DEBUG: Library fallback -> " + mirrorUrl);

                request = HttpRequest.newBuilder()
                        .uri(URI.create(mirrorUrl))
                        .timeout(Duration.ofSeconds(120))
                        .header("User-Agent", "Pura-Launcher/" + getVersion())
                        .header("Accept", "*/*")
                        .GET()
                        .build();

                response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofInputStream()
                );
            } else {
                throw e;
            }
        }

        if (response.statusCode() != 200) {
            if (url.contains("libraries.minecraft.net")) {
                String mirrorUrl = url.replace(
                        "https://libraries.minecraft.net",
                        "https://bmclapi2.bangbang93.com/maven"
                );

                System.out.println("DEBUG: HTTP fallback -> " + mirrorUrl);

                request = HttpRequest.newBuilder()
                        .uri(URI.create(mirrorUrl))
                        .timeout(Duration.ofSeconds(120))
                        .header("User-Agent", "Pura-Launcher/" + getVersion())
                        .header("Accept", "*/*")
                        .GET()
                        .build();

                response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofInputStream()
                );

                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode());
                }
            } else {
                throw new IOException("HTTP " + response.statusCode());
            }
        }

        long totalSize = response.headers()
                .firstValue("Content-Length")
                .map(Long::parseLong)
                .orElse(-1L);

        try (InputStream input = response.body()) {
            byte[] buffer = new byte[65536];
            long downloaded = 0;
            long startTime = System.currentTimeMillis();
            long lastPrint = 0;

            final long[] downloadedRef = {downloaded};
            final long[] lastPrintRef = {lastPrint};

            LauncherUtils.writeBytesAtomic(destination, output -> {
                int bytesRead;

                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                    downloadedRef[0] += bytesRead;

                    long now = System.currentTimeMillis();

                    if (now - lastPrintRef[0] >= 1000) {
                        lastPrintRef[0] = now;

                        long elapsed = (now - startTime) / 1000;
                        if (elapsed == 0) {
                            elapsed = 1;
                        }

                        double speed = (double) downloadedRef[0] / elapsed;

                        if (totalSize > 0) {
                            long remainingBytes = totalSize - downloadedRef[0];
                            long etaSeconds = (long) (remainingBytes / speed);

                            int percent = (int) ((downloadedRef[0] * 100) / totalSize);

                            System.out.println(
                                    "DEBUG: " +
                                            percent + "% | " +
                                            formatSize(downloadedRef[0]) + " / " +
                                            formatSize(totalSize) +
                                            " | ETA: " + formatTime(etaSeconds)
                            );
                        } else {
                            System.out.println(
                                    "DEBUG: Downloaded " + formatSize(downloadedRef[0])
                            );
                        }
                    }
                }
            });
        }
    }

    public String downloadString(String url) throws Exception {
        String fileName = null;

        if (url.contains("version_manifest_v2.json")) {
            fileName = "/version_manifest_v2.json";
        } else if (url.contains("/26.2.json")) {
            fileName = "/26.2.json";
        }

        if (fileName != null) {
            try (InputStream in = getClass().getResourceAsStream(fileName)) {
                if (in != null) {
                    System.out.println("DEBUG: Loading from resources -> " + fileName);

                    return new String(
                            in.readAllBytes(),
                            StandardCharsets.UTF_8
                    );
                }

                System.out.println("DEBUG: Resource not found -> " + fileName);
            }
        }

        List<String> urls = List.of(
                url,
                url.replace("piston-meta.mojang.com", "bmclapi2.bangbang93.com"),
                url.replace("piston-meta.mojang.com", "download.mcbbs.net")
        );

        Exception lastException = null;

        for (String currentUrl : urls) {
            for (int i = 1; i <= 2; i++) {
                try {
                    System.out.println("DEBUG: Download string attempt " + i + " -> " + currentUrl);

                    String result = downloadStringDirect(currentUrl);

                    System.out.println("DEBUG: SUCCESS -> " + currentUrl);
                    return result;

                } catch (Exception e) {
                    lastException = e;
                    System.out.println("DEBUG: ERROR -> " + e.getMessage());
                    Thread.sleep(1000);
                }
            }
        }

        throw new IOException(
                "Не удалось скачать строку: " + url,
                lastException
        );
    }

    private String downloadStringDirect(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("User-Agent", "Pura-Launcher/" + getVersion())
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }

        return response.body();
    }

    private String formatSize(long bytes) {
        double mb = bytes / 1024.0 / 1024.0;
        return String.format("%.2f MB", mb);
    }

    private String formatTime(long seconds) {
        long minutes = seconds / 60;
        long secs = seconds % 60;

        if (minutes > 0) {
            return minutes + "m " + secs + "s";
        }

        return secs + "s";
    }

    private String buildFormBody(Map<String, String> form) {
        StringBuilder builder = new StringBuilder();

        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (builder.length() > 0) {
                builder.append('&');
            }

            builder.append(LauncherUtils.url(entry.getKey()));
            builder.append('=');
            builder.append(LauncherUtils.url(entry.getValue()));
        }

        return builder.toString();
    }

    private HttpResult toHttpResult(int statusCode, String body) {
        JsonObject json = null;

        try {
            JsonElement parsed = JsonParser.parseString(body);

            if (parsed.isJsonObject()) {
                json = parsed.getAsJsonObject();
            }
        } catch (Exception ignored) {
        }

        return new HttpResult(statusCode, body, json);
    }
}
