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
    if (typeof value.__vidvora_ts__ === "string") {
      const parsed = Date.parse(value.__vidvora_ts__);
      return Number.isFinite(parsed) ? parsed : 0;
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

const TEAM_ACCESS_DOC_ID = "dashboardTeamAccess";
const MAX_TEAM_MEMBERS = 50;
const READABLE_PASSKEY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

function hashTeamPasskey(rawPasskey) {
  const clean = String(rawPasskey || "").trim();
  if (!clean) throw new Error("Passkey cannot be empty");
  const salt = crypto.randomBytes(16).toString("hex");
  const derived = crypto.scryptSync(clean, salt, 64).toString("hex");
  return `scrypt$${salt}$${derived}`;
}

function verifyTeamPasskeyHash(rawPasskey, storedHash) {
  const clean = String(rawPasskey || "").trim();
  const rawHash = String(storedHash || "").trim();
  if (!clean || !rawHash) return false;
  const parts = rawHash.split("$");
  if (parts.length !== 3 || parts[0] !== "scrypt") return false;
  const [, salt, expectedHex] = parts;
  if (!salt || !expectedHex) return false;
  try {
    const derived = crypto.scryptSync(clean, salt, 64);
    const expected = Buffer.from(expectedHex, "hex");
    if (derived.length !== expected.length) return false;
    return crypto.timingSafeEqual(derived, expected);
  } catch (_) {
    return false;
  }
}

function generateReadableTeamPasskey() {
  const bytes = crypto.randomBytes(12);
  let chars = "";
  for (let i = 0; i < 12; i++) {
    chars += READABLE_PASSKEY_ALPHABET[bytes[i] % READABLE_PASSKEY_ALPHABET.length];
  }
  return `VV-${chars.slice(0, 4)}-${chars.slice(4, 8)}-${chars.slice(8, 12)}`;
}

function sanitizeTeamMembersForResponse(members) {
  if (!Array.isArray(members)) return [];
  return members
    .filter((m) => m && typeof m === "object" && m.id && m.isActive !== false)
    .map((m) => ({
      id: String(m.id),
      label: String(m.label || "Team Member"),
      createdAtMs: toSafeNumber(m.createdAtMs, 0),
      lastLoginAtMs: toSafeNumber(m.lastLoginAtMs, 0),
      isActive: true,
    }))
    .sort((a, b) => b.createdAtMs - a.createdAtMs);
}

async function loadTeamAccessMembers(db) {
  if (!db) return [];
  try {
    const snap = await db.collection("appConfig").doc(TEAM_ACCESS_DOC_ID).get();
    if (!snap || !snap.exists) return [];
    const data = snap.data() || {};
    return Array.isArray(data.members) ? data.members : [];
  } catch (err) {
    console.warn("Failed to load dashboardTeamAccess:", err?.message || err);
    return [];
  }
}

async function saveTeamAccessMembers(db, members, nowMs = Date.now()) {
  await db.collection("appConfig").doc(TEAM_ACCESS_DOC_ID).set(
    {
      members,
      updatedAtMs: nowMs,
    },
    { merge: true }
  );
}

async function authenticateTeamPasskey(db, rawPasskey, nowMs = Date.now()) {
  const clean = String(rawPasskey || "").trim();
  if (!clean || !db) return null;
  const members = await loadTeamAccessMembers(db);
  for (let i = 0; i < members.length; i++) {
    const entry = members[i];
    if (!entry || entry.isActive === false || !entry.id || !entry.passkeyHash) continue;
    if (verifyTeamPasskeyHash(clean, entry.passkeyHash)) {
      const updated = members.map((m, idx) =>
        idx === i ? { ...m, lastLoginAtMs: nowMs } : m
      );
      saveTeamAccessMembers(db, updated, nowMs).catch((err) => {
        console.warn("Failed to update team lastLoginAtMs:", err?.message || err);
      });
      return {
        id: String(entry.id),
        label: String(entry.label || "Team Member"),
        role: "team",
      };
    }
  }
  return null;
}

async function isTeamMemberActive(db, memberId) {
  const cleanId = String(memberId || "").trim();
  if (!cleanId || !db) return false;
  const members = await loadTeamAccessMembers(db);
  return members.some((m) => m && m.id === cleanId && m.isActive !== false);
}

async function createTeamAccessMember(db, { label, passkey }, nowMs = Date.now()) {
  const cleanLabel = String(label || "").replace(/\s+/g, " ").trim().slice(0, 40);
  if (cleanLabel.length < 2) {
    throw new Error("Team member ka naam kam se kam 2 letters ka hona chahiye.");
  }
  let plainPasskey = String(passkey || "").trim();
  if (plainPasskey) {
    if (plainPasskey.length < 6 || plainPasskey.length > 64) {
      throw new Error("Custom passkey 6 se 64 characters ke beech hona chahiye.");
    }
  } else {
    plainPasskey = generateReadableTeamPasskey();
  }

  const existing = await loadTeamAccessMembers(db);
  const activeMembers = existing.filter((m) => m && m.id && m.isActive !== false);
  if (activeMembers.length >= MAX_TEAM_MEMBERS) {
    throw new Error(`Maximum ${MAX_TEAM_MEMBERS} team logins allowed. Purane unused logins revoke karein.`);
  }

  const newEntry = {
    id: `tm_${crypto.randomBytes(6).toString("hex")}`,
    label: cleanLabel,
    passkeyHash: hashTeamPasskey(plainPasskey),
    createdAtMs: nowMs,
    lastLoginAtMs: 0,
    isActive: true,
  };
  const nextMembers = [newEntry, ...activeMembers];
  await saveTeamAccessMembers(db, nextMembers, nowMs);

  return {
    member: {
      id: newEntry.id,
      label: newEntry.label,
      createdAtMs: newEntry.createdAtMs,
      lastLoginAtMs: 0,
      isActive: true,
    },
    plainPasskey,
    members: sanitizeTeamMembersForResponse(nextMembers),
  };
}

async function revokeTeamAccessMember(db, memberId, nowMs = Date.now()) {
  const cleanId = String(memberId || "").trim();
  if (!cleanId) throw new Error("Missing team member id");
  const existing = await loadTeamAccessMembers(db);
  const nextMembers = existing.filter((m) => m && m.id && m.id !== cleanId && m.isActive !== false);
  await saveTeamAccessMembers(db, nextMembers, nowMs);
  return sanitizeTeamMembersForResponse(nextMembers);
}

function createAdminDashboardToken(secret, optionsOrTtl = DEFAULT_TOKEN_TTL_MS, nowMs = Date.now()) {
  const cleanSecret = String(secret || "").trim();
  if (!cleanSecret) throw new Error("Missing signing secret");
  let role = "superadmin";
  let sub = "superadmin";
  let label = "Superadmin";
  let ttlMs = DEFAULT_TOKEN_TTL_MS;
  if (typeof optionsOrTtl === "number") {
    ttlMs = optionsOrTtl;
  } else if (optionsOrTtl && typeof optionsOrTtl === "object") {
    role = optionsOrTtl.role === "team" ? "team" : "superadmin";
    sub = String(optionsOrTtl.sub || (role === "superadmin" ? "superadmin" : "")).trim();
    label = String(optionsOrTtl.label || (role === "superadmin" ? "Superadmin" : "Team Member")).trim().slice(0, 48);
    if (typeof optionsOrTtl.ttlMs === "number") ttlMs = optionsOrTtl.ttlMs;
  }
  const payload = {
    role,
    sub,
    label,
    iat: nowMs,
    exp: nowMs + Math.max(60_000, ttlMs),
  };
  const encodedPayload = base64UrlEncode(JSON.stringify(payload));
  const signature = base64UrlEncode(
    crypto.createHmac("sha256", cleanSecret).update(encodedPayload).digest()
  );
  return `${encodedPayload}.${signature}`;
}

function verifyAdminDashboardSession(token, secret, nowMs = Date.now()) {
  const cleanToken = String(token || "").trim();
  const cleanSecret = String(secret || "").trim();
  if (!cleanToken || !cleanSecret) return null;
  const parts = cleanToken.split(".");
  if (parts.length !== 2) return null;
  const [encodedPayload, providedSig] = parts;
  if (!encodedPayload || !providedSig) return null;

  const expectedSig = base64UrlEncode(
    crypto.createHmac("sha256", cleanSecret).update(encodedPayload).digest()
  );
  const a = Buffer.from(providedSig, "utf8");
  const b = Buffer.from(expectedSig, "utf8");
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null;

  try {
    const payload = JSON.parse(base64UrlDecodeToString(encodedPayload));
    if (payload.role !== "superadmin" && payload.role !== "team") return null;
    if (typeof payload.exp !== "number" || payload.exp <= nowMs) return null;
    if (payload.role === "team" && !String(payload.sub || "").trim()) return null;
    return {
      role: payload.role,
      sub: String(payload.sub || (payload.role === "superadmin" ? "superadmin" : "")),
      label: String(payload.label || (payload.role === "superadmin" ? "Superadmin" : "Team Member")),
      iat: toSafeNumber(payload.iat, 0),
      exp: payload.exp,
    };
  } catch (_) {
    return null;
  }
}

function verifyAdminDashboardToken(token, secret, nowMs = Date.now()) {
  return Boolean(verifyAdminDashboardSession(token, secret, nowMs));
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
<link rel="icon" type="image/png" href="/vidvora-icon.png" />
<link rel="apple-touch-icon" href="/vidvora-icon.png" />
<style>
  :root {
    --bg: #050811;
    --bg-elev: rgba(13, 20, 36, 0.85);
    --bg-card: linear-gradient(180deg, rgba(20, 30, 52, 0.84) 0%, rgba(12, 19, 34, 0.92) 100%);
    --bg-card-solid: #0f172a;
    --bg-card-hover: rgba(28, 41, 69, 0.95);
    --border: rgba(148, 163, 184, 0.13);
    --border-strong: rgba(148, 163, 184, 0.24);
    --border-sheen: rgba(255, 255, 255, 0.07);
    --text: #f8fafc;
    --text-muted: #94a3b8;
    --text-soft: #64748b;
    --cyan: #22d3ee;
    --emerald: #10b981;
    --violet: #a78bfa;
    --amber: #f59e0b;
    --rose: #fb7185;
    --blue: #38bdf8;
    --radius: 18px;
    --shadow: 0 20px 50px rgba(0, 0, 0, 0.5), inset 0 1px 0 var(--border-sheen);
  }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  html, body {
    width: 100%;
    max-width: 100%;
    overflow-x: hidden;
    background-color: var(--bg);
    background-image:
      radial-gradient(ellipse 65% 45% at 10% -5%, rgba(34, 211, 238, 0.13), transparent 60%),
      radial-gradient(ellipse 55% 40% at 92% 0%, rgba(59, 130, 246, 0.12), transparent 60%),
      radial-gradient(ellipse 50% 40% at 50% 105%, rgba(16, 185, 129, 0.08), transparent 60%),
      radial-gradient(rgba(148, 163, 184, 0.075) 1px, transparent 1px);
    background-size: 100% 100%, 100% 100%, 100% 100%, 26px 26px;
    background-attachment: fixed;
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
    position: relative;
  }
  .login-card {
    width: 100%;
    max-width: 440px;
    background: var(--bg-card);
    backdrop-filter: blur(22px);
    border: 1px solid var(--border-strong);
    border-radius: 24px;
    padding: 40px 34px 36px;
    box-shadow: 0 28px 70px rgba(0, 0, 0, 0.65), 0 0 80px rgba(34, 211, 238, 0.08), inset 0 1px 0 rgba(255,255,255,0.1);
    position: relative;
    overflow: hidden;
  }
  .login-card::before {
    content: "";
    position: absolute;
    top: 0; left: 16%; right: 16%;
    height: 2px;
    background: linear-gradient(90deg, transparent, var(--cyan), var(--blue), transparent);
    opacity: 0.9;
  }
  .brand-row {
    display: flex;
    flex-direction: column;
    align-items: center;
    text-align: center;
    gap: 12px;
    margin-bottom: 22px;
  }
  .login-logo-img {
    height: 44px;
    width: auto;
    display: block;
    object-fit: contain;
    filter: drop-shadow(0 6px 22px rgba(34, 211, 238, 0.34));
  }
  .topbar-logo-img {
    height: 30px;
    width: auto;
    display: block;
    object-fit: contain;
    filter: drop-shadow(0 3px 14px rgba(34, 211, 238, 0.28));
  }
  .analytics-tag {
    font-size: 10.5px;
    font-weight: 700;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    color: #7dd3fc;
    background: linear-gradient(135deg, rgba(34, 211, 238, 0.16), rgba(59, 130, 246, 0.16));
    border: 1px solid rgba(56, 189, 248, 0.35);
    padding: 3.5px 10px;
    border-radius: 999px;
  }
  .login-card p { color: var(--text-muted); font-size: 13.5px; line-height: 1.55; margin-bottom: 24px; text-align: center; }
  .field-label { display: block; font-size: 12px; color: var(--text-muted); margin-bottom: 8px; font-weight: 600; letter-spacing: 0.03em; text-transform: uppercase; }
  .pass-box { position: relative; margin-bottom: 18px; }
  .text-input {
    width: 100%;
    background: rgba(6, 10, 20, 0.78);
    border: 1px solid var(--border-strong);
    border-radius: 12px;
    color: var(--text);
    padding: 13px 54px 13px 15px;
    font-size: 14.5px;
    outline: none;
    transition: border-color 0.2s, box-shadow 0.2s, background 0.2s;
  }
  .text-input:focus {
    border-color: var(--cyan);
    background: rgba(8, 14, 28, 0.96);
    box-shadow: 0 0 0 3px rgba(34, 211, 238, 0.16);
  }
  .eye-btn {
    position: absolute;
    right: 10px;
    top: 50%;
    transform: translateY(-50%);
    background: rgba(148, 163, 184, 0.1);
    border: 1px solid var(--border);
    border-radius: 7px;
    color: var(--text-muted);
    cursor: pointer;
    padding: 4px 8px;
    font-size: 11.5px;
    font-weight: 600;
  }
  .eye-btn:hover { color: var(--text); border-color: var(--border-strong); }
  .btn-primary {
    width: 100%;
    background: linear-gradient(135deg, #22d3ee 0%, #3b82f6 55%, #10b981 100%);
    color: #03101a;
    border: none;
    border-radius: 12px;
    padding: 13px 18px;
    font-weight: 800;
    font-size: 14.5px;
    letter-spacing: 0.01em;
    cursor: pointer;
    box-shadow: 0 10px 28px rgba(34, 211, 238, 0.26);
    transition: transform 0.14s, filter 0.18s, box-shadow 0.18s;
  }
  .btn-primary:hover { filter: brightness(1.08); box-shadow: 0 14px 34px rgba(34, 211, 238, 0.36); transform: translateY(-1px); }
  .btn-primary:active { transform: translateY(0); }
  .btn-primary:disabled { opacity: 0.6; cursor: not-allowed; }
  .error-banner {
    display: none;
    background: rgba(244, 63, 94, 0.14);
    border: 1px solid rgba(244, 63, 94, 0.42);
    color: #fda4af;
    border-radius: 12px;
    padding: 10px 13px;
    font-size: 13px;
    margin-bottom: 16px;
  }

  /* App Shell */
  .app-shell { display: none; min-height: 100vh; padding-bottom: 56px; }
  .topbar {
    position: sticky;
    top: 0;
    z-index: 40;
    backdrop-filter: blur(20px) saturate(140%);
    background: rgba(6, 10, 19, 0.8);
    border-bottom: 1px solid rgba(148, 163, 184, 0.14);
    box-shadow: 0 8px 30px rgba(0, 0, 0, 0.35);
  }
  .topbar-inner {
    max-width: 1460px;
    margin: 0 auto;
    padding: 14px 26px;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 14px;
  }
  .topbar-left { display: flex; align-items: center; gap: 14px; }
  .live-pill {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    font-size: 11.5px;
    color: #6ee7b7;
    background: rgba(16, 185, 129, 0.12);
    border: 1px solid rgba(16, 185, 129, 0.32);
    padding: 3.5px 10px;
    border-radius: 999px;
    font-weight: 700;
    letter-spacing: 0.04em;
    text-transform: uppercase;
  }
  .live-dot {
    width: 7px; height: 7px; border-radius: 50%; background: var(--emerald);
    box-shadow: 0 0 10px var(--emerald);
    animation: pulse 2s infinite;
  }
  @keyframes pulse { 0%,100% { opacity: 1; transform: scale(1); } 50% { opacity: 0.4; transform: scale(0.85); } }
  .updated-text { font-size: 12px; color: var(--text-muted); letter-spacing: 0.01em; }

  .topbar-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
  .seg-group {
    display: inline-flex;
    background: rgba(8, 13, 25, 0.9);
    border: 1px solid var(--border);
    border-radius: 11px;
    padding: 3px;
    box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.4);
  }
  .seg-btn {
    background: transparent;
    border: none;
    color: var(--text-muted);
    padding: 6px 12px;
    border-radius: 8px;
    font-size: 12px;
    font-weight: 600;
    cursor: pointer;
    transition: all 0.16s ease;
  }
  .seg-btn:hover { color: var(--text); }
  .seg-btn.active {
    background: linear-gradient(180deg, rgba(34, 211, 238, 0.2), rgba(59, 130, 246, 0.16));
    color: #e0f2fe;
    box-shadow: inset 0 0 0 1px rgba(34, 211, 238, 0.42), 0 2px 8px rgba(34, 211, 238, 0.14);
  }
  .ctrl-btn {
    background: rgba(15, 23, 42, 0.85);
    border: 1px solid var(--border);
    color: var(--text);
    padding: 7.5px 13px;
    border-radius: 11px;
    font-size: 12px;
    font-weight: 600;
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    gap: 6px;
    transition: all 0.16s ease;
  }
  .ctrl-btn:hover { background: var(--bg-card-hover); border-color: var(--border-strong); transform: translateY(-1px); }
  .ctrl-btn.on { border-color: rgba(16, 185, 129, 0.42); color: #6ee7b7; background: rgba(16, 185, 129, 0.08); }
  .ctrl-btn.danger:hover { border-color: rgba(244, 63, 94, 0.48); color: #fda4af; background: rgba(244, 63, 94, 0.08); }

  .container { max-width: 1460px; margin: 0 auto; padding: 24px 26px 0; }

  /* KPI Grid */
  .kpi-grid {
    display: grid;
    grid-template-columns: repeat(6, minmax(0, 1fr));
    gap: 15px;
    margin-bottom: 22px;
  }
  @media (max-width: 1280px) { .kpi-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
  @media (max-width: 680px) { .kpi-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
  .kpi-card {
    background: var(--bg-card);
    backdrop-filter: blur(14px);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: 18px 18px 16px;
    position: relative;
    overflow: hidden;
    box-shadow: var(--shadow);
    transition: transform 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease;
  }
  .kpi-card:hover {
    transform: translateY(-2px);
    border-color: var(--border-strong);
    box-shadow: 0 22px 46px rgba(0, 0, 0, 0.55), inset 0 1px 0 rgba(255,255,255,0.1);
  }
  .kpi-card::before {
    content: "";
    position: absolute;
    top: -40px; right: -40px;
    width: 120px; height: 120px;
    border-radius: 50%;
    background: radial-gradient(circle, var(--accent, var(--cyan)), transparent 70%);
    opacity: 0.14;
    pointer-events: none;
  }
  .kpi-card::after {
    content: "";
    position: absolute;
    top: 0; left: 14px; right: 14px;
    height: 2px;
    border-radius: 2px;
    background: linear-gradient(90deg, var(--accent, var(--cyan)), transparent);
    opacity: 0.85;
  }
  .kpi-top {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
  }
  .kpi-icon {
    width: 32px; height: 32px;
    border-radius: 10px;
    display: grid;
    place-items: center;
    background: rgba(255, 255, 255, 0.045);
    border: 1px solid rgba(255, 255, 255, 0.08);
    color: var(--accent, var(--cyan));
    flex-shrink: 0;
  }
  .kpi-label { font-size: 11px; color: var(--text-muted); font-weight: 700; text-transform: uppercase; letter-spacing: 0.06em; }
  .kpi-val-row {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 8px;
    margin: 10px 0 8px;
  }
  .kpi-value { font-size: 29px; font-weight: 800; letter-spacing: -0.03em; line-height: 1.05; font-variant-numeric: tabular-nums; color: #ffffff; }
  .kpi-spark { width: 64px; height: 24px; flex-shrink: 0; overflow: visible; }
  .kpi-sub { font-size: 11.5px; color: var(--text-muted); display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
  .badge-good { color: #6ee7b7; background: rgba(16, 185, 129, 0.15); border: 1px solid rgba(16, 185, 129, 0.25); padding: 2px 8px; border-radius: 999px; font-weight: 600; font-size: 11px; }
  .badge-warn { color: #fcd34d; background: rgba(245, 158, 11, 0.15); border: 1px solid rgba(245, 158, 11, 0.25); padding: 2px 8px; border-radius: 999px; font-weight: 600; font-size: 11px; }
  .badge-rose { color: #fda4af; background: rgba(244, 63, 94, 0.15); border: 1px solid rgba(244, 63, 94, 0.25); padding: 2px 8px; border-radius: 999px; font-weight: 600; font-size: 11px; }
  .badge-cyan { color: #67e8f9; background: rgba(34, 211, 238, 0.15); border: 1px solid rgba(34, 211, 238, 0.28); padding: 2px 8px; border-radius: 999px; font-weight: 600; font-size: 11px; }

  /* Panels & Charts Row */
  .charts-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 18px;
    margin-bottom: 22px;
  }
  @media (max-width: 1024px) { .charts-grid { grid-template-columns: 1fr; } }
  .panel {
    background: var(--bg-card);
    backdrop-filter: blur(14px);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: 20px 22px;
    box-shadow: var(--shadow);
  }
  .panel-head {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 16px;
  }
  .panel-title { font-size: 15.5px; font-weight: 700; letter-spacing: -0.015em; display: flex; align-items: center; gap: 8px; }
  .panel-sub { font-size: 12px; color: var(--text-muted); margin-top: 3px; }
  .chart-wrap {
    position: relative;
    width: 100%;
    height: 300px;
  }
  .chart-canvas { width: 100%; height: 100%; display: block; cursor: crosshair; }
  .chart-tooltip {
    position: absolute;
    pointer-events: none;
    display: none;
    background: rgba(7, 11, 22, 0.94);
    backdrop-filter: blur(14px);
    border: 1px solid rgba(56, 189, 248, 0.32);
    border-radius: 12px;
    padding: 11px 14px;
    font-size: 12.5px;
    color: var(--text);
    box-shadow: 0 16px 38px rgba(0, 0, 0, 0.65);
    z-index: 20;
    min-width: 180px;
  }
  .chart-tooltip .tt-title { font-weight: 700; margin-bottom: 6px; color: #fff; font-size: 12px; border-bottom: 1px solid rgba(148,163,184,0.16); padding-bottom: 5px; }
  .chart-tooltip .tt-row { display: flex; justify-content: space-between; gap: 16px; margin-top: 4px; color: var(--text-muted); }
  .chart-tooltip .tt-row strong { color: #fff; font-variant-numeric: tabular-nums; }
  .chart-legend { display: flex; gap: 18px; flex-wrap: wrap; font-size: 12px; color: var(--text-muted); margin-top: 12px; padding-top: 10px; border-top: 1px solid rgba(148, 163, 184, 0.08); }
  .legend-item { display: inline-flex; align-items: center; gap: 7px; font-weight: 500; }
  .legend-dot { width: 9px; height: 9px; border-radius: 999px; box-shadow: 0 0 8px currentColor; }

  /* Breakdown Grid */
  .breakdown-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 18px;
    margin-bottom: 22px;
  }
  @media (max-width: 980px) { .breakdown-grid { grid-template-columns: 1fr; } }
  .bar-list { display: flex; flex-direction: column; gap: 12px; margin-top: 10px; }
  .bar-item {
    padding: 6px 8px;
    border-radius: 10px;
    transition: background 0.15s;
  }
  .bar-item:hover { background: rgba(255, 255, 255, 0.03); }
  .bar-row-top { display: flex; justify-content: space-between; align-items: center; gap: 10px; font-size: 12.5px; margin-bottom: 6px; }
  .bar-label-wrap { display: flex; align-items: center; gap: 8px; min-width: 0; }
  .bar-rank {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 10.5px;
    font-weight: 700;
    color: var(--text-soft);
    background: rgba(148, 163, 184, 0.1);
    padding: 1.5px 5.5px;
    border-radius: 5px;
  }
  .bar-name { font-weight: 600; color: var(--text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .bar-row-top .val { color: var(--text-muted); font-variant-numeric: tabular-nums; font-size: 12px; white-space: nowrap; }
  .bar-row-top .val strong { color: #fff; font-weight: 700; }
  .bar-track { height: 7px; background: rgba(148, 163, 184, 0.11); border-radius: 999px; overflow: hidden; }
  .bar-fill { height: 100%; border-radius: 999px; transition: width 0.45s cubic-bezier(0.22, 1, 0.36, 1); box-shadow: 0 0 12px rgba(34, 211, 238, 0.25); }

  /* Tabs & Tables */
  .tabs-bar {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 16px;
    padding-bottom: 14px;
    border-bottom: 1px solid var(--border);
  }
  .tab-pills { display: flex; flex-wrap: wrap; gap: 8px; }
  .tab-pill {
    background: rgba(8, 13, 25, 0.72);
    border: 1px solid var(--border);
    color: var(--text-muted);
    padding: 8.5px 16px;
    border-radius: 11px;
    font-size: 13px;
    font-weight: 600;
    cursor: pointer;
    transition: all 0.16s ease;
  }
  .tab-pill:hover { color: var(--text); border-color: var(--border-strong); }
  .tab-pill.active {
    background: linear-gradient(135deg, rgba(34, 211, 238, 0.18), rgba(59, 130, 246, 0.16));
    border-color: rgba(34, 211, 238, 0.48);
    color: #e0f2fe;
    box-shadow: 0 4px 16px rgba(34, 211, 238, 0.14);
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
    background: rgba(8, 13, 25, 0.85);
    border: 1px solid var(--border-strong);
    border-radius: 11px;
    color: var(--text);
    padding: 8.5px 14px;
    font-size: 13px;
    min-width: 270px;
    outline: none;
    transition: border-color 0.16s, box-shadow 0.16s;
  }
  .search-input:focus { border-color: var(--cyan); box-shadow: 0 0 0 3px rgba(34, 211, 238, 0.14); }
  .table-scroll { overflow-x: auto; border-radius: 13px; border: 1px solid var(--border); background: rgba(6, 10, 19, 0.55); }
  table { width: 100%; border-collapse: collapse; font-size: 13px; }
  thead th {
    text-align: left;
    padding: 12px 15px;
    background: rgba(12, 19, 35, 0.96);
    color: var(--text-muted);
    font-weight: 700;
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    border-bottom: 1px solid var(--border);
    white-space: nowrap;
  }
  tbody td {
    padding: 12px 15px;
    border-bottom: 1px solid rgba(148, 163, 184, 0.075);
    vertical-align: middle;
  }
  tbody tr { transition: background 0.14s; }
  tbody tr:hover { background: rgba(56, 189, 248, 0.045); }
  tbody tr:last-child td { border-bottom: none; }
  .rank-pill {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-width: 26px;
    height: 22px;
    border-radius: 7px;
    font-size: 11px;
    font-weight: 700;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    background: rgba(148, 163, 184, 0.1);
    color: var(--text-muted);
  }
  .rank-pill.top1 { background: rgba(245, 158, 11, 0.2); color: #fcd34d; border: 1px solid rgba(245, 158, 11, 0.35); }
  .rank-pill.top2 { background: rgba(148, 163, 184, 0.22); color: #e2e8f0; border: 1px solid rgba(226, 232, 240, 0.3); }
  .rank-pill.top3 { background: rgba(217, 119, 6, 0.2); color: #fdba74; border: 1px solid rgba(251, 146, 60, 0.32); }
  .num { font-variant-numeric: tabular-nums; text-align: right; }
  th.num { text-align: right; }
  .mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 11.5px; color: var(--text-muted); }
  .empty-state { padding: 36px; text-align: center; color: var(--text-muted); font-size: 13.5px; }
  .load-more-row { text-align: center; padding-top: 14px; }
  .footer-note {
    margin-top: 24px;
    padding-top: 16px;
    border-top: 1px solid var(--border);
    font-size: 12px;
    color: var(--text-soft);
    display: flex;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: 8px;
  }

  /* Strict viewport fit across Laptop & Mobile Chrome */
  .app-shell, .topbar, .topbar-inner, .container, .panel, .kpi-card, .chart-wrap, .bar-list, .bar-item, .table-scroll {
    min-width: 0;
    max-width: 100%;
  }
  @media (max-width: 768px) {
    .topbar { position: static; }
    .topbar-inner { padding: 12px 14px; gap: 10px; }
    .topbar-logo-img { height: 24px; }
    .topbar-controls { width: 100%; gap: 6px; }
    .seg-btn { padding: 5px 9px; font-size: 11.5px; }
    .ctrl-btn { padding: 6px 10px; font-size: 11.5px; }
    .container { padding: 14px 12px 0; }
    .kpi-grid { gap: 10px; margin-bottom: 14px; }
    .kpi-card { padding: 13px 12px; border-radius: 14px; }
    .kpi-label { font-size: 10px; }
    .kpi-icon { width: 26px; height: 26px; border-radius: 8px; }
    .kpi-value { font-size: 22px; }
    .kpi-spark { width: 44px; height: 18px; }
    .charts-grid, .breakdown-grid { gap: 12px; margin-bottom: 14px; }
    .panel { padding: 14px 13px; border-radius: 14px; }
    .panel-title { font-size: 14.5px; }
    .chart-wrap { height: 235px; }
    .search-input { min-width: 0; width: 100%; }
    .table-toolbar > div { width: 100%; }
    .tab-pill { padding: 7px 11px; font-size: 12px; }
    .login-card { padding: 28px 20px; }
    .team-form-grid { grid-template-columns: 1fr; }
  }
  .role-pill {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    padding: 3px 10px;
    border-radius: 999px;
    font-size: 10.5px;
    font-weight: 700;
    letter-spacing: 0.4px;
    text-transform: uppercase;
  }
  .role-pill.super {
    background: rgba(167, 139, 250, 0.14);
    border: 1px solid rgba(167, 139, 250, 0.34);
    color: #ddd6fe;
  }
  .role-pill.team {
    background: rgba(245, 158, 11, 0.14);
    border: 1px solid rgba(245, 158, 11, 0.34);
    color: #fde68a;
  }
  .team-callout {
    background: linear-gradient(135deg, rgba(34, 211, 238, 0.08), rgba(167, 139, 250, 0.08));
    border: 1px solid rgba(56, 189, 248, 0.22);
    border-radius: 14px;
    padding: 14px 16px;
    margin-bottom: 16px;
    font-size: 13px;
    color: #cbd5e1;
    line-height: 1.55;
  }
  .team-form-grid {
    display: grid;
    grid-template-columns: 1fr 1fr auto;
    gap: 10px;
    align-items: end;
    margin-bottom: 16px;
  }
  .team-secret-box {
    display: none;
    background: rgba(16, 185, 129, 0.08);
    border: 1px solid rgba(16, 185, 129, 0.36);
    border-radius: 14px;
    padding: 14px 16px;
    margin-bottom: 16px;
  }
  .team-secret-code {
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 16px;
    font-weight: 800;
    letter-spacing: 0.8px;
    color: #6ee7b7;
    background: rgba(6, 10, 20, 0.72);
    border: 1px solid rgba(16, 185, 129, 0.32);
    border-radius: 10px;
    padding: 9px 12px;
    display: inline-block;
    margin: 8px 0;
    word-break: break-all;
  }
</style>
</head>
<body>

<div id="loginView" class="login-wrap">
  <div class="login-card">
    <div class="brand-row">
      <img src="/vidvora-logo.png" alt="VidVora" class="login-logo-img" />
      <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;justify-content:center;">
        <span class="analytics-tag">Analytics Pro</span>
        <span style="font-size:12px;color:var(--text-muted);">Admin &amp; Team Console</span>
      </div>
    </div>
    <p>Superadmin ya apna Team Viewer Passkey dalein — live App Installs, Country/City Views aur Performance Graphs dekhne ke liye.</p>
    <div id="loginError" class="error-banner"></div>
    <form id="loginForm">
      <label class="field-label" for="passkeyInput">Admin / Team Passkey</label>
      <div class="pass-box">
        <input id="passkeyInput" class="text-input" type="password" autocomplete="current-password" placeholder="Enter Superadmin or Team Passkey..." required />
        <button type="button" id="togglePassBtn" class="eye-btn" title="Show/Hide">Show</button>
      </div>
      <button id="loginSubmitBtn" type="submit" class="btn-primary">Unlock Analytics Dashboard</button>
    </form>
  </div>
</div>

<div id="appView" class="app-shell">
  <header class="topbar">
    <div class="topbar-inner">
      <div class="topbar-left">
        <div>
          <div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap;">
            <img src="/vidvora-logo.png" alt="VidVora" class="topbar-logo-img" />
            <span class="analytics-tag">Analytics</span>
            <span class="live-pill"><span class="live-dot"></span>Live</span>
            <span id="viewerRoleBadge" class="role-pill super" style="display:none;"></span>
          </div>
          <div id="lastUpdatedText" class="updated-text" style="margin-top:4px;">Loading live metrics...</div>
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
        <div class="kpi-top">
          <div class="kpi-label">Total Real Installs</div>
          <div class="kpi-icon"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg></div>
        </div>
        <div class="kpi-val-row">
          <div id="kpiTotalInstalls" class="kpi-value">-</div>
          <svg id="sparkInstalls" class="kpi-spark" viewBox="0 0 64 24"></svg>
        </div>
        <div id="kpiTotalInstallsSub" class="kpi-sub"></div>
      </div>
      <div class="kpi-card" style="--accent:#10b981;">
        <div class="kpi-top">
          <div class="kpi-label">Active Installed</div>
          <div class="kpi-icon"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><rect x="5" y="2" width="14" height="20" rx="2"/><path d="M9 12l2 2 4-4"/></svg></div>
        </div>
        <div class="kpi-val-row">
          <div id="kpiActiveInstalled" class="kpi-value">-</div>
        </div>
        <div id="kpiActiveInstalledSub" class="kpi-sub"></div>
      </div>
      <div class="kpi-card" style="--accent:#38bdf8;">
        <div class="kpi-top">
          <div class="kpi-label">Active Users (24h)</div>
          <div class="kpi-icon"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg></div>
        </div>
        <div class="kpi-val-row">
          <div id="kpiActive24h" class="kpi-value">-</div>
        </div>
        <div id="kpiActive24hSub" class="kpi-sub"></div>
      </div>
      <div class="kpi-card" style="--accent:#a78bfa;">
        <div class="kpi-top">
          <div class="kpi-label">Total Real Views</div>
          <div class="kpi-icon"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg></div>
        </div>
        <div class="kpi-val-row">
          <div id="kpiTotalViews" class="kpi-value">-</div>
        </div>
        <div id="kpiTotalViewsSub" class="kpi-sub"></div>
      </div>
      <div class="kpi-card" style="--accent:#f59e0b;">
        <div class="kpi-top">
          <div class="kpi-label">Views (Last 24h)</div>
          <div class="kpi-icon"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg></div>
        </div>
        <div class="kpi-val-row">
          <div id="kpiViews24h" class="kpi-value">-</div>
          <svg id="sparkViews" class="kpi-spark" viewBox="0 0 64 24"></svg>
        </div>
        <div id="kpiViews24hSub" class="kpi-sub"></div>
      </div>
      <div class="kpi-card" style="--accent:#fb7185;">
        <div class="kpi-top">
          <div class="kpi-label">Videos &amp; Creators</div>
          <div class="kpi-icon"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><polygon points="5 3 19 12 5 21 5 3"/></svg></div>
        </div>
        <div class="kpi-val-row">
          <div id="kpiVideosCreators" class="kpi-value">-</div>
        </div>
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
          <button id="teamAccessTabBtn" class="tab-pill" data-tab="team" style="display:none;">Team Access</button>
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
          <input id="creatorSearchInput" class="search-input" type="search" placeholder="Search creator name..." />
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
      <!-- Tab 4: Team Access (Superadmin Only) -->
      <div id="tab-team" style="display:none;">
        <div class="team-callout">
          <strong style="color:#fff;">Team Viewer Passkeys (Superadmin Password Safe Rahega):</strong><br />
          Apni team ke har member ke liye alag <strong>View-Only Passkey</strong> banao. Team members sirf is Chrome Analytics Dashboard par live stats dekh sakenge — unhe Superadmin password, Android Admin Panel, ya Team Access settings ka koi control nahi milega. Jab chaho 1 click me kisi ka bhi access <strong>Revoke</strong> kar sakte ho.
        </div>

        <form id="teamCreateForm" class="team-form-grid">
          <div>
            <label class="field-label" for="teamLabelInput">Team Member Name</label>
            <input id="teamLabelInput" class="search-input" style="width:100%;min-width:0;" type="text" maxlength="40" placeholder="e.g. Aman - Marketing" required />
          </div>
          <div>
            <label class="field-label" for="teamPasskeyInput">Custom Passkey (Optional — blank = Auto Strong)</label>
            <input id="teamPasskeyInput" class="search-input" style="width:100%;min-width:0;" type="text" maxlength="64" placeholder="Auto-generate strong passkey..." />
          </div>
          <button id="teamCreateBtn" type="submit" class="ctrl-btn on" style="height:38px;padding:0 16px;">+ Create Team Login</button>
        </form>

        <div id="teamErrorBanner" class="error-banner"></div>

        <div id="teamSecretBox" class="team-secret-box">
          <div style="font-size:13px;font-weight:700;color:#6ee7b7;">New Team Viewer Passkey Ready! (Sirf ek baar dikhega — abhi copy kar lo)</div>
          <div id="teamSecretMeta" style="font-size:12px;color:var(--text-muted);margin-top:3px;"></div>
          <div id="teamSecretCode" class="team-secret-code"></div>
          <div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:6px;">
            <button type="button" id="copyTeamPassBtn" class="ctrl-btn on">Copy Passkey</button>
            <button type="button" id="copyTeamInviteBtn" class="ctrl-btn">Copy WhatsApp / Telegram Message</button>
          </div>
        </div>

        <div class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>#</th>
                <th>Team Member</th>
                <th>Permission</th>
                <th>Created (IST)</th>
                <th>Last Login (IST)</th>
                <th class="num">Action</th>
              </tr>
            </thead>
            <tbody id="teamTableBody">
              <tr><td colspan="6" class="empty-state">Loading team members...</td></tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>

    <div class="footer-note">
      <span>VidVora Analytics — Real-time overview.</span>
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
    viewer: { role: "team", label: "" },
    teamMembers: [],
    lastCreatedInvite: null,
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
      state.viewer = data.viewer || { role: "superadmin", label: "Superadmin" };
      updateViewerRoleUi();
      showApp();
      renderAll();
    } catch (err) {
      $("lastUpdatedText").textContent = "Refresh failed: " + (err.message || err);
    }
  }

  function updateViewerRoleUi() {
    const isSuper = state.viewer && state.viewer.role === "superadmin";
    const badge = $("viewerRoleBadge");
    if (badge) {
      badge.style.display = "inline-flex";
      badge.className = "role-pill " + (isSuper ? "super" : "team");
      badge.textContent = isSuper ? "Superadmin" : ("Team Viewer: " + (state.viewer.label || "Member"));
    }
    const teamTabBtn = $("teamAccessTabBtn");
    if (teamTabBtn) {
      teamTabBtn.style.display = isSuper ? "inline-flex" : "none";
    }
    if (!isSuper && state.activeTab === "team") {
      state.activeTab = "installs";
      $("tab-team").style.display = "none";
      $("tab-installs").style.display = "block";
      $("reportTabs").querySelectorAll(".tab-pill").forEach((b) => b.classList.toggle("active", b.dataset.tab === "installs"));
    }
  }

  async function loadTeamMembers() {
    if (!state.viewer || state.viewer.role !== "superadmin") return;
    try {
      const res = await fetch("/api/admin-dashboard/team", {
        credentials: "same-origin",
        cache: "no-store",
      });
      if (!res.ok) return;
      const json = await res.json();
      if (json.ok && Array.isArray(json.members)) {
        state.teamMembers = json.members;
        renderTeamTable();
      }
    } catch (_) {}
  }

  function renderTeamTable() {
    const body = $("teamTableBody");
    if (!body) return;
    const list = state.teamMembers || [];
    if (!list.length) {
      body.innerHTML = '<tr><td colspan="6" class="empty-state">Abhi koi team viewer passkey nahi bana hai. Upar form se apni team ke liye naya login banao!</td></tr>';
      return;
    }
    body.innerHTML = list.map((m, i) => {
      return '<tr>' +
        '<td class="mono">' + (i + 1) + '</td>' +
        '<td><strong>' + esc(m.label) + '</strong></td>' +
        '<td><span class="badge-cyan">Analytics View-Only</span></td>' +
        '<td>' + esc(fmtDateIst(m.createdAtMs)) + '</td>' +
        '<td>' + (m.lastLoginAtMs > 0 ? esc(fmtDateIst(m.lastLoginAtMs)) : '<span style="color:var(--text-soft);">Never logged in</span>') + '</td>' +
        '<td class="num"><button type="button" class="ctrl-btn danger" data-revoke-id="' + esc(m.id) + '" data-revoke-label="' + esc(m.label) + '">Revoke</button></td>' +
        '</tr>';
    }).join("");
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

    renderSparkline("sparkInstalls", (state.data.charts.dailyInstalls || []).map((d) => d.newInstalls), "#22d3ee");
    renderSparkline("sparkViews", (state.data.charts.hourlyViews24h || []).map((d) => d.views), "#f59e0b");

    drawInstallsChart();
    drawViewsChart();
    renderGeoCards();
    renderGeoTable();
    renderDevicesTable();
    renderCreatorsTable();
    renderVideosTable();
  }

  function renderSparkline(svgId, values, color) {
    const svg = $(svgId);
    if (!svg) return;
    if (!values || values.length < 2) {
      svg.innerHTML = "";
      return;
    }
    const maxV = Math.max(1, ...values);
    const minV = Math.min(0, ...values);
    const span = Math.max(1, maxV - minV);
    const w = 64;
    const h = 22;
    const step = w / (values.length - 1);
    const pts = values.map((v, i) => {
      const x = (i * step).toFixed(1);
      const y = (h - 2 - ((v - minV) / span) * (h - 5)).toFixed(1);
      return x + "," + y;
    });
    const areaPts = "0," + h + " " + pts.join(" ") + " " + w + "," + h;
    svg.innerHTML =
      '<polygon points="' + areaPts + '" fill="' + color + '" fill-opacity="0.16" />' +
      '<polyline points="' + pts.join(" ") + '" fill="none" stroke="' + color + '" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />';
  }

  function renderBreakdown(containerId, items, color) {
    const el = $(containerId);
    if (!items || !items.length) {
      el.innerHTML = '<div class="empty-state" style="padding:18px 0;">No data yet</div>';
      return;
    }
    el.innerHTML = items.map((item, idx) => {
      const pct = Math.max(3, Math.min(100, item.percent));
      const rank = String(idx + 1).padStart(2, "0");
      return '<div class="bar-item">' +
        '<div class="bar-row-top">' +
          '<span class="bar-label-wrap"><span class="bar-rank">' + rank + '</span><span class="bar-name">' + esc(item.label) + '</span></span>' +
          '<span class="val"><strong>' + fmtNum(item.count) + '</strong> (' + item.percent + '%)</span>' +
        '</div>' +
        '<div class="bar-track"><div class="bar-fill" style="width:' + pct + '%;background:linear-gradient(90deg,' + color + 'cc,' + color + ');"></div></div>' +
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
    const w = Math.max(220, Math.floor(rect.width));
    const h = Math.max(200, Math.floor(rect.height));
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
    const pad = { top: 20, right: 18, bottom: 32, left: 42 };
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
    const yMax = niceMax(rawMax * 1.12);
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
      ctx.fillStyle = "rgba(56, 189, 248, 0.06)";
      ctx.fillRect(pad.left + stepX * hoverIdx, pad.top, stepX, plotH);
      ctx.save();
      ctx.setLineDash([4, 4]);
      ctx.strokeStyle = "rgba(148, 163, 184, 0.32)";
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(hc.cx, pad.top);
      ctx.lineTo(hc.cx, pad.top + plotH);
      ctx.stroke();
      ctx.restore();
    }

    function traceSmoothCurve(pts, yKey) {
      if (!pts.length) return;
      ctx.moveTo(pts[0].cx, pts[0][yKey]);
      if (pts.length === 1) return;
      for (let i = 0; i < pts.length - 1; i += 1) {
        const curr = pts[i];
        const next = pts[i + 1];
        const midX = (curr.cx + next.cx) / 2;
        ctx.bezierCurveTo(midX, curr[yKey], midX, next[yKey], next.cx, next[yKey]);
      }
    }

    if (areaMode) {
      const grad = ctx.createLinearGradient(0, pad.top, 0, pad.top + plotH);
      grad.addColorStop(0, barColor + "5c");
      grad.addColorStop(0.7, barColor + "14");
      grad.addColorStop(1, barColor + "00");

      ctx.beginPath();
      traceSmoothCurve(coords, "y1");
      ctx.lineTo(coords[coords.length - 1].cx, pad.top + plotH);
      ctx.lineTo(coords[0].cx, pad.top + plotH);
      ctx.closePath();
      ctx.fillStyle = grad;
      ctx.fill();

      ctx.save();
      ctx.shadowColor = barColor;
      ctx.shadowBlur = 12;
      ctx.beginPath();
      traceSmoothCurve(coords, "y1");
      ctx.strokeStyle = barColor;
      ctx.lineWidth = 2.6;
      ctx.stroke();
      ctx.restore();

      coords.forEach((c, i) => {
        const isHover = i === hoverIdx;
        if (isHover || n <= 16) {
          if (isHover) {
            ctx.beginPath();
            ctx.arc(c.cx, c.y1, 10, 0, Math.PI * 2);
            ctx.fillStyle = barColor + "33";
            ctx.fill();
          }
          ctx.beginPath();
          ctx.arc(c.cx, c.y1, isHover ? 5.2 : 3, 0, Math.PI * 2);
          ctx.fillStyle = isHover ? "#ffffff" : barColor;
          ctx.fill();
          if (isHover) {
            ctx.strokeStyle = barColor;
            ctx.lineWidth = 2.2;
            ctx.stroke();
          }
        }
      });
    } else {
      // Rounded gradient bars
      const barW = Math.max(5, Math.min(28, stepX * 0.54));
      coords.forEach((c, i) => {
        const barH = Math.max(c.p.primary > 0 ? 4 : 0, pad.top + plotH - c.y1);
        const x = c.cx - barW / 2;
        const y = pad.top + plotH - barH;
        const grad = ctx.createLinearGradient(0, y, 0, pad.top + plotH);
        grad.addColorStop(0, i === hoverIdx ? "#ffffff" : barColor);
        grad.addColorStop(1, barColor + "38");
        ctx.save();
        if (i === hoverIdx) {
          ctx.shadowColor = barColor;
          ctx.shadowBlur = 14;
        }
        ctx.fillStyle = grad;
        const r = Math.min(6, barW / 2, barH);
        ctx.beginPath();
        ctx.moveTo(x, y + r);
        ctx.quadraticCurveTo(x, y, x + r, y);
        ctx.lineTo(x + barW - r, y);
        ctx.quadraticCurveTo(x + barW, y, x + barW, y + r);
        ctx.lineTo(x + barW, pad.top + plotH);
        ctx.lineTo(x, pad.top + plotH);
        ctx.closePath();
        ctx.fill();
        ctx.restore();
      });

      // Smooth glowing secondary line
      if (lineColor && coords.some((c) => c.y2 !== null)) {
        ctx.save();
        ctx.shadowColor = lineColor;
        ctx.shadowBlur = 10;
        ctx.beginPath();
        traceSmoothCurve(coords, "y2");
        ctx.strokeStyle = lineColor;
        ctx.lineWidth = 2.4;
        ctx.stroke();
        ctx.restore();

        coords.forEach((c, i) => {
          if (i === hoverIdx || n <= 16) {
            if (i === hoverIdx) {
              ctx.beginPath();
              ctx.arc(c.cx, c.y2, 9, 0, Math.PI * 2);
              ctx.fillStyle = lineColor + "33";
              ctx.fill();
            }
            ctx.beginPath();
            ctx.arc(c.cx, c.y2, i === hoverIdx ? 4.8 : 2.8, 0, Math.PI * 2);
            ctx.fillStyle = i === hoverIdx ? "#fff" : lineColor;
            ctx.fill();
          }
        });
      }
    }

    // X-axis labels (clamped so first/last never clip on mobile)
    const maxLabels = Math.max(3, Math.floor(plotW / 64));
    const labelEvery = Math.max(1, Math.ceil(n / maxLabels));
    ctx.fillStyle = "#94a3b8";
    ctx.font = "10.5px sans-serif";
    ctx.textBaseline = "top";
    coords.forEach((c, i) => {
      if (i % labelEvery === 0 || i === n - 1) {
        if (i === 0) {
          ctx.textAlign = "left";
          ctx.fillText(c.p.label, pad.left, pad.top + plotH + 10);
        } else if (i === n - 1) {
          ctx.textAlign = "right";
          ctx.fillText(c.p.label, w - pad.right, pad.top + plotH + 10);
        } else {
          ctx.textAlign = "center";
          ctx.fillText(c.p.label, c.cx, pad.top + plotH + 10);
        }
      }
    });

    state.chartGeom[canvasId] = { pad, stepX, n, coords, tooltipId, formatTooltip, redrawArgs: arguments[0] };
  }

  function bindChartHover(canvasId) {
    const canvas = $(canvasId);
    const handlePointer = (clientX) => {
      const geom = state.chartGeom[canvasId];
      if (!geom) return;
      const rect = canvas.getBoundingClientRect();
      const x = clientX - rect.left;
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
      let left = c.cx + 12;
      if (left + ttW > rect.width - 6) left = Math.max(6, c.cx - ttW - 12);
      tt.style.left = left + "px";
      tt.style.top = Math.max(6, Math.min(rect.height - 96, c.y1 - 24)) + "px";
    };
    canvas.addEventListener("mousemove", (ev) => handlePointer(ev.clientX));
    canvas.addEventListener("touchstart", (ev) => {
      if (ev.touches && ev.touches[0]) handlePointer(ev.touches[0].clientX);
    }, { passive: true });
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
      return c.name.toLowerCase().includes(q) || c.role.toLowerCase().includes(q);
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
      const rankClass = i === 0 ? "rank-pill top1" : i === 1 ? "rank-pill top2" : i === 2 ? "rank-pill top3" : "rank-pill";
      return '<tr>' +
        '<td><span class="' + rankClass + '">' + (i + 1) + '</span></td>' +
        '<td><strong>' + esc(c.name) + '</strong></td>' +
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
        headers: {
          "Content-Type": "application/json",
          "X-Requested-With": "VidVora-Dashboard",
        },
        credentials: "same-origin",
        body: JSON.stringify({ passkey }),
      });
      const json = await res.json().catch(() => ({}));
      if (!res.ok || !json.ok) {
        showLogin(json.error || "Invalid Superadmin or Team Passkey.");
        return;
      }
      $("passkeyInput").value = "";
      await loadStats();
    } catch (err) {
      showLogin("Network error: " + (err.message || err));
    } finally {
      btn.disabled = false;
      btn.textContent = "Unlock Analytics Dashboard";
    }
  });

  $("logoutBtn").addEventListener("click", async () => {
    try {
      await fetch("/api/admin-dashboard/logout", {
        method: "POST",
        headers: { "X-Requested-With": "VidVora-Dashboard" },
        credentials: "same-origin",
      });
    } catch (_) {}
    state.data = null;
    state.teamMembers = [];
    state.lastCreatedInvite = null;
    if ($("teamSecretBox")) $("teamSecretBox").style.display = "none";
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
    ["installs", "geo", "creators", "videos", "team"].forEach((t) => {
      const el = $("tab-" + t);
      if (el) el.style.display = t === state.activeTab ? "block" : "none";
    });
    if (state.activeTab === "team") {
      loadTeamMembers();
    }
  });

  $("teamCreateForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const errEl = $("teamErrorBanner");
    errEl.style.display = "none";
    const label = ($("teamLabelInput").value || "").trim();
    const passkey = ($("teamPasskeyInput").value || "").trim();
    const btn = $("teamCreateBtn");
    btn.disabled = true;
    btn.textContent = "Creating...";
    try {
      const res = await fetch("/api/admin-dashboard/team", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Requested-With": "VidVora-Dashboard",
        },
        credentials: "same-origin",
        body: JSON.stringify({ label, passkey }),
      });
      const json = await res.json().catch(() => ({}));
      if (!res.ok || !json.ok) {
        errEl.textContent = json.error || "Could not create team login.";
        errEl.style.display = "block";
        return;
      }
      $("teamLabelInput").value = "";
      $("teamPasskeyInput").value = "";
      state.teamMembers = json.members || [];
      renderTeamTable();
      const dashUrl = window.location.origin + "/admin-dashboard";
      state.lastCreatedInvite = {
        label: json.member.label,
        passkey: json.plainPasskey,
        url: dashUrl,
      };
      $("teamSecretMeta").textContent = "Member: " + json.member.label + "  •  Link: " + dashUrl;
      $("teamSecretCode").textContent = json.plainPasskey;
      $("teamSecretBox").style.display = "block";
    } catch (err) {
      errEl.textContent = "Network error: " + (err.message || err);
      errEl.style.display = "block";
    } finally {
      btn.disabled = false;
      btn.textContent = "+ Create Team Login";
    }
  });

  $("copyTeamPassBtn").addEventListener("click", async () => {
    if (!state.lastCreatedInvite) return;
    try {
      await navigator.clipboard.writeText(state.lastCreatedInvite.passkey);
      $("copyTeamPassBtn").textContent = "Copied!";
      setTimeout(() => { $("copyTeamPassBtn").textContent = "Copy Passkey"; }, 1800);
    } catch (_) {}
  });

  $("copyTeamInviteBtn").addEventListener("click", async () => {
    if (!state.lastCreatedInvite) return;
    const inv = state.lastCreatedInvite;
    const msg = "VidVora Analytics Dashboard Login (" + inv.label + ")\\nLink: " + inv.url + "\\nTeam Passkey: " + inv.passkey;
    try {
      await navigator.clipboard.writeText(msg);
      $("copyTeamInviteBtn").textContent = "Message Copied!";
      setTimeout(() => { $("copyTeamInviteBtn").textContent = "Copy WhatsApp / Telegram Message"; }, 1800);
    } catch (_) {}
  });

  $("teamTableBody").addEventListener("click", async (e) => {
    const btn = e.target.closest("button[data-revoke-id]");
    if (!btn) return;
    const memberId = btn.dataset.revokeId;
    const memberLabel = btn.dataset.revokeLabel || "this member";
    if (!window.confirm("Revoke analytics access for " + memberLabel + "? Unka login turant band ho jayega.")) return;
    btn.disabled = true;
    btn.textContent = "Revoking...";
    try {
      const res = await fetch("/api/admin-dashboard/team/revoke", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Requested-With": "VidVora-Dashboard",
        },
        credentials: "same-origin",
        body: JSON.stringify({ id: memberId }),
      });
      const json = await res.json().catch(() => ({}));
      if (res.ok && json.ok && Array.isArray(json.members)) {
        state.teamMembers = json.members;
        renderTeamTable();
      } else {
        btn.disabled = false;
        btn.textContent = "Revoke";
      }
    } catch (_) {
      btn.disabled = false;
      btn.textContent = "Revoke";
    }
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
      c.name,
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
      ["Name", "Role", "Videos", "24h Views", "Real Views", "Credited Views", "Earnings (INR)", "Status"],
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
  TEAM_ACCESS_DOC_ID,
  isEmulatorDeviceLabel,
  toEpochMs,
  hashTeamPasskey,
  verifyTeamPasskeyHash,
  generateReadableTeamPasskey,
  sanitizeTeamMembersForResponse,
  loadTeamAccessMembers,
  authenticateTeamPasskey,
  isTeamMemberActive,
  createTeamAccessMember,
  revokeTeamAccessMember,
  createAdminDashboardToken,
  verifyAdminDashboardSession,
  verifyAdminDashboardToken,
  extractDashboardTokenFromRequest,
  buildAdminDashboardStats,
  renderAdminDashboardHtml,
};
