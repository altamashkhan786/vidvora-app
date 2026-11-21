const fs = require("fs");
const path = require("path");
const { DEFAULT_APK_URL } = require("./sharePreview");

const DEFAULT_APP_VERSION_NAME = "1.9";
const DEFAULT_APP_VERSION_CODE = 10;
const APP_UPDATE_DOC_ID = "appUpdate";
const REMOTE_APK_PROBE_TTL_MS = 5 * 60 * 1000;
const remoteApkProbeCache = new Map();

function resolveLocalReleaseApkPath() {
  const candidates = [
    path.join(__dirname, "..", "release", "vidvora.apk"),
    path.join(__dirname, "..", "release", "VidVora.apk"),
    path.join(__dirname, "release", "vidvora.apk"),
    path.join(__dirname, "assets", "vidvora.apk"),
  ];
  for (const candidate of candidates) {
    try {
      const stat = fs.statSync(candidate);
      if (stat.isFile() && stat.size > 100_000) {
        return { filePath: candidate, size: stat.size };
      }
    } catch (_) {
      // Try next candidate
    }
  }
  return null;
}

function normalizeDirectApkUrl(rawUrl, fallback = DEFAULT_APK_URL) {
  const clean = sanitizeHttpUrl(rawUrl, "");
  if (!clean) return fallback;
  try {
    const parsed = new URL(clean);
    const host = parsed.hostname.toLowerCase();
    // Auto-convert standard Google Drive share links to direct APK download URLs
    if (host === "drive.google.com") {
      const fileMatch = parsed.pathname.match(/\/file\/d\/([a-zA-Z0-9_-]+)/);
      const id = (fileMatch && fileMatch[1]) || parsed.searchParams.get("id") || "";
      if (id) {
        return `https://drive.usercontent.google.com/download?id=${encodeURIComponent(id)}&export=download&confirm=t`;
      }
    }
    // Auto-convert Dropbox preview links (?dl=0) to direct download (?dl=1)
    if (host === "www.dropbox.com" || host === "dropbox.com") {
      parsed.searchParams.set("dl", "1");
      return parsed.toString();
    }
    return parsed.toString();
  } catch (_) {
    return fallback;
  }
}

function resolveGitHubReleaseApkUrl(env = process.env) {
  const explicit = sanitizeHttpUrl(env?.GITHUB_APK_URL || "", "");
  if (explicit) return explicit;
  const repoRaw = String(env?.RENDER_GIT_REPO_URL || env?.GITHUB_REPO_URL || "").trim();
  if (!repoRaw) return "";
  try {
    const parsed = new URL(repoRaw.replace(/\.git$/i, "").replace(/\/+$/, ""));
    if (parsed.hostname.toLowerCase() !== "github.com") return "";
    const parts = parsed.pathname.split("/").filter(Boolean);
    if (parts.length < 2) return "";
    return `https://github.com/${parts[0]}/${parts[1]}/releases/latest/download/VidVora.apk`;
  } catch (_) {
    return "";
  }
}

async function isRemoteApkReachable(apkUrl, fetchImpl = typeof fetch === "function" ? fetch : null) {
  const cleanUrl = sanitizeHttpUrl(apkUrl, "");
  if (!cleanUrl || !fetchImpl) return false;

  const now = Date.now();
  const cached = remoteApkProbeCache.get(cleanUrl);
  if (cached && now - cached.checkedAt < REMOTE_APK_PROBE_TTL_MS) {
    return cached.ok;
  }

  let ok = false;
  try {
    const resp = await fetchImpl(cleanUrl, {
      method: "HEAD",
      redirect: "follow",
      signal: typeof AbortSignal !== "undefined" && AbortSignal.timeout ? AbortSignal.timeout(4500) : undefined,
    });
    const contentType = String(resp.headers?.get?.("content-type") || "").toLowerCase();
    ok = Boolean(resp && resp.ok && !contentType.includes("xml") && !contentType.includes("text/html"));
  } catch (_) {
    ok = false;
  }

  if (remoteApkProbeCache.size > 20) {
    remoteApkProbeCache.clear();
  }
  remoteApkProbeCache.set(cleanUrl, { ok, checkedAt: now });
  return ok;
}

