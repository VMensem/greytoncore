package me.mensem.minecraftlauncher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class VersionManifestHelper {

    public String getRealVersionId(String selectedVersion) {
        if (selectedVersion.startsWith("Pura")) {
            return "26.2";
        }

        if (selectedVersion.equals("26.2")) {
            return "26.2";
        }

        return selectedVersion;
    }

    public File prepareLocalManifest(File manifestFile) throws Exception {
        if (manifestFile.exists()) {
            return manifestFile;
        }

        try (InputStream in = getClass().getResourceAsStream("/version_manifest_v2.json")) {
            if (in == null) {
                throw new RuntimeException("version_manifest_v2.json not found in resources");
            }

            manifestFile.getParentFile().mkdirs();
            LauncherUtils.copyResourceAtomic(in, manifestFile);

            System.out.println("DEBUG: Manifest loaded from resources");
        }

        return manifestFile;
    }

    public String getVersionJsonUrl(File manifestFile, String version) throws Exception {
        manifestFile = prepareLocalManifest(manifestFile);

        String manifest = Files.readString(
                manifestFile.toPath(),
                StandardCharsets.UTF_8
        );

        JsonObject root = JsonParser.parseString(manifest).getAsJsonObject();
        var versions = root.getAsJsonArray("versions");

        for (var elem : versions) {
            JsonObject ver = elem.getAsJsonObject();

            if (ver.get("id").getAsString().equals(version)) {
                return ver.get("url").getAsString();
            }
        }

        throw new Exception("Версия не найдена в манифесте: " + version);
    }

    public String getClientDownloadUrl(String jsonContent) {
        try {
            JsonObject root = JsonParser.parseString(jsonContent).getAsJsonObject();
            JsonObject downloads = root.getAsJsonObject("downloads");

            if (downloads != null && downloads.has("client")) {
                JsonObject client = downloads.getAsJsonObject("client");

                if (client != null && client.has("url")) {
                    return client.get("url").getAsString();
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    public String getAssetIndexUrl(String jsonContent) {
        try {
            JsonObject root = JsonParser.parseString(jsonContent).getAsJsonObject();
            JsonObject assetIndex = root.getAsJsonObject("assetIndex");

            if (assetIndex != null && assetIndex.has("url")) {
                return assetIndex.get("url").getAsString();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    public String getAssetIndexId(String jsonContent) {
        try {
            JsonObject root = JsonParser.parseString(jsonContent).getAsJsonObject();
            JsonObject assetIndex = root.getAsJsonObject("assetIndex");

            if (assetIndex != null && assetIndex.has("id")) {
                return assetIndex.get("id").getAsString();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return "32";
    }

    public String getMainClass(String jsonContent, String selectedVersion) {
        if (selectedVersion.contains("Pura")) {
            return "net.fabricmc.loader.impl.launch.knot.KnotClient";
        }

        try {
            JsonObject root = JsonParser.parseString(jsonContent).getAsJsonObject();

            if (root.has("mainClass")) {
                return root.get("mainClass").getAsString();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return "net.minecraft.client.main.Main";
    }
}
