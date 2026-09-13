package me.mensem.minecraftlauncher;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.google.gson.JsonObject;

public class GreytonAuthHelper {
    private final HttpClientWrapper http;
    private final ConfigManager config;
    private final Consumer<String> notificationConsumer;

    public GreytonAuthHelper(
            HttpClientWrapper http,
            ConfigManager config,
            Consumer<String> notificationConsumer
    ) {
        this.http = http;
        this.config = config;
        this.notificationConsumer = notificationConsumer;
    }

    private void updateNotification(String text) {
        if (notificationConsumer != null) {
            notificationConsumer.accept(text);
        }
    }

    public MinecraftLauncher.AccountSession exchangeCallbackCode(String code, String state) throws Exception {
        updateNotification("Завершаем авторизацию через сайт...");
        
        String url = LauncherConstants.GREYTON_LAUNCHER_AUTH_CALLBACK_URL + 
                     "?code=" + LauncherUtils.url(code) + 
                     "&state=" + LauncherUtils.url(state);
        
        HttpClientWrapper.HttpResult result = http.getJson(url, new LinkedHashMap<>());
        
        if (result == null || result.statusCode != 200 || result.json == null) {
            throw new Exception(extractError(result, "Ошибка при обмене кода авторизации."));
        }
        
        String token = getRequiredString(result.json, "token");
        JsonObject user = result.json.getAsJsonObject("user");
        
        if (user == null) {
            throw new Exception("Greyton Core не вернул данные пользователя.");
        }
        
        config.setGreytonAuthToken(token);
        config.save();
        
        updateNotification("Авторизация через сайт успешна.");
        return sessionFromUser(user, token);
    }

    public MinecraftLauncher.AccountSession authenticate(boolean allowInteractive) throws Exception {
        String token = config.getGreytonAuthToken();

        if (token == null || token.isBlank()) {
            throw new Exception(
                    allowInteractive
                            ? "Введите данные Greyton Core."
                            : "Сначала войдите в Greyton Core."
            );
        }

        updateNotification("Проверяем сессию Greyton Core...");

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token);

        HttpClientWrapper.HttpResult result =
                http.getJson(
                        LauncherConstants.GREYTON_LAUNCHER_ME_URL,
                        headers
                );

        if (result == null || result.statusCode != 200 || result.json == null) {
            config.setGreytonAuthToken("");
            config.save();

            throw new Exception(
                    extractError(
                            result,
                            "Сессия Greyton Core истекла. Войдите заново."
                    )
            );
        }

        JsonObject user = result.json.getAsJsonObject("user");

        if (user == null) {
            throw new Exception(
                    "Greyton Core не вернул данные пользователя."
            );
        }

