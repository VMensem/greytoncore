(function () {
"use strict";

function getBridge() {
    return window.launcher || window.LauncherBridge || null;
}

function initAuth() {
    const loginButton = document.getElementById("login-button");

    if (loginButton) {
        loginButton.addEventListener("click", function (event) {
            event.preventDefault();
            const bridge = getBridge();
            if (bridge && typeof bridge.initiateAuthFlow === "function") {
                bridge.initiateAuthFlow();
            } else {
                console.error("LauncherBridge or initiateAuthFlow not found.");
            }
        });
    }
}

window.PuraAuth = {
    init: initAuth
};

if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initAuth);
} else {
    initAuth();
}

})();
