package me.mensem.minecraftlauncher;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import javafx.application.Platform;

import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class MicrosoftAuthHelper {
    private final HttpClientWrapper http;
    private final ConfigManager config;
    private final Consumer<String> notificationConsumer;

    public MicrosoftAuthHelper(HttpClientWrapper http, ConfigManager config, Consumer<String> notificationConsumer) {
        this.http = http;
        this.config = config;
        this.notificationConsumer = notificationConsumer;
    }

    private void updateNotification(String text) {
        if (notificationConsumer != null) notificationConsumer.accept(text);
    }

    public MinecraftLauncher.AccountSession authenticate(boolean allowInteractive) throws Exception {
        String refreshToken = config.getMicrosoftRefreshToken();
        if (refreshToken != null && !refreshToken.isBlank()) {
            try {
                updateNotification("Обновляем Microsoft-сессию...");
                String accessToken = refreshMicrosoftAccessToken();
                return finishMinecraftLogin(accessToken);
            } catch (Exception refreshError) {
                config.setMicrosoftRefreshToken("");
                config.save();
                if (!allowInteractive) throw refreshError;
            }
        }
        if (!allowInteractive) throw new Exception("Нужен вход Microsoft.");
        DeviceCodeInfo authCode = requestDeviceCode();
        String microsoftAccessToken = redeemAuthorizationCode(authCode);
        return finishMinecraftLogin(microsoftAccessToken);
    }

    private DeviceCodeInfo requestDeviceCode() throws Exception {
        String state = LauncherUtils.randomUrlToken(24);
        String verifier = LauncherUtils.randomUrlToken(64);
        String challenge = LauncherUtils.buildCodeChallenge(verifier);
        String[] resultHolder = new String[2];
        CountDownLatch latch = new CountDownLatch(1);
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", LauncherConstants.MICROSOFT_REDIRECT_PORT), 0);
        } catch (BindException e) {
            throw new Exception("Порт " + LauncherConstants.MICROSOFT_REDIRECT_PORT + " занят.");
        }

        String redirectUri = LauncherConstants.MICROSOFT_REDIRECT_URI;
        server.createContext("/", exchange -> handleAuthCallback(exchange, state, resultHolder, latch));
        server.start();

        String authUrl = LauncherConstants.MICROSOFT_AUTHORIZE_URL
                + "?client_id=" + LauncherUtils.url(LauncherConstants.MICROSOFT_CLIENT_ID)
                + "&response_type=code"
                + "&redirect_uri=" + LauncherUtils.url(redirectUri)
                + "&response_mode=query"
                + "&scope=" + LauncherUtils.url("openid profile offline_access " + LauncherConstants.XBOX_SCOPE)
                + "&code_challenge=" + LauncherUtils.url(challenge)
                + "&code_challenge_method=S256"
                + "&state=" + LauncherUtils.url(state)
                + "&prompt=select_account";

        Platform.runLater(() -> LauncherUtils.openBrowser(authUrl));
        updateNotification("Ожидаем вход через браузер...");

        boolean completed = latch.await(5, TimeUnit.MINUTES);
        server.stop(0);

        if (!completed) throw new Exception("Время ожидания истекло.");
        if (resultHolder[1] != null) throw new Exception(resultHolder[1]);
        if (resultHolder[0] == null || resultHolder[0].isBlank()) throw new Exception("Microsoft не вернул код.");

        return new DeviceCodeInfo(resultHolder[0], state, redirectUri, verifier);
    }

    private void handleAuthCallback(HttpExchange exchange, String expectedState, String[] resultHolder, CountDownLatch latch) throws IOException {
        try {
            Map<String, String> query = LauncherUtils.parseQuery(exchange.getRequestURI().getRawQuery());
            String responseHtml;

            if (query.containsKey("error")) {
                resultHolder[1] = "Microsoft вход отменен: " + query.get("error");
                responseHtml = LauncherUtils.successPage("Вход отменен", "Закройте окно и попробуйте снова.");
            } else if (!Objects.equals(expectedState, query.get("state"))) {
                resultHolder[1] = "Неверный state. Авторизация отклонена.";
                responseHtml = LauncherUtils.successPage("Ошибка", "Проверка безопасности не пройдена.");
            } else {
                resultHolder[0] = query.get("code");
                responseHtml = LauncherUtils.successPage("Вход завершен", "Можно закрыть окно и вернуться в лаунчер.");
            }

            byte[] bytes = responseHtml.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);

            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        } finally {
            latch.countDown();
            exchange.close();
        }
    }

    private String redeemAuthorizationCode(DeviceCodeInfo authCode) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", LauncherConstants.MICROSOFT_CLIENT_ID);
        form.put("grant_type", "authorization_code");
        form.put("code", authCode.code);
        form.put("redirect_uri", authCode.redirectUri);
        form.put("scope", "openid profile offline_access " + LauncherConstants.XBOX_SCOPE);
        form.put("code_verifier", authCode.verifier);

        HttpClientWrapper.HttpResult result = http.postForm(LauncherConstants.MICROSOFT_TOKEN_URL, form);

        if (result.statusCode != 200) {
            throw new Exception("Ошибка обмена кода: " + result.body);
        }

        JsonObject json = result.json;
        String refreshToken = getOptionalString(json, "refresh_token");

        if (refreshToken != null && !refreshToken.isBlank()) {
            config.setMicrosoftRefreshToken(refreshToken);
            config.save();
        }

        return getRequiredString(json, "access_token");
    }

    private String refreshMicrosoftAccessToken() throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", LauncherConstants.MICROSOFT_CLIENT_ID);
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", config.getMicrosoftRefreshToken());
        form.put("scope", "openid profile offline_access " + LauncherConstants.XBOX_SCOPE);

        HttpClientWrapper.HttpResult result = http.postForm(LauncherConstants.MICROSOFT_TOKEN_URL, form);

        if (result.statusCode != 200) {
            throw new Exception("Не удалось обновить токен: " + result.body);
        }

        JsonObject json = result.json;
        String newRefreshToken = getOptionalString(json, "refresh_token");

        if (newRefreshToken != null && !newRefreshToken.isBlank()) {
            config.setMicrosoftRefreshToken(newRefreshToken);
            config.save();
        }

        return getRequiredString(json, "access_token");
    }

    private MinecraftLauncher.AccountSession finishMinecraftLogin(String microsoftAccessToken) throws Exception {
        updateNotification("Xbox Live...");
        JsonObject xbl = authenticateXboxLive(microsoftAccessToken);
        String xblToken = getRequiredString(xbl, "Token");
        String userHash = extractUserHash(xbl);

        updateNotification("XSTS...");
        JsonObject xsts = authorizeXsts(xblToken);
        String xstsToken = getRequiredString(xsts, "Token");

        updateNotification("Minecraft...");
        String minecraftToken = authenticateMinecraft(userHash, xstsToken);

        updateNotification("Профиль...");
        JsonObject profile = fetchMinecraftProfile(minecraftToken);
        String profileName = getRequiredString(profile, "name");
        String profileUuid = getRequiredString(profile, "id");

        return new MinecraftLauncher.AccountSession(
                MinecraftLauncher.AuthMode.MICROSOFT,
                profileName,
                profileUuid,
                minecraftToken,
                "msa",
                userHash
        );
    }

    private JsonObject authenticateXboxLive(String microsoftAccessToken) throws Exception {
        JsonObject properties = new JsonObject();
        properties.addProperty("AuthMethod", "RPS");
        properties.addProperty("SiteName", "user.auth.xboxlive.com");
        properties.addProperty("RpsTicket", "d=" + microsoftAccessToken);

        JsonObject payload = new JsonObject();
        payload.add("Properties", properties);
        payload.addProperty("RelyingParty", "http://auth.xboxlive.com");
        payload.addProperty("TokenType", "JWT");

        HttpClientWrapper.HttpResult result = http.postJson(LauncherConstants.XBOX_USER_AUTH_URL, payload);

        if (result.statusCode != 200) {
            throw new Exception("Xbox Live ошибка: " + result.body);
        }

        return result.json;
    }

    private JsonObject authorizeXsts(String xblToken) throws Exception {
        JsonArray userTokens = new JsonArray();
        userTokens.add(xblToken);

        JsonObject properties = new JsonObject();
        properties.addProperty("SandboxId", "RETAIL");
        properties.add("UserTokens", userTokens);

        JsonObject payload = new JsonObject();
        payload.add("Properties", properties);
        payload.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        payload.addProperty("TokenType", "JWT");

        HttpClientWrapper.HttpResult result = http.postJson(LauncherConstants.XBOX_XSTS_URL, payload);

        if (result.statusCode != 200) {
            throw new Exception("XSTS ошибка: " + formatXstsError(result));
        }

        return result.json;
    }

    private String authenticateMinecraft(String userHash, String xstsToken) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);

        HttpClientWrapper.HttpResult result = http.postJson(LauncherConstants.MINECRAFT_LOGIN_URL, payload);

        System.out.println("Minecraft status: " + result.statusCode);
        System.out.println("Minecraft body: " + result.body);

        if (result.statusCode != 200) {
            throw new Exception("Minecraft ошибка " + result.statusCode + ": " + result.body);
        }

        return getRequiredString(result.json, "access_token");
    }

    private JsonObject fetchMinecraftProfile(String minecraftToken) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + minecraftToken);

        HttpClientWrapper.HttpResult result = http.getJson(LauncherConstants.MINECRAFT_PROFILE_URL, headers);

        if (result.statusCode != 200) {
            throw new Exception("Профиль не найден: " + result.body);
        }

        return result.json;
    }

    private String extractUserHash(JsonObject json) throws Exception {
        JsonArray xui = json.getAsJsonObject("DisplayClaims").getAsJsonArray("xui");
        if (xui == null || xui.isEmpty()) throw new Exception("Нет user hash.");
        return xui.get(0).getAsJsonObject().get("uhs").getAsString();
    }

    private String formatXstsError(HttpClientWrapper.HttpResult result) {
        if (result.json == null || !result.json.has("XErr")) return result.body;

        long xErr = result.json.get("XErr").getAsLong();

        if (xErr == 2148916233L) return "Нет профиля Xbox Live.";
        if (xErr == 2148916238L) return "Детский аккаунт требует одобрения.";
        if (xErr == 2148916227L) return "Аккаунт заблокирован в Xbox.";

        return result.body;
    }

    private String getRequiredString(JsonObject json, String key) throws Exception {
        String value = getOptionalString(json, key);
        if (value == null || value.isBlank()) throw new Exception("Нет поля " + key);
        return value;
    }

    private String getOptionalString(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) return "";
        return json.get(key).getAsString();
    }

    private static class DeviceCodeInfo {
        final String code;
        final String state;
        final String redirectUri;
        final String verifier;

        DeviceCodeInfo(String code, String state, String redirectUri, String verifier) {
            this.code = code;
            this.state = state;
            this.redirectUri = redirectUri;
            this.verifier = verifier;
        }
    }

    public void forceLoginNewAccount() {
        config.setMicrosoftRefreshToken("");
        config.save();
    }
}