        return sessionFromUser(user, token);
    }

    /**
     * Авторизация через официальный launcher endpoint Greyton Core.
     *
     * Greyton Core сам:
     *  - ищет пользователя по email/nickname;
     *  - проверяет password_hash через bcrypt;
     *  - обновляет last_login;
     *  - создаёт настоящий launcher token;
     *  - возвращает данные пользователя.
     */
    public MinecraftLauncher.AccountSession login(
            String identity,
            String password
    ) throws Exception {

        identity = identity == null ? "" : identity.trim();
        password = password == null ? "" : password;

        if (identity.isBlank() || password.isBlank()) {
            throw new Exception(
                    "Введите nickname/email и пароль."
            );
        }

        updateNotification(
                "Подключаемся к Greyton Core..."
        );

        JsonObject payload = new JsonObject();
        payload.addProperty("identity", identity);
        payload.addProperty("password", password);

        HttpClientWrapper.HttpResult result =
                http.postJson(
                        LauncherConstants.GREYTON_LAUNCHER_LOGIN_URL,
                        payload
                );

        if (result == null) {
            throw new Exception(
                    "Не удалось подключиться к Greyton Core."
            );
        }

        if (result.statusCode != 200 || result.json == null) {
            throw new Exception(
                    extractError(
                            result,
                            "Неверный логин или пароль."
                    )
            );
        }

        String token = getRequiredString(
                result.json,
                "token"
        );

        JsonObject user =
                result.json.getAsJsonObject("user");

        if (user == null) {
            throw new Exception(
                    "Greyton Core не вернул данные пользователя."
            );
        }

        config.setGreytonAuthToken(token);
        config.setGreytonIdentity(identity);
        config.save();

        updateNotification(
                "Авторизация успешна."
        );

        return sessionFromUser(
                user,
                token
        );
    }

    private MinecraftLauncher.AccountSession sessionFromUser(
            JsonObject user,
            String token
    ) throws Exception {

        String nickname =
                getRequiredString(user, "nickname");

        String skinUrl =
                getOptionalString(user, "skinUrl");

        if (!skinUrl.isBlank()
                && skinUrl.startsWith("/")) {

            skinUrl =
                    LauncherConstants.GREYTON_API_BASE
                            + skinUrl;
        }

        config.setGreytonSkinUrl(skinUrl);

        return new MinecraftLauncher.AccountSession(
                MinecraftLauncher.AuthMode.GREYTON,
                nickname,
                LauncherUtils.generateOfflineUUID(nickname),
                token,
                "legacy",
                "",
                skinUrl
        );
    }

    public void createJoinGrant(
            MinecraftLauncher.AccountSession session
    ) throws Exception {

        if (session == null
                || session.mode != MinecraftLauncher.AuthMode.GREYTON) {
            return;
        }

        String token = session.accessToken;

        if (token == null || token.isBlank()) {
            token = config.getGreytonAuthToken();
        }

        if (token == null || token.isBlank()) {
            throw new Exception(
                    "Сначала войдите в Greyton Core."
            );
        }

        Map<String, String> headers =
                new LinkedHashMap<>();

        headers.put(
                "Authorization",
                "Bearer " + token
        );

        updateNotification(
                "Проверяем доступ Greyton Core..."
        );

        HttpClientWrapper.HttpResult result =
                http.postJson(
                        LauncherConstants.GREYTON_LAUNCHER_PRESENCE_URL,
                        new JsonObject(),
                        headers
                );

        if (result != null
                && result.statusCode >= 200
                && result.statusCode < 300) {
            return;
        }

        throw new Exception(
                extractError(
                        result,
                        "Не удалось создать launcher-session Greyton. HTTP "
                                + (result == null
                                ? "?"
                                : result.statusCode)
                )
        );
    }

    public MinecraftLauncher.AccountSession withFreshPlayToken(
            MinecraftLauncher.AccountSession session
    ) throws Exception {

        if (session == null
                || session.mode != MinecraftLauncher.AuthMode.GREYTON) {
            return session;
        }

        String token = session.accessToken;

        if (token == null || token.isBlank()) {
            token = config.getGreytonAuthToken();
        }

        if (token == null || token.isBlank()) {
            throw new Exception(
                    "Сначала войдите в Greyton Core."
            );
        }

        Map<String, String> headers =
                new LinkedHashMap<>();

        headers.put(
                "Authorization",
                "Bearer " + token
        );

        updateNotification(
                "Получаем временный токен запуска Greyton Core..."
        );

        HttpClientWrapper.HttpResult result =
                http.postJson(
                        LauncherConstants.GREYTON_LAUNCHER_PLAY_TOKEN_URL,
                        new JsonObject(),
                        headers
                );

        if (result == null
                || result.statusCode < 200
                || result.statusCode >= 300
                || result.json == null) {

            throw new Exception(
                    extractError(
                            result,
                            "Не удалось получить play-token Greyton Core."
                    )
            );
        }

        JsonObject playToken =
                result.json.getAsJsonObject("playToken");

        String value =
                getRequiredString(
                        playToken,
                        "token"
                );

        return new MinecraftLauncher.AccountSession(
                session.mode,
                session.username,
                session.uuid,
                session.accessToken,
                session.userType,
                session.xuid,
                session.skinUrl,
                value
        );
    }

    private String getOptionalString(
            JsonObject json,
            String key
    ) {
        if (json == null
                || !json.has(key)
                || json.get(key).isJsonNull()) {
            return "";
        }

        String value =
                json.get(key).getAsString();

        return value == null
                ? ""
                : value.trim();
    }

    private String getRequiredString(
            JsonObject json,
            String key
    ) throws Exception {

        if (json == null
                || !json.has(key)
                || json.get(key).isJsonNull()) {

            throw new Exception(
                    "Greyton response is missing field: " + key
            );
        }

        String value =
                json.get(key).getAsString();

        if (value == null || value.isBlank()) {
            throw new Exception(
                    "Greyton response is missing field: " + key
            );
        }

        return value;
    }

    private String extractError(
            HttpClientWrapper.HttpResult result,
            String fallback
    ) {
        if (result == null) {
            return LauncherUtils.readableText(
                    fallback
            );
        }

        if (result.json != null
                && result.json.has("error")
                && !result.json.get("error").isJsonNull()) {

            String error =
                    result.json.get("error").getAsString();

            if (error != null && !error.isBlank()) {
                return LauncherUtils.readableText(
                        error
                );
            }
        }

        if (result.body != null
                && !result.body.isBlank()) {

            return LauncherUtils.readableText(
                    result.body
            );
        }

        return LauncherUtils.readableText(
                fallback
        );
    }
}