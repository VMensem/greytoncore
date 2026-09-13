package me.mensem.minecraftlauncher;

public final class AuthService {

    private AuthService() {
        // Utility class
    }
    public static MinecraftLauncher.AccountSession authenticate(
            String identity,
            String password
    ) throws Exception {

        throw new UnsupportedOperationException(
                "Direct database authentication is disabled. "
                        + "Use Greyton Core /api/launcher/login."
        );
    }
}