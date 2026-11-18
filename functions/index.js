const { onRequest, onCall, HttpsError } = require("firebase-functions/v2/https");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { getStorage } = require("firebase-admin/storage");
const admin = require("firebase-admin");
const crypto = require("crypto");
const {
  resolveBotRuntimeConfig,
  handleTelegramUpdate,
  handleStreamPublicVideo,
} = require("./telegramBot");
const {
  resolveFinanceBotRuntimeConfig,
  handleFinanceTelegramUpdate,
} = require("./financeBot");
const {
  handleSharePageRequest,
  handleShareThumbnailRequest,
} = require("./sharePreview");

// Initialize Firebase Admin SDK
admin.initializeApp();

// Database ID configured for this project
const DATABASE_ID = "ai-studio-android-garammas-738e4493-0090-419e-8ece-c55642a933ce";
const db = getFirestore(DATABASE_ID);
const storage = getStorage();

// Secret key for HMAC token signing (can be customized via environment variable)
const PLAYBACK_SECRET = process.env.PLAYBACK_SECRET || "vidvora-secure-playback-secret-key-2026";

// In-memory sliding window rate limiter
const rateLimitMap = new Map();

/**
 * Enforce rate limiting: maximum 10 requests per minute per IP
 */
function checkRateLimit(clientIp) {
  const now = Date.now();
  const windowMs = 60 * 1000; // 1 minute window
  const maxAttempts = 10;

  const record = rateLimitMap.get(clientIp) || { count: 0, resetTime: now + windowMs };
  if (now > record.resetTime) {
    record.count = 1;
    record.resetTime = now + windowMs;
  } else {
    record.count += 1;
  }
  rateLimitMap.set(clientIp, record);
  return record.count <= maxAttempts;
}

/**
 * Deterministic SHA-256 hash of uppercase normalized token
 */
function hashToken(rawToken) {
  const clean = String(rawToken || "").trim().toUpperCase();
  return crypto.createHash("sha256").update(clean, "utf8").digest("hex");
}

/**
 * Create a cryptographically signed, short-lived playback authorization token (15 min TTL)
 */
function createPlaybackToken(payload) {
  const expiresAt = Date.now() + 15 * 60 * 1000; // 15 minutes TTL
  const tokenData = {
    ...payload,
    exp: expiresAt,
    nonce: crypto.randomBytes(8).toString("hex"),
  };
  const b64Data = Buffer.from(JSON.stringify(tokenData)).toString("base64url");
  const signature = crypto
    .createHmac("sha256", PLAYBACK_SECRET)
    .update(b64Data)
    .digest("base64url");
  return `${b64Data}.${signature}`;
}

/**
 * Verify HMAC signature and TTL of playback token
 */
function verifyPlaybackToken(tokenStr) {
  if (!tokenStr || typeof tokenStr !== "string") return null;
  const parts = tokenStr.split(".");
  if (parts.length !== 2) return null;

  const [b64Data, signature] = parts;
  const expectedSig = crypto
    .createHmac("sha256", PLAYBACK_SECRET)
    .update(b64Data)
    .digest("base64url");

  if (signature !== expectedSig) return null;

  try {
    const payload = JSON.parse(Buffer.from(b64Data, "base64url").toString("utf8"));
    if (Date.now() > payload.exp) return null; // Expired
    return payload;
  } catch {
    return null;
  }
}

/**
 * 1. validateVideoAccess (Callable Cloud Function)
 * Validates unique access token, updates views, and issues short-lived playback ticket.
 */
