const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const os = require("os");
const { execFile } = require("child_process");
const { promisify } = require("util");

const execFileAsync = promisify(execFile);

const SHARE_LINK_BASE_URL = "https://d1mn8mrvlzisg5.cloudfront.net/index.html?link=";
const SUPERADMIN_PASSKEY = "GM-ADMIN-2026-MASTER";
const DEFAULT_PREVIEW_SECONDS = 5;
const { normalizeShareBaseUrl } = require("./sharePreview");

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

function isAllowedTelegramVideoMessage(message) {
  if (!message) return false;
  if (message.video && message.video.file_id) return true;
  if (message.document && message.document.file_id) {
    const mime = String(message.document.mime_type || "").toLowerCase();
    const fileName = String(message.document.file_name || "").toLowerCase();
    if (mime.startsWith("video/")) return true;
    return /\.(mp4|mkv|mov|webm|m4v|3gp|avi)$/i.test(fileName);
  }
  return false;
}

function buildShareMessageCaption({
  title,
  uploaderName,
  rawToken,
  previewSeconds = DEFAULT_PREVIEW_SECONDS,
  shareLinkBaseUrl = SHARE_LINK_BASE_URL,
}) {
  const effectiveBase = shareLinkBaseUrl || SHARE_LINK_BASE_URL;
  const watchUrl = `${effectiveBase}${rawToken}`;
  const deepLink = `vidvora://watch?link=${rawToken}`;
  const lines = [
    "🎬 *VidVora — Watch Video*",
    `✅ Uploaded to VidVora by *${escapeMarkdown(uploaderName)}*`,
    `🎞️ Preview Clip: *${previewSeconds}s*`,
    "",
    `🔗 *Watch Full Video:*`,
    watchUrl,
    "",
    `🔑 *Access Code:* \`${rawToken}\``,
    `📲 *App Link:* \`${deepLink}\``,
    "",
    "_Forward this preview clip + link directly to your Telegram channel!_",
  ];
  return lines.join("\n");
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

  const baseArgs = [
    "-y",
    "-ss",
    String(startSec),
    "-i",
    inputPathOrUrl,
    "-t",
    String(clipDuration),
    "-vf",
    "scale='min(720,iw)':-2",
    "-c:v",
    "libx264",
    "-preset",
    "veryfast",
    "-crf",
    "28",
    "-pix_fmt",
    "yuv420p",
    "-movflags",
    "+faststart",
  ];

  try {
    await execFileAsync(
      ffmpegBin,
      [...baseArgs, "-c:a", "aac", "-b:a", "96k", outputClipPath],
      { timeout: 120_000, maxBuffer: 10 * 1024 * 1024 }
    );
  } catch (_) {
    // Retry without audio track (handles silent videos or incompatible audio codecs)
    await execFileAsync(
      ffmpegBin,
      [...baseArgs, "-an", outputClipPath],
      { timeout: 120_000, maxBuffer: 10 * 1024 * 1024 }
    );
  }

  const extractHdThumb = async (source, seekSec) => {
    const args = ["-y"];
    if (seekSec > 0) {
      args.push("-ss", String(seekSec));
    }
    args.push(
      "-i",
      source,
      "-vframes",
      "1",
      "-vf",
      "scale='min(1280,iw)':-2:flags=lanczos",
      "-q:v",
      "2",
      outputThumbPath
    );
    await execFileAsync(ffmpegBin, args, { timeout: 60_000, maxBuffer: 5 * 1024 * 1024 });
  };

  try {
    await extractHdThumb(inputPathOrUrl, startSec);
  } catch (_) {
    try {
      await extractHdThumb(inputPathOrUrl, 0);
    } catch (_) {
      // Fallback thumbnail from frame 0 of generated preview clip
      await extractHdThumb(outputClipPath, 0);
    }
  }

  return {
    clipExists: fs.existsSync(outputClipPath) && fs.statSync(outputClipPath).size > 0,
    thumbExists: fs.existsSync(outputThumbPath) && fs.statSync(outputThumbPath).size > 0,
  };
}

