const http = require("http");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const zlib = require("zlib");
const admin = require("firebase-admin");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { getStorage } = require("firebase-admin/storage");
const {
  SUPERADMIN_PASSKEY,
  hashToken,
  resolveBotRuntimeConfig,
  handleTelegramUpdate,
  handleStreamPublicVideo,
  extractVidVoraShareTarget,
  isAllowedTelegramVideoMessage,
  telegramApiJson,
  clearTelegramRuntimeCaches,
} = require("./telegramBot");
const {
  resolveFinanceBotRuntimeConfig,
  handleFinanceTelegramUpdate,
} = require("./financeBot");
const {
  handleSharePageRequest,
  handleShareThumbnailRequest,
  clearThumbBufferCache,
} = require("./sharePreview");
const {
  isEmailTransportConfigured,
  handleSendOtp,
  handleDirectSignup,
  handleVerifySignupOtp,
  handleVerifyResetOtp,
} = require("./emailOtp");
const {
  createPgFirestoreDb,
  serializeForRestApi,
} = require("./pgFirestoreAdapter");
const {
  handlePrivacyPolicyRequest,
  handlePrivacyPolicyPdfRequest,
} = require("./privacyPolicy");
const {
  getWpAutoPosterEnvConfig,
  convertAndPublishWordPressItem,
  runWordPressAutoSync,
  startWordPressAutoPosterScheduler,
  getWordPressAutoPosterStatus,
} = require("./wpAutoPoster");
const {
  ADMIN_COOKIE_NAME,
  createAdminDashboardToken,
  verifyAdminDashboardSession,
  extractDashboardTokenFromRequest,
  authenticateTeamPasskey,
  isTeamMemberActive,
  loadTeamAccessMembers,
  sanitizeTeamMembersForResponse,
  createTeamAccessMember,
  revokeTeamAccessMember,
  buildAdminDashboardStats,
  renderAdminDashboardHtml,
} = require("./adminDashboard");
const {
  createViewGeoTracker,
  extractClientIp,
} = require("./geoTracker");
const {
  resolveAppDownloadMetadata,
  renderApkDownloadWebsiteHtml,
  renderApkTemporarilyUnavailableHtml,
  normalizeDirectApkUrl,
  resolveGitHubReleaseApkUrl,
  resolveLocalReleaseApkPath,
  isRemoteApkReachable,
} = require("./downloadSite");
const { DEFAULT_APK_URL } = require("./sharePreview");

const dashboardLoginAttempts = new Map();
const DASHBOARD_LOGIN_WINDOW_MS = 5 * 60 * 1000;
const DASHBOARD_LOGIN_MAX_ATTEMPTS = 15;

function checkDashboardLoginRateLimit(clientIp) {
  const ip = String(clientIp || "unknown").trim() || "unknown";
  const now = Date.now();
  const rec = dashboardLoginAttempts.get(ip);
  if (!rec || now - rec.windowStart > DASHBOARD_LOGIN_WINDOW_MS) {
    return { allowed: true, ip };
  }
  return { allowed: rec.count < DASHBOARD_LOGIN_MAX_ATTEMPTS, ip };
}

function recordDashboardLoginFailure(clientIp) {
  const ip = String(clientIp || "unknown").trim() || "unknown";
  const now = Date.now();
  const rec = dashboardLoginAttempts.get(ip);
  if (!rec || now - rec.windowStart > DASHBOARD_LOGIN_WINDOW_MS) {
    dashboardLoginAttempts.set(ip, { count: 1, windowStart: now });
  } else {
    rec.count += 1;
  }
  if (dashboardLoginAttempts.size > 500) {
    for (const [k, v] of dashboardLoginAttempts.entries()) {
      if (now - v.windowStart > DASHBOARD_LOGIN_WINDOW_MS) dashboardLoginAttempts.delete(k);
    }
  }
}

function clearDashboardLoginFailures(clientIp) {
  const ip = String(clientIp || "unknown").trim();
  if (ip) dashboardLoginAttempts.delete(ip);
}

function isValidDashboardMutationRequest(req) {
  const xrw = String(req?.headers?.["x-requested-with"] || "").trim();
  if (xrw === "VidVora-Dashboard") return true;
  const origin = String(req?.headers?.origin || "").trim();
  if (!origin) return false;
  try {
    const originHost = new URL(origin).host.toLowerCase();
    const expectedHost = String(req?.headers?.["x-forwarded-host"] || req?.headers?.host || "")
      .split(",")[0]
      .trim()
      .toLowerCase();
    return Boolean(expectedHost) && originHost === expectedHost;
  } catch (_) {
    return false;
  }
}

function buildDashboardCookieHeader(token, isHttps, maxAgeSec = 604800) {
  const parts = [`${ADMIN_COOKIE_NAME}=${encodeURIComponent(token)}`, "HttpOnly", "Path=/", `Max-Age=${maxAgeSec}`];
  if (isHttps) {
    parts.push("Secure", "SameSite=None", "Partitioned");
  } else {
    parts.push("SameSite=Lax");
  }
  return parts.join("; ");
}

async function resolveActiveDashboardSession(req) {
  const token = extractDashboardTokenFromRequest(req);
  const session = verifyAdminDashboardSession(token, PLAYBACK_SECRET);
  if (!session) return null;
  if (session.role === "superadmin") return session;
  if (session.role === "team") {
    const active = await isTeamMemberActive(db, session.sub);
    return active ? session : null;
  }
  return null;
}

const DATABASE_ID =
  process.env.FIRESTORE_DATABASE_ID ||
  "ai-studio-android-garammas-738e4493-0090-419e-8ece-c55642a933ce";
const STORAGE_BUCKET =
  process.env.FIREBASE_STORAGE_BUCKET || "gen-lang-client-0000740810.firebasestorage.app";
const PLAYBACK_SECRET = process.env.PLAYBACK_SECRET || "vidvora-secure-playback-secret-key-2026";
const PORT = parseInt(process.env.PORT || "8080", 10);
const DATABASE_URL = String(process.env.DATABASE_URL || "").trim();
const EARNING_PER_VIEW_INR = 0.036; // ₹36 per 1,000 views

const ALLOWED_API_COLLECTIONS = new Set([
  "videos",
  "accessLinks",
  "members",
  "withdrawalRequests",
  "notifications",
  "creatorEvents",
  "appInstalls",
  "appConfig",
  "supportChats",
]);

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

const firestoreDb = getFirestore(DATABASE_ID);
const pgDb = DATABASE_URL ? createPgFirestoreDb({ connectionString: DATABASE_URL }) : null;
const db = pgDb || firestoreDb;
const activeDbBackend = pgDb ? "postgresql" : "firestore";
const storage = getStorage();
const viewGeoTracker = createViewGeoTracker();

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

function setCorsHeaders(res, methods = "GET, POST, OPTIONS") {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", methods);
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-VidVora-Key");
}

const MAX_GZIP_JSON_BYTES = 512 * 1024; // Avoid CPU/RAM spikes compressing multi-MB base64 thumbnails

