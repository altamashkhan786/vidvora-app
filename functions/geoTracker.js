const IST_OFFSET_MS = 5.5 * 60 * 60 * 1000;
const GEO_CACHE_TTL_MS = 24 * 60 * 60 * 1000; // 24 hours per IP
const MAX_IP_GEO_CACHE = 10_000;
const PROVIDER_COOLDOWN_MS = 5 * 60 * 1000; // 5 min cooldown if a provider rate-limits
const MAX_DAILY_RETENTION_DAYS = 30;
const MAX_STORED_COUNTRIES = 150;
const MAX_STORED_CITIES = 350;
const MAX_RECENT_LOCATIONS = 50;
const GEO_DOC_COLLECTION = "appConfig";
const GEO_DOC_ID = "viewGeoStats";

let regionDisplayNames = null;
try {
  regionDisplayNames = new Intl.DisplayNames(["en"], { type: "region" });
} catch (_) {
  regionDisplayNames = null;
}

function formatIstDateKey(epochMs = Date.now()) {
  const d = new Date(epochMs + IST_OFFSET_MS);
  const yyyy = d.getUTCFullYear();
  const mm = String(d.getUTCMonth() + 1).padStart(2, "0");
  const dd = String(d.getUTCDate()).padStart(2, "0");
  return `${yyyy}-${mm}-${dd}`;
}

