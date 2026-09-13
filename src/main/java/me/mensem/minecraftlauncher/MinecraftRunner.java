package me.mensem.minecraftlauncher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MinecraftRunner {

    private final String gameDir;
    private final String selectedVersion;
    private final String maxMemory;
    private final boolean useCustomJava;
    private final String javaPath;
    private final String extraJvmArgs;

    public MinecraftRunner(String gameDir, String selectedVersion, String maxMemory,
                           boolean useCustomJava, String javaPath, String extraJvmArgs) {
        this.gameDir = gameDir;
        this.selectedVersion = selectedVersion;
        this.maxMemory = maxMemory;
        this.useCustomJava = useCustomJava;
        this.javaPath = javaPath;
        this.extraJvmArgs = extraJvmArgs;
    }

    public void launch(List<String> libraryPaths, File nativesDir, String assetIndexId,
                       File versionJsonFile, MinecraftLauncher.AccountSession session,
                       LaunchMode launchMode, LaunchCallback callback) {
        try {
            File mcDir = new File(gameDir);
            List<File> versionJars = new ArrayList<>();
            collectVersionJars(versionJsonFile, versionJars);

            String jsonContent = Files.readString(versionJsonFile.toPath(), StandardCharsets.UTF_8);
            VersionManifestHelper helper = new VersionManifestHelper();
            String mainClass = helper.getMainClass(jsonContent, selectedVersion);

            StringBuilder cp = new StringBuilder();
            for (File jar : versionJars) {
                if (cp.length() > 0) cp.append(File.pathSeparator);
                cp.append(jar.getAbsolutePath());
            }

            for (String lib : libraryPaths) {
                if (cp.length() > 0) cp.append(File.pathSeparator);
                cp.append(lib);
            }

            String javaExe = resolveJavaExecutable();

            List<String> command = new ArrayList<>();
            command.add(javaExe);
            command.add("--enable-native-access=ALL-UNNAMED");

            addExtraJvmArgs(command);
            command.add("-Xmx" + getLaunchMaxMemory());

            command.add("-Djava.library.path=" + nativesDir.getAbsolutePath());
            if (session.mode == MinecraftLauncher.AuthMode.GREYTON && !session.playToken.isBlank()) {
                command.add("-Dgreyton.launcher.nickname=" + session.username);
                command.add("-Dgreyton.launcher.playToken=" + session.playToken);
                command.add("-Dgreyton.launcher.authToken=" + session.accessToken);
                command.add("-Dgreyton.launcher.minecraftHeartbeatUrl=" + LauncherConstants.GREYTON_MINECRAFT_HEARTBEAT_URL);
            }

            command.add("-Dpura.launch.mode=" + launchMode.name());

            command.add("-cp");
            command.add(cp.toString());
            command.add(mainClass);

            command.add("--version");
            command.add(selectedVersion);
            command.add("--gameDir");
            command.add(mcDir.getAbsolutePath());
            command.add("--assetsDir");
            command.add(new File(mcDir, "assets").getAbsolutePath());
            command.add("--assetIndex");
            command.add(assetIndexId);
            command.add("--username");
            command.add(session.username);
            command.add("--uuid");
            command.add(session.uuid);
            command.add("--accessToken");
            command.add(session.accessToken);
            command.add("--userType");
            command.add(session.userType);
            command.add("--versionType");
            command.add("Pura Launcher");
            command.add("--userProperties");
            command.add("{}");

            if (!session.xuid.isBlank()) {
                command.add("--xuid");
                command.add(session.xuid);
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(mcDir);
            Process process = pb.start();

            new Thread(() -> LauncherUtils.streamOutput(process.getInputStream(), "[MINECRAFT] "), "mc-stdout").start();
            new Thread(() -> LauncherUtils.streamOutput(process.getErrorStream(), "[MINECRAFT ERROR] "), "mc-stderr").start();

            if (callback != null) {
                callback.onLaunchSuccess(selectedVersion, session.username);
            }

            new Thread(() -> {
                try {
                    int exitCode = process.waitFor();
                    if (callback != null) {
                        callback.onGameExit(exitCode);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (callback != null) {
                        callback.onLaunchError(e);
                    }
                }
            }, "mc-waiter").start();
        } catch (Exception e) {
            e.printStackTrace();
            if (callback != null) {
                callback.onLaunchError(e);
            }
        }
    }

    private String getLaunchMaxMemory() {
        return LauncherUtils.clampRamGb(LauncherUtils.parseRam(maxMemory)) + "G";
    }

    private void addExtraJvmArgs(List<String> command) {
        if (extraJvmArgs == null || extraJvmArgs.isBlank()) {
            return;
        }

        for (String arg : extraJvmArgs.trim().split("\\s+")) {
            String trimmed = arg.trim();
            if (!trimmed.isBlank() && !isMemoryArg(trimmed)) {
                command.add(trimmed);
            }
        }
    }

    private boolean isMemoryArg(String arg) {
        return arg.startsWith("-Xmx") || arg.startsWith("-Xms");
    }

    private void collectVersionJars(File versionJsonFile, List<File> out) throws IOException {
        File versionDir = versionJsonFile.getParentFile();
        String versionName = versionJsonFile.getName().replace(".json", "");
        File versionJar = new File(versionDir, versionName + ".jar");

        if (!versionJar.exists()) {
            throw new IOException("JAR не найден: " + versionJar.getAbsolutePath());
        }

        out.add(versionJar);

        String jsonContent = Files.readString(versionJsonFile.toPath(), StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(jsonContent).getAsJsonObject();

        if (root.has("inheritsFrom")) {
            String parentVersion = root.get("inheritsFrom").getAsString();
            File parentJson = new File(versionDir.getParentFile(), parentVersion + "/" + parentVersion + ".json");

            if (parentJson.exists()) {
                collectVersionJars(parentJson, out);
            }
        }
    }

    private String resolveJavaExecutable() throws IOException {
        List<String> candidates = new ArrayList<>();

        if (useCustomJava && javaPath != null && !javaPath.isBlank()) {
            candidates.add(javaPath);
        }

        addJavaHomeCandidate(candidates, System.getenv("JAVA_HOME"));
        addJavaHomeCandidate(candidates, System.getProperty("java.home"));
        candidates.add("C:\\Program Files\\Eclipse Adoptium\\jdk-25.0.4.101-hotspot\\bin\\java.exe");
        candidates.add("C:\\Program Files\\Java\\jdk-25\\bin\\java.exe");
        candidates.add("C:\\Program Files\\Java\\latest\\bin\\java.exe");

        addJavaInstallCandidates(candidates, new File(System.getProperty("user.home"), ".jdks"));
        addJavaInstallCandidates(candidates, new File("C:\\Program Files\\Java"));
        addJavaInstallCandidates(candidates, new File("C:\\Program Files\\Eclipse Adoptium"));

        for (String candidate : candidates) {
            if (isJavaAtLeast(candidate, 25)) {
                System.out.println("DEBUG: Minecraft Java -> " + candidate);
                return candidate;
            }
        }

        throw new IOException("Minecraft 26.2 modpacks require Java 25 or newer. Install Java 25 or set a custom java.exe path in launcher settings.");
    }

    private void addJavaHomeCandidate(List<String> candidates, String javaHome) {
        if (javaHome != null && !javaHome.isBlank()) {
            candidates.add(new File(javaHome, "bin/java.exe").getAbsolutePath());
        }
    }

    private void addJavaInstallCandidates(List<String> candidates, File root) {
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) {
            return;
        }

        List<File> sorted = new ArrayList<>(List.of(dirs));
        sorted.sort(Comparator.comparing(File::getName).reversed());

        for (File dir : sorted) {
            candidates.add(new File(dir, "bin/java.exe").getAbsolutePath());
        }
    }

    private boolean isJavaAtLeast(String javaExe, int requiredMajor) {
        if (!"java".equals(javaExe) && !new File(javaExe).isFile()) {
            return false;
        }

        try {
            Process process = new ProcessBuilder(javaExe, "-version").start();
            String output;

            try (InputStream err = process.getErrorStream();
                 InputStream out = process.getInputStream()) {
                output = new String(err.readAllBytes(), StandardCharsets.UTF_8)
                        + "\n"
                        + new String(out.readAllBytes(), StandardCharsets.UTF_8);
            }

            process.waitFor();
            int major = parseJavaMajor(output);
            return major >= requiredMajor;
        } catch (Exception ignored) {
            return false;
        }
    }

    private int parseJavaMajor(String versionOutput) {
        Matcher matcher = Pattern.compile("version \"(\\d+)").matcher(versionOutput);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }

        matcher = Pattern.compile("openjdk (\\d+)").matcher(versionOutput.toLowerCase());
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }

        return -1;
    }

    public interface LaunchCallback {
        void onLaunchSuccess(String version, String username);
        void onGameExit(int exitCode);
        void onLaunchError(Exception e);
    }
}