exports.validateVideoAccess = onCall({ cors: true }, async (request) => {
  const clientIp = request.rawRequest.ip || "unknown";

  // 1. Rate limiting check
  if (!checkRateLimit(clientIp)) {
    throw new HttpsError(
      "resource-exhausted",
      "Too many authorization attempts. Please wait a minute and try again."
    );
  }

  const rawToken = request.data?.token;
  if (!rawToken || typeof rawToken !== "string" || !rawToken.trim()) {
    throw new HttpsError("invalid-argument", "A valid video access token must be provided.");
  }

  // 2. Hash received token using SHA-256
  const tokenHash = hashToken(rawToken);

  try {
    // 3. Find matching accessLinks document in Firestore
    const linksSnapshot = await db
      .collection("accessLinks")
      .where("tokenHash", "==", tokenHash)
      .limit(1)
      .get();

    let linkDoc = null;
    if (!linksSnapshot.empty) {
      linkDoc = linksSnapshot.docs[0];
    } else {
      // Fallback: check by document ID (supports lowercase link_... and legacy uppercase tokens)
      const exactDoc = await db.collection("accessLinks").doc(rawToken.trim()).get();
      if (exactDoc.exists) {
        linkDoc = exactDoc;
      } else {
        const directDoc = await db.collection("accessLinks").doc(rawToken.trim().toUpperCase()).get();
        if (directDoc.exists) {
          linkDoc = directDoc;
        }
      }
    }

    if (!linkDoc || !linkDoc.exists) {
      throw new HttpsError("not-found", "Invalid video access link or token not found.");
    }

    const linkData = linkDoc.data();

    // 4. Verify link is active
    if (linkData.isActive !== true) {
      throw new HttpsError(
        "failed-precondition",
        "This video access link has been deactivated by the administrator."
      );
    }

    // 5. Verify expiration (if manually set)
    if (linkData.expiresAt) {
      const expirationDate = linkData.expiresAt.toDate();
      if (Date.now() > expirationDate.getTime()) {
        throw new HttpsError(
          "failed-precondition",
          "This video access link has expired."
        );
      }
    }

    // 6. Fetch and verify associated video
    const videoId = linkData.videoId;
    if (!videoId) {
      throw new HttpsError("internal", "Access link record is missing video reference.");
    }

    const videoRef = db.collection("videos").doc(videoId);
    const videoDoc = await videoRef.get();

    if (!videoDoc.exists) {
      throw new HttpsError("not-found", "The requested video does not exist.");
    }

    const videoData = videoDoc.data();

    // 7. Verify video is active
    if (videoData.isActive !== true) {
      throw new HttpsError(
        "failed-precondition",
        "The requested video has been deactivated by the administrator."
      );
    }

    // 8. Atomically count successful video view
    await videoRef.update({
      totalViews: FieldValue.increment(1),
    });

    // 9. Generate short-lived playback authorization ticket (15 min)
    const playbackPayload = {
      videoId: videoId,
      linkId: linkDoc.id,
      storagePath: videoData.storagePath,
    };
    const playbackToken = createPlaybackToken(playbackPayload);

    // 10. Generate authorized delivery URL (never expose permanent storage URL)
    let streamUrl;
    if (
      videoData.storagePath.startsWith("http://") ||
      videoData.storagePath.startsWith("https://")
    ) {
      // Direct CDN/HTTP media stream
      streamUrl = videoData.storagePath;
    } else {
      // Authenticated streaming proxy endpoint passing short-lived playback token
      const host = request.rawRequest.get("host") || "us-central1-gen-lang-client-0000740810.cloudfunctions.net";
      const protocol = request.rawRequest.secure ? "https" : "https";
      streamUrl = `${protocol}://${host}/streamVideo?playbackToken=${playbackToken}`;
    }

    return {
      success: true,
      videoId: videoId,
      title: videoData.title,
      description: videoData.description || "",
      thumbnailUrl: videoData.thumbnailPath || null,
      streamUrl: streamUrl,
      playbackToken: playbackToken,
      expiresAt: Date.now() + 15 * 60 * 1000,
    };
  } catch (err) {
    if (err instanceof HttpsError) throw err;
    console.error("validateVideoAccess error:", err);
    throw new HttpsError("internal", "Server error during authorization: " + err.message);
  }
});

/**
 * 2. streamVideo (Authenticated Streaming Proxy HTTP Endpoint)
 * Streams video bytes with HTTP 206 Partial Content support for ExoPlayer seeking.
 * Real-time revocation: Immediately blocks playback if admin deactivated the link or video.
 */
