package me.mensem.minecraftlauncher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class AssetDownloader {

    private final HttpClientWrapper http;
    private final String gameDir;
    private final ExecutorService executor;

    private final AtomicInteger totalFiles = new AtomicInteger(0);
    private final AtomicInteger downloadedFiles = new AtomicInteger(0);
    private long globalStartTime;

    public AssetDownloader(HttpClientWrapper http, String gameDir) {
        this.http = http;
        this.gameDir = gameDir;
        this.executor = Executors.newFixedThreadPool(24);
    }

    public List<String> downloadLibraries(File jsonFile) throws Exception {
        List<String> libraryPaths = new ArrayList<>();
        List<Callable<Void>> tasks = new ArrayList<>();

        globalStartTime = System.currentTimeMillis();

        String jsonContent = Files.readString(jsonFile.toPath(), StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(jsonContent).getAsJsonObject();

        File librariesDir = new File(gameDir, "libraries");
        librariesDir.mkdirs();

        if (!root.has("libraries")) {
            return libraryPaths;
        }

        JsonArray libraries = root.getAsJsonArray("libraries");
        Set<String> added = new HashSet<>();

        for (JsonElement element : libraries) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject lib = element.getAsJsonObject();

            if (!lib.has("downloads")) {
                continue;
            }

            JsonObject downloads = lib.getAsJsonObject("downloads");

            if (!downloads.has("artifact")) {
                continue;
            }

            JsonObject artifact = downloads.getAsJsonObject("artifact");

            if (!artifact.has("url") || !artifact.has("path")) {
                continue;
            }

            String url = artifact.get("url").getAsString();
            String path = artifact.get("path").getAsString();

            if (added.contains(path)) {
                continue;
            }

            added.add(path);

            File libFile = new File(librariesDir, path);
            libFile.getParentFile().mkdirs();

            libraryPaths.add(libFile.getAbsolutePath());

            if (!libFile.exists()) {
                tasks.add(() -> {
                    http.downloadFile(url, libFile, "library");
                    printGlobalProgress();
                    return null;
                });
            }
        }

        totalFiles.set(tasks.size());
        downloadedFiles.set(0);

        runTasks(tasks);
        return libraryPaths;
    }

    public void downloadAssets(File indexFile, File assetsDir) throws Exception {
        if (!indexFile.exists()) {
            return;
        }

        List<Callable<Void>> tasks = new ArrayList<>();
        globalStartTime = System.currentTimeMillis();

        File objectsDir = new File(assetsDir, "objects");
        objectsDir.mkdirs();

        String indexContent = Files.readString(indexFile.toPath(), StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(indexContent).getAsJsonObject();

        if (!root.has("objects")) {
            return;
        }

        JsonObject objects = root.getAsJsonObject("objects");

        for (String key : objects.keySet()) {
            JsonObject asset = objects.getAsJsonObject(key);

            if (!asset.has("hash")) {
                continue;
            }

            String hash = asset.get("hash").getAsString();
            String subDir = hash.substring(0, 2);

            File assetFile = new File(objectsDir, subDir + "/" + hash);

            if (!assetFile.exists()) {
                String assetUrl = "https://resources.download.minecraft.net/" + subDir + "/" + hash;

                assetFile.getParentFile().mkdirs();

                tasks.add(() -> {
                    http.downloadFile(assetUrl, assetFile, "asset");
                    printGlobalProgress();
                    return null;
                });
            }
        }

        totalFiles.set(tasks.size());
        downloadedFiles.set(0);

        runTasks(tasks);
    }

    private void runTasks(List<Callable<Void>> tasks) throws Exception {
        if (tasks.isEmpty()) {
            return;
        }

        List<Future<Void>> futures = executor.invokeAll(tasks);

        for (Future<Void> future : futures) {
            future.get();
        }
    }

    private void printGlobalProgress() {
        int done = downloadedFiles.incrementAndGet();
        int total = totalFiles.get();

        long elapsed = (System.currentTimeMillis() - globalStartTime) / 1000;
        if (elapsed == 0) {
            elapsed = 1;
        }

        double speed = (double) done / elapsed;
        long remaining = total - done;
        long eta = speed > 0 ? (long) (remaining / speed) : 0;

        System.out.println(
                "Загрузка: " + done + "/" + total +
                        " | Осталось примерно: " + formatTime(eta)
        );
    }

    private String formatTime(long seconds) {
        long minutes = seconds / 60;
        long secs = seconds % 60;

        if (minutes > 0) {
            return minutes + "м " + secs + "с";
        }

        return secs + "с";
    }

    public void extractNatives(List<String> libraryPaths, File nativesDir) throws Exception {
        nativesDir.mkdirs();

        List<Callable<Void>> tasks = new ArrayList<>();
        Set<String> extractedNames = ConcurrentHashMap.newKeySet();

        for (String path : libraryPaths) {
            File lib = new File(path);

            if (lib.getName().contains("natives")) {
                tasks.add(() -> {
                    extractNativesFromJar(lib, nativesDir, extractedNames);
                    return null;
                });
            }
        }

        runTasks(tasks);
    }

    private void extractNativesFromJar(File jarFile, File nativesDir, Set<String> extractedNames) throws Exception {
        if (!jarFile.exists()) {
            return;
        }

        try (
                ZipInputStream zis = new ZipInputStream(
                        new BufferedInputStream(
                                new FileInputStream(jarFile),
                                65536
                        )
                )
        ) {
            ZipEntry entry;

            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory() && (
                        entry.getName().endsWith(".dll") ||
                                entry.getName().endsWith(".so") ||
                                entry.getName().endsWith(".dylib")
                )) {
                    String nativeName = new File(entry.getName()).getName();
                    if (!extractedNames.add(nativeName)) {
                        zis.closeEntry();
                        continue;
                    }

                    File outFile = new File(
                            nativesDir,
                            nativeName
                    );

                    if (!outFile.exists()) {
                        byte[] buffer = new byte[65536];
                        LauncherUtils.writeBytesAtomic(outFile, output -> {
                            int len;

                            while ((len = zis.read(buffer)) > 0) {
                                output.write(buffer, 0, len);
                            }
                        });
                    }
                }

                zis.closeEntry();
            }
        }
    }

    public void extractModpack(String version, String gameDir, ProgressCallback progressCallback) throws Exception {
        String packId = LauncherConstants.MODPACK_IDS.get(version);

        if (packId == null) {
            return;
        }

        File mcDir = new File(gameDir);
        File markerDir = new File(mcDir, ".pura/modpacks/" + safeFileName(version));
        markerDir.mkdirs();

        JsonObject pack = getModpackManifest(version);
        String remoteVersion = pack.has("version") ? pack.get("version").getAsString() : "";
        String baseUrl = pack.has("baseUrl") ? pack.get("baseUrl").getAsString() : "";
        JsonArray files = pack.has("files") && pack.get("files").isJsonArray()
                ? pack.getAsJsonArray("files")
                : new JsonArray();
        File versionFile = new File(markerDir, "version.txt");
        File installedManifestFile = new File(markerDir, "installed-manifest.json");
        File legacyVersionFile = new File(markerDir.getParentFile(), safeFileName(version) + ".version");

        if (remoteVersion.isBlank()) {
            throw new IOException("Modpack version is empty in manifest: " + version);
        }
        if (baseUrl.isBlank()) {
            throw new IOException("Modpack baseUrl is empty in manifest: " + version);
        }

        Set<String> newPaths = new HashSet<>();
        Set<String> oldPaths = readInstalledManifestPaths(installedManifestFile);
        List<JsonObject> filesToDownload = new ArrayList<>();

        for (JsonElement element : files) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject fileEntry = element.getAsJsonObject();
            if (!fileEntry.has("path") || !fileEntry.has("sha256")) {
                continue;
            }

            String relativePath = normalizeManifestPath(fileEntry.get("path").getAsString());
            if (relativePath == null || isUserOwnedPath(relativePath)) {
                continue;
            }

            newPaths.add(relativePath);
            File target = resolveManifestFile(mcDir, relativePath);
            if (isPreservedRuntimeFile(mcDir, target)) {
                continue;
            }

            String expectedSha256 = fileEntry.get("sha256").getAsString();
            long expectedSize = fileEntry.has("size") ? fileEntry.get("size").getAsLong() : -1L;
            if (!isFileCurrent(target, expectedSha256, expectedSize)) {
                JsonObject normalizedEntry = fileEntry.deepCopy();
                normalizedEntry.addProperty("path", relativePath);
                filesToDownload.add(normalizedEntry);
            }
        }

        Set<String> stalePaths = new HashSet<>(oldPaths);
        stalePaths.removeAll(newPaths);
        stalePaths.addAll(readOtherInstalledModpackPaths(markerDir.getParentFile(), version, newPaths));
        stalePaths.removeIf(this::isProtectedDeletionPath);
        boolean needsLegacyCleanup = !installedManifestFile.exists() && legacyVersionFile.exists();

        if (filesToDownload.isEmpty() && stalePaths.isEmpty() && versionFile.exists()
                && remoteVersion.equals(Files.readString(versionFile.toPath(), StandardCharsets.UTF_8).trim())
                && !needsLegacyCleanup) {
            if (progressCallback != null) {
                progressCallback.onUpdate("Modpack already up to date: " + version);
            }
            return;
        }

        closeRunningGameBeforeUpdate(mcDir, progressCallback);
        ensureModpackDirectories(mcDir);
        if (needsLegacyCleanup) {
            cleanLegacyManagedModpackFiles(mcDir);
        }
        deleteStaleManifestFiles(mcDir, stalePaths);
        downloadManifestFiles(mcDir, baseUrl, filesToDownload);
        LauncherUtils.writeStringAtomic(installedManifestFile, pack.toString());
        LauncherUtils.writeStringAtomic(versionFile, remoteVersion);
        deleteOtherInstalledModpackMarkers(markerDir.getParentFile(), version);
        if (legacyVersionFile.exists()) {
            Files.deleteIfExists(legacyVersionFile.toPath());
        }

        if (progressCallback != null) {
            progressCallback.onUpdate("Сборка установлена: " + version);
        }
    }

    private JsonObject getModpackManifest(String version) throws Exception {
        String manifestContent = http.downloadString(LauncherConstants.MODPACK_MANIFEST_URL);
        JsonObject root = JsonParser.parseString(manifestContent).getAsJsonObject();

        if (!root.has("modpacks") || !root.get("modpacks").isJsonObject()) {
            throw new IOException("Modpack manifest has no modpacks object");
        }

        JsonObject modpacks = root.getAsJsonObject("modpacks");
        if (!modpacks.has(version) || !modpacks.get(version).isJsonObject()) {
            throw new IOException("Modpack not found in manifest: " + version);
        }

        return modpacks.getAsJsonObject(version);
    }

    private void downloadManifestFiles(File mcDir, String baseUrl, List<JsonObject> filesToDownload) throws Exception {
        if (filesToDownload.isEmpty()) {
            return;
        }

        List<Callable<Void>> tasks = new ArrayList<>();
        totalFiles.set(filesToDownload.size());
        downloadedFiles.set(0);
        globalStartTime = System.currentTimeMillis();

        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;

        for (JsonObject fileEntry : filesToDownload) {
            String relativePath = normalizeManifestPath(fileEntry.get("path").getAsString());
            if (relativePath == null || isUserOwnedPath(relativePath)) {
                continue;
            }

            String expectedSha256 = fileEntry.get("sha256").getAsString();
            long expectedSize = fileEntry.has("size") ? fileEntry.get("size").getAsLong() : -1L;
            File target = resolveManifestFile(mcDir, relativePath);
            String url = normalizedBaseUrl + "/" + encodeManifestPath(relativePath);

            tasks.add(() -> {
                downloadManifestFileWithVerification(url, target, relativePath, expectedSha256, expectedSize);
                printGlobalProgress();
                return null;
            });
        }

        runTasks(tasks);
    }

    private void downloadManifestFileWithVerification(String url, File target, String relativePath,
                                                      String expectedSha256, long expectedSize) throws Exception {
        IOException lastMismatch = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            Files.deleteIfExists(target.toPath());

            String attemptUrl = attempt == 1 ? url : cacheBustedUrl(url, attempt);
            http.downloadFile(attemptUrl, target, "modpack file " + relativePath);

            if (isFileCurrent(target, expectedSha256, expectedSize)) {
                return;
            }

            String actualSha256 = target.exists() && target.isFile() ? sha256(target) : "missing";
            long actualSize = target.exists() && target.isFile() ? target.length() : -1L;
            Files.deleteIfExists(target.toPath());

            lastMismatch = new IOException(
                    "SHA-256 mismatch after download: " + relativePath
                            + " (expected " + expectedSha256 + ", got " + actualSha256
                            + ", expectedSize " + expectedSize + ", gotSize " + actualSize + ")"
            );
        }

        throw new IOException(
                "Downloaded file does not match the modpack manifest after 3 attempts: " + relativePath
                        + ". The launcher cleared the bad cached copy. Regenerate /pura/manifest.json on the server "
                        + "after uploading this file, then start the launcher again.",
                lastMismatch
        );
    }

    private String cacheBustedUrl(String url, int attempt) {
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "greytonRetry=" + attempt + "-" + System.currentTimeMillis();
    }

    private Set<String> readInstalledManifestPaths(File installedManifestFile) throws IOException {
        Set<String> paths = new HashSet<>();
        if (!installedManifestFile.exists()) {
            return paths;
        }

        JsonObject root = JsonParser.parseString(
                Files.readString(installedManifestFile.toPath(), StandardCharsets.UTF_8)
        ).getAsJsonObject();

        if (!root.has("files") || !root.get("files").isJsonArray()) {
            return paths;
        }

        for (JsonElement element : root.getAsJsonArray("files")) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject fileEntry = element.getAsJsonObject();
            if (!fileEntry.has("path")) {
                continue;
            }

            String path = normalizeManifestPath(fileEntry.get("path").getAsString());
            if (path != null) {
                paths.add(path);
            }
        }

        return paths;
    }

    private Set<String> readOtherInstalledModpackPaths(File modpacksRoot, String activeVersion, Set<String> activePaths) throws IOException {
        Set<String> paths = new HashSet<>();
        if (modpacksRoot == null || !modpacksRoot.isDirectory()) {
            return paths;
        }

        File[] children = modpacksRoot.listFiles(File::isDirectory);
        if (children == null) {
            return paths;
        }

        String activeMarkerName = safeFileName(activeVersion);
        for (File child : children) {
            if (activeMarkerName.equals(child.getName())) {
                continue;
            }

            File manifest = new File(child, "installed-manifest.json");
            Set<String> otherPaths = readInstalledManifestPaths(manifest);
            otherPaths.removeAll(activePaths);
            paths.addAll(otherPaths);
        }

        return paths;
    }

    private void deleteOtherInstalledModpackMarkers(File modpacksRoot, String activeVersion) throws IOException {
        if (modpacksRoot == null || !modpacksRoot.isDirectory()) {
            return;
        }

        File[] children = modpacksRoot.listFiles(File::isDirectory);
        if (children == null) {
            return;
        }

        String activeMarkerName = safeFileName(activeVersion);
        for (File child : children) {
            if (activeMarkerName.equals(child.getName())) {
                continue;
            }

            if (!LauncherUtils.deleteDirectory(child) && child.exists()) {
                throw new IOException("Failed to delete old modpack marker: " + child.getAbsolutePath());
            }
        }
    }

    private void deleteStaleManifestFiles(File mcDir, Set<String> stalePaths) throws IOException {
        for (String relativePath : stalePaths) {
            if (isProtectedDeletionPath(relativePath)) {
                continue;
            }

            File target = resolveManifestFile(mcDir, relativePath);
            if (!target.exists() || isPreservedRuntimeFile(mcDir, target)) {
                continue;
            }

            if (!target.delete() && target.exists()) {
                throw new IOException("Failed to delete locked modpack file: " + target.getAbsolutePath());
            }

            deleteEmptyParents(mcDir, target.getParentFile());
        }
    }

    private void deleteEmptyParents(File mcDir, File directory) throws IOException {
        Path gamePath = mcDir.getCanonicalFile().toPath();
        while (directory != null) {
            Path directoryPath = directory.getCanonicalFile().toPath();
            if (directoryPath.equals(gamePath) || !directoryPath.startsWith(gamePath)) {
                return;
            }

            String relativePath = gamePath.relativize(directoryPath).toString().replace('\\', '/');
            if ("config".equals(relativePath) || "mods".equals(relativePath)) {
                return;
            }

            String[] children = directory.list();
            if (children == null || children.length > 0 || !directory.delete()) {
                return;
            }

            directory = directory.getParentFile();
        }
    }

    private File resolveManifestFile(File mcDir, String relativePath) throws IOException {
        File outFile = new File(mcDir, relativePath);
        Path gamePath = mcDir.getCanonicalFile().toPath();
        Path outPath = outFile.getCanonicalFile().toPath();

        if (!outPath.startsWith(gamePath)) {
            throw new IOException("Refusing to write outside game directory: " + relativePath);
        }

        return outFile;
    }

    private String normalizeManifestPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }

        String normalized = path.trim().replace('\\', '/');
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        if (normalized.contains("../") || normalized.equals("..") || normalized.contains(":/")) {
            return null;
        }

        return normalized;
    }

    private String encodeManifestPath(String path) {
        String[] parts = path.split("/");
        List<String> encoded = new ArrayList<>();
        for (String part : parts) {
            encoded.add(LauncherUtils.url(part).replace("+", "%20"));
        }
        return String.join("/", encoded);
    }

    private boolean isUserOwnedPath(String relativePath) {
        String normalized = relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.equals("options.txt")
                || normalized.equals("servers.dat")
                || normalized.equals("config/euphoria_patcher/data.json")
                || normalized.startsWith("saves/")
                || normalized.startsWith("screenshots/")
                || normalized.startsWith("logs/")
                || normalized.startsWith("crash-reports/");
    }

    private boolean isProtectedDeletionPath(String relativePath) {
        String normalized = relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        return isUserOwnedPath(relativePath)
                || normalized.equals("config")
                || normalized.startsWith("config/");
    }

    private boolean isFileCurrent(File file, String expectedSha256, long expectedSize) throws IOException {
        if (!file.exists() || !file.isFile()) {
            return false;
        }

        if (expectedSize >= 0 && file.length() != expectedSize) {
            return false;
        }

        return expectedSha256.equalsIgnoreCase(sha256(file));
    }

    private String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file.toPath())) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }

            StringBuilder result = new StringBuilder();
            for (byte b : digest.digest()) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
    }

    private void cleanLegacyManagedModpackFiles(File mcDir) throws IOException {
        String[] modpackPaths = {
                "mods",
                "defaultconfigs",
                ".fabric",
                ".mixin.out",
                "libraries/v1"
        };

        for (String path : modpackPaths) {
            deleteInsideGameDir(mcDir, path);
        }
    }

    private void closeRunningGameBeforeUpdate(File mcDir, ProgressCallback progressCallback) throws IOException {
        if (!LauncherUtils.hasJavaProcessUsingDirectory(mcDir)) {
            return;
        }

        if (progressCallback != null) {
            progressCallback.onUpdate("Закрываем запущенный Minecraft перед обновлением...");
        }

        LauncherUtils.stopJavaProcessesUsingDirectory(mcDir, 15);

        if (LauncherUtils.hasJavaProcessUsingDirectory(mcDir)) {
            throw new IOException("Minecraft все еще запущен и держит файлы сборки. Обновление отложено.");
        }
    }

    private void ensureModpackDirectories(File mcDir) throws IOException {
        Files.createDirectories(new File(mcDir, "config/euphoria_patcher").toPath());
    }

    private boolean isPreservedRuntimeFile(File mcDir, File file) throws IOException {
        Path gamePath = mcDir.getCanonicalFile().toPath();
        Path filePath = file.getCanonicalFile().toPath();
        String relativePath = gamePath.relativize(filePath).toString().replace('\\', '/');

        return relativePath.equalsIgnoreCase("config/euphoria_patcher/data.json");
    }

    private void writeModpackFile(File mcDir, File outFile, ZipInputStream zis, byte[] buffer) throws IOException {
        outFile.getParentFile().mkdirs();

        try {
            LauncherUtils.writeBytesAtomic(outFile, output -> {
                int len;

                while ((len = zis.read(buffer)) > 0) {
                    output.write(buffer, 0, len);
                }
            });
        } catch (IOException e) {
            if (isConfigFile(mcDir, outFile)) {
                System.out.println("DEBUG: Skipping locked config file -> " + outFile.getAbsolutePath() + " (" + e.getMessage() + ")");
                return;
            }

            throw e;
        }
    }

    private boolean isConfigFile(File mcDir, File file) throws IOException {
        Path gamePath = mcDir.getCanonicalFile().toPath();
        Path filePath = file.getCanonicalFile().toPath();
        Path configPath = gamePath.resolve("config").normalize();

        return filePath.startsWith(configPath);
    }

    private File resolveZipEntry(File mcDir, ZipEntry entry) throws IOException {
        File outFile = new File(mcDir, entry.getName());
        Path gamePath = mcDir.getCanonicalFile().toPath();
        Path outPath = outFile.getCanonicalFile().toPath();

        if (!outPath.startsWith(gamePath)) {
            throw new IOException("Refusing to extract outside game directory: " + entry.getName());
        }

        return outFile;
    }

    private void deleteInsideGameDir(File mcDir, String relativePath) throws IOException {
        if ("config".equals(relativePath.replace('\\', '/'))) {
            System.out.println("DEBUG: Preserving config directory during modpack cleanup");
            return;
        }

        File target = new File(mcDir, relativePath);

        if (!target.exists()) {
            return;
        }

        Path gamePath = mcDir.getCanonicalFile().toPath();
        Path targetPath = target.getCanonicalFile().toPath();

        if (!targetPath.startsWith(gamePath)) {
            throw new IOException("Refusing to delete outside game directory: " + targetPath);
        }

        if (!LauncherUtils.deleteDirectory(target) && target.exists()) {
            throw new IOException("Failed to delete locked path: " + targetPath);
        }
    }

    private String safeFileName(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public List<String> downloadExtraFiles(String version, List<String> libraryPaths) throws Exception {
        if (!version.contains("Pura")) {
            return libraryPaths;
        }

        libraryPaths.removeIf(this::isReplacedFabricLibrary);
        String minecraftVersion = new VersionManifestHelper().getRealVersionId(version);
        String fabricLoaderVersion = resolveFabricLoaderVersion(minecraftVersion);

        List<String[]> requiredLibraries = List.of(
                new String[]{"net.fabricmc", "fabric-loader", fabricLoaderVersion},

                new String[]{"net.fabricmc", "intermediary", minecraftVersion},

                new String[]{"net.fabricmc", "access-widener", "2.1.0"},
                new String[]{"net.fabricmc", "mapping-io", "0.5.1"},
                new String[]{"net.fabricmc", "tiny-remapper", "0.10.3"},

                new String[]{"org.ow2.asm", "asm", LauncherConstants.ASM_VERSION},
                new String[]{"org.ow2.asm", "asm-analysis", LauncherConstants.ASM_VERSION},
                new String[]{"org.ow2.asm", "asm-commons", LauncherConstants.ASM_VERSION},
                new String[]{"org.ow2.asm", "asm-tree", LauncherConstants.ASM_VERSION},
                new String[]{"org.ow2.asm", "asm-util", LauncherConstants.ASM_VERSION},

                new String[]{"net.fabricmc", "sponge-mixin", LauncherConstants.SPONGE_MIXIN_VERSION}
        );

        for (String[] lib : requiredLibraries) {
            String group = lib[0];
            String artifact = lib[1];
            String versionValue = lib[2];

            String groupPath = group.replace('.', '/');
            String jarName = artifact + "-" + versionValue + ".jar";

            String url;

            if (group.startsWith("org.ow2.asm")) {
                url = "https://repo1.maven.org/maven2/"
                        + groupPath + "/"
                        + artifact + "/"
                        + versionValue + "/"
                        + jarName;
            } else {
                url = "https://maven.fabricmc.net/"
                        + groupPath + "/"
                        + artifact + "/"
                        + versionValue + "/"
                        + jarName;
            }

            File jarFile = new File(
                    gameDir + "/libraries/"
                            + groupPath + "/"
                            + artifact + "/"
                            + versionValue + "/"
                            + jarName
            );

            if (!jarFile.exists()) {
                jarFile.getParentFile().mkdirs();

                System.out.println("DEBUG: downloading " + artifact + " -> " + url);

                http.downloadFile(url, jarFile, artifact);
            }

            if (!libraryPaths.contains(jarFile.getAbsolutePath())) {
                libraryPaths.add(jarFile.getAbsolutePath());
            }
        }

        return libraryPaths;
    }

    private String resolveFabricLoaderVersion(String minecraftVersion) {
        List<String> urls = List.of(
                "https://meta.fabricmc.net/v2/versions/loader/" + LauncherUtils.url(minecraftVersion),
                "https://meta.fabricmc.net/v2/versions/loader"
        );

        for (String url : urls) {
            try {
                JsonArray versions = JsonParser.parseString(http.downloadString(url)).getAsJsonArray();
                String fallback = null;

                for (JsonElement element : versions) {
                    if (!element.isJsonObject()) {
                        continue;
                    }

                    JsonObject root = element.getAsJsonObject();
                    JsonObject loader = root.has("loader") && root.get("loader").isJsonObject()
                            ? root.getAsJsonObject("loader")
                            : root;

                    if (!loader.has("version")) {
                        continue;
                    }

                    String version = loader.get("version").getAsString();
                    if (fallback == null) {
                        fallback = version;
                    }

                    boolean stable = !loader.has("stable") || loader.get("stable").getAsBoolean();
                    if (stable) {
                        System.out.println("DEBUG: Fabric Loader for " + minecraftVersion + " -> " + version);
                        return version;
                    }
                }

                if (fallback != null) {
                    System.out.println("DEBUG: Fabric Loader fallback for " + minecraftVersion + " -> " + fallback);
                    return fallback;
                }
            } catch (Exception e) {
                System.out.println("DEBUG: Failed to resolve Fabric Loader from " + url + ": " + e.getMessage());
            }
        }

        System.out.println("DEBUG: Using bundled Fabric Loader fallback -> " + LauncherConstants.FABRIC_LOADER_VERSION);
        return LauncherConstants.FABRIC_LOADER_VERSION;
    }

    private boolean isReplacedFabricLibrary(String path) {
        String normalized = path.replace('\\', '/');
        return normalized.contains("/net/fabricmc/fabric-loader/")
                || normalized.contains("/net/fabricmc/sponge-mixin/")
                || normalized.contains("/org/spongepowered/mixin/");
    }

    public interface ProgressCallback {
        void onUpdate(String message);
    }
}
