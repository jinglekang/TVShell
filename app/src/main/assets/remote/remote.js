(function () {
  const urlParams = new URLSearchParams(window.location.search);
  const token = urlParams.get("token") || "";

  const I18N = {
    zh: {
      page_title: "TV Shell 遥控器",
      brand_sub: "用手机控制电视",
      status_connecting: "正在连接…",
      status_connected: "已连上电视",
      status_disconnected: "未连接",
      status_bad_key: "遥控密钥无效，请重新扫码",
      status_loading: "正在打开网页…",
      status_live: "实时同步",
      label_tv_page: "电视正在显示",
      label_open_url: "在电视上打开网页",
      hint_url: "输入网址，例如 192.168.1.100:3000",
      label_paste: "粘贴到浏览器",
      hint_paste: "粘贴任意文本",
      btn_clear: "清空",
      btn_open: "打开",
      btn_paste: "粘贴",
      toast_need_paste: "请先粘贴或输入文本",
      toast_pasted: "已粘贴到浏览器",
      toast_paste_failed: "粘贴失败",
      label_browser_controls: "网页控制",
      label_app_controls: "程序控制",
      btn_scroll_up: "向上滚动",
      btn_scroll_down: "向下滚动",
      btn_ok: "确认",
      btn_back: "返回",
      btn_history_back: "后退",
      btn_history_forward: "前进",
      btn_refresh: "刷新",
      btn_tv_settings: "设置",
      btn_home: "首页",
      toast_home: "已回到首页",
      toast_history_back: "已后退",
      toast_history_forward: "已前进",
      toast_need_url: "请先输入网址",
      toast_opening: "正在电视上打开…",
      toast_opened: "已发送到电视",
      toast_failed: "没能发送，请重试",
      toast_scroll_up: "已向上滚动",
      toast_scroll_down: "已向下滚动",
      toast_scroll_failed: "滚动失败",
      toast_refreshing: "正在刷新…",
      toast_refreshed: "已刷新",
      toast_refresh_failed: "刷新失败",
      toast_menu: "已打开电视菜单",
      toast_settings: "已打开电视设置",
      toast_action_failed: "操作失败"
    },
    en: {
      page_title: "TV Shell remote",
      brand_sub: "Control the TV from your phone",
      status_connecting: "Connecting…",
      status_connected: "Connected to TV",
      status_disconnected: "Not connected",
      status_bad_key: "Remote key is invalid. Scan the QR code again.",
      status_loading: "Opening page…",
      status_live: "Live",
      label_tv_page: "On the TV",
      label_open_url: "Open a page on the TV",
      hint_url: "Enter a web address, like 192.168.1.100:3000",
      label_paste: "Paste to the TV",
      hint_paste: "Paste any text",
      btn_clear: "Clear",
      btn_open: "Open",
      btn_paste: "Paste",
      toast_need_paste: "Paste or type some text first",
      toast_pasted: "Pasted on the TV",
      toast_paste_failed: "Couldn’t paste",
      label_browser_controls: "Page controls",
      label_app_controls: "App controls",
      btn_scroll_up: "Scroll up",
      btn_scroll_down: "Scroll down",
      btn_ok: "OK",
      btn_back: "Back",
      btn_history_back: "Back",
      btn_history_forward: "Forward",
      btn_refresh: "Refresh",
      btn_tv_settings: "Settings",
      btn_home: "Home",
      toast_home: "Opened home",
      toast_history_back: "Went back",
      toast_history_forward: "Went forward",
      toast_need_url: "Enter a web address first",
      toast_opening: "Opening on the TV…",
      toast_opened: "Sent to the TV",
      toast_failed: "Couldn’t send. Try again.",
      toast_scroll_up: "Scrolled up",
      toast_scroll_down: "Scrolled down",
      toast_scroll_failed: "Couldn’t scroll",
      toast_refreshing: "Refreshing…",
      toast_refreshed: "Refreshed",
      toast_refresh_failed: "Couldn’t refresh",
      toast_menu: "Opened the TV menu",
      toast_settings: "Opened TV settings",
      toast_action_failed: "That didn’t work"
    }
  };

  let lang = "zh";

  function t(key) {
    return (I18N[lang] && I18N[lang][key]) || I18N.zh[key] || key;
  }

  function applyI18n() {
    document.documentElement.lang = lang === "en" ? "en" : "zh-CN";
    document.title = t("page_title");
    document.querySelectorAll("[data-i18n]").forEach(function (el) {
      el.textContent = t(el.getAttribute("data-i18n"));
    });
    document.querySelectorAll("[data-i18n-placeholder]").forEach(function (el) {
      el.setAttribute("placeholder", t(el.getAttribute("data-i18n-placeholder")));
    });
    document.querySelectorAll("[data-i18n-aria]").forEach(function (el) {
      el.setAttribute("aria-label", t(el.getAttribute("data-i18n-aria")));
    });
  }

  const statusBadge = document.getElementById("status-badge");
  const statusText = document.getElementById("status-text");
  const currentTitle = document.getElementById("current-title");
  const currentUrl = document.getElementById("current-url");
  const loadingIndicator = document.getElementById("loading-indicator");
  const urlInput = document.getElementById("url-input");
  const pasteInput = document.getElementById("paste-input");
  const toast = document.getElementById("toast");

  let isConnected = false;

  function haptic(ms) {
    try {
      if (typeof navigator !== "undefined" && typeof navigator.vibrate === "function") {
        navigator.vibrate(ms || 12);
      }
    } catch (e) {
      // HTTP / iOS / permission: vibration is often unavailable.
    }
  }

  function showToast(msg) {
    if (!toast) return;
    toast.textContent = msg;
    toast.classList.add("show");
    setTimeout(function () {
      toast.classList.remove("show");
    }, 2000);
  }

  function setBadgeState(state, text) {
    statusBadge.className = "badge badge-" + state;
    statusText.textContent = text;
  }

  async function apiCall(endpoint, method, body) {
    method = method || "GET";
    const url = endpoint + "?token=" + encodeURIComponent(token);
    const options = {
      method: method,
      headers: {
        "Content-Type": "application/json",
        "X-Remote-Token": token
      }
    };
    if (body) {
      options.body = JSON.stringify(body);
    }
    const response = await fetch(url, options);
    if (!response.ok) {
      if (response.status === 403) {
        throw new Error(t("status_bad_key"));
      }
      throw new Error(t("toast_failed"));
    }
    return await response.json();
  }

  function setPageOnlyVisible(pageOpen) {
    ["browser-card", "paste-card"].forEach(function (id) {
      const el = document.getElementById(id);
      if (el) el.hidden = !pageOpen;
    });
  }

  async function fetchStatus() {
    try {
      const data = await apiCall("/api/status");
      const nextLang = data.language === "en" ? "en" : "zh";
      if (nextLang !== lang) {
        lang = nextLang;
        applyI18n();
      }
      isConnected = true;
      setBadgeState("connected", t("status_connected"));

      const pageOpen = !!data.pageOpen;
      const sessionLabel = document.getElementById("session-label");
      if (sessionLabel) {
        sessionLabel.textContent = pageOpen ? t("label_tv_page") : t("label_open_url");
      }
      const pageMeta = document.getElementById("page-meta");
      if (pageMeta) pageMeta.hidden = !pageOpen;
      currentTitle.textContent = pageOpen ? (data.title || "").trim() : "";
      currentUrl.textContent = pageOpen ? (data.currentUrl || "") : "";

      if (data.loading && pageOpen) {
        loadingIndicator.style.display = "inline-block";
      } else {
        loadingIndicator.style.display = "none";
      }

      setPageOnlyVisible(pageOpen);
    } catch (e) {
      isConnected = false;
      setBadgeState("error", e.message || t("status_disconnected"));
      loadingIndicator.style.display = "none";
      setPageOnlyVisible(false);
      const pageMeta = document.getElementById("page-meta");
      if (pageMeta) pageMeta.hidden = true;
    }
  }

  window.openUrl = async function () {
    haptic(16);
    const inputVal = urlInput.value.trim();
    if (!inputVal) {
      showToast(t("toast_need_url"));
      return;
    }
    try {
      showToast(t("toast_opening"));
      await apiCall("/api/open", "POST", { url: inputVal });
      showToast(t("toast_opened"));
      fetchStatus();
    } catch (e) {
      showToast(t("toast_failed"));
    }
  };

  window.pasteToTv = async function () {
    const text = pasteInput ? pasteInput.value : "";
    if (!text) {
      haptic(10);
      showToast(t("toast_need_paste"));
      return;
    }
    haptic(16);
    try {
      await apiCall("/api/paste", "POST", { text: text });
      showToast(t("toast_pasted"));
    } catch (e) {
      showToast(t("toast_paste_failed"));
    }
  };

  window.scrollPage = async function (direction) {
    try {
      await apiCall("/api/scroll", "POST", { direction: direction });
      showToast(direction === "up" ? t("toast_scroll_up") : t("toast_scroll_down"));
    } catch (e) {
      showToast(t("toast_scroll_failed"));
    }
  };

  async function sendKey(key, action) {
    try {
      await apiCall("/api/key", "POST", { key: key, action: action });
    } catch (e) {
      if (action === "down") {
        showToast(t("toast_action_failed"));
      }
    }
  }

  function bindRemoteKey(el) {
    if (!el) return;
    const key = el.getAttribute("data-key");
    if (!key) return;
    let pressed = false;
    const down = function (e) {
      e.preventDefault();
      if (pressed) return;
      pressed = true;
      el.classList.add("is-down");
      haptic(key === "ok" ? 18 : 12);
      sendKey(key, "down");
    };
    const up = function (e) {
      if (e) e.preventDefault();
      if (!pressed) return;
      pressed = false;
      el.classList.remove("is-down");
      sendKey(key, "up");
    };
    el.addEventListener("pointerdown", down);
    el.addEventListener("pointerup", up);
    el.addEventListener("pointercancel", up);
    el.addEventListener("pointerleave", function (e) {
      if (pressed) up(e);
    });
    el.addEventListener("contextmenu", function (e) {
      e.preventDefault();
    });
  }

  document.querySelectorAll("[data-key]").forEach(bindRemoteKey);

  window.historyBack = async function () {
    haptic(14);
    try {
      await apiCall("/api/history-back", "POST");
      showToast(t("toast_history_back"));
      fetchStatus();
    } catch (e) {
      showToast(t("toast_action_failed"));
    }
  };

  window.historyForward = async function () {
    haptic(14);
    try {
      await apiCall("/api/history-forward", "POST");
      showToast(t("toast_history_forward"));
      fetchStatus();
    } catch (e) {
      showToast(t("toast_action_failed"));
    }
  };

  window.reloadPage = async function () {
    haptic(14);
    try {
      showToast(t("toast_refreshing"));
      await apiCall("/api/reload", "POST");
      showToast(t("toast_refreshed"));
      fetchStatus();
    } catch (e) {
      showToast(t("toast_refresh_failed"));
    }
  };

  window.showMenu = async function () {
    haptic(14);
    try {
      await apiCall("/api/show-menu", "POST");
      showToast(t("toast_menu"));
    } catch (e) {
      showToast(t("toast_action_failed"));
    }
  };

  window.showHome = async function () {
    haptic(14);
    try {
      await apiCall("/api/show-home", "POST");
      showToast(t("toast_home"));
      fetchStatus();
    } catch (e) {
      showToast(t("toast_action_failed"));
    }
  };

  window.showSettings = async function () {
    haptic(14);
    try {
      await apiCall("/api/show-settings", "POST");
      showToast(t("toast_settings"));
    } catch (e) {
      showToast(t("toast_action_failed"));
    }
  };

  window.quickFill = function (prefix) {
    haptic(10);
    if (prefix.startsWith(":")) {
      urlInput.value = urlInput.value + prefix;
    } else {
      if (!urlInput.value.startsWith("http://") && !urlInput.value.startsWith("https://")) {
        urlInput.value = prefix + urlInput.value;
      }
    }
    urlInput.focus();
  };

  window.clearInput = function () {
    haptic(10);
    urlInput.value = "";
    urlInput.focus();
  };

  urlInput.addEventListener("keydown", function (e) {
    if (e.key === "Enter") {
      window.openUrl();
    }
  });

  applyI18n();
  fetchStatus();
  setInterval(fetchStatus, 1000);
})();
