const http = require("http");
const admin = require("firebase-admin");
const { getFirestore } = require("firebase-admin/firestore");
const { getStorage } = require("firebase-admin/storage");
const {
  resolveBotRuntimeConfig,
  handleTelegramUpdate,
  handleStreamPublicVideo,
} = require("./telegramBot");
const {
  handleSharePageRequest,
  handleShareThumbnailRequest,
} = require("./sharePreview");

const DATABASE_ID =
  process.env.FIRESTORE_DATABASE_ID ||
  "ai-studio-android-garammas-738e4493-0090-419e-8ece-c55642a933ce";
const STORAGE_BUCKET =
  process.env.FIREBASE_STORAGE_BUCKET || "gen-lang-client-0000740810.firebasestorage.app";
const PLAYBACK_SECRET = process.env.PLAYBACK_SECRET || "vidvora-secure-playback-secret-key-2026";
const PORT = parseInt(process.env.PORT || "8080", 10);

function resolvePublicHostBaseUrl(req) {
  const configured = (process.env.PUBLIC_BASE_URL || process.env.RENDER_EXTERNAL_URL || "")
    .trim()
    .replace(/\/+$/, "");
  if (configured) return configured;
  const host = req.headers.host;
  const protocol = String(req.headers["x-forwarded-proto"] || "https").split(",")[0].trim();
  return `${protocol}://${host}`.replace(/\/+$/, "");
}

function loadServiceAccountCredential() {
  const raw = String(process.env.FIREBASE_SERVICE_ACCOUNT_JSON || "").trim();
  if (!raw) return null;
  try {
    const jsonText = raw.startsWith("{") ? raw : Buffer.from(raw, "base64").toString("utf8");
    return admin.credential.cert(JSON.parse(jsonText));
  } catch (err) {
    console.error("Invalid FIREBASE_SERVICE_ACCOUNT_JSON:", err.message);
    return null;
  }
}

if (!admin.apps.length) {
  const cert = loadServiceAccountCredential();
  if (cert) {
    admin.initializeApp({ credential: cert, storageBucket: STORAGE_BUCKET });
  } else {
    admin.initializeApp({ storageBucket: STORAGE_BUCKET });
  }
}

const db = getFirestore(DATABASE_ID);
const storage = getStorage();

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    let data = "";
    req.on("data", (chunk) => {
      data += chunk;
      if (data.length > 5 * 1024 * 1024) {
        reject(new Error("Request body too large"));
        req.destroy();
      }
    });
    req.on("end", () => {
      try {
        resolve(data ? JSON.parse(data) : {});
      } catch (err) {
        reject(err);
      }
    });
    req.on("error", reject);
  });
}

