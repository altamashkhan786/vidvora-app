const http = require("http");
const admin = require("firebase-admin");
const { getFirestore } = require("firebase-admin/firestore");
const { getStorage } = require("firebase-admin/storage");
const {
  resolveBotRuntimeConfig,
  handleTelegramUpdate,
  handleStreamPublicVideo,
  extractVidVoraShareTarget,
  isAllowedTelegramVideoMessage,
  telegramApiJson,
} = require("./telegramBot");
const {
  resolveFinanceBotRuntimeConfig,
  handleFinanceTelegramUpdate,
} = require("./financeBot");
const {
  handleSharePageRequest,
  handleShareThumbnailRequest,
} = require("./sharePreview");
const {
  isEmailTransportConfigured,
  handleSendOtp,
  handleVerifySignupOtp,
  handleVerifyResetOtp,
} = require("./emailOtp");

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

const MAX_MEDIA_QUEUE_PENDING = 500;
const MEDIA_QUEUE_TASK_TIMEOUT_MS = 135_000;
let mediaQueueTail = Promise.resolve();
let mediaQueueDepth = 0;

function enqueueTelegramMediaTask(taskFn) {
  if (mediaQueueDepth >= MAX_MEDIA_QUEUE_PENDING) {
    return Promise.resolve({ ok: false, reason: "queue_full" });
  }
  mediaQueueDepth += 1;
  const runNext = async () => {
    let timer = null;
    try {
      const taskPromise = Promise.resolve().then(() => taskFn());
      return await Promise.race([
        taskPromise,
        new Promise((_, reject) => {
          timer = setTimeout(() => {
            reject(new Error("Server media queue task timed out."));
          }, MEDIA_QUEUE_TASK_TIMEOUT_MS);
        }),
      ]);
    } finally {
      if (timer) clearTimeout(timer);
      mediaQueueDepth = Math.max(0, mediaQueueDepth - 1);
    }
  };
  const next = mediaQueueTail.then(runNext, runNext);
  mediaQueueTail = next.catch(() => {});
  return next;
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
    const healthPayload = {
      ok: true,
      service: "VidVora Telegram Bot, Finance Bot & Share Preview Server",
      uploadBotConfigured: Boolean((process.env.TELEGRAM_BOT_TOKEN || "").trim()),
      financeBotConfigured: Boolean((process.env.FINANCE_BOT_TOKEN || "").trim()),
      mtprotoConfigured: Boolean(
        (process.env.TELEGRAM_API_ID || "").trim() && (process.env.TELEGRAM_API_HASH || "").trim()
      ),
      otpEmailConfigured: isEmailTransportConfigured(),
      mediaQueueDepth,
    };

    if (urlObj.searchParams.get("check") === "1") {
      try {
        const cfg = await resolveBotRuntimeConfig(db);
        healthPayload.uploadBotConfigured = Boolean(cfg.botToken);
        healthPayload.mtprotoConfigured = Boolean(cfg.apiId && cfg.apiHash);
        if (cfg.botToken) {
          const infoRes = await fetch(`${cfg.apiBase}/bot${cfg.botToken}/getWebhookInfo`, {
            signal: AbortSignal.timeout(10_000),
          });
          const infoData = await infoRes.json().catch(() => ({}));
          if (infoData.ok && infoData.result) {
            healthPayload.uploadWebhook = {
              url: infoData.result.url || "",
              pendingUpdateCount: infoData.result.pending_update_count || 0,
              lastErrorMessage: infoData.result.last_error_message || "",
              lastErrorDate: infoData.result.last_error_date || 0,
            };
          }
        }
      } catch (diagErr) {
        healthPayload.diagnosticError = diagErr?.message || "Diagnostic check failed";
      }
    }

    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify(healthPayload));
    return;
  }

  if (req.method === "GET" && pathname === "/.well-known/assetlinks.json") {
    const fingerprints = String(process.env.ANDROID_APP_SHA256_FINGERPRINTS || "")
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean);
    const body = fingerprints.length
      ? [
          {
            relation: ["delegate_permission/common.handle_all_urls"],
            target: {
              namespace: "android_app",
              package_name: "com.aistudio.vidvora.vstr",
              sha256_cert_fingerprints: fingerprints,
            },
          },
        ]
      : [];
    res.writeHead(200, { "Content-Type": "application/json", "Cache-Control": "public, max-age=3600" });
    res.end(JSON.stringify(body));
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
        userAgent: req.headers["user-agent"] || "",
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
      const msg = body?.message || body?.edited_message;
      const msgVideoLike = Boolean(msg && isAllowedTelegramVideoMessage(msg));
      const msgCombinedText = String(msg?.text || msg?.caption || "").trim();
      const isVidVoraConvert = Boolean(
        msg &&
          extractVidVoraShareTarget(msgCombinedText, msg, {
            strictVidVoraOnly: msgVideoLike,
          })
      );
      const isMediaUpload = Boolean(msgVideoLike && !isVidVoraConvert);

      if (isMediaUpload) {
        // Respond 200 OK immediately so Telegram never retries, and serialize heavy uploads 1-by-1 to stay under 512MB RAM
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true, accepted: true, async: true, queued: mediaQueueDepth + 1 }));
        enqueueTelegramMediaTask(() =>
          handleTelegramUpdate({
            update: body,
            db,
            storage,
            functionBaseUrl: publicBaseUrl,
            playbackSecret: PLAYBACK_SECRET,
          })
        ).catch(async (bgErr) => {
          console.error("Async telegramWebhook video processing error:", bgErr);
          try {
            const cfg = await resolveBotRuntimeConfig(db);
            if (cfg.botToken && msg?.chat?.id) {
              await telegramApiJson(cfg.apiBase, cfg.botToken, "sendMessage", {
                chat_id: msg.chat.id,
                text: `❌ Video processing error: ${bgErr?.message || "Please try sending the video again."}`,
              });
            }
          } catch (_) {
            // Best-effort error notice
          }
        });
        return;
      }

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

  if (pathname === "/financeTelegramWebhook" || pathname === "/financeWebhook") {
    if (req.method !== "POST") {
      res.writeHead(200, { "Content-Type": "text/plain" });
      res.end("VidVora Creator Finance Bot Webhook Active");
      return;
    }

    try {
      const { webhookSecret } = await resolveFinanceBotRuntimeConfig(db);
      if (webhookSecret) {
        const incomingSecret = String(req.headers["x-telegram-bot-api-secret-token"] || "").trim();
        if (incomingSecret !== webhookSecret) {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Unauthorized webhook secret");
          return;
        }
      }

      const body = await readJsonBody(req);
      const result = await handleFinanceTelegramUpdate({
        update: body,
        db,
      });

      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(result));
    } catch (err) {
      console.error("Standalone financeTelegramWebhook error:", err);
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: false, error: err.message }));
    }
    return;
  }

  if ((req.method === "GET" || req.method === "HEAD") && pathname === "/streamPublicVideo") {
    try {
      await handleStreamPublicVideo({
        videoId: urlObj.searchParams.get("videoId"),
        kind: urlObj.searchParams.get("kind"),
        sig: urlObj.searchParams.get("sig"),
        method: req.method,
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

  if (
    pathname === "/auth/send-signup-otp" ||
    pathname === "/auth/send-otp" ||
    pathname === "/auth/verify-signup-otp" ||
    pathname === "/auth/send-reset-otp" ||
    pathname === "/auth/verify-reset-otp"
  ) {
    res.setHeader("Access-Control-Allow-Origin", "*");
    res.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
    res.setHeader("Access-Control-Allow-Headers", "Content-Type");
    if (req.method === "OPTIONS") {
      res.writeHead(204);
      res.end();
      return;
    }
    if (req.method !== "POST") {
      res.writeHead(405, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: false, error: "Method not allowed. Use POST." }));
      return;
    }
    try {
      const body = await readJsonBody(req);
      let result;
      if (pathname === "/auth/send-signup-otp" || pathname === "/auth/send-otp") {
        result = await handleSendOtp({
          body,
          db,
          playbackSecret: PLAYBACK_SECRET,
          purpose: "signup",
        });
      } else if (pathname === "/auth/verify-signup-otp") {
        result = await handleVerifySignupOtp({
          body,
          db,
          playbackSecret: PLAYBACK_SECRET,
        });
      } else if (pathname === "/auth/send-reset-otp") {
        result = await handleSendOtp({
          body,
          db,
          playbackSecret: PLAYBACK_SECRET,
          purpose: "reset",
        });
      } else {
        result = await handleVerifyResetOtp({
          body,
          db,
          playbackSecret: PLAYBACK_SECRET,
        });
      }
      res.writeHead(result.statusCode || 200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(result.payload));
    } catch (err) {
      console.error("Auth OTP endpoint error:", err);
      res.writeHead(500, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: false, error: err?.message || "Internal auth error" }));
    }
    return;
  }

  res.writeHead(404, { "Content-Type": "text/plain" });
  res.end("Not found");
});