function sendJson(res, statusCode, payload, req = null) {
  const jsonBuf = Buffer.isBuffer(payload)
    ? payload
    : Buffer.from(JSON.stringify(payload), "utf8");
  const acceptEncoding = String(req?.headers?.["accept-encoding"] || "").toLowerCase();
  if (
    jsonBuf.length > 1024 &&
    jsonBuf.length <= MAX_GZIP_JSON_BYTES &&
    acceptEncoding.includes("gzip")
  ) {
    zlib.gzip(jsonBuf, { level: 1 }, (gzErr, gzBuf) => {
      if (res.writableEnded || res.destroyed) return;
      if (!gzErr && gzBuf && gzBuf.length < jsonBuf.length) {
        res.writeHead(statusCode, {
          "Content-Type": "application/json; charset=utf-8",
          "Content-Encoding": "gzip",
          Vary: "Accept-Encoding",
          "Content-Length": gzBuf.length,
        });
        res.end(gzBuf);
        return;
      }
      res.writeHead(statusCode, {
        "Content-Type": "application/json; charset=utf-8",
        "Content-Length": jsonBuf.length,
      });
      res.end(jsonBuf);
    });
    return;
  }
  res.writeHead(statusCode, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": jsonBuf.length,
  });
  res.end(jsonBuf);
}

// Pre-serialized HTTP response buffer cache for GET /api/collection/:name
// Tightly bounded (12 MB max) to prevent off-heap Buffer RSS spikes on Render 512 MB instances.
const apiCollectionResponseCache = new Map(); // cacheKey -> { buf, bytes, structureVer, fullVer, builtAt }
const apiCollectionInflight = new Map(); // cacheKey -> Promise<Buffer>
let apiCollectionCacheTotalBytes = 0;
const MAX_API_COLLECTION_CACHE_ENTRIES = 6;
const MAX_API_COLLECTION_CACHE_BYTES = 12 * 1024 * 1024;
const VIEW_COUNTER_SOFT_TTL_MS = 15_000;

// Short-lived (20s) computed analytics cache + inflight dedupe so multiple Chrome tabs
// never trigger redundant full-collection scans or V8 heap cloning spikes.
const DASHBOARD_STATS_CACHE_TTL_MS = 20_000;
const dashboardStatsCache = new Map(); // daysKey -> { stats, builtAt }
const dashboardStatsInflight = new Map(); // daysKey -> Promise<stats>

function clearApiCollectionBufferCache() {
  apiCollectionResponseCache.clear();
  apiCollectionCacheTotalBytes = 0;
}

function setApiCollectionBufferCache(cacheKey, buf, ver) {
  const prev = apiCollectionResponseCache.get(cacheKey);
  if (prev) {
    apiCollectionCacheTotalBytes -= prev.bytes;
    apiCollectionResponseCache.delete(cacheKey);
  }
  const bytes = buf.length;
  if (bytes > MAX_API_COLLECTION_CACHE_BYTES) return;
  while (
    apiCollectionResponseCache.size >= MAX_API_COLLECTION_CACHE_ENTRIES ||
    apiCollectionCacheTotalBytes + bytes > MAX_API_COLLECTION_CACHE_BYTES
  ) {
    const oldestKey = apiCollectionResponseCache.keys().next().value;
    if (oldestKey === undefined) break;
    const oldest = apiCollectionResponseCache.get(oldestKey);
    apiCollectionResponseCache.delete(oldestKey);
    if (oldest) apiCollectionCacheTotalBytes -= oldest.bytes;
  }
  apiCollectionResponseCache.set(cacheKey, {
    buf,
    bytes,
    structureVer: ver.structure,
    fullVer: ver.full,
    builtAt: Date.now(),
  });
  apiCollectionCacheTotalBytes += bytes;
}

function currentUtcHourBucketKey(nowMs = Date.now()) {
  const d = new Date(nowMs);
  const yyyy = d.getUTCFullYear();
  const mm = String(d.getUTCMonth() + 1).padStart(2, "0");
  const dd = String(d.getUTCDate()).padStart(2, "0");
  const hh = String(d.getUTCHours()).padStart(2, "0");
  return `${yyyy}${mm}${dd}${hh}`;
}

function decodeRestWritePayload(val, useFirestoreSentinels = false) {
  if (val === null || val === undefined) return val;
  if (Array.isArray(val)) {
    return val.map((item) => decodeRestWritePayload(item, useFirestoreSentinels));
  }
  if (typeof val === "object") {
    if (val.__vidvora_server_ts__ === true) {
      return useFirestoreSentinels ? FieldValue.serverTimestamp() : new Date();
    }
    if (typeof val.__vidvora_ts_ms__ === "number" && Number.isFinite(val.__vidvora_ts_ms__)) {
      return new Date(val.__vidvora_ts_ms__);
    }
    if (typeof val.__vidvora_inc__ === "number" && Number.isFinite(val.__vidvora_inc__)) {
      return useFirestoreSentinels ? FieldValue.increment(val.__vidvora_inc__) : { __vidvora_inc__: val.__vidvora_inc__ };
    }
    const out = {};
    for (const [k, v] of Object.entries(val)) {
      if (v !== undefined) {
        out[k] = decodeRestWritePayload(v, useFirestoreSentinels);
      }
    }
    return out;
  }
  return val;
}

function resolveAllowedCollectionRef(targetDb, rawCollection, rawSubpath = "") {
  const cleanSub = String(rawSubpath || "").trim().replace(/^\/+|\/+$/g, "");
  if (cleanSub) {
    const subMatch = cleanSub.match(/^supportChats\/([a-zA-Z0-9_-]+)\/messages$/);
    if (!subMatch) return null;
    return targetDb.collection("supportChats").doc(subMatch[1]).collection("messages");
  }
  const cleanCol = String(rawCollection || "").trim();
  if (!ALLOWED_API_COLLECTIONS.has(cleanCol)) return null;
  return targetDb.collection(cleanCol);
}

function shouldCountView(uploadedBy, currentMemberId) {
  const uploader = String(uploadedBy || "").trim();
  const viewer = String(currentMemberId || "").trim();
  if (!uploader) return true;
  if (viewer && uploader.toLowerCase() === viewer.toLowerCase()) return false;
  return true;
}

async function creditVideoViewAndCreator({
  targetDb,
  videoId,
  uploadedBy,
  currentMemberId,
  req = null,
  clientIp = "",
  headers = null,
  videoTitle = "",
}) {
  if (!shouldCountView(uploadedBy, currentMemberId)) return false;
  const cleanVideoId = String(videoId || "").trim();
  if (!cleanVideoId) return false;

  const incOne = targetDb.isPostgresAdapter ? { __vidvora_inc__: 1 } : FieldValue.increment(1);

  try {
    await targetDb.collection("videos").doc(cleanVideoId).update({ totalViews: incOne });
  } catch (err) {
    console.warn("View count increment skipped:", err?.message || err);
  }

  const uploader = String(uploadedBy || "").trim();
  if (uploader && uploader.toLowerCase() !== "admin") {
    const hourKey = currentUtcHourBucketKey();
    try {
      const directSnap = await targetDb.collection("members").doc(uploader).get();
      let memberDocId = directSnap.exists ? uploader : null;
      if (!memberDocId) {
        const byField = await targetDb.collection("members").where("memberId", "==", uploader).limit(1).get();
        if (!byField.empty) {
          memberDocId = byField.docs[0].id;
        }
      }
      if (memberDocId) {
        await targetDb.collection("members").doc(memberDocId).update({
          [`hourlyViews.${hourKey}`]: incOne,
        });
      }
    } catch (err) {
      console.warn("Creator hourly view tracking skipped:", err?.message || err);
    }
  }

  if (req || clientIp || headers) {
    void viewGeoTracker
      .recordViewGeo({
        db: targetDb,
        req,
        clientIp,
        headers,
        videoId: cleanVideoId,
        videoTitle,
      })
      .catch(() => {});
  }
  return true;
}

