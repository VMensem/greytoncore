/*
 * PuraLauncher Web UI
 * app.js
 *
 * UI layer:
 * - Navigation
 * - Profile dropdown
 * - LauncherBridge integration
 * - Home/Profile/Statistics/News/Settings
 * - Loading overlay
 * - Toast notifications
 * - Modal
 *
 * ВАЖНО:
 * Java остаётся ответственным за:
 * - авторизацию
 * - запуск Minecraft
 * - скачивание файлов
 * - настройки лаунчера
 * - получение данных игрока
 * - native window controls
 *
 * HTML/CSS/JS отвечают только за интерфейс.
 */

(() => {
    "use strict";

    // =========================================================
    // GLOBAL STATE
    // =========================================================

    const state = {
        currentPage: "home",
        loading: false,
        profile: null,
        statistics: null,
        friends: [],
        news: [],
        settings: {},
        selectedVersion: null,
        initialized: false
    };

    // =========================================================
    // DOM HELPERS
    // =========================================================

    const $ = (selector, root = document) => root.querySelector(selector);
    const $$ = (selector, root = document) =>
        Array.from(root.querySelectorAll(selector));

    function byId(id) {
        return document.getElementById(id);
    }

    function setText(id, value) {
        const element = byId(id);

        if (!element) {
            return;
        }

        element.textContent =
            value === null || value === undefined ? "" : String(value);
    }

    function setHTML(id, html) {
        const element = byId(id);

        if (!element) {
            return;
        }

        element.innerHTML = html;
    }

    function showElement(element) {
        if (!element) return;

        element.hidden = false;
        element.classList.remove("hidden");
    }

    function hideElement(element) {
        if (!element) return;

        element.hidden = true;
        element.classList.add("hidden");
    }

    // =========================================================
    // LAUNCHER BRIDGE
    // =========================================================

    function getBridge() {
        if (typeof window.launcher !== "undefined") {
            return window.launcher;
        }

        if (typeof window.LauncherBridge !== "undefined") {
            return window.LauncherBridge;
        }

        return null;
    }

    function hasBridgeMethod(method) {
        const bridge = getBridge();

        return !!(
            bridge &&
            typeof bridge[method] === "function"
        );
    }

    function callBridge(method, ...args) {
        const bridge = getBridge();

        if (!bridge) {
            console.warn(
                `[PuraLauncher] LauncherBridge недоступен: ${method}`
            );

            return null;
        }

        if (typeof bridge[method] !== "function") {
            console.warn(
                `[PuraLauncher] Метод LauncherBridge.${method} отсутствует`
            );

            return null;
        }

        try {
            return bridge[method](...args);
        } catch (error) {
            console.error(
                `[PuraLauncher] Ошибка LauncherBridge.${method}:`,
                error
            );

            return null;
        }
    }

    // =========================================================
    // LOADING
    // =========================================================

    function setLoading(value, text = "Загрузка...") {
        state.loading = Boolean(value);

        const overlay =
            byId("loading-overlay") ||
            $(".loading-overlay");

        if (!overlay) {
            return;
        }

        const textElement =
            byId("loading-text") ||
            $(".loading-text", overlay);

        if (textElement) {
            textElement.textContent = text;
        }

        if (state.loading) {
            showElement(overlay);
            document.body.classList.add("is-loading");
        } else {
            hideElement(overlay);
            document.body.classList.remove("is-loading");
        }
    }

    // =========================================================
    // TOAST
    // =========================================================

    function toast(message, type = "info", duration = 3500) {
        const container =
            byId("toast-container") ||
            $(".toast-container");

        if (!container) {
            console.log(`[Toast/${type}] ${message}`);
            return;
        }

        const element = document.createElement("div");

        element.className = `toast toast-${type}`;

        element.innerHTML = `
            <div class="toast-content">
                <span class="toast-message"></span>
            </div>
        `;

        const messageElement = $(".toast-message", element);

        if (messageElement) {
            messageElement.textContent = message;
        }

        container.appendChild(element);

        requestAnimationFrame(() => {
            element.classList.add("show");
        });

        setTimeout(() => {
            element.classList.remove("show");

            setTimeout(() => {
                element.remove();
            }, 250);
        }, duration);
    }

    // =========================================================
    // MODAL
    // =========================================================

    function openModal(title, content) {
        const modal =
            byId("modal") ||
            $(".modal");

        if (!modal) {
            return;
        }

        const titleElement =
            byId("modal-title") ||
            $(".modal-title", modal);

        const contentElement =
            byId("modal-content") ||
            $(".modal-content", modal);

        if (titleElement) {
            titleElement.textContent = title;
        }

        if (contentElement) {
            contentElement.innerHTML = content;
        }

        showElement(modal);

        requestAnimationFrame(() => {
            modal.classList.add("open");
        });
    }

    function closeModal() {
        const modal =
            byId("modal") ||
            $(".modal");

        if (!modal) {
            return;
        }

        modal.classList.remove("open");

        setTimeout(() => {
            hideElement(modal);
        }, 180);
    }

    // =========================================================
    // NAVIGATION
    // =========================================================

    function normalizePageName(value) {
        if (!value) {
            return "home";
        }

        const aliases = {
            index: "home",
            main: "home",
            главная: "home",

            profile: "profile",
            профиль: "profile",

            statistics: "statistics",
            stats: "statistics",
            статистика: "statistics",

            news: "news",
            новости: "news",

            settings: "settings",
            настройки: "settings",

            support: "support",
            поддержка: "support"
        };

        return aliases[String(value).toLowerCase()] || "home";
    }

    function getPageElement(page) {
        const selectors = [
            `[data-page="${page}"]`,
            `#page-${page}`,
            `.page-${page}`,
            `#${page}`
        ];

        for (const selector of selectors) {
            const element = $(selector);

            if (element) {
                return element;
            }
        }

        return null;
    }

    async function showPage(pageName, updateHash = true) {
        const page = normalizePageName(pageName);
        console.log(`[PuraLauncher] showPage called for: ${pageName}, normalized: ${page}`);
        const mainContent = byId("main-content");

        if (!mainContent) {
            console.warn("[PuraLauncher] Элемент main-content не найден");
            return;
        }

        // Try Java Bridge first (more reliable for file:// protocol)
        const bridge = getBridge();
        console.log(`[PuraLauncher] Bridge available: ${!!bridge}`);
        
        if (bridge && typeof bridge.loadPageContent === "function") {
            console.log(`[PuraLauncher] Loading via bridge: ${page}`);
            const html = bridge.loadPageContent(page);
            console.log(`[PuraLauncher] Bridge returned html: ${!!html}`);
            if (html) {
                mainContent.innerHTML = html;
            } else {
                mainContent.innerHTML = `<div class="error">Ошибка загрузки страницы (bridge returned null/empty)</div>`;
            }
        } else {
            // Fallback to fetch
            try {
                const url = `pages/${page}.html`;
                console.log(`[PuraLauncher] Attempting fetch: ${url}`);
                const response = await fetch(url);
                if (!response.ok) throw new Error(`Status: ${response.status}`);
                const html = await response.text();
                mainContent.innerHTML = html;
            } catch (error) {
                console.error(`[PuraLauncher] Error during fetch:`, error);
                mainContent.innerHTML = `<div class="error">Ошибка загрузки страницы (fetch fail: ${error.message})</div>`;
            }
        }

        const titles = {
            home: "Главная",
            profile: "Профиль",
            statistics: "Статистика",
            news: "Новости",
            settings: "Настройки"
        };
        setText("page-title-text", titles[page] || "Главная");

        $$("[data-page-link], [data-nav]").forEach(link => {
            const linkPage = normalizePageName(link.dataset.pageLink || link.dataset.nav);
            link.classList.toggle("active", linkPage === page);
        });

        state.currentPage = page;

        if (updateHash) {
            try { history.replaceState(null, "", `#${page}`); } catch (_) {}
        }

        window.scrollTo({ top: 0, behavior: "smooth" });
        closeProfileDropdown();

        if (page === "profile") loadProfile();
        if (page === "statistics") loadStatistics();
        if (page === "news") loadNews();
        if (page === "settings") loadSettings();
    }

    function initNavigation(autoShow = false) {
        // Collect all potential navigation buttons
        const navButtons = $$("[data-page-link], [data-nav]");
        const processedButtons = new Set();

        navButtons.forEach(button => {
            if (processedButtons.has(button)) return;
            processedButtons.add(button);

            button.addEventListener("click", event => {
                event.preventDefault();
                const page = button.dataset.pageLink || button.dataset.nav;
                showPage(page);
            });
        });

        // Дополнительная поддержка кнопок вида:
        // <button data-page="profile">
        $$("[data-page]").forEach(element => {
            if (
                element.classList.contains("page") ||
                element.dataset.pageLink ||
                element.dataset.nav
            ) {
                return;
            }

            element.addEventListener("click", () => {
                showPage(element.dataset.page);
            });
        });

        if (autoShow) {
            const hash = window.location.hash
                .replace("#", "")
                .trim();

            if (hash) {
                showPage(hash, false);
            } else {
                showPage("home", false);
            }
        }
    }

    // =========================================================
    // PROFILE DROPDOWN
    // =========================================================

    function findProfileDropdown() {
        return (
            byId("profile-dropdown") ||
            $(".profile-dropdown") ||
            $(".account-dropdown")
        );
    }

    function closeProfileDropdown() {
        const dropdown = findProfileDropdown();

        if (!dropdown) {
            return;
        }

        dropdown.classList.remove("open", "active");
        dropdown.hidden = true;
    }

    function openProfileDropdown() {
        const dropdown = findProfileDropdown();

        if (!dropdown) {
            return;
        }

        dropdown.hidden = false;

        requestAnimationFrame(() => {
            dropdown.classList.add("open", "active");
        });
    }

    function toggleProfileDropdown(event) {
        if (event) {
            event.preventDefault();
            event.stopPropagation();
        }

        const dropdown = findProfileDropdown();

        if (!dropdown) {
            return;
        }

        const isOpen =
            dropdown.classList.contains("open") ||
            dropdown.classList.contains("active");

        if (isOpen) {
            closeProfileDropdown();
        } else {
            openProfileDropdown();
        }
    }

    function initProfileDropdown() {
        const accountButton =
            byId("account-button") ||
            byId("profile-button") ||
            $(".account-button") ||
            $(".profile-button") ||
            $(".profile-trigger");

        if (accountButton) {
            accountButton.addEventListener(
                "click",
                toggleProfileDropdown
            );
        }

        const dropdown = findProfileDropdown();

        if (dropdown) {
            dropdown.addEventListener(
                "click",
                event => {
                    event.stopPropagation();
                }
            );
        }

        document.addEventListener(
            "click",
            () => {
                closeProfileDropdown();
            }
        );
    }

    // =========================================================
    // PLAY
    // =========================================================

    function play() {
        if (state.loading) {
            return;
        }

        if (!hasBridgeMethod("play")) {
            toast(
                "Метод запуска игры пока не подключён к Java.",
                "warning"
            );

            return;
        }

        setLoading(
            true,
            "Запуск FireWorld..."
        );

        const result = callBridge("play");

        /*
         * Java может вернуть:
         * - boolean
         * - string
         * - объект
         * - null
         *
         * Не считаем null автоматически ошибкой:
         * старый bridge может выполнять запуск асинхронно.
         */

        if (result === false) {
            setLoading(false);

            toast(
                "Не удалось запустить игру.",
                "error"
            );

            return;
        }

        toast(
            "Запуск FireWorld...",
            "success"
        );

        // Не держим overlay бесконечно.
        setTimeout(() => {
            setLoading(false);
        }, 5000);
    }

    function initPlayButtons() {
        const selectors = [
            "#play-button",
            "#play-btn",
            ".play-button",
            "[data-action='play']"
        ];

        selectors.forEach(selector => {
            $$(selector).forEach(button => {
                button.addEventListener(
                    "click",
                    event => {
                        event.preventDefault();
                        play();
                    }
                );
            });
        });
    }
    
    // Jopa

    function selectBuild(card, version, time) {
    $$(".build-card").forEach(element => {
        element.classList.remove("active");
    });

    if (card) {
        card.classList.add("active");
    }

    state.selectedVersion = `Pura ${version}`;

    setText("active-version-name", "Pura");
    setText("active-version-main", version);
    setText(
        "active-playtime",
        `Наигранное время в игре: ${time}`
    );

    const bridge = getBridge();

    if (bridge && typeof bridge.setSelectedVersion === "function") {
        try {
            bridge.setSelectedVersion(state.selectedVersion);
        } catch (error) {
            console.error(
                "[PuraLauncher] Ошибка сохранения выбранной версии:",
                error
            );
        }
    }

    console.log(
        `[PuraLauncher] Выбрана сборка: ${state.selectedVersion}`
    );
}

    window.selectBuild = selectBuild;
    
    // =========================================================
    // WINDOW CONTROLS
    // =========================================================

    function minimizeWindow() {
        if (!hasBridgeMethod("minimize")) {
            toast(
                "Управление окном недоступно.",
                "warning"
            );

            return;
        }

        callBridge("minimize");
    }

    function closeWindow() {
        if (!hasBridgeMethod("close")) {
            return;
        }

        callBridge("close");
    }

    function initWindowControls() {
        $$("[data-window-action]").forEach(button => {
            button.addEventListener("click", () => {
                const action =
                    button.dataset.windowAction;

                if (action === "minimize") {
                    minimizeWindow();
                }

                if (action === "close") {
                    closeWindow();
                }
            });
        });

        $$("[data-action='minimize']").forEach(button => {
            button.addEventListener(
                "click",
                minimizeWindow
            );
        });

        $$("[data-action='close']").forEach(button => {
            button.addEventListener(
                "click",
                closeWindow
            );
        });

        const titleBar = byId("title-bar") || $(".title-bar");
        if (titleBar) {
            let dragging = false;
            titleBar.addEventListener("mousedown", event => {
                if (event.target.closest("button, a, input, select, .control-btn, .profile-button")) {
                    return;
                }
                dragging = true;
                if (hasBridgeMethod("startDrag")) {
                    callBridge("startDrag", event.screenX, event.screenY);
                }
            });

            window.addEventListener("mousemove", event => {
                if (!dragging) return;
                if (hasBridgeMethod("drag")) {
                    callBridge("drag", event.screenX, event.screenY);
                }
            });

            window.addEventListener("mouseup", () => {
                dragging = false;
            });
        }
    }

    // =========================================================
    // PROFILE
    // =========================================================

    function normalizeObject(value) {
        if (!value) {
            return {};
        }

        if (typeof value === "string") {
            try {
                return JSON.parse(value);
            } catch (_) {
                return {};
            }
        }

        return value;
    }

    function loadProfile() {
        const result =
            callBridge("getProfile") ||
            callBridge("getUserProfile");

        if (!result) {
            return;
        }

        const profile = normalizeObject(result);

        state.profile = profile;

        renderProfile(profile);
    }

    function renderProfile(profile) {
        const nickname =
            profile.nickname ||
            profile.username ||
            profile.name ||
            "Игрок";

        const role =
            profile.role ||
            profile.rank ||
            "Игрок";

        const avatar =
            profile.avatar ||
            profile.skin ||
            profile.avatarUrl ||
            "";

        const level =
            profile.level ??
            profile.lvl ??
            0;

        const xp =
            profile.xp ??
            profile.experience ??
            0;

        setText("profile-name", nickname);
        setText("username", nickname);
        setText("profile-nickname", nickname);

        setText("profile-role", role);
        setText("user-role", role);

        setText("profile-level", level);
        setText("user-level", level);

        setText("profile-xp", xp);

        setAvatar(
            avatar + (avatar.includes("?") ? "&" : "?") + "t=" + Date.now(),
            [
                "profile-avatar",
                "user-avatar",
                "account-avatar"
            ]
        );

        updateXP(profile);
    }

    function setAvatar(source, ids) {
        if (!source) {
            return;
        }

        ids.forEach(id => {
            const element = byId(id);

            if (!element) {
                return;
            }

            if (
                element.tagName === "IMG"
            ) {
                element.src = source;
                element.onerror = () => {
                    element.style.opacity = "0";
                };
            } else {
                element.style.backgroundImage =
                    `url("${source}")`;
            }
        });
    }

    // =========================================================
    // XP
    // =========================================================

    function updateXP(data = {}) {
        const level =
            Number(
                data.level ??
                data.lvl ??
                state.profile?.level ??
                0
            );

        const currentXP =
            Number(
                data.xp ??
                data.experience ??
                state.profile?.xp ??
                0
            );

        const nextXP =
            Number(
                data.nextLevelXp ??
                data.nextXp ??
                data.xpToNextLevel ??
                100
            );

        const safeNextXP =
            nextXP > 0 ? nextXP : 100;

        const percentage =
            Math.min(
                100,
                Math.max(
                    0,
                    (currentXP / safeNextXP) * 100
                )
            );

        setText("xp-value", currentXP);
        setText("xp-current", currentXP);
        setText("xp-next", safeNextXP);
        setText("level-value", level);

        const bars = [
            "#xp-progress",
            "#xp-bar",
            ".xp-progress-bar"
        ];

        bars.forEach(selector => {
            $$(selector).forEach(element => {
                element.style.width =
                    `${percentage}%`;
            });
        });

        const circles =
            $$(".xp-progress-fill");

        circles.forEach(element => {
            element.style.width =
                `${percentage}%`;
        });
    }

    // =========================================================
    // STATISTICS
    // =========================================================

    function loadStatistics() {
        const result =
            callBridge("getStatistics") ||
            callBridge("getStats");

        if (!result) {
            return;
        }

        const statistics =
            normalizeObject(result);

        state.statistics = statistics;

        renderStatistics(statistics);
    }

    function renderStatistics(stats) {
        const values = {
            "stat-playtime":
                stats.playtime ??
                stats.playTime ??
                stats.timePlayed,

            "stat-kills":
                stats.kills ??
                stats.killCount,

            "stat-deaths":
                stats.deaths ??
                stats.deathCount,

            "stat-blocks":
                stats.blocks ??
                stats.blocksBroken,

            "stat-distance":
                stats.distance ??
                stats.distanceTravelled,

            "stat-wins":
                stats.wins ??
                stats.victories
        };

        Object.entries(values).forEach(
            ([id, value]) => {
                if (
                    value !== undefined &&
                    value !== null
                ) {
                    setText(id, formatNumber(value));
                }
            }
        );
    }

    function formatNumber(value) {
        if (
            typeof value === "number"
        ) {
            return value.toLocaleString("ru-RU");
        }

        return String(value);
    }

    // =========================================================
    // FRIENDS
    // =========================================================

    function loadFriends() {
        const result =
            callBridge("getFriends");

        if (!result) {
            return;
        }

        const friends =
            Array.isArray(result)
                ? result
                : normalizeObject(result).friends || [];

        state.friends = friends;

        renderFriends(friends);
    }

    function renderFriends(friends) {
        const container =
            byId("friends-list") ||
            $(".friends-list");

        if (!container) {
            return;
        }

        if (!friends.length) {
            container.innerHTML = `
                <div class="empty-state">
                    <span>У вас пока нет друзей</span>
                </div>
            `;

            return;
        }

        container.innerHTML = "";

        friends
            .slice(0, 5)
            .forEach(friend => {
                const element =
                    document.createElement("div");

                element.className =
                    "friend-item";

                const name =
                    friend.nickname ||
                    friend.username ||
                    friend.name ||
                    "Игрок";

                const avatar =
                    friend.avatar ||
                    friend.skin ||
                    "";

                const online =
                    Boolean(
                        friend.online ??
                        friend.isOnline
                    );

                element.innerHTML = `
                    <div class="friend-avatar-wrap">
                        <div class="friend-avatar"></div>
                        <span class="friend-status ${
                            online ? "online" : "offline"
                        }"></span>
                    </div>

                    <div class="friend-info">
                        <div class="friend-name"></div>
                        <div class="friend-state">
                            ${online ? "В сети" : "Не в сети"}
                        </div>
                    </div>
                `;

                const nameElement =
                    $(".friend-name", element);

                if (nameElement) {
                    nameElement.textContent =
                        name;
                }

                const avatarElement =
                    $(".friend-avatar", element);

                if (
                    avatarElement &&
                    avatar
                ) {
                    avatarElement.style.backgroundImage =
                        `url("${avatar}")`;
                }

                container.appendChild(element);
            });
    }

    // =========================================================
    // NEWS
    // =========================================================

    function loadNews() {
        const result =
            callBridge("getNews");

        if (!result) {
            return;
        }

        const news =
            Array.isArray(result)
                ? result
                : normalizeObject(result).news || [];

        state.news = news;

        renderNews(news);
    }

    function renderNews(news) {
        const container =
            byId("news-list") ||
            $(".news-list");

        if (!container) {
            return;
        }

        if (!news.length) {
            container.innerHTML = `
                <div class="empty-state">
                    <span>Новостей пока нет</span>
                </div>
            `;

            return;
        }

        container.innerHTML = "";

        news.forEach(item => {
            const element =
                document.createElement("article");

            element.className = "news-card";

            const title =
                item.title ||
                item.name ||
                "Новость";

            const text =
                item.text ||
                item.description ||
                item.content ||
                "";

            const date =
                item.date ||
                item.createdAt ||
                "";

            element.innerHTML = `
                <div class="news-card-content">
                    <div class="news-card-date"></div>
                    <h3 class="news-card-title"></h3>
                    <p class="news-card-text"></p>
                </div>
            `;

            setTextInside(
                element,
                ".news-card-date",
                formatDate(date)
            );

            setTextInside(
                element,
                ".news-card-title",
                title
            );

            setTextInside(
                element,
                ".news-card-text",
                text
            );

            container.appendChild(element);
        });
    }

    function setTextInside(
        parent,
        selector,
        value
    ) {
        const element =
            $(selector, parent);

        if (element) {
            element.textContent =
                value || "";
        }
    }

    function formatDate(value) {
        if (!value) {
            return "";
        }

        const date =
            new Date(value);

        if (
            Number.isNaN(
                date.getTime()
            )
        ) {
            return String(value);
        }

        return date.toLocaleDateString(
            "ru-RU"
        );
    }

    // =========================================================
    // SERVER STATUS
    // =========================================================

    function loadServerStatus() {
        const result =
            callBridge("getServerStatus");

        if (!result) {
            return;
        }

        const status =
            normalizeObject(result);

        const online =
            Boolean(
                status.online ??
                status.isOnline
            );

        setText(
            "server-status",
            online
                ? "Онлайн"
                : "Оффлайн"
        );

        setText(
            "server-online",
            online
                ? "Онлайн"
                : "Оффлайн"
        );

        if (
            status.players !== undefined
        ) {
            setText(
                "server-players",
                status.players
            );
        }

        if (
            status.onlinePlayers !== undefined
        ) {
            setText(
                "server-players",
                status.onlinePlayers
            );
        }

        const statusElements = [
            byId("server-status"),
            byId("server-online")
        ];

        statusElements.forEach(element => {
            if (!element) {
                return;
            }

            element.classList.toggle(
                "online",
                online
            );

            element.classList.toggle(
                "offline",
                !online
            );
        });
    }

    // =========================================================
    // SETTINGS
    // =========================================================

    function loadSettings() {
        const result =
            callBridge("getSettings");

        if (!result) {
            return;
        }

        const settings =
            normalizeObject(result);

        state.settings = settings;

        renderSettings(settings);
    }

    function renderSettings(settings) {
        Object.entries(settings)
            .forEach(([key, value]) => {
                const elements =
                    $$(
                        `[data-setting="${key}"]`
                    );

                elements.forEach(element => {
                    if (
                        element.type === "checkbox"
                    ) {
                        element.checked =
                            Boolean(value);

                        return;
                    }

                    element.value =
                        value ?? "";
                });
            });

        const selectedVersion =
            settings.selectedVersion ||
            settings.version;

        if (selectedVersion) {
            state.selectedVersion =
                selectedVersion;

            setText(
                "selected-version",
                selectedVersion
            );
        }
    }

    function collectSettings() {
        const settings = {
            ...state.settings
        };

        $$("[data-setting]")
            .forEach(element => {
                const key =
                    element.dataset.setting;

                if (!key) {
                    return;
                }

                if (
                    element.type === "checkbox"
                ) {
                    settings[key] =
                        element.checked;
                } else {
                    settings[key] =
                        element.value;
                }
            });

        return settings;
    }

    function saveSettings() {
        const settings =
            collectSettings();

        state.settings = settings;

        const jsonString = JSON.stringify(settings);

        if (
            hasBridgeMethod(
                "saveSettings"
            )
        ) {
            const result =
                callBridge(
                    "saveSettings",
                    jsonString
                );

            if (result === false) {
                toast(
                    "Не удалось сохранить настройки.",
                    "error"
                );

                return;
            }

            toast(
                "Настройки сохранены.",
                "success"
            );

            return;
        }

        /*
         * Совместимость со старым bridge.
         */
        if (
            hasBridgeMethod(
                "applyLauncherSettings"
            )
        ) {
            callBridge(
                "applyLauncherSettings",
                jsonString
            );

            toast(
                "Настройки сохранены.",
                "success"
            );

            return;
        }

        toast(
            "Java Bridge для настроек ещё не подключён.",
            "warning"
        );
    }


    function initSettings() {
        $$("[data-setting]")
            .forEach(element => {
                element.addEventListener(
                    "change",
                    () => {
                        /*
                         * Автосохранение можно включить
                         * после подключения Java Bridge.
                         */
                    }
                );
            });

        $$("[data-action='save-settings']")
            .forEach(button => {
                button.addEventListener(
                    "click",
                    saveSettings
                );
            });

        $$("[data-save-settings]")
            .forEach(button => {
            button.addEventListener(
                "click",
                saveSettings
            );
        });
    }

    // =========================================================
    // LOGOUT
    // =========================================================

    function logout() {
        openModal(
            "Выход из аккаунта",
            `
                <div class="modal-confirm">
                    <p>Вы действительно хотите выйти?</p>

                    <div class="modal-actions">
                        <button
                            class="button secondary"
                            data-modal-cancel
                        >
                            Отмена
                        </button>

                        <button
                            class="button danger"
                            data-modal-confirm
                        >
                            Выйти
                        </button>
                    </div>
                </div>
            `
        );

        const modal =
            byId("modal") ||
            $(".modal");

        if (!modal) {
            return;
        }

        const cancel =
            $("[data-modal-cancel]", modal);

        const confirm =
            $("[data-modal-confirm]", modal);

        if (cancel) {
            cancel.addEventListener(
                "click",
                closeModal
            );
        }

        if (confirm) {
            confirm.addEventListener(
                "click",
                () => {
                    closeModal();

                    const result =
                        callBridge("logout");

                    if (result === false) {
                        toast(
                            "Не удалось выйти из аккаунта.",
                            "error"
                        );

                        return;
                    }

                    toast(
                        "Вы вышли из аккаунта.",
                        "success"
                    );
                }
            );
        }
    }

    function initLogout() {
        $$("[data-action='logout']")
            .forEach(button => {
                button.addEventListener(
                    "click",
                    logout
                );
            });
    }

    // =========================================================
    // GENERIC ACTIONS
    // =========================================================

    function initGenericActions() {
        $$("[data-action]")
            .forEach(element => {
                const action =
                    element.dataset.action;

                /*
                 * Эти действия уже обрабатываются
                 * отдельными функциями.
                 */
                if (
                    [
                        "play",
                        "logout",
                        "close",
                        "minimize",
                        "save-settings"
                    ].includes(action)
                ) {
                    return;
                }

                element.addEventListener(
                    "click",
                    () => {
                        switch (action) {
                            case "profile":
                                showPage("profile");
                                break;

                            case "statistics":
                                showPage("statistics");
                                break;

                            case "news":
                                showPage("news");
                                break;

                            case "settings":
                                showPage("settings");
                                break;

                            case "support":
                                showPage("support");
                                break;

                            case "refresh":
                                refreshAll();
                                break;

                            default:
                                console.debug(
                                    `[PuraLauncher] Неизвестное действие: ${action}`
                                );
                        }
                    }
                );
            });
    }

    // =========================================================
    // REFRESH
    // =========================================================

    function refreshAll() {
        loadProfile();
        loadFriends();
        loadStatistics();
        loadNews();
        loadServerStatus();
        loadSettings();
    }

    // =========================================================
    // BRIDGE INITIALIZATION
    // =========================================================

    function waitForBridge(
        callback,
        attempts = 50
    ) {
        if (
            getBridge()
        ) {
            callback();
            return;
        }

        if (attempts <= 0) {
            console.warn(
                "[PuraLauncher] LauncherBridge не появился."
            );

            callback();
            return;
        }

        setTimeout(
            () => {
                waitForBridge(
                    callback,
                    attempts - 1
                );
            },
            100
        );
    }

    // =========================================================
    // BRIDGE CALLBACKS (Java -> JS)
    // =========================================================

    window.PuraAuth = {
        onLoginSuccess() {
            document.body.classList.remove('is-auth');
            showPage('home');
            toast("Авторизация успешна.", "success");
        },
        setLoading(loading) {
            setLoading(loading);
        },
        showMessage(message, type) {
            toast(message, type);
        }
    };

    window.PuraLauncher = {
        onLogin(data) {
            const profile = normalizeObject(data);
            state.profile = profile;
            renderProfile(profile);
            document.body.classList.remove('is-auth');
            showPage('home');
            toast("Авторизация выполнена.", "success");
        },

        onLogout() {
            state.profile = null;
            document.body.classList.add('is-auth');
            showPage('auth');
            toast("Вы вышли из аккаунта.", "info");
        },

        onDownloadProgress(progress, text) {
            const value = Math.max(0, Math.min(100, Number(progress) || 0));
            setLoading(true, text || `Загрузка... ${Math.round(value)}%`);
            $$(".loading-progress").forEach(element => element.style.width = `${value}%`);
        },

        onDownloadComplete() {
            setLoading(false);
            toast("Загрузка завершена.", "success");
        },

        onServerStatus(data) {
            loadServerStatusFromData(normalizeObject(data));
        },

        onProfileUpdate(data) {
            const profile = normalizeObject(data);
            state.profile = profile;
            renderProfile(profile);
        },

        onError(message) {
            setLoading(false);
            toast(message || "Произошла ошибка.", "error");
        },

        showToast(message, type) {
            toast(message, type || "info");
        }
    };

    function loadServerStatusFromData(status) {
        const online =
            Boolean(
                status.online ??
                status.isOnline
            );

        setText(
            "server-status",
            online
                ? "Онлайн"
                : "Оффлайн"
        );

        setText(
            "server-online",
            online
                ? "Онлайн"
                : "Оффлайн"
        );

        if (
            status.players !== undefined
        ) {
            setText(
                "server-players",
                status.players
            );
        }
    }

    // =========================================================
    // MODAL INIT
    // =========================================================

    function initModal() {
        const modal =
            byId("modal") ||
            $(".modal");

        if (!modal) {
            return;
        }

        const closeButtons = [
            ...$$(
                "[data-modal-close]",
                modal
            ),
            ...$$(
                ".modal-close",
                modal
            )
        ];

        closeButtons.forEach(
            button => {
                button.addEventListener(
                    "click",
                    closeModal
                );
            }
        );

        modal.addEventListener(
            "click",
            event => {
                if (
                    event.target === modal
                ) {
                    closeModal();
                }
            }
        );
    }

    // =========================================================
    // KEYBOARD
    // =========================================================

    function initKeyboard() {
        document.addEventListener(
            "keydown",
            event => {
                if (
                    event.key === "Escape"
                ) {
                    closeProfileDropdown();
                    closeModal();
                }
            }
        );
    }

    // =========================================================
    // PREVENT DOUBLE CLICK / DRAG ISSUES
    // =========================================================

    function initUIProtection() {
        document.addEventListener(
            "dragstart",
            event => {
                if (
                    event.target.tagName === "IMG"
                ) {
                    event.preventDefault();
                }
            }
        );
    }

    // =========================================================
    // INITIAL DATA
    // =========================================================

    function loadInitialData() {
        /*
         * Не вызываем методы, которых может не быть.
         * Это позволяет запускать HTML даже во время
         * постепенной миграции JavaFX → WebView.
         */

        loadProfile();
        loadFriends();
        loadStatistics();
        loadNews();
        loadServerStatus();
        loadSettings();

        /*
         * Получение выбранной версии.
         */
        const version =
            callBridge(
                "getSelectedVersion"
            );

        if (
            version !== null &&
            version !== undefined
        ) {
            state.selectedVersion =
                version;

            setText(
                "selected-version",
                version
            );
        }

        // Авторизация при старте
        if (state.profile && state.profile.authMode && state.profile.authMode !== "OFFLINE") {
            document.body.classList.remove('is-auth');
            showPage('home', false);
        } else {
            // Оставляем is-auth, auth.html уже в DOM
        }
    }

    // =========================================================
    // INIT
    // =========================================================

    function init() {
        if (state.initialized) {
            return;
        }

        state.initialized = true;

        initNavigation();
        initProfileDropdown();
        initPlayButtons();
        initWindowControls();
        initSettings();
        initLogout();
        initGenericActions();
        initModal();
        initKeyboard();
        initUIProtection();

        waitForBridge(
            loadInitialData
        );

        /*
         * Периодически обновляем только динамический
         * статус сервера. Не перезагружаем всю страницу.
         */
        setInterval(
            () => {
                if (
                    hasBridgeMethod(
                        "getServerStatus"
                    )
                ) {
                    loadServerStatus();
                }
            },
            30000
        );

        console.log(
            "[PuraLauncher] Web UI initialized."
        );
    }

    // =========================================================
    // DOM READY
    // =========================================================

    if (
        document.readyState ===
        "loading"
    ) {
        document.addEventListener(
            "DOMContentLoaded",
            init
        );
    } else {
        init();
    }

    // =========================================================
    // EXPOSE PUBLIC API
    // =========================================================

    window.PuraApp = {
        showPage,
        play,
        logout,
        saveSettings,
        refreshAll,
        openModal,
        closeModal,
        toast,
        setLoading,

        getState() {
            return {
                ...state
            };
        }
    };

})();