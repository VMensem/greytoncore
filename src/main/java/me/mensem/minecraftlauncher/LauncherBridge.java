package me.mensem.minecraftlauncher;

import java.awt.Desktop;
import java.io.File;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javafx.application.Platform;
import javafx.stage.Stage;

public class LauncherBridge {
    private final MinecraftLauncher launcher;
    private MainWindow ui;
    private final Gson gson = new Gson();

    private double dragOffsetX;
    private double dragOffsetY;

    public LauncherBridge(MinecraftLauncher launcher) {
        this(launcher, null);
    }

    public LauncherBridge(MinecraftLauncher launcher, MainWindow ui) {
        this.launcher = launcher;
        this.ui = ui;
    }

    public void setUi(MainWindow ui) {
        this.ui = ui;
    }

    // --- Page Loader ---

    public String loadPageContent(String page) {
        try {
            String path = "/web/pages/" + page + ".html";
            java.io.InputStream is = getClass().getResourceAsStream(path);
            if (is == null) {
                System.out.println("[LauncherBridge] Resource not found: " + path);
                return null;
            }
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // --- Action Methods ---

    public void play() {
        launcher.startDownloadAndLaunch();
    }

    public void minimize() {
        Platform.runLater(() -> {
            Stage stage = launcher.getPrimaryStage();
            if (stage != null) {
                stage.setIconified(true);
            }
        });
    }

    public void close() {
        Platform.runLater(() -> {
            Stage stage = launcher.getPrimaryStage();
            if (stage != null) {
                stage.close();
            }
            Platform.exit();
            System.exit(0);
        });
    }

    // --- Window Dragging ---

    public void startDrag(double screenX, double screenY) {
        Stage stage = launcher.getPrimaryStage();
        if (stage != null) {
            dragOffsetX = screenX - stage.getX();
            dragOffsetY = screenY - stage.getY();
        }
    }

    public void drag(double screenX, double screenY) {
        Stage stage = launcher.getPrimaryStage();
        if (stage != null) {
            stage.setX(screenX - dragOffsetX);
            stage.setY(screenY - dragOffsetY);
        }
    }

    public void loginGreyton(String identity, String password) {
        launcher.loginGreytonGate(identity, password, true, (session) -> {
            Platform.runLater(() -> {
                try {
                    ui.getWebEngine().executeScript("window.PuraAuth.onLoginSuccess();");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }, (error) -> {
            Platform.runLater(() -> {
                try {
                    String escapedError = gson.toJson(error);
                    ui.getWebEngine().executeScript("window.PuraAuth.setLoading(false); window.PuraAuth.showMessage(" + escapedError + ", 'error');");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        });
    }

    public void loginMicrosoft() {
        launcher.loginMicrosoftInteractive();
    }

    public String getProfile() {
        // Force refresh session data to get latest skin/cape
        if (launcher.getAuthMode() == MinecraftLauncher.AuthMode.GREYTON) {
            try {
                // This calls the API /api/launcher/me and updates the current session
                launcher.ensureLaunchSession();
            } catch (Exception e) {
                // Fallback to cached if API fails
            }
        }

        JsonObject profile = new JsonObject();
        MinecraftLauncher.AccountSession session = launcher.getCurrentSession();
        ConfigManager config = launcher.getConfig();

        String username = "Игрок";
        String skinUrl = "";
        String uuid = "";

        if (session != null) {
            username = session.username != null && !session.username.isBlank() ? session.username : "Игрок";
            skinUrl = session.skinUrl != null ? session.skinUrl : "";
            uuid = session.uuid != null ? session.uuid : "";
        } else if (config != null) {
            if (config.getAuthMode() == MinecraftLauncher.AuthMode.GREYTON && config.getGreytonIdentity() != null && !config.getGreytonIdentity().isBlank()) {
                username = config.getGreytonIdentity();
                skinUrl = config.getGreytonSkinUrl() != null ? config.getGreytonSkinUrl() : "";
            } else if (config.getAuthMode() == MinecraftLauncher.AuthMode.MICROSOFT && config.getCachedMicrosoftName() != null && !config.getCachedMicrosoftName().isBlank()) {
                username = config.getCachedMicrosoftName();
                uuid = config.getCachedMicrosoftUuid() != null ? config.getCachedMicrosoftUuid() : "";
            } else if (config.getOfflineUsername() != null && !config.getOfflineUsername().isBlank()) {
                username = config.getOfflineUsername();
            }
        }

        // Cache busting: append timestamp
        if (!skinUrl.isBlank()) {
            skinUrl += (skinUrl.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis();
        }

        profile.addProperty("username", username);
        profile.addProperty("nickname", username);
        profile.addProperty("role", launcher.getAuthMode() != null ? launcher.getAuthMode().toString() : "Игрок");
        profile.addProperty("avatar", skinUrl);
        profile.addProperty("skin", skinUrl);
        profile.addProperty("uuid", uuid);
        profile.addProperty("authMode", launcher.getAuthMode() != null ? launcher.getAuthMode().name() : "OFFLINE");
        profile.addProperty("level", 1);
        profile.addProperty("xp", 0);
        profile.addProperty("nextLevelXp", 100);

        return gson.toJson(profile);
    }

    public String getUserProfile() {
        return getProfile();
    }

    // --- Friends & News & Stats (Per Rule 14: NO FAKE DATA) ---

    public String getFriends() {
        return "[]";
    }

    public String getNews() {
        return "[]";
    }

    public String getStatistics() {
        JsonObject stats = new JsonObject();
        stats.addProperty("playtime", "0 ч.");
        stats.addProperty("kills", 0);
        stats.addProperty("deaths", 0);
        stats.addProperty("blocks", 0);
        stats.addProperty("distance", 0);
        stats.addProperty("wins", 0);
        return gson.toJson(stats);
    }

    public String getStats() {
        return getStatistics();
    }

    // --- Server Status ---

    public String getServerStatus() {
        JsonObject status = new JsonObject();
        status.addProperty("online", true);
        status.addProperty("isOnline", true);
        status.addProperty("players", 0);
        status.addProperty("onlinePlayers", 0);
        status.addProperty("maxPlayers", 100);
        status.addProperty("serverName", "FireWorld");
        status.addProperty("ip", "play.fireworld.online");
        return gson.toJson(status);
    }

    // --- Settings ---

    public String getSettings() {
        JsonObject settings = new JsonObject();
        settings.addProperty("gameDir", launcher.getGameDir() != null ? launcher.getGameDir() : "");
        settings.addProperty("maxMemory", launcher.getMaxMemory() != null ? launcher.getMaxMemory() : "4G");
        settings.addProperty("useCustomJava", launcher.isUseCustomJava());
        settings.addProperty("javaPath", launcher.getJavaPath() != null ? launcher.getJavaPath() : "");
        settings.addProperty("extraJvmArgs", launcher.getExtraJvmArgs() != null ? launcher.getExtraJvmArgs() : "");
        settings.addProperty("selectedVersion", launcher.getSelectedVersion() != null ? launcher.getSelectedVersion() : "Pura Lite");
        settings.addProperty("authMode", launcher.getAuthMode() != null ? launcher.getAuthMode().name() : "OFFLINE");
        settings.addProperty("offlineUsername", launcher.getOfflineUsername() != null ? launcher.getOfflineUsername() : "");
        return gson.toJson(settings);
    }

    public boolean saveSettings(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String gameDir = obj.has("gameDir") ? obj.get("gameDir").getAsString() : launcher.getGameDir();
            String maxMemory = obj.has("maxMemory") ? obj.get("maxMemory").getAsString() : launcher.getMaxMemory();
            boolean useCustomJava = obj.has("useCustomJava") ? obj.get("useCustomJava").getAsBoolean() : launcher.isUseCustomJava();
            String javaPath = obj.has("javaPath") ? obj.get("javaPath").getAsString() : launcher.getJavaPath();
            String extraJvmArgs = obj.has("extraJvmArgs") ? obj.get("extraJvmArgs").getAsString() : launcher.getExtraJvmArgs();

            if (obj.has("selectedVersion")) {
                launcher.setSelectedVersion(obj.get("selectedVersion").getAsString());
            }
            if (obj.has("offlineUsername")) {
                launcher.setOfflineUsername(obj.get("offlineUsername").getAsString());
            }

            launcher.applyLauncherSettings(gameDir, maxMemory, useCustomJava, javaPath, extraJvmArgs);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            if (ui != null) {
                ui.showError("Ошибка настроек", e.getMessage());
            }
            return false;
        }
    }

    public boolean applyLauncherSettings(String json) {
        return saveSettings(json);
    }

    // --- Version Management ---

    public String getSelectedVersion() {
        return launcher.getSelectedVersion();
    }

    public void setSelectedVersion(String version) {
        if (version != null && !version.isBlank()) {
            launcher.setSelectedVersion(version);
            ConfigManager config = launcher.getConfig();
            if (config != null) {
                config.setSelectedVersion(version);
                config.save();
            }
        }
    }

    public String getAvailableVersions() {
        return gson.toJson(LauncherConstants.AVAILABLE_VERSIONS);
    }

    // --- Authentication & Session ---

    public void logout() {
        if (launcher.getAuthMode() == MinecraftLauncher.AuthMode.GREYTON) {
            launcher.logoutGreyton();
        } else if (launcher.getAuthMode() == MinecraftLauncher.AuthMode.MICROSOFT) {
            launcher.logoutMicrosoft();
        } else {
            launcher.setCurrentSession(null);
            launcher.setOfflineUsername("");
            ConfigManager config = launcher.getConfig();
            if (config != null) {
                config.setOfflineUsername("");
                config.save();
            }
            if (ui != null) {
                ui.updateProfilePanel();
            }
        }
    }

    public void setOfflineUsername(String name) {
        launcher.setOfflineUsername(name);
        launcher.setAuthMode(MinecraftLauncher.AuthMode.OFFLINE);
        ConfigManager config = launcher.getConfig();
        if (config != null) {
            config.setOfflineUsername(name);
            config.setAuthMode(MinecraftLauncher.AuthMode.OFFLINE);
            config.save();
        }
        if (ui != null) {
            ui.updateProfilePanel();
        }
    }

    public void openGameDir() {
        try {
            File dir = new File(launcher.getGameDir());
            if (dir.exists() && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void initiateAuthFlow() {
        launcher.initiateAuthFlow();
    }
}
