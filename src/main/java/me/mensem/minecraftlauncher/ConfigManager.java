package me.mensem.minecraftlauncher;

import java.io.*;
import java.util.Properties;

public class ConfigManager {
    private File configFile;
    private Properties properties;

    private String gameDir;
    private String offlineUsername;
    private String selectedVersion;
    private MinecraftLauncher.AuthMode authMode;
    private String microsoftRefreshToken;
    private String cachedMicrosoftName;
    private String cachedMicrosoftUuid;
    private String greytonAuthToken;
    private String greytonIdentity;
    private String greytonSkinUrl;
    private String maxMemory;
    private boolean maxMemoryUserConfigured;
    private String javaPath;
    private boolean useCustomJava;
    private String extraJvmArgs;
    private LaunchMode launchMode = LaunchMode.FIREWORLD;

    private static final String DEFAULT_GAME_DIR = System.getenv("APPDATA") + "/PuraLauncher";
    private static final String OLD_CONFIG_PATH = System.getenv("APPDATA") + "/PuraLauncher/config.properties";

    public ConfigManager() {
        properties = new Properties();
        load();
    }

    public void load() {
        File defaultConfigFile = new File(DEFAULT_GAME_DIR, "config.properties");
        File oldConfigFile = new File(OLD_CONFIG_PATH);
        if (defaultConfigFile.exists()) {
            try (FileInputStream in = new FileInputStream(defaultConfigFile)) {
                properties.load(in);
            } catch (Exception ignored) {}
        } else if (oldConfigFile.exists()) {
            try (FileInputStream in = new FileInputStream(oldConfigFile)) {
                properties.load(in);
            } catch (Exception ignored) {}
        }
        gameDir = properties.getProperty("gameDir", DEFAULT_GAME_DIR);
        configFile = new File(gameDir, "config.properties");
        offlineUsername = properties.getProperty("offlineUsername", "").trim();
        if ("mensem".equalsIgnoreCase(offlineUsername)) {
            offlineUsername = "";
        }
        String defaultMemory = LauncherUtils.getDefaultRamGb() + "G";
        String savedMemory = properties.getProperty("maxMemory", defaultMemory);
        maxMemoryUserConfigured = Boolean.parseBoolean(properties.getProperty("maxMemoryUserConfigured", "false"));
        if (!maxMemoryUserConfigured && "8G".equalsIgnoreCase(savedMemory)) {
            savedMemory = defaultMemory;
        }
        maxMemory = LauncherUtils.clampRamGb(LauncherUtils.parseRam(savedMemory)) + "G";
        javaPath = properties.getProperty("javaPath", System.getProperty("java.home") + "\\bin\\java.exe");
        useCustomJava = Boolean.parseBoolean(properties.getProperty("useCustomJava", "false"));
        extraJvmArgs = properties.getProperty("extraJvmArgs", "");
        selectedVersion = properties.getProperty("selectedVersion", "Pura Lite");
        String launchModeStr = properties.getProperty("launchMode", LaunchMode.FIREWORLD.name());
        try {
            launchMode = LaunchMode.valueOf(launchModeStr);
        } catch (IllegalArgumentException e) {
            launchMode = LaunchMode.FIREWORLD;
        }
        authMode = MinecraftLauncher.AuthMode.fromConfig(properties.getProperty("authMode", MinecraftLauncher.AuthMode.OFFLINE.name()));
        microsoftRefreshToken = properties.getProperty("microsoftRefreshToken", "");
        cachedMicrosoftName = properties.getProperty("microsoftName", "");
        cachedMicrosoftUuid = properties.getProperty("microsoftUuid", "");
        greytonAuthToken = properties.getProperty("greytonAuthToken", "");
        greytonIdentity = properties.getProperty("greytonIdentity", "");
        greytonSkinUrl = properties.getProperty("greytonSkinUrl", "");
        if (!isAvailableVersion(selectedVersion)) selectedVersion = "Pura Lite";
        properties.setProperty("gameDir", gameDir);
        properties.setProperty("offlineUsername", offlineUsername);
        properties.setProperty("selectedVersion", selectedVersion);
        properties.setProperty("authMode", authMode.name());
        save();
        new File(gameDir).mkdirs();
    }

