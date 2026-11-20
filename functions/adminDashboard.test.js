const test = require("node:test");
const assert = require("node:assert/strict");
const {
  ADMIN_COOKIE_NAME,
  isEmulatorDeviceLabel,
  createAdminDashboardToken,
  verifyAdminDashboardToken,
  extractDashboardTokenFromRequest,
  buildAdminDashboardStats,
  renderAdminDashboardHtml,
} = require("./adminDashboard");

test("dashboard auth token signs, verifies, rejects tampering and expiry", () => {
  const secret = "test-super-secret-2026";
  const now = Date.UTC(2026, 10, 20, 10, 0, 0);
  const token = createAdminDashboardToken(secret, 60 * 60 * 1000, now);

  assert.equal(verifyAdminDashboardToken(token, secret, now + 1000), true);
  assert.equal(verifyAdminDashboardToken(token, "wrong-secret", now + 1000), false);
  assert.equal(verifyAdminDashboardToken(token + "x", secret, now + 1000), false);
  assert.equal(verifyAdminDashboardToken(token, secret, now + 60 * 60 * 1000 + 1), false);

  // Extract from cookie and Authorization Bearer header
  const fromCookie = extractDashboardTokenFromRequest({
    headers: { cookie: `foo=bar; ${ADMIN_COOKIE_NAME}=${encodeURIComponent(token)}` },
  });
  assert.equal(fromCookie, token);

  const fromBearer = extractDashboardTokenFromRequest({
    headers: { authorization: `Bearer ${token}` },
  });
  assert.equal(fromBearer, token);
});

test("emulator detection matches Android rules", () => {
  assert.equal(isEmulatorDeviceLabel("Google sdk_gphone64_x86_64"), true);
  assert.equal(isEmulatorDeviceLabel("AOSP on IA Emulator"), true);
  assert.equal(isEmulatorDeviceLabel("Samsung SM-S918B"), false);
  assert.equal(isEmulatorDeviceLabel("Xiaomi Redmi Note 13"), false);
});