const server = http.createServer(async (req, res) => {
  const urlObj = new URL(req.url || "/", `http://${req.headers.host || "localhost"}`);
  const pathname = urlObj.pathname;
  const hasWatchParam =
    urlObj.searchParams.has("link") ||
    urlObj.searchParams.has("v") ||
    urlObj.searchParams.has("video") ||
    urlObj.searchParams.has("token");

  if (req.method === "GET" && (pathname === "/health" || (pathname === "/" && !hasWatchParam))) {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ ok: true, service: "VidVora Telegram Bot & Share Preview Server" }));
    return;
  }

  if (
    (req.method === "GET" || req.method === "HEAD") &&
    (pathname === "/share" ||
      pathname.startsWith("/share/") ||
      pathname === "/watch" ||
      pathname.startsWith("/watch/") ||
      (pathname === "/" && hasWatchParam))
  ) {
    try {
      await handleSharePageRequest({
        reqUrl: req.url,
        hostBaseUrl: resolvePublicHostBaseUrl(req),
        method: req.method,
        res,
        db,
      });
    } catch (err) {
      console.error("Standalone sharePage error:", err);
      if (!res.headersSent) {
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Share preview error");
      }
    }
    return;
  }

  if (
    (req.method === "GET" || req.method === "HEAD") &&
    (pathname === "/shareThumb" ||
      pathname.startsWith("/shareThumb/") ||
      pathname === "/vidvora-og-banner.png")
  ) {
    try {
      await handleShareThumbnailRequest({
        videoId: urlObj.searchParams.get("videoId"),
        link: urlObj.searchParams.get("link"),
        method: req.method,
        res,
        db,
        storage,
      });
    } catch (err) {
      console.error("Standalone shareThumb error:", err);
      if (!res.headersSent) {
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Thumbnail error");
      }
    }
    return;
  }

  if (pathname === "/telegramWebhook") {
    if (req.method !== "POST") {
      res.writeHead(200, { "Content-Type": "text/plain" });
      res.end("VidVora Telegram Creator Bot Webhook Active");
      return;
    }

    try {
      const { webhookSecret } = await resolveBotRuntimeConfig(db);
      if (webhookSecret) {
        const incomingSecret = String(req.headers["x-telegram-bot-api-secret-token"] || "").trim();
        if (incomingSecret !== webhookSecret) {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Unauthorized webhook secret");
          return;
        }
      }

      const body = await readJsonBody(req);
      const publicBaseUrl = resolvePublicHostBaseUrl(req);

      const result = await handleTelegramUpdate({
        update: body,
        db,
        storage,
        functionBaseUrl: publicBaseUrl,
        playbackSecret: PLAYBACK_SECRET,
      });

      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(result));
    } catch (err) {
      console.error("Standalone telegramWebhook error:", err);
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: false, error: err.message }));
    }
    return;
  }

  if (req.method === "GET" && pathname === "/streamPublicVideo") {
    try {
      await handleStreamPublicVideo({
        videoId: urlObj.searchParams.get("videoId"),
        kind: urlObj.searchParams.get("kind"),
        sig: urlObj.searchParams.get("sig"),
        rangeHeader: req.headers.range,
        res,
        db,
        storage,
        playbackSecret: PLAYBACK_SECRET,
      });
    } catch (err) {
      console.error("Standalone streamPublicVideo error:", err);
      if (!res.headersSent) {
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Streaming error");
      }
    }
    return;
  }

  res.writeHead(404, { "Content-Type": "text/plain" });
  res.end("Not found");
});

async function autoRegisterTelegramWebhook() {
  try {
    const publicBase = (process.env.PUBLIC_BASE_URL || process.env.RENDER_EXTERNAL_URL || "")
      .trim()
      .replace(/\/+$/, "");
    if (!/^https:\/\//i.test(publicBase)) return;

    const { botToken, apiBase, webhookSecret } = await resolveBotRuntimeConfig(db);
    if (!botToken) return;

    const desiredWebhookUrl = `${publicBase}/telegramWebhook`;
    const infoRes = await fetch(`${apiBase}/bot${botToken}/getWebhookInfo`);
    const infoData = await infoRes.json().catch(() => ({}));
    if (infoData.ok && infoData.result && infoData.result.url === desiredWebhookUrl) {
      console.log(`Telegram webhook already active: ${desiredWebhookUrl}`);
      return;
    }

    const payload = {
      url: desiredWebhookUrl,
      allowed_updates: ["message"],
    };
    if (webhookSecret) {
      payload.secret_token = webhookSecret;
    }
    const setRes = await fetch(`${apiBase}/bot${botToken}/setWebhook`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    const setData = await setRes.json().catch(() => ({}));
    if (setData.ok) {
      console.log(`Telegram webhook auto-registered: ${desiredWebhookUrl}`);
    } else {
      console.warn("Telegram webhook auto-register failed:", setData.description || "Unknown error");
    }
  } catch (err) {
    console.warn("Telegram webhook auto-register skipped:", err.message);
  }
}

server.listen(PORT, () => {
  console.log(`VidVora Telegram Bot server listening on port ${PORT}`);
  autoRegisterTelegramWebhook();
});
