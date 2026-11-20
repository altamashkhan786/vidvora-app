const crypto = require("crypto");
const { buildGeoAnalyticsSummary } = require("./geoTracker");

const ADMIN_COOKIE_NAME = "vidvora_admin_token";
const DEFAULT_TOKEN_TTL_MS = 7 * 24 * 60 * 60 * 1000; // 7 days
const IST_OFFSET_MS = 5.5 * 60 * 60 * 1000;
const DAY_MS = 24 * 60 * 60 * 1000;
const HOUR_MS = 60 * 60 * 1000;
const UNINSTALLED_INACTIVITY_MS = 14 * DAY_MS;

const EMULATOR_MARKERS = [
  "sdk_gphone",
  "gphone",
  "emulator",
  "goldfish",
  "ranchu",
  "genymotion",
  "bluestacks",
  "noxplayer",
  "vbox86",
  "cuttlefish",
  "aosp on ",
  "unknown generic",
  "google sdk",
];

function isEmulatorDeviceLabel(label) {
  const lower = String(label || "").trim().toLowerCase();
  if (!lower) return false;
  return EMULATOR_MARKERS.some((marker) => lower.includes(marker));
}

function toEpochMs(value) {
  if (value === null || value === undefined) return 0;
  if (typeof value === "number" && Number.isFinite(value)) {
    return value > 0 && value < 1e12 ? value * 1000 : value;
  }
  if (value instanceof Date) {
    const ms = value.getTime();
    return Number.isFinite(ms) ? ms : 0;
  }
  if (typeof value === "string") {
    const trimmed = value.trim();
    if (!trimmed) return 0;
    const asNum = Number(trimmed);
    if (Number.isFinite(asNum) && asNum > 0) {
      return asNum < 1e12 ? asNum * 1000 : asNum;
    }
    const parsed = Date.parse(trimmed);
    return Number.isFinite(parsed) ? parsed : 0;
  }
  if (typeof value === "object") {
    if (typeof value.toMillis === "function") {
      const ms = value.toMillis();
      return Number.isFinite(ms) ? ms : 0;
    }
    if (typeof value.__vidvora_ts_ms__ === "number" && Number.isFinite(value.__vidvora_ts_ms__)) {
      return value.__vidvora_ts_ms__;
    }
    if (typeof value._seconds === "number" && Number.isFinite(value._seconds)) {
      return value._seconds * 1000 + Math.floor((value._nanoseconds || 0) / 1e6);
    }
    if (typeof value.seconds === "number" && Number.isFinite(value.seconds)) {
      return value.seconds * 1000 + Math.floor((value.nanoseconds || 0) / 1e6);
    }
  }
  return 0;
}

function toSafeNumber(value, fallback = 0) {
  const num = Number(value);
  return Number.isFinite(num) ? num : fallback;
}

function base64UrlEncode(input) {
  return Buffer.from(input)
    .toString("base64")
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/g, "");
}

function base64UrlDecodeToString(input) {
  const normalized = String(input || "")
    .replace(/-/g, "+")
    .replace(/_/g, "/");
  const pad = normalized.length % 4 === 0 ? "" : "=".repeat(4 - (normalized.length % 4));
  return Buffer.from(normalized + pad, "base64").toString("utf8");
}

function createAdminDashboardToken(secret, ttlMs = DEFAULT_TOKEN_TTL_MS, nowMs = Date.now()) {
  const cleanSecret = String(secret || "").trim();
  if (!cleanSecret) throw new Error("Missing signing secret");
  const payload = {
    role: "superadmin",
    iat: nowMs,
    exp: nowMs + Math.max(60_000, ttlMs),
  };
  const encodedPayload = base64UrlEncode(JSON.stringify(payload));
  const signature = base64UrlEncode(
    crypto.createHmac("sha256", cleanSecret).update(encodedPayload).digest()
  );
  return `${encodedPayload}.${signature}`;
}

function verifyAdminDashboardToken(token, secret, nowMs = Date.now()) {
  const cleanToken = String(token || "").trim();
  const cleanSecret = String(secret || "").trim();
  if (!cleanToken || !cleanSecret) return false;
  const parts = cleanToken.split(".");
  if (parts.length !== 2) return false;
  const [encodedPayload, providedSig] = parts;
  if (!encodedPayload || !providedSig) return false;

  const expectedSig = base64UrlEncode(
    crypto.createHmac("sha256", cleanSecret).update(encodedPayload).digest()
  );
  const a = Buffer.from(providedSig, "utf8");
  const b = Buffer.from(expectedSig, "utf8");
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return false;

  try {
    const payload = JSON.parse(base64UrlDecodeToString(encodedPayload));
    if (payload.role !== "superadmin") return false;
    if (typeof payload.exp !== "number" || payload.exp <= nowMs) return false;
    return true;
  } catch (_) {
    return false;
  }
}

function parseCookies(cookieHeader) {
  const result = {};
  const raw = String(cookieHeader || "");
  if (!raw) return result;
  for (const part of raw.split(";")) {
    const eqIdx = part.indexOf("=");
    if (eqIdx <= 0) continue;
    const key = part.slice(0, eqIdx).trim();
    const val = part.slice(eqIdx + 1).trim();
    if (key) {
      try {
        result[key] = decodeURIComponent(val);
      } catch (_) {
        result[key] = val;
      }
    }
  }
  return result;
}

function extractDashboardTokenFromRequest(req) {
  const authHeader = String(req?.headers?.authorization || "").trim();
  if (authHeader.toLowerCase().startsWith("bearer ")) {
    const bearer = authHeader.slice(7).trim();
    if (bearer) return bearer;
  }
  const cookies = parseCookies(req?.headers?.cookie);
  return String(cookies[ADMIN_COOKIE_NAME] || "").trim();
}

function formatIstDateKey(epochMs) {
  const d = new Date(epochMs + IST_OFFSET_MS);
  const yyyy = d.getUTCFullYear();
  const mm = String(d.getUTCMonth() + 1).padStart(2, "0");
  const dd = String(d.getUTCDate()).padStart(2, "0");
  return `${yyyy}-${mm}-${dd}`;
}

const MONTH_SHORT = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

function formatIstShortDateLabel(dateKey) {
  const [yyyy, mm, dd] = String(dateKey || "").split("-").map((x) => parseInt(x, 10));
  if (!yyyy || !mm || !dd) return String(dateKey || "");
  return `${dd} ${MONTH_SHORT[mm - 1] || ""}`;
}

function formatUtcHourBucketKey(epochMs) {
  const d = new Date(epochMs);
  const yyyy = d.getUTCFullYear();
  const mm = String(d.getUTCMonth() + 1).padStart(2, "0");
  const dd = String(d.getUTCDate()).padStart(2, "0");
  const hh = String(d.getUTCHours()).padStart(2, "0");
  return `${yyyy}${mm}${dd}${hh}`;
}

function parseUtcHourBucketKeyToEpochMs(bucketKey) {
  const raw = String(bucketKey || "").trim();
  // Format 1: YYYYMMDDHH (primary format used by VidVora Android & server.js)
  const m1 = raw.match(/^(\d{4})(\d{2})(\d{2})(\d{2})$/);
  if (m1) {
    return Date.UTC(
      parseInt(m1[1], 10),
      parseInt(m1[2], 10) - 1,
      parseInt(m1[3], 10),
      parseInt(m1[4], 10),
      0,
      0,
      0
    );
  }
  // Format 2: YYYY-MM-DDTHH
  const m2 = raw.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2})$/);
  if (m2) {
    return Date.UTC(
      parseInt(m2[1], 10),
      parseInt(m2[2], 10) - 1,
      parseInt(m2[3], 10),
      parseInt(m2[4], 10),
      0,
      0,
      0
    );
  }
  return 0;
}

function formatIstHourLabel(epochMs) {
  const d = new Date(epochMs + IST_OFFSET_MS);
  const hh = d.getUTCHours();
  const mins = d.getUTCMinutes();
  const ampm = hh >= 12 ? "PM" : "AM";
  const h12 = hh % 12 === 0 ? 12 : hh % 12;
  const minStr = mins > 0 ? `:${String(mins).padStart(2, "0")}` : "";
  return `${h12}${minStr} ${ampm}`;
}

function normalizePhoneBrand(deviceLabel) {
  const raw = String(deviceLabel || "").trim();
  if (!raw) return "Unknown";
  const lower = raw.toLowerCase();
  if (lower.includes("samsung") || lower.startsWith("sm-")) return "Samsung";
  if (lower.includes("xiaomi") || lower.includes("redmi") || lower.includes("mi ")) return "Xiaomi / Redmi";
  if (lower.includes("poco")) return "POCO";
  if (lower.includes("vivo")) return "Vivo";
  if (lower.includes("iqoo")) return "iQOO";
  if (lower.includes("oppo")) return "OPPO";
  if (lower.includes("realme") || lower.startsWith("rmx")) return "Realme";
  if (lower.includes("oneplus") || lower.startsWith("cph2")) return "OnePlus";
  if (lower.includes("motorola") || lower.includes("moto ")) return "Motorola";
  if (lower.includes("nothing") || lower.includes("cmf")) return "Nothing";
  if (lower.includes("pixel") || lower.includes("google")) return "Google Pixel";
  if (lower.includes("infinix")) return "Infinix";
  if (lower.includes("tecno")) return "Tecno";
  if (lower.includes("itel")) return "itel";
  if (lower.includes("lava")) return "Lava";
  if (lower.includes("honor")) return "Honor";
  if (lower.includes("huawei")) return "Huawei";
  if (lower.includes("nokia")) return "Nokia";
  if (lower.includes("asus")) return "ASUS";
  if (lower.includes("sony")) return "Sony";
  const firstWord = raw.split(/\s+/)[0] || "Other";
  if (firstWord.length <= 2) return "Other";
  return firstWord.charAt(0).toUpperCase() + firstWord.slice(1).toLowerCase();
}

function buildBreakdownList(countsMap, totalCount, maxItems = 8) {
  const entries = Array.from(countsMap.entries()).sort((a, b) => b[1] - a[1]);
  const top = entries.slice(0, maxItems);
  const remainder = entries.slice(maxItems).reduce((sum, [, c]) => sum + c, 0);
  if (remainder > 0) {
    const existingOther = top.find(([k]) => k === "Other");
    if (existingOther) {
      existingOther[1] += remainder;
    } else {
      top.push(["Other", remainder]);
    }
  }
  return top.map(([label, count]) => ({
    label,
    count,
    percent: totalCount > 0 ? Math.round((count * 1000) / totalCount) / 10 : 0,
  }));
}

