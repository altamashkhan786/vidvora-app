const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { execFile } = require("child_process");
const { promisify } = require("util");

const execFileAsync = promisify(execFile);

const DEFAULT_APK_URL = "https://d1mn8mrvlzisg5.cloudfront.net/vidvora.apk";
const DEFAULT_FALLBACK_BANNER_PATH = path.join(__dirname, "vidvora-og-banner.png");
const MAX_CRISP_THUMB_CACHE = 64;
const crispThumbCache = new Map();

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

function getImageDimensions(buffer, contentType = "") {
  if (!Buffer.isBuffer(buffer) || buffer.length < 16) return null;

  // PNG: 8-byte signature + IHDR width(16..19), height(20..23)
  if (
    buffer[0] === 0x89 &&
    buffer[1] === 0x50 &&
    buffer[2] === 0x4e &&
    buffer[3] === 0x47 &&
    buffer.length >= 24
  ) {
    const width = buffer.readUInt32BE(16);
    const height = buffer.readUInt32BE(20);
    if (width > 0 && height > 0) return { width, height, format: "png" };
  }

  // JPEG: scan SOF0..SOF15 markers (excluding DHT 0xC4, JPG 0xC8, DAC 0xCC)
  if (buffer[0] === 0xff && buffer[1] === 0xd8) {
    let offset = 2;
    while (offset + 8 < buffer.length) {
      if (buffer[offset] !== 0xff) {
        offset += 1;
        continue;
      }
      const marker = buffer[offset + 1];
      if (marker === 0xff) {
        offset += 1;
        continue;
      }
      if (marker === 0xd8 || marker === 0xd9 || (marker >= 0xd0 && marker <= 0xd7) || marker === 0x01) {
        offset += 2;
        continue;
      }
      if (offset + 4 >= buffer.length) break;
      const segLen = buffer.readUInt16BE(offset + 2);
      if (segLen < 2) break;
      const isSof =
        (marker >= 0xc0 && marker <= 0xcf) &&
        marker !== 0xc4 &&
        marker !== 0xc8 &&
        marker !== 0xcc;
      if (isSof && offset + 9 <= buffer.length) {
        const height = buffer.readUInt16BE(offset + 5);
        const width = buffer.readUInt16BE(offset + 7);
        if (width > 0 && height > 0) return { width, height, format: "jpeg" };
      }
      offset += 2 + segLen;
    }
  }

  // WebP: RIFF....WEBP
  if (
    buffer.length >= 30 &&
    buffer.toString("ascii", 0, 4) === "RIFF" &&
    buffer.toString("ascii", 8, 12) === "WEBP"
  ) {
    const chunk = buffer.toString("ascii", 12, 16);
    if (chunk === "VP8X" && buffer.length >= 30) {
      const width = 1 + (buffer[24] | (buffer[25] << 8) | (buffer[26] << 16));
      const height = 1 + (buffer[27] | (buffer[28] << 8) | (buffer[29] << 16));
      if (width > 0 && height > 0) return { width, height, format: "webp" };
    }
    if (chunk === "VP8 " && buffer.length >= 30 && buffer[23] === 0x9d && buffer[24] === 0x01 && buffer[25] === 0x2a) {
      const width = buffer.readUInt16LE(26) & 0x3fff;
      const height = buffer.readUInt16LE(28) & 0x3fff;
      if (width > 0 && height > 0) return { width, height, format: "webp" };
    }
    if (chunk === "VP8L" && buffer.length >= 25 && buffer[20] === 0x2f) {
      const bits = buffer.readUInt32LE(21);
      const width = (bits & 0x3fff) + 1;
      const height = ((bits >> 14) & 0x3fff) + 1;
      if (width > 0 && height > 0) return { width, height, format: "webp" };
    }
  }

  if (String(contentType).toLowerCase().includes("svg")) {
    return { width: 1200, height: 630, format: "svg" };
  }
  return null;
}

function makeEven(n) {
  const rounded = Math.max(2, Math.round(n));
  return rounded % 2 === 0 ? rounded : rounded + 1;
}

function computeHdTargetDimensions(width, height) {
  if (!width || !height || width <= 0 || height <= 0) {
    return { width: 1280, height: 720, needsUpscale: false };
  }
  const longestSide = Math.max(width, height);
  if (longestSide >= 1080) {
    return { width, height, needsUpscale: false };
  }
  const targetLongest = 1280;
  const scale = targetLongest / longestSide;
  return {
    width: makeEven(width * scale),
    height: makeEven(height * scale),
    needsUpscale: true,
  };
}