test("buildAdminDashboardStats computes installs, 24h views, daily series, and strips sensitive/heavy fields", () => {
  // 2026-11-20 12:00:00 UTC => 2026-11-20 17:30 IST
  const nowMs = Date.UTC(2026, 10, 20, 12, 0, 0);
  const DAY = 24 * 60 * 60 * 1000;

  const installs = [
    {
      installId: "real-today-1",
      deviceLabel: "Samsung Galaxy S23",
      appVersion: "1.7",
      androidVersion: "14",
      firstInstalledAt: nowMs - 2 * 60 * 60 * 1000,
      lastSeenAt: nowMs - 10 * 60 * 1000,
      sessionCount: 5,
      isMarkedUninstalled: false,
    },
    {
      installId: "real-old-uninstalled",
      deviceLabel: "Redmi Note 12",
      appVersion: "1.6",
      androidVersion: "13",
      firstInstalledAt: nowMs - 20 * DAY,
      lastSeenAt: nowMs - 16 * DAY, // > 14 days inactive => UNINSTALLED
      sessionCount: 2,
      isMarkedUninstalled: false,
    },
    {
      installId: "real-yesterday",
      deviceLabel: "Vivo V29",
      appVersion: "1.7",
      androidVersion: "14",
      firstInstalledAt: nowMs - 1 * DAY,
      lastSeenAt: nowMs - 12 * 60 * 60 * 1000,
      sessionCount: 9,
      isMarkedUninstalled: false,
    },
    {
      installId: "emu-test-device",
      deviceLabel: "Google sdk_gphone64_arm64",
      appVersion: "1.7",
      androidVersion: "14",
      firstInstalledAt: nowMs - 1000,
      lastSeenAt: nowMs - 500,
      sessionCount: 1,
      isMarkedUninstalled: false,
    },
  ];

  const videos = [
    {
      videoId: "vid1",
      title: "Trending Reel",
      uploadedBy: "creator_1",
      uploaderName: "Rahul",
      totalViews: 450,
      createdAt: nowMs - 3 * 60 * 60 * 1000,
      isActive: true,
      thumbnailPath: "data:image/jpeg;base64," + "A".repeat(50000),
      storagePath: "https://secret.stream/1",
    },
    {
      videoId: "vid2",
      title: "Admin Clip",
      uploadedBy: "admin",
      uploaderName: "Admin",
      totalViews: 150,
      createdAt: nowMs - 2 * DAY,
      isActive: true,
      thumbnailPath: "data:image/jpeg;base64," + "B".repeat(50000),
    },
  ];

  const members = [
    {
      memberId: "creator_1",
      name: "Rahul",
      emailOrPhone: "rahul@example.com",
      passkey: "SUPER_SECRET_CREATOR_PASSWORD",
      role: "member",
      isActive: true,
      creditedViews: 400,
      totalEarning: 76.0,
      viewRatePer1000: 190,
      hourlyViews: {
        "2026112011": 80, // 1h ago (inside 24h window, IST 2026-11-20)
        "2026112012": 45, // current hour (inside 24h window, IST 2026-11-20)
        "2026111810": 120, // 2 days ago (outside 24h, inside 7d dailyViews)
      },
    },
  ];

  const withdrawals = [
    { requestId: "w1", amount: 500, status: "PENDING" },
    { requestId: "w2", amount: 1200, status: "DONE" },
  ];

  const stats = buildAdminDashboardStats({
    installs,
    videos,
    members,
    withdrawals,
    days: 7,
    nowMs,
  });

  assert.equal(stats.ok, true);
  assert.equal(stats.summary.totalDownloads, 3);
  assert.equal(stats.summary.hiddenEmulatorCount, 1);
  assert.equal(stats.summary.currentlyInstalled, 2);
  assert.equal(stats.summary.uninstalledCount, 1);
  assert.equal(stats.summary.downloadsToday, 1);
  assert.equal(stats.summary.activeLast24h, 2);
  assert.equal(stats.summary.totalRealViews, 600);
  assert.equal(stats.summary.totalCreditedViews, 400);
  assert.equal(stats.summary.viewsLast24h, 125);
  assert.equal(stats.summary.viewsToday, 125);
  assert.equal(stats.summary.videosUploadedToday, 1);
  assert.equal(stats.summary.pendingWithdrawalCount, 1);
  assert.equal(stats.summary.pendingWithdrawalAmount, 500);
  assert.equal(stats.summary.paidWithdrawalAmount, 1200);

  assert.equal(stats.charts.dailyInstalls.length, 7);
  assert.equal(stats.charts.hourlyViews24h.length, 24);
  assert.equal(stats.charts.dailyViews.length, 7);

  // Verify cumulative installs includes pre-window real install (20 days ago)
  const lastDayInstall = stats.charts.dailyInstalls[stats.charts.dailyInstalls.length - 1];
  assert.equal(lastDayInstall.newInstalls, 1);
  assert.equal(lastDayInstall.cumulativeInstalls, 3);

  // Verify dailyViews captured 2-days-ago tracked views (120) and today (125)
  const dayNov18 = stats.charts.dailyViews.find((d) => d.date === "2026-11-18");
  assert.ok(dayNov18);
  assert.equal(dayNov18.trackedViews, 120);

  // Verify privacy & payload size: no passkey, no base64 thumbnailPath, no storagePath
  const serialized = JSON.stringify(stats);
  assert.equal(serialized.includes("SUPER_SECRET_CREATOR_PASSWORD"), false);
  assert.equal(serialized.includes("data:image/jpeg;base64"), false);
  assert.equal(serialized.includes("secret.stream"), false);
});

test("renderAdminDashboardHtml returns complete dashboard HTML with valid inline JS", () => {
  const html = renderAdminDashboardHtml();
  assert.ok(html.includes("<!DOCTYPE html>"));
  assert.ok(html.includes("/api/admin-dashboard/login"));
  assert.ok(html.includes("/api/admin-dashboard/stats"));
  assert.ok(html.includes("installsCanvas"));
  assert.ok(html.includes("viewsCanvas"));
  assert.ok(html.includes("geoCountriesList"));
  assert.ok(html.includes("geoCitiesList"));
  assert.ok(html.includes("Download Install CSV"));
  assert.ok(html.includes("Download Geo CSV"));

  const scriptMatch = html.match(/<script>([\s\S]*?)<\/script>/);
  assert.ok(scriptMatch && scriptMatch[1], "Expected inline <script> block");
  assert.doesNotThrow(() => new Function(scriptMatch[1]));
});