    public void save() {
        if (configFile == null) configFile = new File(gameDir, "config.properties");
        configFile.getParentFile().mkdirs();
        properties.setProperty("gameDir", gameDir);
        properties.setProperty("offlineUsername", offlineUsername);
        properties.setProperty("selectedVersion", selectedVersion);
        properties.setProperty("maxMemory", maxMemory);
        properties.setProperty("maxMemoryUserConfigured", String.valueOf(maxMemoryUserConfigured));
        properties.setProperty("useCustomJava", String.valueOf(useCustomJava));
        properties.setProperty("javaPath", javaPath);
        properties.setProperty("extraJvmArgs", extraJvmArgs);
        properties.setProperty("authMode", authMode.name());
        if (!microsoftRefreshToken.isBlank()) properties.setProperty("microsoftRefreshToken", microsoftRefreshToken);
        else properties.remove("microsoftRefreshToken");
        if (!cachedMicrosoftName.isBlank()) properties.setProperty("microsoftName", cachedMicrosoftName);
        else properties.remove("microsoftName");
        if (!cachedMicrosoftUuid.isBlank()) properties.setProperty("microsoftUuid", cachedMicrosoftUuid);
        else properties.remove("microsoftUuid");
        if (!greytonAuthToken.isBlank()) properties.setProperty("greytonAuthToken", greytonAuthToken);
        else properties.remove("greytonAuthToken");
        if (!greytonIdentity.isBlank()) properties.setProperty("greytonIdentity", greytonIdentity);
        else properties.remove("greytonIdentity");
        if (!greytonSkinUrl.isBlank()) properties.setProperty("greytonSkinUrl", greytonSkinUrl);
        else properties.remove("greytonSkinUrl");
        properties.setProperty("launchMode", launchMode.name());
        try {
            LauncherUtils.writeBytesAtomic(configFile, out -> properties.store(out, "PuraLauncher Settings"));
        } catch (Exception ignored) {}
    }

    private boolean isAvailableVersion(String version) {
        return version.equals("Pura Lite") || version.equals("Pura Plus") || version.equals("Pura Ultra");
    }

    public LaunchMode getLaunchMode() { return launchMode; }
    public void setLaunchMode(LaunchMode launchMode) { this.launchMode = launchMode; }

    public String getGameDir() { return gameDir; }
    public void setGameDir(String gameDir) {
        this.gameDir = gameDir;
        this.configFile = new File(this.gameDir, "config.properties");
    }
    public String getOfflineUsername() { return offlineUsername; }
    public void setOfflineUsername(String offlineUsername) {
        this.offlineUsername = offlineUsername == null ? "" : offlineUsername.trim();
    }
    public String getSelectedVersion() { return selectedVersion; }
    public void setSelectedVersion(String selectedVersion) { this.selectedVersion = selectedVersion; }
    public MinecraftLauncher.AuthMode getAuthMode() { return authMode; }
    public void setAuthMode(MinecraftLauncher.AuthMode authMode) { this.authMode = authMode; }
    public String getMicrosoftRefreshToken() { return microsoftRefreshToken; }
    public void setMicrosoftRefreshToken(String microsoftRefreshToken) { this.microsoftRefreshToken = microsoftRefreshToken; }
    public String getCachedMicrosoftName() { return cachedMicrosoftName; }
    public void setCachedMicrosoftName(String cachedMicrosoftName) { this.cachedMicrosoftName = cachedMicrosoftName; }
    public String getCachedMicrosoftUuid() { return cachedMicrosoftUuid; }
    public void setCachedMicrosoftUuid(String cachedMicrosoftUuid) { this.cachedMicrosoftUuid = cachedMicrosoftUuid; }
    public String getGreytonAuthToken() { return greytonAuthToken; }
    public void setGreytonAuthToken(String greytonAuthToken) { this.greytonAuthToken = greytonAuthToken == null ? "" : greytonAuthToken; }
    public String getGreytonIdentity() { return greytonIdentity; }
    public void setGreytonIdentity(String greytonIdentity) { this.greytonIdentity = greytonIdentity == null ? "" : greytonIdentity.trim(); }
    public String getGreytonSkinUrl() { return greytonSkinUrl; }
    public void setGreytonSkinUrl(String greytonSkinUrl) { this.greytonSkinUrl = greytonSkinUrl == null ? "" : greytonSkinUrl.trim(); }
    public String getMaxMemory() { return maxMemory; }
    public void setMaxMemory(String maxMemory) {
        this.maxMemory = LauncherUtils.clampRamGb(LauncherUtils.parseRam(maxMemory)) + "G";
        this.maxMemoryUserConfigured = true;
    }
    public String getJavaPath() { return javaPath; }
    public void setJavaPath(String javaPath) { this.javaPath = javaPath; }
    public boolean isUseCustomJava() { return useCustomJava; }
    public void setUseCustomJava(boolean useCustomJava) { this.useCustomJava = useCustomJava; }
    public String getExtraJvmArgs() { return extraJvmArgs; }
    public void setExtraJvmArgs(String extraJvmArgs) { this.extraJvmArgs = extraJvmArgs; }
}