async function ensureCrispShareImage(buffer, contentType = "image/jpeg") {
  if (!Buffer.isBuffer(buffer) || buffer.length === 0) {
    return { buffer, contentType, width: 1280, height: 720 };
  }
  const dims = getImageDimensions(buffer, contentType);
  if (!dims) {
    return { buffer, contentType, width: 1280, height: 720 };
  }

  const target = computeHdTargetDimensions(dims.width, dims.height);
  if (!target.needsUpscale) {
    return { buffer, contentType, width: dims.width, height: dims.height };
  }

  const cacheKey = crypto
    .createHash("sha256")
    .update(buffer)
    .digest("hex")
    .slice(0, 20);
  const cached = crispThumbCache.get(cacheKey);
  if (cached) {
    return cached;
  }

  const ext = dims.format === "png" ? "png" : dims.format === "webp" ? "webp" : "jpg";
  let tmpDir = "";
  try {
    tmpDir = await fs.promises.mkdtemp(path.join(os.tmpdir(), "vidvora-og-"));
    const inFile = path.join(tmpDir, `in.${ext}`);
    const outFile = path.join(tmpDir, "out.jpg");
    await fs.promises.writeFile(inFile, buffer);

    const ffmpegBin = getFfmpegBinary();
    await execFileAsync(
      ffmpegBin,
      [
        "-y",
        "-i",
        inFile,
        "-vf",
        `scale=${target.width}:${target.height}:flags=lanczos,unsharp=5:5:0.75:5:5:0.0`,
        "-q:v",
        "2",
        outFile,
      ],
      { timeout: 10_000, maxBuffer: 4 * 1024 * 1024 }
    );

    if (fs.existsSync(outFile)) {
      const outBuf = await fs.promises.readFile(outFile);
      if (outBuf.length > 0) {
        const result = {
          buffer: outBuf,
          contentType: "image/jpeg",
          width: target.width,
          height: target.height,
        };
        if (crispThumbCache.size >= MAX_CRISP_THUMB_CACHE) {
          const oldestKey = crispThumbCache.keys().next().value;
          crispThumbCache.delete(oldestKey);
        }
        crispThumbCache.set(cacheKey, result);
        return result;
      }
    }
  } catch (err) {
    console.warn("ensureCrispShareImage fallback to source image:", err.message);
  } finally {
    if (tmpDir) {
      fs.promises.rm(tmpDir, { recursive: true, force: true }).catch(() => {});
    }
  }

  return { buffer, contentType, width: dims.width, height: dims.height };
}

function sendImageResponse(res, { buffer, contentType, method = "GET", maxAgeSeconds = 86400 }) {
  const etag = `"${crypto.createHash("sha256").update(buffer).digest("hex").slice(0, 16)}"`;
  res.writeHead(200, {
    "Content-Type": contentType || "image/jpeg",
    "Content-Length": buffer.length,
    ETag: etag,
    "Cache-Control": `public, max-age=${maxAgeSeconds}`,
    "Access-Control-Allow-Origin": "*",
    "X-Content-Type-Options": "nosniff",
  });
  if (String(method || "GET").toUpperCase() === "HEAD") {
    res.end();
  } else {
    res.end(buffer);
  }
}

function hashToken(rawToken) {
  const clean = String(rawToken || "").trim().toUpperCase();
  return crypto.createHash("sha256").update(clean, "utf8").digest("hex");
}