test("geoTracker resolves Country & City via IP cache, edge headers, and flushes compact stats to DB", async () => {
  const {
    extractClientIp,
    createViewGeoTracker,
    buildGeoAnalyticsSummary,
    GEO_DOC_COLLECTION,
    GEO_DOC_ID,
  } = require("./geoTracker");

  assert.equal(
    extractClientIp({ headers: { "x-forwarded-for": "10.0.0.1, 49.36.120.45, 172.16.0.1" } }),
    "49.36.120.45"
  );
  assert.equal(extractClientIp({ headers: { "x-forwarded-for": "127.0.0.1" } }), "");

  let fetchCalls = 0;
  const fakeFetch = async (url) => {
    fetchCalls += 1;
    if (url.includes("49.36.120.45")) {
      return {
        ok: true,
        status: 200,
        json: async () => ({
          country: "India",
          country_code: "IN",
          region: "Maharashtra",
          city: "Mumbai",
        }),
      };
    }
    return {
      ok: true,
      status: 200,
      json: async () => ({
        country: "United Arab Emirates",
        country_code: "AE",
        region: "Dubai",
        city: "Dubai",
      }),
    };
  };

  const docStore = new Map();
  const fakeDb = {
    collection: (col) => ({
      doc: (id) => ({
        get: async () => {
          const val = docStore.get(`${col}/${id}`);
          return { exists: Boolean(val), data: () => val };
        },
        set: async (data) => {
          docStore.set(`${col}/${id}`, data);
        },
      }),
    }),
  };

  const tracker = createViewGeoTracker({ flushIntervalMs: 60_000, flushThreshold: 100, fetchFn: fakeFetch });
  const nowMs = Date.UTC(2026, 10, 20, 12, 0, 0);

  // Two views from same Indian IP -> only 1 external GeoIP call thanks to RAM cache
  await tracker.recordViewGeo({
    db: fakeDb,
    req: { headers: { "x-forwarded-for": "49.36.120.45" } },
    videoId: "v1",
    videoTitle: "Mumbai Clip",
    nowMs,
  });
  await tracker.recordViewGeo({
    db: fakeDb,
    req: { headers: { "x-forwarded-for": "49.36.120.45" } },
    videoId: "v2",
    videoTitle: "Mumbai Clip 2",
    nowMs: nowMs + 1000,
  });
  // One view with Cloudflare city+country headers -> 0 external calls
  await tracker.recordViewGeo({
    db: fakeDb,
    req: {
      headers: {
        "cf-connecting-ip": "103.25.10.2",
        "cf-ipcountry": "IN",
        "cf-ipcity": "Lucknow",
        "cf-region": "Uttar Pradesh",
      },
    },
    videoId: "v3",
    videoTitle: "Lucknow Clip",
    nowMs: nowMs + 2000,
  });
  // One view from UAE IP
  await tracker.recordViewGeo({
    db: fakeDb,
    req: { headers: { "x-forwarded-for": "5.32.45.10" } },
    videoId: "v4",
    videoTitle: "Dubai Clip",
    nowMs: nowMs + 3000,
  });

  assert.equal(fetchCalls, 2);

  // Even before flush, live merged doc has all 4 views
  const liveBeforeFlush = await tracker.getMergedGeoDoc(fakeDb, nowMs + 4000);
  const summaryLive = buildGeoAnalyticsSummary(liveBeforeFlush, nowMs + 4000);
  assert.equal(summaryLive.totalTrackedViews, 4);
  assert.equal(summaryLive.uniqueCountriesCount, 2);
  assert.equal(summaryLive.uniqueCitiesCount, 3);
  assert.equal(summaryLive.countries[0].country, "India");
  assert.equal(summaryLive.countries[0].viewsAll, 3);
  assert.equal(summaryLive.cities[0].city, "Mumbai");
  assert.equal(summaryLive.cities[0].viewsAll, 2);
  assert.equal(summaryLive.recentLocations.length, 4);
  assert.equal(summaryLive.recentLocations[0].city, "Dubai");

  // Flush to DB and verify persisted cleanly
  const flushed = await tracker.flushPending(fakeDb, nowMs + 5000);
  assert.equal(flushed, true);
  assert.ok(docStore.has(`${GEO_DOC_COLLECTION}/${GEO_DOC_ID}`));

  const afterFlush = await tracker.getMergedGeoDoc(fakeDb, nowMs + 6000);
  const stats = buildAdminDashboardStats({ geoStats: afterFlush, nowMs: nowMs + 6000 });
  assert.equal(stats.geo.totalTrackedViews, 4);
  assert.equal(stats.geo.countries[0].flag, "🇮🇳");
  assert.equal(stats.geo.cities[0].region, "Maharashtra");
});