async function encodeCrispThumbDataUri(thumbPath, maxBytes = 350 * 1024) {
  if (!thumbPath || !fs.existsSync(thumbPath)) return "";
  try {
    let thumbBuf = await fs.promises.readFile(thumbPath);
    if (thumbBuf.length > 0 && thumbBuf.length <= maxBytes) {
      return `data:image/jpeg;base64,${thumbBuf.toString("base64")}`;
    }
    const ffmpegBin = getFfmpegBinary();
    const recompressedPath = `${thumbPath}.compact.jpg`;
    for (const qVal of ["4", "6", "8"]) {
      await execFileAsync(
        ffmpegBin,
        ["-y", "-i", thumbPath, "-vf", "scale='min(1280,iw)':-2:flags=lanczos", "-q:v", qVal, recompressedPath],
        { timeout: 20_000, maxBuffer: 4 * 1024 * 1024 }
      );
      if (fs.existsSync(recompressedPath)) {
        const candidate = await fs.promises.readFile(recompressedPath);
        if (candidate.length > 0 && candidate.length <= maxBytes) {
          return `data:image/jpeg;base64,${candidate.toString("base64")}`;
        }
      }
    }
  } catch (err) {
    console.warn("encodeCrispThumbDataUri warning:", err.message);
  }
  return "";
}