function escapeHtml(value) {
  return String(value || "")
    .replace(/&/g, "&amp;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

function normalizeShareBaseUrl(rawUrl) {
  const clean = String(rawUrl || "").trim();
  if (!clean) return "";
  if (/[?&](link|v|video|token)=$/i.test(clean)) {
    return clean;
  }
  const withScheme = /^https?:\/\//i.test(clean) ? clean : `https://${clean}`;
  try {
    const parsed = new URL(withScheme);
    if (!parsed.pathname || parsed.pathname === "/") {
      parsed.pathname = "/share";
    }
    if (!parsed.searchParams.has("link")) {
      return `${parsed.origin}${parsed.pathname}${parsed.search ? parsed.search + "&" : "?"}link=`;
    }
    return parsed.toString();
  } catch (_) {
    return clean;
  }
}

function extractShareCode({ urlObj, codeParam }) {
  if (codeParam && String(codeParam).trim()) {
    return String(codeParam).trim();
  }
  if (!urlObj) return "";
  const fromQuery =
    urlObj.searchParams.get("link") ||
    urlObj.searchParams.get("v") ||
    urlObj.searchParams.get("video") ||
    urlObj.searchParams.get("token") ||
    "";
  if (fromQuery.trim()) return fromQuery.trim();

  const segments = String(urlObj.pathname || "")
    .split("/")
    .map((s) => s.trim())
    .filter(Boolean);
  if (segments.length >= 2 && (segments[0] === "watch" || segments[0] === "share")) {
    return decodeURIComponent(segments[1]);
  }
  return "";
}

function decodeDataImageUri(rawUri) {
  const str = String(rawUri || "").trim();
  const match = str.match(/^data:(image\/[a-zA-Z0-9.+-]+);base64,([A-Za-z0-9+/=\s]+)$/i);
  if (!match) return null;
  try {
    const contentType = match[1].toLowerCase() === "image/jpg" ? "image/jpeg" : match[1].toLowerCase();
    const buffer = Buffer.from(match[2].replace(/\s+/g, ""), "base64");
    if (!buffer || buffer.length === 0) return null;
    return { contentType, buffer };
  } catch (_) {
    return null;
  }
}

async function resolveShareTarget(db, rawCode) {
  const cleanCode = String(rawCode || "").trim();
  if (!cleanCode || !db || typeof db.collection !== "function") {
    return { found: false, isActive: false, code: cleanCode };
  }

  let linkDoc = null;
  let videoId = "";

  try {
    const tokenHash = hashToken(cleanCode);
    const linksCol = db.collection("accessLinks");
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
    } else if (!linkDoc && typeof linksCol.document === "function") {
      const exactDoc = await linksCol.document(cleanCode).get();
      if (exactDoc && exactDoc.exists) {
        linkDoc = exactDoc;
      } else {
        const upperDoc = await linksCol.document(cleanCode.toUpperCase()).get();
        if (upperDoc && upperDoc.exists) {
          linkDoc = upperDoc;
        }
      }
    }
  } catch (err) {
    console.warn("resolveShareTarget link lookup warning:", err.message);
  }

  let linkIsActive = true;
  let linkId = "";
  if (linkDoc && linkDoc.exists) {
    const lData = linkDoc.data() || {};
    linkId = linkDoc.id || lData.linkId || "";
    videoId = String(lData.videoId || "").trim();
    linkIsActive = lData.isActive === true;
  } else {
    videoId = cleanCode;
  }

  if (!videoId) {
    return { found: false, isActive: false, code: cleanCode };
  }

  try {
    const videosCol = db.collection("videos");
    const vidDocRef = typeof videosCol.doc === "function" ? videosCol.doc(videoId) : videosCol.document(videoId);
    const vidDoc = await vidDocRef.get();
    if (!vidDoc || !vidDoc.exists) {
      return { found: false, isActive: false, code: cleanCode, videoId };
    }
    const vData = vidDoc.data() || {};
    const isActive = linkIsActive && vData.isActive === true;
    return {
      found: true,
      isActive,
      videoId: vidDoc.id || vData.videoId || videoId,
      linkId,
      code: cleanCode,
      title: String(vData.title || "VidVora Video").trim() || "VidVora Video",
      description: String(vData.description || "").trim(),
      thumbnailPath: vData.thumbnailPath ? String(vData.thumbnailPath).trim() : "",
      uploaderName: String(vData.uploaderName || "VidVora Creator").trim(),
    };
  } catch (err) {
    console.warn("resolveShareTarget video lookup warning:", err.message);
    return { found: false, isActive: false, code: cleanCode, videoId };
  }
}

async function sendFallbackBanner(res, fallbackImagePath = DEFAULT_FALLBACK_BANNER_PATH, method = "GET") {
  try {
    const targetPath = fs.existsSync(fallbackImagePath)
      ? fallbackImagePath
      : path.join(__dirname, "..", "public", "vidvora-og-banner.png");
    if (fs.existsSync(targetPath)) {
      const buf = await fs.promises.readFile(targetPath);
      sendImageResponse(res, {
        buffer: buf,
        contentType: "image/png",
        method,
        maxAgeSeconds: 3600,
      });
      return;
    }
  } catch (_) {
    // Fall through to 1x1 transparent PNG
  }
  const tinyPng = Buffer.from(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
    "base64"
  );
  sendImageResponse(res, {
    buffer: tinyPng,
    contentType: "image/png",
    method,
    maxAgeSeconds: 300,
  });
}

async function handleShareThumbnailRequest({
  videoId,
  link,
  method = "GET",
  res,
  db,
  storage,
  fallbackImagePath = DEFAULT_FALLBACK_BANNER_PATH,
}) {
  let target = null;

  const cleanVideoId = String(videoId || "").trim();
  const cleanLink = String(link || "").trim();

  if (cleanVideoId && db && typeof db.collection === "function") {
    try {
      const videosCol = db.collection("videos");
      const vidRef = typeof videosCol.doc === "function" ? videosCol.doc(cleanVideoId) : videosCol.document(cleanVideoId);
      const vidDoc = await vidRef.get();
      if (vidDoc && vidDoc.exists) {
        const vData = vidDoc.data() || {};
        target = {
          found: true,
          isActive: vData.isActive === true,
          videoId: vidDoc.id || vData.videoId || cleanVideoId,
          thumbnailPath: vData.thumbnailPath ? String(vData.thumbnailPath).trim() : "",
        };
      }
    } catch (_) {
      // Fall through to resolveShareTarget
    }
  }

  if (!target && (cleanLink || cleanVideoId)) {
    target = await resolveShareTarget(db, cleanLink || cleanVideoId);
  }

  if (target && target.found && target.isActive) {
    const rawThumb = String(target.thumbnailPath || "").trim();

    // 1. Inline base64 data URI -> decode and normalize to crisp HD (>=1200px) JPEG/PNG bytes
    if (rawThumb.startsWith("data:image/")) {
      const decoded = decodeDataImageUri(rawThumb);
      if (decoded) {
        const crisp = await ensureCrispShareImage(decoded.buffer, decoded.contentType);
        sendImageResponse(res, {
          buffer: crisp.buffer,
          contentType: crisp.contentType,
          method,
        });
        return;
      }
    }

    // 2. Firebase Storage file (`videos/{videoId}/thumbnail.jpg` or explicit storage path)
    if (storage && typeof storage.bucket === "function") {
      try {
        const candidatePaths = [];
        if (rawThumb && !rawThumb.startsWith("http://") && !rawThumb.startsWith("https://") && !rawThumb.startsWith("data:")) {
          candidatePaths.push(rawThumb.startsWith("gs://") ? rawThumb.replace(/^gs:\/\/[^/]+\//, "") : rawThumb);
        }
        candidatePaths.push(`videos/${target.videoId}/thumbnail.jpg`);

        for (const candidate of candidatePaths) {
          const file = storage.bucket().file(candidate);
          const [exists] = await file.exists();
          if (exists) {
            const [metadata] = await file.getMetadata();
            const [buf] = await file.download();
            const crisp = await ensureCrispShareImage(buf, metadata.contentType || "image/jpeg");
            sendImageResponse(res, {
              buffer: crisp.buffer,
              contentType: crisp.contentType,
              method,
            });
            return;
          }
        }
      } catch (err) {
        console.warn("handleShareThumbnailRequest storage fallback:", err.message);
      }
    }

    // 3. External HTTPS thumbnail URL (excluding self-loops to /shareThumb)
    if ((rawThumb.startsWith("https://") || rawThumb.startsWith("http://")) && !rawThumb.includes("/shareThumb")) {
      try {
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), 5000);
        const upstream = await fetch(rawThumb, { signal: controller.signal });
        clearTimeout(timer);
        if (upstream.ok) {
          const contentType = upstream.headers.get("content-type") || "image/jpeg";
          if (contentType.toLowerCase().startsWith("image/")) {
            const arr = await upstream.arrayBuffer();
            const buf = Buffer.from(arr);
            if (buf.length > 0) {
              const crisp = await ensureCrispShareImage(buf, contentType);
              sendImageResponse(res, {
                buffer: crisp.buffer,
                contentType: crisp.contentType,
                method,
              });
              return;
            }
          }
        }
      } catch (err) {
        console.warn("handleShareThumbnailRequest HTTP fetch warning:", err.message);
      }
    }
  }

  await sendFallbackBanner(res, fallbackImagePath, method);
}

function buildShareHtml({
  code,
  title,
  description,
  ogImageUrl,
  ogImageType,
  ogImageWidth = 1280,
  ogImageHeight = 720,
  canonicalUrl,
  apkDownloadUrl,
}) {
  const safeTitle = escapeHtml(title);
  const safeDesc = escapeHtml(description);
  const safeOgImage = escapeHtml(ogImageUrl);
  const safeOgUrl = escapeHtml(canonicalUrl);
  const safeApkUrl = escapeHtml(apkDownloadUrl || DEFAULT_APK_URL);
  const safeCodeDisplay = escapeHtml(code || "VidVora Direct Player");
  const encodedCode = encodeURIComponent(code || "");

  const customUri = code ? `vidvora://watch?link=${encodedCode}` : "vidvora://watch";
  const intentBase = canonicalUrl.split("?")[0];
  const fallbackPageUrl = code
    ? `${intentBase}?link=${encodedCode}&install=1`
    : `${intentBase}?install=1`;
  const intentUri = code
    ? `intent://watch?link=${encodedCode}#Intent;scheme=vidvora;package=com.aistudio.vidvora.vstr;S.browser_fallback_url=${encodeURIComponent(fallbackPageUrl)};end`
    : "intent://watch#Intent;scheme=vidvora;package=com.aistudio.vidvora.vstr;end";

  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>${safeTitle}</title>
  <meta property="og:type" content="video.other" />
  <meta property="og:site_name" content="VidVora" />
  <meta property="og:title" content="${safeTitle}" />
  <meta property="og:description" content="${safeDesc}" />
  <meta property="og:url" content="${safeOgUrl}" />
  <meta property="og:image" content="${safeOgImage}" />
  <meta property="og:image:url" content="${safeOgImage}" />
  <meta property="og:image:secure_url" content="${safeOgImage}" />
  <meta property="og:image:type" content="${escapeHtml(ogImageType || "image/jpeg")}" />
  <meta property="og:image:width" content="${String(ogImageWidth)}" />
  <meta property="og:image:height" content="${String(ogImageHeight)}" />
  <meta property="og:image:alt" content="${safeTitle}" />
  <meta name="twitter:card" content="summary_large_image" />
  <meta name="twitter:title" content="${safeTitle}" />
  <meta name="twitter:description" content="${safeDesc}" />
  <meta name="twitter:image" content="${safeOgImage}" />
  <meta name="twitter:image:src" content="${safeOgImage}" />
  <link rel="image_src" href="${safeOgImage}" />
  <meta name="description" content="${safeDesc}" />
  <meta name="theme-color" content="#070d1a" />
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      background: radial-gradient(circle at top, #0c2347 0%, #070d1a 60%);
      color: #f5f7fb;
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 20px;
    }
    .card {
      width: 100%;
      max-width: 440px;
      background: #0d1526;
      border: 1px solid #21355c;
      border-radius: 22px;
      padding: 22px;
      text-align: center;
      box-shadow: 0 20px 50px rgba(0, 0, 0, 0.65);
    }
    .badge {
      display: inline-block;
      background: rgba(0, 106, 252, 0.18);
      color: #01e7fd;
      border: 1px solid rgba(1, 200, 252, 0.45);
      font-size: 11px;
      font-weight: 800;
      letter-spacing: 1px;
      padding: 5px 12px;
      border-radius: 999px;
      margin-bottom: 14px;
    }
    .thumb-wrap {
      position: relative;
      width: 100%;
      aspect-ratio: 16 / 9;
      border-radius: 14px;
      overflow: hidden;
      background: #070d1a;
      border: 1px solid #21355c;
      margin-bottom: 16px;
    }
    .thumb-wrap img {
      width: 100%;
      height: 100%;
      object-fit: cover;
      display: block;
    }
    .play-pill {
      position: absolute;
      inset: 0;
      display: flex;
      align-items: center;
      justify-content: center;
      background: rgba(7, 13, 26, 0.25);
    }
    .play-circle {
      width: 56px;
      height: 56px;
      border-radius: 999px;
      background: rgba(0, 106, 252, 0.92);
      border: 2px solid #01e7fd;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 22px;
      color: #ffffff;
    }
    h1 {
      font-size: 20px;
      font-weight: 900;
      margin-bottom: 8px;
      color: #ffffff;
      line-height: 1.3;
    }
    p {
      font-size: 13px;
      color: #b8c7e0;
      line-height: 1.5;
      margin-bottom: 16px;
    }
    .code-box {
      background: #070d1a;
      border: 1px solid #21355c;
      border-radius: 12px;
      padding: 10px 12px;
      font-family: monospace;
      font-size: 13px;
      color: #01e7fd;
      word-break: break-all;
      margin-bottom: 16px;
    }
    .btn {
      display: block;
      width: 100%;
      padding: 14px 18px;
      border-radius: 14px;
      font-size: 15px;
      font-weight: 800;
      text-decoration: none;
      cursor: pointer;
      border: none;
      margin-bottom: 10px;
    }
    .btn-primary {
      background: linear-gradient(135deg, #01c8fc, #006afc, #0112e9);
      color: #ffffff;
    }
    .btn-secondary {
      background: #15213b;
      color: #01e7fd;
      border: 1px solid #263d69;
    }
    .btn-download {
      background: #111b30;
      color: #ffffff;
      border: 1px solid #263d69;
      font-size: 14px;
      font-weight: 700;
    }
    .note {
      margin-top: 8px;
      font-size: 12px;
      color: #7d92b8;
      line-height: 1.45;
    }
  </style>
</head>
<body>
  <div class="card">
    <div class="badge">VIDVORA PLAYER</div>
    <a href="${escapeHtml(intentUri)}" class="thumb-wrap" id="thumbLink" style="display:block;">
      <img src="${safeOgImage}" alt="${safeTitle}" />
      <div class="play-pill"><div class="play-circle">&#9654;</div></div>
    </a>
    <h1 id="pageTitle">${safeTitle}</h1>
    <p id="pageDesc">${safeDesc}</p>
    <div class="code-box" id="linkCode">${safeCodeDisplay}</div>
    <a id="downloadApkBtn" class="btn btn-download" href="${safeApkUrl}" download="VidVora.apk" style="display:none;">
      Download VidVora App (APK)
    </a>
    <a id="openIntentBtn" class="btn btn-primary" href="${escapeHtml(intentUri)}">Watch in VidVora App</a>
    <button id="copyBtn" class="btn btn-secondary" type="button">Copy Video Link</button>
    <a id="downloadSecondaryBtn" class="btn btn-download" href="${safeApkUrl}" download="VidVora.apk">
      Don't have the app? Download APK
    </a>
    <div class="note" id="statusNote">If the app doesn't open automatically, tap the blue button above.</div>
  </div>

  <script>
    (function () {
      var params = new URLSearchParams(window.location.search);
      var code = ${JSON.stringify(code || "")};
      var isInstallFallback = params.get("install") === "1";
      var cleanShareUrl = ${JSON.stringify(canonicalUrl)};
      var apkUrl = ${JSON.stringify(apkDownloadUrl || DEFAULT_APK_URL)};
      var intentUri = ${JSON.stringify(intentUri)};
      var customUri = ${JSON.stringify(customUri)};

      var pageTitleEl = document.getElementById("pageTitle");
      var pageDescEl = document.getElementById("pageDesc");
      var openBtn = document.getElementById("openIntentBtn");
      var thumbLink = document.getElementById("thumbLink");
      var downloadPrimaryBtn = document.getElementById("downloadApkBtn");
      var downloadSecondaryBtn = document.getElementById("downloadSecondaryBtn");
      var copyBtn = document.getElementById("copyBtn");
      var statusNote = document.getElementById("statusNote");

      function handleApkDownloadClick(e) {
        e.preventDefault();
        statusNote.textContent = "Checking APK file...";
        fetch(apkUrl, { method: "HEAD" })
          .then(function (res) {
            if (res.ok) {
              statusNote.textContent = "Downloading VidVora APK...";
              window.location.href = apkUrl;
            } else {
              statusNote.textContent = "APK abhi server par upload nahi hua hai. Admin se VidVora APK mangein.";
            }
          })
          .catch(function () {
            window.location.href = apkUrl;
          });
      }

      downloadPrimaryBtn.addEventListener("click", handleApkDownloadClick);
      downloadSecondaryBtn.addEventListener("click", handleApkDownloadClick);

      function showInstallView() {
        pageTitleEl.textContent = "VidVora App Install Karein";
        pageDescEl.textContent = "Video dekhne ke liye pehle niche button se VidVora app download & install karein.";
        downloadPrimaryBtn.style.display = "block";
        downloadPrimaryBtn.className = "btn btn-primary";
        openBtn.className = "btn btn-download";
        openBtn.textContent = "Already Installed? Open Video";
        downloadSecondaryBtn.style.display = "none";
        statusNote.textContent = "Install karne ke baad wapas is link par click karein — video direct play ho jayega.";
      }

      var isAndroid = /Android/i.test(navigator.userAgent || "");
      openBtn.href = isAndroid ? intentUri : customUri;
      thumbLink.href = isAndroid ? intentUri : customUri;

      if (isInstallFallback) {
        showInstallView();
      }

      copyBtn.addEventListener("click", function () {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(cleanShareUrl).then(function () {
            statusNote.textContent = "Link copied!";
          });
        } else {
          statusNote.textContent = "Copy this link: " + cleanShareUrl;
        }
      });

      if (code && isAndroid && !isInstallFallback) {
        setTimeout(function () {
          window.location.href = intentUri;
        }, 120);
      }
    })();
  </script>
</body>
</html>`;
}

async function handleSharePageRequest({
  reqUrl,
  codeParam,
  hostBaseUrl,
  apkDownloadUrl = DEFAULT_APK_URL,
  method = "GET",
  res,
  db,
}) {
  const cleanHost = String(hostBaseUrl || "").replace(/\/+$/, "");
  let urlObj = null;
  try {
    urlObj = new URL(reqUrl || "/share", cleanHost || "https://localhost");
  } catch (_) {
    urlObj = null;
  }

  const code = extractShareCode({ urlObj, codeParam });
  const target = code ? await resolveShareTarget(db, code) : { found: false, isActive: false };

  const activeVideo = Boolean(target && target.found && target.isActive);
  const title = "VidVora — Watch Video";
  const description = "Tap to watch this video in VidVora App.";

  const hasVideoThumb = Boolean(activeVideo && target.thumbnailPath);
  const thumbVersion = hasVideoThumb
    ? crypto.createHash("sha256").update(target.thumbnailPath).digest("hex").slice(0, 8)
    : "hd2";
  const ogImageUrl = activeVideo
    ? `${cleanHost}/shareThumb?videoId=${encodeURIComponent(target.videoId)}&v=${thumbVersion}`
    : `${cleanHost}/shareThumb?v=${thumbVersion}`;

  let ogImageType = "image/png";
  let ogImageWidth = 1200;
  let ogImageHeight = 630;
  if (hasVideoThumb) {
    ogImageWidth = 1280;
    ogImageHeight = 720;
    ogImageType = "image/jpeg";
    if (target.thumbnailPath.startsWith("data:image/")) {
      const decoded = decodeDataImageUri(target.thumbnailPath);
      if (decoded) {
        const dims = getImageDimensions(decoded.buffer, decoded.contentType);
        if (dims) {
          const hdDims = computeHdTargetDimensions(dims.width, dims.height);
          ogImageWidth = hdDims.width;
          ogImageHeight = hdDims.height;
          if (!hdDims.needsUpscale) {
            ogImageType = decoded.contentType;
          }
        }
      }
    }
  }

  const canonicalUrl = code
    ? `${cleanHost}/share?link=${encodeURIComponent(code)}`
    : `${cleanHost}/share`;

  const html = buildShareHtml({
    code,
    title,
    description,
    ogImageUrl,
    ogImageType,
    ogImageWidth,
    ogImageHeight,
    canonicalUrl,
    apkDownloadUrl,
  });

  const htmlBuf = Buffer.from(html, "utf8");
  res.writeHead(200, {
    "Content-Type": "text/html; charset=utf-8",
    "Content-Length": htmlBuf.length,
    "Cache-Control": "public, max-age=120",
  });
  if (String(method || "GET").toUpperCase() === "HEAD") {
    res.end();
  } else {
    res.end(htmlBuf);
  }
}

module.exports = {
  DEFAULT_APK_URL,
  hashToken,
  normalizeShareBaseUrl,
  extractShareCode,
  decodeDataImageUri,
  getImageDimensions,
  computeHdTargetDimensions,
  ensureCrispShareImage,
  resolveShareTarget,
  handleShareThumbnailRequest,
  handleSharePageRequest,
  buildShareHtml,
};