exports.streamVideo = onRequest({ cors: true }, async (req, res) => {
  try {
    const playbackToken = req.query.playbackToken || req.headers["x-playback-token"];
    if (!playbackToken) {
      res.status(401).send("Unauthorized: Missing playback authorization token.");
      return;
    }

    // 1. Verify token cryptographic signature and expiration
    const payload = verifyPlaybackToken(playbackToken);
    if (!payload) {
      res.status(403).send("Forbidden: Invalid or expired playback authorization token.");
      return;
    }

    const { videoId, linkId, storagePath } = payload;

    // 2. Real-time revocation check against Firestore backend
    const [linkDoc, videoDoc] = await Promise.all([
      db.collection("accessLinks").doc(linkId).get(),
      db.collection("videos").doc(videoId).get(),
    ]);

    if (!linkDoc.exists || linkDoc.data().isActive !== true) {
      res.status(403).send("Forbidden: This video access link has been revoked by admin.");
      return;
    }

    if (!videoDoc.exists || videoDoc.data().isActive !== true) {
      res.status(403).send("Forbidden: This video has been deactivated by admin.");
      return;
    }

    // 3. Stream from Cloud Storage bucket
    const bucket = storage.bucket();
    const cleanPath = storagePath.startsWith("gs://")
      ? storagePath.replace(/^gs:\/\/[^/]+\//, "")
      : storagePath;

    const file = bucket.file(cleanPath);
    const [exists] = await file.exists();
    if (!exists) {
      res.status(404).send("Video asset not found in storage.");
      return;
    }

    const [metadata] = await file.getMetadata();
    const fileSize = parseInt(metadata.size, 10);
    const contentType = metadata.contentType || "video/mp4";

    // 4. Handle HTTP Range Request (RFC 7233) for ExoPlayer scrubbing/seeking
    const range = req.headers.range;
    if (range) {
      const parts = range.replace(/bytes=/, "").split("-");
      const start = parseInt(parts[0], 10);
      const end = parts[1] ? parseInt(parts[1], 10) : fileSize - 1;

      if (start >= fileSize || end >= fileSize) {
        res.status(416).set("Content-Range", `bytes */${fileSize}`).end();
        return;
      }

      const chunkSize = end - start + 1;
      res.writeHead(206, {
        "Content-Range": `bytes ${start}-${end}/${fileSize}`,
        "Accept-Ranges": "bytes",
        "Content-Length": chunkSize,
        "Content-Type": contentType,
        "Cache-Control": "private, no-cache, no-store, must-revalidate",
        "Pragma": "no-cache",
      });

      file.createReadStream({ start, end }).pipe(res);
    } else {
      res.writeHead(200, {
        "Content-Length": fileSize,
        "Content-Type": contentType,
        "Accept-Ranges": "bytes",
        "Cache-Control": "private, no-cache, no-store, must-revalidate",
      });

      file.createReadStream().pipe(res);
    }
  } catch (error) {
    console.error("streamVideo error:", error);
    if (!res.headersSent) {
      res.status(500).send("Internal Streaming Error");
    }
  }
});

/**
 * 3. streamPublicVideo (HMAC-signed streaming endpoint for Telegram-uploaded & bot-generated videos)
 * Verifies cryptographic HMAC signature AND live Firestore videos/{videoId}.isActive status.
 */
exports.streamPublicVideo = onRequest({ cors: true }, async (req, res) => {
  try {
    await handleStreamPublicVideo({
      videoId: req.query.videoId,
      kind: req.query.kind,
      sig: req.query.sig,
      method: req.method,
      rangeHeader: req.headers.range,
      res,
      db,
      storage,
      playbackSecret: PLAYBACK_SECRET,
    });
  } catch (error) {
    console.error("streamPublicVideo error:", error);
    if (!res.headersSent) {
      res.status(500).send("Streaming error");
    }
  }
});

/**
 * 4. telegramWebhook (Telegram Creator Bot Webhook)
 * Accepts Creator login via Master Key (/login), receives Video / direct URL,
 * uploads full video to Storage + Firestore, cuts a 5-second preview clip via ffmpeg,
 * and replies in Telegram with the 5-sec clip + VidVora shareable watch link.
 */
exports.telegramWebhook = onRequest(
  {
    cors: false,
    memory: "1GiB",
    timeoutSeconds: 300,
    maxInstances: 10,
  },
  async (req, res) => {
    if (req.method !== "POST") {
      res.status(200).send("VidVora Telegram Creator Bot Webhook Active");
      return;
    }

    try {
      const { webhookSecret } = await resolveBotRuntimeConfig(db);
      if (webhookSecret) {
        const incomingSecret = String(req.get("X-Telegram-Bot-Api-Secret-Token") || "").trim();
        if (incomingSecret !== webhookSecret) {
          res.status(401).send("Unauthorized webhook secret");
          return;
        }
      }

      const host = req.get("host");
      const protocol = req.get("x-forwarded-proto") || "https";
      const functionBaseUrl = process.env.FUNCTION_BASE_URL || `${protocol}://${host}`;

      const result = await handleTelegramUpdate({
        update: req.body,
        db,
        storage,
        functionBaseUrl,
        playbackSecret: PLAYBACK_SECRET,
      });

      res.status(200).json(result);
    } catch (error) {
      console.error("telegramWebhook error:", error);
      // Respond 200 to Telegram after logging to avoid infinite webhook retry storms
      res.status(200).json({ ok: false, error: error.message });
    }
  }
);

/**
 * 4b. financeTelegramWebhook (Telegram Creator Finance Bot Webhook)
 * Allows Creators to check earnings, request UPI withdrawals (>= 500 INR), and track payout history,
 * and allows Admins to inspect pending payouts and approve/process/reject them.
 */
exports.financeTelegramWebhook = onRequest(
  {
    cors: false,
    memory: "512MiB",
    timeoutSeconds: 120,
    maxInstances: 10,
  },
  async (req, res) => {
    if (req.method !== "POST") {
      res.status(200).send("VidVora Creator Finance Bot Webhook Active");
      return;
    }

    try {
      const { webhookSecret } = await resolveFinanceBotRuntimeConfig(db);
      if (webhookSecret) {
        const incomingSecret = String(req.get("X-Telegram-Bot-Api-Secret-Token") || "").trim();
        if (incomingSecret !== webhookSecret) {
          res.status(401).send("Unauthorized webhook secret");
          return;
        }
      }

      const result = await handleFinanceTelegramUpdate({
        update: req.body,
        db,
      });

      res.status(200).json(result);
    } catch (error) {
      console.error("financeTelegramWebhook error:", error);
      res.status(200).json({ ok: false, error: error.message });
    }
  }
);

/**
 * 5. sharePage (Dynamic Open Graph HTML page for WhatsApp/Telegram/FB/Instagram/X previews)
 */
exports.sharePage = onRequest({ cors: true }, async (req, res) => {
  try {
    const host = req.get("x-forwarded-host") || req.get("host") || "gen-lang-client-0000740810.web.app";
    const protocol = req.get("x-forwarded-proto") || "https";
    const hostBaseUrl = (process.env.PUBLIC_BASE_URL || `${protocol}://${host}`).replace(/\/+$/, "");

    await handleSharePageRequest({
      reqUrl: req.originalUrl || req.url,
      codeParam: req.query.link || req.query.v || req.query.video || req.query.token,
      hostBaseUrl,
      method: req.method,
      res,
      db,
    });
  } catch (error) {
    console.error("sharePage error:", error);
    if (!res.headersSent) {
      res.status(500).send("Share preview error");
    }
  }
});

/**
 * 6. shareThumb (Public JPEG/PNG endpoint for og:image across all social crawlers)
 */
exports.shareThumb = onRequest({ cors: true }, async (req, res) => {
  try {
    await handleShareThumbnailRequest({
      videoId: req.query.videoId,
      link: req.query.link,
      method: req.method,
      res,
      db,
      storage,
    });
  } catch (error) {
    console.error("shareThumb error:", error);
    if (!res.headersSent) {
      res.status(500).send("Thumbnail error");
    }
  }
});