function escapeHtml(value) {
  return String(value == null ? "" : value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function sanitizeHttpUrl(rawUrl, fallback = DEFAULT_APK_URL) {
  const clean = String(rawUrl || "").trim();
  if (!clean) return fallback;
  try {
    const parsed = new URL(clean);
    if (parsed.protocol === "https:" || parsed.protocol === "http:") {
      return parsed.toString();
    }
  } catch (_) {
    // Fall back to verified default APK URL
  }
  return fallback;
}

async function resolveAppDownloadMetadata(db) {
  let versionName = DEFAULT_APP_VERSION_NAME;
  let versionCode = DEFAULT_APP_VERSION_CODE;
  let apkUrl = DEFAULT_APK_URL;
  let releaseNotes = "";

  if (db && typeof db.collection === "function") {
    try {
      const snap = await db.collection("appConfig").doc(APP_UPDATE_DOC_ID).get();
      if (snap && snap.exists) {
        const data = snap.data() || {};
        const dbVerName = String(data.latestVersionName || "").trim();
        const dbVerCode = Number(data.latestVersionCode);
        const dbApkUrl = normalizeDirectApkUrl(data.apkUrl, "");
        const dbNotes = String(data.releaseNotes || data.message || data.updateMessage || "").trim();
        if (dbVerName) versionName = dbVerName.slice(0, 24);
        if (Number.isFinite(dbVerCode) && dbVerCode >= 1) versionCode = Math.floor(dbVerCode);
        if (dbApkUrl) apkUrl = dbApkUrl;
        if (dbNotes) releaseNotes = dbNotes.slice(0, 600);
      }
    } catch (err) {
      console.warn("resolveAppDownloadMetadata fallback:", err?.message || err);
    }
  }

  return {
    versionName,
    versionCode,
    apkUrl,
    releaseNotes,
  };
}

function renderApkTemporarilyUnavailableHtml({ versionName = DEFAULT_APP_VERSION_NAME } = {}) {
  const safeVersion = escapeHtml(versionName || DEFAULT_APP_VERSION_NAME);
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>VidVora APK v${safeVersion} — Preparing Download</title>
<link rel="icon" type="image/png" href="/vidvora-icon.png" />
<style>
  body { margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center; background: #050811; color: #f8fafc; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; padding: 20px; }
  .box { max-width: 460px; width: 100%; background: rgba(17,25,44,0.92); border: 1px solid rgba(56,189,248,0.28); border-radius: 22px; padding: 28px 24px; text-align: center; box-shadow: 0 24px 54px rgba(0,0,0,0.55); }
  .logo { width: 64px; height: 64px; border-radius: 16px; margin-bottom: 14px; }
  h1 { font-size: 20px; margin: 0 0 10px; }
  p { font-size: 14px; color: #94a3b8; line-height: 1.55; margin: 0 0 20px; }
  .btn { display: inline-block; padding: 12px 22px; border-radius: 12px; font-weight: 700; font-size: 14px; text-decoration: none; color: #03101d; background: linear-gradient(135deg, #22d3ee, #38bdf8, #a78bfa); }
  .sub { display: block; margin-top: 14px; font-size: 13px; color: #94a3b8; text-decoration: underline; }
</style>
</head>
<body>
  <div class="box">
    <img src="/vidvora-icon.png" alt="VidVora" class="logo" />
    <h1>VidVora v${safeVersion} APK Update In Progress</h1>
    <p>Latest APK file abhi CDN server par upload / sync ho rahi hai. Kripya thodi der baad dubara try karein.</p>
    <a class="btn" href="/download-apk">Try Download Again</a>
    <a class="sub" href="/download">&larr; Back to VidVora Download Page</a>
  </div>
</body>
</html>`;
}

function renderApkDownloadWebsiteHtml({
  baseUrl = "https://vidvora-share-bot.onrender.com",
  versionName = DEFAULT_APP_VERSION_NAME,
  versionCode = DEFAULT_APP_VERSION_CODE,
  apkUrl = DEFAULT_APK_URL,
  releaseNotes = "",
} = {}) {
  const cleanBase = sanitizeHttpUrl(baseUrl, "https://vidvora-share-bot.onrender.com").replace(/\/+$/, "");
  const safeVersion = escapeHtml(versionName || DEFAULT_APP_VERSION_NAME);
  const safeVersionCode = Number.isFinite(Number(versionCode)) ? Math.floor(Number(versionCode)) : DEFAULT_APP_VERSION_CODE;
  const safeDirectApkUrl = sanitizeHttpUrl(apkUrl, DEFAULT_APK_URL);
  const canonicalUrl = `${cleanBase}/download`;
  const ogIconUrl = `${cleanBase}/vidvora-icon.png`;

  const noteLines = String(releaseNotes || "")
    .split(/\r?\n/)
    .map((line) => line.replace(/^[-•*]\s*/, "").trim())
    .filter(Boolean)
    .slice(0, 6);

  const effectiveNotes = noteLines.length > 0
    ? noteLines
    : [
        "Faster HD video startup with adaptive smooth playback",
        "Instant 1-tap Telegram & share link opening inside VidVora",
        "Improved stability, lighter memory usage & cleaner dark UI",
      ];

  const notesHtml = effectiveNotes
    .map((line) => `<li>${escapeHtml(line)}</li>`)
    .join("");

  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<meta name="theme-color" content="#060a14" />
<title>Download VidVora APK (v${safeVersion}) — Official Android App</title>
<meta name="description" content="Download the official VidVora Android APK (v${safeVersion}) for fast HD video streaming, instant link playback, and smooth viewing. 100% free & verified." />
<link rel="canonical" href="${escapeHtml(canonicalUrl)}" />
<link rel="icon" type="image/png" href="/vidvora-icon.png" />
<link rel="apple-touch-icon" href="/vidvora-icon.png" />
<meta property="og:type" content="website" />
<meta property="og:title" content="Download VidVora APK v${safeVersion} — Official Android App" />
<meta property="og:description" content="Fast, smooth & free HD video streaming app for Android. Download the latest verified VidVora APK now." />
<meta property="og:image" content="${escapeHtml(ogIconUrl)}" />
<meta property="og:url" content="${escapeHtml(canonicalUrl)}" />
<meta name="twitter:card" content="summary_large_image" />
<style>
  :root {
    --bg: #050811;
    --bg-elevated: rgba(15, 23, 42, 0.72);
    --bg-card: rgba(17, 25, 44, 0.78);
    --border: rgba(148, 163, 184, 0.14);
    --border-bright: rgba(56, 189, 248, 0.34);
    --text: #f8fafc;
    --text-muted: #94a3b8;
    --text-soft: #64748b;
    --cyan: #22d3ee;
    --blue: #3b82f6;
    --violet: #8b5cf6;
    --emerald: #10b981;
    --amber: #f59e0b;
    --radius: 20px;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  html { scroll-behavior: smooth; }
  html, body {
    width: 100%;
    max-width: 100%;
    overflow-x: hidden;
    background: var(--bg);
    color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Inter, Helvetica, Arial, sans-serif;
    -webkit-font-smoothing: antialiased;
    line-height: 1.5;
  }
  body::before {
    content: "";
    position: fixed;
    inset: 0;
    pointer-events: none;
    z-index: 0;
    background:
      radial-gradient(640px 420px at 14% 4%, rgba(34, 211, 238, 0.14), transparent 70%),
      radial-gradient(620px 420px at 86% 12%, rgba(139, 92, 246, 0.14), transparent 70%),
      radial-gradient(540px 360px at 50% 95%, rgba(59, 130, 246, 0.10), transparent 70%);
  }
  a { color: inherit; text-decoration: none; }

  .page-wrap {
    position: relative;
    z-index: 1;
    width: 100%;
    max-width: 100%;
  }

  /* Sticky Glass Navbar */
  .nav {
    position: sticky;
    top: 0;
    z-index: 40;
    backdrop-filter: blur(16px);
    -webkit-backdrop-filter: blur(16px);
    background: rgba(5, 8, 17, 0.78);
    border-bottom: 1px solid var(--border);
  }
  .nav-inner {
    max-width: 1120px;
    margin: 0 auto;
    padding: 12px 18px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }
  .brand {
    display: inline-flex;
    align-items: center;
    gap: 10px;
    min-width: 0;
  }
  .brand-logo {
    height: 30px;
    width: auto;
    display: block;
    object-fit: contain;
  }
  .official-chip {
    font-size: 10.5px;
    font-weight: 700;
    letter-spacing: 0.5px;
    text-transform: uppercase;
    padding: 3px 9px;
    border-radius: 999px;
    background: rgba(16, 185, 129, 0.14);
    border: 1px solid rgba(16, 185, 129, 0.34);
    color: #6ee7b7;
    white-space: nowrap;
  }
  .nav-links {
    display: flex;
    align-items: center;
    gap: 20px;
    font-size: 13.5px;
    color: var(--text-muted);
    font-weight: 500;
  }
  .nav-links a:hover { color: #fff; }
  .nav-cta {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 7px;
    padding: 9px 16px;
    border-radius: 12px;
    font-size: 13px;
    font-weight: 700;
    color: #04101f;
    background: linear-gradient(135deg, #22d3ee, #38bdf8 55%, #818cf8);
    box-shadow: 0 6px 20px rgba(34, 211, 238, 0.25);
    transition: transform 0.16s ease, box-shadow 0.16s ease;
    white-space: nowrap;
  }
  .nav-cta:hover {
    transform: translateY(-1px);
    box-shadow: 0 10px 26px rgba(34, 211, 238, 0.36);
  }

  .container {
    max-width: 1120px;
    margin: 0 auto;
    padding: 0 18px;
  }

  /* Hero Section */
  .hero {
    padding: 44px 0 36px;
    display: grid;
    grid-template-columns: 1.15fr 0.85fr;
    gap: 34px;
    align-items: center;
  }
  .app-identity {
    display: flex;
    align-items: center;
    gap: 16px;
    margin-bottom: 18px;
  }
  .app-icon-wrap {
    width: 72px;
    height: 72px;
    border-radius: 20px;
    padding: 4px;
    background: linear-gradient(135deg, rgba(34, 211, 238, 0.65), rgba(139, 92, 246, 0.65));
    box-shadow: 0 12px 32px rgba(34, 211, 238, 0.22);
    flex-shrink: 0;
  }
  .app-icon-wrap img {
    width: 100%;
    height: 100%;
    border-radius: 16px;
    object-fit: cover;
    display: block;
    background: #0b1222;
  }
  .verified-line {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: 12.5px;
    font-weight: 600;
    color: #6ee7b7;
    margin-bottom: 4px;
  }
  .hero h1 {
    font-size: clamp(28px, 4.4vw, 46px);
    line-height: 1.12;
    font-weight: 850;
    letter-spacing: -0.6px;
    margin-bottom: 14px;
    background: linear-gradient(180deg, #ffffff 30%, #cbd5e1 100%);
    -webkit-background-clip: text;
    -webkit-text-fill-color: transparent;
  }
  .hero-sub {
    font-size: 15.5px;
    color: var(--text-muted);
    max-width: 540px;
    margin-bottom: 22px;
  }

  /* Specs Strip */
  .specs-strip {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 10px;
    margin-bottom: 24px;
  }
  .spec-pill {
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: 14px;
    padding: 10px 12px;
    text-align: center;
  }
  .spec-val {
    font-size: 14.5px;
    font-weight: 800;
    color: #fff;
    white-space: nowrap;
  }
  .spec-lbl {
    font-size: 11px;
    color: var(--text-muted);
    margin-top: 2px;
  }

  /* Primary Download CTA */
  .cta-row {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
    align-items: center;
    margin-bottom: 16px;
  }
  .btn-download {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 10px;
    min-height: 54px;
    padding: 0 26px;
    border-radius: 16px;
    border: none;
    cursor: pointer;
    font-size: 16px;
    font-weight: 800;
    color: #03101d;
    background: linear-gradient(135deg, #22d3ee 0%, #38bdf8 50%, #a78bfa 100%);
    box-shadow: 0 12px 34px rgba(34, 211, 238, 0.34);
    transition: transform 0.16s ease, box-shadow 0.16s ease;
  }
  .btn-download:hover {
    transform: translateY(-2px);
    box-shadow: 0 16px 40px rgba(34, 211, 238, 0.46);
  }
  .btn-secondary {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    min-height: 54px;
    padding: 0 18px;
    border-radius: 16px;
    border: 1px solid var(--border);
    background: rgba(15, 23, 42, 0.8);
    color: #e2e8f0;
    font-size: 14px;
    font-weight: 700;
    cursor: pointer;
    transition: border-color 0.16s ease, background 0.16s ease;
  }
  .btn-secondary:hover {
    border-color: var(--border-bright);
    background: rgba(30, 41, 59, 0.9);
  }

  .trust-row {
    display: flex;
    flex-wrap: wrap;
    gap: 14px;
    font-size: 12.5px;
    color: var(--text-muted);
  }
  .trust-item {
    display: inline-flex;
    align-items: center;
    gap: 6px;
  }
  .download-banner {
    display: none;
    margin-top: 14px;
    padding: 13px 15px;
    border-radius: 14px;
    background: rgba(16, 185, 129, 0.12);
    border: 1px solid rgba(16, 185, 129, 0.36);
    color: #a7f3d0;
    font-size: 13.5px;
  }

  /* Phone Showcase Card */
  .phone-stage {
    display: flex;
    justify-content: center;
    align-items: center;
  }
  .phone-frame {
    width: 100%;
    max-width: 310px;
    border-radius: 34px;
    padding: 12px;
    background: linear-gradient(160deg, rgba(30, 41, 59, 0.95), rgba(15, 23, 42, 0.98));
    border: 1px solid rgba(148, 163, 184, 0.25);
    box-shadow: 0 28px 64px rgba(0, 0, 0, 0.65), 0 0 42px rgba(34, 211, 238, 0.14);
  }
  .phone-screen {
    border-radius: 24px;
    background: linear-gradient(180deg, #0b1120 0%, #060913 100%);
    padding: 16px 14px;
    border: 1px solid rgba(255, 255, 255, 0.06);
  }
  .phone-notch {
    width: 72px;
    height: 6px;
    border-radius: 99px;
    background: rgba(148, 163, 184, 0.22);
    margin: 0 auto 14px;
  }
  .mock-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 14px;
  }
  .mock-player {
    position: relative;
    border-radius: 16px;
    height: 158px;
    background:
      radial-gradient(circle at 30% 30%, rgba(34, 211, 238, 0.35), transparent 60%),
      radial-gradient(circle at 80% 75%, rgba(139, 92, 246, 0.4), transparent 60%),
      linear-gradient(135deg, #111827, #1e1b4b);
    border: 1px solid rgba(56, 189, 248, 0.28);
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    margin-bottom: 14px;
    overflow: hidden;
  }
  .mock-play-btn {
    width: 54px;
    height: 54px;
    border-radius: 999px;
    background: rgba(34, 211, 238, 0.92);
    color: #03101d;
    display: flex;
    align-items: center;
    justify-content: center;
    box-shadow: 0 0 28px rgba(34, 211, 238, 0.55);
  }
  .mock-hd-tag {
    position: absolute;
    top: 10px;
    right: 10px;
    font-size: 10px;
    font-weight: 800;
    padding: 3px 8px;
    border-radius: 8px;
    background: rgba(5, 8, 17, 0.72);
    border: 1px solid rgba(34, 211, 238, 0.4);
    color: #67e8f9;
  }
  .mock-progress {
    position: absolute;
    left: 12px;
    right: 12px;
    bottom: 12px;
    height: 4px;
    border-radius: 99px;
    background: rgba(255, 255, 255, 0.16);
    overflow: hidden;
  }
  .mock-progress > span {
    display: block;
    width: 64%;
    height: 100%;
    background: linear-gradient(90deg, #22d3ee, #8b5cf6);
  }
  .mock-input-box {
    border-radius: 12px;
    padding: 10px 12px;
    background: rgba(30, 41, 59, 0.7);
    border: 1px solid rgba(148, 163, 184, 0.18);
    font-size: 11.5px;
    color: #cbd5e1;
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 10px;
  }
  .mock-mini-btn {
    font-size: 10.5px;
    font-weight: 800;
    padding: 4px 10px;
    border-radius: 8px;
    background: linear-gradient(135deg, #22d3ee, #3b82f6);
    color: #04101f;
  }
  .mock-stats {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px;
  }
  .mock-stat-card {
    border-radius: 12px;
    padding: 10px;
    background: rgba(15, 23, 42, 0.9);
    border: 1px solid rgba(148, 163, 184, 0.12);
  }
  .mock-stat-card strong { display: block; font-size: 13px; color: #fff; }
  .mock-stat-card span { font-size: 10.5px; color: var(--text-muted); }

  /* Sections */
  section { padding: 34px 0; }
  .sec-head {
    text-align: center;
    max-width: 640px;
    margin: 0 auto 26px;
  }
  .sec-kicker {
    display: inline-block;
    font-size: 11.5px;
    font-weight: 800;
    text-transform: uppercase;
    letter-spacing: 0.9px;
    color: var(--cyan);
    margin-bottom: 6px;
  }
  .sec-head h2 {
    font-size: clamp(22px, 3vw, 32px);
    font-weight: 800;
    letter-spacing: -0.4px;
    margin-bottom: 8px;
  }
  .sec-head p { font-size: 14.5px; color: var(--text-muted); }

  /* Feature Cards */
  .features-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 16px;
  }
  .card {
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: 20px;
    transition: border-color 0.18s ease, transform 0.18s ease;
  }
  .card:hover {
    border-color: var(--border-bright);
    transform: translateY(-2px);
  }
  .feat-icon {
    width: 42px;
    height: 42px;
    border-radius: 12px;
    display: flex;
    align-items: center;
    justify-content: center;
    margin-bottom: 13px;
    background: rgba(34, 211, 238, 0.12);
    border: 1px solid rgba(34, 211, 238, 0.25);
    color: #67e8f9;
  }
  .card h3 {
    font-size: 16px;
    font-weight: 750;
    margin-bottom: 6px;
  }
  .card p {
    font-size: 13.5px;
    color: var(--text-muted);
  }

  /* What's New + Install Steps */
  .two-col {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 18px;
    align-items: start;
  }
  .steps-list {
    display: flex;
    flex-direction: column;
    gap: 12px;
    margin-top: 14px;
  }
  .step-item {
    display: flex;
    gap: 14px;
    align-items: flex-start;
    padding: 14px;
    border-radius: 15px;
    background: rgba(9, 14, 28, 0.72);
    border: 1px solid var(--border);
  }
  .step-num {
    width: 30px;
    height: 30px;
    border-radius: 10px;
    background: linear-gradient(135deg, #22d3ee, #3b82f6);
    color: #03101d;
    font-size: 14px;
    font-weight: 900;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
  }
  .step-item strong { display: block; font-size: 14.5px; margin-bottom: 2px; }
  .step-item span { font-size: 13px; color: var(--text-muted); }

  .changelog-ul {
    list-style: none;
    margin-top: 14px;
    display: flex;
    flex-direction: column;
    gap: 10px;
  }
  .changelog-ul li {
    position: relative;
    padding-left: 22px;
    font-size: 13.8px;
    color: #cbd5e1;
  }
  .changelog-ul li::before {
    content: "";
    position: absolute;
    left: 0;
    top: 8px;
    width: 8px;
    height: 8px;
    border-radius: 99px;
    background: var(--emerald);
    box-shadow: 0 0 10px rgba(16, 185, 129, 0.7);
  }
  .meta-table {
    width: 100%;
    margin-top: 16px;
    border-collapse: collapse;
    font-size: 13px;
  }
  .meta-table td {
    padding: 9px 4px;
    border-top: 1px solid var(--border);
  }
  .meta-table td:first-child { color: var(--text-muted); }
  .meta-table td:last-child { text-align: right; font-weight: 650; color: #f1f5f9; }

  /* FAQ */
  .faq-list {
    max-width: 780px;
    margin: 0 auto;
    display: flex;
    flex-direction: column;
    gap: 10px;
  }
  details.faq-item {
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: 16px;
    padding: 16px 18px;
  }
  details.faq-item summary {
    cursor: pointer;
    font-weight: 700;
    font-size: 14.8px;
    list-style: none;
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 12px;
  }
  details.faq-item summary::-webkit-details-marker { display: none; }
  details.faq-item summary::after {
    content: "+";
    font-size: 20px;
    color: var(--cyan);
    font-weight: 400;
    line-height: 1;
  }
  details.faq-item[open] summary::after { content: "−"; }
  details.faq-item p {
    margin-top: 10px;
    font-size: 13.5px;
    color: var(--text-muted);
    line-height: 1.6;
  }

  /* Bottom CTA Banner */
  .bottom-cta {
    margin: 18px 0 10px;
    padding: 32px 24px;
    border-radius: 24px;
    text-align: center;
    background: linear-gradient(135deg, rgba(34, 211, 238, 0.14), rgba(139, 92, 246, 0.16));
    border: 1px solid rgba(56, 189, 248, 0.28);
  }
  .bottom-cta h2 {
    font-size: clamp(20px, 3vw, 28px);
    font-weight: 850;
    margin-bottom: 8px;
  }
  .bottom-cta p {
    font-size: 14px;
    color: #cbd5e1;
    margin-bottom: 20px;
  }

  /* Footer */
  footer {
    margin-top: 28px;
    border-top: 1px solid var(--border);
    padding: 24px 0 36px;
    font-size: 13px;
    color: var(--text-muted);
  }
  .footer-inner {
    display: flex;
    justify-content: space-between;
    align-items: center;
    flex-wrap: wrap;
    gap: 14px;
  }
  .footer-links {
    display: flex;
    flex-wrap: wrap;
    gap: 18px;
  }
  .footer-links a:hover { color: #fff; }

  @media (max-width: 900px) {
    .hero { grid-template-columns: 1fr; padding: 28px 0 22px; gap: 26px; }
    .features-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    .two-col { grid-template-columns: 1fr; }
    .nav-links { display: none; }
  }
  @media (max-width: 560px) {
    .container { padding: 0 14px; }
    .nav-inner { padding: 10px 14px; }
    .brand-logo { height: 25px; }
    .official-chip { display: none; }
    .specs-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    .features-grid { grid-template-columns: 1fr; }
    .btn-download, .btn-secondary { width: 100%; }
    .app-icon-wrap { width: 62px; height: 62px; }
  }
</style>
</head>
<body>
<div class="page-wrap">
  <header class="nav">
    <div class="nav-inner">
      <a href="/download" class="brand" aria-label="VidVora Home">
        <img src="/vidvora-logo.png" alt="VidVora" class="brand-logo" />
        <span class="official-chip">Official APK</span>
      </a>
      <nav class="nav-links" aria-label="Primary Navigation">
        <a href="#features">Features</a>
        <a href="#install">How to Install</a>
        <a href="#whats-new">What's New</a>
        <a href="#faq">FAQ</a>
        <a href="/privacy-policy">Privacy</a>
      </nav>
      <a href="/download-apk" class="nav-cta" data-apk-trigger="1">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
        Download v${safeVersion}
      </a>
    </div>
  </header>

  <main class="container">
    <!-- Hero -->
    <section class="hero">
      <div>
        <div class="app-identity">
          <div class="app-icon-wrap">
            <img src="/vidvora-icon.png" alt="VidVora App Icon" />
          </div>
          <div>
            <div class="verified-line">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>
              Official Signed Android APK
            </div>
            <div style="font-size:13px;color:var(--text-muted);">VidVora Media &bull; Free Android App</div>
          </div>
        </div>

        <h1>Watch HD Videos Instantly with VidVora</h1>
        <p class="hero-sub">
          Open shared video links in 1 tap, enjoy fast buffer-free HD streaming, and explore trending content — all inside a sleek, lightweight Android app.
        </p>

        <div class="specs-strip">
          <div class="spec-pill">
            <div class="spec-val">v${safeVersion}</div>
            <div class="spec-lbl">Latest Version</div>
          </div>
          <div class="spec-pill">
            <div class="spec-val">Android 7+</div>
            <div class="spec-lbl">Compatibility</div>
          </div>
          <div class="spec-pill">
            <div class="spec-val">100% Free</div>
            <div class="spec-lbl">No Subscription</div>
          </div>
          <div class="spec-pill">
            <div class="spec-val">Verified</div>
            <div class="spec-lbl">Official CDN</div>
          </div>
        </div>

        <div class="cta-row">
          <a id="heroDownloadBtn" href="/download-apk" class="btn-download" data-apk-trigger="1">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
            Download VidVora APK (v${safeVersion})
          </a>
          <button type="button" id="shareSiteBtn" class="btn-secondary">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>
            <span id="shareSiteLabel">Share Link</span>
          </button>
        </div>

        <div class="trust-row">
          <span class="trust-item">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#10b981" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>
            Safe &amp; Virus-Free APK
          </span>
          <span class="trust-item">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#22d3ee" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>
            Fast Direct Download
          </span>
          <span class="trust-item">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#a78bfa" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>
            Auto-Updated to Latest Build
          </span>
        </div>

        <div id="downloadStartedBanner" class="download-banner">
          <strong>Download shuru ho gaya hai!</strong> Download complete hone par notification me <code>vidvora.apk</code> tap karein aur <strong>Install</strong> dabayein. Agar download start nahi hua toh <a href="/download-apk" style="color:#fff;text-decoration:underline;font-weight:700;">yahan dobara tap karein</a>.
        </div>
      </div>

      <!-- Crisp Lightweight Phone Preview -->
      <div class="phone-stage" aria-hidden="true">
        <div class="phone-frame">
          <div class="phone-screen">
            <div class="phone-notch"></div>
            <div class="mock-header">
              <img src="/vidvora-logo.png" alt="" style="height:20px;width:auto;" />
              <span style="font-size:10px;font-weight:700;padding:3px 8px;border-radius:99px;background:rgba(16,185,129,0.16);color:#6ee7b7;">HD READY</span>
            </div>
            <div class="mock-player">
              <span class="mock-hd-tag">1080p HD</span>
              <div class="mock-play-btn">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><polygon points="6 4 20 12 6 20 6 4"/></svg>
              </div>
              <div class="mock-progress"><span></span></div>
            </div>
            <div class="mock-input-box">
              <span>Paste VidVora Link or Code...</span>
              <span class="mock-mini-btn">Play</span>
            </div>
            <div class="mock-stats">
              <div class="mock-stat-card">
                <strong>1-Tap Open</strong>
                <span>Telegram &amp; Web links</span>
              </div>
              <div class="mock-stat-card">
                <strong>Fast CDN</strong>
                <span>Zero-lag playback</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- Features -->
    <section id="features">
      <div class="sec-head">
        <span class="sec-kicker">Why VidVora</span>
        <h2>Built for Fast, Effortless Streaming</h2>
        <p>Everything you need to open shared video links and watch smoothly on any Android phone.</p>
      </div>
      <div class="features-grid">
        <div class="card">
          <div class="feat-icon">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>
          </div>
          <h3>Instant 1-Tap Playback</h3>
          <p>Tap any VidVora link from Telegram, WhatsApp, or Chrome and the video opens directly inside the player.</p>
        </div>
        <div class="card">
          <div class="feat-icon">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>
          </div>
          <h3>Smooth HD ExoPlayer</h3>
          <p>Hardware-accelerated video engine with adaptive buffering, landscape full-screen, and quick seek controls.</p>
        </div>
        <div class="card">
          <div class="feat-icon">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
          </div>
          <h3>Safe &amp; Verified Build</h3>
          <p>Officially signed by VidVora with minimal permissions — no unnecessary storage or contact access required.</p>
        </div>
        <div class="card">
          <div class="feat-icon">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><rect x="5" y="2" width="14" height="20" rx="2"/><line x1="12" y1="18" x2="12.01" y2="18"/></svg>
          </div>
          <h3>Lightweight &amp; Fast</h3>
          <p>Optimized for budget and flagship Android phones alike — runs smoothly even on 4G mobile networks.</p>
        </div>
        <div class="card">
          <div class="feat-icon">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>
          </div>
          <h3>Direct Video Codes</h3>
          <p>Have a short VidVora video code? Paste it right on the home screen to stream immediately.</p>
        </div>
        <div class="card">
          <div class="feat-icon">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>
          </div>
          <h3>Always Up-to-Date</h3>
          <p>Get built-in update alerts and always download the newest verified release directly from this official page.</p>
        </div>
      </div>
    </section>

    <!-- How to Install + What's New -->
    <section id="install">
      <div class="two-col">
        <div class="card">
          <span class="sec-kicker">3-Step Guide</span>
          <h3 style="font-size:20px;">How to Install VidVora APK</h3>
          <p>Takes less than 20 seconds on any Android phone:</p>
          <div class="steps-list">
            <div class="step-item">
              <div class="step-num">1</div>
              <div>
                <strong>Download the Official APK</strong>
                <span>Tap the <em>Download VidVora APK</em> button above. If Chrome asks for confirmation, tap <strong>Download anyway</strong>.</span>
              </div>
            </div>
            <div class="step-item">
              <div class="step-num">2</div>
              <div>
                <strong>Allow Chrome to Install Apps</strong>
                <span>Tap the downloaded <code>vidvora.apk</code> file. If prompted, open <strong>Settings &rarr; Allow from this source</strong> once.</span>
              </div>
            </div>
            <div class="step-item">
              <div class="step-num">3</div>
              <div>
                <strong>Tap Install &amp; Open VidVora</strong>
                <span>Installation finishes in seconds. Open VidVora or tap any shared link to start watching in HD!</span>
              </div>
            </div>
          </div>
        </div>

        <div id="whats-new" class="card">
          <span class="sec-kicker">Release Details</span>
          <h3 style="font-size:20px;">What's New in v${safeVersion}</h3>
          <p>Latest official Android package directly from VidVora:</p>
          <ul class="changelog-ul">
            ${notesHtml}
          </ul>
          <table class="meta-table" aria-label="App Specifications">
            <tbody>
              <tr><td>App Name</td><td>VidVora</td></tr>
              <tr><td>Version</td><td>v${safeVersion} (Build ${safeVersionCode})</td></tr>
              <tr><td>Platform</td><td>Android 7.0 &amp; above</td></tr>
              <tr><td>License</td><td>Free</td></tr>
              <tr><td>Official Source</td><td>VidVora Cloud CDN</td></tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>

    <!-- FAQ -->
    <section id="faq">
      <div class="sec-head">
        <span class="sec-kicker">Help &amp; Safety</span>
        <h2>Frequently Asked Questions</h2>
      </div>
      <div class="faq-list">
        <details class="faq-item" open>
          <summary>Why does Chrome show "File might be harmful" while downloading?</summary>
          <p>Chrome shows this standard warning for every Android <code>.apk</code> file downloaded outside Google Play Store. This is the official, signed VidVora APK served directly from our verified CDN — simply tap <strong>Download anyway</strong> to proceed safely.</p>
        </details>
        <details class="faq-item">
          <summary>Is VidVora free to download and use?</summary>
          <p>Yes, VidVora is 100% free to download and watch videos. No paid subscription or credit card is required.</p>
        </details>
        <details class="faq-item">
          <summary>How do I update VidVora when a new version comes out?</summary>
          <p>Simply return to this official page and tap Download. Installing the new APK updates your existing app automatically without losing your saved history.</p>
        </details>
        <details class="faq-item">
          <summary>Which Android phones support VidVora?</summary>
          <p>VidVora supports all Android smartphones and tablets running Android 7.0 (Nougat) through Android 15+ (Samsung, Xiaomi/Redmi, Vivo, OPPO, Realme, OnePlus, Motorola, Pixel, and more).</p>
        </details>
      </div>
    </section>

    <!-- Bottom CTA -->
    <div class="bottom-cta">
      <h2>Ready to Start Watching?</h2>
      <p>Download the latest official VidVora APK (v${safeVersion}) for Android in one tap.</p>
      <a href="/download-apk" class="btn-download" data-apk-trigger="1">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
        Download VidVora APK Free
      </a>
    </div>
  </main>

  <footer>
    <div class="container footer-inner">
      <div style="display:flex;align-items:center;gap:10px;">
        <img src="/vidvora-logo.png" alt="VidVora" style="height:22px;width:auto;" />
        <span>&copy; ${new Date().getUTCFullYear()} VidVora. All rights reserved.</span>
      </div>
      <div class="footer-links">
        <a href="/download-apk">Direct APK</a>
        <a href="/privacy-policy">Privacy Policy</a>
        <a href="#install">Install Guide</a>
        <a href="#faq">FAQ</a>
      </div>
    </div>
  </footer>
</div>

<script>
(function() {
  var banner = document.getElementById("downloadStartedBanner");
  document.querySelectorAll("[data-apk-trigger]").forEach(function(el) {
    el.addEventListener("click", function() {
      if (banner) {
        banner.style.display = "block";
      }
    });
  });

  var shareBtn = document.getElementById("shareSiteBtn");
  var shareLbl = document.getElementById("shareSiteLabel");
  if (shareBtn) {
    shareBtn.addEventListener("click", async function() {
      var shareUrl = window.location.origin + "/download";
      var shareData = {
        title: ${JSON.stringify(`Download VidVora APK (v${String(versionName || DEFAULT_APP_VERSION_NAME)})`).replace(/</g, "\\u003c")},
        text: "Download the official VidVora Android app for fast HD video streaming:",
        url: shareUrl
      };
      try {
        if (navigator.share) {
          await navigator.share(shareData);
          return;
        }
      } catch (_) {}
      try {
        await navigator.clipboard.writeText(shareUrl);
        if (shareLbl) {
          shareLbl.textContent = "Link Copied!";
          setTimeout(function() { shareLbl.textContent = "Share Link"; }, 2000);
        }
      } catch (_) {}
    });
  }
})();
</script>
</body>
</html>`;
}

module.exports = {
  DEFAULT_APP_VERSION_NAME,
  DEFAULT_APP_VERSION_CODE,
  sanitizeHttpUrl,
  normalizeDirectApkUrl,
  resolveGitHubReleaseApkUrl,
  resolveLocalReleaseApkPath,
  isRemoteApkReachable,
  resolveAppDownloadMetadata,
  renderApkDownloadWebsiteHtml,
  renderApkTemporarilyUnavailableHtml,
};