function buildAdminDashboardStats({
  installs = [],
  videos = [],
  members = [],
  withdrawals = [],
  geoStats = null,
  days = 14,
  nowMs = Date.now(),
}) {
  const safeDays = [7, 14, 30, 60].includes(Number(days)) ? Number(days) : 14;
  const todayIstKey = formatIstDateKey(nowMs);

  // Build ordered list of IST calendar dates for the selected range
  const dateKeys = [];
  const dateKeySet = new Set();
  for (let i = safeDays - 1; i >= 0; i -= 1) {
    const key = formatIstDateKey(nowMs - i * DAY_MS);
    if (!dateKeySet.has(key)) {
      dateKeySet.add(key);
      dateKeys.push(key);
    }
  }

  // 1. Process App Installs (filter emulators exactly like Android app)
  let hiddenEmulatorCount = 0;
  const realDevices = [];
  const dailyNewInstallsMap = new Map(dateKeys.map((k) => [k, 0]));
  const dailyActiveDevicesMap = new Map(dateKeys.map((k) => [k, 0]));
  const brandCounts = new Map();
  const appVersionCounts = new Map();
  const androidVersionCounts = new Map();

  let currentlyInstalled = 0;
  let uninstalledCount = 0;
  let downloadsToday = 0;
  let activeLast24h = 0;
  let activeLast7d = 0;
  let installsBeforeWindow = 0;

  const firstWindowDate = dateKeys[0] || todayIstKey;

  for (const raw of installs) {
    if (!raw || typeof raw !== "object") continue;
    const deviceLabel = String(raw.deviceLabel || "Android Device").trim() || "Android Device";
    if (isEmulatorDeviceLabel(deviceLabel)) {
      hiddenEmulatorCount += 1;
      continue;
    }

    const firstInstalledAtMs = toEpochMs(raw.firstInstalledAt);
    const lastSeenAtMs = toEpochMs(raw.lastSeenAt) || firstInstalledAtMs;
    const isMarkedUninstalled = raw.isMarkedUninstalled === true;
    const isUninstalled =
      isMarkedUninstalled || (lastSeenAtMs > 0 && nowMs - lastSeenAtMs > UNINSTALLED_INACTIVITY_MS);

    if (isUninstalled) {
      uninstalledCount += 1;
    } else {
      currentlyInstalled += 1;
    }

    const firstIstDate = firstInstalledAtMs > 0 ? formatIstDateKey(firstInstalledAtMs) : "";
    const lastIstDate = lastSeenAtMs > 0 ? formatIstDateKey(lastSeenAtMs) : "";

    if (firstIstDate === todayIstKey) downloadsToday += 1;
    if (lastSeenAtMs > 0 && nowMs - lastSeenAtMs <= DAY_MS) activeLast24h += 1;
    if (lastSeenAtMs > 0 && nowMs - lastSeenAtMs <= 7 * DAY_MS) activeLast7d += 1;

    if (firstIstDate) {
      if (dailyNewInstallsMap.has(firstIstDate)) {
        dailyNewInstallsMap.set(firstIstDate, (dailyNewInstallsMap.get(firstIstDate) || 0) + 1);
      } else if (firstIstDate < firstWindowDate) {
        installsBeforeWindow += 1;
      }
    }

    if (lastIstDate && dailyActiveDevicesMap.has(lastIstDate)) {
      dailyActiveDevicesMap.set(lastIstDate, (dailyActiveDevicesMap.get(lastIstDate) || 0) + 1);
    }

    const brand = normalizePhoneBrand(deviceLabel);
    brandCounts.set(brand, (brandCounts.get(brand) || 0) + 1);

    const appVerRaw = String(raw.appVersion || "Unknown").trim() || "Unknown";
    const appVerLabel = appVerRaw.toLowerCase().startsWith("v") ? appVerRaw : `v${appVerRaw}`;
    appVersionCounts.set(appVerLabel, (appVersionCounts.get(appVerLabel) || 0) + 1);

    const androidVerRaw = String(raw.androidVersion || "Unknown").trim() || "Unknown";
    const androidVerLabel = androidVerRaw.toLowerCase().startsWith("android")
      ? androidVerRaw
      : `Android ${androidVerRaw}`;
    androidVersionCounts.set(androidVerLabel, (androidVersionCounts.get(androidVerLabel) || 0) + 1);

    realDevices.push({
      installId: String(raw.installId || raw._id || "").trim(),
      deviceLabel,
      brand,
      androidVersion: androidVerLabel,
      appVersion: appVerLabel,
      firstInstalledAtMs,
      lastSeenAtMs,
      sessionCount: Math.max(1, Math.floor(toSafeNumber(raw.sessionCount, 1))),
      status: isUninstalled ? "UNINSTALLED" : "INSTALLED",
    });
  }

  realDevices.sort((a, b) => (b.lastSeenAtMs || b.firstInstalledAtMs) - (a.lastSeenAtMs || a.firstInstalledAtMs));

  const totalDownloads = realDevices.length;
  const retentionPercent =
    totalDownloads > 0 ? Math.round((currentlyInstalled * 1000) / totalDownloads) / 10 : 0;

  let runningCumulative = installsBeforeWindow;
  const dailyInstalls = dateKeys.map((dateKey) => {
    const newInstalls = dailyNewInstallsMap.get(dateKey) || 0;
    runningCumulative += newInstalls;
    return {
      date: dateKey,
      label: formatIstShortDateLabel(dateKey),
      newInstalls,
      activeDevices: dailyActiveDevicesMap.get(dateKey) || 0,
      cumulativeInstalls: runningCumulative,
    };
  });

  // 2. Process Videos (strictly drop thumbnailPath/storagePath to keep payload tiny & private)
  let totalRealViews = 0;
  let activeVideos = 0;
  let videosUploadedToday = 0;
  const uploaderStatsMap = new Map(); // normalized uploaderId -> { videosCount, realViews }
  const dailyUploadsMap = new Map(dateKeys.map((k) => [k, 0]));
  const dailyUploadViewsMap = new Map(dateKeys.map((k) => [k, 0]));
  const sanitizedVideos = [];

  for (const raw of videos) {
    if (!raw || typeof raw !== "object") continue;
    const views = Math.max(0, Math.floor(toSafeNumber(raw.totalViews, 0)));
    const isActive = raw.isActive !== false;
    const createdAtMs = toEpochMs(raw.createdAt);
    const createdIstDate = createdAtMs > 0 ? formatIstDateKey(createdAtMs) : "";
    const uploadedBy = String(raw.uploadedBy || "admin").trim() || "admin";
    const uploaderName = String(raw.uploaderName || (uploadedBy === "admin" ? "Admin" : uploadedBy)).trim();

    totalRealViews += views;
    if (isActive) activeVideos += 1;
    if (createdIstDate === todayIstKey) videosUploadedToday += 1;

    if (createdIstDate && dailyUploadsMap.has(createdIstDate)) {
      dailyUploadsMap.set(createdIstDate, (dailyUploadsMap.get(createdIstDate) || 0) + 1);
      dailyUploadViewsMap.set(createdIstDate, (dailyUploadViewsMap.get(createdIstDate) || 0) + views);
    }

    const uploaderKey = uploadedBy.toLowerCase();
    const cur = uploaderStatsMap.get(uploaderKey) || { videosCount: 0, realViews: 0 };
    cur.videosCount += 1;
    cur.realViews += views;
    uploaderStatsMap.set(uploaderKey, cur);

    sanitizedVideos.push({
      videoId: String(raw.videoId || raw._id || "").trim(),
      title: String(raw.title || "Untitled Video").trim() || "Untitled Video",
      uploadedBy,
      uploaderName,
      totalViews: views,
      createdAtMs,
      isActive,
    });
  }

  sanitizedVideos.sort((a, b) => b.totalViews - a.totalViews || b.createdAtMs - a.createdAtMs);
  const topVideos = sanitizedVideos.slice(0, 30);

  // 3. Process Members + Hourly Views (Last 24 Hours + Daily Views History)
  const currentUtcHourFloorMs = Math.floor(nowMs / HOUR_MS) * HOUR_MS;
  const last24HourSlots = [];
  const last24HourKeySet = new Set();
  for (let i = 23; i >= 0; i -= 1) {
    const slotMs = currentUtcHourFloorMs - i * HOUR_MS;
    const utcKey = formatUtcHourBucketKey(slotMs);
    last24HourKeySet.add(utcKey);
    last24HourSlots.push({
      utcKey,
      slotMs,
      label: formatIstHourLabel(slotMs),
      fullLabel: `${formatIstShortDateLabel(formatIstDateKey(slotMs))}, ${formatIstHourLabel(slotMs)} IST`,
      views: 0,
    });
  }
  const last24HourMap = new Map(last24HourSlots.map((s) => [s.utcKey, s]));
  const dailyTrackedViewsMap = new Map(dateKeys.map((k) => [k, 0]));

  let totalCreditedViews = 0;
  let totalCreators = 0;
  let activeCreators = 0;
  let viewsLast24h = 0;
  let viewsToday = 0;
  const creatorsList = [];

  for (const raw of members) {
    if (!raw || typeof raw !== "object") continue;
    const memberId = String(raw.memberId || raw._id || "").trim();
    const roleRaw = String(raw.role || "member").trim().toLowerCase();
    const isAdmin = roleRaw === "admin" || roleRaw === "subadmin" || roleRaw === "delegated_admin";
    const isActive = raw.isActive !== false;
    const creditedViews = Math.max(0, Math.floor(toSafeNumber(raw.creditedViews, 0)));
    const totalEarning = Math.max(0, Math.round(toSafeNumber(raw.totalEarning, 0) * 100) / 100);
    const viewRatePer1000 = toSafeNumber(raw.viewRatePer1000, 190);

    if (!isAdmin) {
      totalCreators += 1;
      if (isActive) activeCreators += 1;
    }
    totalCreditedViews += creditedViews;

    let memberViews24h = 0;
    const hourlyObj = raw.hourlyViews && typeof raw.hourlyViews === "object" ? raw.hourlyViews : {};
    for (const [bucketKey, rawCount] of Object.entries(hourlyObj)) {
      const count = Math.max(0, Math.floor(toSafeNumber(rawCount, 0)));
      if (count <= 0) continue;
      const bucketMs = parseUtcHourBucketKeyToEpochMs(bucketKey);
      if (bucketMs <= 0) continue;

      const canonicalUtcKey = formatUtcHourBucketKey(bucketMs);
      if (last24HourKeySet.has(canonicalUtcKey)) {
        const slot = last24HourMap.get(canonicalUtcKey);
        if (slot) slot.views += count;
        memberViews24h += count;
        viewsLast24h += count;
      }

      const istDate = formatIstDateKey(bucketMs);
      if (istDate === todayIstKey) {
        viewsToday += count;
      }
      if (dailyTrackedViewsMap.has(istDate)) {
        dailyTrackedViewsMap.set(istDate, (dailyTrackedViewsMap.get(istDate) || 0) + count);
      }
    }

    const byId = uploaderStatsMap.get(memberId.toLowerCase()) || { videosCount: 0, realViews: 0 };

    creatorsList.push({
      memberId,
      name: String(raw.name || "Creator").trim() || "Creator",
      emailOrPhone: String(raw.emailOrPhone || "").trim(),
      role: isAdmin ? "Admin" : "Creator",
      isActive,
      videosCount: byId.videosCount,
      views24h: memberViews24h,
      realViews: byId.realViews,
      creditedViews,
      totalEarning,
      viewRatePer1000,
    });
  }

  creatorsList.sort((a, b) => b.views24h - a.views24h || b.realViews - a.realViews || b.videosCount - a.videosCount);

  const dailyViews = dateKeys.map((dateKey) => ({
    date: dateKey,
    label: formatIstShortDateLabel(dateKey),
    trackedViews: dailyTrackedViewsMap.get(dateKey) || 0,
    uploadsCount: dailyUploadsMap.get(dateKey) || 0,
    uploadTotalViews: dailyUploadViewsMap.get(dateKey) || 0,
  }));

  // 4. Process Withdrawal Summary
  let pendingWithdrawalCount = 0;
  let pendingWithdrawalAmount = 0;
  let paidWithdrawalAmount = 0;
  for (const raw of withdrawals) {
    if (!raw || typeof raw !== "object") continue;
    const status = String(raw.status || "PENDING").trim().toUpperCase();
    const amount = Math.max(0, toSafeNumber(raw.amount, 0));
    if (status === "PENDING" || status === "PROCESSING") {
      pendingWithdrawalCount += 1;
      pendingWithdrawalAmount += amount;
    } else if (status === "DONE") {
      paidWithdrawalAmount += amount;
    }
  }

  return {
    ok: true,
    generatedAtMs: nowMs,
    days: safeDays,
    summary: {
      totalDownloads,
      currentlyInstalled,
      uninstalledCount,
      downloadsToday,
      activeLast24h,
      activeLast7d,
      retentionPercent,
      hiddenEmulatorCount,
      totalRealViews,
      totalCreditedViews,
      viewsLast24h,
      viewsToday,
      totalVideos: videos.length,
      activeVideos,
      videosUploadedToday,
      totalCreators,
      activeCreators,
      pendingWithdrawalCount,
      pendingWithdrawalAmount: Math.round(pendingWithdrawalAmount * 100) / 100,
      paidWithdrawalAmount: Math.round(paidWithdrawalAmount * 100) / 100,
    },
    charts: {
      dailyInstalls,
      hourlyViews24h: last24HourSlots.map(({ utcKey, label, fullLabel, views }) => ({
        utcKey,
        label,
        fullLabel,
        views,
      })),
      dailyViews,
    },
    breakdowns: {
      brands: buildBreakdownList(brandCounts, totalDownloads, 8),
      appVersions: buildBreakdownList(appVersionCounts, totalDownloads, 8),
      androidVersions: buildBreakdownList(androidVersionCounts, totalDownloads, 8),
    },
    geo: buildGeoAnalyticsSummary(geoStats || {}, nowMs),
    creators: creatorsList,
    topVideos,
    totalDevicesCount: realDevices.length,
    devices: realDevices.slice(0, 1500),
  };
}

