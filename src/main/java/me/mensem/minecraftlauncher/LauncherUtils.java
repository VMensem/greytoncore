package me.mensem.minecraftlauncher;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class LauncherUtils {

    private LauncherUtils() {}

    public static String randomUrlToken(int size) {
        byte[] bytes = new byte[size];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String buildCodeChallenge(String verifier) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    public static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public static String readableText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        String trimmed = value.trim();
        if (!looksLikeMojibake(trimmed)) {
            return trimmed;
        }

        try {
            String repaired = new String(trimmed.getBytes(java.nio.charset.Charset.forName("windows-1251")), StandardCharsets.UTF_8);
            return repaired.isBlank() ? trimmed : repaired;
        } catch (Exception ignored) {
            return trimmed;
        }
    }

    private static boolean looksLikeMojibake(String value) {
        return value.contains("Р") || value.contains("С") || value.contains("Ð") || value.contains("Ñ");
    }

    public static Map<String, String> parseQuery(String query) {
        Map<String, String> values = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return values;
        }
        for (String part : query.split("&")) {
            int separator = part.indexOf('=');
            String key = separator >= 0 ? part.substring(0, separator) : part;
            String value = separator >= 0 ? part.substring(separator + 1) : "";
            values.put(
                    java.net.URLDecoder.decode(key, StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(value, StandardCharsets.UTF_8)
            );
        }
        return values;
    }

    public static String shortenUuid(String uuid) {
        return uuid.length() > 8 ? uuid.substring(0, 8) + "..." : uuid;
    }

    public static String generateOfflineUUID(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static int parseRam(String ramValue) {
        try {
            return Integer.parseInt(ramValue.replaceAll("[^0-9]", ""));
        } catch (Exception ignored) {
            return 8;
        }
    }

    public static int getTotalSystemRamGb() {
        try {
            java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
                long totalBytes = sunOsBean.getTotalMemorySize();
                if (totalBytes > 0) {
                    long oneGb = 1024L * 1024L * 1024L;
                    return Math.max(1, (int) ((totalBytes + oneGb - 1) / oneGb));
                }
            }
        } catch (Exception ignored) {
        }
        return 32;
    }

    public static int clampRamGb(int ramGb) {
        int maxRamGb = getTotalSystemRamGb();
        return Math.max(1, Math.min(ramGb, maxRamGb));
    }

    public static int getDefaultRamGb() {
        return Math.max(1, getTotalSystemRamGb() / 2);
    }

    public static boolean deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirectory(child);
                }
            }
        }
        return dir.delete();
    }

    public static void writeStringAtomic(File destination, String content) throws IOException {
        writeBytesAtomic(destination, out -> out.write(content.getBytes(StandardCharsets.UTF_8)));
    }

    public static void copyResourceAtomic(java.io.InputStream input, File destination) throws IOException {
        writeBytesAtomic(destination, out -> input.transferTo(out));
    }

    public static void writeBytesAtomic(File destination, ConsumerWithIOException<OutputStream> writer) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }

        Path parentPath = parent != null ? parent.toPath() : Path.of(".");
        Path temp = Files.createTempFile(parentPath, destination.getName(), ".tmp");
        boolean moved = false;

        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                writer.accept(out);
            }

            moveReplace(temp, destination.toPath());
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp);
            }
        }
    }

    public static void moveReplace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static boolean hasJavaProcessUsingDirectory(File directory) {
        Path target = canonicalPath(directory);
        return ProcessHandle.allProcesses().anyMatch(process -> isJavaProcessUsingDirectory(process, target));
    }

    public static boolean stopJavaProcessesUsingDirectory(File directory, long timeoutSeconds) {
        Path target = canonicalPath(directory);
        boolean stoppedAny = false;

        for (ProcessHandle process : ProcessHandle.allProcesses().toList()) {
            if (!isJavaProcessUsingDirectory(process, target)) {
                continue;
            }

            stoppedAny = true;
            process.destroy();

            try {
                process.onExit().get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }

        return stoppedAny;
    }

    private static boolean isJavaProcessUsingDirectory(ProcessHandle process, Path target) {
        ProcessHandle.Info info = process.info();
        String command = info.command().orElse("").toLowerCase();

        if (!command.endsWith("java.exe") && !command.endsWith("javaw.exe") && !command.equals("java")) {
            return false;
        }

        return pathStartsWith(info.command(), target)
                || info.commandLine()
                .map(line -> line.toLowerCase().contains(target.toString().toLowerCase()))
                .orElse(false);
    }

    private static boolean pathStartsWith(Optional<String> value, Path target) {
        if (value.isEmpty()) {
            return false;
        }

        try {
            return canonicalPath(Path.of(value.get())).startsWith(target);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Path canonicalPath(File file) {
        try {
            return file.getCanonicalFile().toPath();
        } catch (IOException ignored) {
            return file.getAbsoluteFile().toPath();
        }
    }

    private static Path canonicalPath(Path path) {
        try {
            return path.toFile().getCanonicalFile().toPath();
        } catch (IOException ignored) {
            return path.toAbsolutePath();
        }
    }

    @FunctionalInterface
    public interface ConsumerWithIOException<T> {
        void accept(T value) throws IOException;
    }

    public static String successPage(String title, String body) {
        return """
                <!DOCTYPE html>
                <html lang="ru">
                <head>
                    <meta charset="UTF-8">
                    <title>%s</title>
                    <style>
                        body {
                            font-family: 'Segoe UI', sans-serif;
                            background: linear-gradient(135deg, #121216, #1a1a22);
                            color: white;
                            display: flex;
                            align-items: center;
                            justify-content: center;
                            height: 100vh;
                            margin: 0;
                        }
                        .card {
                            max-width: 520px;
                            padding: 32px;
                            border-radius: 20px;
                            background: rgba(24, 24, 31, 0.94);
                            box-shadow: 0 20px 60px rgba(0, 0, 0, 0.35);
                            border: 1px solid rgba(200, 100, 255, 0.35);
                            text-align: center;
                        }
                        h1 {
                            color: #c864ff;
                            margin-top: 0;
                        }
                        p {
                            color: #d6d6e3;
                            line-height: 1.5;
                        }
                    </style>
                </head>
                <body>
                    <div class="card">
                        <h1>%s</h1>
                        <p>%s</p>
                    </div>
                </body>
                </html>
                """.formatted(title, title, body);
    }

    public static void streamOutput(java.io.InputStream inputStream, String prefix) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(prefix + line);
            }
        } catch (Exception ignored) {
        }
    }

    public static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            } else {
                String os = System.getProperty("os.name").toLowerCase();
                if (os.contains("win")) {
                    Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start", url});
                } else if (os.contains("mac")) {
                    Runtime.getRuntime().exec(new String[]{"open", url});
                } else if (os.contains("nix") || os.contains("nux")) {
                    Runtime.getRuntime().exec(new String[]{"xdg-open", url});
                } else {
                    System.err.println("Не удалось открыть браузер: " + url);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
