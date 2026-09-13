package me.mensem.minecraftlauncher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MinecraftLauncher extends Application {

    public enum AuthMode {
        OFFLINE("Оффлайн"),
        GREYTON("Greyton Core"),
        MICROSOFT("Microsoft");

        private final String label;
        AuthMode(String label) { this.label = label; }
        @Override public String toString() { return label; }
        static AuthMode fromConfig(String value) {
            for (AuthMode mode : values()) {
                if (mode.name().equalsIgnoreCase(value)) return mode;
            }
            return OFFLINE;
        }
    }

    static final class AccountSession {
        final AuthMode mode;
        final String username;
        final String uuid;
        final String accessToken;
        final String userType;
        final String xuid;
        final String skinUrl;
        final String playToken;

        AccountSession(AuthMode mode, String username, String uuid, String accessToken, String userType, String xuid) {
            this(mode, username, uuid, accessToken, userType, xuid, "", "");
        }

        AccountSession(AuthMode mode, String username, String uuid, String accessToken, String userType, String xuid, String skinUrl) {
            this(mode, username, uuid, accessToken, userType, xuid, skinUrl, "");
        }

        AccountSession(AuthMode mode, String username, String uuid, String accessToken, String userType, String xuid, String skinUrl, String playToken) {
            this.mode = mode;
            this.username = username;
            this.uuid = uuid;
            this.accessToken = accessToken;
            this.userType = userType;
            this.xuid = xuid == null ? "" : xuid;
            this.skinUrl = skinUrl == null ? "" : skinUrl;
            this.playToken = playToken == null ? "" : playToken;
        }
    }

    private ConfigManager config;
    private String selectedVersion;
    private AuthMode authMode;
    private String offlineUsername;
    private String microsoftRefreshToken;
    private String cachedMicrosoftName;
    private String cachedMicrosoftUuid;
    private String greytonAuthToken;
    private String greytonIdentity;
    private String greytonSkinUrl;
    private String maxMemory;
    private String javaPath;
    private boolean useCustomJava;
    private String extraJvmArgs;
    private String gameDir;

    private AccountSession currentSession;
    private Stage primaryStage;
    private MainWindow ui;
    private String expectedAuthState;

    private final HttpClientWrapper http = new HttpClientWrapper();
    private VersionManifestHelper manifestHelper = new VersionManifestHelper();
    private AssetDownloader assetDownloader;
    private MinecraftRunner minecraftRunner;
    private MicrosoftAuthHelper microsoftAuthHelper;
    private GreytonAuthHelper greytonAuthHelper;
    private ScheduledExecutorService greytonJoinHeartbeat;

    @Override
    public void start(Stage stage) {
        System.setProperty("file.encoding", "UTF-8");
        Platform.setImplicitExit(false);
        config = new ConfigManager();
        syncConfigToFields();
        
        // Определение режима авторизации на основе Dev режима
        if (isDevMode()) {
            authMode = AuthMode.OFFLINE;
        } else if (authMode == null) {
            authMode = AuthMode.GREYTON; // Значение по умолчанию
        }
        
        syncFieldsToConfig();
        config.save();
        primaryStage = stage;
        primaryStage.setOnCloseRequest(event -> {
            stopGreytonJoinHeartbeat();
            Platform.exit();
        });

        assetDownloader = new AssetDownloader(http, gameDir);
        minecraftRunner = new MinecraftRunner(gameDir, selectedVersion, maxMemory, useCustomJava, javaPath, extraJvmArgs);

        // Create UI
        ui = new MainWindow(this);
        ui.setup(stage);

        microsoftAuthHelper = new MicrosoftAuthHelper(http, config, msg -> { if (ui != null) ui.updateNotification(msg); });
        greytonAuthHelper = new GreytonAuthHelper(http, config, msg -> { if (ui != null) ui.updateNotification(msg); });
    }

    private void syncConfigToFields() {
        gameDir = config.getGameDir();
        offlineUsername = config.getOfflineUsername();
        selectedVersion = config.getSelectedVersion();
        authMode = config.getAuthMode();
        microsoftRefreshToken = config.getMicrosoftRefreshToken();
        cachedMicrosoftName = config.getCachedMicrosoftName();
        cachedMicrosoftUuid = config.getCachedMicrosoftUuid();
        greytonAuthToken = config.getGreytonAuthToken();
        greytonIdentity = config.getGreytonIdentity();
        greytonSkinUrl = config.getGreytonSkinUrl();
        maxMemory = config.getMaxMemory();
        javaPath = config.getJavaPath();
        useCustomJava = config.isUseCustomJava();
        extraJvmArgs = config.getExtraJvmArgs();
    }

    void syncFieldsToConfig() {
        config.setGameDir(gameDir);
        config.setOfflineUsername(offlineUsername);
        config.setSelectedVersion(selectedVersion);
        config.setAuthMode(authMode);
        config.setMicrosoftRefreshToken(microsoftRefreshToken);
        config.setCachedMicrosoftName(cachedMicrosoftName);
        config.setCachedMicrosoftUuid(cachedMicrosoftUuid);
        config.setGreytonAuthToken(greytonAuthToken);
        config.setGreytonIdentity(greytonIdentity);
        config.setGreytonSkinUrl(greytonSkinUrl);
        config.setMaxMemory(maxMemory);
        config.setJavaPath(javaPath);
        config.setUseCustomJava(useCustomJava);
        config.setExtraJvmArgs(extraJvmArgs);
    }

    public Stage getPrimaryStage() { return primaryStage; }

    public String getSelectedVersion() { return selectedVersion; }
    public void setSelectedVersion(String v) { this.selectedVersion = v; }

    public AuthMode getAuthMode() { return authMode; }
    public void setAuthMode(AuthMode mode) { this.authMode = mode; }

    public String getOfflineUsername() { return offlineUsername; }
    public void setOfflineUsername(String name) { this.offlineUsername = name == null ? "" : name.trim(); }

    public String getCachedMicrosoftName() { return cachedMicrosoftName; }
    public void setCachedMicrosoftName(String name) { this.cachedMicrosoftName = name; }

    public String getCachedMicrosoftUuid() { return cachedMicrosoftUuid; }
    public void setCachedMicrosoftUuid(String uuid) { this.cachedMicrosoftUuid = uuid; }

    public String getMicrosoftRefreshToken() { return microsoftRefreshToken; }
    public void setMicrosoftRefreshToken(String token) { this.microsoftRefreshToken = token; }
    public String getGreytonAuthToken() { return greytonAuthToken; }
    public void setGreytonAuthToken(String token) { this.greytonAuthToken = token == null ? "" : token; }
    public String getGreytonIdentity() { return greytonIdentity; }
    public void setGreytonIdentity(String value) { this.greytonIdentity = value == null ? "" : value.trim(); }
    public String getGreytonSkinUrl() { return greytonSkinUrl; }
    public void setGreytonSkinUrl(String value) { this.greytonSkinUrl = value == null ? "" : value.trim(); }

    public String getMaxMemory() { return maxMemory; }
    public void setMaxMemory(String mem) { this.maxMemory = LauncherUtils.clampRamGb(LauncherUtils.parseRam(mem)) + "G"; }

    public String getJavaPath() { return javaPath; }
    public void setJavaPath(String path) { this.javaPath = path; }

    public boolean isUseCustomJava() { return useCustomJava; }
    public void setUseCustomJava(boolean use) { this.useCustomJava = use; }

    public String getExtraJvmArgs() { return extraJvmArgs; }
    public void setExtraJvmArgs(String args) { this.extraJvmArgs = args; }

    public String getGameDir() { return gameDir; }
    public void setGameDir(String dir) { this.gameDir = dir; }

    public AccountSession getCurrentSession() { return currentSession; }
    public void setCurrentSession(AccountSession session) { this.currentSession = session; }

    public ConfigManager getConfig() { return config; }

    public void applyLauncherSettings(String newGameDir, String newMaxMemory, boolean newUseCustomJava,
                                      String newJavaPath, String newExtraJvmArgs) throws Exception {
        File targetDir = validateGameDir(newGameDir);

        gameDir = targetDir.getAbsolutePath();
        maxMemory = LauncherUtils.clampRamGb(LauncherUtils.parseRam(newMaxMemory)) + "G";
        useCustomJava = newUseCustomJava;
        javaPath = newJavaPath == null ? "" : newJavaPath.trim();
        extraJvmArgs = newExtraJvmArgs == null ? "" : newExtraJvmArgs.trim();

        syncFieldsToConfig();
        config.save();
        refreshLaunchServices();
    }

    private File validateGameDir(String value) throws IOException {
        if (value == null || value.trim().isBlank()) {
            throw new IOException("Choose a folder for Minecraft files.");
        }

        File dir = new File(value.trim()).getAbsoluteFile();
        if (dir.exists() && !dir.isDirectory()) {
            throw new IOException("Selected path is not a folder: " + dir.getAbsolutePath());
        }

        java.nio.file.Files.createDirectories(dir.toPath());

        File probe = File.createTempFile(".pura-write-test", ".tmp", dir);
        if (!probe.delete() && probe.exists()) {
            probe.deleteOnExit();
        }

        return dir;
    }

    public String getStylesheet() {
        var resource = getClass().getResource("/launcher.css");
        return resource != null ? resource.toExternalForm() : null;
    }

    public javafx.scene.image.Image getResourceImage(String path) {
        var resource = getClass().getResource(path);
        return resource != null ? new javafx.scene.image.Image(resource.toExternalForm()) : null;
    }

    public javafx.scene.text.Font loadFont(String path, double size) {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is != null) return javafx.scene.text.Font.loadFont(is, size);
        } catch (Exception e) { e.printStackTrace(); }
        return javafx.scene.text.Font.getDefault();
    }

    void startDownloadAndLaunch() {
        new Thread(() -> {
            final boolean[] gameStarted = {false};
            try {
                ui.setProgressVisible(true);
                ui.updateProgress(0);
                refreshLaunchServices();
                AccountSession session = ensureLaunchSession();
                File mcDir = new File(gameDir);
                mcDir.mkdirs();
                closeRunningGameBeforeLaunch(mcDir);

                File manifestFile = new File(mcDir, "version_manifest_v2.json");

                if (!manifestFile.exists()) {
                    ui.updateNotification("Копируем version_manifest_v2.json...");

                    try (InputStream in = getClass().getResourceAsStream("/version_manifest_v2.json")) {
                        if (in == null) {
                            throw new RuntimeException("version_manifest_v2.json не найден в resources");
                        }
                        LauncherUtils.copyResourceAtomic(in, manifestFile);
                    }
                    ui.updateProgress(0.10);
                }

                List<String> versionChain = new ArrayList<>();
                String currentVer = selectedVersion;
                while (true) {
                    versionChain.add(currentVer);
                    String realId = manifestHelper.getRealVersionId(currentVer);
                    String jsonUrl = manifestHelper.getVersionJsonUrl(manifestFile, realId);
                    if (jsonUrl == null) break;
                    String tempJsonContent = http.downloadString(jsonUrl);
                    String parent = null;
                    try {
                        JsonObject root = JsonParser.parseString(tempJsonContent).getAsJsonObject();
                        if (root.has("inheritsFrom")) parent = root.get("inheritsFrom").getAsString();
                    } catch (Exception ignored) {}
                    if (parent == null) break;
                    currentVer = parent;
                }
                Collections.reverse(versionChain);

                for (String ver : versionChain) {
                    File verDir = new File(mcDir, "versions/" + ver);
                    verDir.mkdirs();
                    File verJson = new File(verDir, ver + ".json");
                    File verJar = new File(verDir, ver + ".jar");
                    String realId = manifestHelper.getRealVersionId(ver);
                    String jsonUrl = manifestHelper.getVersionJsonUrl(manifestFile, realId);
                    if (!verJson.exists() || verJson.length() == 0) {
                        ui.updateNotification("Скачиваем " + ver + ".json...");
                        http.downloadFile(jsonUrl, verJson, ver + ".json");
                        ui.updateProgress(0.25);
                    }
                    String jsonContent = Files.readString(verJson.toPath(), StandardCharsets.UTF_8);
                    String oldIdNoSpace = "\"id\":\"" + realId + "\"";
                    String oldIdWithSpace = "\"id\": \"" + realId + "\"";
                    if (jsonContent.contains(oldIdNoSpace) || jsonContent.contains(oldIdWithSpace)) {
                        jsonContent = jsonContent.replace(oldIdNoSpace, "\"id\": \"" + ver + "\"");
                        jsonContent = jsonContent.replace(oldIdWithSpace, "\"id\": \"" + ver + "\"");
                        LauncherUtils.writeStringAtomic(verJson, jsonContent);
                    }
                    String clientUrl = manifestHelper.getClientDownloadUrl(jsonContent);
                    if (clientUrl != null && !verJar.exists()) {
                        ui.updateNotification("Скачиваем " + ver + ".jar...");
                        http.downloadFile(clientUrl, verJar, ver + ".jar");
                        ui.updateProgress(0.50);
                    }
                }

                File jsonFile = new File(mcDir, "versions/" + selectedVersion + "/" + selectedVersion + ".json");
                String jsonContent = Files.readString(jsonFile.toPath(), StandardCharsets.UTF_8);
                String assetIndexUrl = manifestHelper.getAssetIndexUrl(jsonContent);
                String assetIndexId = manifestHelper.getAssetIndexId(jsonContent);
                File indexesDir = new File(mcDir, "assets/indexes");
                indexesDir.mkdirs();
                File indexFile = new File(indexesDir, assetIndexId + ".json");
                if (assetIndexUrl != null && !indexFile.exists()) {
                    ui.updateNotification("Скачиваем asset index...");
                    http.downloadFile(assetIndexUrl, indexFile, "asset index");
                    ui.updateProgress(0.55);
                }

                ui.updateNotification("Скачиваем ассеты...");
                assetDownloader.downloadAssets(indexFile, new File(mcDir, "assets"));
                ui.updateProgress(0.75);

                ui.updateNotification("Скачиваем библиотеки...");
                List<String> libraryPaths = assetDownloader.downloadLibraries(jsonFile);
                ui.updateProgress(0.90);

                File versionDir = new File(mcDir, "versions/" + selectedVersion);
                versionDir.mkdirs();
                cleanupOldNativesDirs(versionDir);
                File nativesDir = Files.createTempDirectory(versionDir.toPath(), "natives-").toFile();
                assetDownloader.extractNatives(libraryPaths, nativesDir);
                ui.updateProgress(0.95);

                if (selectedVersion.contains("Pura")) {
                    File v1Dir = new File(mcDir, "libraries/v1");
                    if (v1Dir.exists() && !LauncherUtils.deleteDirectory(v1Dir) && v1Dir.exists()) {
                        throw new IOException("Не удалось обновить libraries/v1: файлы заняты другим процессом.");
                    }
                    ui.updateNotification("Докачиваем Fabric + Mixin + ASM для " + selectedVersion + "...");
                    libraryPaths = assetDownloader.downloadExtraFiles(selectedVersion, libraryPaths);
                }

                if (LauncherConstants.MODPACK_IDS.containsKey(selectedVersion)) {
                    assetDownloader.extractModpack(selectedVersion, gameDir, msg -> ui.updateNotification(msg));
                }

                ui.updateProgress(1.0);

                if (session.mode == AuthMode.GREYTON) {
                    greytonAuthHelper.createJoinGrant(session);
                    session = greytonAuthHelper.withFreshPlayToken(session);
                }

                ui.updateNotification("Все готово. Запускаем " + selectedVersion);

                AccountSession launchSession = session;
                minecraftRunner.launch(libraryPaths, nativesDir, assetIndexId, jsonFile, launchSession,
                        config.getLaunchMode(),
                        new MinecraftRunner.LaunchCallback() {
                            @Override public void onLaunchSuccess(String version, String username) {
                                gameStarted[0] = true;
                                stopGreytonJoinHeartbeat();
                                Platform.runLater(() -> {
                                    Platform.exit();
                                    System.exit(0);
                                });
                            }
                            @Override public void onGameExit(int exitCode) {
                                stopGreytonJoinHeartbeat();
                                Platform.runLater(() -> {
                                    ui.setPlayButtonEnabled(true);
                                    primaryStage.show();
                                    primaryStage.toFront();
                                });
                            }
                            @Override public void onLaunchError(Exception e) {
                                stopGreytonJoinHeartbeat();
                                ui.showError("Ошибка запуска", e.getMessage());
                            }
                        });
            } catch (Exception ex) {
                ex.printStackTrace();
                ui.showError("Ошибка", ex.getMessage());
            } finally {
                if (!gameStarted[0]) {
                    stopGreytonJoinHeartbeat();
                    ui.setPlayButtonEnabled(true);
                }
                ui.setProgressVisible(false);
                ui.hideNotification();
            }
        }).start();
    }

    private void closeRunningGameBeforeLaunch(File mcDir) throws IOException {
        if (!LauncherUtils.hasJavaProcessUsingDirectory(mcDir)) {
            return;
        }

        ui.updateNotification("Закрываем запущенный Minecraft...");
        LauncherUtils.stopJavaProcessesUsingDirectory(mcDir, 15);

        if (LauncherUtils.hasJavaProcessUsingDirectory(mcDir)) {
            throw new IOException("Minecraft всё ещё запущен и держит файлы сборки. Закройте игру и попробуйте снова.");
        }
    }

    private void cleanupOldNativesDirs(File versionDir) {
        File[] nativeDirs = versionDir.listFiles(file -> file.isDirectory()
                && ("natives".equals(file.getName()) || file.getName().startsWith("natives-")));
        if (nativeDirs == null) {
            return;
        }

        long keepRecentMs = 6L * 60L * 60L * 1000L;
        long now = System.currentTimeMillis();

        for (File dir : nativeDirs) {
            if (dir.getName().startsWith("natives-") && now - dir.lastModified() < keepRecentMs) {
                continue;
            }

            if (!LauncherUtils.deleteDirectory(dir) && dir.exists()) {
                System.out.println("DEBUG: Keeping locked natives directory -> " + dir.getAbsolutePath());
            }
        }
    }

    public AccountSession ensureLaunchSession() throws Exception {
        if (isDevMode()) {
            currentSession = new AccountSession(AuthMode.OFFLINE, "Mensem",
                    "00000000-0000-0000-0000-000000000000",
                    "00000000-0000-0000-0000-000000000000", "legacy", "", "", "mock-token");
            return currentSession;
        }
        if (authMode == AuthMode.OFFLINE) {
            if (offlineUsername == null || offlineUsername.isBlank()) {
                throw new IllegalStateException("Введите ник в левом меню перед запуском.");
            }
            currentSession = new AccountSession(AuthMode.OFFLINE, offlineUsername,
                    LauncherUtils.generateOfflineUUID(offlineUsername),
                    "00000000-0000-0000-0000-000000000000", "legacy", "");
            return currentSession;
        }
        if (authMode == AuthMode.GREYTON) {
            try {
                AccountSession session = greytonAuthHelper.authenticate(false);
                currentSession = session;
                offlineUsername = session.username;
                greytonAuthToken = config.getGreytonAuthToken();
                greytonIdentity = config.getGreytonIdentity();
                greytonSkinUrl = config.getGreytonSkinUrl();
                syncFieldsToConfig();
                config.save();
                Platform.runLater(() -> ui.updateProfilePanel());
                return session;
            } catch (Exception error) {
                currentSession = null;
                greytonAuthToken = config.getGreytonAuthToken();
                greytonIdentity = config.getGreytonIdentity();
                greytonSkinUrl = config.getGreytonSkinUrl();
                syncFieldsToConfig();
                config.save();
                Platform.runLater(() -> ui.updateProfilePanel());
                throw error;
            }
        }
        AccountSession session = microsoftAuthHelper.authenticate(true);
        currentSession = session;
        cachedMicrosoftName = session.username;
        cachedMicrosoftUuid = session.uuid;
        microsoftRefreshToken = config.getMicrosoftRefreshToken();
        syncFieldsToConfig();
        config.save();
        Platform.runLater(() -> ui.updateProfilePanel());
        return session;
    }

    void refreshLaunchServices() {
        assetDownloader = new AssetDownloader(http, gameDir);
        minecraftRunner = new MinecraftRunner(gameDir, selectedVersion, maxMemory, useCustomJava, javaPath, extraJvmArgs);
    }

    private synchronized void startGreytonJoinHeartbeat(AccountSession session) {
        stopGreytonJoinHeartbeat();
        if (session == null || session.mode != AuthMode.GREYTON) {
            return;
        }

        greytonJoinHeartbeat = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "greyton-join-heartbeat");
            thread.setDaemon(true);
            return thread;
        });

        greytonJoinHeartbeat.scheduleAtFixedRate(() -> {
            try {
                greytonAuthHelper.createJoinGrant(session);
            } catch (Exception error) {
                System.out.println("DEBUG: Greyton join heartbeat failed -> " + error.getMessage());
            }
        }, 4, 4, TimeUnit.MINUTES);
    }

    private synchronized void stopGreytonJoinHeartbeat() {
        if (greytonJoinHeartbeat != null) {
            greytonJoinHeartbeat.shutdownNow();
            greytonJoinHeartbeat = null;
        }
    }

    void loginMicrosoftInteractive() {
        new Thread(() -> {
            try {
                ui.updateNotification("Открываем вход Microsoft...");
                AccountSession session = microsoftAuthHelper.authenticate(true);
                currentSession = session;
                cachedMicrosoftName = session.username;
                cachedMicrosoftUuid = session.uuid;
                microsoftRefreshToken = config.getMicrosoftRefreshToken();
                syncFieldsToConfig();
                config.save();
                Platform.runLater(() -> ui.updateProfilePanel());
            } catch (Exception e) {
                e.printStackTrace();
                ui.showError("Ошибка входа Microsoft", e.getMessage());
            } finally {
                ui.hideNotification();
            }
        }).start();
    }

    void loginGreytonInteractive(String identity, String password) {
        new Thread(() -> {
            try {
                ui.updateNotification("Greyton Core login...");
                AccountSession session = greytonAuthHelper.login(identity, password);
                currentSession = session;
                offlineUsername = session.username;
                greytonAuthToken = config.getGreytonAuthToken();
                greytonIdentity = config.getGreytonIdentity();
                greytonSkinUrl = config.getGreytonSkinUrl();
                syncFieldsToConfig();
                config.save();
                Platform.runLater(() -> ui.updateProfilePanel());
            } catch (Exception e) {
                e.printStackTrace();
                ui.showError("Ошибка входа Greyton Core", LauncherUtils.readableText(e.getMessage()));
            } finally {
                ui.hideNotification();
            }
        }).start();
    }

    void loginGreytonGate(String identity, String password, boolean rememberSession,
                          java.util.function.Consumer<AccountSession> onSuccess,
                          java.util.function.Consumer<String> onError) {
        new Thread(() -> {
            try {
                AccountSession session = greytonAuthHelper.login(identity, password);
                currentSession = session;
                offlineUsername = session.username;
                greytonIdentity = config.getGreytonIdentity();
                greytonSkinUrl = config.getGreytonSkinUrl();
                authMode = AuthMode.GREYTON;

                if (rememberSession) {
                    greytonAuthToken = config.getGreytonAuthToken();
                } else {
                    greytonAuthToken = "";
                    config.setGreytonAuthToken("");
                }

                syncFieldsToConfig();
                config.save();
                Platform.runLater(() -> {
                    ui.updateProfilePanel();
                    ui.hideNotification();
                    if (onSuccess != null) {
                        onSuccess.accept(session);
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> {
                    ui.hideNotification();
                    if (onError != null) {
                        onError.accept(LauncherUtils.readableText(e.getMessage()));
                    }
                });
            }
        }, "greyton-login-gate").start();
    }

    void resumeGreytonGate(java.util.function.Consumer<AccountSession> onSuccess,
                           java.util.function.Consumer<String> onError) {
        new Thread(() -> {
            try {
                AccountSession session = greytonAuthHelper.authenticate(false);
                currentSession = session;
                offlineUsername = session.username;
                greytonAuthToken = config.getGreytonAuthToken();
                greytonIdentity = config.getGreytonIdentity();
                greytonSkinUrl = config.getGreytonSkinUrl();
                authMode = AuthMode.GREYTON;
                syncFieldsToConfig();
                config.save();
                Platform.runLater(() -> {
                    ui.hideNotification();
                    ui.updateProfilePanel();
                    if (onSuccess != null) {
                        onSuccess.accept(session);
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> {
                    ui.hideNotification();
                    if (onError != null) {
                        onError.accept(LauncherUtils.readableText(e.getMessage()));
                    }
                });
            }
        }, "greyton-resume-gate").start();
    }

    void logoutGreyton() {
        stopGreytonJoinHeartbeat();
        currentSession = null;
        greytonAuthToken = "";
        greytonSkinUrl = "";
        config.setGreytonAuthToken("");
        config.setGreytonSkinUrl("");
        config.save();
        syncConfigToFields();
        Platform.runLater(() -> ui.updateProfilePanel());
    }

    void logoutMicrosoft() {
        stopGreytonJoinHeartbeat();
        currentSession = null;
        microsoftRefreshToken = "";
        cachedMicrosoftName = "";
        cachedMicrosoftUuid = "";
        config.setMicrosoftRefreshToken("");
        config.setCachedMicrosoftName("");
        config.setCachedMicrosoftUuid("");
        config.save();
        syncConfigToFields();
        Platform.runLater(() -> ui.updateProfilePanel());
    }

    void switchMicrosoftAccount() {
        new Thread(() -> {
            try {
                ui.updateNotification("Смена аккаунта...");
                stopGreytonJoinHeartbeat();
                currentSession = null;
                microsoftRefreshToken = "";
                cachedMicrosoftName = "";
                cachedMicrosoftUuid = "";
                config.setMicrosoftRefreshToken("");
                config.setCachedMicrosoftName("");
                config.setCachedMicrosoftUuid("");
                config.save();
                Platform.runLater(() -> ui.updateProfilePanel());

                AccountSession session = microsoftAuthHelper.authenticate(true);
                currentSession = session;
                cachedMicrosoftName = session.username;
                cachedMicrosoftUuid = session.uuid;
                microsoftRefreshToken = config.getMicrosoftRefreshToken();
                syncFieldsToConfig();
                config.save();
                Platform.runLater(() -> ui.updateProfilePanel());
            } catch (Exception e) {
                e.printStackTrace();
                ui.showError("Ошибка смены аккаунта", e.getMessage());
            } finally {
                ui.hideNotification();
            }
        }).start();
    }

    boolean isFabricVersion() {
        return selectedVersion.contains("Pura");
    }

    public boolean isDevMode() {
        return Boolean.parseBoolean(System.getenv("PURA_DEV_OFFLINE")) ||
               Boolean.parseBoolean(System.getProperty("PuraDevOffline"));
    }

    public void initiateAuthFlow() {
        expectedAuthState = LauncherUtils.randomUrlToken(32);
        String authUrl = LauncherConstants.GREYTON_API_BASE + "/api/launcher/auth?state=" + expectedAuthState;
        
        try {
            java.awt.Desktop.getDesktop().browse(new java.net.URI(authUrl));
            ui.updateNotification("Ожидание авторизации в браузере...");
        } catch (Exception e) {
            e.printStackTrace();
            ui.showError("Ошибка", "Не удалось открыть браузер.");
        }
    }

    public void processAuthCallback(String uri) {
        try {
            java.net.URI parsedUri = new java.net.URI(uri);
            String query = parsedUri.getQuery();
            if (query == null) throw new Exception("Некорректный callback.");
            
            Map<String, String> params = new HashMap<>();
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length == 2) params.put(pair[0], pair[1]);
            }
            
            String code = params.get("code");
            String state = params.get("state");
            
            if (code == null || state == null) throw new Exception("Отсутствует code или state.");
            if (!state.equals(expectedAuthState)) throw new Exception("Неверный state.");
            
            // Exchange code
            AccountSession session = greytonAuthHelper.exchangeCallbackCode(code, state);
            this.currentSession = session;
            this.offlineUsername = session.username;
            this.authMode = AuthMode.GREYTON;
            
            syncFieldsToConfig();
            config.save();
            Platform.runLater(() -> ui.updateProfilePanel());
            
            // Clear state
            expectedAuthState = null;
            
        } catch (Exception e) {
            e.printStackTrace();
            Platform.runLater(() -> ui.showError("Ошибка авторизации", e.getMessage()));
        }
    }
}