async function telegramApiJson(apiBase, botToken, method, payload) {
  const res = await fetch(`${apiBase}/bot${botToken}/${method}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  const data = await res.json();
  if (!data.ok) {
    throw new Error(data.description || `Telegram ${method} failed`);
  }
  return data.result;
}

async function sendTelegramVideoClip(apiBase, botToken, { chatId, clipPath, caption, watchUrl, replyToMessageId }) {
  const form = new FormData();
  form.append("chat_id", String(chatId));
  form.append("caption", caption);
  form.append("parse_mode", "Markdown");
  if (replyToMessageId) {
    form.append("reply_to_message_id", String(replyToMessageId));
  }
  form.append(
    "reply_markup",
    JSON.stringify({
      inline_keyboard: [[{ text: "▶️ Watch Full Video on VidVora", url: watchUrl }]],
    })
  );
  const clipBuffer = await fs.promises.readFile(clipPath);
  const blob = new Blob([clipBuffer], { type: "video/mp4" });
  form.append("video", blob, "vidvora_preview_5s.mp4");

  const res = await fetch(`${apiBase}/bot${botToken}/sendVideo`, {
    method: "POST",
    body: form,
  });
  const data = await res.json();
  if (!data.ok) {
    throw new Error(data.description || "Telegram sendVideo failed");
  }
  return data.result;
}

async function downloadFileToPath(url, destPath) {
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`Download failed (${res.status} ${res.statusText})`);
  }
  const arrayBuffer = await res.arrayBuffer();
  await fs.promises.writeFile(destPath, Buffer.from(arrayBuffer));
}

async function resolveBotRuntimeConfig(db) {
  let botToken = (process.env.TELEGRAM_BOT_TOKEN || "").trim();
  let apiBase = (process.env.TELEGRAM_API_BASE || "https://api.telegram.org").trim().replace(/\/+$/, "");
  let webhookSecret = (process.env.TELEGRAM_WEBHOOK_SECRET || "").trim();
  let shareLinkBaseUrl = normalizeShareBaseUrl(process.env.SHARE_LINK_BASE_URL || "");

  if (!botToken) {
    try {
      const cfgSnap = await db.collection("appConfig").doc("telegramBot").get();
      if (cfgSnap.exists) {
        const data = cfgSnap.data() || {};
        if (typeof data.botToken === "string" && data.botToken.trim()) {
          botToken = data.botToken.trim();
        }
        if (typeof data.webhookSecret === "string" && data.webhookSecret.trim()) {
          webhookSecret = data.webhookSecret.trim();
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

  return { botToken, apiBase, webhookSecret, shareLinkBaseUrl };
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

  const snap = await db.collection("members").where("passkey", "==", cleanKey).limit(10).get();
  if (snap.empty) return null;

  const cleanIdentity = String(emailOrPhone || "").trim().toLowerCase();
  for (const doc of snap.docs) {
    const data = doc.data() || {};
    if (data.isActive !== true) continue;
    if (cleanIdentity && String(data.emailOrPhone || "").trim().toLowerCase() !== cleanIdentity) {
      continue;
    }
    const allowed = Array.isArray(data.allowedOptions) ? data.allowedOptions : null;
    const canUpload = !allowed || allowed.includes("UPLOAD_VIDEOS");
    if (!canUpload) {
      return { memberId: data.memberId || doc.id, name: data.name || "Creator", canUpload: false };
    }
    return {
      memberId: data.memberId || doc.id,
      name: String(data.name || "Creator").trim() || "Creator",
      emailOrPhone: String(data.emailOrPhone || ""),
      role: String(data.role || "member"),
      canUpload: true,
    };
  }
  return null;
}

async function handleTelegramUpdate({ update, db, storage, functionBaseUrl, playbackSecret }) {
  const message = update?.message || update?.edited_message;
  if (!message || !message.chat || !message.from) {
    return { ok: true, ignored: true };
  }

  const chatId = message.chat.id;
  const telegramUserId = String(message.from.id);
  const { botToken, apiBase, shareLinkBaseUrl } = await resolveBotRuntimeConfig(db);
  if (!botToken) {
    throw new Error("TELEGRAM_BOT_TOKEN is not configured.");
  }

  const replyText = async (text, extra = {}) =>
    telegramApiJson(apiBase, botToken, "sendMessage", {
      chat_id: chatId,
      text,
      ...extra,
    });

  const textBody = String(message.text || "").trim();

  // 1. Commands
  if (/^\/start(?:@\w+)?/i.test(textBody) || /^\/help(?:@\w+)?/i.test(textBody)) {
    await replyText(
      [
        "🎬 *VidVora Creator Auto-Upload Bot*",
        "",
        "1️⃣ Link your VidVora Creator / Admin account:",
        "`/login YOUR_MASTER_KEY`",
        "_(or `/login your@email.com YOUR_MASTER_KEY`)_",
        "",
        "2️⃣ Send any *Video* (or direct `.mp4` stream link) with a caption for Title.",
        "",
        "3️⃣ Bot automatically uploads it to VidVora, creates a *5-second preview clip*, and sends the clip + VidVora watch link right back here!",
        "",
        "Commands: `/status` • `/logout`",
      ].join("\n"),
      { parse_mode: "Markdown" }
    );
    return { ok: true, action: "help" };
  }

  if (/^\/logout(?:@\w+)?/i.test(textBody)) {
    await db.collection("telegramBotSessions").doc(telegramUserId).delete();
    await replyText("🔓 Unlinked your VidVora account from this bot. Send `/login MASTER_KEY` anytime to reconnect.", {
      parse_mode: "Markdown",
    });
    return { ok: true, action: "logout" };
  }

  if (/^\/status(?:@\w+)?/i.test(textBody)) {
    const sessionSnap = await db.collection("telegramBotSessions").doc(telegramUserId).get();
    if (!sessionSnap.exists) {
      await replyText("❌ Not linked yet. Send `/login YOUR_MASTER_KEY` first.", { parse_mode: "Markdown" });
      return { ok: true, action: "status_unlinked" };
    }
    const s = sessionSnap.data() || {};
    await replyText(
      `✅ Linked Creator: *${escapeMarkdown(s.memberName || "Creator")}*\n📤 Uploads via Bot: *${s.uploadCount || 0}*`,
      { parse_mode: "Markdown" }
    );
    return { ok: true, action: "status" };
  }

  const loginCmd = parseLoginCommand(textBody);
  if (loginCmd) {
    const creator = await authenticateCreatorMember(db, loginCmd.emailOrPhone, loginCmd.passkey);
    if (!creator) {
      await replyText("❌ Invalid or paused Master Key. Check your VidVora Creator Master Key and try again.");
      return { ok: true, action: "login_failed" };
    }
    if (!creator.canUpload) {
      await replyText("⛔ Your account does not have Upload Videos permission enabled.");
      return { ok: true, action: "login_forbidden" };
    }
    await db.collection("telegramBotSessions").doc(telegramUserId).set(
      {
        telegramUserId,
        chatId: String(chatId),
        memberId: creator.memberId,
        memberName: creator.name,
        role: creator.role || "member",
        linkedAt: new Date(),
        uploadCount: 0,
      },
      { merge: true }
    );
    await replyText(
      `✅ Welcome *${escapeMarkdown(creator.name)}*!\n\nYour VidVora account is now linked. Send any video (caption = Title) and I will upload it & send back your *5-sec preview clip + watch link*.`,
      { parse_mode: "Markdown" }
    );
    return { ok: true, action: "login_success" };
  }

  // 2. Check upload session before handling video or direct URL
  const isVideoMsg = isAllowedTelegramVideoMessage(message);
  const directUrlMsg = !isVideoMsg ? extractDirectVideoUrlMessage(textBody) : null;
  if (!isVideoMsg && !directUrlMsg) {
    await replyText(
      "📹 Send a *Video file* (or a direct HTTPS `.mp4` link with Title) to upload to VidVora.\nNeed to link first? Send `/login YOUR_MASTER_KEY`.",
      { parse_mode: "Markdown" }
    );
    return { ok: true, action: "unsupported_message" };
  }

  const sessionSnap = await db.collection("telegramBotSessions").doc(telegramUserId).get();
  if (!sessionSnap.exists) {
    await replyText("🔐 Please link your Creator account first:\n`/login YOUR_MASTER_KEY`", {
      parse_mode: "Markdown",
    });
    return { ok: true, action: "auth_required" };
  }
  const session = sessionSnap.data() || {};

  // Re-verify active permission on every upload
  if (session.memberId !== "admin") {
    const memberSnap = await db.collection("members").doc(session.memberId).get();
    const memberData = memberSnap.exists ? memberSnap.data() : null;
    const allowed = Array.isArray(memberData?.allowedOptions) ? memberData.allowedOptions : null;
    if (!memberData || memberData.isActive !== true || (allowed && !allowed.includes("UPLOAD_VIDEOS"))) {
      await replyText("⛔ Your Creator upload access is currently paused or disabled.");
      return { ok: true, action: "member_disabled" };
    }
  }

  const statusMsg = await replyText("⏳ Uploading to VidVora & generating 5-sec preview clip...");
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
    const bucket = storage.bucket();

    if (isVideoMsg) {
      const mediaObj = message.video || message.document;
      const fallbackName = mediaObj.file_name || "Creator Video";
      const parsedMeta = parseTitleAndDescription(message.caption, fallbackName);
      title = parsedMeta.title;
      description = parsedMeta.description;

      let fileInfo;
      try {
        fileInfo = await telegramApiJson(apiBase, botToken, "getFile", { file_id: mediaObj.file_id });
      } catch (err) {
        if (String(err.message || "").toLowerCase().includes("file is too big")) {
          await telegramApiJson(apiBase, botToken, "editMessageText", {
            chat_id: chatId,
            message_id: statusMsg.message_id,
            text: "⚠️ Telegram standard Bot API allows direct downloads up to 20 MB.\n\nFor larger videos: paste a direct `https://...mp4` stream link with Title (or configure a Telegram Local Bot API Server via `TELEGRAM_API_BASE`).",
            parse_mode: "Markdown",
          });
          return { ok: false, reason: "file_too_big" };
        }
        throw err;
      }

      const filePath = fileInfo.file_path;
      const downloadUrl = filePath.startsWith("http")
        ? filePath
        : `${apiBase}/file/bot${botToken}/${filePath}`;
      const localVideoPath = path.join(tmpDir, `${videoId}.mp4`);
      await downloadFileToPath(downloadUrl, localVideoPath);

      const { clipExists, thumbExists } = await createFiveSecondPreviewAndThumbnail({
        inputPathOrUrl: localVideoPath,
        outputClipPath: clipPath,
        outputThumbPath: thumbPath,
        durationSeconds: message.video?.duration || 0,
        previewSeconds: DEFAULT_PREVIEW_SECONDS,
      });

      const baseHost = functionBaseUrl.replace(/\/+$/, "");
      const videoSig = signStreamTarget(videoId, "video", playbackSecret);
      finalStoragePath = `${baseHost}/streamPublicVideo?videoId=${encodeURIComponent(videoId)}&sig=${encodeURIComponent(videoSig)}`;

      let storageUploadSucceeded = false;
      try {
        if (bucket && typeof bucket.upload === "function") {
          const videoDest = `videos/${videoId}/video.mp4`;
          await bucket.upload(localVideoPath, {
            destination: videoDest,
            metadata: { contentType: mediaObj.mime_type || "video/mp4" },
          });
          storageUploadSucceeded = true;

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

      if (!storageUploadSucceeded && thumbExists) {
        finalThumbPath = await encodeCrispThumbDataUri(thumbPath);
      }

      var tgFileIdForDoc = storageUploadSucceeded ? null : String(mediaObj.file_id || "");
    } else {
      // Direct URL flow (supports unlimited file sizes hosted on Bunny/Cloudflare/S3/CDN)
      const parsedMeta = parseTitleAndDescription(directUrlMsg.caption, "Stream Video");
      title = parsedMeta.title;
      description = parsedMeta.description;
      finalStoragePath = directUrlMsg.url;

      const { clipExists, thumbExists } = await createFiveSecondPreviewAndThumbnail({
        inputPathOrUrl: finalStoragePath,
        outputClipPath: clipPath,
        outputThumbPath: thumbPath,
        durationSeconds: 0,
        previewSeconds: DEFAULT_PREVIEW_SECONDS,
      });

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

      var tgFileIdForDoc = null;
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
    await db.collection("videos").doc(videoId).set(videoDocPayload);

    await db.collection("accessLinks").doc(linkId).set({
      linkId,
      videoId,
      tokenHash,
      isActive: true,
      createdAt: now,
      expiresAt: null,
    });

    await db.collection("telegramBotSessions").doc(telegramUserId).set(
      { uploadCount: (Number(session.uploadCount) || 0) + 1 },
      { merge: true }
    );

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
    });

    if (fs.existsSync(clipPath) && fs.statSync(clipPath).size > 0) {
      await sendTelegramVideoClip(apiBase, botToken, {
        chatId,
        clipPath,
        caption,
        watchUrl,
        replyToMessageId: message.message_id,
      });
      await telegramApiJson(apiBase, botToken, "deleteMessage", {
        chat_id: chatId,
        message_id: statusMsg.message_id,
      }).catch(() => {});
    } else {
      await telegramApiJson(apiBase, botToken, "editMessageText", {
        chat_id: chatId,
        message_id: statusMsg.message_id,
        text: caption,
        parse_mode: "Markdown",
      });
    }

    return { ok: true, videoId, linkId, rawToken };
  } catch (err) {
    console.error("Telegram upload error:", err);
    await telegramApiJson(apiBase, botToken, "editMessageText", {
      chat_id: chatId,
      message_id: statusMsg.message_id,
      text: `❌ Upload failed: ${err.message || "Unknown error"}`,
    }).catch(() => {});
    return { ok: false, error: err.message };
  } finally {
    await fs.promises.rm(tmpDir, { recursive: true, force: true }).catch(() => {});
  }
}

