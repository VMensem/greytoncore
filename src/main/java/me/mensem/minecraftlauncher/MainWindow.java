package me.mensem.minecraftlauncher;

import com.google.gson.Gson;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.*;
import javafx.scene.web.WebView;
import javafx.scene.web.WebEngine;
import netscape.javascript.JSObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;

public class MainWindow {
    private final MinecraftLauncher launcher;
    private StackPane root;
    private WebView webView;
    private WebEngine webEngine;
    private LauncherBridge bridge;
    private final Gson gson = new Gson();

    public MainWindow(MinecraftLauncher launcher) {
        this.launcher = launcher;
    }

    public void setup(Stage stage) {
        stage.initStyle(StageStyle.UNDECORATED);

        // Pre-register fonts in JavaFX font engine
        loadMinecraftFont();

        root = new StackPane();
        root.getStyleClass().add("app-root");
        
        webView = new WebView();
        webEngine = webView.getEngine();
        
        bridge = new LauncherBridge(this.launcher, this);

        // Enable JS bridge
        webEngine.getLoadWorker().stateProperty().addListener((obs, old, newVal) -> {
            if (newVal == javafx.concurrent.Worker.State.SUCCEEDED) {
                try {
                    JSObject window = (JSObject) webEngine.executeScript("window");
                    window.setMember("launcher", bridge);
                    window.setMember("LauncherBridge", bridge);
                    
                    if (launcher.isDevMode()) {
                        webEngine.executeScript("document.body.classList.remove('is-auth'); const authPage = document.getElementById('auth'); const homePage = document.getElementById('home'); if (authPage) authPage.classList.remove('active'); if (homePage) homePage.classList.add('active');");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        // Load the HTML UI safely
        String htmlUrl = resolveIndexHtmlUrl();
        webEngine.load(htmlUrl);
        
        root.getChildren().add(webView);

        Scene scene = new Scene(root, 1280, 720);
        
        stage.setTitle("Pura Launcher");
        try {
            java.net.URL iconUrl = getClass().getResource("/web/assets/logo2.ico");
            if (iconUrl != null) {
                stage.getIcons().add(new javafx.scene.image.Image(iconUrl.toExternalForm()));
            } else {
                System.out.println("DEBUG: Icon not found at /web/assets/logo2.ico");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        stage.setScene(scene);
        stage.show();
    }

    private void loadMinecraftFont() {
        try (InputStream is = getClass().getResourceAsStream("/web/fonts/minecraft.ttf")) {
            if (is != null) {
                javafx.scene.text.Font.loadFont(is, 14);
            }
        } catch (Exception ignored) {}

        try (InputStream is = getClass().getResourceAsStream("/web/minecraft.ttf")) {
            if (is != null) {
                javafx.scene.text.Font.loadFont(is, 14);
            }
        } catch (Exception ignored) {}
    }

    private String resolveIndexHtmlUrl() {
        try {
            URL resUrl = getClass().getResource("/web/base.html");
            if (resUrl == null) {
                return "";
            }

            if ("file".equalsIgnoreCase(resUrl.getProtocol())) {
                // Running from IDE or target/classes: load directly from file URL
                return resUrl.toExternalForm();
            }

            // Running from JAR or Launch4j EXE: unpack web resources to local app data cache
            // to ensure CSS, JS, fonts and images load without jar: stream restrictions
            File targetDir = getWebCacheDir();
            unpackWebResources(targetDir);

            File indexFile = new File(targetDir, "base.html");
            if (indexFile.exists()) {
                return indexFile.toURI().toURL().toExternalForm();
            }
            return resUrl.toExternalForm();
        } catch (Exception e) {
            e.printStackTrace();
            URL fallback = getClass().getResource("/web/base.html");
            return fallback != null ? fallback.toExternalForm() : "";
        }
    }

    private static File getWebCacheDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return new File(localAppData, "Pura Launcher/web");
        }
        return new File(System.getProperty("user.home"), ".pura-launcher/web");
    }

    private void unpackWebResources(File targetDir) {
        try {
            targetDir.mkdirs();

            java.security.CodeSource codeSource = getClass().getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                File jarOrExe = new File(codeSource.getLocation().toURI());
                if (jarOrExe.isFile()) {
                    try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jarOrExe)) {
                        java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                        while (entries.hasMoreElements()) {
                            java.util.zip.ZipEntry entry = entries.nextElement();
                            String name = entry.getName();
                            if (name.startsWith("web/") && !entry.isDirectory()) {
                                String relativeName = name.substring(4);
                                File destFile = new File(targetDir, relativeName);
                                destFile.getParentFile().mkdirs();
                                if (!destFile.exists() || destFile.length() != entry.getSize() || destFile.lastModified() < entry.getTime()) {
                                    try (InputStream in = zip.getInputStream(entry);
                                         OutputStream out = new java.io.FileOutputStream(destFile)) {
                                        in.transferTo(out);
                                    }
                                }
                            }
                        }
                        return;
                    } catch (Exception ignored) {
                        // Fallback below
                    }
                }
            }

            String[] resourcePaths = {
                    "base.html",
                    "minecraft.ttf",
                    "fonts/minecraft.ttf",
                    "css/style.css",
                    "js/app.js",
                    "assets/play.png",
                    "assets/Pura.png",
                    "assets/logo2.png",
                    "assets/logo.jpg",
                    "assets/background.jpg",
                    "assets/background2.png",
                    "assets/background3.png"
            };

            for (String path : resourcePaths) {
                try (InputStream in = getClass().getResourceAsStream("/web/" + path)) {
                    if (in != null) {
                        File dest = new File(targetDir, path);
                        dest.getParentFile().mkdirs();
                        try (OutputStream out = new java.io.FileOutputStream(dest)) {
                            in.transferTo(out);
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void updateNotification(String msg) {
        Platform.runLater(() -> {
            if (webEngine != null) {
                try {
                    webEngine.executeScript("window.PuraLauncher && window.PuraLauncher.onDownloadProgress(null, " + gson.toJson(msg) + ");");
                } catch (Exception ignored) {}
            }
        });
    }

    public void setProgressVisible(boolean visible) {
        Platform.runLater(() -> {
            if (webEngine != null) {
                try {
                    if (!visible) {
                        webEngine.executeScript("window.PuraLauncher && window.PuraLauncher.onDownloadComplete();");
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    public void updateProgress(double progress) {
        Platform.runLater(() -> {
            if (webEngine != null) {
                try {
                    webEngine.executeScript("window.PuraLauncher && window.PuraLauncher.onDownloadProgress(" + (progress * 100.0) + ");");
                } catch (Exception ignored) {}
            }
        });
    }

    public void setPlayButtonEnabled(boolean enabled) {
        Platform.runLater(() -> {
            if (webEngine != null) {
                try {
                    webEngine.executeScript("window.PuraApp && window.PuraApp.setLoading(" + (!enabled) + ");");
                } catch (Exception ignored) {}
            }
        });
    }

    public void showError(String title, String message) {
        Platform.runLater(() -> {
            if (webEngine != null) {
                try {
                    String fullMsg = (title != null && !title.isBlank() ? title + ": " : "") + (message != null ? message : "");
                    webEngine.executeScript("window.PuraLauncher && window.PuraLauncher.onError(" + gson.toJson(fullMsg) + ");");
                } catch (Exception ignored) {}
            }
        });
    }

    public void hideNotification() {}

    public void updateProfilePanel() {
        Platform.runLater(() -> {
            if (webEngine != null && bridge != null) {
                try {
                    webEngine.executeScript("window.PuraLauncher && window.PuraLauncher.onProfileUpdate(" + bridge.getProfile() + ");");
                } catch (Exception ignored) {}
            }
        });
    }

    public void showInfo(String title, String message) {
        Platform.runLater(() -> {
            if (webEngine != null) {
                try {
                    String fullMsg = (title != null && !title.isBlank() ? title + ": " : "") + (message != null ? message : "");
                    webEngine.executeScript("window.PuraLauncher && window.PuraLauncher.showToast(" + gson.toJson(fullMsg) + ", 'info');");
                } catch (Exception ignored) {}
            }
        });
    }

    public WebEngine getWebEngine() {
        return webEngine;
    }
}

