const test = require("node:test");
const assert = require("node:assert/strict");
const { DEFAULT_APK_URL } = require("./sharePreview");
const {
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
} = require("./downloadSite");

test("normalizeDirectApkUrl converts Google Drive and Dropbox share links to direct download URLs", () => {
  assert.equal(
    normalizeDirectApkUrl("https://drive.google.com/file/d/1AbCdEfGhIjKlMnOp/view?usp=sharing"),
    "https://drive.usercontent.google.com/download?id=1AbCdEfGhIjKlMnOp&export=download&confirm=t"
  );
  assert.equal(
    normalizeDirectApkUrl("https://www.dropbox.com/s/abc123/vidvora.apk?dl=0"),
    "https://www.dropbox.com/s/abc123/vidvora.apk?dl=1"
  );
  assert.equal(
    resolveGitHubReleaseApkUrl({ RENDER_GIT_REPO_URL: "https://github.com/teamaltamash/vidvora.git" }),
    "https://github.com/teamaltamash/vidvora/releases/latest/download/VidVora.apk"
  );
  const fallbackHtml = renderApkTemporarilyUnavailableHtml({ versionName: "1.8" });
  assert.match(fallbackHtml, /VidVora v1\.8/);
  assert.match(fallbackHtml, /href="\/download-apk"/);
});

test("sanitizeHttpUrl accepts http/https and rejects unsafe protocols", () => {
  assert.equal(
    sanitizeHttpUrl("https://cdn.example.com/vidvora-v2.apk"),
    "https://cdn.example.com/vidvora-v2.apk"
  );
  assert.equal(
    sanitizeHttpUrl("http://cdn.example.com/app.apk"),
    "http://cdn.example.com/app.apk"
  );
  assert.equal(sanitizeHttpUrl("javascript:alert(1)"), DEFAULT_APK_URL);
  assert.equal(sanitizeHttpUrl("data:text/html,<script>alert(1)</script>"), DEFAULT_APK_URL);
  assert.equal(sanitizeHttpUrl("not a url", ""), "");
});

test("resolveAppDownloadMetadata falls back cleanly when db or doc is missing", async () => {
  const fallbackNoDb = await resolveAppDownloadMetadata(null);
  assert.equal(fallbackNoDb.versionName, DEFAULT_APP_VERSION_NAME);
  assert.equal(fallbackNoDb.versionCode, DEFAULT_APP_VERSION_CODE);
  assert.equal(fallbackNoDb.apkUrl, DEFAULT_APK_URL);
  assert.equal(fallbackNoDb.releaseNotes, "");

  const mockMissingDb = {
    collection: () => ({
      doc: () => ({
        get: async () => ({ exists: false }),
      }),
    }),
  };
  const fallbackMissing = await resolveAppDownloadMetadata(mockMissingDb);
  assert.equal(fallbackMissing.apkUrl, DEFAULT_APK_URL);
});

test("resolveAppDownloadMetadata reads appConfig/appUpdate dynamically and guards unsafe URLs", async () => {
  const mockDb = {
    collection: (col) => ({
      doc: (id) => ({
        get: async () => {
          assert.equal(col, "appConfig");
          assert.equal(id, "appUpdate");
          return {
            exists: true,
            data: () => ({
              latestVersionName: "2.1",
              latestVersionCode: 12,
              apkUrl: "https://cdn.vidvora.app/releases/vidvora-2.1.apk",
              message: "- Faster HD startup\n- Bug fixes",
            }),
          };
        },
      }),
    }),
  };

  const meta = await resolveAppDownloadMetadata(mockDb);
  assert.equal(meta.versionName, "2.1");
  assert.equal(meta.versionCode, 12);
  assert.equal(meta.apkUrl, "https://cdn.vidvora.app/releases/vidvora-2.1.apk");
  assert.match(meta.releaseNotes, /Faster HD startup/);

  const mockUnsafeUrlDb = {
    collection: () => ({
      doc: () => ({
        get: async () => ({
          exists: true,
          data: () => ({
            latestVersionName: "2.2",
            latestVersionCode: 13,
            apkUrl: "javascript:evil()",
          }),
        }),
      }),
    }),
  };
  const safeMeta = await resolveAppDownloadMetadata(mockUnsafeUrlDb);
  assert.equal(safeMeta.versionName, "2.2");
  assert.equal(safeMeta.apkUrl, DEFAULT_APK_URL);
});

test("resolveLocalReleaseApkPath finds bundled release/vidvora.apk and isRemoteApkReachable detects AccessDenied", async () => {
  const localApk = resolveLocalReleaseApkPath();
  assert.ok(localApk, "expected release/vidvora.apk to exist");
  assert.match(localApk.filePath, /release\/vidvora\.apk$/);
  assert.ok(localApk.size > 1_000_000);

  const denied = await isRemoteApkReachable("https://denied.example.com/vidvora.apk", async () => ({
    ok: false,
    status: 403,
    headers: new Map([["content-type", "application/xml"]]),
  }));
  assert.equal(denied, false);

  const okBinary = await isRemoteApkReachable("https://ok.example.com/vidvora.apk", async () => ({
    ok: true,
    status: 200,
    headers: new Map([["content-type", "application/vnd.android.package-archive"]]),
  }));
  assert.equal(okBinary, true);
});

test("renderApkDownloadWebsiteHtml renders valid HTML, escapes XSS, and compiles inline JS", () => {
  const html = renderApkDownloadWebsiteHtml({
    baseUrl: "https://vidvora-share-bot.onrender.com/",
    versionName: '1.8"<script>alert(1)</script>',
    versionCode: 9,
    apkUrl: "https://d1mn8mrvlzisg5.cloudfront.net/vidvora.apk",
    releaseNotes: "- <b>Fast</b> startup\n- Better player",
  });

  assert.match(html, /\/vidvora-logo\.png/);
  assert.match(html, /\/vidvora-icon\.png/);
  assert.match(html, /href="\/download-apk"/);
  assert.match(html, /href="\/privacy-policy"/);
  assert.match(html, /Build 9/);
  assert.doesNotMatch(html, /<script>alert\(1\)<\/script>/);
  assert.doesNotMatch(html, /<b>Fast<\/b>/);
  assert.match(html, /&lt;b&gt;Fast&lt;\/b&gt; startup/);

  const scriptMatches = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)];
  assert.equal(scriptMatches.length, 1);
  assert.doesNotThrow(() => new Function(scriptMatches[0][1]));
});