function renderAdminDashboardHtml() {
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>VidVora Analytics — Installs &amp; Views Dashboard</title>
<style>
  :root {
    --bg: #080d16;
    --bg-elev: #0f1726;
    --bg-card: #131d31;
    --bg-card-hover: #18253e;
    --border: rgba(148, 163, 184, 0.14);
    --border-strong: rgba(148, 163, 184, 0.25);
    --text: #f1f5f9;
    --text-muted: #94a3b8;
    --text-soft: #64748b;
    --cyan: #22d3ee;
    --emerald: #10b981;
    --violet: #a78bfa;
    --amber: #f59e0b;
    --rose: #f43f5e;
    --blue: #3b82f6;
    --radius: 14px;
    --shadow: 0 12px 32px rgba(0, 0, 0, 0.35);
  }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  html, body {
    background: radial-gradient(circle at 12% 0%, rgba(34, 211, 238, 0.08), transparent 40%),
                radial-gradient(circle at 88% 10%, rgba(16, 185, 129, 0.07), transparent 40%),
                var(--bg);
    color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Inter, Arial, sans-serif;
    min-height: 100vh;
    -webkit-font-smoothing: antialiased;
  }
  button, input, select { font-family: inherit; }

  /* Login Overlay */
  .login-wrap {
    min-height: 100vh;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 24px;
  }
  .login-card {
    width: 100%;
    max-width: 420px;
    background: var(--bg-card);
    border: 1px solid var(--border-strong);
    border-radius: 20px;
    padding: 36px 32px;
    box-shadow: var(--shadow);
  }
  .brand-row {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 18px;
  }
  .brand-logo {
    width: 44px;
    height: 44px;
    border-radius: 12px;
    background: linear-gradient(135deg, #22d3ee, #10b981);
    display: grid;
    place-items: center;
    font-weight: 800;
    font-size: 20px;
    color: #06131c;
    box-shadow: 0 6px 20px rgba(34, 211, 238, 0.32);
  }
  .login-card h1 { font-size: 22px; font-weight: 700; letter-spacing: -0.02em; }
  .login-card p { color: var(--text-muted); font-size: 13.5px; line-height: 1.5; margin-bottom: 22px; }
  .field-label { display: block; font-size: 12.5px; color: var(--text-muted); margin-bottom: 8px; font-weight: 600; }
  .pass-box { position: relative; margin-bottom: 16px; }
  .text-input {
    width: 100%;
    background: var(--bg-elev);
    border: 1px solid var(--border-strong);
    border-radius: 10px;
    color: var(--text);
    padding: 12px 44px 12px 14px;
    font-size: 14.5px;
    outline: none;
    transition: border-color 0.15s, box-shadow 0.15s;
  }
  .text-input:focus {
    border-color: var(--cyan);
    box-shadow: 0 0 0 3px rgba(34, 211, 238, 0.18);
  }
  .eye-btn {
    position: absolute;
    right: 10px;
    top: 50%;
    transform: translateY(-50%);
    background: transparent;
    border: none;
    color: var(--text-muted);
    cursor: pointer;
    padding: 4px 6px;
    font-size: 13px;
  }
  .btn-primary {
    width: 100%;
    background: linear-gradient(135deg, #22d3ee, #10b981);
    color: #05131c;
    border: none;
    border-radius: 10px;
    padding: 12px 18px;
    font-weight: 700;
    font-size: 14.5px;
    cursor: pointer;
    transition: transform 0.12s, filter 0.15s;
  }
  .btn-primary:hover { filter: brightness(1.06); }
  .btn-primary:active { transform: scale(0.99); }
  .btn-primary:disabled { opacity: 0.6; cursor: not-allowed; }
  .error-banner {
    display: none;
    background: rgba(244, 63, 94, 0.14);
    border: 1px solid rgba(244, 63, 94, 0.4);
    color: #fda4af;
    border-radius: 10px;
    padding: 10px 12px;
    font-size: 13px;
    margin-bottom: 14px;
  }

  /* App Shell */
  .app-shell { display: none; min-height: 100vh; padding-bottom: 48px; }
  .topbar {
    position: sticky;
    top: 0;
    z-index: 40;
    backdrop-filter: blur(14px);
    background: rgba(8, 13, 22, 0.84);
    border-bottom: 1px solid var(--border);
  }
  .topbar-inner {
    max-width: 1440px;
    margin: 0 auto;
    padding: 14px 24px;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 14px;
  }
  .topbar-left { display: flex; align-items: center; gap: 14px; }
  .topbar-title { font-size: 18px; font-weight: 700; letter-spacing: -0.02em; }
  .live-pill {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    font-size: 12px;
    color: #6ee7b7;
    background: rgba(16, 185, 129, 0.13);
    border: 1px solid rgba(16, 185, 129, 0.3);
    padding: 4px 10px;
    border-radius: 999px;
    font-weight: 600;
  }
  .live-dot {
    width: 8px; height: 8px; border-radius: 50%; background: var(--emerald);
    box-shadow: 0 0 8px var(--emerald);
    animation: pulse 2s infinite;
  }
  @keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: 0.35; } }
  .updated-text { font-size: 12px; color: var(--text-muted); }

  .topbar-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
  .seg-group {
    display: inline-flex;
    background: var(--bg-elev);
    border: 1px solid var(--border);
    border-radius: 10px;
    padding: 3px;
  }
  .seg-btn {
    background: transparent;
    border: none;
    color: var(--text-muted);
    padding: 6px 12px;
    border-radius: 7px;
    font-size: 12.5px;
    font-weight: 600;
    cursor: pointer;
    transition: all 0.15s;
  }
  .seg-btn.active {
    background: var(--bg-card-hover);
    color: var(--cyan);
    box-shadow: inset 0 0 0 1px rgba(34, 211, 238, 0.35);
  }
  .ctrl-btn {
    background: var(--bg-elev);
    border: 1px solid var(--border);
    color: var(--text);
    padding: 7px 13px;
    border-radius: 10px;
    font-size: 12.5px;
    font-weight: 600;
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    gap: 6px;
    transition: background 0.15s, border-color 0.15s;
  }
  .ctrl-btn:hover { background: var(--bg-card-hover); border-color: var(--border-strong); }
  .ctrl-btn.on { border-color: rgba(16, 185, 129, 0.45); color: #6ee7b7; }
  .ctrl-btn.danger:hover { border-color: rgba(244, 63, 94, 0.5); color: #fda4af; }

  .container { max-width: 1440px; margin: 0 auto; padding: 22px 24px 0; }

  /* KPI Grid */
  .kpi-grid {
    display: grid;
    grid-template-columns: repeat(6, minmax(0, 1fr));
    gap: 14px;
    margin-bottom: 20px;
  }
  @media (max-width: 1240px) { .kpi-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
  @media (max-width: 680px) { .kpi-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
  .kpi-card {
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: 16px 18px;
    position: relative;
    overflow: hidden;
  }
  .kpi-card::after {
    content: "";
    position: absolute;
    top: 0; left: 0; right: 0;
    height: 3px;
    background: var(--accent, var(--cyan));
    opacity: 0.85;
  }
  .kpi-label { font-size: 12px; color: var(--text-muted); font-weight: 600; text-transform: uppercase; letter-spacing: 0.04em; }
  .kpi-value { font-size: 28px; font-weight: 800; letter-spacing: -0.025em; margin: 6px 0 4px; font-variant-numeric: tabular-nums; }
  .kpi-sub { font-size: 12px; color: var(--text-muted); display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
  .badge-good { color: #6ee7b7; background: rgba(16, 185, 129, 0.14); padding: 2px 7px; border-radius: 999px; font-weight: 600; font-size: 11px; }
  .badge-warn { color: #fcd34d; background: rgba(245, 158, 11, 0.14); padding: 2px 7px; border-radius: 999px; font-weight: 600; font-size: 11px; }
  .badge-rose { color: #fda4af; background: rgba(244, 63, 94, 0.14); padding: 2px 7px; border-radius: 999px; font-weight: 600; font-size: 11px; }
  .badge-cyan { color: #67e8f9; background: rgba(34, 211, 238, 0.14); padding: 2px 7px; border-radius: 999px; font-weight: 600; font-size: 11px; }

  /* Charts Row */
  .charts-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px;
    margin-bottom: 20px;
  }
  @media (max-width: 1024px) { .charts-grid { grid-template-columns: 1fr; } }
  .panel {
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: 18px 20px;
  }
  .panel-head {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    margin-bottom: 14px;
  }
  .panel-title { font-size: 15.5px; font-weight: 700; }
  .panel-sub { font-size: 12px; color: var(--text-muted); margin-top: 2px; }
  .chart-wrap {
    position: relative;
    width: 100%;
    height: 290px;
  }
  .chart-canvas { width: 100%; height: 100%; display: block; cursor: crosshair; }
  .chart-tooltip {
    position: absolute;
    pointer-events: none;
    display: none;
    background: rgba(8, 13, 22, 0.96);
    border: 1px solid var(--border-strong);
    border-radius: 10px;
    padding: 10px 12px;
    font-size: 12.5px;
    color: var(--text);
    box-shadow: 0 10px 26px rgba(0,0,0,0.5);
    z-index: 20;
    min-width: 160px;
  }
  .chart-tooltip .tt-title { font-weight: 700; margin-bottom: 5px; color: #fff; }
  .chart-tooltip .tt-row { display: flex; justify-content: space-between; gap: 14px; margin-top: 3px; color: var(--text-muted); }
  .chart-tooltip .tt-row strong { color: #fff; font-variant-numeric: tabular-nums; }
  .chart-legend { display: flex; gap: 16px; flex-wrap: wrap; font-size: 12px; color: var(--text-muted); margin-top: 10px; }
  .legend-item { display: inline-flex; align-items: center; gap: 6px; }
  .legend-dot { width: 10px; height: 10px; border-radius: 3px; }

  /* Breakdown Grid */
  .breakdown-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 16px;
    margin-bottom: 20px;
  }
  @media (max-width: 980px) { .breakdown-grid { grid-template-columns: 1fr; } }
  .bar-list { display: flex; flex-direction: column; gap: 10px; margin-top: 8px; }
  .bar-row-top { display: flex; justify-content: space-between; font-size: 12.5px; margin-bottom: 4px; }
  .bar-row-top .val { color: var(--text-muted); font-variant-numeric: tabular-nums; }
  .bar-track { height: 7px; background: rgba(148, 163, 184, 0.12); border-radius: 999px; overflow: hidden; }
  .bar-fill { height: 100%; border-radius: 999px; transition: width 0.35s ease; }

  /* Tabs & Tables */
  .tabs-bar {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 14px;
  }
  .tab-pills { display: flex; flex-wrap: wrap; gap: 8px; }
  .tab-pill {
    background: var(--bg-elev);
    border: 1px solid var(--border);
    color: var(--text-muted);
    padding: 8px 15px;
    border-radius: 10px;
    font-size: 13px;
    font-weight: 600;
    cursor: pointer;
    transition: all 0.15s;
  }
  .tab-pill.active {
    background: rgba(34, 211, 238, 0.14);
    border-color: rgba(34, 211, 238, 0.45);
    color: var(--cyan);
  }
  .table-toolbar {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    margin-bottom: 14px;
  }
  .search-input {
    background: var(--bg-elev);
    border: 1px solid var(--border-strong);
    border-radius: 10px;
    color: var(--text);
    padding: 8px 13px;
    font-size: 13px;
    min-width: 260px;
    outline: none;
  }
  .search-input:focus { border-color: var(--cyan); }
  .table-scroll { overflow-x: auto; border-radius: 10px; border: 1px solid var(--border); }
  table { width: 100%; border-collapse: collapse; font-size: 13px; }
  thead th {
    text-align: left;
    padding: 11px 14px;
    background: var(--bg-elev);
    color: var(--text-muted);
    font-weight: 600;
    font-size: 11.5px;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    border-bottom: 1px solid var(--border);
    white-space: nowrap;
  }
  tbody td {
    padding: 11px 14px;
    border-bottom: 1px solid rgba(148, 163, 184, 0.08);
    vertical-align: middle;
  }
  tbody tr:hover { background: rgba(255, 255, 255, 0.025); }
  tbody tr:last-child td { border-bottom: none; }
  .num { font-variant-numeric: tabular-nums; text-align: right; }
  th.num { text-align: right; }
  .mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; color: var(--text-muted); }
  .empty-state { padding: 36px; text-align: center; color: var(--text-muted); font-size: 13.5px; }
  .load-more-row { text-align: center; padding-top: 12px; }
  .footer-note {
    margin-top: 22px;
    font-size: 12px;
    color: var(--text-soft);
    display: flex;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: 8px;
  }
</style>
</head>
<body>

<div id="loginView" class="login-wrap">
  <div class="login-card">
    <div class="brand-row">
      <div class="brand-logo">V</div>
      <div>
        <h1>VidVora Analytics</h1>
        <div style="font-size:12px;color:var(--text-muted);">Desktop Chrome Admin Console</div>
      </div>
    </div>
    <p>Superadmin Master Passkey dalein — live App Install Report, 24h Hourly Views Graph aur Creator Performance dekhne ke liye.</p>
    <div id="loginError" class="error-banner"></div>
    <form id="loginForm">
      <label class="field-label" for="passkeyInput">Superadmin Master Passkey</label>
      <div class="pass-box">
        <input id="passkeyInput" class="text-input" type="password" autocomplete="current-password" placeholder="Enter Superadmin Passkey..." required />
        <button type="button" id="togglePassBtn" class="eye-btn" title="Show/Hide">Show</button>
      </div>
      <button id="loginSubmitBtn" type="submit" class="btn-primary">Open Dashboard</button>
    </form>
  </div>
</div>

<div id="appView" class="app-shell">
  <header class="topbar">
    <div class="topbar-inner">
      <div class="topbar-left">
        <div class="brand-logo" style="width:36px;height:36px;font-size:17px;border-radius:10px;">V</div>
        <div>
          <div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap;">
            <span class="topbar-title">VidVora Analytics</span>
            <span class="live-pill"><span class="live-dot"></span>Live RAM Cache (0 Extra Neon Cost)</span>
          </div>
          <div id="lastUpdatedText" class="updated-text">Loading live metrics...</div>
        </div>
      </div>
      <div class="topbar-controls">
        <div class="seg-group" id="rangeGroup">
          <button class="seg-btn" data-days="7">7 Days</button>
          <button class="seg-btn active" data-days="14">14 Days</button>
          <button class="seg-btn" data-days="30">30 Days</button>
        </div>
        <button id="autoRefreshBtn" class="ctrl-btn on">Auto 30s: ON</button>
        <button id="refreshNowBtn" class="ctrl-btn">Refresh</button>
        <button id="logoutBtn" class="ctrl-btn danger">Logout</button>
      </div>
    </div>
  </header>

  <main class="container">
    <!-- KPI Cards -->
    <section class="kpi-grid">
      <div class="kpi-card" style="--accent:#22d3ee;">
        <div class="kpi-label">Total Real Installs</div>
        <div id="kpiTotalInstalls" class="kpi-value">-</div>
        <div id="kpiTotalInstallsSub" class="kpi-sub"></div>
      </div>
      <div class="kpi-card" style="--accent:#10b981;">
        <div class="kpi-label">Active Installed</div>
        <div id="kpiActiveInstalled" class="kpi-value">-</div>
        <div id="kpiActiveInstalledSub" class="kpi-sub"></div>
      </div>
      <div class="kpi-card" style="--accent:#3b82f6;">
        <div class="kpi-label">Active Users (24h)</div>
        <div id="kpiActive24h" class="kpi-value">-</div>
        <div id="kpiActive24hSub" class="kpi-sub"></div>
      </div>
      <div class="kpi-card" style="--accent:#a78bfa;">
        <div class="kpi-label">Total Real Views</div>
        <div id="kpiTotalViews" class="kpi-value">-</div>
        <div id="kpiTotalViewsSub" class="kpi-sub"></div>
      </div>
      <div class="kpi-card" style="--accent:#f59e0b;">
        <div class="kpi-label">Views (Last 24h)</div>
        <div id="kpiViews24h" class="kpi-value">-</div>
        <div id="kpiViews24hSub" class="kpi-sub"></div>
      </div>
      <div class="kpi-card" style="--accent:#f43f5e;">
        <div class="kpi-label">Videos &amp; Creators</div>
        <div id="kpiVideosCreators" class="kpi-value">-</div>
        <div id="kpiVideosCreatorsSub" class="kpi-sub"></div>
      </div>
    </section>

    <!-- Interactive Graphs -->
    <section class="charts-grid">
      <div class="panel">
        <div class="panel-head">
          <div>
            <div class="panel-title">App Installs Graph</div>
            <div id="installsChartSub" class="panel-sub">Daily real installs &amp; active devices (IST)</div>
          </div>
          <div class="seg-group" id="installChartMode">
            <button class="seg-btn active" data-mode="daily">Daily</button>
            <button class="seg-btn" data-mode="cumulative">Total Growth</button>
          </div>
        </div>
        <div class="chart-wrap">
          <canvas id="installsCanvas" class="chart-canvas"></canvas>
          <div id="installsTooltip" class="chart-tooltip"></div>
        </div>
        <div id="installsLegend" class="chart-legend"></div>
      </div>

      <div class="panel">
        <div class="panel-head">
          <div>
            <div class="panel-title">Views Graph</div>
            <div id="viewsChartSub" class="panel-sub">Hourly views over last 24 hours (IST)</div>
          </div>
          <div class="seg-group" id="viewsChartMode">
            <button class="seg-btn active" data-mode="hourly">Last 24 Hours</button>
            <button class="seg-btn" data-mode="daily">Daily Trend</button>
          </div>
        </div>
        <div class="chart-wrap">
          <canvas id="viewsCanvas" class="chart-canvas"></canvas>
          <div id="viewsTooltip" class="chart-tooltip"></div>
        </div>
        <div id="viewsLegend" class="chart-legend"></div>
      </div>
    </section>

    <!-- Country & City Views Section -->
    <section class="charts-grid" style="margin-bottom:20px;">
      <div class="panel">
        <div class="panel-head">
          <div>
            <div class="panel-title">Views by Country</div>
            <div id="geoCountriesSub" class="panel-sub">Kaunse country se sabse zyada views aa rahe hain</div>
          </div>
          <div class="seg-group" id="geoPeriodCards">
            <button class="seg-btn active" data-period="all">All Time</button>
            <button class="seg-btn" data-period="7d">7 Days</button>
            <button class="seg-btn" data-period="today">Today</button>
          </div>
        </div>
        <div id="geoCountriesList" class="bar-list"></div>
      </div>

      <div class="panel">
        <div class="panel-head">
          <div>
            <div class="panel-title">Views by City</div>
            <div id="geoCitiesSub" class="panel-sub">Top cities jahan se log video dekh rahe hain</div>
          </div>
          <div id="geoSummaryPill" class="badge-cyan">0 Locations</div>
        </div>
        <div id="geoCitiesList" class="bar-list"></div>
      </div>
    </section>

    <!-- Breakdown Cards -->
    <section class="breakdown-grid">
      <div class="panel">
        <div class="panel-title">Top Phone Brands</div>
        <div class="panel-sub">Real user devices</div>
        <div id="brandsList" class="bar-list"></div>
      </div>
      <div class="panel">
        <div class="panel-title">App Versions</div>
        <div class="panel-sub">Installed APK versions</div>
        <div id="versionsList" class="bar-list"></div>
      </div>
      <div class="panel">
        <div class="panel-title">Android OS Versions</div>
        <div class="panel-sub">OS distribution</div>
        <div id="androidList" class="bar-list"></div>
      </div>
    </section>

    <!-- Detailed Reports Tabs -->
    <section class="panel">
      <div class="tabs-bar">
        <div class="tab-pills" id="reportTabs">
          <button class="tab-pill active" data-tab="installs">App Install Report</button>
          <button class="tab-pill" data-tab="geo">Country &amp; City Views</button>
          <button class="tab-pill" data-tab="creators">Creators &amp; Views</button>
          <button class="tab-pill" data-tab="videos">Top Videos</button>
        </div>
        <div id="emulatorHiddenBadge" class="badge-cyan" style="display:none;"></div>
      </div>

      <!-- Tab 1: Install Report -->
      <div id="tab-installs">
        <div class="table-toolbar">
          <div style="display:flex;flex-wrap:wrap;gap:8px;align-items:center;">
            <div class="seg-group" id="deviceStatusFilter">
              <button class="seg-btn active" data-status="ALL">All</button>
              <button class="seg-btn" data-status="INSTALLED">Installed</button>
              <button class="seg-btn" data-status="UNINSTALLED">Uninstalled</button>
            </div>
            <input id="deviceSearchInput" class="search-input" type="search" placeholder="Search model, brand, version, ID..." />
          </div>
          <button id="exportDevicesCsvBtn" class="ctrl-btn">Download Install CSV (Excel)</button>
        </div>
        <div class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>#</th>
                <th>Device Model</th>
                <th>Brand</th>
                <th>App Ver</th>
                <th>Android</th>
                <th class="num">Sessions</th>
                <th>First Installed (IST)</th>
                <th>Last Active (IST)</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody id="devicesTableBody"></tbody>
          </table>
        </div>
        <div id="devicesLoadMoreWrap" class="load-more-row" style="display:none;">
          <button id="devicesLoadMoreBtn" class="ctrl-btn">Show More Devices</button>
        </div>
      </div>

      <!-- Tab 1b: Country & City Views -->
      <div id="tab-geo" style="display:none;">
        <div class="table-toolbar">
          <div style="display:flex;flex-wrap:wrap;gap:8px;align-items:center;">
            <div class="seg-group" id="geoSubViewGroup">
              <button class="seg-btn active" data-view="cities">Top Cities</button>
              <button class="seg-btn" data-view="countries">Top Countries</button>
              <button class="seg-btn" data-view="recent">Live Recent Views</button>
            </div>
            <input id="geoSearchInput" class="search-input" type="search" placeholder="Search city, state, country, video..." />
          </div>
          <button id="exportGeoCsvBtn" class="ctrl-btn">Download Geo CSV (Excel)</button>
        </div>
        <div class="table-scroll">
          <table>
            <thead id="geoTableHead"></thead>
            <tbody id="geoTableBody"></tbody>
          </table>
        </div>
      </div>

      <!-- Tab 2: Creators -->
      <div id="tab-creators" style="display:none;">
        <div class="table-toolbar">
          <input id="creatorSearchInput" class="search-input" type="search" placeholder="Search creator name or email..." />
          <button id="exportCreatorsCsvBtn" class="ctrl-btn">Download Creators CSV (Excel)</button>
        </div>
        <div class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>#</th>
                <th>Creator</th>
                <th>Role</th>
                <th class="num">Videos</th>
                <th class="num">24h Views</th>
                <th class="num">Real Views</th>
                <th class="num">Credited Views</th>
                <th class="num">Earnings (INR)</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody id="creatorsTableBody"></tbody>
          </table>
        </div>
      </div>

      <!-- Tab 3: Top Videos -->
      <div id="tab-videos" style="display:none;">
        <div class="table-toolbar">
          <input id="videoSearchInput" class="search-input" type="search" placeholder="Search video title or uploader..." />
          <div style="font-size:12px;color:var(--text-muted);">Top 30 highest-viewed videos</div>
        </div>
        <div class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>#</th>
                <th>Video Title</th>
                <th>Uploader</th>
                <th class="num">Real Views</th>
                <th>Uploaded (IST)</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody id="videosTableBody"></tbody>
          </table>
        </div>
      </div>
    </section>

    <div class="footer-note">
      <span>VidVora Server Analytics — Real-time data directly from Render RAM cache.</span>
      <span id="footerStatsNote"></span>
    </div>
  </main>
</div>

<script>
(function() {
  const state = {
    days: 14,
    installChartMode: "daily",
    viewsChartMode: "hourly",
    activeTab: "installs",
    geoPeriod: "all",
    geoSubView: "cities",
    deviceStatus: "ALL",
    deviceLimit: 80,
    autoRefresh: true,
    timerId: null,
    data: null,
    chartGeom: {},
  };

  const $ = (id) => document.getElementById(id);
  const fmtNum = (n) => Number(n || 0).toLocaleString("en-IN");
  const fmtInr = (n) => "₹" + Number(n || 0).toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const fmtDateIst = (ms) => {
    if (!ms || ms <= 0) return "-";
    try {
      return new Intl.DateTimeFormat("en-IN", {
        timeZone: "Asia/Kolkata",
        day: "2-digit",
        month: "short",
        hour: "2-digit",
        minute: "2-digit",
        hour12: true,
      }).format(new Date(ms));
    } catch (_) {
      return new Date(ms).toLocaleString();
    }
  };
  const esc = (s) => String(s == null ? "" : s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");

  function showLogin(errorMsg) {
    $("appView").style.display = "none";
    $("loginView").style.display = "flex";
    stopAutoRefresh();
    if (errorMsg) {
      $("loginError").textContent = errorMsg;
      $("loginError").style.display = "block";
    } else {
      $("loginError").style.display = "none";
    }
  }

  function showApp() {
    $("loginView").style.display = "none";
    $("appView").style.display = "block";
    startAutoRefresh();
  }

  function stopAutoRefresh() {
    if (state.timerId) {
      clearInterval(state.timerId);
      state.timerId = null;
    }
  }

  function startAutoRefresh() {
    stopAutoRefresh();
    if (!state.autoRefresh) return;
    state.timerId = setInterval(() => {
      if (document.visibilityState === "visible") {
        loadStats(true);
      }
    }, 30000);
  }

  async function loadStats(silent = false) {
    if (!silent) {
      $("lastUpdatedText").textContent = "Refreshing live stats...";
    }
    try {
      const res = await fetch("/api/admin-dashboard/stats?days=" + encodeURIComponent(state.days), {
        credentials: "same-origin",
        cache: "no-store",
      });
      if (res.status === 401 || res.status === 403) {
        showLogin();
        return;
      }
      if (!res.ok) throw new Error("Server returned " + res.status);
      const data = await res.json();
      state.data = data;
      showApp();
      renderAll();
    } catch (err) {
      $("lastUpdatedText").textContent = "Refresh failed: " + (err.message || err);
    }
  }

  function renderAll() {
    if (!state.data) return;
    const { summary, breakdowns, generatedAtMs } = state.data;
    $("lastUpdatedText").textContent = "Updated: " + fmtDateIst(generatedAtMs) + " IST";

    // KPIs
    $("kpiTotalInstalls").textContent = fmtNum(summary.totalDownloads);
    $("kpiTotalInstallsSub").innerHTML =
      '<span class="badge-cyan">+' + fmtNum(summary.downloadsToday) + ' Today</span>' +
      (summary.hiddenEmulatorCount > 0
        ? '<span>' + fmtNum(summary.hiddenEmulatorCount) + ' emulators filtered</span>'
        : '<span>Real Android devices</span>');

    $("kpiActiveInstalled").textContent = fmtNum(summary.currentlyInstalled);
    $("kpiActiveInstalledSub").innerHTML =
      '<span class="badge-good">' + summary.retentionPercent + '% Retained</span>' +
      '<span class="badge-rose">' + fmtNum(summary.uninstalledCount) + ' Uninstalled</span>';

    $("kpiActive24h").textContent = fmtNum(summary.activeLast24h);
    $("kpiActive24hSub").innerHTML =
      '<span>7-day active: <strong style="color:#fff">' + fmtNum(summary.activeLast7d) + '</strong></span>';

    $("kpiTotalViews").textContent = fmtNum(summary.totalRealViews);
    $("kpiTotalViewsSub").innerHTML =
      '<span>Credited: <strong style="color:#fff">' + fmtNum(summary.totalCreditedViews) + '</strong></span>';

    $("kpiViews24h").textContent = fmtNum(summary.viewsLast24h);
    $("kpiViews24hSub").innerHTML =
      '<span class="badge-warn">Today IST: ' + fmtNum(summary.viewsToday) + '</span>';

    $("kpiVideosCreators").textContent = fmtNum(summary.activeVideos) + " / " + fmtNum(summary.totalCreators);
    $("kpiVideosCreatorsSub").innerHTML =
      '<span class="badge-good">+' + fmtNum(summary.videosUploadedToday) + ' vids today</span>' +
      (summary.pendingWithdrawalCount > 0
        ? '<span class="badge-warn">' + summary.pendingWithdrawalCount + ' pending payout</span>'
        : '<span>Payouts clear</span>');

    if (summary.hiddenEmulatorCount > 0) {
      $("emulatorHiddenBadge").style.display = "inline-block";
      $("emulatorHiddenBadge").textContent = fmtNum(summary.hiddenEmulatorCount) + " Emulator Test Records Hidden";
    } else {
      $("emulatorHiddenBadge").style.display = "none";
    }

    $("footerStatsNote").textContent =
      "Paid Payouts: " + fmtInr(summary.paidWithdrawalAmount) +
      " | Pending: " + fmtInr(summary.pendingWithdrawalAmount);

    renderBreakdown("brandsList", breakdowns.brands, "#22d3ee");
    renderBreakdown("versionsList", breakdowns.appVersions, "#10b981");
    renderBreakdown("androidList", breakdowns.androidVersions, "#a78bfa");

    drawInstallsChart();
    drawViewsChart();
    renderGeoCards();
    renderGeoTable();
    renderDevicesTable();
    renderCreatorsTable();
    renderVideosTable();
  }

  function renderBreakdown(containerId, items, color) {
    const el = $(containerId);
    if (!items || !items.length) {
      el.innerHTML = '<div class="empty-state" style="padding:18px 0;">No data yet</div>';
      return;
    }
    el.innerHTML = items.map((item) => {
      const pct = Math.max(2, Math.min(100, item.percent));
      return '<div>' +
        '<div class="bar-row-top"><span>' + esc(item.label) + '</span><span class="val">' +
        fmtNum(item.count) + ' (' + item.percent + '%)</span></div>' +
        '<div class="bar-track"><div class="bar-fill" style="width:' + pct + '%;background:' + color + ';"></div></div>' +
        '</div>';
    }).join("");
  }

  function getGeoMetricField() {
    if (state.geoPeriod === "today") return { countKey: "viewsToday", pctKey: "percentToday", label: "Today (IST)" };
    if (state.geoPeriod === "7d") return { countKey: "views7d", pctKey: "percent7d", label: "Last 7 Days" };
    return { countKey: "viewsAll", pctKey: "percentAll", label: "All Time" };
  }

  function renderGeoCards() {
    const geo = (state.data && state.data.geo) || { countries: [], cities: [], totalTrackedViews: 0 };
    const metric = getGeoMetricField();

    const countriesSorted = (geo.countries || [])
      .filter((c) => (c[metric.countKey] || 0) > 0)
      .sort((a, b) => (b[metric.countKey] || 0) - (a[metric.countKey] || 0));
    const citiesSorted = (geo.cities || [])
      .filter((c) => (c[metric.countKey] || 0) > 0)
      .sort((a, b) => (b[metric.countKey] || 0) - (a[metric.countKey] || 0));

    const totalForPeriod = state.geoPeriod === "today"
      ? (geo.totalTodayViews || 0)
      : state.geoPeriod === "7d"
        ? (geo.total7dViews || 0)
        : (geo.totalTrackedViews || 0);

    $("geoCountriesSub").textContent =
      metric.label + " — " + fmtNum(totalForPeriod) + " geo-tracked views across " + fmtNum(countriesSorted.length) + " countries";
    $("geoCitiesSub").textContent =
      metric.label + " — Top cities jahan se viewers video dekh rahe hain";
    $("geoSummaryPill").textContent =
      fmtNum(geo.uniqueCountriesCount || 0) + " Countries • " + fmtNum(geo.uniqueCitiesCount || 0) + " Cities";

    if (!countriesSorted.length) {
      $("geoCountriesList").innerHTML =
        '<div class="empty-state" style="padding:20px 0;">Geo tracking abhi active hua hai — jaise hi naye video views aayenge, Country yahan live dikhne lagenge!</div>';
    } else {
      renderBreakdown(
        "geoCountriesList",
        countriesSorted.slice(0, 8).map((c) => ({
          label: (c.flag ? c.flag + " " : "") + c.country,
          count: c[metric.countKey] || 0,
          percent: c[metric.pctKey] || 0,
        })),
        "#22d3ee"
      );
    }

    if (!citiesSorted.length) {
      $("geoCitiesList").innerHTML =
        '<div class="empty-state" style="padding:20px 0;">Jaise hi users videos play karenge, City aur State yahan live update ho jayenge!</div>';
    } else {
      renderBreakdown(
        "geoCitiesList",
        citiesSorted.slice(0, 8).map((c) => ({
          label: (c.flag ? c.flag + " " : "") + c.cityDisplay + " (" + c.country + ")",
          count: c[metric.countKey] || 0,
          percent: c[metric.pctKey] || 0,
        })),
        "#f59e0b"
      );
    }
  }

  function renderGeoTable() {
    const geo = (state.data && state.data.geo) || { countries: [], cities: [], recentLocations: [] };
    const q = ($("geoSearchInput").value || "").trim().toLowerCase();
    const head = $("geoTableHead");
    const body = $("geoTableBody");

    if (state.geoSubView === "countries") {
      head.innerHTML =
        '<tr><th>#</th><th>Country</th><th>Code</th><th class="num">Today (IST)</th><th class="num">Last 7 Days</th><th class="num">All-Time Views</th><th class="num">Share %</th></tr>';
      const rows = (geo.countries || []).filter((c) => {
        if (!q) return true;
        return c.country.toLowerCase().includes(q) || (c.countryCode || "").toLowerCase().includes(q);
      });
      if (!rows.length) {
        body.innerHTML = '<tr><td colspan="7" class="empty-state">No country view data yet — naye views aate hi yahan live show honge.</td></tr>';
        return;
      }
      body.innerHTML = rows.map((c, i) =>
        '<tr>' +
        '<td class="mono">' + (i + 1) + '</td>' +
        '<td><strong>' + esc((c.flag ? c.flag + " " : "") + c.country) + '</strong></td>' +
        '<td class="mono">' + esc(c.countryCode || "-") + '</td>' +
        '<td class="num" style="color:#fcd34d;font-weight:700;">' + fmtNum(c.viewsToday) + '</td>' +
        '<td class="num">' + fmtNum(c.views7d) + '</td>' +
        '<td class="num" style="color:#67e8f9;font-weight:700;">' + fmtNum(c.viewsAll) + '</td>' +
        '<td class="num">' + c.percentAll + '%</td>' +
        '</tr>'
      ).join("");
      return;
    }

    if (state.geoSubView === "recent") {
      head.innerHTML =
        '<tr><th>#</th><th>City &amp; State</th><th>Country</th><th>Video Watched</th><th>Time (IST)</th></tr>';
      const rows = (geo.recentLocations || []).filter((r) => {
        if (!q) return true;
        return (
          (r.city || "").toLowerCase().includes(q) ||
          (r.region || "").toLowerCase().includes(q) ||
          (r.country || "").toLowerCase().includes(q) ||
          (r.videoTitle || "").toLowerCase().includes(q)
        );
      });
      if (!rows.length) {
        body.innerHTML = '<tr><td colspan="5" class="empty-state">No recent viewer locations yet — jaise hi koi video play karega, live location yahan dikhega.</td></tr>';
        return;
      }
      body.innerHTML = rows.map((r, i) =>
        '<tr>' +
        '<td class="mono">' + (i + 1) + '</td>' +
        '<td><strong>' + esc(r.city) + '</strong>' + (r.region ? '<div class="mono">' + esc(r.region) + '</div>' : '') + '</td>' +
        '<td>' + esc((r.flag ? r.flag + " " : "") + r.country) + '</td>' +
        '<td>' + esc(r.videoTitle) + '</td>' +
        '<td>' + esc(fmtDateIst(r.atMs)) + '</td>' +
        '</tr>'
      ).join("");
      return;
    }

    // Default: cities
    head.innerHTML =
      '<tr><th>#</th><th>City</th><th>State / Region</th><th>Country</th><th class="num">Today (IST)</th><th class="num">Last 7 Days</th><th class="num">All-Time Views</th><th class="num">Share %</th></tr>';
    const rows = (geo.cities || []).filter((c) => {
      if (!q) return true;
      return (
        (c.city || "").toLowerCase().includes(q) ||
        (c.region || "").toLowerCase().includes(q) ||
        (c.country || "").toLowerCase().includes(q) ||
        (c.countryCode || "").toLowerCase().includes(q)
      );
    });
    if (!rows.length) {
      body.innerHTML = '<tr><td colspan="8" class="empty-state">No city view data yet — naye views aate hi yahan live show honge.</td></tr>';
      return;
    }
    body.innerHTML = rows.map((c, i) =>
      '<tr>' +
      '<td class="mono">' + (i + 1) + '</td>' +
      '<td><strong>' + esc(c.city) + '</strong></td>' +
      '<td>' + esc(c.region || "-") + '</td>' +
      '<td>' + esc((c.flag ? c.flag + " " : "") + c.country) + '</td>' +
      '<td class="num" style="color:#fcd34d;font-weight:700;">' + fmtNum(c.viewsToday) + '</td>' +
      '<td class="num">' + fmtNum(c.views7d) + '</td>' +
      '<td class="num" style="color:#67e8f9;font-weight:700;">' + fmtNum(c.viewsAll) + '</td>' +
      '<td class="num">' + c.percentAll + '%</td>' +
      '</tr>'
    ).join("");
  }

  // ==================== INTERACTIVE CANVAS CHART ENGINE ====================
  function setupCanvas(canvas) {
    const rect = canvas.parentElement.getBoundingClientRect();
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const w = Math.max(300, Math.floor(rect.width));
    const h = Math.max(220, Math.floor(rect.height));
    if (canvas.width !== w * dpr || canvas.height !== h * dpr) {
      canvas.width = w * dpr;
      canvas.height = h * dpr;
    }
    const ctx = canvas.getContext("2d");
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    return { ctx, w, h };
  }

  function niceMax(val) {
    if (val <= 0) return 5;
    if (val <= 5) return 5;
    const exp = Math.pow(10, Math.floor(Math.log10(val)));
    const frac = val / exp;
    const niceFrac = frac <= 1.2 ? 1.2 : frac <= 1.5 ? 1.5 : frac <= 2 ? 2 : frac <= 3 ? 3 : frac <= 5 ? 5 : frac <= 8 ? 8 : 10;
    return Math.ceil(niceFrac * exp);
  }

  function compactAxis(n) {
    if (n >= 1000000) return (n / 1000000).toFixed(1).replace(/\\.0$/, "") + "M";
    if (n >= 1000) return (n / 1000).toFixed(1).replace(/\\.0$/, "") + "K";
    return String(Math.round(n));
  }

  function drawComboChart({
    canvasId,
    tooltipId,
    points,
    barColor,
    lineColor,
    areaMode = false,
    hoverIdx = -1,
    formatTooltip,
  }) {
    const canvas = $(canvasId);
    const { ctx, w, h } = setupCanvas(canvas);
    const pad = { top: 18, right: 16, bottom: 34, left: 46 };
    const plotW = w - pad.left - pad.right;
    const plotH = h - pad.top - pad.bottom;

    if (!points || !points.length) {
      ctx.fillStyle = "#64748b";
      ctx.font = "13px sans-serif";
      ctx.textAlign = "center";
      ctx.fillText("No data available yet", w / 2, h / 2);
      return;
    }

    let rawMax = 0;
    for (const p of points) {
      if (p.primary > rawMax) rawMax = p.primary;
      if (typeof p.secondary === "number" && p.secondary > rawMax) rawMax = p.secondary;
    }
    const yMax = niceMax(rawMax);
    const gridLines = 4;

    // Grid & Y labels
    ctx.strokeStyle = "rgba(148, 163, 184, 0.11)";
    ctx.lineWidth = 1;
    ctx.fillStyle = "#64748b";
    ctx.font = "11px sans-serif";
    ctx.textAlign = "right";
    ctx.textBaseline = "middle";

    for (let i = 0; i <= gridLines; i += 1) {
      const ratio = i / gridLines;
      const y = pad.top + plotH * (1 - ratio);
      ctx.beginPath();
      ctx.moveTo(pad.left, y);
      ctx.lineTo(w - pad.right, y);
      ctx.stroke();
      ctx.fillText(compactAxis(yMax * ratio), pad.left - 8, y);
    }

    const n = points.length;
    const stepX = plotW / n;
    const coords = points.map((p, idx) => {
      const cx = pad.left + stepX * idx + stepX / 2;
      const y1 = pad.top + plotH * (1 - p.primary / yMax);
      const y2 = typeof p.secondary === "number" ? pad.top + plotH * (1 - p.secondary / yMax) : null;
      return { cx, y1, y2, p, idx };
    });

    // Hover vertical guide
    if (hoverIdx >= 0 && hoverIdx < coords.length) {
      const hc = coords[hoverIdx];
      ctx.fillStyle = "rgba(255, 255, 255, 0.045)";
      ctx.fillRect(pad.left + stepX * hoverIdx, pad.top, stepX, plotH);
    }

    if (areaMode) {
      const grad = ctx.createLinearGradient(0, pad.top, 0, pad.top + plotH);
      grad.addColorStop(0, barColor + "55");
      grad.addColorStop(1, barColor + "02");

      ctx.beginPath();
      coords.forEach((c, i) => {
        if (i === 0) ctx.moveTo(c.cx, c.y1);
        else ctx.lineTo(c.cx, c.y1);
      });
      ctx.lineTo(coords[coords.length - 1].cx, pad.top + plotH);
      ctx.lineTo(coords[0].cx, pad.top + plotH);
      ctx.closePath();
      ctx.fillStyle = grad;
      ctx.fill();

      ctx.beginPath();
      coords.forEach((c, i) => {
        if (i === 0) ctx.moveTo(c.cx, c.y1);
        else ctx.lineTo(c.cx, c.y1);
      });
      ctx.strokeStyle = barColor;
      ctx.lineWidth = 2.5;
      ctx.stroke();

      coords.forEach((c, i) => {
        const isHover = i === hoverIdx;
        if (isHover || n <= 16) {
          ctx.beginPath();
          ctx.arc(c.cx, c.y1, isHover ? 5.5 : 3, 0, Math.PI * 2);
          ctx.fillStyle = isHover ? "#ffffff" : barColor;
          ctx.fill();
          if (isHover) {
            ctx.strokeStyle = barColor;
            ctx.lineWidth = 2;
            ctx.stroke();
          }
        }
      });
    } else {
      // Bar series
      const barW = Math.max(4, Math.min(28, stepX * 0.56));
      coords.forEach((c, i) => {
        const barH = Math.max(c.p.primary > 0 ? 3 : 0, pad.top + plotH - c.y1);
        const x = c.cx - barW / 2;
        const y = pad.top + plotH - barH;
        const grad = ctx.createLinearGradient(0, y, 0, pad.top + plotH);
        grad.addColorStop(0, i === hoverIdx ? "#ffffff" : barColor);
        grad.addColorStop(1, barColor + "66");
        ctx.fillStyle = grad;
        const r = Math.min(4, barW / 2, barH);
        ctx.beginPath();
        ctx.moveTo(x, y + r);
        ctx.quadraticCurveTo(x, y, x + r, y);
        ctx.lineTo(x + barW - r, y);
        ctx.quadraticCurveTo(x + barW, y, x + barW, y + r);
        ctx.lineTo(x + barW, pad.top + plotH);
        ctx.lineTo(x, pad.top + plotH);
        ctx.closePath();
        ctx.fill();
      });

      // Optional secondary line
      if (lineColor && coords.some((c) => c.y2 !== null)) {
        ctx.beginPath();
        coords.forEach((c, i) => {
          if (i === 0) ctx.moveTo(c.cx, c.y2);
          else ctx.lineTo(c.cx, c.y2);
        });
        ctx.strokeStyle = lineColor;
        ctx.lineWidth = 2.2;
        ctx.stroke();

        coords.forEach((c, i) => {
          if (i === hoverIdx || n <= 16) {
            ctx.beginPath();
            ctx.arc(c.cx, c.y2, i === hoverIdx ? 5 : 2.8, 0, Math.PI * 2);
            ctx.fillStyle = i === hoverIdx ? "#fff" : lineColor;
            ctx.fill();
          }
        });
      }
    }

    // X-axis labels
    const maxLabels = Math.max(4, Math.floor(plotW / 58));
    const labelEvery = Math.max(1, Math.ceil(n / maxLabels));
    ctx.fillStyle = "#94a3b8";
    ctx.font = "11px sans-serif";
    ctx.textAlign = "center";
    ctx.textBaseline = "top";
    coords.forEach((c, i) => {
      if (i % labelEvery === 0 || i === n - 1) {
        ctx.fillText(c.p.label, c.cx, pad.top + plotH + 10);
      }
    });

    state.chartGeom[canvasId] = { pad, stepX, n, coords, tooltipId, formatTooltip, redrawArgs: arguments[0] };
  }

  function bindChartHover(canvasId) {
    const canvas = $(canvasId);
    canvas.addEventListener("mousemove", (ev) => {
      const geom = state.chartGeom[canvasId];
      if (!geom) return;
      const rect = canvas.getBoundingClientRect();
      const x = ev.clientX - rect.left;
      const idx = Math.floor((x - geom.pad.left) / geom.stepX);
      const tt = $(geom.tooltipId);
      if (idx < 0 || idx >= geom.n) {
        tt.style.display = "none";
        drawComboChart({ ...geom.redrawArgs, hoverIdx: -1 });
        return;
      }
      drawComboChart({ ...geom.redrawArgs, hoverIdx: idx });
      const c = geom.coords[idx];
      tt.innerHTML = geom.formatTooltip(c.p);
      tt.style.display = "block";
      const ttW = tt.offsetWidth || 170;
      let left = c.cx + 14;
      if (left + ttW > rect.width - 8) left = Math.max(8, c.cx - ttW - 14);
      tt.style.left = left + "px";
      tt.style.top = Math.max(8, Math.min(rect.height - 90, c.y1 - 24)) + "px";
    });
    canvas.addEventListener("mouseleave", () => {
      const geom = state.chartGeom[canvasId];
      if (!geom) return;
      $(geom.tooltipId).style.display = "none";
      drawComboChart({ ...geom.redrawArgs, hoverIdx: -1 });
    });
  }

  function drawInstallsChart() {
    if (!state.data) return;
    const rows = state.data.charts.dailyInstalls || [];
    const isCum = state.installChartMode === "cumulative";
    $("installsChartSub").textContent = isCum
      ? "Total cumulative real app installs growth (IST)"
      : "Daily new real installs & active devices (IST)";
    $("installsLegend").innerHTML = isCum
      ? '<span class="legend-item"><span class="legend-dot" style="background:#22d3ee"></span>Total Real Installs</span>'
      : '<span class="legend-item"><span class="legend-dot" style="background:#22d3ee"></span>New Installs</span>' +
        '<span class="legend-item"><span class="legend-dot" style="background:#10b981"></span>Active Devices</span>';

    const points = rows.map((r) => ({
      label: r.label,
      fullLabel: r.date + " (IST)",
      primary: isCum ? r.cumulativeInstalls : r.newInstalls,
      secondary: isCum ? null : r.activeDevices,
      raw: r,
    }));

    drawComboChart({
      canvasId: "installsCanvas",
      tooltipId: "installsTooltip",
      points,
      barColor: "#22d3ee",
      lineColor: isCum ? null : "#10b981",
      areaMode: isCum,
      formatTooltip: (p) =>
        '<div class="tt-title">' + esc(p.fullLabel) + '</div>' +
        '<div class="tt-row"><span>New Installs</span><strong>' + fmtNum(p.raw.newInstalls) + '</strong></div>' +
        '<div class="tt-row"><span>Active Devices</span><strong>' + fmtNum(p.raw.activeDevices) + '</strong></div>' +
        '<div class="tt-row"><span>Total Cumulative</span><strong>' + fmtNum(p.raw.cumulativeInstalls) + '</strong></div>',
    });
  }

  function drawViewsChart() {
    if (!state.data) return;
    const isHourly = state.viewsChartMode === "hourly";
    if (isHourly) {
      const rows = state.data.charts.hourlyViews24h || [];
      $("viewsChartSub").textContent = "Hourly creator views over last 24 hours (IST)";
      $("viewsLegend").innerHTML =
        '<span class="legend-item"><span class="legend-dot" style="background:#f59e0b"></span>Hourly Views (IST)</span>';
      const points = rows.map((r) => ({
        label: r.label,
        fullLabel: r.fullLabel,
        primary: r.views,
        secondary: null,
        raw: r,
      }));
      drawComboChart({
        canvasId: "viewsCanvas",
        tooltipId: "viewsTooltip",
        points,
        barColor: "#f59e0b",
        lineColor: null,
        areaMode: true,
        formatTooltip: (p) =>
          '<div class="tt-title">' + esc(p.fullLabel) + '</div>' +
          '<div class="tt-row"><span>Views in Hour</span><strong>' + fmtNum(p.raw.views) + '</strong></div>',
      });
    } else {
      const rows = state.data.charts.dailyViews || [];
      $("viewsChartSub").textContent = "Daily tracked views & video uploads (IST)";
      $("viewsLegend").innerHTML =
        '<span class="legend-item"><span class="legend-dot" style="background:#a78bfa"></span>Tracked Views / Day</span>' +
        '<span class="legend-item"><span class="legend-dot" style="background:#22d3ee"></span>Videos Uploaded</span>';
      const points = rows.map((r) => ({
        label: r.label,
        fullLabel: r.date + " (IST)",
        primary: r.trackedViews,
        secondary: null,
        raw: r,
      }));
      drawComboChart({
        canvasId: "viewsCanvas",
        tooltipId: "viewsTooltip",
        points,
        barColor: "#a78bfa",
        lineColor: null,
        areaMode: false,
        formatTooltip: (p) =>
          '<div class="tt-title">' + esc(p.fullLabel) + '</div>' +
          '<div class="tt-row"><span>Tracked Views</span><strong>' + fmtNum(p.raw.trackedViews) + '</strong></div>' +
          '<div class="tt-row"><span>Videos Uploaded</span><strong>' + fmtNum(p.raw.uploadsCount) + '</strong></div>' +
          '<div class="tt-row"><span>Total Views on Day Uploads</span><strong>' + fmtNum(p.raw.uploadTotalViews) + '</strong></div>',
      });
    }
  }

  // ==================== TABLES & CSV EXPORT ====================
  function getFilteredDevices() {
    const all = (state.data && state.data.devices) || [];
    const q = ($("deviceSearchInput").value || "").trim().toLowerCase();
    return all.filter((d) => {
      if (state.deviceStatus !== "ALL" && d.status !== state.deviceStatus) return false;
      if (!q) return true;
      return (
        d.deviceLabel.toLowerCase().includes(q) ||
        d.brand.toLowerCase().includes(q) ||
        d.appVersion.toLowerCase().includes(q) ||
        d.androidVersion.toLowerCase().includes(q) ||
        d.installId.toLowerCase().includes(q)
      );
    });
  }

  function renderDevicesTable() {
    const filtered = getFilteredDevices();
    const body = $("devicesTableBody");
    if (!filtered.length) {
      body.innerHTML = '<tr><td colspan="9" class="empty-state">No matching devices found.</td></tr>';
      $("devicesLoadMoreWrap").style.display = "none";
      return;
    }
    const slice = filtered.slice(0, state.deviceLimit);
    body.innerHTML = slice.map((d, i) => {
      const statusBadge = d.status === "INSTALLED"
        ? '<span class="badge-good">Installed</span>'
        : '<span class="badge-rose">Uninstalled</span>';
      return '<tr>' +
        '<td class="mono">' + (i + 1) + '</td>' +
        '<td><strong>' + esc(d.deviceLabel) + '</strong><div class="mono">' + esc(d.installId.slice(0, 14)) + '</div></td>' +
        '<td>' + esc(d.brand) + '</td>' +
        '<td><span class="badge-cyan">' + esc(d.appVersion) + '</span></td>' +
        '<td>' + esc(d.androidVersion) + '</td>' +
        '<td class="num">' + fmtNum(d.sessionCount) + '</td>' +
        '<td>' + esc(fmtDateIst(d.firstInstalledAtMs)) + '</td>' +
        '<td>' + esc(fmtDateIst(d.lastSeenAtMs)) + '</td>' +
        '<td>' + statusBadge + '</td>' +
        '</tr>';
    }).join("");

    if (filtered.length > state.deviceLimit) {
      $("devicesLoadMoreWrap").style.display = "block";
      $("devicesLoadMoreBtn").textContent = "Show More (" + (filtered.length - state.deviceLimit) + " remaining)";
    } else {
      $("devicesLoadMoreWrap").style.display = "none";
    }
  }

  function renderCreatorsTable() {
    const all = (state.data && state.data.creators) || [];
    const q = ($("creatorSearchInput").value || "").trim().toLowerCase();
    const filtered = all.filter((c) => {
      if (!q) return true;
      return c.name.toLowerCase().includes(q) || c.emailOrPhone.toLowerCase().includes(q) || c.memberId.toLowerCase().includes(q);
    });
    const body = $("creatorsTableBody");
    if (!filtered.length) {
      body.innerHTML = '<tr><td colspan="9" class="empty-state">No creators found.</td></tr>';
      return;
    }
    body.innerHTML = filtered.map((c, i) => {
      const statusBadge = c.isActive
        ? '<span class="badge-good">Active</span>'
        : '<span class="badge-rose">Disabled</span>';
      return '<tr>' +
        '<td class="mono">' + (i + 1) + '</td>' +
        '<td><strong>' + esc(c.name) + '</strong><div class="mono">' + esc(c.emailOrPhone) + '</div></td>' +
        '<td>' + esc(c.role) + '</td>' +
        '<td class="num">' + fmtNum(c.videosCount) + '</td>' +
        '<td class="num" style="color:#fcd34d;font-weight:700;">' + fmtNum(c.views24h) + '</td>' +
        '<td class="num" style="font-weight:700;">' + fmtNum(c.realViews) + '</td>' +
        '<td class="num">' + fmtNum(c.creditedViews) + '</td>' +
        '<td class="num" style="color:#6ee7b7;font-weight:700;">' + esc(fmtInr(c.totalEarning)) + '</td>' +
        '<td>' + statusBadge + '</td>' +
        '</tr>';
    }).join("");
  }

  function renderVideosTable() {
    const all = (state.data && state.data.topVideos) || [];
    const q = ($("videoSearchInput").value || "").trim().toLowerCase();
    const filtered = all.filter((v) => {
      if (!q) return true;
      return v.title.toLowerCase().includes(q) || v.uploaderName.toLowerCase().includes(q) || v.videoId.toLowerCase().includes(q);
    });
    const body = $("videosTableBody");
    if (!filtered.length) {
      body.innerHTML = '<tr><td colspan="6" class="empty-state">No videos found.</td></tr>';
      return;
    }
    body.innerHTML = filtered.map((v, i) => {
      const statusBadge = v.isActive
        ? '<span class="badge-good">Active</span>'
        : '<span class="badge-rose">Inactive</span>';
      return '<tr>' +
        '<td class="mono">' + (i + 1) + '</td>' +
        '<td><strong>' + esc(v.title) + '</strong><div class="mono">' + esc(v.videoId) + '</div></td>' +
        '<td>' + esc(v.uploaderName) + '</td>' +
        '<td class="num" style="color:#c4b5fd;font-weight:700;">' + fmtNum(v.totalViews) + '</td>' +
        '<td>' + esc(fmtDateIst(v.createdAtMs)) + '</td>' +
        '<td>' + statusBadge + '</td>' +
        '</tr>';
    }).join("");
  }

  function downloadCsv(filename, headers, rows) {
    const csvCell = (val) => {
      const s = String(val == null ? "" : val).replace(/"/g, '""');
      return /[",\\n\\r]/.test(s) ? '"' + s + '"' : s;
    };
    const lines = [headers.map(csvCell).join(",")];
    rows.forEach((r) => lines.push(r.map(csvCell).join(",")));
    const blob = new Blob(["\\uFEFF" + lines.join("\\r\\n")], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  // ==================== EVENT HANDLERS ====================
  $("togglePassBtn").addEventListener("click", () => {
    const inp = $("passkeyInput");
    const isPass = inp.type === "password";
    inp.type = isPass ? "text" : "password";
    $("togglePassBtn").textContent = isPass ? "Hide" : "Show";
  });

  $("loginForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const passkey = $("passkeyInput").value.trim();
    if (!passkey) return;
    const btn = $("loginSubmitBtn");
    btn.disabled = true;
    btn.textContent = "Verifying...";
    $("loginError").style.display = "none";
    try {
      const res = await fetch("/api/admin-dashboard/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "same-origin",
        body: JSON.stringify({ passkey }),
      });
      const json = await res.json().catch(() => ({}));
      if (!res.ok || !json.ok) {
        showLogin(json.error || "Invalid Superadmin Master Passkey.");
        return;
      }
      $("passkeyInput").value = "";
      await loadStats();
    } catch (err) {
      showLogin("Network error: " + (err.message || err));
    } finally {
      btn.disabled = false;
      btn.textContent = "Open Dashboard";
    }
  });

  $("logoutBtn").addEventListener("click", async () => {
    try {
      await fetch("/api/admin-dashboard/logout", { method: "POST", credentials: "same-origin" });
    } catch (_) {}
    state.data = null;
    showLogin();
  });

  $("refreshNowBtn").addEventListener("click", () => loadStats(false));

  $("autoRefreshBtn").addEventListener("click", () => {
    state.autoRefresh = !state.autoRefresh;
    $("autoRefreshBtn").textContent = state.autoRefresh ? "Auto 30s: ON" : "Auto 30s: OFF";
    $("autoRefreshBtn").classList.toggle("on", state.autoRefresh);
    if (state.autoRefresh) startAutoRefresh();
    else stopAutoRefresh();
  });

  $("rangeGroup").addEventListener("click", (e) => {
    const btn = e.target.closest("button[data-days]");
    if (!btn) return;
    state.days = parseInt(btn.dataset.days, 10) || 14;
    $("rangeGroup").querySelectorAll(".seg-btn").forEach((b) => b.classList.toggle("active", b === btn));
    loadStats(false);
  });

  $("installChartMode").addEventListener("click", (e) => {
    const btn = e.target.closest("button[data-mode]");
    if (!btn) return;
    state.installChartMode = btn.dataset.mode;
    $("installChartMode").querySelectorAll(".seg-btn").forEach((b) => b.classList.toggle("active", b === btn));
    drawInstallsChart();
  });

  $("viewsChartMode").addEventListener("click", (e) => {
    const btn = e.target.closest("button[data-mode]");
    if (!btn) return;
    state.viewsChartMode = btn.dataset.mode;
    $("viewsChartMode").querySelectorAll(".seg-btn").forEach((b) => b.classList.toggle("active", b === btn));
    drawViewsChart();
  });

  $("reportTabs").addEventListener("click", (e) => {
    const btn = e.target.closest("button[data-tab]");
    if (!btn) return;
    state.activeTab = btn.dataset.tab;
    $("reportTabs").querySelectorAll(".tab-pill").forEach((b) => b.classList.toggle("active", b === btn));
    ["installs", "geo", "creators", "videos"].forEach((t) => {
      $("tab-" + t).style.display = t === state.activeTab ? "block" : "none";
    });
  });

  $("geoPeriodCards").addEventListener("click", (e) => {
    const btn = e.target.closest("button[data-period]");
    if (!btn) return;
    state.geoPeriod = btn.dataset.period;
    $("geoPeriodCards").querySelectorAll(".seg-btn").forEach((b) => b.classList.toggle("active", b === btn));
    renderGeoCards();
  });

  $("geoSubViewGroup").addEventListener("click", (e) => {
    const btn = e.target.closest("button[data-view]");
    if (!btn) return;
    state.geoSubView = btn.dataset.view;
    $("geoSubViewGroup").querySelectorAll(".seg-btn").forEach((b) => b.classList.toggle("active", b === btn));
    renderGeoTable();
  });

  $("geoSearchInput").addEventListener("input", renderGeoTable);

  $("exportGeoCsvBtn").addEventListener("click", () => {
    const geo = (state.data && state.data.geo) || { countries: [], cities: [] };
    if (state.geoSubView === "countries") {
      const rows = (geo.countries || []).map((c) => [
        c.country,
        c.countryCode,
        c.viewsToday,
        c.views7d,
        c.viewsAll,
        c.percentAll + "%",
      ]);
      downloadCsv(
        "vidvora-country-views-" + new Date().toISOString().slice(0, 10) + ".csv",
        ["Country", "Code", "Today (IST)", "Last 7 Days", "All-Time Views", "Share %"],
        rows
      );
    } else {
      const rows = (geo.cities || []).map((c) => [
        c.city,
        c.region,
        c.country,
        c.countryCode,
        c.viewsToday,
        c.views7d,
        c.viewsAll,
        c.percentAll + "%",
      ]);
      downloadCsv(
        "vidvora-city-views-" + new Date().toISOString().slice(0, 10) + ".csv",
        ["City", "State/Region", "Country", "Code", "Today (IST)", "Last 7 Days", "All-Time Views", "Share %"],
        rows
      );
    }
  });

  $("deviceStatusFilter").addEventListener("click", (e) => {
    const btn = e.target.closest("button[data-status]");
    if (!btn) return;
    state.deviceStatus = btn.dataset.status;
    state.deviceLimit = 80;
    $("deviceStatusFilter").querySelectorAll(".seg-btn").forEach((b) => b.classList.toggle("active", b === btn));
    renderDevicesTable();
  });

  $("deviceSearchInput").addEventListener("input", () => {
    state.deviceLimit = 80;
    renderDevicesTable();
  });
  $("creatorSearchInput").addEventListener("input", renderCreatorsTable);
  $("videoSearchInput").addEventListener("input", renderVideosTable);

  $("devicesLoadMoreBtn").addEventListener("click", () => {
    state.deviceLimit += 100;
    renderDevicesTable();
  });

  $("exportDevicesCsvBtn").addEventListener("click", () => {
    const rows = getFilteredDevices().map((d) => [
      d.installId,
      d.deviceLabel,
      d.brand,
      d.appVersion,
      d.androidVersion,
      d.sessionCount,
      fmtDateIst(d.firstInstalledAtMs),
      fmtDateIst(d.lastSeenAtMs),
      d.status,
    ]);
    downloadCsv(
      "vidvora-install-report-" + new Date().toISOString().slice(0, 10) + ".csv",
      ["Install ID", "Device Model", "Brand", "App Version", "Android Version", "Sessions", "First Installed (IST)", "Last Active (IST)", "Status"],
      rows
    );
  });

  $("exportCreatorsCsvBtn").addEventListener("click", () => {
    const rows = ((state.data && state.data.creators) || []).map((c) => [
      c.memberId,
      c.name,
      c.emailOrPhone,
      c.role,
      c.videosCount,
      c.views24h,
      c.realViews,
      c.creditedViews,
      c.totalEarning,
      c.isActive ? "Active" : "Disabled",
    ]);
    downloadCsv(
      "vidvora-creators-report-" + new Date().toISOString().slice(0, 10) + ".csv",
      ["Member ID", "Name", "Email / Phone", "Role", "Videos", "24h Views", "Real Views", "Credited Views", "Earnings (INR)", "Status"],
      rows
    );
  });

  bindChartHover("installsCanvas");
  bindChartHover("viewsCanvas");

  let resizeTimer = null;
  window.addEventListener("resize", () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => {
      if (state.data) {
        drawInstallsChart();
        drawViewsChart();
      }
    }, 120);
  });

  // Auto-check existing cookie session on load
  loadStats(true);
})();
</script>
</body>
</html>`;
}

module.exports = {
  ADMIN_COOKIE_NAME,
  DEFAULT_TOKEN_TTL_MS,
  isEmulatorDeviceLabel,
  toEpochMs,
  createAdminDashboardToken,
  verifyAdminDashboardToken,
  extractDashboardTokenFromRequest,
  buildAdminDashboardStats,
  renderAdminDashboardHtml,
};