async function handleStreamPublicVideo({ videoId, kind, sig, rangeHeader, res, db, storage, playbackSecret }) {
  const cleanVideoId = String(videoId || "").trim();
  const cleanKind = kind === "thumb" ? "thumb" : "video";
  const cleanSig = String(sig || "").trim();

  if (!cleanVideoId || !verifyStreamTarget(cleanVideoId, cleanKind, cleanSig, playbackSecret)) {
    res.writeHead(403, { "Content-Type": "text/plain" });
    res.end("Forbidden: Invalid stream signature.");
    return;
  }

  const videosCol = db.collection("videos");
  const videoRef = typeof videosCol.doc === "function" ? videosCol.doc(cleanVideoId) : videosCol.document(cleanVideoId);
  const videoDoc = await videoRef.get();
  if (!videoDoc.exists || videoDoc.data()?.isActive !== true) {
    res.writeHead(403, { "Content-Type": "text/plain" });
    res.end("Forbidden: This video is deactivated or unavailable.");
    return;
  }

  const videoData = videoDoc.data() || {};

  // 1. Try Firebase Storage first if available
  if (storage && typeof storage.bucket === "function") {
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
        const contentType = metadata.contentType || (cleanKind === "thumb" ? "image/jpeg" : "video/mp4");

        if (rangeHeader && cleanKind === "video") {
          const parts = String(rangeHeader).replace(/bytes=/, "").split("-");
          const start = parseInt(parts[0], 10);
          const end = parts[1] ? parseInt(parts[1], 10) : fileSize - 1;
          if (start >= fileSize || end >= fileSize || Number.isNaN(start)) {
            res.writeHead(416, { "Content-Range": `bytes */${fileSize}` });
            res.end();
            return;
          }
          const chunkSize = end - start + 1;
          res.writeHead(206, {
            "Content-Range": `bytes ${start}-${end}/${fileSize}`,
            "Accept-Ranges": "bytes",
            "Content-Length": chunkSize,
            "Content-Type": contentType,
            "Cache-Control": "private, no-cache",
          });
          file.createReadStream({ start, end }).pipe(res);
          return;
        }

        res.writeHead(200, {
          "Content-Length": fileSize,
          "Content-Type": contentType,
          "Accept-Ranges": "bytes",
          "Cache-Control": cleanKind === "thumb" ? "public, max-age=3600" : "private, no-cache",
        });
        file.createReadStream().pipe(res);
        return;
      }
    } catch (storageErr) {
      console.warn("Storage lookup skipped, checking Telegram fallback:", storageErr.message);
    }
  }

  // 2. Fallback: Stream directly via Telegram file_id (works on free Spark plan without Storage!)
  if (cleanKind === "video" && videoData.tgFileId) {
    const { botToken, apiBase } = await resolveBotRuntimeConfig(db);
    if (!botToken) {
      res.writeHead(500, { "Content-Type": "text/plain" });
      res.end("Bot token unavailable for stream.");
      return;
    }
    const fileInfo = await telegramApiJson(apiBase, botToken, "getFile", { file_id: videoData.tgFileId });
    const downloadUrl = String(fileInfo.file_path || "").startsWith("http")
      ? fileInfo.file_path
      : `${apiBase}/file/bot${botToken}/${fileInfo.file_path}`;

    const upstreamHeaders = {};
    if (rangeHeader) {
      upstreamHeaders.Range = rangeHeader;
    }
    const upstream = await fetch(downloadUrl, { headers: upstreamHeaders });
    if (!upstream.ok && upstream.status !== 206) {
      res.writeHead(502, { "Content-Type": "text/plain" });
      res.end("Unable to stream video from Telegram.");
      return;
    }

    const respHeaders = {
      "Content-Type": upstream.headers.get("content-type") || "video/mp4",
      "Accept-Ranges": "bytes",
      "Cache-Control": "private, no-cache",
    };
    const contentLen = upstream.headers.get("content-length");
    if (contentLen) respHeaders["Content-Length"] = contentLen;
    const contentRange = upstream.headers.get("content-range");
    if (contentRange) respHeaders["Content-Range"] = contentRange;

    res.writeHead(upstream.status, respHeaders);
    const { Readable } = require("stream");
    if (upstream.body) {
      Readable.fromWeb(upstream.body).pipe(res);
    } else {
      const buf = Buffer.from(await upstream.arrayBuffer());
      res.end(buf);
    }
    return;
  }

  res.writeHead(404, { "Content-Type": "text/plain" });
  res.end("Media file not found.");
}

module.exports = {
  SHARE_LINK_BASE_URL,
  SUPERADMIN_PASSKEY,
  hashToken,
  generateAccessToken,
  signStreamTarget,
  verifyStreamTarget,
  parseLoginCommand,
  parseTitleAndDescription,
  extractDirectVideoUrlMessage,
  isAllowedTelegramVideoMessage,
  buildShareMessageCaption,
  createFiveSecondPreviewAndThumbnail,
  resolveBotRuntimeConfig,
  authenticateCreatorMember,
  handleTelegramUpdate,
  handleStreamPublicVideo,
};
