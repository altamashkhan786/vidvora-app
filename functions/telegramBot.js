const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const os = require("os");
const { Readable } = require("stream");
const { pipeline } = require("stream/promises");
const { execFile } = require("child_process");
const { promisify } = require("util");

const execFileAsync = promisify(execFile);

const SHARE_LINK_BASE_URL = "https://d1mn8mrvlzisg5.cloudfront.net/index.html?link=";
const SUPERADMIN_PASSKEY = "GM-ADMIN-2026-MASTER";
const DEFAULT_PREVIEW_SECONDS = 5;
const BOT_API_MAX_BYTES = 20 * 1024 * 1024;
const WEBHOOK_PREVIEW_MAX_BYTES = BOT_API_MAX_BYTES;
const MTPROTO_CHUNK_SIZE = 512 * 1024;
const { normalizeShareBaseUrl } = require("./sharePreview");

let heavyMediaLockTail = Promise.resolve();
let heavyMediaPendingCount = 0;
const HEAVY_MEDIA_TASK_TIMEOUT_MS = 120_000;

function withHeavyMediaSlot(taskFn, timeoutMs = HEAVY_MEDIA_TASK_TIMEOUT_MS) {
  heavyMediaPendingCount += 1;
  const run = async () => {
    let timer = null;
    try {
      const taskPromise = Promise.resolve().then(() => taskFn());
      if (!timeoutMs || timeoutMs <= 0) {
        return await taskPromise;
      }
      return await Promise.race([
        taskPromise,
        new Promise((_, reject) => {
          timer = setTimeout(() => {
            reject(new Error("Upload task timed out while processing media."));
          }, timeoutMs);
        }),
      ]);
    } finally {
      if (timer) clearTimeout(timer);
      heavyMediaPendingCount = Math.max(0, heavyMediaPendingCount - 1);
    }
  };
  const next = heavyMediaLockTail.then(run, run);
  heavyMediaLockTail = next.catch(() => {});
  return next;
}

function getFfmpegBinary() {
  try {
    const staticPath = require("ffmpeg-static");
    if (staticPath && fs.existsSync(staticPath)) {
      return staticPath;
    }
  } catch (_) {
    // Fallback to system ffmpeg in PATH
  }
  return "ffmpeg";
}

function hashToken(rawToken) {
  const clean = String(rawToken || "").trim().toUpperCase();
  return crypto.createHash("sha256").update(clean, "utf8").digest("hex");
}

function generateAccessToken() {
  const charset = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  let code = "";
  const bytes = crypto.randomBytes(8);
  for (let i = 0; i < 8; i += 1) {
    code += charset[bytes[i] % charset.length];
  }
  return `GM-${code}`;
}

function signStreamTarget(videoId, kind, secret) {
  return crypto
    .createHmac("sha256", secret)
    .update(`${videoId}:${kind}`)
    .digest("base64url");
}

function verifyStreamTarget(videoId, kind, signature, secret) {
  if (!videoId || !signature || !secret) return false;
  const expected = signStreamTarget(videoId, kind, secret);
  const sigBuf = Buffer.from(String(signature));
  const expBuf = Buffer.from(expected);
  if (sigBuf.length !== expBuf.length) return false;
  return crypto.timingSafeEqual(sigBuf, expBuf);
}

function parseLoginCommand(text) {
  const clean = String(text || "").trim();
  const match = clean.match(/^\/login(?:@[A-Za-z0-9_]+)?\s+(.+)$/i);
  if (!match) return null;
  const parts = match[1].trim().split(/\s+/).filter(Boolean);
  if (parts.length === 1) {
    return { emailOrPhone: null, passkey: parts[0] };
  }
  if (parts.length >= 2) {
    return {
      emailOrPhone: parts.slice(0, -1).join(" "),
      passkey: parts[parts.length - 1],
    };
  }
  return null;
}

function parseTitleAndDescription(caption, fallbackName = "Creator Video") {
  const cleanCaption = String(caption || "").trim();
  if (!cleanCaption) {
    const cleanFallback = String(fallbackName || "Creator Video")
      .replace(/\.[a-z0-9]{2,5}$/i, "")
      .replace(/[_-]+/g, " ")
      .trim()
      .slice(0, 200);
    return {
      title: cleanFallback || "Creator Video",
      description: "Uploaded via VidVora Telegram Creator Bot",
    };
  }
  const lines = cleanCaption.split(/\r?\n/);
  const firstLine = (lines[0] || "").trim().slice(0, 200);
  const rest = lines.slice(1).join("\n").trim().slice(0, 2000);
  return {
    title: firstLine || "Creator Video",
    description: rest || "Uploaded via VidVora Telegram Creator Bot",
  };
}