function sanitizeGeoText(raw, maxLen = 64) {
  const cleaned = String(raw || "")
    .replace(/[\r\n\t|]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  if (!cleaned) return "";
  if (/^(unknown|none|null|undefined|n\/a|xx|t1)$/i.test(cleaned)) return "";
  return cleaned.slice(0, maxLen);
}

function normalizeCountryCode(raw) {
  const code = String(raw || "").trim().toUpperCase();
  if (!/^[A-Z]{2}$/.test(code)) return "";
  if (code === "XX" || code === "T1") return "";
  return code;
}

function countryCodeToName(code) {
  const cc = normalizeCountryCode(code);
  if (!cc) return "";
  if (regionDisplayNames) {
    try {
      const name = regionDisplayNames.of(cc);
      if (name && name.toUpperCase() !== cc) return name;
    } catch (_) {
      // ignore
    }
  }
  return cc;
}

function countryCodeToFlag(code) {
  const cc = normalizeCountryCode(code);
  if (!cc) return "🌐";
  const base = 0x1f1e6;
  const first = cc.charCodeAt(0) - 65 + base;
  const second = cc.charCodeAt(1) - 65 + base;
  return String.fromCodePoint(first, second);
}

function cleanIpString(rawIp) {
  let ip = String(rawIp || "").trim();
  if (!ip) return "";
  // Strip IPv4 port or brackets like [::1]:8080 or 1.2.3.4:1234
  if (ip.startsWith("[")) {
    const closeIdx = ip.indexOf("]");
    if (closeIdx > 1) ip = ip.slice(1, closeIdx);
  } else if (/^\d{1,3}(\.\d{1,3}){3}:\d+$/.test(ip)) {
    ip = ip.split(":")[0];
  }
  if (ip.toLowerCase().startsWith("::ffff:")) {
    ip = ip.slice(7);
  }
  return ip.trim();
}

function isPrivateOrLocalIp(rawIp) {
  const ip = cleanIpString(rawIp);
  if (!ip) return true;
  if (ip === "::1" || ip === "0.0.0.0" || ip === "localhost") return true;
  const lower = ip.toLowerCase();
  if (lower.startsWith("fe80:") || lower.startsWith("fc") || lower.startsWith("fd")) return true;
  if (ip.startsWith("127.") || ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("169.254.") || ip.startsWith("0.")) {
    return true;
  }
  const m172 = ip.match(/^172\.(\d{1,3})\./);
  if (m172) {
    const second = parseInt(m172[1], 10);
    if (second >= 16 && second <= 31) return true;
  }
  return false;
}

function extractClientIp(req) {
  const headers = req?.headers || {};
  const candidates = [];

  const pushHeader = (val) => {
    if (!val) return;
    const raw = Array.isArray(val) ? val.join(",") : String(val);
    for (const part of raw.split(",")) {
      const cleaned = cleanIpString(part);
      if (cleaned) candidates.push(cleaned);
    }
  };

  pushHeader(headers["cf-connecting-ip"]);
  pushHeader(headers["true-client-ip"]);
  pushHeader(headers["x-forwarded-for"]);
  pushHeader(headers["x-real-ip"]);
  pushHeader(req?.socket?.remoteAddress || req?.connection?.remoteAddress);

  for (const ip of candidates) {
    if (!isPrivateOrLocalIp(ip)) return ip;
  }
  return "";
}

function extractEdgeGeoHeaders(headers = {}) {
  const lowerHeaders = {};
  for (const [k, v] of Object.entries(headers || {})) {
    lowerHeaders[String(k).toLowerCase()] = Array.isArray(v) ? v[0] : v;
  }

  const rawCode =
    lowerHeaders["cf-ipcountry"] ||
    lowerHeaders["x-vercel-ip-country"] ||
    lowerHeaders["x-country-code"] ||
    "";
  const countryCode = normalizeCountryCode(rawCode);
  const country = countryCode ? countryCodeToName(countryCode) : "";

  const decodeHeader = (val) => {
    const raw = String(val || "").trim();
    if (!raw) return "";
    try {
      return sanitizeGeoText(decodeURIComponent(raw));
    } catch (_) {
      return sanitizeGeoText(raw);
    }
  };

  const city = decodeHeader(
    lowerHeaders["cf-ipcity"] ||
      lowerHeaders["x-vercel-ip-city"] ||
      lowerHeaders["x-appengine-city"] ||
      ""
  );
  const region = decodeHeader(
    lowerHeaders["cf-region"] ||
      lowerHeaders["x-vercel-ip-country-region"] ||
      lowerHeaders["x-appengine-region"] ||
      ""
  );

  return { countryCode, country, region, city };
}

function makeCountryKey(countryCode, country) {
  const cc = normalizeCountryCode(countryCode) || "ZZ";
  const name = sanitizeGeoText(country) || (cc !== "ZZ" ? countryCodeToName(cc) : "Unknown");
  return `${cc}|${name}`;
}

function formatCompactCityDisplay(city, region) {
  const cleanCity = sanitizeGeoText(city) || "Unknown City";
  const cleanRegion = sanitizeGeoText(region);
  if (!cleanRegion) return cleanCity;
  const lcCity = cleanCity.toLowerCase();
  const lcRegion = cleanRegion.toLowerCase();
  if (lcRegion === lcCity || lcRegion.includes(lcCity) || lcCity.includes(lcRegion)) {
    return cleanCity;
  }
  return `${cleanCity}, ${cleanRegion}`;
}

function makeCityKey(city, region, countryCode, country) {
  const cc = normalizeCountryCode(countryCode) || "ZZ";
  const cleanCountry = sanitizeGeoText(country) || (cc !== "ZZ" ? countryCodeToName(cc) : "Unknown");
  const cityDisplay = formatCompactCityDisplay(city, region);
  return `${cityDisplay}|${cc}|${cleanCountry}`;
}

function parseCountryKey(key) {
  const parts = String(key || "").split("|");
  if (parts.length >= 2) {
    const code = normalizeCountryCode(parts[0]);
    const country = parts.slice(1).join("|").trim() || countryCodeToName(code) || "Unknown";
    return { countryCode: code, country, flag: countryCodeToFlag(code) };
  }
  const fallback = String(key || "Unknown").trim() || "Unknown";
  return { countryCode: "", country: fallback, flag: "🌐" };
}

function parseCityKey(key) {
  const parts = String(key || "").split("|");
  if (parts.length >= 3) {
    const cityRegion = parts[0].trim() || "Unknown City";
    const code = normalizeCountryCode(parts[1]);
    const country = parts.slice(2).join("|").trim() || countryCodeToName(code) || "Unknown";
    const commaIdx = cityRegion.indexOf(",");
    const city = commaIdx > 0 ? cityRegion.slice(0, commaIdx).trim() : cityRegion;
    const region = commaIdx > 0 ? cityRegion.slice(commaIdx + 1).trim() : "";
    return {
      city,
      region,
      cityDisplay: formatCompactCityDisplay(city, region),
      countryCode: code,
      country,
      flag: countryCodeToFlag(code),
    };
  }
  const fallback = String(key || "Unknown City").trim() || "Unknown City";
  return {
    city: fallback,
    region: "",
    cityDisplay: fallback,
    countryCode: "",
    country: "Unknown",
    flag: "🌐",
  };
}

function pruneTopMap(mapObj, maxEntries) {
  const entries = Object.entries(mapObj || {}).filter(([, v]) => Number(v) > 0);
  if (entries.length <= maxEntries) {
    return Object.fromEntries(entries.map(([k, v]) => [k, Math.floor(Number(v))]));
  }
  entries.sort((a, b) => Number(b[1]) - Number(a[1]));
  return Object.fromEntries(entries.slice(0, maxEntries).map(([k, v]) => [k, Math.floor(Number(v))]));
}

function mergeCountMaps(baseMap, deltaMap) {
  const out = {};
  if (baseMap && typeof baseMap === "object") {
    for (const [k, v] of Object.entries(baseMap)) {
      const n = Math.floor(Number(v) || 0);
      if (n > 0) out[k] = n;
    }
  }
  if (deltaMap && typeof deltaMap === "object") {
    for (const [k, v] of Object.entries(deltaMap)) {
      const n = Math.floor(Number(v) || 0);
      if (n > 0) out[k] = (out[k] || 0) + n;
    }
  }
  return out;
}

function mergeDailyNestedMaps(baseDaily, deltaDaily, cutoffDateKey, maxPerDay) {
  const allDates = new Set([
    ...Object.keys(baseDaily || {}),
    ...Object.keys(deltaDaily || {}),
  ]);
  const out = {};
  for (const dateKey of allDates) {
    if (cutoffDateKey && dateKey < cutoffDateKey) continue;
    const merged = mergeCountMaps(baseDaily?.[dateKey], deltaDaily?.[dateKey]);
    if (Object.keys(merged).length > 0) {
      out[dateKey] = pruneTopMap(merged, maxPerDay);
    }
  }
  return out;
}

function createEmptyDelta() {
  return {
    totalTrackedViews: 0,
    countries: {},
    cities: {},
    dailyCountries: {},
    dailyCities: {},
    recentLocations: [],
  };
}

function createViewGeoTracker({
  flushIntervalMs = 15_000,
  flushThreshold = 25,
  fetchFn = null,
} = {}) {
  const ipGeoCache = new Map(); // ip -> { geo, expiresAt }
  const ipGeoInflight = new Map(); // ip -> Promise<geo|null>
  const providerCooldownUntil = new Map(); // providerName -> epochMs

  let pendingDelta = createEmptyDelta();
  let flushTimer = null;
  let flushInflight = null;
  let activeDbRef = null;

  function getFetch() {
    return fetchFn || globalThis.fetch;
  }

  function putIpCache(ip, geo, nowMs) {
    if (ipGeoCache.size >= MAX_IP_GEO_CACHE) {
      const oldestKey = ipGeoCache.keys().next().value;
      if (oldestKey !== undefined) ipGeoCache.delete(oldestKey);
    }
    ipGeoCache.set(ip, { geo, expiresAt: nowMs + GEO_CACHE_TTL_MS });
  }

  async function queryGeoProviders(ip, nowMs) {
    const fn = getFetch();
    if (typeof fn !== "function") return null;

    const providers = [
      {
        name: "geojs",
        url: `https://get.geojs.io/v1/ip/geo/${encodeURIComponent(ip)}.json`,
        parse: (data) => {
          const countryCode = normalizeCountryCode(data?.country_code);
          const country = sanitizeGeoText(data?.country) || countryCodeToName(countryCode);
          const region = sanitizeGeoText(data?.region);
          const city = sanitizeGeoText(data?.city);
          if (!country && !countryCode) return null;
          return { countryCode, country, region, city };
        },
      },
      {
        name: "ipwhois",
        url: `https://ipwho.is/${encodeURIComponent(ip)}?fields=success,country,country_code,region,city`,
        parse: (data) => {
          if (data?.success === false) return null;
          const countryCode = normalizeCountryCode(data?.country_code);
          const country = sanitizeGeoText(data?.country) || countryCodeToName(countryCode);
          const region = sanitizeGeoText(data?.region);
          const city = sanitizeGeoText(data?.city);
          if (!country && !countryCode) return null;
          return { countryCode, country, region, city };
        },
      },
      {
        name: "ipapi",
        url: `http://ip-api.com/json/${encodeURIComponent(ip)}?fields=status,country,countryCode,regionName,city`,
        parse: (data) => {
          if (data?.status && data.status !== "success") return null;
          const countryCode = normalizeCountryCode(data?.countryCode);
          const country = sanitizeGeoText(data?.country) || countryCodeToName(countryCode);
          const region = sanitizeGeoText(data?.regionName);
          const city = sanitizeGeoText(data?.city);
          if (!country && !countryCode) return null;
          return { countryCode, country, region, city };
        },
      },
    ];

    for (const p of providers) {
      const coolUntil = providerCooldownUntil.get(p.name) || 0;
      if (coolUntil > nowMs) continue;
      try {
        const res = await fn(p.url, {
          headers: { Accept: "application/json", "User-Agent": "VidVora-Analytics/1.0" },
          signal: typeof AbortSignal !== "undefined" && AbortSignal.timeout ? AbortSignal.timeout(2500) : undefined,
        });
        if (res.status === 429 || res.status >= 500) {
          providerCooldownUntil.set(p.name, nowMs + PROVIDER_COOLDOWN_MS);
          continue;
        }
        if (!res.ok) continue;
        const json = await res.json().catch(() => null);
        const parsed = p.parse(json);
        if (parsed && (parsed.country || parsed.countryCode)) {
          return parsed;
        }
      } catch (_) {
        // Move to next provider silently
      }
    }
    return null;
  }

  async function resolveGeo({ ip, headers = {}, nowMs = Date.now() }) {
    const edge = extractEdgeGeoHeaders(headers);
    if (edge.countryCode && edge.city) {
      return {
        countryCode: edge.countryCode,
        country: edge.country || countryCodeToName(edge.countryCode),
        region: edge.region,
        city: edge.city,
      };
    }

    const cleanIp = cleanIpString(ip);
    if (!cleanIp || isPrivateOrLocalIp(cleanIp)) {
      if (edge.countryCode) {
        return {
          countryCode: edge.countryCode,
          country: edge.country || countryCodeToName(edge.countryCode),
          region: edge.region,
          city: edge.city || "Unknown City",
        };
      }
      return null;
    }

    const cached = ipGeoCache.get(cleanIp);
    if (cached && cached.expiresAt > nowMs) {
      return cached.geo;
    }

    let inflight = ipGeoInflight.get(cleanIp);
    if (!inflight) {
      inflight = (async () => {
        const lookedUp = await queryGeoProviders(cleanIp, nowMs);
        let finalGeo = null;
        if (lookedUp) {
          const cc = lookedUp.countryCode || edge.countryCode;
          finalGeo = {
            countryCode: cc,
            country: lookedUp.country || edge.country || countryCodeToName(cc) || "Unknown",
            region: lookedUp.region || edge.region || "",
            city: lookedUp.city || edge.city || "Unknown City",
          };
        } else if (edge.countryCode) {
          finalGeo = {
            countryCode: edge.countryCode,
            country: edge.country || countryCodeToName(edge.countryCode),
            region: edge.region || "",
            city: edge.city || "Unknown City",
          };
        }
        if (finalGeo) {
          putIpCache(cleanIp, finalGeo, nowMs);
        }
        return finalGeo;
      })().finally(() => {
        ipGeoInflight.delete(cleanIp);
      });
      ipGeoInflight.set(cleanIp, inflight);
    }

    return inflight;
  }

  function applyGeoHitToDelta(geo, { videoId = "", videoTitle = "", nowMs = Date.now() } = {}) {
    if (!geo || (!geo.country && !geo.countryCode)) return;
    const istDate = formatIstDateKey(nowMs);
    const countryKey = makeCountryKey(geo.countryCode, geo.country);
    const cityKey = makeCityKey(geo.city, geo.region, geo.countryCode, geo.country);

    pendingDelta.totalTrackedViews += 1;
    pendingDelta.countries[countryKey] = (pendingDelta.countries[countryKey] || 0) + 1;
    pendingDelta.cities[cityKey] = (pendingDelta.cities[cityKey] || 0) + 1;

    if (!pendingDelta.dailyCountries[istDate]) pendingDelta.dailyCountries[istDate] = {};
    pendingDelta.dailyCountries[istDate][countryKey] =
      (pendingDelta.dailyCountries[istDate][countryKey] || 0) + 1;

    if (!pendingDelta.dailyCities[istDate]) pendingDelta.dailyCities[istDate] = {};
    pendingDelta.dailyCities[istDate][cityKey] =
      (pendingDelta.dailyCities[istDate][cityKey] || 0) + 1;

    pendingDelta.recentLocations.unshift({
      countryCode: normalizeCountryCode(geo.countryCode),
      country: sanitizeGeoText(geo.country) || countryCodeToName(geo.countryCode) || "Unknown",
      region: sanitizeGeoText(geo.region),
      city: sanitizeGeoText(geo.city) || "Unknown City",
      videoId: String(videoId || "").trim().slice(0, 64),
      videoTitle: sanitizeGeoText(videoTitle, 90) || "Video",
      atMs: nowMs,
    });
    if (pendingDelta.recentLocations.length > MAX_RECENT_LOCATIONS) {
      pendingDelta.recentLocations.length = MAX_RECENT_LOCATIONS;
    }
  }

  function scheduleFlush(db) {
    activeDbRef = db || activeDbRef;
    if (!activeDbRef) return;
    if (pendingDelta.totalTrackedViews >= flushThreshold) {
      if (flushTimer) {
        clearTimeout(flushTimer);
        flushTimer = null;
      }
      void flushPending(activeDbRef).catch(() => {});
      return;
    }
    if (!flushTimer) {
      flushTimer = setTimeout(() => {
        flushTimer = null;
        if (activeDbRef) {
          void flushPending(activeDbRef).catch(() => {});
        }
      }, flushIntervalMs);
      if (typeof flushTimer.unref === "function") flushTimer.unref();
    }
  }

  async function flushPending(db, nowMs = Date.now()) {
    const targetDb = db || activeDbRef;
    if (!targetDb || pendingDelta.totalTrackedViews <= 0) return false;
    if (flushInflight) return flushInflight;

    const snapshotDelta = pendingDelta;
    pendingDelta = createEmptyDelta();

    flushInflight = (async () => {
      try {
        const docRef = targetDb.collection(GEO_DOC_COLLECTION).doc(GEO_DOC_ID);
        const snap = await docRef.get();
        const existing = snap.exists ? snap.data() || {} : {};
        const cutoffDateKey = formatIstDateKey(nowMs - MAX_DAILY_RETENTION_DAYS * 24 * 60 * 60 * 1000);

        const mergedCountries = pruneTopMap(
          mergeCountMaps(existing.countries, snapshotDelta.countries),
          MAX_STORED_COUNTRIES
        );
        const mergedCities = pruneTopMap(
          mergeCountMaps(existing.cities, snapshotDelta.cities),
          MAX_STORED_CITIES
        );
        const mergedDailyCountries = mergeDailyNestedMaps(
          existing.dailyCountries,
          snapshotDelta.dailyCountries,
          cutoffDateKey,
          80
        );
        const mergedDailyCities = mergeDailyNestedMaps(
          existing.dailyCities,
          snapshotDelta.dailyCities,
          cutoffDateKey,
          120
        );

        const existingRecent = Array.isArray(existing.recentLocations) ? existing.recentLocations : [];
        const mergedRecent = [...snapshotDelta.recentLocations, ...existingRecent].slice(
          0,
          MAX_RECENT_LOCATIONS
        );

        const nextDoc = {
          totalTrackedViews:
            Math.max(0, Math.floor(Number(existing.totalTrackedViews) || 0)) +
            snapshotDelta.totalTrackedViews,
          countries: mergedCountries,
          cities: mergedCities,
          dailyCountries: mergedDailyCountries,
          dailyCities: mergedDailyCities,
          recentLocations: mergedRecent,
          updatedAtMs: nowMs,
        };

        await docRef.set(nextDoc, { merge: false });
        return true;
      } catch (err) {
        // Restore delta so no geo hits are lost on transient error
        pendingDelta.totalTrackedViews += snapshotDelta.totalTrackedViews;
        pendingDelta.countries = mergeCountMaps(snapshotDelta.countries, pendingDelta.countries);
        pendingDelta.cities = mergeCountMaps(snapshotDelta.cities, pendingDelta.cities);
        pendingDelta.dailyCountries = mergeDailyNestedMaps(
          snapshotDelta.dailyCountries,
          pendingDelta.dailyCountries,
          "",
          120
        );
        pendingDelta.dailyCities = mergeDailyNestedMaps(
          snapshotDelta.dailyCities,
          pendingDelta.dailyCities,
          "",
          150
        );
        pendingDelta.recentLocations = [
          ...pendingDelta.recentLocations,
          ...snapshotDelta.recentLocations,
        ].slice(0, MAX_RECENT_LOCATIONS);
        console.warn("Geo stats flush skipped:", err?.message || err);
        return false;
      } finally {
        flushInflight = null;
      }
    })();

    return flushInflight;
  }

  async function recordViewGeo({ db, req, clientIp, headers, videoId = "", videoTitle = "", nowMs = Date.now() }) {
    try {
      const ip = clientIp || extractClientIp(req);
      const reqHeaders = headers || req?.headers || {};
      const geo = await resolveGeo({ ip, headers: reqHeaders, nowMs });
      if (!geo) return null;
      applyGeoHitToDelta(geo, { videoId, videoTitle, nowMs });
      if (db) scheduleFlush(db);
      return geo;
    } catch (_) {
      return null;
    }
  }

  async function getMergedGeoDoc(db, nowMs = Date.now()) {
    let persisted = {};
    if (db) {
      try {
        const snap = await db.collection(GEO_DOC_COLLECTION).doc(GEO_DOC_ID).get();
        if (snap.exists) persisted = snap.data() || {};
      } catch (_) {
        persisted = {};
      }
    }
    const cutoffDateKey = formatIstDateKey(nowMs - MAX_DAILY_RETENTION_DAYS * 24 * 60 * 60 * 1000);
    return {
      totalTrackedViews:
        Math.max(0, Math.floor(Number(persisted.totalTrackedViews) || 0)) +
        pendingDelta.totalTrackedViews,
      countries: mergeCountMaps(persisted.countries, pendingDelta.countries),
      cities: mergeCountMaps(persisted.cities, pendingDelta.cities),
      dailyCountries: mergeDailyNestedMaps(
        persisted.dailyCountries,
        pendingDelta.dailyCountries,
        cutoffDateKey,
        100
      ),
      dailyCities: mergeDailyNestedMaps(
        persisted.dailyCities,
        pendingDelta.dailyCities,
        cutoffDateKey,
        150
      ),
      recentLocations: [
        ...pendingDelta.recentLocations,
        ...(Array.isArray(persisted.recentLocations) ? persisted.recentLocations : []),
      ].slice(0, MAX_RECENT_LOCATIONS),
      updatedAtMs: Math.max(Number(persisted.updatedAtMs) || 0, pendingDelta.totalTrackedViews > 0 ? nowMs : 0),
    };
  }

  return {
    resolveGeo,
    recordViewGeo,
    flushPending,
    getMergedGeoDoc,
  };
}

function buildGeoAnalyticsSummary(geoDoc = {}, nowMs = Date.now()) {
  const todayIst = formatIstDateKey(nowMs);
  const last7Keys = new Set();
  for (let i = 0; i < 7; i += 1) {
    last7Keys.add(formatIstDateKey(nowMs - i * 24 * 60 * 60 * 1000));
  }

  const countriesAllMap = geoDoc.countries && typeof geoDoc.countries === "object" ? geoDoc.countries : {};
  const citiesAllMap = geoDoc.cities && typeof geoDoc.cities === "object" ? geoDoc.cities : {};
  const dailyCountries = geoDoc.dailyCountries && typeof geoDoc.dailyCountries === "object" ? geoDoc.dailyCountries : {};
  const dailyCities = geoDoc.dailyCities && typeof geoDoc.dailyCities === "object" ? geoDoc.dailyCities : {};

  const countriesTodayMap = dailyCountries[todayIst] || {};
  const citiesTodayMap = dailyCities[todayIst] || {};

  const countries7dMap = {};
  const cities7dMap = {};
  for (const dKey of last7Keys) {
    if (dailyCountries[dKey]) {
      for (const [k, v] of Object.entries(dailyCountries[dKey])) {
        countries7dMap[k] = (countries7dMap[k] || 0) + Math.max(0, Math.floor(Number(v) || 0));
      }
    }
    if (dailyCities[dKey]) {
      for (const [k, v] of Object.entries(dailyCities[dKey])) {
        cities7dMap[k] = (cities7dMap[k] || 0) + Math.max(0, Math.floor(Number(v) || 0));
      }
    }
  }

  const allCountryKeys = new Set([
    ...Object.keys(countriesAllMap),
    ...Object.keys(countries7dMap),
    ...Object.keys(countriesTodayMap),
  ]);
  const allCityKeys = new Set([
    ...Object.keys(citiesAllMap),
    ...Object.keys(cities7dMap),
    ...Object.keys(citiesTodayMap),
  ]);

  let totalAll = Math.max(0, Math.floor(Number(geoDoc.totalTrackedViews) || 0));
  const sumCountriesAll = Object.values(countriesAllMap).reduce((s, v) => s + Math.max(0, Math.floor(Number(v) || 0)), 0);
  if (totalAll < sumCountriesAll) totalAll = sumCountriesAll;

  const totalToday = Object.values(countriesTodayMap).reduce((s, v) => s + Math.max(0, Math.floor(Number(v) || 0)), 0);
  const total7d = Object.values(countries7dMap).reduce((s, v) => s + Math.max(0, Math.floor(Number(v) || 0)), 0);

  const countries = Array.from(allCountryKeys)
    .map((key) => {
      const parsed = parseCountryKey(key);
      const viewsAll = Math.max(0, Math.floor(Number(countriesAllMap[key]) || 0));
      const views7d = Math.max(0, Math.floor(Number(countries7dMap[key]) || 0));
      const viewsToday = Math.max(0, Math.floor(Number(countriesTodayMap[key]) || 0));
      return {
        key,
        countryCode: parsed.countryCode,
        country: parsed.country,
        flag: parsed.flag,
        viewsAll,
        views7d,
        viewsToday,
        percentAll: totalAll > 0 ? Math.round((viewsAll * 1000) / totalAll) / 10 : 0,
        percent7d: total7d > 0 ? Math.round((views7d * 1000) / total7d) / 10 : 0,
        percentToday: totalToday > 0 ? Math.round((viewsToday * 1000) / totalToday) / 10 : 0,
      };
    })
    .filter((row) => row.viewsAll > 0 || row.views7d > 0 || row.viewsToday > 0)
    .sort((a, b) => b.viewsAll - a.viewsAll || b.views7d - a.views7d);

  const cities = Array.from(allCityKeys)
    .map((key) => {
      const parsed = parseCityKey(key);
      const viewsAll = Math.max(0, Math.floor(Number(citiesAllMap[key]) || 0));
      const views7d = Math.max(0, Math.floor(Number(cities7dMap[key]) || 0));
      const viewsToday = Math.max(0, Math.floor(Number(citiesTodayMap[key]) || 0));
      return {
        key,
        city: parsed.city,
        region: parsed.region,
        cityDisplay: parsed.cityDisplay,
        countryCode: parsed.countryCode,
        country: parsed.country,
        flag: parsed.flag,
        viewsAll,
        views7d,
        viewsToday,
        percentAll: totalAll > 0 ? Math.round((viewsAll * 1000) / totalAll) / 10 : 0,
        percent7d: total7d > 0 ? Math.round((views7d * 1000) / total7d) / 10 : 0,
        percentToday: totalToday > 0 ? Math.round((viewsToday * 1000) / totalToday) / 10 : 0,
      };
    })
    .filter((row) => row.viewsAll > 0 || row.views7d > 0 || row.viewsToday > 0)
    .sort((a, b) => b.viewsAll - a.viewsAll || b.views7d - a.views7d);

  const recentLocations = (Array.isArray(geoDoc.recentLocations) ? geoDoc.recentLocations : [])
    .slice(0, MAX_RECENT_LOCATIONS)
    .map((r) => ({
      countryCode: normalizeCountryCode(r.countryCode),
      flag: countryCodeToFlag(r.countryCode),
      country: sanitizeGeoText(r.country) || "Unknown",
      region: sanitizeGeoText(r.region),
      city: sanitizeGeoText(r.city) || "Unknown City",
      videoTitle: sanitizeGeoText(r.videoTitle, 90) || "Video",
      atMs: Number(r.atMs) || 0,
    }));

  return {
    totalTrackedViews: totalAll,
    totalTodayViews: totalToday,
    total7dViews: total7d,
    uniqueCountriesCount: countries.length,
    uniqueCitiesCount: cities.length,
    topCountry: countries[0] ? `${countries[0].flag} ${countries[0].country}` : "-",
    topCity: cities[0] ? `${cities[0].cityDisplay}` : "-",
    countries,
    cities,
    recentLocations,
    updatedAtMs: Number(geoDoc.updatedAtMs) || 0,
  };
}

module.exports = {
  GEO_DOC_COLLECTION,
  GEO_DOC_ID,
  extractClientIp,
  isPrivateOrLocalIp,
  extractEdgeGeoHeaders,
  countryCodeToName,
  countryCodeToFlag,
  createViewGeoTracker,
  buildGeoAnalyticsSummary,
};