const LEGACY_STREAM_VIEW_TTL_MS = 5 * 60 * 1000;
const MAX_LEGACY_STREAM_VIEW_CACHE = 2000;
const legacyStreamViewCache = new Map();

function recordLegacyDirectStreamView({ targetDb, shareCode, clientIp, headers = null }) {
  const cleanCode = String(shareCode || "").trim().toUpperCase();
  if (!cleanCode || !targetDb) return;
  const dedupeKey = `${String(clientIp || "unknown").trim()}|${cleanCode}`;
  const now = Date.now();
  const prevTs = legacyStreamViewCache.get(dedupeKey);
  if (prevTs && now - prevTs < LEGACY_STREAM_VIEW_TTL_MS) {
    return;
  }
  if (legacyStreamViewCache.size >= MAX_LEGACY_STREAM_VIEW_CACHE) {
    const oldestKey = legacyStreamViewCache.keys().next().value;
    if (oldestKey !== undefined) legacyStreamViewCache.delete(oldestKey);
  }
  legacyStreamViewCache.set(dedupeKey, now);

  // Fire-and-forget view credit so streaming response never delays
  (async () => {
    try {
      const tokenHash = hashToken(cleanCode);
      const linksSnap = await targetDb
        .collection("accessLinks")
        .where("tokenHash", "==", tokenHash)
        .limit(1)
        .get();
      let videoId = "";
      if (!linksSnap.empty) {
        const lData = linksSnap.docs[0].data() || {};
        if (lData.isActive === false) return;
        videoId = String(lData.videoId || "").trim();
      } else {
        videoId = cleanCode;
      }
      if (!videoId) return;
      const vSnap = await targetDb.collection("videos").doc(videoId).get();
      if (!vSnap.exists) return;
      const vData = vSnap.data() || {};
      if (vData.isActive === false) return;
      await creditVideoViewAndCreator({
        targetDb,
        videoId: String(vData.videoId || vSnap.id),
        uploadedBy: String(vData.uploadedBy || "admin"),
        currentMemberId: null,
        clientIp,
        headers,
        videoTitle: String(vData.title || ""),
      });
    } catch (err) {
      console.warn("Legacy direct stream view credit skipped:", err?.message || err);
    }
  })();
}

function hashSecretExact(secret) {
  return crypto.createHash("sha256").update(String(secret || "").trim(), "utf8").digest("hex");
}

async function verifyAdminMigrationKey(rawKey) {
  const cleanKey = String(rawKey || "").trim();
  if (!cleanKey) return false;
  if (cleanKey === SUPERADMIN_PASSKEY) return true;
  const envKey = String(process.env.ADMIN_MIGRATION_KEY || "").trim();
  if (envKey && cleanKey === envKey) return true;

  const candidateHash = hashSecretExact(cleanKey);
  for (const checkDb of [pgDb, firestoreDb].filter(Boolean)) {
    try {
      const secSnap = await checkDb.collection("appConfig").doc("adminSecurity").get();
      const storedHash = String(secSnap.exists ? secSnap.data()?.adminMasterKeyHash || "" : "").trim();
      if (storedHash && storedHash.toLowerCase() === candidateHash.toLowerCase()) {
        return true;
      }
    } catch (_) {
      // Ignore if Firestore quota exhausted during key check
    }
  }
  return false;
}

