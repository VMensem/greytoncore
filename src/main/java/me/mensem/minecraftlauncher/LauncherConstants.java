package me.mensem.minecraftlauncher;

import java.util.Map;

public final class LauncherConstants {

    private LauncherConstants() {
        // Запрещаем создание экземпляров.
    }

    public static final String LAUNCHER_NAME = "Pura Launcher";
    // Version is managed by Maven and stored in version.txt

    public static final String[] AVAILABLE_VERSIONS = {
            "26.2",
            "Pura Lite",
            "Pura Plus",
            "Pura Ultra"
    };

    // Microsoft OAuth
    public static final String MICROSOFT_CLIENT_ID = "e0ac5455-92c0-4797-aeed-3865534e2dc0";
    public static final int MICROSOFT_REDIRECT_PORT = 1919;
    public static final String MICROSOFT_REDIRECT_URI = "http://localhost:" + MICROSOFT_REDIRECT_PORT;

    public static final String MICROSOFT_AUTHORIZE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
    public static final String MICROSOFT_TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";

    // Xbox Live auth
    public static final String XBOX_USER_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate";
    public static final String XBOX_XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";

    // Minecraft services
    public static final String MINECRAFT_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
    public static final String MINECRAFT_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";

    public static final String XBOX_SCOPE = "XboxLive.signin offline_access";

    // Greyton Core auth (Local GrimSite)
    private static final String GREYTON_PROD_API_BASE = "https://greytoncore.online";
    private static final String GREYTON_DEV_API_BASE = "http://localhost:4187";
    
    // Switch between PROD and DEV here
    public static final String GREYTON_API_BASE = GREYTON_DEV_API_BASE;
    
    public static final String GREYTON_LAUNCHER_CALLBACK_PROTOCOL = "pura-launcher://";
    public static final int IPC_PORT = 1921;
    public static final String GREYTON_LAUNCHER_LOGIN_URL = GREYTON_API_BASE + "/api/launcher/login";
    public static final String GREYTON_LAUNCHER_AUTH_CALLBACK_URL = GREYTON_API_BASE + "/api/launcher/auth/callback";
    public static final String GREYTON_LAUNCHER_ME_URL = GREYTON_API_BASE + "/api/launcher/me";
    public static final String GREYTON_LAUNCHER_PRESENCE_URL = GREYTON_API_BASE + "/api/launcher/presence";
    public static final String GREYTON_LAUNCHER_PLAY_TOKEN_URL = GREYTON_API_BASE + "/api/launcher/play-token";
    public static final String GREYTON_MINECRAFT_HEARTBEAT_URL = GREYTON_API_BASE + "/api/launcher/minecraft-heartbeat";

    // Создает временное разрешение на вход в Minecraft-сервер.
    // Серверный endpoint должен добавить запись в launcher_join_grants.
    public static final String GREYTON_LAUNCHER_JOIN_GRANT_URL = GREYTON_API_BASE + "/api/launcher/join-grant";
    public static final String[] GREYTON_LAUNCHER_JOIN_GRANT_FALLBACK_URLS = {
            GREYTON_API_BASE + "/api/launcher/request-join",
            GREYTON_API_BASE + "/api/launcher/create-grant",
            GREYTON_API_BASE + "/api/launcher/create-join-grant",
            GREYTON_API_BASE + "/api/launcher/request-grant",
            GREYTON_API_BASE + "/api/launcher/grant",
            GREYTON_API_BASE + "/api/launcher/join",
            GREYTON_API_BASE + "/api/launcher/launch",
            GREYTON_API_BASE + "/api/launcher/play"
    };

    // Встроенные модпаки.
    public static final String MODPACK_MANIFEST_URL =
            "https://greytoncore.online/pura/manifest.json";

    public static final Map<String, String> MODPACK_IDS = Map.of(
            "Pura Lite", "PuraLite",
            "Pura Plus", "PuraPlus",
            "Pura Ultra", "PuraUltra"
    );

    // Fabric / библиотеки.
    public static final String FABRIC_LOADER_VERSION = "0.19.5";
    public static final String SPONGE_MIXIN_VERSION = "0.17.3+mixin.0.8.7";
    public static final String ASM_VERSION = "9.8";
    public static final String[] ASM_MODULES = {"asm", "asm-tree", "asm-commons", "asm-analysis", "asm-util"};
}