async function registerSingleWebhook({ label, botToken, apiBase, webhookSecret, desiredWebhookUrl }) {
  try {
    if (!botToken) return;
    const infoRes = await fetch(`${apiBase}/bot${botToken}/getWebhookInfo`, {
      signal: AbortSignal.timeout(15_000),
    });
    const infoData = await infoRes.json().catch(() => ({}));
    const currentUrl = infoData?.result?.url || "";
    const hasLastError = Boolean(infoData?.result?.last_error_message);
    if (infoData.ok && currentUrl === desiredWebhookUrl && !hasLastError && !webhookSecret) {
      console.log(`${label} webhook already active: ${desiredWebhookUrl}`);
      return;
    }

    const payload = {
      url: desiredWebhookUrl,
      allowed_updates: ["message", "edited_message"],
    };
    if (webhookSecret) {
      payload.secret_token = webhookSecret;
    }
    const setRes = await fetch(`${apiBase}/bot${botToken}/setWebhook`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
      signal: AbortSignal.timeout(15_000),
    });
    const setData = await setRes.json().catch(() => ({}));
    if (setData.ok) {
      console.log(`${label} webhook auto-registered: ${desiredWebhookUrl}`);
    } else {
      console.warn(`${label} webhook auto-register failed:`, setData.description || "Unknown error");
    }
  } catch (err) {
    console.warn(`${label} webhook auto-register skipped:`, err.message);
  }
}