async function migrateFirestoreToPostgres() {
  if (!pgDb) {
    throw new Error("DATABASE_URL is not configured yet. Add your Neon/Supabase PostgreSQL DATABASE_URL in Render Environment Variables first.");
  }
  await pgDb.ensureInitialized();

  const collectionsToMigrate = [
    "appConfig",
    "members",
    "videos",
    "accessLinks",
    "withdrawalRequests",
    "notifications",
    "creatorEvents",
    "appInstalls",
    "telegramBotSessions",
    "telegramFinanceSessions",
    "supportChats",
  ];

  const migratedCounts = {};
  let totalMigrated = 0;

  for (const colName of collectionsToMigrate) {
    const snap = await firestoreDb.collection(colName).get();
    let count = 0;
    for (const doc of snap.docs) {
      const rawData = doc.data() || {};
      await pgDb.collection(colName).doc(doc.id).set(rawData, { merge: true });
      count += 1;
      totalMigrated += 1;

      if (colName === "supportChats") {
        const msgSnap = await firestoreDb
          .collection("supportChats")
          .doc(doc.id)
          .collection("messages")
          .get();
        let msgCount = 0;
        for (const msgDoc of msgSnap.docs) {
          await pgDb
            .collection("supportChats")
            .doc(doc.id)
            .collection("messages")
            .doc(msgDoc.id)
            .set(msgDoc.data() || {}, { merge: true });
          msgCount += 1;
          totalMigrated += 1;
        }
        migratedCounts[`supportChats/${doc.id}/messages`] = msgCount;
      }
    }
    migratedCounts[colName] = count;
  }

  return { migratedCounts, totalMigrated };
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

  const wantsHtmlHome =
    req.method === "GET" &&
    pathname === "/" &&
    !hasWatchParam &&
    urlObj.searchParams.get("check") !== "1" &&
    String(req.headers.accept || "").toLowerCase().includes("text/html");

  if (
    (req.method === "GET" || req.method === "HEAD") &&
    (pathname === "/health" || (pathname === "/" && !hasWatchParam && !wantsHtmlHome))
  ) {
    if (req.method === "HEAD") {
      res.writeHead(200, { "Content-Type": "application/json; charset=utf-8" });
      res.end();
      return;
    }
    const healthPayload = {
      ok: true,
      service: "VidVora Telegram Bot, Finance Bot & Share Preview Server",
      databaseBackend: activeDbBackend,
      postgresConfigured: Boolean(pgDb),
      uploadBotConfigured: Boolean((process.env.TELEGRAM_BOT_TOKEN || "").trim()),
      financeBotConfigured: Boolean((process.env.FINANCE_BOT_TOKEN || "").trim()),
      mtprotoConfigured: Boolean(
        (process.env.TELEGRAM_API_ID || "").trim() && (process.env.TELEGRAM_API_HASH || "").trim()
      ),
      otpEmailConfigured: isEmailTransportConfigured(),
      wpAutoPosterConfigured: getWpAutoPosterEnvConfig().enabled,
      mediaQueueDepth,
    };

    if (urlObj.searchParams.get("check") === "1") {
      if (pgDb) {
        try {
          await pgDb.healthCheck();
          healthPayload.databaseConnected = true;
        } catch (pgErr) {
          healthPayload.databaseConnected = false;
          healthPayload.databaseError = pgErr?.message || "PostgreSQL connection check failed";
        }
      }
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

    sendJson(res, 200, healthPayload);
    return;
  }

  // ==================== REST API FOR ANDROID APP & ADMIN ANALYTICS ====================
  if (pathname.startsWith("/api/")) {
    const isDashboardApi = pathname.startsWith("/api/admin-dashboard/");
    if (!isDashboardApi) {
      setCorsHeaders(res, "GET, POST, OPTIONS");
      if (req.method === "OPTIONS") {
        res.writeHead(204);
        res.end();
        return;
      }
    } else if (req.method === "OPTIONS") {
      res.writeHead(204);
      res.end();
      return;
    }

    // 1. Lightweight backend status check
    if (req.method === "GET" && pathname === "/api/status") {
      const mem = process.memoryUsage();
      sendJson(res, 200, {
        ok: true,
        databaseBackend: activeDbBackend,
        postgresActive: Boolean(pgDb),
        memoryMb: {
          rss: Math.round(mem.rss / (1024 * 1024)),
          heapUsed: Math.round(mem.heapUsed / (1024 * 1024)),
          external: Math.round(mem.external / (1024 * 1024)),
        },
        cacheStats: pgDb && typeof pgDb.getCacheStats === "function" ? pgDb.getCacheStats() : null,
      }, req);
      return;
    }

    // 1b. Chrome Admin & Team Analytics Dashboard Auth, Team Access & Stats APIs
    if (req.method === "POST" && pathname === "/api/admin-dashboard/login") {
      try {
        const clientIp = extractClientIp(req);
        const rateCheck = checkDashboardLoginRateLimit(clientIp);
        if (!rateCheck.allowed) {
          sendJson(res, 429, {
            ok: false,
            error: "Too many failed attempts. Please wait 5 minutes and try again.",
          });
          return;
        }
        const body = await readJsonBody(req);
        const rawPasskey = String(body.passkey || body.key || "").trim();
        let sessionInfo = null;
        if (await verifyAdminMigrationKey(rawPasskey)) {
          sessionInfo = { role: "superadmin", sub: "superadmin", label: "Superadmin" };
        } else {
          const teamMatch = await authenticateTeamPasskey(db, rawPasskey);
          if (teamMatch) {
            sessionInfo = { role: "team", sub: teamMatch.id, label: teamMatch.label };
          }
        }
        if (!sessionInfo) {
          recordDashboardLoginFailure(clientIp);
          sendJson(res, 401, { ok: false, error: "Invalid Superadmin or Team Passkey." });
          return;
        }
        clearDashboardLoginFailures(clientIp);
        const token = createAdminDashboardToken(PLAYBACK_SECRET, sessionInfo);
        const isHttps = String(req.headers["x-forwarded-proto"] || "").toLowerCase().includes("https");
        res.setHeader("Set-Cookie", buildDashboardCookieHeader(token, isHttps, 604800));
        res.setHeader("Cache-Control", "no-store");
        sendJson(res, 200, { ok: true, role: sessionInfo.role, label: sessionInfo.label });
      } catch (loginErr) {
        sendJson(res, 400, { ok: false, error: loginErr?.message || "Login failed" });
      }
      return;
    }

    if (req.method === "POST" && pathname === "/api/admin-dashboard/logout") {
      if (!isValidDashboardMutationRequest(req)) {
        sendJson(res, 403, { ok: false, error: "Invalid request origin." });
        return;
      }
      const isHttps = String(req.headers["x-forwarded-proto"] || "").toLowerCase().includes("https");
      res.setHeader("Set-Cookie", buildDashboardCookieHeader("", isHttps, 0));
      sendJson(res, 200, { ok: true });
      return;
    }

    if (req.method === "GET" && pathname === "/api/admin-dashboard/team") {
      const session = await resolveActiveDashboardSession(req);
      if (!session) {
        sendJson(res, 401, { ok: false, error: "Unauthorized." });
        return;
      }
      if (session.role !== "superadmin") {
        sendJson(res, 403, { ok: false, error: "Superadmin access required." });
        return;
      }
      try {
        const members = sanitizeTeamMembersForResponse(await loadTeamAccessMembers(db));
        res.setHeader("Cache-Control", "no-store");
        sendJson(res, 200, { ok: true, members });
      } catch (teamErr) {
        sendJson(res, 500, { ok: false, error: teamErr?.message || "Failed to load team list" });
      }
      return;
    }

    if (req.method === "POST" && pathname === "/api/admin-dashboard/team") {
      if (!isValidDashboardMutationRequest(req)) {
        sendJson(res, 403, { ok: false, error: "Invalid request origin." });
        return;
      }
      const session = await resolveActiveDashboardSession(req);
      if (!session) {
        sendJson(res, 401, { ok: false, error: "Unauthorized." });
        return;
      }
      if (session.role !== "superadmin") {
        sendJson(res, 403, { ok: false, error: "Only Superadmin can create team logins." });
        return;
      }
      try {
        const body = await readJsonBody(req);
        const created = await createTeamAccessMember(db, {
          label: body.label,
          passkey: body.passkey,
        });
        res.setHeader("Cache-Control", "no-store");
        sendJson(res, 200, { ok: true, ...created });
      } catch (createErr) {
        sendJson(res, 400, { ok: false, error: createErr?.message || "Failed to create team login" });
      }
      return;
    }

    if (req.method === "POST" && pathname === "/api/admin-dashboard/team/revoke") {
      if (!isValidDashboardMutationRequest(req)) {
        sendJson(res, 403, { ok: false, error: "Invalid request origin." });
        return;
      }
      const session = await resolveActiveDashboardSession(req);
      if (!session) {
        sendJson(res, 401, { ok: false, error: "Unauthorized." });
        return;
      }
      if (session.role !== "superadmin") {
        sendJson(res, 403, { ok: false, error: "Only Superadmin can revoke team logins." });
        return;
      }
      try {
        const body = await readJsonBody(req);
        const members = await revokeTeamAccessMember(db, body.id);
        res.setHeader("Cache-Control", "no-store");
        sendJson(res, 200, { ok: true, members });
      } catch (revokeErr) {
        sendJson(res, 400, { ok: false, error: revokeErr?.message || "Failed to revoke team login" });
      }
      return;
    }

    if (req.method === "GET" && pathname === "/api/admin-dashboard/stats") {
      const session = await resolveActiveDashboardSession(req);
      if (!session) {
        sendJson(res, 401, { ok: false, error: "Unauthorized. Please log in with Admin or Team Passkey." });
        return;
      }
      try {
        const rawDays = parseInt(urlObj.searchParams.get("days") || "14", 10);
        const safeDays = [7, 14, 30].includes(rawDays) ? rawDays : 14;
        const cacheKey = String(safeDays);
        const nowMs = Date.now();
        const cachedEntry = dashboardStatsCache.get(cacheKey);

        let baseStats = null;
        if (cachedEntry && nowMs - cachedEntry.builtAt < DASHBOARD_STATS_CACHE_TTL_MS) {
          baseStats = cachedEntry.stats;
        } else if (dashboardStatsInflight.has(cacheKey)) {
          baseStats = await dashboardStatsInflight.get(cacheKey);
        } else {
          const computePromise = (async () => {
            const readColFast = async (colName) => {
              if (db && typeof db.getRawCollectionEntries === "function") {
                const entries = await db.getRawCollectionEntries(colName);
                // Pass lightweight shallow objects without deep-cloning base64 thumbnails
                return entries.map((e) => {
                  const r = e.raw || {};
                  if (colName === "videos" && r.thumbnailPath) {
                    const { thumbnailPath: _drop, ...slim } = r;
                    return { _id: e.id, ...slim };
                  }
                  return { _id: e.id, ...r };
                });
              }
              const snap = await db.collection(colName).get();
              return snap.docs.map((d) => ({ _id: d.id, ...(d.data() || {}) }));
            };

            const [installs, videos, members, withdrawals, geoStats] = await Promise.all([
              readColFast("appInstalls"),
              readColFast("videos"),
              readColFast("members"),
              readColFast("withdrawalRequests"),
              viewGeoTracker.getMergedGeoDoc(db),
            ]);

            const built = buildAdminDashboardStats({
              installs,
              videos,
              members,
              withdrawals,
              geoStats,
              days: safeDays,
            });
            dashboardStatsCache.set(cacheKey, { stats: built, builtAt: Date.now() });
            return built;
          })().finally(() => {
            dashboardStatsInflight.delete(cacheKey);
          });
          dashboardStatsInflight.set(cacheKey, computePromise);
          baseStats = await computePromise;
        }

        res.setHeader("Cache-Control", "no-store");
        sendJson(res, 200, { ...baseStats, viewer: { role: session.role, label: session.label } }, req);
      } catch (statsErr) {
        console.error("Admin dashboard stats error:", statsErr);
        sendJson(res, 500, { ok: false, error: statsErr?.message || "Failed to build analytics" });
      }
      return;
    }

    // 2. Resolve video link / code and record view + creator earning
    if (req.method === "POST" && pathname === "/api/resolve-video") {
      try {
        const body = await readJsonBody(req);
        const rawToken = String(body.token || "").trim();
        const currentMemberId = String(body.currentMemberId || "").trim() || null;
        if (!rawToken) {
          sendJson(res, 400, { ok: false, status: "error", error: "Missing token" });
          return;
        }

        let effectiveToken = rawToken;
        if (/^https?:\/\//i.test(rawToken)) {
          try {
            const parsedTokenUrl = new URL(rawToken);
            const embeddedCode = String(parsedTokenUrl.searchParams.get("vv") || "").trim();
            if (embeddedCode) {
              effectiveToken = embeddedCode;
            }
          } catch (_) {
            // Keep rawToken as-is
          }
        }

        const cleanUpper = effectiveToken.toUpperCase();
        const keyCandidates = Array.from(new Set([effectiveToken, effectiveToken.toLowerCase(), cleanUpper]));
        let resolvedVideoSnap = null;

        const tokenHash = hashToken(effectiveToken);
        const linksQuery = await db
          .collection("accessLinks")
          .where("tokenHash", "==", tokenHash)
          .limit(1)
          .get();

        let linkSnap = !linksQuery.empty ? linksQuery.docs[0] : null;
        if (!linkSnap) {
          for (const candidate of keyCandidates) {
            const snap = await db.collection("accessLinks").doc(candidate).get();
            if (snap.exists) {
              linkSnap = snap;
              break;
            }
          }
        }

        if (linkSnap && linkSnap.exists) {
          const linkData = linkSnap.data() || {};
          if (linkData.isActive === false) {
            sendJson(res, 200, { ok: true, backend: activeDbBackend, status: "deactivated" });
            return;
          }
          if (linkData.expiresAt) {
            const expMs =
              typeof linkData.expiresAt.toMillis === "function"
                ? linkData.expiresAt.toMillis()
                : new Date(linkData.expiresAt).getTime();
            if (Number.isFinite(expMs) && expMs < Date.now()) {
              sendJson(res, 200, { ok: true, backend: activeDbBackend, status: "deactivated" });
              return;
            }
          }
          const linkedVideoId = String(linkData.videoId || "").trim();
          if (linkedVideoId) {
            const vSnap = await db.collection("videos").doc(linkedVideoId).get();
            if (vSnap.exists) {
              resolvedVideoSnap = vSnap;
            }
          }
        }

        if (!resolvedVideoSnap) {
          for (const candidate of keyCandidates) {
            const vSnap = await db.collection("videos").doc(candidate).get();
            if (vSnap.exists) {
              resolvedVideoSnap = vSnap;
              break;
            }
          }
        }

        if (!resolvedVideoSnap && /^https?:\/\//i.test(rawToken)) {
          const byUrl = await db
            .collection("videos")
            .where("isActive", "==", true)
            .where("storagePath", "==", rawToken)
            .limit(1)
            .get();
          if (!byUrl.empty) {
            resolvedVideoSnap = byUrl.docs[0];
          }
        }

        if (!resolvedVideoSnap || !resolvedVideoSnap.exists) {
          sendJson(res, 200, { ok: true, backend: activeDbBackend, status: "not_found" });
          return;
        }

        const vData = resolvedVideoSnap.data() || {};
        const videoId = String(vData.videoId || resolvedVideoSnap.id);
        if (vData.isActive === false) {
          sendJson(res, 200, { ok: true, backend: activeDbBackend, status: "deactivated" });
          return;
        }

        const uploadedBy = String(vData.uploadedBy || "admin");
        const isSelfCreatorView =
          Boolean(currentMemberId) &&
          String(currentMemberId).trim() === uploadedBy;
        const currentViews = Number(vData.totalViews || 0) || 0;
        const serializedVideo = serializeForRestApi({
          ...vData,
          videoId,
          totalViews: isSelfCreatorView ? currentViews : currentViews + 1,
        });

        // Respond immediately for fast video start; persist view/creator earnings credit asynchronously
        sendJson(res, 200, {
          ok: true,
          backend: activeDbBackend,
          status: "success",
          video: serializedVideo,
        });

        void creditVideoViewAndCreator({
          targetDb: db,
          videoId,
          uploadedBy,
          currentMemberId,
          req,
          videoTitle: String(vData.title || ""),
        }).catch((creditErr) => {
          console.warn("Background creditVideoViewAndCreator error:", creditErr?.message || creditErr);
        });
      } catch (err) {
        console.error("API /api/resolve-video error:", err);
        sendJson(res, 500, { ok: false, status: "error", error: err?.message || "Resolve error" });
      }
      return;
    }

    // 3. Direct view & creator earning credit (for in-panel Watch button)
    if (req.method === "POST" && pathname === "/api/credit-view") {
      try {
        const body = await readJsonBody(req);
        const credited = await creditVideoViewAndCreator({
          targetDb: db,
          videoId: body.videoId,
          uploadedBy: body.uploadedBy,
          currentMemberId: body.currentMemberId,
          req,
          videoTitle: String(body.videoTitle || ""),
        });
        sendJson(res, 200, { ok: true, backend: activeDbBackend, credited });
      } catch (err) {
        console.error("API /api/credit-view error:", err);
        sendJson(res, 500, { ok: false, error: err?.message || "Credit view failed" });
      }
      return;
    }

    // 4. 1-click Firestore -> PostgreSQL migration (Superadmin protected)
    if (pathname === "/api/migrate-from-firestore") {
      try {
        let adminKey = urlObj.searchParams.get("key") || req.headers["x-vidvora-key"] || "";
        if (!adminKey && req.method === "POST") {
          const body = await readJsonBody(req).catch(() => ({}));
          adminKey = body.key || "";
        }
        const authorized = await verifyAdminMigrationKey(adminKey);
        if (!authorized) {
          sendJson(res, 403, {
            ok: false,
            error: "Invalid Superadmin key. Pass ?key=YOUR_SUPERADMIN_KEY",
          });
          return;
        }
        const result = await migrateFirestoreToPostgres();
        sendJson(res, 200, {
          ok: true,
          message: "Firestore data successfully copied to PostgreSQL!",
          ...result,
        });
      } catch (err) {
        console.error("Migration error:", err);
        const msg = String(err?.message || "Migration failed");
        const isQuota = /RESOURCE_EXHAUSTED|Quota limit exceeded/i.test(msg);
        sendJson(res, isQuota ? 429 : 500, {
          ok: false,
          error: isQuota
            ? "Firestore quota abhi reset nahi hua hai. Dopahar ~1:30 PM IST ke baad yeh link dubara kholiyega — aapka saara purana data automatically PostgreSQL me copy ho jayega."
            : msg,
        });
      }
      return;
    }

    // 5. Query collection: GET /api/collection/:name
    const colMatch = pathname.match(/^\/api\/collection\/([^/]+)$/);
    if (req.method === "GET" && colMatch) {
      try {
        const colName = decodeURIComponent(colMatch[1]);
        const subpath = urlObj.searchParams.get("subpath") || "";
        const colRef = resolveAllowedCollectionRef(db, colName, subpath);
        if (!colRef) {
          sendJson(res, 403, { ok: false, error: "Collection not allowed" });
          return;
        }

        const effectiveColKey = subpath.trim().replace(/^\/+|\/+$/g, "") || colName;
        const responseCacheKey = `${effectiveColKey}?${urlObj.searchParams.toString()}`;
        const canUseResponseCache = db.isPostgresAdapter && typeof db.getCollectionVersion === "function";
        const currentVer = canUseResponseCache
          ? db.getCollectionVersion(effectiveColKey)
          : { structure: -1, full: -1 };

        if (canUseResponseCache) {
          const cachedResp = apiCollectionResponseCache.get(responseCacheKey);
          if (
            cachedResp &&
            cachedResp.structureVer === currentVer.structure &&
            (cachedResp.fullVer === currentVer.full ||
              Date.now() - cachedResp.builtAt < VIEW_COUNTER_SOFT_TTL_MS)
          ) {
            sendJson(res, 200, cachedResp.buf, req);
            return;
          }
          if (apiCollectionInflight.has(responseCacheKey)) {
            const sharedBuf = await apiCollectionInflight.get(responseCacheKey);
            sendJson(res, 200, sharedBuf, req);
            return;
          }
        }

        const buildCollectionBuffer = async () => {
          let queryRef = colRef;
          const isActiveParam = urlObj.searchParams.get("isActive");
          if (isActiveParam === "true" || isActiveParam === "false") {
            queryRef = queryRef.where("isActive", "==", isActiveParam === "true");
          }
          const field1 = urlObj.searchParams.get("field");
          const value1 = urlObj.searchParams.get("value");
          if (field1 && value1 !== null) {
            queryRef = queryRef.where(field1, "==", value1);
          }
          const field2 = urlObj.searchParams.get("field2");
          const value2 = urlObj.searchParams.get("value2");
          if (field2 && value2 !== null) {
            queryRef = queryRef.where(field2, "==", value2);
          }
          const limitParam = parseInt(urlObj.searchParams.get("limit") || "0", 10);
          if (Number.isFinite(limitParam) && limitParam > 0) {
            queryRef = queryRef.limit(Math.min(limitParam, 2000));
          }

          const snap = await queryRef.get();
          const docs = snap.docs.map((doc) => ({
            id: doc.id,
            data: serializeForRestApi(doc.data() || {}),
          }));
          const buf = Buffer.from(
            JSON.stringify({ ok: true, backend: activeDbBackend, docs }),
            "utf8"
          );
          if (canUseResponseCache) {
            const latestVer = db.getCollectionVersion(effectiveColKey);
            setApiCollectionBufferCache(responseCacheKey, buf, latestVer);
          }
          return buf;
        };

        let responseBuf;
        if (canUseResponseCache) {
          const p = buildCollectionBuffer().finally(() => {
            apiCollectionInflight.delete(responseCacheKey);
          });
          apiCollectionInflight.set(responseCacheKey, p);
          responseBuf = await p;
        } else {
          responseBuf = await buildCollectionBuffer();
        }
        sendJson(res, 200, responseBuf, req);
      } catch (err) {
        console.error("API GET collection error:", err);
        sendJson(res, 500, { ok: false, error: err?.message || "Collection query failed" });
      }
      return;
    }

    // 6. Document read/write: GET/POST /api/doc/:collection/:docId
    const docMatch = pathname.match(/^\/api\/doc\/([^/]+)\/([^/]+)$/);
    if (docMatch) {
      try {
        const colName = decodeURIComponent(docMatch[1]);
        const docId = decodeURIComponent(docMatch[2]);
        const subpath = urlObj.searchParams.get("subpath") || "";
        const colRef = resolveAllowedCollectionRef(db, colName, subpath);
        if (!colRef || !docId) {
          sendJson(res, 403, { ok: false, error: "Collection or document not allowed" });
          return;
        }
        const docRef = colRef.doc(docId);

        if (req.method === "GET") {
          const snap = await docRef.get();
          sendJson(res, 200, {
            ok: true,
            backend: activeDbBackend,
            exists: snap.exists,
            id: snap.id,
            data: snap.exists ? serializeForRestApi(snap.data() || {}) : null,
          }, req);
          return;
        }

        if (req.method === "POST") {
          const body = await readJsonBody(req);
          const action = String(body.action || "set").toLowerCase();
          const useFirestoreSentinels = !db.isPostgresAdapter;

          if (action === "delete") {
            if (colName === "supportChats" && !subpath) {
              const msgs = await docRef.collection("messages").get();
              for (const msgDoc of msgs.docs) {
                await docRef.collection("messages").doc(msgDoc.id).delete();
              }
            }
            await docRef.delete();
            sendJson(res, 200, { ok: true, backend: activeDbBackend, id: docId, action: "delete" });
            return;
          }

          const decodedData = decodeRestWritePayload(body.data || {}, useFirestoreSentinels);
          if (action === "merge") {
            await docRef.set(decodedData, { merge: true });
          } else if (action === "update") {
            await docRef.update(decodedData);
          } else {
            await docRef.set(decodedData);
          }
          sendJson(res, 200, { ok: true, backend: activeDbBackend, id: docId, action });
          return;
        }
      } catch (err) {
        console.error("API doc error:", err);
        sendJson(res, 500, { ok: false, error: err?.message || "Document operation failed" });
      }
      return;
    }

    sendJson(res, 404, { ok: false, error: "API route not found" });
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

  if ((req.method === "GET" || req.method === "HEAD") && (pathname === "/download-apk" || pathname === "/download-apk/")) {
    try {
      const meta = await resolveAppDownloadMetadata(db);
      const primaryUrl = normalizeDirectApkUrl(meta.apkUrl);
      const isCustomPrimaryUrl = primaryUrl !== DEFAULT_APK_URL;
      const localApk = resolveLocalReleaseApkPath();
      const githubApkUrl = resolveGitHubReleaseApkUrl();

      const primaryOk = await isRemoteApkReachable(primaryUrl);
      const githubOk = !primaryOk && !localApk && githubApkUrl ? await isRemoteApkReachable(githubApkUrl) : false;

      if (urlObj.searchParams.get("check") === "1") {
        sendJson(res, 200, {
          ok: Boolean(primaryOk || isCustomPrimaryUrl || localApk || githubOk),
          versionName: meta.versionName,
          versionCode: meta.versionCode,
          primaryReachable: primaryOk,
          isCustomPrimaryUrl,
          localApkFound: Boolean(localApk),
          localApkBytes: localApk ? localApk.size : 0,
          githubReleaseConfigured: Boolean(githubApkUrl),
          githubReachable: githubOk,
        });
        return;
      }

      if (primaryOk || isCustomPrimaryUrl) {
        res.writeHead(302, {
          Location: primaryUrl,
          "Cache-Control": "no-store, max-age=0",
          "X-Content-Type-Options": "nosniff",
        });
        res.end();
        return;
      }

      if (localApk) {
        const safeFileVer = String(meta.versionName || "1.8").replace(/[^a-zA-Z0-9._-]/g, "");
        const fileName = safeFileVer ? `VidVora-v${safeFileVer}.apk` : "VidVora.apk";
        res.writeHead(200, {
          "Content-Type": "application/vnd.android.package-archive",
          "Content-Length": String(localApk.size),
          "Content-Disposition": `attachment; filename="${fileName}"`,
          "Cache-Control": "public, max-age=300",
          "X-Content-Type-Options": "nosniff",
        });
        if (req.method === "HEAD") {
          res.end();
        } else {
          const stream = fs.createReadStream(localApk.filePath);
          stream.on("error", (streamErr) => {
            console.error("Local APK stream error:", streamErr);
            if (!res.headersSent) {
              res.writeHead(500, { "Content-Type": "text/plain" });
            }
            res.end();
          });
          res.on("close", () => {
            stream.destroy();
          });
          stream.pipe(res);
        }
        return;
      }

      if (githubOk && githubApkUrl) {
        res.writeHead(302, {
          Location: githubApkUrl,
          "Cache-Control": "no-store, max-age=0",
          "X-Content-Type-Options": "nosniff",
        });
        res.end();
        return;
      }

      const unavailableHtml = renderApkTemporarilyUnavailableHtml({ versionName: meta.versionName });
      res.writeHead(503, {
        "Content-Type": "text/html; charset=utf-8",
        "Cache-Control": "no-store, max-age=0",
        "X-Content-Type-Options": "nosniff",
      });
      if (req.method === "HEAD") {
        res.end();
      } else {
        res.end(unavailableHtml);
      }
    } catch (err) {
      console.error("APK download route error:", err);
      if (!res.headersSent) {
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("APK download unavailable");
      }
    }
    return;
  }

  if (
    (req.method === "GET" || req.method === "HEAD") &&
    (wantsHtmlHome ||
      pathname === "/download" ||
      pathname === "/download/" ||
      pathname === "/apk" ||
      pathname === "/apk/" ||
      pathname === "/app" ||
      pathname === "/app/")
  ) {
    try {
      const meta = await resolveAppDownloadMetadata(db);
      const html = renderApkDownloadWebsiteHtml({
        baseUrl: resolvePublicHostBaseUrl(req),
        versionName: meta.versionName,
        versionCode: meta.versionCode,
        apkUrl: meta.apkUrl,
        releaseNotes: meta.releaseNotes,
      });
      res.writeHead(200, {
        "Content-Type": "text/html; charset=utf-8",
        "Cache-Control": "public, max-age=120",
        "X-Content-Type-Options": "nosniff",
      });
      if (req.method === "HEAD") {
        res.end();
      } else {
        res.end(html);
      }
    } catch (err) {
      console.error("APK download site error:", err);
      if (!res.headersSent) {
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Download page error");
      }
    }
    return;
  }

  if ((req.method === "GET" || req.method === "HEAD") && (pathname === "/privacy-policy" || pathname === "/privacy")) {
    handlePrivacyPolicyRequest({
      res,
      method: req.method,
      hostBaseUrl: resolvePublicHostBaseUrl(req),
    });
    return;
  }

  if ((req.method === "GET" || req.method === "HEAD") && pathname === "/privacy-policy.pdf") {
    try {
      await handlePrivacyPolicyPdfRequest({
        res,
        method: req.method,
      });
    } catch (err) {
      console.error("Privacy Policy PDF error:", err);
      if (!res.headersSent) {
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Privacy policy PDF error");
      }
    }
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
        videoId: urlObj.searchParams.get("videoId") || urlObj.searchParams.get("v"),
        link: urlObj.searchParams.get("link"),
        method: req.method,
        res,
        db,
        storage,
        resolveBotConfig: () => resolveBotRuntimeConfig(db),
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

  if (
    (req.method === "GET" || req.method === "HEAD") &&
    (pathname === "/vidvora-logo.png" ||
      pathname === "/vidvora-icon.png" ||
      pathname === "/favicon.ico")
  ) {
    const fileName = pathname === "/vidvora-logo.png" ? "vidvora-logo.png" : "vidvora-icon.png";
    const assetPath = path.join(__dirname, fileName);
    if (!server._brandAssetCache) server._brandAssetCache = new Map();
    let buf = server._brandAssetCache.get(fileName);
    if (!buf) {
      try {
        buf = fs.readFileSync(assetPath);
        server._brandAssetCache.set(fileName, buf);
      } catch (_) {
        res.writeHead(404, { "Content-Type": "text/plain" });
        res.end("Logo asset not found");
        return;
      }
    }
    res.writeHead(200, {
      "Content-Type": "image/png",
      "Cache-Control": "public, max-age=86400",
      "Content-Length": buf.length,
    });
    if (req.method === "HEAD") {
      res.end();
    } else {
      res.end(buf);
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
              const rawMsg = String(bgErr?.message || "");
              const isQuota = /RESOURCE_EXHAUSTED|Quota limit exceeded/i.test(rawMsg);
              const friendlyText = isQuota
                ? "⚠️ Aaj ka Firebase Firestore Free Daily Quota (50,000 reads/day) poora ho gaya hai.\n\nUnlimited FREE chalane ke liye Render Environment me Neon PostgreSQL ka DATABASE_URL add karein."
                : `❌ Video processing error: ${rawMsg || "Please try sending the video again."}`;
              await telegramApiJson(cfg.apiBase, cfg.botToken, "sendMessage", {
                chat_id: msg.chat.id,
                text: friendlyText,
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
      const legacyShareCode = String(urlObj.searchParams.get("vv") || "").trim();
      if (req.method === "GET" && legacyShareCode && db && db.isPostgresAdapter === true) {
        const clientIp =
          extractClientIp(req) ||
          String(req.headers["x-forwarded-for"] || "")
            .split(",")[0]
            .trim() ||
          req.socket?.remoteAddress ||
          "unknown";
        recordLegacyDirectStreamView({
          targetDb: db,
          shareCode: legacyShareCode,
          clientIp,
          headers: req.headers,
        });
      }
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
    pathname === "/auth/direct-signup" ||
    pathname === "/auth/signup" ||
    pathname === "/auth/send-signup-otp" ||
    pathname === "/auth/send-otp" ||
    pathname === "/auth/verify-signup-otp" ||
    pathname === "/auth/send-reset-otp" ||
    pathname === "/auth/verify-reset-otp"
  ) {
    setCorsHeaders(res, "POST, OPTIONS");
    if (req.method === "OPTIONS") {
      res.writeHead(204);
      res.end();
      return;
    }
    if (req.method !== "POST") {
      sendJson(res, 405, { ok: false, error: "Method not allowed. Use POST." });
      return;
    }
    try {
      const body = await readJsonBody(req);
      let result;
      if (pathname === "/auth/direct-signup" || pathname === "/auth/signup") {
        result = await handleDirectSignup({
          body,
          db,
        });
      } else if (pathname === "/auth/send-signup-otp" || pathname === "/auth/send-otp") {
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
      sendJson(res, result.statusCode || 200, result.payload);
    } catch (err) {
      console.error("Auth OTP endpoint error:", err);
      sendJson(res, 500, { ok: false, error: err?.message || "Internal auth error" });
    }
    return;
  }

  // ==================== WORDPRESS AUTO-VIDEO CONVERTER & TELEGRAM CHANNEL AUTO-POSTER ====================
  if (
    pathname === "/wp-autopost/status" ||
    pathname === "/wp-autopost/sync" ||
    pathname === "/wp-autopost/webhook"
  ) {
    setCorsHeaders(res, "GET, POST, OPTIONS");
    if (req.method === "OPTIONS") {
      res.writeHead(204);
      res.end();
      return;
    }

    const wpCfg = getWpAutoPosterEnvConfig();
    let parsedBody = {};
    if (req.method === "POST") {
      try {
        parsedBody = await readJsonBody(req);
      } catch (bodyErr) {
        sendJson(res, 400, { ok: false, error: `Invalid JSON body: ${bodyErr?.message || "parse error"}` });
        return;
      }
    }

    const suppliedSecret = String(
      urlObj.searchParams.get("secret") ||
        urlObj.searchParams.get("key") ||
        req.headers["x-vidvora-key"] ||
        req.headers["x-wp-secret"] ||
        parsedBody?.secret ||
        ""
    ).trim();

    if (wpCfg.secret) {
      const isAuthorized =
        suppliedSecret === wpCfg.secret || (await verifyAdminMigrationKey(suppliedSecret));
      if (!isAuthorized) {
        sendJson(res, 403, {
          ok: false,
          error: "Unauthorized. Pass ?secret=YOUR_WP_AUTOPOST_SECRET (or X-WP-Secret header).",
        });
        return;
      }
    }

    if (pathname === "/wp-autopost/status") {
      sendJson(res, 200, { ok: true, ...getWordPressAutoPosterStatus() });
      return;
    }

    if (pathname === "/wp-autopost/sync") {
      try {
        const result = await runWordPressAutoSync({
          db,
          publicBaseUrl: resolvePublicHostBaseUrl(req),
        });
        sendJson(res, result.ok === false ? 400 : 200, result);
      } catch (syncErr) {
        console.error("WP Auto-Poster sync error:", syncErr);
        sendJson(res, 500, { ok: false, error: syncErr?.message || "WordPress sync failed" });
      }
      return;
    }

    if (pathname === "/wp-autopost/webhook") {
      if (req.method !== "POST") {
        sendJson(res, 200, {
          ok: true,
          message: "VidVora WordPress Auto-Poster Webhook Active. Send POST JSON with title & videoUrl.",
        });
        return;
      }
      try {
        const targetChannels = parsedBody.channelId || parsedBody.channels || wpCfg.channels;
        const result = await convertAndPublishWordPressItem({
          db,
          item: parsedBody,
          siteUrl: parsedBody.siteUrl || wpCfg.siteUrl,
          channels: targetChannels,
          publicBaseUrl: resolvePublicHostBaseUrl(req),
          uploaderMemberId: wpCfg.uploaderMemberId,
          uploaderName: parsedBody.uploaderName || wpCfg.uploaderName,
        });
        sendJson(res, 200, { ok: true, ...result });
      } catch (hookErr) {
        console.error("WP Auto-Poster webhook error:", hookErr);
        sendJson(res, 500, { ok: false, error: hookErr?.message || "WordPress webhook failed" });
      }
      return;
    }
  }

  if (
    req.method === "GET" &&
    (pathname === "/admin-dashboard" ||
      pathname === "/admin-dashboard/" ||
      pathname === "/dashboard" ||
      pathname === "/dashboard/")
  ) {
    const htmlBuf = Buffer.from(renderAdminDashboardHtml(), "utf8");
    res.writeHead(200, {
      "Content-Type": "text/html; charset=utf-8",
      "Cache-Control": "no-store",
      "Content-Length": htmlBuf.length,
    });
    res.end(htmlBuf);
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
  console.log(`VidVora server listening on port ${PORT} (databaseBackend=${activeDbBackend})`);
  if (pgDb) {
    pgDb
      .ensureInitialized()
      .then(() => console.log("PostgreSQL vidvora_docs schema ready."))
      .catch((err) => console.error("PostgreSQL schema init warning:", err?.message || err));
  }
  autoRegisterTelegramWebhook();
  const bootPublicBase = (process.env.PUBLIC_BASE_URL || process.env.RENDER_EXTERNAL_URL || "")
    .trim()
    .replace(/\/+$/, "");
  startWordPressAutoPosterScheduler({ db, publicBaseUrl: bootPublicBase });
});

const MEMORY_SOFT_TRIM_RSS_BYTES = 340 * 1024 * 1024; // Trim soft caches at 340 MB (well below Render 512 MB limit)
const memoryWatchTimer = setInterval(() => {
  try {
    const rss = process.memoryUsage().rss;
    if (rss >= MEMORY_SOFT_TRIM_RSS_BYTES) {
      const rssMb = Math.round(rss / (1024 * 1024));
      clearApiCollectionBufferCache();
      dashboardStatsCache.clear();
      clearThumbBufferCache();
      clearTelegramRuntimeCaches();
      if (viewGeoTracker && typeof viewGeoTracker.clearIpCache === "function") {
        viewGeoTracker.clearIpCache();
      }
      legacyStreamViewCache.clear();
      if (pgDb && typeof pgDb.trimSoftCaches === "function") {
        pgDb.trimSoftCaches();
      }
      console.warn(`Memory pressure guard triggered at ${rssMb} MB RSS — soft caches trimmed.`);
    }
  } catch (_) {}
}, 45_000);
if (typeof memoryWatchTimer.unref === "function") {
  memoryWatchTimer.unref();
}

process.on("SIGTERM", () => {
  clearInterval(memoryWatchTimer);
  viewGeoTracker
    .flushPending(db)
    .catch(() => {})
    .finally(() => process.exit(0));
});