function extractDirectVideoUrlMessage(text) {
  const clean = String(text || "").trim();
  if (!clean || clean.startsWith("/")) return null;
  const urlMatch = clean.match(/https?:\/\/[^\s<>"{}|\\^`[\]]+/i);
  if (!urlMatch) return null;
  const url = urlMatch[0].trim();
  const remainder = clean.replace(urlMatch[0], "").trim();
  return { url, caption: remainder };
}

const UNSUPPORTED_THIRD_PARTY_HOST_REGEX =
  /(?:^|\.)(?:(?:1024)?terabox(?:app|link|share|file)?|1024tera|freeterabox|teraurl|terasharefile|nephobox|mirrobox|momerybox|4funbox|(?:1024)?tenbox|flezen|vidbunker|diskwala|mdisk|pdisk|dood(?:stream|s)?|streamtape|mixdrop|filemoon|voe)\.[a-z0-9.-]+$/i;

function isUnsupportedThirdPartyHostUrl(rawUrl) {
  const urlText = String(rawUrl || "").trim();
  if (!urlText) return false;
  try {
    const parsed = new URL(urlText);
    const hostname = String(parsed.hostname || "").toLowerCase();
    return UNSUPPORTED_THIRD_PARTY_HOST_REGEX.test(hostname);
  } catch (_) {
    return false;
  }
}

function isVidVoraHostOrScheme(rawUrl) {
  const urlText = String(rawUrl || "").trim();
  if (!urlText) return false;
  if (/^vidvora:\/\//i.test(urlText)) return true;
  try {
    const parsed = new URL(urlText);
    const host = String(parsed.hostname || "").toLowerCase();
    return (
      host.includes("vidvora") ||
      host === "d1mn8mrvlzisg5.cloudfront.net" ||
      host.endsWith(".cloudfront.net")
    );
  } catch (_) {
    return false;
  }
}

function extractCodeFromVidVoraUrl(rawUrl, options = {}) {
  const urlText = String(rawUrl || "").trim();
  if (!urlText || isUnsupportedThirdPartyHostUrl(urlText)) return "";
  const strictVidVoraOnly = Boolean(options?.strictVidVoraOnly);
  const isKnownVidVoraHost = isVidVoraHostOrScheme(urlText);
  const matchesVidVoraTokenPattern = (candidate) =>
    /^(?:GM-[A-Za-z0-9-]{4,}|link_[A-Za-z0-9]{4,}|vid_[A-Za-z0-9]{4,})$/i.test(String(candidate || "").trim());

  try {
    const parsed = new URL(urlText);
    const paramCode = (
      parsed.searchParams.get("link") ||
      parsed.searchParams.get("v") ||
      parsed.searchParams.get("video") ||
      parsed.searchParams.get("token") ||
      ""
    ).trim();
    if (paramCode) {
      if (!strictVidVoraOnly || isKnownVidVoraHost || matchesVidVoraTokenPattern(paramCode)) {
        return paramCode;
      }
      return "";
    }

    const pathMatch = parsed.pathname.match(/^\/(?:share|watch)\/([A-Za-z0-9_-]{4,})/i);
    if (pathMatch && pathMatch[1]) {
      const pathCode = pathMatch[1].trim();
      if (!strictVidVoraOnly || isKnownVidVoraHost || matchesVidVoraTokenPattern(pathCode)) {
        return pathCode;
      }
      return "";
    }
  } catch (_) {
    // Fallback regex parsing for custom scheme / partial URLs
  }

  const queryMatch = urlText.match(/[?&](?:link|v|video|token)=([A-Za-z0-9_-]{4,})/i);
  if (queryMatch && queryMatch[1]) {
    const qCode = queryMatch[1].trim();
    if (!strictVidVoraOnly || isKnownVidVoraHost || matchesVidVoraTokenPattern(qCode)) {
      return qCode;
    }
  }
  return "";
}

function extractVidVoraShareTarget(text, message = null, options = {}) {
  const raw = String(text || "").trim();
  const strictVidVoraOnly = Boolean(options?.strictVidVoraOnly);
  const candidateUrls = [];

  if (raw) {
    const urlMatches = raw.match(/(?:https?|vidvora):\/\/[^\s<>"{}|\\^`[\]]+/gi);
    if (urlMatches) {
      candidateUrls.push(...urlMatches);
    }
  }

  const collectEntityUrls = (entities = []) => {
    if (!Array.isArray(entities)) return;
    for (const entity of entities) {
      if (entity && typeof entity.url === "string" && entity.url.trim()) {
        candidateUrls.push(entity.url.trim());
      }
    }
  };

  if (message) {
    collectEntityUrls(message.entities);
    collectEntityUrls(message.caption_entities);
    const rows = message.reply_markup?.inline_keyboard;
    if (Array.isArray(rows)) {
      for (const row of rows) {
        if (!Array.isArray(row)) continue;
        for (const btn of row) {
          if (btn && typeof btn.url === "string" && btn.url.trim()) {
            candidateUrls.push(btn.url.trim());
          }
        }
      }
    }
  }

  let matchedCode = "";
  let matchedUrl = "";

  for (const url of candidateUrls) {
    const code = extractCodeFromVidVoraUrl(url, { strictVidVoraOnly });
    if (code) {
      matchedCode = code;
      matchedUrl = url;
      break;
    }
  }

  if (!matchedCode && raw) {
    const convertCmdMatch = raw.match(/^\/convert(?:@[A-Za-z0-9_]+)?\s+(\S+)(?:\s+([\s\S]+))?$/i);
    if (convertCmdMatch && convertCmdMatch[1]) {
      const arg = convertCmdMatch[1].trim();
      const fromUrl = extractCodeFromVidVoraUrl(arg, { strictVidVoraOnly });
      matchedCode = fromUrl || (isUnsupportedThirdPartyHostUrl(arg) ? "" : arg);
      matchedUrl = arg;
    }
  }

  if (!matchedCode && raw) {
    const standaloneMatch = raw.match(/\b(GM-[A-Za-z0-9-]{4,}|link_[A-Za-z0-9]{4,}|vid_[A-Za-z0-9]{4,})\b/i);
    if (standaloneMatch && standaloneMatch[1]) {
      matchedCode = standaloneMatch[1].trim();
    }
  }

  if (!matchedCode) return null;

  let cleanTitle = raw
    .replace(/^\/convert(?:@[A-Za-z0-9_]+)?\s*/i, "")
    .replace(matchedUrl || "", "")
    .replace(/(?:https?|vidvora):\/\/[^\s<>"{}|\\^`[\]]+/gi, "")
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => {
      if (!line) return false;
      if (/^(?:🎬\s*)?\*?VidVora\s*[—-]\s*Watch Video\*?$/i.test(line)) return false;
      if (/^✅\s*Uploaded to VidVora/i.test(line)) return false;
      if (/^🎞️\s*Preview Clip:/i.test(line)) return false;
      if (/^🔗\s*\*?Watch Full Video/i.test(line)) return false;
      if (/^🔑\s*\*?Access Code/i.test(line)) return false;
      if (/^📲\s*\*?App Link/i.test(line)) return false;
      if (/^_?Forward this preview clip/i.test(line)) return false;
      if (/^(?:वीडियो कैसे देखे|video kaise dekhe)(?:\s|\/|$)/iu.test(line)) return false;
      if (/^👇+$/u.test(line)) return false;
      if (/^\[?https?:\/\/t\.me\/VideoKaiseDekhe/i.test(line)) return false;
      if (/^https?:\/\/t\.me\/VideoKaiseDekhe/i.test(line.replace(/\\_/g, "_"))) return false;
      if (line.toUpperCase() === matchedCode.toUpperCase()) return false;
      return true;
    })
    .join(" ")
    .trim()
    .slice(0, 200);

  return {
    code: matchedCode,
    customTitle: cleanTitle || "",
  };
}

const SOURCE_VIDEO_CACHE_TTL_MS = 15 * 60 * 1000;
const sourceVideoCacheByDb = new WeakMap();

function getScopedMap(weakMap, db) {
  if (!db || typeof db !== "object") return null;
  let m = weakMap.get(db);
  if (!m) {
    m = new Map();
    weakMap.set(db, m);
  }
  return m;
}

function cacheResolvedSourceVideo(db, rawCode, resolved) {
  const map = getScopedMap(sourceVideoCacheByDb, db);
  const key = String(rawCode || "").trim().toUpperCase();
  if (!map || !key || !resolved?.found || !resolved?.isActive) return;
  if (map.size >= 500) {
    const oldest = map.keys().next().value;
    if (oldest !== undefined) map.delete(oldest);
  }
  map.set(key, { value: resolved, cachedAt: Date.now() });
}

async function resolveVidVoraSourceVideo(db, rawCode) {
  const cleanCode = String(rawCode || "").trim();
  if (!cleanCode || !db || typeof db.collection !== "function") {
    return { found: false, isActive: false };
  }

  const srcCache = getScopedMap(sourceVideoCacheByDb, db);
  const cacheKey = cleanCode.toUpperCase();
  if (srcCache) {
    const cached = srcCache.get(cacheKey);
    if (cached && Date.now() - cached.cachedAt < SOURCE_VIDEO_CACHE_TTL_MS) {
      return cached.value;
    }
  }

  let linkDoc = null;
  let sourceVideoId = "";
  let linkActive = true;

  try {
    const linksCol = db.collection("accessLinks");
    const tokenHash = hashToken(cleanCode);
    if (typeof linksCol.where === "function") {
      const snap = await linksCol.where("tokenHash", "==", tokenHash).limit(1).get();
      if (snap && !snap.empty && snap.docs && snap.docs.length > 0) {
        linkDoc = snap.docs[0];
      }
    }
    if (!linkDoc && typeof linksCol.doc === "function") {
      const exactDoc = await linksCol.doc(cleanCode).get();
      if (exactDoc && exactDoc.exists) {
        linkDoc = exactDoc;
      } else {
        const upperDoc = await linksCol.doc(cleanCode.toUpperCase()).get();
        if (upperDoc && upperDoc.exists) {
          linkDoc = upperDoc;
        }
      }
    }
  } catch (_) {
    // Fallback to direct video lookup below
  }

  if (linkDoc && linkDoc.exists) {
    const lData = linkDoc.data() || {};
    sourceVideoId = String(lData.videoId || "").trim();
    linkActive = lData.isActive === true;
    if (lData.expiresAt) {
      const expMs =
        typeof lData.expiresAt.toDate === "function"
          ? lData.expiresAt.toDate().getTime()
          : new Date(lData.expiresAt).getTime();
      if (Number.isFinite(expMs) && Date.now() > expMs) {
        linkActive = false;
      }
    }
    if (!linkActive) {
      return { found: true, isActive: false, reason: "link_inactive" };
    }
  } else {
    sourceVideoId = cleanCode;
  }

  if (!sourceVideoId) {
    return { found: false, isActive: false };
  }

  try {
    const videosCol = db.collection("videos");
    const vidRef = typeof videosCol.doc === "function" ? videosCol.doc(sourceVideoId) : videosCol.document(sourceVideoId);
    const vidDoc = await vidRef.get();
    if (!vidDoc || !vidDoc.exists) {
      return { found: false, isActive: false };
    }
    const vData = vidDoc.data() || {};
    if (vData.isActive !== true) {
      return { found: true, isActive: false, reason: "video_inactive" };
    }
    if (!vData.storagePath) {
      return { found: false, isActive: false };
    }
    const resolved = {
      found: true,
      isActive: true,
      videoId: vidDoc.id || vData.videoId || sourceVideoId,
      video: vData,
    };
    cacheResolvedSourceVideo(db, cleanCode, resolved);
    return resolved;
  } catch (_) {
    return { found: false, isActive: false };
  }
}

const ALLOWED_VIDEO_EXT_RE = /\.(mp4|mkv|mov|webm|m4v|3gp|avi|ts|flv|wmv|mpeg|mpg|ogv)$/i;

function toSafeByteLength(value) {
  if (value === null || value === undefined) return 0;
  if (typeof value === "number") {
    return Number.isFinite(value) && value > 0 ? Math.floor(value) : 0;
  }
  if (typeof value === "bigint") {
    const num = Number(value);
    return Number.isFinite(num) && num > 0 ? num : 0;
  }
  if (typeof value === "object") {
    if (typeof value.toJSNumber === "function") {
      try {
        const num = value.toJSNumber();
        if (Number.isFinite(num) && num > 0) return Math.floor(num);
      } catch (_) {}
    }
    if (typeof value.toString === "function") {
      const parsed = Number(value.toString());
      if (Number.isFinite(parsed) && parsed > 0) return Math.floor(parsed);
    }
    return 0;
  }
  const parsed = Number(String(value).trim());
  return Number.isFinite(parsed) && parsed > 0 ? Math.floor(parsed) : 0;
}

function inferVideoMimeType(fileName, rawMime) {
  const cleanMime = String(rawMime || "").trim().toLowerCase();
  if (cleanMime.startsWith("video/")) return cleanMime;
  const lowerName = String(fileName || "").trim().toLowerCase();
  if (lowerName.endsWith(".mkv")) return "video/x-matroska";
  if (lowerName.endsWith(".webm")) return "video/webm";
  if (lowerName.endsWith(".mov")) return "video/quicktime";
  if (lowerName.endsWith(".avi")) return "video/x-msvideo";
  if (lowerName.endsWith(".3gp")) return "video/3gpp";
  if (lowerName.endsWith(".ts")) return "video/mp2t";
  if (lowerName.endsWith(".flv")) return "video/x-flv";
  if (lowerName.endsWith(".wmv")) return "video/x-ms-wmv";
  if (lowerName.endsWith(".ogv")) return "video/ogg";
  if (lowerName.endsWith(".mpeg") || lowerName.endsWith(".mpg")) return "video/mpeg";
  return "video/mp4";
}

function getTelegramVideoMediaObject(message) {
  if (!message) return null;
  if (message.video && message.video.file_id) return message.video;
  if (message.document && message.document.file_id) {
    const mime = String(message.document.mime_type || "").toLowerCase();
    const fileName = String(message.document.file_name || "").toLowerCase();
    if (mime.startsWith("video/") || ALLOWED_VIDEO_EXT_RE.test(fileName)) {
      return message.document;
    }
  }
  if (message.animation && message.animation.file_id) {
    const mime = String(message.animation.mime_type || "").toLowerCase();
    const fileName = String(message.animation.file_name || "").toLowerCase();
    const duration = Number(message.animation.duration || 0);
    if (mime.startsWith("video/") || ALLOWED_VIDEO_EXT_RE.test(fileName) || duration > 0) {
      return message.animation;
    }
  }
  if (message.video_note && message.video_note.file_id) {
    return message.video_note;
  }
  return null;
}

function isAllowedTelegramVideoMessage(message) {
  return Boolean(getTelegramVideoMediaObject(message));
}

function escapeHtml(str) {
  return String(str || "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

const MAX_CUSTOM_HEADER_LENGTH = 300;
const MAX_CUSTOM_FOOTER_LENGTH = 400;
const MAX_CUSTOM_CAPTION_LINES = 8;
const CAPTION_BLOCK_NONE = "__NONE__";
const DEFAULT_SHARE_FOOTER_HTML = [
  "<b>वीडियो कैसे देखे / How to play video</b>",
  "👇👇👇👇",
  "https://t.me/VideoKaiseDekhe_ar",
].join("\n");
const DEFAULT_SHARE_FOOTER_PLAIN = [
  "वीडियो कैसे देखे / How to play video",
  "👇👇👇👇",
  "https://t.me/VideoKaiseDekhe_ar",
].join("\n");

function isCaptionBlockDisabled(raw) {
  return String(raw || "").trim() === CAPTION_BLOCK_NONE;
}

function sanitizeCustomCaptionBlock(raw, maxLength = MAX_CUSTOM_FOOTER_LENGTH) {
  if (isCaptionBlockDisabled(raw)) return CAPTION_BLOCK_NONE;
  const normalized = String(raw || "")
    .replace(/\r\n?/g, "\n")
    .split("\n")
    .map((line) => line.trimEnd())
    .slice(0, MAX_CUSTOM_CAPTION_LINES)
    .join("\n")
    .trim();
  if (!normalized) return "";
  return normalized.slice(0, maxLength).trim();
}

function buildShareMessageCaption({
  rawToken,
  shareLinkBaseUrl = SHARE_LINK_BASE_URL,
  customHeader = "",
  customFooter = "",
}) {
  const effectiveBase = shareLinkBaseUrl || SHARE_LINK_BASE_URL;
  const watchUrl = `${effectiveBase}${rawToken}`;
  const headerDisabled = isCaptionBlockDisabled(customHeader);
  const footerDisabled = isCaptionBlockDisabled(customFooter);
  const cleanHeader = headerDisabled ? "" : sanitizeCustomCaptionBlock(customHeader, MAX_CUSTOM_HEADER_LENGTH);
  const cleanFooter = footerDisabled ? "" : sanitizeCustomCaptionBlock(customFooter, MAX_CUSTOM_FOOTER_LENGTH);
  const lines = [];
  if (!headerDisabled) {
    lines.push(cleanHeader ? escapeHtml(cleanHeader) : "🎬 <b>VidVora — Watch Video</b>", "");
  }
  lines.push("🔗 <b>Watch Full Video:</b>", escapeHtml(watchUrl));
  if (!footerDisabled) {
    lines.push("", cleanFooter ? escapeHtml(cleanFooter) : DEFAULT_SHARE_FOOTER_HTML);
  }
  return lines.join("\n");
}

function buildSharePlainFallbackText({
  rawToken,
  shareLinkBaseUrl = SHARE_LINK_BASE_URL,
  customHeader = "",
  customFooter = "",
}) {
  const effectiveBase = shareLinkBaseUrl || SHARE_LINK_BASE_URL;
  const watchUrl = `${effectiveBase}${rawToken}`;
  const headerDisabled = isCaptionBlockDisabled(customHeader);
  const footerDisabled = isCaptionBlockDisabled(customFooter);
  const cleanHeader = headerDisabled ? "" : sanitizeCustomCaptionBlock(customHeader, MAX_CUSTOM_HEADER_LENGTH);
  const cleanFooter = footerDisabled ? "" : sanitizeCustomCaptionBlock(customFooter, MAX_CUSTOM_FOOTER_LENGTH);
  const lines = [];
  if (!headerDisabled) {
    lines.push(cleanHeader || "🎬 VidVora — Watch Video", "");
  }
  lines.push("🔗 Watch Full Video:", watchUrl);
  if (!footerDisabled) {
    lines.push("", cleanFooter || DEFAULT_SHARE_FOOTER_PLAIN);
  }
  return lines.join("\n");
}

const CREATOR_BOT_REPLY_KEYBOARD = {
  keyboard: [
    [{ text: "✏️ Edit Header" }, { text: "✏️ Edit Footer" }],
    [{ text: "⚙️ Header / Footer Settings" }, { text: "👁️ Preview Post" }],
    [{ text: "📊 My Status" }, { text: "🔓 Logout" }],
  ],
  resize_keyboard: true,
  is_persistent: true,
};

function buildCaptionSettingsKeyboard() {
  return {
    inline_keyboard: [
      [
        { text: "✏️ Edit Header", callback_data: "caption:edit_header" },
        { text: "✏️ Edit Footer", callback_data: "caption:edit_footer" },
      ],
      [
        { text: "🚫 Remove Header", callback_data: "caption:remove_header" },
        { text: "🚫 Remove Footer", callback_data: "caption:remove_footer" },
      ],
      [
        { text: "♻️ Default Header", callback_data: "caption:reset_header" },
        { text: "♻️ Default Footer", callback_data: "caption:reset_footer" },
      ],
      [{ text: "👁️ Preview Post", callback_data: "caption:preview" }],
    ],
  };
}

function buildEditPromptCancelKeyboard(type) {
  const isHeader = type === "header";
  return {
    inline_keyboard: [
      [
        {
          text: isHeader ? "🚫 Remove Header" : "🚫 Remove Footer",
          callback_data: isHeader ? "caption:remove_header" : "caption:remove_footer",
        },
        {
          text: isHeader ? "♻️ Default Header" : "♻️ Default Footer",
          callback_data: isHeader ? "caption:reset_header" : "caption:reset_footer",
        },
      ],
      [{ text: "❌ Cancel", callback_data: "caption:cancel" }],
    ],
  };
}

function buildCaptionSettingsHtml(session) {
  const rawHeader = session?.customCaptionHeader || "";
  const rawFooter = session?.customCaptionFooter || "";
  const headerDisabled = isCaptionBlockDisabled(rawHeader);
  const footerDisabled = isCaptionBlockDisabled(rawFooter);
  const header = headerDisabled ? "" : sanitizeCustomCaptionBlock(rawHeader, MAX_CUSTOM_HEADER_LENGTH);
  const footer = footerDisabled ? "" : sanitizeCustomCaptionBlock(rawFooter, MAX_CUSTOM_FOOTER_LENGTH);

  const headerDisplay = headerDisabled
    ? "<i>Removed (Hidden)</i>"
    : header
      ? escapeHtml(header)
      : "<i>Default (🎬 VidVora — Watch Video)</i>";
  const footerDisplay = footerDisabled
    ? "<i>Removed (Hidden)</i>"
    : footer
      ? escapeHtml(footer)
      : "<i>Default (वीडियो कैसे देखे / How to play video + https://t.me/VideoKaiseDekhe_ar)</i>";

  return [
    "📝 <b>Post Header &amp; Footer Settings</b>",
    "",
    `<b>Header:</b>\n${headerDisplay}`,
    "",
    `<b>Footer:</b>\n${footerDisplay}`,
    "",
    "Agar aap kuch change nahi karenge toh default Header aur <b>How to play video</b> link laga rahega. Apna custom Header/Footer ya channel link set/remove karne ke liye neeche buttons use karein 👇",
  ].join("\n");
}

function escapeMarkdown(str) {
  return String(str || "").replace(/([_*`\[])/g, "\\$1");
}

/**
 * Generates a compact 5-second preview MP4 clip and a JPG thumbnail using ffmpeg.
 * Retries without audio stream if source video lacks audio or audio encoding fails.
 */
async function createFiveSecondPreviewAndThumbnail({
  inputPathOrUrl,
  outputClipPath,
  outputThumbPath,
  durationSeconds = 0,
  previewSeconds = DEFAULT_PREVIEW_SECONDS,
}) {
  const ffmpegBin = getFfmpegBinary();
  const numericDuration = Number(durationSeconds) || 0;
  const startSec = numericDuration >= 12 ? 3 : 0;
  const clipDuration = Math.max(1, Math.min(Number(previewSeconds) || DEFAULT_PREVIEW_SECONDS, 10));
  const MAX_COPY_CLIP_BYTES = 6 * 1024 * 1024;
  let clipReady = false;

  const extractHdThumb = async (source, seekSec) => {
    const args = ["-y", "-threads", "1"];
    if (seekSec > 0) {
      args.push("-ss", String(seekSec));
    }
    args.push(
      "-i",
      source,
      "-vframes",
      "1",
      "-vf",
      "scale='min(720,iw)':-2:flags=bilinear",
      "-q:v",
      "3",
      outputThumbPath
    );
    await execFileAsync(ffmpegBin, args, { timeout: 25_000, killSignal: "SIGKILL", maxBuffer: 2 * 1024 * 1024 });
  };

  // 1. Extract crisp HD thumbnail FIRST (~0.3s, very low RAM) so even if 5-sec clip encoding fails/times out,
  //    we ALWAYS have a real video thumbnail ready!
  try {
    await extractHdThumb(inputPathOrUrl, startSec);
  } catch (_) {
    try {
      await extractHdThumb(inputPathOrUrl, 0);
    } catch (_) {
      // Will retry from generated preview clip if clip succeeds
    }
  }

  // 2. Fast & ultra-low-RAM 5-sec clip path (~12 MB): stream-copy first 5 seconds cleanly from keyframe 0
  try {
    await execFileAsync(
      ffmpegBin,
      [
        "-y",
        "-threads",
        "1",
        "-i",
        inputPathOrUrl,
        "-t",
        String(clipDuration),
        "-c",
        "copy",
        "-movflags",
        "+faststart",
        outputClipPath,
      ],
      { timeout: 20_000, killSignal: "SIGKILL", maxBuffer: 2 * 1024 * 1024 }
    );
    if (fs.existsSync(outputClipPath)) {
      const sz = fs.statSync(outputClipPath).size;
      if (sz > 1024 && sz <= MAX_COPY_CLIP_BYTES) {
        clipReady = true;
      } else {
        await fs.promises.unlink(outputClipPath).catch(() => {});
      }
    }
  } catch (_) {
    await fs.promises.unlink(outputClipPath).catch(() => {});
  }

  // 3. Fallback: ultra-low-memory x264 transcode (safe for Render 512MB Free tier)
  if (!clipReady) {
    const baseArgs = [
      "-y",
      "-threads",
      "1",
      "-ss",
      String(startSec),
      "-i",
      inputPathOrUrl,
      "-t",
      String(clipDuration),
      "-vf",
      "scale='min(480,iw)':-2",
      "-c:v",
      "libx264",
      "-preset",
      "ultrafast",
      "-tune",
      "fastdecode",
      "-x264-params",
      "rc-lookahead=8:ref=1:bframes=0:threads=1",
      "-crf",
      "30",
      "-maxrate",
      "650k",
      "-bufsize",
      "650k",
      "-pix_fmt",
      "yuv420p",
      "-movflags",
      "+faststart",
    ];

    try {
      await execFileAsync(
        ffmpegBin,
        [...baseArgs, "-c:a", "aac", "-b:a", "64k", outputClipPath],
        { timeout: 45_000, killSignal: "SIGKILL", maxBuffer: 3 * 1024 * 1024 }
      );
      clipReady = fs.existsSync(outputClipPath) && fs.statSync(outputClipPath).size > 0;
    } catch (_) {
      try {
        // Retry without audio track (handles silent videos or incompatible audio codecs)
        await execFileAsync(
          ffmpegBin,
          [...baseArgs, "-an", outputClipPath],
          { timeout: 45_000, killSignal: "SIGKILL", maxBuffer: 3 * 1024 * 1024 }
        );
        clipReady = fs.existsSync(outputClipPath) && fs.statSync(outputClipPath).size > 0;
      } catch (transcodeErr) {
        await fs.promises.unlink(outputClipPath).catch(() => {});
        console.warn("5-sec clip transcode failed (keeping thumbnail):", transcodeErr?.message || transcodeErr);
      }
    }
  }

  // 4. If initial thumbnail extraction failed earlier, retry from frame 0 of generated clip
  const thumbAlreadyReady = fs.existsSync(outputThumbPath) && fs.statSync(outputThumbPath).size > 0;
  if (!thumbAlreadyReady && clipReady && fs.existsSync(outputClipPath)) {
    try {
      await extractHdThumb(outputClipPath, 0);
    } catch (_) {
      // Non-fatal — caller will fall back to Telegram's built-in video thumbnail
    }
  }

  return {
    clipExists: fs.existsSync(outputClipPath) && fs.statSync(outputClipPath).size > 0,
    thumbExists: fs.existsSync(outputThumbPath) && fs.statSync(outputThumbPath).size > 0,
  };
}

async function encodeCrispThumbDataUri(thumbPath, maxBytes = 55 * 1024) {
  if (!thumbPath || !fs.existsSync(thumbPath)) return "";
  try {
    let thumbBuf = await fs.promises.readFile(thumbPath);
    if (thumbBuf.length > 0 && thumbBuf.length <= maxBytes) {
      return `data:image/jpeg;base64,${thumbBuf.toString("base64")}`;
    }
    const ffmpegBin = getFfmpegBinary();
    const recompressedPath = `${thumbPath}.compact.jpg`;
    for (const qVal of ["5", "7", "9", "12"]) {
      await execFileAsync(
        ffmpegBin,
        ["-y", "-threads", "1", "-i", thumbPath, "-vf", "scale='min(480,iw)':-2:flags=bilinear", "-q:v", qVal, recompressedPath],
        { timeout: 20_000, maxBuffer: 4 * 1024 * 1024 }
      );
      if (fs.existsSync(recompressedPath)) {
        const candidate = await fs.promises.readFile(recompressedPath);
        if (candidate.length > 0 && candidate.length <= maxBytes) {
          await fs.promises.unlink(recompressedPath).catch(() => {});
          return `data:image/jpeg;base64,${candidate.toString("base64")}`;
        }
      }
    }
    if (fs.existsSync(recompressedPath)) {
      const fallbackBuf = await fs.promises.readFile(recompressedPath);
      await fs.promises.unlink(recompressedPath).catch(() => {});
      if (fallbackBuf.length > 0 && fallbackBuf.length <= 120 * 1024) {
        return `data:image/jpeg;base64,${fallbackBuf.toString("base64")}`;
      }
    }
  } catch (err) {
    console.warn("encodeCrispThumbDataUri warning:", err.message);
  }
  return "";
}

function parseTelegramRetryAfterMs(data, message) {
  const paramSec = Number(data?.parameters?.retry_after || 0);
  if (Number.isFinite(paramSec) && paramSec > 0) {
    return Math.min((paramSec + 1) * 1000, 60_000);
  }
  const match = String(message || "").match(/retry after\s+(\d+)/i);
  if (match && match[1]) {
    const sec = parseInt(match[1], 10);
    if (Number.isFinite(sec) && sec > 0) {
      return Math.min((sec + 1) * 1000, 60_000);
    }
  }
  return 0;
}

async function telegramApiJson(apiBase, botToken, method, payload, maxRetries = 4) {
  for (let attempt = 0; attempt <= maxRetries; attempt += 1) {
    try {
      const fetchOpts = {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      };
      if (typeof AbortSignal !== "undefined" && typeof AbortSignal.timeout === "function") {
        fetchOpts.signal = AbortSignal.timeout(25_000);
      }
      const res = await fetch(`${apiBase}/bot${botToken}/${method}`, fetchOpts);
      const data = await res.json();
      if (!data.ok) {
        const retryWaitMs = parseTelegramRetryAfterMs(data, data.description);
        if (retryWaitMs > 0 && attempt < maxRetries) {
          await new Promise((r) => setTimeout(r, retryWaitMs));
          continue;
        }
        throw new Error(data.description || `Telegram ${method} failed`);
      }
      return data.result;
    } catch (err) {
      if (attempt < maxRetries && isTransientNetworkError(err)) {
        await new Promise((r) => setTimeout(r, 500));
        continue;
      }
      throw err;
    }
  }
  throw new Error(`Telegram ${method} failed`);
}

async function telegramMultipartCall(apiBase, botToken, method, buildForm, maxRetries = 4) {
  for (let attempt = 0; attempt <= maxRetries; attempt += 1) {
    try {
      const fetchOpts = {
        method: "POST",
        body: buildForm(),
      };
      if (typeof AbortSignal !== "undefined" && typeof AbortSignal.timeout === "function") {
        fetchOpts.signal = AbortSignal.timeout(90_000);
      }
      const res = await fetch(`${apiBase}/bot${botToken}/${method}`, fetchOpts);
      const data = await res.json();
      if (!data.ok) {
        const retryWaitMs = parseTelegramRetryAfterMs(data, data.description);
        if (retryWaitMs > 0 && attempt < maxRetries) {
          await new Promise((r) => setTimeout(r, retryWaitMs));
          continue;
        }
        throw new Error(data.description || `Telegram ${method} failed`);
      }
      return data.result;
    } catch (err) {
      if (attempt < maxRetries && isTransientNetworkError(err)) {
        await new Promise((r) => setTimeout(r, 500));
        continue;
      }
      throw err;
    }
  }
  throw new Error(`Telegram ${method} failed`);
}

async function sendTelegramVideoClip(apiBase, botToken, { chatId, clipPath, caption, watchUrl, replyToMessageId }) {
  const clipBuffer = await fs.promises.readFile(clipPath);
  const replyMarkup = JSON.stringify({
    inline_keyboard: [[{ text: "▶️ Watch Full Video on VidVora", url: watchUrl }]],
  });
  const sendWithReply = (replyId) =>
    telegramMultipartCall(apiBase, botToken, "sendVideo", () => {
      const form = new FormData();
      form.append("chat_id", String(chatId));
      form.append("caption", caption);
      form.append("parse_mode", "HTML");
      if (replyId) {
        form.append("reply_to_message_id", String(replyId));
      }
      form.append("reply_markup", replyMarkup);
      form.append("video", new Blob([clipBuffer], { type: "video/mp4" }), "vidvora_preview_5s.mp4");
      return form;
    });
  try {
    return await sendWithReply(replyToMessageId);
  } catch (err) {
    if (replyToMessageId && /reply|replied|message.*not found/i.test(String(err?.message || ""))) {
      return await sendWithReply(null);
    }
    throw err;
  }
}

async function sendTelegramVideoByFileId(apiBase, botToken, { chatId, fileId, caption, watchUrl, replyToMessageId }) {
  const sendWithReply = (replyId) =>
    telegramApiJson(apiBase, botToken, "sendVideo", {
      chat_id: chatId,
      video: String(fileId),
      caption,
      parse_mode: "HTML",
      ...(replyId ? { reply_to_message_id: replyId } : {}),
      reply_markup: {
        inline_keyboard: [[{ text: "▶️ Watch Full Video on VidVora", url: watchUrl }]],
      },
    });
  try {
    return await sendWithReply(replyToMessageId);
  } catch (err) {
    if (replyToMessageId && /reply|replied|message.*not found/i.test(String(err?.message || ""))) {
      return await sendWithReply(null);
    }
    throw err;
  }
}

async function sendTelegramPhoto(apiBase, botToken, { chatId, imagePathOrUri, caption, watchUrl, replyToMessageId }) {
  const cleanImg = String(imagePathOrUri || "").trim();
  if (!cleanImg) {
    throw new Error("Missing thumbnail image");
  }
  const replyMarkup = JSON.stringify({
    inline_keyboard: [[{ text: "▶️ Watch Full Video on VidVora", url: watchUrl }]],
  });

  const uploadPhotoBuffer = async (imgBuffer, mime = "image/jpeg", ext = "jpg") => {
    const sendWithReply = (replyId) =>
      telegramMultipartCall(apiBase, botToken, "sendPhoto", () => {
        const form = new FormData();
        form.append("chat_id", String(chatId));
        form.append("caption", caption);
        form.append("parse_mode", "HTML");
        if (replyId) {
          form.append("reply_to_message_id", String(replyId));
        }
        form.append("reply_markup", replyMarkup);
        form.append("photo", new Blob([imgBuffer], { type: mime }), `vidvora_thumb.${ext}`);
        return form;
      });
    try {
      return await sendWithReply(replyToMessageId);
    } catch (err) {
      if (replyToMessageId && /reply|replied|message.*not found/i.test(String(err?.message || ""))) {
        return await sendWithReply(null);
      }
      throw err;
    }
  };

  const dataUriMatch = cleanImg.match(/^data:(image\/[a-zA-Z0-9.+-]+);base64,([A-Za-z0-9+/=\s]+)$/i);
  if (dataUriMatch) {
    const mime = dataUriMatch[1].toLowerCase() === "image/jpg" ? "image/jpeg" : dataUriMatch[1].toLowerCase();
    const imgBuffer = Buffer.from(dataUriMatch[2].replace(/\s+/g, ""), "base64");
    const ext = mime.includes("png") ? "png" : "jpg";
    return uploadPhotoBuffer(imgBuffer, mime, ext);
  }

  if (!cleanImg.startsWith("http://") && !cleanImg.startsWith("https://") && fs.existsSync(cleanImg)) {
    const imgBuffer = await fs.promises.readFile(cleanImg);
    const ext = cleanImg.toLowerCase().endsWith(".png") ? "png" : "jpg";
    const mime = ext === "png" ? "image/png" : "image/jpeg";
    return uploadPhotoBuffer(imgBuffer, mime, ext);
  }

  const sendJsonPhoto = (replyId) =>
    telegramApiJson(apiBase, botToken, "sendPhoto", {
      chat_id: chatId,
      photo: cleanImg,
      caption,
      parse_mode: "HTML",
      ...(replyId ? { reply_to_message_id: replyId } : {}),
      reply_markup: {
        inline_keyboard: [[{ text: "▶️ Watch Full Video on VidVora", url: watchUrl }]],
      },
    });

  try {
    return await sendJsonPhoto(replyToMessageId);
  } catch (err) {
    const errMsg = String(err?.message || "");
    if (replyToMessageId && /reply|replied|message.*not found/i.test(errMsg)) {
      return await sendJsonPhoto(null);
    }
    // If Telegram couldn't fetch an HTTP(S) thumbnail URL directly, download it on our server and upload bytes
    if (/^https?:\/\//i.test(cleanImg)) {
      const dlRes = await fetch(cleanImg, { redirect: "follow", signal: AbortSignal.timeout(15_000) });
      if (dlRes.ok) {
        const contentType = String(dlRes.headers?.get?.("content-type") || "image/jpeg").toLowerCase();
        const imgBuf = Buffer.from(await dlRes.arrayBuffer());
        if (imgBuf.length > 0) {
          const isPng = contentType.includes("png");
          return await uploadPhotoBuffer(imgBuf, isPng ? "image/png" : "image/jpeg", isPng ? "png" : "jpg");
        }
      }
    }
    throw err;
  }
}

function parseChannelTargetInput(rawInput) {
  const clean = String(rawInput || "").trim();
  if (!clean) return { valid: false, reason: "empty" };
  if (/^(?:https?:\/\/)?(?:www\.)?(?:t\.me|telegram\.me)\/(?:\+|joinchat\/)/i.test(clean)) {
    return { valid: false, reason: "invite_link" };
  }
  const numericMatch = clean.match(/^(-100\d{5,20})$/);
  if (numericMatch) {
    return { valid: true, target: numericMatch[1] };
  }
  const urlMatch = clean.match(/^(?:https?:\/\/)?(?:www\.)?(?:t\.me|telegram\.me)\/([a-zA-Z0-9_]{4,64})(?:\/.*)?$/i);
  if (urlMatch) {
    return { valid: true, target: `@${urlMatch[1]}` };
  }
  const handleMatch = clean.match(/^@?([a-zA-Z][a-zA-Z0-9_]{3,63})$/);
  if (handleMatch) {
    return { valid: true, target: `@${handleMatch[1]}` };
  }
  return { valid: false, reason: "invalid_format" };
}

function extractForwardedChannelInfo(message) {
  const originChat =
    message?.forward_origin?.type === "channel" && message.forward_origin.chat
      ? message.forward_origin.chat
      : null;
  const legacyChat =
    message?.forward_from_chat?.type === "channel" ? message.forward_from_chat : null;
  const ch = originChat || legacyChat;
  if (!ch || !ch.id) return null;
  return {
    id: String(ch.id),
    title: String(ch.title || ch.username || ch.id),
    username: ch.username ? String(ch.username) : "",
  };
}

async function writeFetchResponseToFile(res, destPath) {
  if (res.body && typeof res.body.getReader === "function" && typeof Readable.fromWeb === "function") {
    const outStream = fs.createWriteStream(destPath);
    await pipeline(Readable.fromWeb(res.body), outStream);
    const stat = await fs.promises.stat(destPath);
    return stat.size;
  }
  const bytes = Buffer.from(await res.arrayBuffer());
  if (!bytes.length) {
    return 0;
  }
  await fs.promises.writeFile(destPath, bytes);
  return bytes.length;
}

function isTransientNetworkError(err) {
  const msg = String(err?.message || err || "").toLowerCase();
  return (
    msg.includes("terminated") ||
    msg.includes("econnreset") ||
    msg.includes("etimedout") ||
    msg.includes("socket") ||
    msg.includes("abort") ||
    msg.includes("fetch failed")
  );
}

async function downloadFileToPath(url, destPath, retries = 1) {
  let lastErr = null;
  for (let attempt = 0; attempt <= retries; attempt += 1) {
    try {
      const fetchOpts =
        typeof AbortSignal !== "undefined" && typeof AbortSignal.timeout === "function"
          ? { signal: AbortSignal.timeout(45_000) }
          : undefined;
      const res = fetchOpts ? await fetch(url, fetchOpts) : await fetch(url);
      if (!res.ok) {
        throw new Error(`Download failed (${res.status} ${res.statusText})`);
      }
      const written = await writeFetchResponseToFile(res, destPath);
      if (!written) {
        throw new Error("Downloaded file is empty.");
      }
      return;
    } catch (err) {
      lastErr = err;
      await fs.promises.unlink(destPath).catch(() => {});
      if (attempt < retries && isTransientNetworkError(err)) {
        await new Promise((r) => setTimeout(r, 400));
        continue;
      }
      throw err;
    }
  }
  throw lastErr || new Error("Download failed.");
}

function telegramRleDecode(buf) {
  const out = [];
  for (let i = 0; i < buf.length; i += 1) {
    if (buf[i] === 0x00) {
      if (i + 1 >= buf.length) break;
      const count = buf[i + 1];
      for (let c = 0; c < count; c += 1) {
        out.push(0x00);
      }
      i += 1;
    } else {
      out.push(buf[i]);
    }
  }
  return Buffer.from(out);
}

/**
 * Decodes a Telegram Bot API v4 file_id into MTProto InputDocumentFileLocation components
 * so streaming & downloads work even across stateless container restarts without needing user entity cache.
 */
function decodeTelegramBotFileId(fileId) {
  const clean = String(fileId || "").trim();
  if (!clean || clean.length < 16) return null;

  try {
    let b64 = clean.replace(/-/g, "+").replace(/_/g, "/");
    while (b64.length % 4 !== 0) b64 += "=";
    const raw = Buffer.from(b64, "base64");
    if (!raw.length) return null;

    const decoded = telegramRleDecode(raw);
    if (decoded.length < 18) return null;

    const majorVersion = decoded[decoded.length - 1];
    if (majorVersion !== 4) return null;

    const payload = decoded.subarray(0, decoded.length - 2);
    if (payload.length < 24) return null;

    let pos = 0;
    const rawTypeId = payload.readUInt32LE(pos);
    pos += 4;
    const WEB_LOCATION_FLAG = 1 << 24;
    const FILE_REFERENCE_FLAG = 1 << 25;
    if ((rawTypeId & WEB_LOCATION_FLAG) !== 0) return null;

    const typeId = rawTypeId & ~(FILE_REFERENCE_FLAG | WEB_LOCATION_FLAG);
    const dcId = payload.readInt32LE(pos);
    pos += 4;
    if (dcId <= 0 || dcId > 32) return null;

    let fileReference = Buffer.alloc(0);
    if ((rawTypeId & FILE_REFERENCE_FLAG) !== 0) {
      if (pos >= payload.length) return null;
      let len = payload[pos];
      let headerBytes = 1;
      pos += 1;
      if (len === 254) {
        if (pos + 3 > payload.length) return null;
        len = payload[pos] | (payload[pos + 1] << 8) | (payload[pos + 2] << 16);
        pos += 3;
        headerBytes = 4;
      }
      if (pos + len > payload.length) return null;
      fileReference = Buffer.from(payload.subarray(pos, pos + len));
      pos += len;
      const pad = (4 - ((headerBytes + len) % 4)) % 4;
      pos += pad;
    }

    if (pos + 16 > payload.length) return null;
    const id = payload.readBigInt64LE(pos);
    pos += 8;
    const accessHash = payload.readBigInt64LE(pos);
    pos += 8;

    return {
      majorVersion,
      typeId,
      dcId,
      id: id.toString(),
      accessHash: accessHash.toString(),
      fileReference,
    };
  } catch (_) {
    return null;
  }
}

const MTPROTO_SESSION_DOC_ID = "telegramMtprotoSession";
let mtprotoState = { key: "", client: null, promise: null };
let mtprotoFloodWaitUntilMs = 0;

function parseTelegramFloodWaitSeconds(err) {
  const direct = Number(err?.seconds || 0);
  if (Number.isFinite(direct) && direct > 0) return Math.ceil(direct);
  const text = String(err?.errorMessage || err?.message || "");
  const m1 = text.match(/FLOOD_WAIT_(\d+)/i);
  if (m1 && m1[1]) return Math.ceil(Number(m1[1]));
  const m2 = text.match(/wait of (\d+) seconds/i);
  if (m2 && m2[1]) return Math.ceil(Number(m2[1]));
  return 0;
}

function computeMtprotoKeyHash(apiId, apiHash, botToken) {
  return crypto
    .createHash("sha256")
    .update(`${Number(apiId)}:${String(apiHash || "").trim()}:${String(botToken || "").trim()}`)
    .digest("hex");
}

async function loadPersistedMtprotoSession(db, keyHash) {
  const envSession = String(process.env.TELEGRAM_MTPROTO_SESSION || "").trim();
  if (envSession) return envSession;
  if (!db || typeof db.collection !== "function") return "";
  try {
    const snap = await db.collection("appConfig").doc(MTPROTO_SESSION_DOC_ID).get();
    if (!snap || !snap.exists) return "";
    const data = snap.data() || {};
    const savedSession = String(data.sessionString || "").trim();
    if (!savedSession) return "";
    if (data.keyHash && String(data.keyHash) !== String(keyHash)) return "";
    return savedSession;
  } catch (err) {
    console.warn("Could not load persisted MTProto session:", err?.message || err);
    return "";
  }
}

async function savePersistedMtprotoSession(db, keyHash, sessionString) {
  const cleanSession = String(sessionString || "").trim();
  if (!cleanSession || !db || typeof db.collection !== "function") return;
  try {
    await db.collection("appConfig").doc(MTPROTO_SESSION_DOC_ID).set(
      {
        sessionString: cleanSession,
        keyHash: String(keyHash),
        updatedAt: new Date(),
      },
      { merge: true }
    );
  } catch (err) {
    console.warn("Could not save persisted MTProto session:", err?.message || err);
  }
}

function loadTelegramMtprotoLibs(telegramLibOverride = null) {
  if (telegramLibOverride) {
    return telegramLibOverride;
  }
  const { TelegramClient, Api } = require("telegram");
  const { StringSession } = require("telegram/sessions");
  const bigInt = require("big-integer");
  return { TelegramClient, Api, StringSession, bigInt };
}

async function getMtprotoClient({ apiId, apiHash, botToken, db = null, telegramLibOverride = null }) {
  const numApiId = Number(apiId);
  const cleanHash = String(apiHash || "").trim();
  const cleanToken = String(botToken || "").trim();
  if (!numApiId || !cleanHash || !cleanToken) {
    throw new Error("TELEGRAM_API_ID and TELEGRAM_API_HASH are required for >20 MB MTProto downloads.");
  }

  const keyHash = computeMtprotoKeyHash(numApiId, cleanHash, cleanToken);

  if (telegramLibOverride) {
    const libs = loadTelegramMtprotoLibs(telegramLibOverride);
    const savedSession = await loadPersistedMtprotoSession(db, keyHash);
    const client = new libs.TelegramClient(new libs.StringSession(savedSession), numApiId, cleanHash, {
      connectionRetries: 3,
      useWSS: false,
    });
    if (typeof client.setLogLevel === "function") client.setLogLevel("warn");
    let reused = false;
    if (savedSession && typeof client.connect === "function") {
      try {
        await client.connect();
        const authed = typeof client.checkAuthorization === "function" ? await client.checkAuthorization() : true;
        reused = Boolean(authed);
      } catch (_) {
        reused = false;
      }
    }
    if (!reused) {
      await client.start({ botAuthToken: cleanToken });
      const freshSession = typeof client.session?.save === "function" ? String(client.session.save() || "") : "";
      if (freshSession) {
        await savePersistedMtprotoSession(db, keyHash, freshSession);
      }
    }
    return { client, libs };
  }

  const key = `${numApiId}:${cleanHash}:${cleanToken}`;
  if (mtprotoState.key === key && mtprotoState.client && !mtprotoState.client.disconnected) {
    return { client: mtprotoState.client, libs: loadTelegramMtprotoLibs() };
  }
  if (mtprotoState.key === key && mtprotoState.promise) {
    return mtprotoState.promise;
  }

  const prevClient = mtprotoState.client;
  const prevKey = mtprotoState.key;
  const connectPromise = (async () => {
    const libs = loadTelegramMtprotoLibs();

    // 1. If we already have an authorized client in memory for the same key and it only disconnected,
    //    reconnect the SAME session via client.connect() — NEVER trigger auth.ImportBotAuthorization again!
    if (prevKey === key && prevClient && typeof prevClient.connect === "function") {
      try {
        await prevClient.connect();
        const authed =
          typeof prevClient.checkAuthorization === "function" ? await prevClient.checkAuthorization() : true;
        if (authed) {
          mtprotoState = { key, client: prevClient, promise: null };
          return { client: prevClient, libs };
        }
      } catch (reconnErr) {
        console.warn("In-memory MTProto reconnect failed, checking persisted session:", reconnErr?.message || reconnErr);
      }
    }

    if (prevClient && typeof prevClient.disconnect === "function") {
      await prevClient.disconnect().catch(() => {});
    }

    // 2. Cold boot / Render restart: restore persisted StringSession from PostgreSQL/Firestore so
    //    deployments & restarts NEVER call auth.ImportBotAuthorization (zero Telegram FLOOD_WAIT!).
    const savedSession = await loadPersistedMtprotoSession(db, keyHash);
    if (savedSession) {
      const restoredClient = new libs.TelegramClient(new libs.StringSession(savedSession), numApiId, cleanHash, {
        connectionRetries: 5,
        autoReconnect: true,
        useWSS: false,
      });
      if (typeof restoredClient.setLogLevel === "function") {
        restoredClient.setLogLevel("warn");
      }
      try {
        await restoredClient.connect();
        const authed =
          typeof restoredClient.checkAuthorization === "function"
            ? await restoredClient.checkAuthorization()
            : true;
        if (authed) {
          mtprotoState = { key, client: restoredClient, promise: null };
          return { client: restoredClient, libs };
        }
      } catch (restoreErr) {
        console.warn("Persisted MTProto session invalid, falling back to bot login:", restoreErr?.message || restoreErr);
      }
      if (typeof restoredClient.disconnect === "function") {
        await restoredClient.disconnect().catch(() => {});
      }
    }

    // 3. First-time bot login (or revoked key): respect active FLOOD_WAIT cooldown so retries don't extend it
    const nowMs = Date.now();
    if (mtprotoFloodWaitUntilMs > nowMs) {
      const remainingSec = Math.max(1, Math.ceil((mtprotoFloodWaitUntilMs - nowMs) / 1000));
      const err = new Error(`Telegram stream session cooling down. Please retry in ${remainingSec} seconds.`);
      err.statusCode = 503;
      err.retryAfterSec = remainingSec;
      throw err;
    }

    const client = new libs.TelegramClient(new libs.StringSession(""), numApiId, cleanHash, {
      connectionRetries: 5,
      autoReconnect: true,
      useWSS: false,
    });
    if (typeof client.setLogLevel === "function") {
      client.setLogLevel("warn");
    }
    try {
      await client.start({ botAuthToken: cleanToken });
    } catch (startErr) {
      const waitSec = parseTelegramFloodWaitSeconds(startErr);
      if (waitSec > 0) {
        mtprotoFloodWaitUntilMs = Date.now() + waitSec * 1000;
        const wrapped = new Error(
          `Telegram stream login temporarily rate-limited. Please retry in ${waitSec} seconds.`
        );
        wrapped.statusCode = 503;
        wrapped.retryAfterSec = waitSec;
        throw wrapped;
      }
      throw startErr;
    }

    mtprotoFloodWaitUntilMs = 0;
    const freshSession = typeof client.session?.save === "function" ? String(client.session.save() || "") : "";
    if (freshSession) {
      await savePersistedMtprotoSession(db, keyHash, freshSession);
    }

    mtprotoState = { key, client, promise: null };
    return { client, libs };
  })();

  mtprotoState = { key, client: prevKey === key ? prevClient : null, promise: connectPromise };
  try {
    return await connectPromise;
  } catch (err) {
    if (mtprotoState.promise === connectPromise) {
      mtprotoState = { key: prevKey === key ? key : "", client: prevKey === key ? prevClient : null, promise: null };
    }
    throw err;
  }
}

async function resolveMtprotoMediaDescriptor({
  client,
  libs,
  tgFileId,
  tgChatId = null,
  tgMessageId = null,
  tgFileSize = 0,
  forceRefresh = false,
}) {
  const { Api, bigInt } = libs;

  if (!forceRefresh) {
    const decoded = decodeTelegramBotFileId(tgFileId);
    if (decoded) {
      return {
        dcId: decoded.dcId,
        fileSize: toSafeByteLength(tgFileSize),
        location: new Api.InputDocumentFileLocation({
          id: bigInt(decoded.id),
          accessHash: bigInt(decoded.accessHash),
          fileReference: decoded.fileReference,
          thumbSize: "",
        }),
      };
    }
  }

  if (tgChatId && tgMessageId && typeof client.getMessages === "function") {
    const peer = /^-?\d+$/.test(String(tgChatId)) ? Number(tgChatId) : String(tgChatId);
    const msgs = await client.getMessages(peer, { ids: [Number(tgMessageId)] });
    const msg = Array.isArray(msgs) ? msgs[0] : msgs;
    const doc = msg?.media?.document || msg?.document;
    if (doc) {
      return {
        dcId: doc.dcId,
        fileSize: toSafeByteLength(doc.size) || toSafeByteLength(tgFileSize),
        location: new Api.InputDocumentFileLocation({
          id: doc.id,
          accessHash: doc.accessHash,
          fileReference: doc.fileReference,
          thumbSize: "",
        }),
      };
    }
  }

  throw new Error("Unable to resolve Telegram MTProto media location for this video.");
}

async function downloadTelegramFileViaMtproto({
  apiId,
  apiHash,
  botToken,
  tgFileId,
  tgChatId = null,
  tgMessageId = null,
  tgFileSize = 0,
  destPath,
  db = null,
  telegramLibOverride = null,
}) {
  const { client, libs } = await getMtprotoClient({ apiId, apiHash, botToken, db, telegramLibOverride });
  let descriptor = await resolveMtprotoMediaDescriptor({
    client,
    libs,
    tgFileId,
    tgChatId,
    tgMessageId,
    tgFileSize,
    forceRefresh: false,
  });

  const runDownload = async (desc) => {
    const outStream = fs.createWriteStream(destPath);
    let written = 0;
    try {
      const iter = client.iterDownload({
        file: desc.location,
        dcId: desc.dcId,
        offset: libs.bigInt.zero,
        requestSize: MTPROTO_CHUNK_SIZE,
      });
      for await (const rawChunk of iter) {
        const chunk = Buffer.isBuffer(rawChunk) ? rawChunk : Buffer.from(rawChunk);
        if (!chunk.length) break;
        written += chunk.length;
        if (!outStream.write(chunk)) {
          await new Promise((resolve, reject) => {
            outStream.once("drain", resolve);
            outStream.once("error", reject);
          });
        }
      }
      await new Promise((resolve, reject) => {
        outStream.once("finish", resolve);
        outStream.once("error", reject);
        outStream.end();
      });
      return written;
    } catch (err) {
      outStream.destroy();
      throw err;
    }
  };

  try {
    return await runDownload(descriptor);
  } catch (err) {
    const msg = String(err?.errorMessage || err?.message || "");
    if (msg.includes("FILE_REFERENCE") && tgChatId && tgMessageId) {
      descriptor = await resolveMtprotoMediaDescriptor({
        client,
        libs,
        tgFileId,
        tgChatId,
        tgMessageId,
        tgFileSize,
        forceRefresh: true,
      });
      return runDownload(descriptor);
    }
    throw err;
  }
}

const BOT_CONFIG_CACHE_TTL_MS = 15 * 60 * 1000;
const BOT_SESSION_CACHE_TTL_MS = 30 * 60 * 1000;
const MEMBER_PERM_CACHE_TTL_MS = 15 * 60 * 1000;
const botConfigCacheByDb = new WeakMap();
const botSessionCacheByDb = new WeakMap();
const memberPermCacheByDb = new WeakMap();

function isFirestoreQuotaError(err) {
  return /RESOURCE_EXHAUSTED|Quota limit exceeded/i.test(String(err?.message || err || ""));
}

function getCachedBotSession(db, telegramUserId) {
  const map = getScopedMap(botSessionCacheByDb, db);
  if (!map) return null;
  const entry = map.get(String(telegramUserId));
  if (entry && Date.now() - entry.cachedAt < BOT_SESSION_CACHE_TTL_MS) {
    return entry.data;
  }
  return null;
}

function setCachedBotSession(db, telegramUserId, data) {
  const map = getScopedMap(botSessionCacheByDb, db);
  if (!map || !telegramUserId) return;
  if (map.size >= 500) {
    const oldest = map.keys().next().value;
    if (oldest !== undefined) map.delete(oldest);
  }
  map.set(String(telegramUserId), { data: { ...data }, cachedAt: Date.now() });
}

function deleteCachedBotSession(db, telegramUserId) {
  const map = getScopedMap(botSessionCacheByDb, db);
  if (map) map.delete(String(telegramUserId));
}

function getCachedMemberPerm(db, memberId) {
  const map = getScopedMap(memberPermCacheByDb, db);
  if (!map) return null;
  const entry = map.get(String(memberId));
  if (entry && Date.now() - entry.cachedAt < MEMBER_PERM_CACHE_TTL_MS) {
    return entry.data;
  }
  return null;
}

function setCachedMemberPerm(db, memberId, data) {
  const map = getScopedMap(memberPermCacheByDb, db);
  if (!map || !memberId) return;
  if (map.size >= 500) {
    const oldest = map.keys().next().value;
    if (oldest !== undefined) map.delete(oldest);
  }
  map.set(String(memberId), { data, cachedAt: Date.now() });
}

async function resolveBotRuntimeConfig(db) {
  let botToken = (process.env.TELEGRAM_BOT_TOKEN || "").trim();
  let apiBase = (process.env.TELEGRAM_API_BASE || "https://api.telegram.org").trim().replace(/\/+$/, "");
  let webhookSecret = (process.env.TELEGRAM_WEBHOOK_SECRET || "").trim();
  let shareLinkBaseUrl = normalizeShareBaseUrl(process.env.SHARE_LINK_BASE_URL || "");
  let apiId = parseInt(String(process.env.TELEGRAM_API_ID || "0").trim(), 10) || 0;
  let apiHash = (process.env.TELEGRAM_API_HASH || "").trim();
  const envSig = `${botToken}|${apiBase}|${webhookSecret}|${shareLinkBaseUrl}|${apiId}|${apiHash}`;

  if (db && typeof db === "object") {
    const cached = botConfigCacheByDb.get(db);
    if (cached && cached.envSig === envSig && Date.now() - cached.cachedAt < BOT_CONFIG_CACHE_TTL_MS) {
      return cached.value;
    }
  }

  if (!botToken || !apiId || !apiHash) {
    try {
      const cfgSnap = await db.collection("appConfig").doc("telegramBot").get();
      if (cfgSnap.exists) {
        const data = cfgSnap.data() || {};
        if (!botToken && typeof data.botToken === "string" && data.botToken.trim()) {
          botToken = data.botToken.trim();
        }
        if (!webhookSecret && typeof data.webhookSecret === "string" && data.webhookSecret.trim()) {
          webhookSecret = data.webhookSecret.trim();
        }
        if (!apiId && data.apiId) {
          apiId = parseInt(String(data.apiId).trim(), 10) || 0;
        }
        if (!apiHash && typeof data.apiHash === "string" && data.apiHash.trim()) {
          apiHash = data.apiHash.trim();
        }
      }
    } catch (_) {
      // Ignore config read errors
    }
  }

  if (!shareLinkBaseUrl) {
    try {
      const generalSnap = await db.collection("appConfig").doc("general").get();
      if (generalSnap.exists) {
        const gData = generalSnap.data() || {};
        if (typeof gData.shareLinkBaseUrl === "string" && gData.shareLinkBaseUrl.trim()) {
          shareLinkBaseUrl = normalizeShareBaseUrl(gData.shareLinkBaseUrl);
        }
      }
    } catch (_) {
      // Ignore config read errors
    }
  }

  const resolved = { botToken, apiBase, webhookSecret, shareLinkBaseUrl, apiId, apiHash };
  if (db && typeof db === "object" && resolved.botToken) {
    botConfigCacheByDb.set(db, { envSig, cachedAt: Date.now(), value: resolved });
  }
  return resolved;
}

async function authenticateCreatorMember(db, emailOrPhone, passkey) {
  const cleanKey = String(passkey || "").trim();
  if (!cleanKey) return null;

  let customAdminHash = "";
  try {
    const secSnap = await db.collection("appConfig").doc("adminSecurity").get();
    if (secSnap && secSnap.exists) {
      const sData = secSnap.data() || {};
      if (typeof sData.adminMasterKeyHash === "string") {
        customAdminHash = sData.adminMasterKeyHash.trim();
      }
    }
  } catch (_) {
    // Fallback to default Superadmin passkeys
  }

  const keyHash = crypto.createHash("sha256").update(cleanKey, "utf8").digest("hex");
  const matchesSuperadmin =
    cleanKey === SUPERADMIN_PASSKEY ||
    (customAdminHash.length === 64 ? keyHash === customAdminHash : cleanKey === "7827128212");

  if (matchesSuperadmin) {
    return {
      memberId: "admin",
      name: "Admin",
      emailOrPhone: "teamaltamash@gmail.com",
      role: "admin",
      canUpload: true,
    };
  }

  const snap = await db.collection("members").where("passkey", "==", cleanKey).limit(50).get();
  if (snap.empty) return null;

  const cleanIdentity = String(emailOrPhone || "").trim().toLowerCase();
  const activeMatches = [];
  for (const doc of snap.docs) {
    const data = doc.data() || {};
    if (data.isActive !== true) continue;
    if (cleanIdentity && String(data.emailOrPhone || "").trim().toLowerCase() !== cleanIdentity) {
      continue;
    }
    activeMatches.push({ doc, data });
  }

  if (activeMatches.length === 0) return null;
  if (!cleanIdentity && activeMatches.length > 1) {
    return { ambiguous: true };
  }

  const { doc, data } = activeMatches[0];
  const allowed = Array.isArray(data.allowedOptions) ? data.allowedOptions : null;
  const canUpload = !allowed || allowed.includes("UPLOAD_VIDEOS");
  if (!canUpload) {
    return {
      memberId: data.memberId || doc.id,
      name: data.name || "Creator",
      emailOrPhone: String(data.emailOrPhone || ""),
      role: String(data.role || "member"),
      allowedOptions: allowed,
      canUpload: false,
    };
  }
  return {
    memberId: data.memberId || doc.id,
    name: String(data.name || "Creator").trim() || "Creator",
    emailOrPhone: String(data.emailOrPhone || ""),
    role: String(data.role || "member"),
    allowedOptions: allowed,
    canUpload: true,
  };
}

const processedUpdateIds = new Set();

function recordAndCheckDuplicateUpdate(updateId) {
  if (updateId == null) return false;
  const key = String(updateId);
  if (processedUpdateIds.has(key)) return true;
  processedUpdateIds.add(key);
  if (processedUpdateIds.size > 1000) {
    const oldest = processedUpdateIds.values().next().value;
    if (oldest !== undefined) processedUpdateIds.delete(oldest);
  }
  return false;
}

async function handleTelegramUpdate({
  update,
  db,
  storage,
  functionBaseUrl,
  playbackSecret,
  telegramLibOverride = null,
}) {
  if (recordAndCheckDuplicateUpdate(update?.update_id)) {
    return { ok: true, duplicate: true };
  }
  const callbackQuery = update?.callback_query;
  const message = update?.message || update?.edited_message;
  const activeChatId = callbackQuery?.message?.chat?.id ?? message?.chat?.id;
  const activeFrom = callbackQuery?.from || message?.from;
  if (!activeChatId || !activeFrom) {
    return { ok: true, ignored: true };
  }

  const chatId = activeChatId;
  const telegramUserId = String(activeFrom.id);
  const { botToken, apiBase, shareLinkBaseUrl, apiId, apiHash } = await resolveBotRuntimeConfig(db);
  if (!botToken) {
    throw new Error("TELEGRAM_BOT_TOKEN is not configured.");
  }

  const replyText = async (text, extra = {}) =>
    telegramApiJson(apiBase, botToken, "sendMessage", {
      chat_id: chatId,
      text,
      ...extra,
    });

  const loadCurrentBotSession = async () => {
    let s = getCachedBotSession(db, telegramUserId);
    if (!s) {
      const sessionSnap = await db.collection("telegramBotSessions").doc(telegramUserId).get();
      if (sessionSnap.exists) {
        s = sessionSnap.data() || {};
        setCachedBotSession(db, telegramUserId, s);
      }
    }
    return s || null;
  };

  const persistCreatorCaptionPrefs = async (currentSession, patch) => {
    const updated = {
      ...currentSession,
      ...patch,
    };
    await db.collection("telegramBotSessions").doc(telegramUserId).set(patch, { merge: true });
    setCachedBotSession(db, telegramUserId, updated);
    const memberId = String(updated.memberId || "").trim();
    if (
      memberId &&
      memberId !== "admin" &&
      (Object.prototype.hasOwnProperty.call(patch, "customCaptionHeader") ||
        Object.prototype.hasOwnProperty.call(patch, "customCaptionFooter"))
    ) {
      const memberPatch = {};
      if (Object.prototype.hasOwnProperty.call(patch, "customCaptionHeader")) {
        memberPatch.customCaptionHeader = patch.customCaptionHeader;
      }
      if (Object.prototype.hasOwnProperty.call(patch, "customCaptionFooter")) {
        memberPatch.customCaptionFooter = patch.customCaptionFooter;
      }
      await db.collection("members").doc(memberId).set(memberPatch, { merge: true }).catch(() => {});
    }
    return updated;
  };

  const hydrateCreatorCaptionPrefs = async (memberId, prevSession) => {
    let customCaptionHeader = sanitizeCustomCaptionBlock(
      prevSession?.customCaptionHeader || "",
      MAX_CUSTOM_HEADER_LENGTH
    );
    let customCaptionFooter = sanitizeCustomCaptionBlock(
      prevSession?.customCaptionFooter || "",
      MAX_CUSTOM_FOOTER_LENGTH
    );
    if ((!customCaptionHeader || !customCaptionFooter) && memberId && memberId !== "admin") {
      try {
        const mSnap = await db.collection("members").doc(memberId).get();
        if (mSnap && mSnap.exists) {
          const mData = mSnap.data() || {};
          if (!customCaptionHeader && mData.customCaptionHeader) {
            customCaptionHeader = sanitizeCustomCaptionBlock(mData.customCaptionHeader, MAX_CUSTOM_HEADER_LENGTH);
          }
          if (!customCaptionFooter && mData.customCaptionFooter) {
            customCaptionFooter = sanitizeCustomCaptionBlock(mData.customCaptionFooter, MAX_CUSTOM_FOOTER_LENGTH);
          }
        }
      } catch (_) {
        // Best-effort restore
      }
    }
    return { customCaptionHeader, customCaptionFooter };
  };

  // 0. Handle Inline Button Callbacks (Header / Footer Edit & Preview)
  if (callbackQuery) {
    const cbData = String(callbackQuery.data || "").trim();
    await telegramApiJson(apiBase, botToken, "answerCallbackQuery", {
      callback_query_id: callbackQuery.id,
    }).catch(() => {});

    const s = await loadCurrentBotSession();
    if (!s) {
      await replyText("🔐 Pehle apna VidVora Password bhej kar account link karein.");
      return { ok: true, action: "auth_required" };
    }

    if (cbData === "caption:edit_header") {
      await persistCreatorCaptionPrefs(s, { pendingInputAction: "set_header" });
      await replyText(
        [
          "✏️ <b>Apna naya Header bhejein</b>",
          "",
          "Jo bhi text, emoji ya channel link aap yahan bhejenge woh har video post ke <b>upar (Header)</b> me lag jayega.",
        ].join("\n"),
        { parse_mode: "HTML", reply_markup: buildEditPromptCancelKeyboard("header") }
      );
      return { ok: true, action: "prompt_header" };
    }

    if (cbData === "caption:edit_footer") {
      await persistCreatorCaptionPrefs(s, { pendingInputAction: "set_footer" });
      await replyText(
        [
          "✏️ <b>Apna naya Footer bhejein</b>",
          "",
          "Jo bhi text, channel link ya promo message aap yahan bhejenge woh har video post ke <b>neeche (Footer)</b> me lag jayega.",
        ].join("\n"),
        { parse_mode: "HTML", reply_markup: buildEditPromptCancelKeyboard("footer") }
      );
      return { ok: true, action: "prompt_footer" };
    }

    if (cbData === "caption:cancel") {
      await persistCreatorCaptionPrefs(s, { pendingInputAction: "" });
      await replyText("✅ Edit cancel kar diya gaya hai.", {
        reply_markup: CREATOR_BOT_REPLY_KEYBOARD,
      });
      return { ok: true, action: "cancelled" };
    }

    if (cbData === "caption:remove_header") {
      const updated = await persistCreatorCaptionPrefs(s, {
        customCaptionHeader: CAPTION_BLOCK_NONE,
        pendingInputAction: "",
      });
      await replyText(`✅ Header remove (hide) kar diya gaya hai.\n\n${buildCaptionSettingsHtml(updated)}`, {
        parse_mode: "HTML",
        disable_web_page_preview: true,
        reply_markup: buildCaptionSettingsKeyboard(),
      });
      return { ok: true, action: "header_removed" };
    }

    if (cbData === "caption:reset_header" || cbData === "caption:clear_header") {
      const updated = await persistCreatorCaptionPrefs(s, {
        customCaptionHeader: "",
        pendingInputAction: "",
      });
      await replyText(`✅ Header default par reset ho gaya.\n\n${buildCaptionSettingsHtml(updated)}`, {
        parse_mode: "HTML",
        disable_web_page_preview: true,
        reply_markup: buildCaptionSettingsKeyboard(),
      });
      return { ok: true, action: "header_reset" };
    }

    if (cbData === "caption:remove_footer" || cbData === "caption:clear_footer") {
      const updated = await persistCreatorCaptionPrefs(s, {
        customCaptionFooter: CAPTION_BLOCK_NONE,
        pendingInputAction: "",
      });
      await replyText(`✅ Footer remove (hide) kar diya gaya hai.\n\n${buildCaptionSettingsHtml(updated)}`, {
        parse_mode: "HTML",
        disable_web_page_preview: true,
        reply_markup: buildCaptionSettingsKeyboard(),
      });
      return { ok: true, action: "footer_removed" };
    }

    if (cbData === "caption:reset_footer") {
      const updated = await persistCreatorCaptionPrefs(s, {
        customCaptionFooter: "",
        pendingInputAction: "",
      });
      await replyText(
        `✅ Footer default (How to play video + channel link) par reset ho gaya.\n\n${buildCaptionSettingsHtml(updated)}`,
        {
          parse_mode: "HTML",
          disable_web_page_preview: true,
          reply_markup: buildCaptionSettingsKeyboard(),
        }
      );
      return { ok: true, action: "footer_reset" };
    }

    if (cbData === "caption:preview") {
      const sampleCaption = buildShareMessageCaption({
        rawToken: "GM-SAMPLE99",
        shareLinkBaseUrl: shareLinkBaseUrl || SHARE_LINK_BASE_URL,
        customHeader: s.customCaptionHeader || "",
        customFooter: s.customCaptionFooter || "",
      });
      await replyText(`👁️ <b>Live Post Preview:</b>\n\n${sampleCaption}`, {
        parse_mode: "HTML",
        disable_web_page_preview: true,
        reply_markup: buildCaptionSettingsKeyboard(),
      });
      return { ok: true, action: "caption_preview" };
    }

    return { ok: true, action: "callback_ignored" };
  }

  const textBody = String(message.text || "").trim();

  // 1. Commands & Bottom Menu Buttons (zero commands needed for creators!)
  if (/^\/start(?:@\w+)?/i.test(textBody) || /^\/help(?:@\w+)?/i.test(textBody)) {
    await replyText(
      [
        "🎬 *VidVora Creator Auto-Upload Bot*",
        "",
        "1️⃣ Apna VidVora Creator / Admin account link karein:",
        "_(Bas apna Password seedha yahan bhej dein)_",
        "",
        "2️⃣ *Custom Header & Footer Set Karein:*",
        "Neeche diye gaye buttons se apna Header / Footer (apna channel link ya text) kabhi bhi edit karein, aur seedha koi bhi video ya VidVora link bhej kar apna link banayein! 👇",
      ].join("\n"),
      { parse_mode: "Markdown", reply_markup: CREATOR_BOT_REPLY_KEYBOARD }
    );
    return { ok: true, action: "help" };
  }

  if (/^\/cancel(?:@\w+)?(?:\s|$)/i.test(textBody) || textBody === "❌ Cancel") {
    const s = await loadCurrentBotSession();
    if (s && s.pendingInputAction) {
      await persistCreatorCaptionPrefs(s, { pendingInputAction: "" });
      await replyText("✅ Edit cancel kar diya gaya hai.", {
        reply_markup: CREATOR_BOT_REPLY_KEYBOARD,
      });
    } else {
      await replyText("ℹ️ Koi active edit mode nahi chal raha hai.", {
        reply_markup: CREATOR_BOT_REPLY_KEYBOARD,
      });
    }
    return { ok: true, action: "cancelled" };
  }

  if (textBody === "👁️ Preview Post") {
    const s = await loadCurrentBotSession();
    if (!s) {
      await replyText("🔐 Pehle apna VidVora Password bhej kar account link karein.", {
        reply_markup: CREATOR_BOT_REPLY_KEYBOARD,
      });
      return { ok: true, action: "auth_required" };
    }
    if (s.pendingInputAction) {
      await persistCreatorCaptionPrefs(s, { pendingInputAction: "" });
    }
    const sampleCaption = buildShareMessageCaption({
      rawToken: "GM-SAMPLE99",
      shareLinkBaseUrl: shareLinkBaseUrl || SHARE_LINK_BASE_URL,
      customHeader: s.customCaptionHeader || "",
      customFooter: s.customCaptionFooter || "",
    });
    await replyText(`👁️ <b>Live Post Preview:</b>\n\n${sampleCaption}`, {
      parse_mode: "HTML",
      disable_web_page_preview: true,
      reply_markup: buildCaptionSettingsKeyboard(),
    });
    return { ok: true, action: "caption_preview" };
  }

  if (
    /^\/(?:caption|editcaption|settings)(?:@\w+)?(?:\s|$)/i.test(textBody) ||
    textBody === "⚙️ Header / Footer Settings"
  ) {
    const s = await loadCurrentBotSession();
    if (!s) {
      await replyText("🔐 Pehle apna VidVora Password bhej kar account link karein.", {
        reply_markup: CREATOR_BOT_REPLY_KEYBOARD,
      });
      return { ok: true, action: "auth_required" };
    }
    if (s.pendingInputAction) {
      await persistCreatorCaptionPrefs(s, { pendingInputAction: "" });
    }
    await replyText(buildCaptionSettingsHtml(s), {
      parse_mode: "HTML",
      disable_web_page_preview: true,
      reply_markup: buildCaptionSettingsKeyboard(),
    });
    return { ok: true, action: "caption_settings" };
  }

  if (/^\/(?:set)?header(?:@\w+)?(?:\s|$)/i.test(textBody) || textBody === "✏️ Edit Header") {
    const s = await loadCurrentBotSession();
    if (!s) {
      await replyText("🔐 Pehle apna VidVora Password bhej kar account link karein.", {
        reply_markup: CREATOR_BOT_REPLY_KEYBOARD,
      });
      return { ok: true, action: "auth_required" };
    }
    const rawHeader =
      textBody === "✏️ Edit Header" ? "" : textBody.replace(/^\/(?:set)?header(?:@\w+)?/i, "").trim();
    if (!rawHeader) {
      await persistCreatorCaptionPrefs(s, { pendingInputAction: "set_header" });
      const curHdr = isCaptionBlockDisabled(s.customCaptionHeader)
        ? "<i>Removed (Hidden)</i>"
        : s.customCaptionHeader
          ? escapeHtml(s.customCaptionHeader)
          : "<i>Default (🎬 VidVora — Watch Video)</i>";
      await replyText(
        [
          "✏️ <b>Apna naya Header text ya channel link bhejein:</b>",
          "",
          `Current Header: ${curHdr}`,
          "",
          "<i>Bas apna text / link yahan bhej dein, ya neeche button se Remove / Default / Cancel karein 👇</i>",
        ].join("\n"),
        { parse_mode: "HTML", disable_web_page_preview: true, reply_markup: buildEditPromptCancelKeyboard("header") }
      );
      return { ok: true, action: "prompt_header" };
    }
    const cleanHeader = sanitizeCustomCaptionBlock(rawHeader, MAX_CUSTOM_HEADER_LENGTH);
    const updated = await persistCreatorCaptionPrefs(s, {
      customCaptionHeader: cleanHeader,
      pendingInputAction: "",
    });
    await replyText(`✅ <b>Header update ho gaya!</b>\n\n${buildCaptionSettingsHtml(updated)}`, {
      parse_mode: "HTML",
      disable_web_page_preview: true,
      reply_markup: buildCaptionSettingsKeyboard(),
    });
    return { ok: true, action: "header_updated", customCaptionHeader: cleanHeader };
  }

  if (/^\/(?:set)?footer(?:@\w+)?(?:\s|$)/i.test(textBody) || textBody === "✏️ Edit Footer") {
    const s = await loadCurrentBotSession();
    if (!s) {
      await replyText("🔐 Pehle apna VidVora Password bhej kar account link karein.", {
        reply_markup: CREATOR_BOT_REPLY_KEYBOARD,
      });
      return { ok: true, action: "auth_required" };
    }
    const rawFooter =
      textBody === "✏️ Edit Footer" ? "" : textBody.replace(/^\/(?:set)?footer(?:@\w+)?/i, "").trim();
    if (!rawFooter) {
      await persistCreatorCaptionPrefs(s, { pendingInputAction: "set_footer" });
      const curFtr = isCaptionBlockDisabled(s.customCaptionFooter)
        ? "<i>Removed (Hidden)</i>"
        : s.customCaptionFooter
          ? escapeHtml(s.customCaptionFooter)
          : "<i>Default (वीडियो कैसे देखे / How to play video + https://t.me/VideoKaiseDekhe_ar)</i>";
      await replyText(
        [
          "✏️ <b>Apna naya Footer text ya channel link bhejein:</b>",
          "",
          `Current Footer: ${curFtr}`,
          "",
          "<i>Bas apna text / link yahan bhej dein, ya neeche button se Remove / Default / Cancel karein 👇</i>",
        ].join("\n"),
        { parse_mode: "HTML", disable_web_page_preview: true, reply_markup: buildEditPromptCancelKeyboard("footer") }
      );
      return { ok: true, action: "prompt_footer" };
    }
    const cleanFooter = sanitizeCustomCaptionBlock(rawFooter, MAX_CUSTOM_FOOTER_LENGTH);
    const updated = await persistCreatorCaptionPrefs(s, {
      customCaptionFooter: cleanFooter,
      pendingInputAction: "",
    });
    await replyText(`✅ <b>Footer update ho gaya!</b>\n\n${buildCaptionSettingsHtml(updated)}`, {
      parse_mode: "HTML",
      disable_web_page_preview: true,
      reply_markup: buildCaptionSettingsKeyboard(),
    });
    return { ok: true, action: "footer_updated", customCaptionFooter: cleanFooter };
  }

  if (/^\/(?:remove|hide)header(?:@\w+)?(?:\s|$)/i.test(textBody)) {
    const s = await loadCurrentBotSession();
    if (!s) {
      await replyText("🔐 Pehle apna VidVora Password bhej kar account link karein.");
      return { ok: true, action: "auth_required" };
    }
    const updated = await persistCreatorCaptionPrefs(s, {
      customCaptionHeader: CAPTION_BLOCK_NONE,
      pendingInputAction: "",
    });
    await replyText(`✅ Header remove (hide) kar diya gaya hai.\n\n${buildCaptionSettingsHtml(updated)}`, {
      parse_mode: "HTML",
      disable_web_page_preview: true,
      reply_markup: buildCaptionSettingsKeyboard(),
    });
    return { ok: true, action: "header_removed" };
  }

  if (/^\/(?:clear|reset|default)header(?:@\w+)?(?:\s|$)/i.test(textBody)) {
    const s = await loadCurrentBotSession();
    if (!s) {
      await replyText("🔐 Pehle apna VidVora Password bhej kar account link karein.");
      return { ok: true, action: "auth_required" };
    }
    const updated = await persistCreatorCaptionPrefs(s, {
      customCaptionHeader: "",
      pendingInputAction: "",
    });
    await replyText(`✅ Header default par reset ho gaya.\n\n${buildCaptionSettingsHtml(updated)}`, {
      parse_mode: "HTML",
      disable_web_page_preview: true,
      reply_markup: buildCaptionSettingsKeyboard(),
    });
    return { ok: true, action: "header_reset" };
  }

  if (/^\/(?:clear|remove|hide)footer(?:@\w+)?(?:\s|$)/i.test(textBody)) {
    const s = await loadCurrentBotSession();
    if (!s) {
      await replyText("🔐 Pehle apna VidVora Password bhej kar account link karein.");
      return { ok: true, action: "auth_required" };
    }
    const updated = await persistCreatorCaptionPrefs(s, {
      customCaptionFooter: CAPTION_BLOCK_NONE,
      pendingInputAction: "",
    });
    await replyText(`✅ Footer remove (hide) kar diya gaya hai.\n\n${buildCaptionSettingsHtml(updated)}`, {
      parse_mode: "HTML",
      disable_web_page_preview: true,
      reply_markup: buildCaptionSettingsKeyboard(),
    });
    return { ok: true, action: "footer_removed" };
  }

  if (/^\/(?:reset|default)footer(?:@\w+)?(?:\s|$)/i.test(textBody)) {
    const s = await loadCurrentBotSession();
    if (!s) {
      await replyText("🔐 Pehle apna VidVora Password bhej kar account link karein.");
      return { ok: true, action: "auth_required" };
    }
    const updated = await persistCreatorCaptionPrefs(s, {
      customCaptionFooter: "",
      pendingInputAction: "",
    });
    await replyText(
      `✅ Footer default (How to play video + channel link) par reset ho gaya.\n\n${buildCaptionSettingsHtml(updated)}`,
      {
        parse_mode: "HTML",
        disable_web_page_preview: true,
        reply_markup: buildCaptionSettingsKeyboard(),
      }
    );
    return { ok: true, action: "footer_reset" };
  }

  if (/^\/logout(?:@\w+)?/i.test(textBody) || textBody === "🔓 Logout") {
    deleteCachedBotSession(db, telegramUserId);
    await db.collection("telegramBotSessions").doc(telegramUserId).delete().catch(() => {});
    await replyText("🔓 Aapka VidVora account unlink ho gaya hai. Dobara link karne ke liye apna Password seedha bhej dein.", {
      parse_mode: "Markdown",
      reply_markup: CREATOR_BOT_REPLY_KEYBOARD,
    });
    return { ok: true, action: "logout" };
  }

  if (
    /^\/status(?:@\w+)?/i.test(textBody) ||
    /^\/channel(?:@\w+)?(?:\s|$)/i.test(textBody) ||
    textBody === "📊 My Status"
  ) {
    const s = await loadCurrentBotSession();
    if (!s) {
      await replyText("❌ Abhi account link nahi hai. Apna *Creator / Admin Password* seedha yahan bhej dein.", {
        parse_mode: "Markdown",
        reply_markup: CREATOR_BOT_REPLY_KEYBOARD,
      });
      return { ok: true, action: "status_unlinked" };
    }
    if (s.pendingInputAction) {
      await persistCreatorCaptionPrefs(s, { pendingInputAction: "" });
    }
    const headerStatus = isCaptionBlockDisabled(s.customCaptionHeader)
      ? "Removed"
      : sanitizeCustomCaptionBlock(s.customCaptionHeader || "", MAX_CUSTOM_HEADER_LENGTH)
        ? "Custom Set"
        : "Default";
    const footerStatus = isCaptionBlockDisabled(s.customCaptionFooter)
      ? "Removed"
      : sanitizeCustomCaptionBlock(s.customCaptionFooter || "", MAX_CUSTOM_FOOTER_LENGTH)
        ? "Custom Set"
        : "Default";
    await replyText(
      [
        `✅ Linked Account: *${escapeMarkdown(s.memberName || "Creator")}*`,
        `📤 Uploads via Bot: *${s.uploadCount || 0}*`,
        `📝 Header: *${headerStatus}* • Footer: *${footerStatus}*`,
        "",
        "Header / Footer edit karne ke liye neeche buttons use karein 👇",
      ].join("\n"),
      { parse_mode: "Markdown", reply_markup: CREATOR_BOT_REPLY_KEYBOARD }
    );
    return { ok: true, action: "status" };
  }

  if (/^\/(?:clear|off|stop|remove)channel(?:@\w+)?(?:\s|$)/i.test(textBody)) {
    const s = await loadCurrentBotSession();
    if (!s) {
      await replyText("🔐 Pehle apna VidVora Password bhej kar account link karein.");
      return { ok: true, action: "auth_required" };
    }
    const updated = {
      ...s,
      autoPostChannelId: "",
      autoPostChannelTitle: "",
      autoPostChannelUsername: "",
    };
    await db.collection("telegramBotSessions").doc(telegramUserId).set(
      {
        autoPostChannelId: "",
        autoPostChannelTitle: "",
        autoPostChannelUsername: "",
      },
      { merge: true }
    );
    setCachedBotSession(db, telegramUserId, updated);
    await replyText(
      "✅ *Auto-Post Channel band kar diya gaya hai.*\n\nAb videos ke link sirf isi bot chat me aayenge. Dobara chalu karne ke liye `/setchannel @YourChannel` bhejein.",
      { parse_mode: "Markdown" }
    );
    return { ok: true, action: "channel_cleared" };
  }

  if (/^\/setchannel(?:@\w+)?(?:\s|$)/i.test(textBody)) {
    const s = await loadCurrentBotSession();
    if (!s) {
      await replyText("🔐 Pehle apna VidVora Password bhej kar account link karein, phir `/setchannel @YourChannel` bhejein.", {
        parse_mode: "Markdown",
      });
      return { ok: true, action: "auth_required" };
    }
    const rawArg = textBody.replace(/^\/setchannel(?:@\w+)?/i, "").trim();
    const parsedTarget = parseChannelTargetInput(rawArg);
    if (!parsedTarget.valid) {
      const extraHint =
        parsedTarget.reason === "invite_link"
          ? "⚠️ Invite link (`t.me/+...`) se bot channel connect nahi kar sakta.\n\n*Private Channel* ke liye: Bot ko channel me Admin banayein aur us channel ka koi bhi text message yahan forward kar dein — main khud Channel ID bata dunga!"
          : [
              "📢 *Auto-Post Channel Set Karne Ka Tarika:*",
              "",
              "1️⃣ Pehle is bot ko apne Telegram Channel me *Admin (Post Messages)* banayein.",
              "2️⃣ *Public Channel:* `/setchannel @YourChannelUsername` bhejein.",
              "3️⃣ *Private Channel:* Us channel ka koi bhi text message yahan forward karein (ya `/setchannel -1001234567890` bhejein).",
            ].join("\n");
      await replyText(extraHint, { parse_mode: "Markdown" });
      return { ok: true, action: "setchannel_help" };
    }

    try {
      const chatInfo = await telegramApiJson(apiBase, botToken, "getChat", {
        chat_id: parsedTarget.target,
      });
      if (!chatInfo || !chatInfo.id) {
        throw new Error("Channel not found");
      }
      const canonicalId = String(chatInfo.id);
      const channelTitle = String(chatInfo.title || chatInfo.username || canonicalId);
      const channelUsername = chatInfo.username ? String(chatInfo.username) : "";

      const updatedSession = {
        ...s,
        autoPostChannelId: canonicalId,
        autoPostChannelTitle: channelTitle,
        autoPostChannelUsername: channelUsername,
      };
      await db.collection("telegramBotSessions").doc(telegramUserId).set(
        {
          autoPostChannelId: canonicalId,
          autoPostChannelTitle: channelTitle,
          autoPostChannelUsername: channelUsername,
        },
        { merge: true }
      );
      setCachedBotSession(db, telegramUserId, updatedSession);

      const displayLabel = channelUsername
        ? `*${escapeMarkdown(channelTitle)}* (@${escapeMarkdown(channelUsername)})`
        : `*${escapeMarkdown(channelTitle)}* (\`${canonicalId}\`)`;
      await replyText(
        [
          "✅ *Auto-Post Channel Connect Ho Gaya!*",
          "",
          `📢 Channel: ${displayLabel}`,
          "",
          "Ab jab bhi aap mujhe koi *Video* (ya 10–50 videos ek saath) bhejenge, main khud VidVora link + preview banakar seedha is channel par post kar dunga!",
          "",
          "_(Make sure bot is Admin with 'Post Messages' ON in this channel. Band karne ke liye `/clearchannel` bhejein.)_",
        ].join("\n"),
        { parse_mode: "Markdown" }
      );
      return { ok: true, action: "channel_set", channelId: canonicalId };
    } catch (chErr) {
      await replyText(
        [
          `❌ Channel connect nahi ho paaya (\`${escapeMarkdown(parsedTarget.target)}\`).`,
          "",
          "Check karein:",
          "1️⃣ Kya aapne is bot ko us channel me *Administrator (Post Messages)* add kiya hai?",
          "2️⃣ Public channel ka username `@YourChannel` sahi likha hai?",
          "3️⃣ Private channel hai toh us channel ka koi text message yahan forward karein taaki sahi `-100...` ID mil sake.",
        ].join("\n"),
        { parse_mode: "Markdown" }
      );
      return { ok: false, action: "channel_set_failed", error: chErr?.message || "getChat failed" };
    }
  }

  const loginCmd = parseLoginCommand(textBody);
  if (loginCmd) {
    const creator = await authenticateCreatorMember(db, loginCmd.emailOrPhone, loginCmd.passkey);
    if (!creator) {
      await replyText("❌ Invalid or paused Password. Check your VidVora Creator Password and try again.");
      return { ok: true, action: "login_failed" };
    }
    if (!creator.canUpload) {
      await replyText("⛔ Your account does not have Upload Videos permission enabled.");
      return { ok: true, action: "login_forbidden" };
    }
    const prevSession = await loadCurrentBotSession();
    const restoredCaptionPrefs = await hydrateCreatorCaptionPrefs(creator.memberId, prevSession);
    const newSessionPayload = {
      ...(prevSession?.autoPostChannelId
        ? {
            autoPostChannelId: prevSession.autoPostChannelId,
            autoPostChannelTitle: prevSession.autoPostChannelTitle || "",
            autoPostChannelUsername: prevSession.autoPostChannelUsername || "",
          }
        : {}),
      customCaptionHeader: restoredCaptionPrefs.customCaptionHeader,
      customCaptionFooter: restoredCaptionPrefs.customCaptionFooter,
      pendingInputAction: "",
      telegramUserId,
      chatId: String(chatId),
      memberId: creator.memberId,
      memberName: creator.name,
      role: creator.role || "member",
      linkedAt: new Date(),
      uploadCount: Number(prevSession?.uploadCount || 0) || 0,
    };
    await db.collection("telegramBotSessions").doc(telegramUserId).set(newSessionPayload, { merge: true });
    setCachedBotSession(db, telegramUserId, newSessionPayload);
    if (creator.memberId !== "admin") {
      setCachedMemberPerm(db, creator.memberId, {
        isActive: true,
        allowedOptions: creator.allowedOptions || null,
      });
    }
    await replyText(
      `✅ Welcome *${escapeMarkdown(creator.name)}*!\n\nAapka VidVora account link ho gaya hai. Seedha koi bhi *Video* ya *VidVora Share Link* bhejein, ya neeche diye gaye buttons se apna *Header / Footer* edit karein 👇`,
      { parse_mode: "Markdown", reply_markup: CREATOR_BOT_REPLY_KEYBOARD }
    );
    return { ok: true, action: "login_success" };
  }

  // 1.5 Interactive Header/Footer edit capture (runs BEFORE link/video parsing so creator can put links in Header/Footer!)
  const isVideoMsg = isAllowedTelegramVideoMessage(message);
  if (textBody && !textBody.startsWith("/") && !isVideoMsg) {
    const pendingSession = await loadCurrentBotSession();
    const pendingAction = String(pendingSession?.pendingInputAction || "").trim();
    if (pendingSession && (pendingAction === "set_header" || pendingAction === "set_footer")) {
      const isHeader = pendingAction === "set_header";
      const cleanVal = sanitizeCustomCaptionBlock(
        textBody,
        isHeader ? MAX_CUSTOM_HEADER_LENGTH : MAX_CUSTOM_FOOTER_LENGTH
      );
      const updated = await persistCreatorCaptionPrefs(pendingSession, {
        ...(isHeader ? { customCaptionHeader: cleanVal } : { customCaptionFooter: cleanVal }),
        pendingInputAction: "",
      });
      await replyText(
        `✅ <b>${isHeader ? "Header" : "Footer"} save ho gaya!</b>\n\n${buildCaptionSettingsHtml(updated)}`,
        {
          parse_mode: "HTML",
          disable_web_page_preview: true,
          reply_markup: buildCaptionSettingsKeyboard(),
        }
      );
      return {
        ok: true,
        action: isHeader ? "header_updated" : "footer_updated",
        ...(isHeader ? { customCaptionHeader: cleanVal } : { customCaptionFooter: cleanVal }),
      };
    }
  }

  // 2. Check upload session before handling VidVora link conversion, video, or direct URL
  const combinedText = String(message.text || message.caption || "").trim();
  const vidvoraConvertTarget = extractVidVoraShareTarget(combinedText, message, {
    strictVidVoraOnly: isVideoMsg,
  });
  const directUrlMsg = !isVideoMsg && !vidvoraConvertTarget ? extractDirectVideoUrlMessage(combinedText) : null;

  if (!vidvoraConvertTarget && !isVideoMsg && !directUrlMsg) {
    // Allow beginners to send their Master Key directly (to link or switch accounts) without /login
    if (textBody && !textBody.startsWith("/") && !/\s/.test(textBody) && textBody.length >= 4 && textBody.length <= 80) {
      const directCreator = await authenticateCreatorMember(db, null, textBody);
      if (directCreator?.ambiguous) {
        await replyText(
          "⚠️ Yeh password ek se zyada accounts me use ho raha hai. Apne Gmail ke saath login karein:\n`/login yourmail@gmail.com YOUR_PASSWORD`",
          { parse_mode: "Markdown" }
        );
        return { ok: true, action: "login_ambiguous" };
      }
      if (directCreator) {
        if (!directCreator.canUpload) {
          await replyText("⛔ Your account does not have Upload Videos permission enabled.");
          return { ok: true, action: "login_forbidden" };
        }
        const prevDirectSession = await loadCurrentBotSession();
        const restoredDirectPrefs = await hydrateCreatorCaptionPrefs(directCreator.memberId, prevDirectSession);
        const directSessionPayload = {
          ...(prevDirectSession?.autoPostChannelId
            ? {
                autoPostChannelId: prevDirectSession.autoPostChannelId,
                autoPostChannelTitle: prevDirectSession.autoPostChannelTitle || "",
                autoPostChannelUsername: prevDirectSession.autoPostChannelUsername || "",
              }
            : {}),
          customCaptionHeader: restoredDirectPrefs.customCaptionHeader,
          customCaptionFooter: restoredDirectPrefs.customCaptionFooter,
          pendingInputAction: "",
          telegramUserId,
          chatId: String(chatId),
          memberId: directCreator.memberId,
          memberName: directCreator.name,
          role: directCreator.role || "member",
          linkedAt: new Date(),
          uploadCount: Number(prevDirectSession?.uploadCount || 0) || 0,
        };
        await db.collection("telegramBotSessions").doc(telegramUserId).set(directSessionPayload, { merge: true });
        setCachedBotSession(db, telegramUserId, directSessionPayload);
        if (directCreator.memberId !== "admin") {
          setCachedMemberPerm(db, directCreator.memberId, {
            isActive: true,
            allowedOptions: directCreator.allowedOptions || null,
          });
        }
        await replyText(
          `✅ Welcome *${escapeMarkdown(directCreator.name)}*!\n\nAapka VidVora account link ho gaya hai. Seedha koi bhi *Video* ya *VidVora Share Link* bhejein, ya neeche diye gaye buttons se apna *Header / Footer* edit karein 👇`,
          { parse_mode: "Markdown", reply_markup: CREATOR_BOT_REPLY_KEYBOARD }
        );
        return { ok: true, action: "login_success" };
      }
    }

    const existingSessionObj = await loadCurrentBotSession();
    const hasExistingSession = Boolean(existingSessionObj);
    if (hasExistingSession) {
      await replyText(
        "📹 Seedha koi bhi *Video file*, direct `.mp4` link, ya *VidVora Share Link* bhejein — ya neeche diye gaye buttons se Header / Footer edit karein 👇",
        { parse_mode: "Markdown", reply_markup: CREATOR_BOT_REPLY_KEYBOARD }
      );
    } else {
      await replyText(
        "🔐 Invalid Password (ya unsupported message).\n\nApna sahi *Creator / Admin Password* seedha yahan bhej dein.",
        { parse_mode: "Markdown", reply_markup: CREATOR_BOT_REPLY_KEYBOARD }
      );
    }
    return { ok: true, action: "unsupported_message" };
  }

  let session = getCachedBotSession(db, telegramUserId);
  if (!session) {
    const sessionSnap = await db.collection("telegramBotSessions").doc(telegramUserId).get();
    if (!sessionSnap.exists) {
      await replyText("🔐 Please link your Creator account first:\n`/login YOUR_PASSWORD`", {
        parse_mode: "Markdown",
      });
      return { ok: true, action: "auth_required" };
    }
    session = sessionSnap.data() || {};
    setCachedBotSession(db, telegramUserId, session);
  }

  // Re-verify active permission (cached 15 min per creator to avoid 100s of redundant reads on batch uploads)
  if (session.memberId !== "admin") {
    let memberData = getCachedMemberPerm(db, session.memberId);
    if (!memberData) {
      const memberSnap = await db.collection("members").doc(session.memberId).get();
      memberData = memberSnap.exists ? memberSnap.data() : { isActive: false };
      setCachedMemberPerm(db, session.memberId, memberData);
    }
    const allowed = Array.isArray(memberData?.allowedOptions) ? memberData.allowedOptions : null;
    if (!memberData || memberData.isActive !== true || (allowed && !allowed.includes("UPLOAD_VIDEOS"))) {
      await replyText("⛔ Your Creator upload access is currently paused or disabled.");
      return { ok: true, action: "member_disabled" };
    }
  }

  if (directUrlMsg && isUnsupportedThirdPartyHostUrl(directUrlMsg.url)) {
    await replyText(
      [
        "⚠️ *Third-Party Web Link Supported Nahi Hai*",
        "",
        "`TeraBox`, `Tenbox`, `Flezen`, `Vidbunker` jaise external links login/Cloudflare protected HTML web pages hote hain — inhe direct fetch ya convert nahi kiya ja sakta.",
        "",
        "*Tezi se bulk upload/convert karne ke sahi tarike:*",
        "1️⃣ *Batch Forward Videos (Sabse Fast):* Telegram channel se 10–50 *video files* ek saath select karke yahan forward karein — bina download/upload kiye sabke VidVora links ban jayenge!",
        "2️⃣ *VidVora Link Convert:* Kisi VidVora share post ya link ko forward/paste karein.",
        "3️⃣ *Direct Stream URL:* Seedha `.mp4` / `.m3u8` / CDN (Bunny, R2, S3, CloudFront) link bhejein.",
      ].join("\n"),
      { parse_mode: "Markdown" }
    );
    return { ok: false, reason: "unsupported_third_party_host" };
  }

  const runMediaOrConvertTask = async () => {
    const incomingMediaObj = getTelegramVideoMediaObject(message);
    const uploadFileSizeBytes = Number(incomingMediaObj?.file_size || 0) || 0;
    const statusMsg = await replyText(
      vidvoraConvertTarget
        ? "🔄 Converting VidVora link to your Creator link..."
        : uploadFileSizeBytes > BOT_API_MAX_BYTES && apiId && apiHash
          ? `⏳ Uploading ${Math.max(21, Math.round(uploadFileSizeBytes / (1024 * 1024)))} MB video via 2 GB MTProto mode...`
          : "⏳ Uploading to VidVora & generating 5-sec preview clip..."
    );
    const tmpDir = await fs.promises.mkdtemp(path.join(os.tmpdir(), "vidvora-bot-"));

    try {
    const videoId = `vid_${crypto.randomBytes(6).toString("hex")}`;
    const linkId = `link_${crypto.randomBytes(6).toString("hex")}`;
    const rawToken = generateAccessToken();
    const tokenHash = hashToken(rawToken);
    const clipPath = path.join(tmpDir, `${videoId}_preview_5s.mp4`);
    const thumbPath = path.join(tmpDir, `${videoId}_thumb.jpg`);

    let title = "Creator Video";
    let description = "Uploaded via VidVora Telegram Bot";
    let finalStoragePath = "";
    let finalThumbPath = null;
    let sourceThumbFallbackUri = null;
    let forwardedPreviewVideoFileId = null;
    let forwardedPreviewPhotoFileId = null;
    let fallbackTelegramThumbFileId = null;
    let tgFileIdForDoc = null;
    let tgChatIdForDoc = null;
    let tgMessageIdForDoc = null;
    let tgFileSizeForDoc = 0;
    let tgMimeTypeForDoc = null;
    let tgPreviewClipFileIdForDoc = null;
    let tgThumbFileIdForDoc = null;
    let sourceVideoIdForDoc = null;
    const cleanPublicBase =
      functionBaseUrl && /^https?:\/\//i.test(functionBaseUrl) && !functionBaseUrl.includes("localhost")
        ? functionBaseUrl.replace(/\/+$/, "")
        : "";
    const bucket = storage && typeof storage.bucket === "function" ? storage.bucket() : null;

    if (vidvoraConvertTarget) {
      const source = await resolveVidVoraSourceVideo(db, vidvoraConvertTarget.code);
      if (!source.found || !source.isActive) {
        await telegramApiJson(apiBase, botToken, "editMessageText", {
          chat_id: chatId,
          message_id: statusMsg.message_id,
          text: "❌ Yeh VidVora video link invalid ya deactivated hai, isko convert nahi kiya ja sakta.",
        });
        return { ok: false, reason: "source_link_unavailable" };
      }

      title = vidvoraConvertTarget.customTitle || source.video.title || "Creator Video";
      description = source.video.description || "Converted via VidVora Link Converter Bot";
      finalStoragePath = source.video.storagePath;
      tgFileIdForDoc = source.video.tgFileId ? String(source.video.tgFileId) : null;
      tgChatIdForDoc = source.video.tgChatId ? String(source.video.tgChatId) : null;
      tgMessageIdForDoc = source.video.tgMessageId ? Number(source.video.tgMessageId) : null;
      tgFileSizeForDoc = Number(source.video.tgFileSize || 0) || 0;
      tgMimeTypeForDoc = source.video.tgMimeType ? String(source.video.tgMimeType) : null;
      tgPreviewClipFileIdForDoc = source.video.tgPreviewClipFileId
        ? String(source.video.tgPreviewClipFileId)
        : null;
      tgThumbFileIdForDoc = source.video.tgThumbFileId ? String(source.video.tgThumbFileId) : null;
      sourceVideoIdForDoc = source.video.sourceVideoId
        ? String(source.video.sourceVideoId)
        : source.videoId || null;

      // Zero base64 duplication in Neon: never copy 70-350KB data:image blobs onto converted creator docs!
      // Instead store a lightweight ~75-byte /shareThumb?videoId=<sourceId> reference (0 KB duplicate storage).
      const srcThumb = source.video.thumbnailPath ? String(source.video.thumbnailPath).trim() : "";
      if (srcThumb.startsWith("data:image/")) {
        sourceThumbFallbackUri = srcThumb;
        finalThumbPath =
          cleanPublicBase && sourceVideoIdForDoc
            ? `${cleanPublicBase}/shareThumb?videoId=${encodeURIComponent(sourceVideoIdForDoc)}`
            : null;
      } else if (srcThumb) {
        finalThumbPath = srcThumb;
      } else if (cleanPublicBase && tgThumbFileIdForDoc) {
        finalThumbPath = `${cleanPublicBase}/shareThumb?videoId=${encodeURIComponent(sourceVideoIdForDoc || videoId)}`;
      }

      // Instant 0-RAM preview reuse: if the user forwarded a VidVora 5-sec preview clip or photo post,
      // reuse its Telegram file_id directly. Even if they pasted ONLY a text link, reuse the saved
      // 5-second preview clip file_id from the original video so the 5-sec clip ALWAYS goes out!
      if (message.video && message.video.file_id) {
        forwardedPreviewVideoFileId = String(message.video.file_id);
        tgPreviewClipFileIdForDoc = forwardedPreviewVideoFileId;
        const videoThumb = message.video.thumbnail || message.video.thumb;
        if (videoThumb && videoThumb.file_id) {
          fallbackTelegramThumbFileId = String(videoThumb.file_id);
          if (!tgThumbFileIdForDoc) tgThumbFileIdForDoc = fallbackTelegramThumbFileId;
        }
      } else if (Array.isArray(message.photo) && message.photo.length > 0) {
        const largestPhoto = message.photo[message.photo.length - 1];
        if (largestPhoto && largestPhoto.file_id) {
          forwardedPreviewPhotoFileId = String(largestPhoto.file_id);
          if (!tgThumbFileIdForDoc) tgThumbFileIdForDoc = forwardedPreviewPhotoFileId;
        }
      }

      if (!forwardedPreviewVideoFileId && tgPreviewClipFileIdForDoc) {
        forwardedPreviewVideoFileId = tgPreviewClipFileIdForDoc;
      }
      if (!fallbackTelegramThumbFileId && tgThumbFileIdForDoc) {
        fallbackTelegramThumbFileId = tgThumbFileIdForDoc;
      }
      if (!finalThumbPath && cleanPublicBase && tgThumbFileIdForDoc) {
        finalThumbPath = `${cleanPublicBase}/shareThumb?videoId=${encodeURIComponent(sourceVideoIdForDoc || videoId)}`;
      }
    } else if (isVideoMsg) {
      const mediaObj = incomingMediaObj;
      const incomingThumbObj = mediaObj.thumbnail || mediaObj.thumb;
      if (incomingThumbObj && incomingThumbObj.file_id) {
        fallbackTelegramThumbFileId = String(incomingThumbObj.file_id);
        tgThumbFileIdForDoc = fallbackTelegramThumbFileId;
      }
      const fallbackName = mediaObj.file_name || "Creator Video";
      const parsedMeta = parseTitleAndDescription(message.caption, fallbackName);
      title = parsedMeta.title;
      description = parsedMeta.description;
      const reportedFileSize = toSafeByteLength(mediaObj.file_size);
      const mimeType = inferVideoMimeType(mediaObj.file_name, mediaObj.mime_type);
      const hasMtproto = Boolean(apiId && apiHash);
      const localVideoPath = path.join(tmpDir, `${videoId}.mp4`);

      const fetchTelegramThumb = async () => {
        const thumbObj = mediaObj.thumbnail || mediaObj.thumb;
        if (!thumbObj || !thumbObj.file_id) return false;
        try {
          const thumbInfo = await telegramApiJson(apiBase, botToken, "getFile", { file_id: thumbObj.file_id });
          if (!thumbInfo?.file_path) return false;
          const thumbUrl = String(thumbInfo.file_path).startsWith("http")
            ? thumbInfo.file_path
            : `${apiBase}/file/bot${botToken}/${thumbInfo.file_path}`;
          await downloadFileToPath(thumbUrl, thumbPath);
          return fs.existsSync(thumbPath) && fs.statSync(thumbPath).size > 0;
        } catch (_) {
          return false;
        }
      };

      const sendFileTooBigGuide = async () => {
        await telegramApiJson(apiBase, botToken, "editMessageText", {
          chat_id: chatId,
          message_id: statusMsg.message_id,
          text: [
            "⚠️ *20 MB Se Badi File (Telegram Bot API Limit)*",
            "",
            "Telegram Bot API bina setup ke sirf *20 MB* tak direct download allow karta hai. Ise *2 GB tak* unlock karne ke 2 aasan tarike hain:",
            "",
            "1️⃣ *2 GB Direct Telegram Upload On Karein (Recommended):*",
            "• https://my.telegram.org par login karke *API Development Tools* se apna free `API ID` aur `API Hash` lein.",
            "• Render Dashboard (`vidvora-share-bot` → *Environment*) me `TELEGRAM_API_ID` aur `TELEGRAM_API_HASH` add karke redeploy karein.",
            "• Iske baad aap *2 GB tak ke videos* seedha bot par upload kar payenge!",
            "",
            "2️⃣ *Instant Direct Link Mode (Unlimited Size):*",
            "• Koi bhi direct `https://...mp4` stream link + Title bhej dein.",
          ].join("\n"),
          parse_mode: "Markdown",
        });
      };

      let downloadedViaBotApi = false;
      let mtprotoStreamOnly = false;
      let hasDownloadedLocalVideo = false;
      let clipExists = false;
      let thumbExists = false;

      if (reportedFileSize <= BOT_API_MAX_BYTES) {
        try {
          const fileInfo = await telegramApiJson(apiBase, botToken, "getFile", { file_id: mediaObj.file_id });
          const filePath = fileInfo.file_path;
          const downloadUrl = String(filePath || "").startsWith("http")
            ? filePath
            : `${apiBase}/file/bot${botToken}/${filePath}`;
          await downloadFileToPath(downloadUrl, localVideoPath);
          downloadedViaBotApi = true;
          hasDownloadedLocalVideo = true;
        } catch (err) {
          if (!String(err.message || "").toLowerCase().includes("file is too big")) {
            throw err;
          }
        }
      }

      if (!downloadedViaBotApi) {
        if (!hasMtproto) {
          await sendFileTooBigGuide();
          return { ok: false, reason: "file_too_big" };
        }

        thumbExists = await fetchTelegramThumb();

        if (reportedFileSize > 0 && reportedFileSize <= WEBHOOK_PREVIEW_MAX_BYTES) {
          try {
            await downloadTelegramFileViaMtproto({
              apiId,
              apiHash,
              botToken,
              tgFileId: mediaObj.file_id,
              tgChatId: chatId,
              tgMessageId: message.message_id,
              tgFileSize: reportedFileSize,
              destPath: localVideoPath,
              db,
              telegramLibOverride,
            });
            hasDownloadedLocalVideo = fs.existsSync(localVideoPath) && fs.statSync(localVideoPath).size > 0;
            if (!hasDownloadedLocalVideo) {
              mtprotoStreamOnly = true;
            }
          } catch (previewDlErr) {
            console.warn("20-60MB MTProto preview download skipped, switching to instant stream mode:", previewDlErr?.message || previewDlErr);
            hasDownloadedLocalVideo = false;
            mtprotoStreamOnly = true;
            await fs.promises.unlink(localVideoPath).catch(() => {});
          }
        } else {
          // Giant video (>60 MB up to 2 GB): publish immediately using Telegram thumbnail and stream bytes on-demand via MTProto Range requests
          mtprotoStreamOnly = true;
        }
      }

      if (hasDownloadedLocalVideo && !mtprotoStreamOnly) {
        try {
          const generated = await createFiveSecondPreviewAndThumbnail({
            inputPathOrUrl: localVideoPath,
            outputClipPath: clipPath,
            outputThumbPath: thumbPath,
            durationSeconds: mediaObj?.duration || 0,
            previewSeconds: DEFAULT_PREVIEW_SECONDS,
          });
          clipExists = generated.clipExists;
          thumbExists = generated.thumbExists || thumbExists;
        } catch (previewGenErr) {
          console.warn("5-sec preview generation skipped for video:", previewGenErr?.message || previewGenErr);
          if (reportedFileSize > BOT_API_MAX_BYTES) {
            mtprotoStreamOnly = true;
          }
        }
      }

      // Always fall back to Telegram's built-in video thumbnail if ffmpeg didn't produce thumbPath
      if (!thumbExists) {
        thumbExists = await fetchTelegramThumb();
      }

      const baseHost = functionBaseUrl.replace(/\/+$/, "");
      const videoSig = signStreamTarget(videoId, "video", playbackSecret);
      finalStoragePath = `${baseHost}/streamPublicVideo?videoId=${encodeURIComponent(videoId)}&sig=${encodeURIComponent(videoSig)}`;

      let storageUploadSucceeded = false;
      try {
        if (bucket && typeof bucket.upload === "function") {
          if (hasDownloadedLocalVideo && !mtprotoStreamOnly) {
            const videoDest = `videos/${videoId}/video.mp4`;
            await bucket.upload(localVideoPath, {
              destination: videoDest,
              metadata: { contentType: mimeType },
            });
            storageUploadSucceeded = true;
          }

          if (thumbExists) {
            await bucket.upload(thumbPath, {
              destination: `videos/${videoId}/thumbnail.jpg`,
              metadata: { contentType: "image/jpeg" },
            });
            const thumbSig = signStreamTarget(videoId, "thumb", playbackSecret);
            finalThumbPath = `${baseHost}/streamPublicVideo?videoId=${encodeURIComponent(videoId)}&kind=thumb&sig=${encodeURIComponent(thumbSig)}`;
          }
          if (clipExists) {
            await bucket.upload(clipPath, {
              destination: `videos/${videoId}/preview_5s.mp4`,
              metadata: { contentType: "video/mp4" },
            });
          }
        }
      } catch (storageErr) {
        console.warn("Firebase Storage unavailable (Spark/unprovisioned bucket), using Telegram stream fallback:", storageErr.message);
      }

      if (!finalThumbPath) {
        if (cleanPublicBase && tgThumbFileIdForDoc) {
          // Keep Neon lightweight (~75 bytes instead of 70-350KB base64): /shareThumb serves from free Telegram cloud
          finalThumbPath = `${cleanPublicBase}/shareThumb?videoId=${encodeURIComponent(videoId)}`;
        } else if (thumbExists) {
          finalThumbPath = await encodeCrispThumbDataUri(thumbPath);
        }
      }
      if (!storageUploadSucceeded && hasDownloadedLocalVideo && !mtprotoStreamOnly && heavyMediaPendingCount <= 2) {
        try {
          const localStat = await fs.promises.stat(localVideoPath).catch(() => null);
          if (localStat && localStat.size > 0 && localStat.size <= BOT_API_MAX_BYTES) {
            await fs.promises.mkdir(STREAM_CACHE_DIR, { recursive: true });
            const cachedTarget = path.join(STREAM_CACHE_DIR, `${videoId}.mp4`);
            let remuxed = false;
            const fastStartTmp = path.join(tmpDir, `${videoId}_faststart.mp4`);
            try {
              const ffmpegBin = getFfmpegBinary();
              await execFileAsync(
                ffmpegBin,
                ["-y", "-threads", "1", "-i", localVideoPath, "-c", "copy", "-movflags", "+faststart", fastStartTmp],
                { timeout: 15_000, maxBuffer: 4 * 1024 * 1024 }
              );
              if (fs.existsSync(fastStartTmp) && fs.statSync(fastStartTmp).size > 0) {
                await fs.promises.copyFile(fastStartTmp, cachedTarget);
                remuxed = true;
              }
            } catch (_) {
              // Fall back to direct file copy when stream copy isn't applicable
            } finally {
              await fs.promises.unlink(fastStartTmp).catch(() => {});
            }
            if (!remuxed) {
              await fs.promises.copyFile(localVideoPath, cachedTarget);
            }
            await pruneStreamCacheIfNeeded();
          }
        } catch (_) {
          // Cache pre-warm optional
        }
      }
      await fs.promises.unlink(localVideoPath).catch(() => {});

      if (!storageUploadSucceeded) {
        tgFileIdForDoc = String(mediaObj.file_id || "");
        tgChatIdForDoc = String(chatId);
        tgMessageIdForDoc = Number(message.message_id);
        tgFileSizeForDoc = reportedFileSize;
        tgMimeTypeForDoc = mimeType;
      }
    } else {
      // Direct URL flow (supports unlimited file sizes hosted on Bunny/Cloudflare/S3/CDN)
      const parsedMeta = parseTitleAndDescription(directUrlMsg.caption, "Stream Video");
      title = parsedMeta.title;
      description = parsedMeta.description;
      finalStoragePath = directUrlMsg.url;

      let clipExists = false;
      let thumbExists = false;
      try {
        const generated = await createFiveSecondPreviewAndThumbnail({
          inputPathOrUrl: finalStoragePath,
          outputClipPath: clipPath,
          outputThumbPath: thumbPath,
          durationSeconds: 0,
          previewSeconds: DEFAULT_PREVIEW_SECONDS,
        });
        clipExists = Boolean(generated?.clipExists);
        thumbExists = Boolean(generated?.thumbExists);
      } catch (directPreviewErr) {
        console.warn("Direct URL preview generation skipped, publishing link directly:", directPreviewErr?.message || directPreviewErr);
      }

      let storageThumbUploaded = false;
      try {
        if (bucket && typeof bucket.upload === "function") {
          if (thumbExists) {
            await bucket.upload(thumbPath, {
              destination: `videos/${videoId}/thumbnail.jpg`,
              metadata: { contentType: "image/jpeg" },
            });
            const baseHost = functionBaseUrl.replace(/\/+$/, "");
            const thumbSig = signStreamTarget(videoId, "thumb", playbackSecret);
            finalThumbPath = `${baseHost}/streamPublicVideo?videoId=${encodeURIComponent(videoId)}&kind=thumb&sig=${encodeURIComponent(thumbSig)}`;
            storageThumbUploaded = true;
          }
          if (clipExists) {
            await bucket.upload(clipPath, {
              destination: `videos/${videoId}/preview_5s.mp4`,
              metadata: { contentType: "video/mp4" },
            });
          }
        }
      } catch (storageErr) {
        console.warn("Firebase Storage thumb upload skipped:", storageErr.message);
      }

      if (!storageThumbUploaded && thumbExists) {
        finalThumbPath = await encodeCrispThumbDataUri(thumbPath);
      }
    }

    const now = new Date();
    const videoDocPayload = {
      videoId,
      title,
      description,
      thumbnailPath: finalThumbPath,
      storagePath: finalStoragePath,
      createdAt: now,
      isActive: true,
      totalViews: 0,
      uploadedBy: session.memberId || "admin",
      uploaderName: session.memberName || "Creator",
    };
    if (tgFileIdForDoc) {
      videoDocPayload.tgFileId = tgFileIdForDoc;
    }
    if (tgChatIdForDoc) {
      videoDocPayload.tgChatId = tgChatIdForDoc;
    }
    if (tgMessageIdForDoc) {
      videoDocPayload.tgMessageId = tgMessageIdForDoc;
    }
    if (tgFileSizeForDoc > 0) {
      videoDocPayload.tgFileSize = tgFileSizeForDoc;
    }
    if (tgMimeTypeForDoc) {
      videoDocPayload.tgMimeType = tgMimeTypeForDoc;
    }
    if (tgPreviewClipFileIdForDoc) {
      videoDocPayload.tgPreviewClipFileId = tgPreviewClipFileIdForDoc;
    }
    if (tgThumbFileIdForDoc) {
      videoDocPayload.tgThumbFileId = tgThumbFileIdForDoc;
    }
    if (sourceVideoIdForDoc) {
      videoDocPayload.sourceVideoId = sourceVideoIdForDoc;
    }
    await db.collection("videos").doc(videoId).set(videoDocPayload);

    await db.collection("accessLinks").doc(linkId).set({
      linkId,
      videoId,
      tokenHash,
      isActive: true,
      createdAt: now,
      expiresAt: null,
    });

    const nextUploadCount = (Number(session.uploadCount) || 0) + 1;
    session.uploadCount = nextUploadCount;
    setCachedBotSession(db, telegramUserId, session);
    await db.collection("telegramBotSessions").doc(telegramUserId).set(
      { uploadCount: nextUploadCount },
      { merge: true }
    );

    // Pre-warm stream & link-converter caches so immediate plays/conversions need 0 Firestore reads
    if (videoDocCache.size >= MAX_VIDEO_DOC_CACHE) {
      const oldestKey = videoDocCache.keys().next().value;
      if (oldestKey) videoDocCache.delete(oldestKey);
    }
    videoDocCache.set(videoId, { data: compactVideoDocForStreamCache(videoDocPayload), cachedAt: Date.now() });
    cacheResolvedSourceVideo(db, rawToken, {
      found: true,
      isActive: true,
      videoId,
      video: videoDocPayload,
    });

    const resolvedShareBase =
      shareLinkBaseUrl ||
      (functionBaseUrl && /^https:\/\//i.test(functionBaseUrl) && !functionBaseUrl.includes("localhost")
        ? `${functionBaseUrl.replace(/\/+$/, "")}/share?link=`
        : SHARE_LINK_BASE_URL);

    const watchUrl = `${resolvedShareBase}${rawToken}`;
    const caption = buildShareMessageCaption({
      title,
      uploaderName: session.memberName || "Creator",
      rawToken,
      previewSeconds: DEFAULT_PREVIEW_SECONDS,
      shareLinkBaseUrl: resolvedShareBase,
      customHeader: session.customCaptionHeader || "",
      customFooter: session.customCaptionFooter || "",
    });
    const watchInlineMarkup = {
      inline_keyboard: [[{ text: "▶️ Watch Full Video on VidVora", url: watchUrl }]],
    };

    const plainFallbackText = buildSharePlainFallbackText({
      rawToken,
      shareLinkBaseUrl: resolvedShareBase,
      customHeader: session.customCaptionHeader || "",
      customFooter: session.customCaptionFooter || "",
    });

    const localThumbReady = fs.existsSync(thumbPath) && fs.statSync(thumbPath).size > 0;

    const ensureDownloadedFallbackThumb = async () => {
      if (fs.existsSync(thumbPath) && fs.statSync(thumbPath).size > 0) {
        return thumbPath;
      }
      if (!fallbackTelegramThumbFileId) return null;
      try {
        const thumbInfo = await telegramApiJson(apiBase, botToken, "getFile", {
          file_id: fallbackTelegramThumbFileId,
        });
        if (!thumbInfo?.file_path) return null;
        const thumbUrl = String(thumbInfo.file_path).startsWith("http")
          ? thumbInfo.file_path
          : `${apiBase}/file/bot${botToken}/${thumbInfo.file_path}`;
        await downloadFileToPath(thumbUrl, thumbPath);
        if (fs.existsSync(thumbPath) && fs.statSync(thumbPath).size > 0) {
          return thumbPath;
        }
      } catch (dlThumbErr) {
        console.warn("Fallback Telegram thumbnail download failed:", dlThumbErr?.message || dlThumbErr);
      }
      return null;
    };

    const sendFallbackLinkText = async (extraNotice = "") => {
      const htmlText = extraNotice ? `${caption}\n\n${extraNotice}` : caption;
      const plainText = extraNotice ? `${plainFallbackText}\n\n${extraNotice}` : plainFallbackText;
      try {
        await telegramApiJson(apiBase, botToken, "editMessageText", {
          chat_id: chatId,
          message_id: statusMsg.message_id,
          text: htmlText,
          parse_mode: "HTML",
          disable_web_page_preview: true,
          link_preview_options: { is_disabled: true },
          reply_markup: watchInlineMarkup,
        });
      } catch (_) {
        await telegramApiJson(apiBase, botToken, "editMessageText", {
          chat_id: chatId,
          message_id: statusMsg.message_id,
          text: plainText,
          disable_web_page_preview: true,
          link_preview_options: { is_disabled: true },
          reply_markup: watchInlineMarkup,
        });
      }
    };

    const extractTelegramMediaIds = (sentMsg) => {
      const sentClipFileId = sentMsg?.video?.file_id ? String(sentMsg.video.file_id) : null;
      let sentThumbFileId = null;
      if (Array.isArray(sentMsg?.photo) && sentMsg.photo.length > 0) {
        const largest = sentMsg.photo[sentMsg.photo.length - 1];
        if (largest && largest.file_id) sentThumbFileId = String(largest.file_id);
      } else if (sentMsg?.video?.thumbnail?.file_id) {
        sentThumbFileId = String(sentMsg.video.thumbnail.file_id);
      } else if (sentMsg?.video?.thumb?.file_id) {
        sentThumbFileId = String(sentMsg.video.thumb.file_id);
      }
      return { sentClipFileId, sentThumbFileId };
    };

    const publishPostToTargetChat = async (targetChatId, replyToMsgId) => {
      if (forwardedPreviewVideoFileId) {
        try {
          const sent = await sendTelegramVideoByFileId(apiBase, botToken, {
            chatId: targetChatId,
            fileId: forwardedPreviewVideoFileId,
            caption,
            watchUrl,
            replyToMessageId: replyToMsgId,
          });
          return { posted: true, mode: "video_file_id", ...extractTelegramMediaIds(sent) };
        } catch (vidErr) {
          console.warn("sendTelegramVideoByFileId fallback to photo:", vidErr?.message || vidErr);
        }
      } else if (fs.existsSync(clipPath) && fs.statSync(clipPath).size > 0) {
        try {
          const sent = await sendTelegramVideoClip(apiBase, botToken, {
            chatId: targetChatId,
            clipPath,
            caption,
            watchUrl,
            replyToMessageId: replyToMsgId,
          });
          return { posted: true, mode: "video_clip", ...extractTelegramMediaIds(sent) };
        } catch (clipErr) {
          console.warn("sendTelegramVideoClip fallback to photo:", clipErr?.message || clipErr);
        }
      }

      // Build ordered, valid photo candidates (NEVER pass raw video.thumbnail.file_id directly to sendPhoto!)
      const photoCandidates = [];
      if (forwardedPreviewPhotoFileId) photoCandidates.push(forwardedPreviewPhotoFileId);
      if (localThumbReady) photoCandidates.push(thumbPath);
      if (sourceThumbFallbackUri) photoCandidates.push(sourceThumbFallbackUri);
      if (finalThumbPath) photoCandidates.push(finalThumbPath);

      for (const candidate of photoCandidates) {
        try {
          const sent = await sendTelegramPhoto(apiBase, botToken, {
            chatId: targetChatId,
            imagePathOrUri: candidate,
            caption,
            watchUrl,
            replyToMessageId: replyToMsgId,
          });
          return { posted: true, mode: "photo", ...extractTelegramMediaIds(sent) };
        } catch (photoErr) {
          console.warn("sendTelegramPhoto candidate failed, trying next:", photoErr?.message || photoErr);
        }
      }

      // Last-chance photo fallback: download video's Telegram thumbnail bytes via getFile & upload as JPEG
      if (fallbackTelegramThumbFileId) {
        const downloadedThumb = await ensureDownloadedFallbackThumb();
        if (downloadedThumb) {
          try {
            const sent = await sendTelegramPhoto(apiBase, botToken, {
              chatId: targetChatId,
              imagePathOrUri: downloadedThumb,
              caption,
              watchUrl,
              replyToMessageId: replyToMsgId,
            });
            return { posted: true, mode: "photo_tg_thumb", ...extractTelegramMediaIds(sent) };
          } catch (dlPhotoErr) {
            console.warn("Downloaded Telegram thumbnail sendPhoto failed:", dlPhotoErr?.message || dlPhotoErr);
          }
        }
      }

      return { posted: false, mode: "text_fallback", sentClipFileId: null, sentThumbFileId: null };
    };

    const persistTelegramPreviewMetadata = async (postRes) => {
      if (!postRes || !postRes.posted) return;
      const patch = {};
      if (postRes.sentClipFileId && postRes.sentClipFileId !== videoDocPayload.tgPreviewClipFileId) {
        patch.tgPreviewClipFileId = postRes.sentClipFileId;
      }
      if (postRes.sentThumbFileId && postRes.sentThumbFileId !== videoDocPayload.tgThumbFileId) {
        patch.tgThumbFileId = postRes.sentThumbFileId;
        // If Neon previously fell back to a heavy base64 data URI, replace it with the lightweight /shareThumb URL now
        if (
          cleanPublicBase &&
          (!videoDocPayload.thumbnailPath || String(videoDocPayload.thumbnailPath).startsWith("data:image/"))
        ) {
          patch.thumbnailPath = `${cleanPublicBase}/shareThumb?videoId=${encodeURIComponent(videoId)}`;
        }
      }
      if (Object.keys(patch).length === 0) return;
      Object.assign(videoDocPayload, patch);
      videoDocCache.set(videoId, { data: compactVideoDocForStreamCache(videoDocPayload), cachedAt: Date.now() });
      cacheResolvedSourceVideo(db, rawToken, {
        found: true,
        isActive: true,
        videoId,
        video: videoDocPayload,
      });
      try {
        await db.collection("videos").doc(videoId).set(patch, { merge: true });
      } catch (metaErr) {
        console.warn("Non-fatal preview metadata persist warning:", metaErr?.message || metaErr);
      }
    };

    const autoPostChannelId = String(session.autoPostChannelId || "").trim();
    const autoPostChannelDisplay = session.autoPostChannelUsername
      ? `${session.autoPostChannelTitle || autoPostChannelId} (@${session.autoPostChannelUsername})`
      : session.autoPostChannelTitle || autoPostChannelId;

    if (autoPostChannelId) {
      let channelPosted = false;
      let channelErrorMsg = "";
      try {
        const chRes = await publishPostToTargetChat(autoPostChannelId, null);
        if (chRes.posted) {
          await persistTelegramPreviewMetadata(chRes);
          channelPosted = true;
        } else {
          await telegramApiJson(apiBase, botToken, "sendMessage", {
            chat_id: autoPostChannelId,
            text: caption,
            parse_mode: "HTML",
            disable_web_page_preview: true,
            link_preview_options: { is_disabled: true },
            reply_markup: watchInlineMarkup,
          });
          channelPosted = true;
        }
      } catch (chPostErr) {
        channelErrorMsg = String(chPostErr?.message || "Channel permission error");
        console.warn(`Auto-post to channel ${autoPostChannelId} failed:`, channelErrorMsg);
      }

      if (channelPosted) {
        const confirmHtml = [
          "✅ <b>Channel par post ho gaya!</b>",
          `📢 <b>Channel:</b> ${escapeHtml(autoPostChannelDisplay)}`,
          `🔗 ${escapeHtml(watchUrl)}`,
        ].join("\n");
        await telegramApiJson(apiBase, botToken, "editMessageText", {
          chat_id: chatId,
          message_id: statusMsg.message_id,
          text: confirmHtml,
          parse_mode: "HTML",
          disable_web_page_preview: true,
          link_preview_options: { is_disabled: true },
          reply_markup: watchInlineMarkup,
        }).catch(() => {});
        return { ok: true, videoId, linkId, rawToken, postedToChannel: autoPostChannelId };
      }

      // Channel post failed (e.g. bot removed as Admin) — still deliver link to user DM with warning
      const dmRes = await publishPostToTargetChat(chatId, message.message_id);
      if (dmRes.posted) {
        await persistTelegramPreviewMetadata(dmRes);
        await telegramApiJson(apiBase, botToken, "editMessageText", {
          chat_id: chatId,
          message_id: statusMsg.message_id,
          text: `⚠️ Channel (${autoPostChannelDisplay}) par post nahi ho paaya. Check karein ki bot us channel me Admin (Post Messages) hai ya nahi. Link niche bhej diya hai.`,
        }).catch(() => {});
      } else {
        await sendFallbackLinkText(
          `⚠️ <i>Channel par auto-post fail hua (${escapeHtml(channelErrorMsg)}). Bot ko channel me Admin banayein.</i>`
        );
      }
      return { ok: true, videoId, linkId, rawToken, channelPostError: channelErrorMsg };
    }

    const dmRes = await publishPostToTargetChat(chatId, message.message_id);
    if (dmRes.posted) {
      await persistTelegramPreviewMetadata(dmRes);
      await telegramApiJson(apiBase, botToken, "deleteMessage", {
        chat_id: chatId,
        message_id: statusMsg.message_id,
      }).catch(() => {});
    } else {
      await sendFallbackLinkText();
    }

    return { ok: true, videoId, linkId, rawToken };
  } catch (err) {
    console.error("Telegram upload error:", err);
    const errText = isFirestoreQuotaError(err)
      ? "⚠️ Aaj ka Firebase Firestore Free Daily Quota (50,000 reads/day) poora ho gaya hai.\n\nYeh roz dopahar ~1:30 PM IST par automatically reset hota hai."
      : `❌ Upload failed: ${err.message || "Unknown error"}`;
    await telegramApiJson(apiBase, botToken, "editMessageText", {
      chat_id: chatId,
      message_id: statusMsg.message_id,
      text: errText,
    }).catch(() => {});
    return { ok: false, error: err.message };
  } finally {
    await fs.promises.rm(tmpDir, { recursive: true, force: true }).catch(() => {});
  }
  };

  return (!vidvoraConvertTarget && (isVideoMsg || directUrlMsg))
    ? withHeavyMediaSlot(runMediaOrConvertTask)
    : runMediaOrConvertTask();
}

const STREAM_CACHE_DIR = path.join(os.tmpdir(), "vidvora-stream-cache");
const MAX_STREAM_CACHE_FILES = 3;
const MAX_STREAM_CACHE_BYTES = 35 * 1024 * 1024;
const VIDEO_DOC_CACHE_TTL_MS = 10 * 60 * 1000;
const MAX_VIDEO_DOC_CACHE = 200;
const videoDocCache = new Map();
const inflightTelegramDownloads = new Map();
const TELEGRAM_FILE_URL_CACHE_TTL_MS = 40 * 60 * 1000;
const MAX_TELEGRAM_FILE_URL_CACHE = 200;
const telegramFileUrlCache = new Map();

function compactVideoDocForStreamCache(raw) {
  if (!raw || typeof raw !== "object") return {};
  // Strip heavy base64 thumbnailPath & large text blobs not needed for video streaming
  const { thumbnailPath: _dropThumb, description: _dropDesc, ...rest } = raw;
  return rest;
}

function clearTelegramRuntimeCaches() {
  videoDocCache.clear();
  telegramFileUrlCache.clear();
  mtprotoFloodWaitUntilMs = 0;
}

function parseHttpByteRange(rangeHeader, fileSize) {
  if (!rangeHeader || !Number.isFinite(fileSize) || fileSize <= 0) return null;
  const match = String(rangeHeader).trim().match(/^bytes=(\d*)-(\d*)$/i);
  if (!match) return { invalid: true };

  const startStr = match[1];
  const endStr = match[2];
  let start = 0;
  let end = fileSize - 1;

  if (startStr === "" && endStr === "") {
    return { invalid: true };
  } else if (startStr === "") {
    const suffixLen = parseInt(endStr, 10);
    if (Number.isNaN(suffixLen) || suffixLen <= 0) return { invalid: true };
    start = Math.max(0, fileSize - suffixLen);
    end = fileSize - 1;
  } else {
    start = parseInt(startStr, 10);
    end = endStr ? Math.min(parseInt(endStr, 10), fileSize - 1) : fileSize - 1;
  }

  if (Number.isNaN(start) || Number.isNaN(end) || start < 0 || start >= fileSize || end < start) {
    return { invalid: true };
  }
  return { start, end, chunkSize: end - start + 1 };
}

async function pruneStreamCacheIfNeeded() {
  try {
    const entries = await fs.promises.readdir(STREAM_CACHE_DIR, { withFileTypes: true });
    const files = entries.filter((e) => e.isFile() && !e.name.includes(".part."));
    if (files.length === 0) return;
    const stats = await Promise.all(
      files.map(async (f) => {
        const fullPath = path.join(STREAM_CACHE_DIR, f.name);
        const st = await fs.promises.stat(fullPath).catch(() => null);
        return st ? { fullPath, mtimeMs: st.mtimeMs, size: st.size || 0 } : null;
      })
    );
    const sorted = stats.filter(Boolean).sort((a, b) => a.mtimeMs - b.mtimeMs);
    let totalBytes = sorted.reduce((sum, item) => sum + item.size, 0);
    let remainingCount = sorted.length;
    for (let i = 0; i < sorted.length; i += 1) {
      if (remainingCount <= MAX_STREAM_CACHE_FILES && totalBytes <= MAX_STREAM_CACHE_BYTES) {
        break;
      }
      await fs.promises.unlink(sorted[i].fullPath).catch(() => {});
      totalBytes -= sorted[i].size;
      remainingCount -= 1;
    }
  } catch (_) {
    // Non-fatal cache cleanup
  }
}

async function resolveTelegramBotDownloadUrl({ tgFileId, db, forceRefresh = false }) {
  const cleanFileId = String(tgFileId || "").trim();
  const nowMs = Date.now();
  if (!forceRefresh) {
    const cached = telegramFileUrlCache.get(cleanFileId);
    if (cached && nowMs - cached.cachedAt < TELEGRAM_FILE_URL_CACHE_TTL_MS) {
      return cached;
    }
  }

  const { botToken, apiBase } = await resolveBotRuntimeConfig(db);
  if (!botToken) {
    const err = new Error("Bot token unavailable for stream.");
    err.statusCode = 500;
    throw err;
  }

  const fileInfo = await telegramApiJson(apiBase, botToken, "getFile", { file_id: cleanFileId });
  const downloadUrl = String(fileInfo.file_path || "").startsWith("http")
    ? fileInfo.file_path
    : `${apiBase}/file/bot${botToken}/${fileInfo.file_path}`;
  const fileSize = Number(fileInfo.file_size || 0) || 0;

  if (telegramFileUrlCache.size >= MAX_TELEGRAM_FILE_URL_CACHE) {
    const oldestKey = telegramFileUrlCache.keys().next().value;
    if (oldestKey) telegramFileUrlCache.delete(oldestKey);
  }
  const entry = { downloadUrl, fileSize, cachedAt: nowMs };
  telegramFileUrlCache.set(cleanFileId, entry);
  return entry;
}

function getHeaderValue(headers, name) {
  if (!headers) return "";
  if (typeof headers.get === "function") {
    return String(headers.get(name) || "");
  }
  const lower = String(name).toLowerCase();
  return String(headers[lower] || headers[name] || "");
}

async function streamTelegramBotVideoFast({
  videoId,
  tgFileId,
  method = "GET",
  rangeHeader,
  res,
  db,
}) {
  const safeId = String(videoId).replace(/[^a-zA-Z0-9_-]/g, "_");
  const cachedPath = path.join(STREAM_CACHE_DIR, `${safeId}.mp4`);
  const isHead = String(method || "GET").toUpperCase() === "HEAD";

  // 1. Instant local /tmp hit if already cached (e.g., recently uploaded video with +faststart)
  try {
    const existing = await fs.promises.stat(cachedPath);
    if (existing.isFile() && existing.size > 0) {
      await serveLocalFileWithRange({
        filePath: cachedPath,
        contentType: "video/mp4",
        method,
        rangeHeader,
        cacheControl: "private, no-cache",
        res,
      });
      return;
    }
  } catch (_) {
    // Cache miss — stream directly from Telegram with zero full-file wait
  }

  // 2. Resolve cached Telegram CDN URL (avoids repeated getFile round-trips on ExoPlayer range probes)
  let resolved = await resolveTelegramBotDownloadUrl({ tgFileId, db });
  const reqHeaders = {};
  if (rangeHeader) {
    reqHeaders.Range = rangeHeader;
  }

  let upstream = await fetch(resolved.downloadUrl, {
    method: isHead ? "HEAD" : "GET",
    headers: reqHeaders,
  });

  // If cached Telegram file_path expired (401/404/410), refresh once via getFile
  if (!upstream.ok && (upstream.status === 401 || upstream.status === 404 || upstream.status === 410)) {
    telegramFileUrlCache.delete(String(tgFileId || "").trim());
    resolved = await resolveTelegramBotDownloadUrl({ tgFileId, db, forceRefresh: true });
    upstream = await fetch(resolved.downloadUrl, {
      method: isHead ? "HEAD" : "GET",
      headers: reqHeaders,
    });
  }

  // If Telegram CDN rejects HEAD (405), answer directly using known fileSize from getFile
  if (isHead && upstream.status === 405 && resolved.fileSize > 0) {
    res.writeHead(200, {
      "Content-Type": "video/mp4",
      "Accept-Ranges": "bytes",
      "Content-Length": resolved.fileSize,
      "Cache-Control": "private, no-cache",
    });
    res.end();
    return;
  }

  if (!upstream.ok && upstream.status !== 206) {
    const err = new Error("Unable to stream video from Telegram.");
    err.statusCode = upstream.status === 416 ? 416 : 502;
    throw err;
  }

  // 3. Fast path: Upstream supports Range (206), or request starts from byte 0 (bytes=0- / no Range / HEAD)
  // Pipe bytes straight to ExoPlayer immediately without waiting to download the full file!
  const upstreamContentRange = getHeaderValue(upstream.headers, "content-range");
  const upstreamContentLength = getHeaderValue(upstream.headers, "content-length");
  const isOpenStartRange = Boolean(rangeHeader) && /^bytes=0-\s*$/i.test(String(rangeHeader).trim());
  const canDirectStream =
    upstream.status === 206 ||
    !rangeHeader ||
    isOpenStartRange ||
    isHead;

  if (canDirectStream && (isHead || upstream.body || !rangeHeader || isOpenStartRange)) {
    const responseStatus = upstream.status === 206 ? 206 : 200;
    const outHeaders = {
      "Content-Type": "video/mp4",
      "Accept-Ranges": "bytes",
      "Cache-Control": "private, no-cache",
    };
    if (upstreamContentRange) {
      outHeaders["Content-Range"] = upstreamContentRange;
    }
    if (upstreamContentLength) {
      const parsedLen = Number(upstreamContentLength);
      outHeaders["Content-Length"] = Number.isFinite(parsedLen) && parsedLen >= 0 ? parsedLen : upstreamContentLength;
    } else if (resolved.fileSize > 0 && responseStatus === 200) {
      outHeaders["Content-Length"] = resolved.fileSize;
    }

    res.writeHead(responseStatus, outHeaders);
    if (isHead) {
      res.end();
      return;
    }

    if (upstream.body) {
      const nodeStream =
        typeof upstream.body.pipe === "function"
          ? upstream.body
          : Readable.fromWeb(upstream.body);
      if (typeof res.on === "function") {
        res.on("close", () => {
          if (typeof nodeStream.destroy === "function") nodeStream.destroy();
        });
      }
      await new Promise((resolveStream) => {
        let settled = false;
        const finishOnce = () => {
          if (settled) return;
          settled = true;
          resolveStream();
        };
        nodeStream.on("error", () => {
          if (!res.writableEnded) res.end();
          finishOnce();
        });
        if (typeof res.on === "function") {
          res.on("error", () => {
            if (typeof nodeStream.destroy === "function") nodeStream.destroy();
            finishOnce();
          });
          res.on("finish", finishOnce);
          res.on("close", finishOnce);
        }
        nodeStream.pipe(res);
      });
      return;
    }

    const buf = Buffer.from(await upstream.arrayBuffer());
    res.end(buf);
    return;
  }

  // 4. Fallback when upstream ignored Range header (returned 200 for a Range request):
  // Save that already-open response directly into /tmp cache (1 fetch total) and serve exact byte range.
  await fs.promises.mkdir(STREAM_CACHE_DIR, { recursive: true });
  const tmpPart = `${cachedPath}.part.${crypto.randomBytes(4).toString("hex")}`;
  const written = await writeFetchResponseToFile(upstream, tmpPart);
  if (!written) {
    await fs.promises.unlink(tmpPart).catch(() => {});
    const err = new Error("Empty video file from Telegram.");
    err.statusCode = 502;
    throw err;
  }
  await fs.promises.rename(tmpPart, cachedPath);
  pruneStreamCacheIfNeeded();

  await serveLocalFileWithRange({
    filePath: cachedPath,
    contentType: "video/mp4",
    method,
    rangeHeader,
    cacheControl: "private, no-cache",
    res,
  });
}

async function ensureTelegramVideoCached({ videoId, tgFileId, db }) {
  const safeId = String(videoId).replace(/[^a-zA-Z0-9_-]/g, "_");
  const cachedPath = path.join(STREAM_CACHE_DIR, `${safeId}.mp4`);

  try {
    const existing = await fs.promises.stat(cachedPath);
    if (existing.isFile() && existing.size > 0) {
      return cachedPath;
    }
  } catch (_) {
    // Cache miss — fetch below
  }

  if (inflightTelegramDownloads.has(safeId)) {
    return inflightTelegramDownloads.get(safeId);
  }

  const downloadPromise = (async () => {
    await fs.promises.mkdir(STREAM_CACHE_DIR, { recursive: true });
    const { downloadUrl } = await resolveTelegramBotDownloadUrl({ tgFileId, db });

    const upstream = await fetch(downloadUrl);
    if (!upstream.ok) {
      const err = new Error("Unable to stream video from Telegram.");
      err.statusCode = 502;
      throw err;
    }

    const tmpPart = `${cachedPath}.part.${crypto.randomBytes(4).toString("hex")}`;
    const written = await writeFetchResponseToFile(upstream, tmpPart);
    if (!written) {
      await fs.promises.unlink(tmpPart).catch(() => {});
      const err = new Error("Empty video file from Telegram.");
      err.statusCode = 502;
      throw err;
    }
    await fs.promises.rename(tmpPart, cachedPath);
    pruneStreamCacheIfNeeded();
    return cachedPath;
  })();

  inflightTelegramDownloads.set(safeId, downloadPromise);
  try {
    return await downloadPromise;
  } finally {
    inflightTelegramDownloads.delete(safeId);
  }
}

async function serveLocalFileWithRange({ filePath, contentType, method, rangeHeader, cacheControl, res }) {
  const stat = await fs.promises.stat(filePath);
  const fileSize = stat.size;
  const isHead = String(method || "GET").toUpperCase() === "HEAD";

  if (rangeHeader) {
    const parsed = parseHttpByteRange(rangeHeader, fileSize);
    if (parsed && parsed.invalid) {
      res.writeHead(416, { "Content-Range": `bytes */${fileSize}` });
      res.end();
      return;
    }
    if (parsed) {
      res.writeHead(206, {
        "Content-Range": `bytes ${parsed.start}-${parsed.end}/${fileSize}`,
        "Accept-Ranges": "bytes",
        "Content-Length": parsed.chunkSize,
        "Content-Type": contentType,
        "Cache-Control": cacheControl,
      });
      if (isHead) {
        res.end();
        return;
      }
      const stream = fs.createReadStream(filePath, { start: parsed.start, end: parsed.end });
      if (typeof res.on === "function") {
        res.on("close", () => stream.destroy());
      }
      stream.on("error", () => {
        if (!res.writableEnded) res.end();
      });
      stream.pipe(res);
      return;
    }
  }

  res.writeHead(200, {
    "Content-Length": fileSize,
    "Content-Type": contentType,
    "Accept-Ranges": "bytes",
    "Cache-Control": cacheControl,
  });
  if (isHead) {
    res.end();
    return;
  }
  const stream = fs.createReadStream(filePath);
  if (typeof res.on === "function") {
    res.on("close", () => stream.destroy());
  }
  stream.on("error", () => {
    if (!res.writableEnded) res.end();
  });
  stream.pipe(res);
}

async function streamTelegramMtprotoWithRange({
  videoData,
  method = "GET",
  rangeHeader,
  res,
  db,
  telegramLibOverride = null,
}) {
  const { botToken, apiId, apiHash } = await resolveBotRuntimeConfig(db);
  if (!botToken || !apiId || !apiHash) {
    const err = new Error("MTProto streaming requires TELEGRAM_API_ID and TELEGRAM_API_HASH.");
    err.statusCode = 502;
    throw err;
  }

  let { client, libs } = await getMtprotoClient({ apiId, apiHash, botToken, db, telegramLibOverride });
  const isHead = String(method || "GET").toUpperCase() === "HEAD";
  const contentType = inferVideoMimeType(videoData.title, videoData.tgMimeType);

  let descriptor = await resolveMtprotoMediaDescriptor({
    client,
    libs,
    tgFileId: videoData.tgFileId,
    tgChatId: videoData.tgChatId || null,
    tgMessageId: videoData.tgMessageId || null,
    tgFileSize: toSafeByteLength(videoData.tgFileSize),
    forceRefresh: false,
  });

  if ((!descriptor.fileSize || descriptor.fileSize <= 0) && videoData.tgChatId && videoData.tgMessageId) {
    descriptor = await resolveMtprotoMediaDescriptor({
      client,
      libs,
      tgFileId: videoData.tgFileId,
      tgChatId: videoData.tgChatId,
      tgMessageId: videoData.tgMessageId,
      tgFileSize: 0,
      forceRefresh: true,
    });
  }

  const fileSize = toSafeByteLength(descriptor.fileSize) || toSafeByteLength(videoData.tgFileSize);
  if (!Number.isFinite(fileSize) || fileSize <= 0) {
    const err = new Error("Unable to determine Telegram video byte size for streaming.");
    err.statusCode = 502;
    throw err;
  }

  // Backfill missing tgFileSize in DB asynchronously so future plays skip getMessages lookup
  if (!toSafeByteLength(videoData.tgFileSize) && videoData.videoId && db && typeof db.collection === "function") {
    try {
      const col = db.collection("videos");
      const ref = typeof col.doc === "function" ? col.doc(String(videoData.videoId)) : null;
      if (ref && typeof ref.set === "function") {
        ref.set({ tgFileSize: fileSize }, { merge: true }).catch(() => {});
      }
    } catch (_) {}
  }

  let start = 0;
  let end = fileSize - 1;
  let isPartial = false;

  if (rangeHeader) {
    const parsed = parseHttpByteRange(rangeHeader, fileSize);
    if (parsed && parsed.invalid) {
      res.writeHead(416, { "Content-Range": `bytes */${fileSize}` });
      res.end();
      return;
    }
    if (parsed) {
      start = parsed.start;
      end = parsed.end;
      isPartial = true;
    }
  }

  const chunkSize = end - start + 1;
  const responseHeaders = isPartial
    ? {
        "Content-Range": `bytes ${start}-${end}/${fileSize}`,
        "Accept-Ranges": "bytes",
        "Content-Length": chunkSize,
        "Content-Type": contentType,
        "Cache-Control": "private, no-cache",
      }
    : {
        "Content-Length": fileSize,
        "Content-Type": contentType,
        "Accept-Ranges": "bytes",
        "Cache-Control": "private, no-cache",
      };

  if (isHead) {
    res.writeHead(isPartial ? 206 : 200, responseHeaders);
    res.end();
    return;
  }

  const startIteration = async (activeClient, desc) => {
    const alignedStart = Math.floor(start / MTPROTO_CHUNK_SIZE) * MTPROTO_CHUNK_SIZE;
    const rawIterable = activeClient.iterDownload({
      file: desc.location,
      dcId: desc.dcId,
      offset: libs.bigInt(alignedStart),
      requestSize: MTPROTO_CHUNK_SIZE,
    });
    const iter =
      rawIterable && typeof rawIterable.next === "function"
        ? rawIterable
        : rawIterable && typeof rawIterable[Symbol.asyncIterator] === "function"
          ? rawIterable[Symbol.asyncIterator]()
          : null;
    if (!iter || typeof iter.next !== "function") {
      throw new Error("Telegram MTProto stream iterator is unavailable.");
    }
    const first = await iter.next();
    return { iter, first, alignedStart };
  };

  let iterState;
  try {
    iterState = await startIteration(client, descriptor);
  } catch (err) {
    const msg = String(err?.errorMessage || err?.message || "");
    const isDisconnectErr =
      /not connected|disconnected|connection closed|timeout|socket|econnreset|broken pipe/i.test(msg);
    if (msg.includes("FILE_REFERENCE") && videoData.tgChatId && videoData.tgMessageId) {
      descriptor = await resolveMtprotoMediaDescriptor({
        client,
        libs,
        tgFileId: videoData.tgFileId,
        tgChatId: videoData.tgChatId,
        tgMessageId: videoData.tgMessageId,
        tgFileSize: fileSize,
        forceRefresh: true,
      });
      iterState = await startIteration(client, descriptor);
    } else if (isDisconnectErr && typeof client.connect === "function") {
      await client.connect();
      iterState = await startIteration(client, descriptor);
    } else {
      throw err;
    }
  }

  const { iter, first, alignedStart } = iterState;
  let aborted = false;
  if (typeof res.on === "function") {
    res.on("close", () => {
      aborted = true;
      if (typeof iter.return === "function") {
        Promise.resolve(iter.return()).catch(() => {});
      }
    });
  }

  res.writeHead(isPartial ? 206 : 200, responseHeaders);

  let skipBytes = start - alignedStart;
  let remaining = chunkSize;

  const emitChunk = async (rawChunk) => {
    if (aborted || res.destroyed || res.writableEnded || remaining <= 0) return false;
    let slice = Buffer.isBuffer(rawChunk) ? rawChunk : Buffer.from(rawChunk);
    if (!slice.length) return false;

    if (skipBytes > 0) {
      if (slice.length <= skipBytes) {
        skipBytes -= slice.length;
        return true;
      }
      slice = slice.subarray(skipBytes);
      skipBytes = 0;
    }

    if (slice.length > remaining) {
      slice = slice.subarray(0, remaining);
    }

    if (slice.length > 0) {
      remaining -= slice.length;
      const canContinue = res.write(slice);
      if (!canContinue && !aborted && !res.destroyed) {
        await new Promise((resolve) => {
          const cleanup = () => {
            if (typeof res.off === "function") {
              res.off("drain", onDone);
              res.off("close", onDone);
            } else if (typeof res.removeListener === "function") {
              res.removeListener("drain", onDone);
              res.removeListener("close", onDone);
            }
          };
          const onDone = () => {
            cleanup();
            resolve();
          };
          if (typeof res.once === "function") {
            res.once("drain", onDone);
            res.once("close", onDone);
          } else {
            resolve();
          }
        });
      }
    }
    return remaining > 0;
  };

  try {
    if (first && !first.done) {
      const keepGoing = await emitChunk(first.value);
      if (keepGoing && !aborted) {
        while (remaining > 0 && !aborted) {
          const step = await iter.next();
          if (step.done) break;
          const cont = await emitChunk(step.value);
          if (!cont) break;
        }
      }
    }
  } finally {
    if (typeof iter.return === "function") {
      await Promise.resolve(iter.return()).catch(() => {});
    }
    if (!res.writableEnded) {
      res.end();
    }
  }
}

async function handleStreamPublicVideo({
  videoId,
  kind,
  sig,
  method = "GET",
  rangeHeader,
  res,
  db,
  storage,
  playbackSecret,
  telegramLibOverride = null,
}) {
  const cleanVideoId = String(videoId || "").trim();
  const cleanKind = kind === "thumb" ? "thumb" : "video";
  const cleanSig = String(sig || "").trim();
  const isHead = String(method || "GET").toUpperCase() === "HEAD";

  if (!cleanVideoId || !verifyStreamTarget(cleanVideoId, cleanKind, cleanSig, playbackSecret)) {
    res.writeHead(403, { "Content-Type": "text/plain" });
    res.end("Forbidden: Invalid stream signature.");
    return;
  }

  const nowMs = Date.now();
  const cachedEntry = videoDocCache.get(cleanVideoId);
  let videoData = null;
  if (cachedEntry && nowMs - cachedEntry.cachedAt < VIDEO_DOC_CACHE_TTL_MS) {
    videoData = cachedEntry.data;
  } else {
    const videosCol = db.collection("videos");
    const videoRef = typeof videosCol.doc === "function" ? videosCol.doc(cleanVideoId) : videosCol.document(cleanVideoId);
    const videoDoc = await videoRef.get();
    if (!videoDoc.exists || videoDoc.data()?.isActive !== true) {
      videoDocCache.delete(cleanVideoId);
      res.writeHead(403, { "Content-Type": "text/plain" });
      res.end("Forbidden: This video is deactivated or unavailable.");
      return;
    }
    videoData = compactVideoDocForStreamCache(videoDoc.data() || {});
    if (videoDocCache.size >= MAX_VIDEO_DOC_CACHE) {
      const oldestKey = videoDocCache.keys().next().value;
      if (oldestKey) videoDocCache.delete(oldestKey);
    }
    videoDocCache.set(cleanVideoId, { data: videoData, cachedAt: nowMs });
  }

  // Fast path: if video was saved with Telegram fallback (no Storage copy) or is already cached in /tmp,
  // avoid slow Firebase Storage metadata checks on every range request.
  const isTelegramVideoStream = cleanKind === "video" && Boolean(videoData.tgFileId);

  // 1. Try Firebase Storage first only when video is NOT Telegram-fallback backed
  if (!isTelegramVideoStream && storage && typeof storage.bucket === "function") {
    try {
      const storagePath =
        cleanKind === "thumb"
          ? `videos/${cleanVideoId}/thumbnail.jpg`
          : `videos/${cleanVideoId}/video.mp4`;
      const file = storage.bucket().file(storagePath);
      const [exists] = await file.exists();
      if (exists) {
        const [metadata] = await file.getMetadata();
        const fileSize = parseInt(metadata.size, 10);
        const rawType = String(metadata.contentType || "").toLowerCase();
        const contentType =
          !rawType || rawType === "application/octet-stream"
            ? cleanKind === "thumb"
              ? "image/jpeg"
              : "video/mp4"
            : metadata.contentType;

        if (rangeHeader && cleanKind === "video") {
          const parsed = parseHttpByteRange(rangeHeader, fileSize);
          if (parsed && parsed.invalid) {
            res.writeHead(416, { "Content-Range": `bytes */${fileSize}` });
            res.end();
            return;
          }
          if (parsed) {
            res.writeHead(206, {
              "Content-Range": `bytes ${parsed.start}-${parsed.end}/${fileSize}`,
              "Accept-Ranges": "bytes",
              "Content-Length": parsed.chunkSize,
              "Content-Type": contentType,
              "Cache-Control": "private, no-cache",
            });
            if (isHead) {
              res.end();
              return;
            }
            const stream = file.createReadStream({ start: parsed.start, end: parsed.end });
            if (typeof res.on === "function") {
              res.on("close", () => stream.destroy());
            }
            stream.pipe(res);
            return;
          }
        }

        res.writeHead(200, {
          "Content-Length": fileSize,
          "Content-Type": contentType,
          "Accept-Ranges": "bytes",
          "Cache-Control": cleanKind === "thumb" ? "public, max-age=3600" : "private, no-cache",
        });
        if (isHead) {
          res.end();
          return;
        }
        const stream = file.createReadStream();
        if (typeof res.on === "function") {
          res.on("close", () => stream.destroy());
        }
        stream.pipe(res);
        return;
      }
    } catch (storageErr) {
      console.warn("Storage lookup skipped, checking Telegram fallback:", storageErr.message);
    }
  }

  // 2. Fallback: Stream Telegram file directly (MTProto byte-ranges for >20 MB, or /tmp cache for <=20 MB)
  if (cleanKind === "video" && videoData.tgFileId) {
    try {
      const knownFileSize = Number(videoData.tgFileSize || 0) || 0;
      if (knownFileSize > BOT_API_MAX_BYTES) {
        await streamTelegramMtprotoWithRange({
          videoData,
          method,
          rangeHeader,
          res,
          db,
          telegramLibOverride,
        });
        return;
      }

      try {
        await streamTelegramBotVideoFast({
          videoId: cleanVideoId,
          tgFileId: videoData.tgFileId,
          method,
          rangeHeader,
          res,
          db,
        });
        return;
      } catch (botCacheErr) {
        if (String(botCacheErr?.message || "").toLowerCase().includes("file is too big")) {
          await streamTelegramMtprotoWithRange({
            videoData,
            method,
            rangeHeader,
            res,
            db,
            telegramLibOverride,
          });
          return;
        }
        throw botCacheErr;
      }
    } catch (tgStreamErr) {
      const status = tgStreamErr.statusCode || 502;
      if (!res.headersSent) {
        const errHeaders = { "Content-Type": "text/plain" };
        if (Number(tgStreamErr.retryAfterSec) > 0) {
          errHeaders["Retry-After"] = String(Math.ceil(Number(tgStreamErr.retryAfterSec)));
        }
        res.writeHead(status, errHeaders);
        res.end(tgStreamErr.message || "Unable to stream video from Telegram.");
      } else if (!res.writableEnded) {
        res.end();
      }
      return;
    }
  }

  res.writeHead(404, { "Content-Type": "text/plain" });
  res.end("Media file not found.");
}

module.exports = {
  SHARE_LINK_BASE_URL,
  SUPERADMIN_PASSKEY,
  BOT_API_MAX_BYTES,
  WEBHOOK_PREVIEW_MAX_BYTES,
  MTPROTO_CHUNK_SIZE,
  hashToken,
  generateAccessToken,
  escapeMarkdown,
  telegramApiJson,
  signStreamTarget,
  verifyStreamTarget,
  parseLoginCommand,
  parseChannelTargetInput,
  extractForwardedChannelInfo,
  parseTitleAndDescription,
  extractDirectVideoUrlMessage,
  extractVidVoraShareTarget,
  isUnsupportedThirdPartyHostUrl,
  resolveVidVoraSourceVideo,
  isAllowedTelegramVideoMessage,
  toSafeByteLength,
  inferVideoMimeType,
  buildShareMessageCaption,
  buildSharePlainFallbackText,
  CAPTION_BLOCK_NONE,
  sanitizeCustomCaptionBlock,
  buildCaptionSettingsKeyboard,
  parseTelegramFloodWaitSeconds,
  createFiveSecondPreviewAndThumbnail,
  decodeTelegramBotFileId,
  resolveBotRuntimeConfig,
  authenticateCreatorMember,
  handleTelegramUpdate,
  streamTelegramMtprotoWithRange,
  handleStreamPublicVideo,
  clearTelegramRuntimeCaches,
};