async function autoRegisterTelegramWebhook() {
  try {
    const publicBase = (process.env.PUBLIC_BASE_URL || process.env.RENDER_EXTERNAL_URL || "")
      .trim()
      .replace(/\/+$/, "");
    if (!/^https:\/\//i.test(publicBase)) return;

    const uploadCfg = await resolveBotRuntimeConfig(db);
    await registerSingleWebhook({
      label: "Creator Upload Bot",
      botToken: uploadCfg.botToken,
      apiBase: uploadCfg.apiBase,
      webhookSecret: uploadCfg.webhookSecret,
      desiredWebhookUrl: `${publicBase}/telegramWebhook`,
    });

    const financeCfg = await resolveFinanceBotRuntimeConfig(db);
    await registerSingleWebhook({
      label: "Creator Finance Bot",
      botToken: financeCfg.botToken,
      apiBase: financeCfg.apiBase,
      webhookSecret: financeCfg.webhookSecret,
      desiredWebhookUrl: `${publicBase}/financeTelegramWebhook`,
    });
  } catch (err) {
    console.warn("Telegram webhook auto-register skipped:", err.message);
  }
}

server.listen(PORT, () => {
  console.log(`VidVora Telegram Bot server listening on port ${PORT}`);
  autoRegisterTelegramWebhook();
});
