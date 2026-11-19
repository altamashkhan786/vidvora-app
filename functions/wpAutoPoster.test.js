const test = require("node:test");
const assert = require("node:assert/strict");
const {
  extractVideoUrlFromWpPost,
  extractThumbnailFromWpPost,
  extractTitleFromWpPost,
  parseWordPressRssFeed,
  runWordPressAutoSync,
  convertAndPublishWordPressItem,
} = require("./wpAutoPoster");

function createInMemoryDb() {
  const store = new Map();
  const getCol = (colName) => {
    if (!store.has(colName)) store.set(colName, new Map());
    return store.get(colName);
  };
  return {
    _store: store,
    collection(colName) {
      const col = getCol(colName);
      const makeQuery = (filters = [], limitNum = null) => ({
        where(field, op, value) {
          return makeQuery([...filters, { field, op, value }], limitNum);
        },
        limit(n) {
          return makeQuery(filters, n);
        },
        async get() {
          let entries = Array.from(col.entries()).filter(([, doc]) =>
            filters.every((f) => f.op === "==" && doc[f.field] === f.value)
          );
          if (limitNum != null) entries = entries.slice(0, limitNum);
          const docs = entries.map(([id, data]) => ({
            id,
            exists: true,
            data: () => ({ ...data }),
          }));
          return { empty: docs.length === 0, docs };
        },
      });
      return {
        ...makeQuery(),
        doc(docId) {
          return {
            async get() {
              const found = col.has(docId);
              return {
                id: docId,
                exists: found,
                data: () => (found ? { ...col.get(docId) } : undefined),
              };
            },
            async set(data, opts) {
              if (opts?.merge && col.has(docId)) {
                col.set(docId, { ...col.get(docId), ...data });
              } else {
                col.set(docId, { ...data });
              }
            },
          };
        },
      };
    },
  };
}

test("extractVideoUrlFromWpPost supports ACF fields, HTML <video>/<source>, shortcode, and direct links", () => {
  assert.equal(
    extractVideoUrlFromWpPost({ acf: { video_url: "https://cdn.example.com/movies/ep1.mp4" } }),
    "https://cdn.example.com/movies/ep1.mp4"
  );

  assert.equal(
    extractVideoUrlFromWpPost({
      content: { rendered: '<p>Watch:</p><video poster="/p.jpg"><source src="https://cdn.site.com/live/master.m3u8" type="application/x-mpegURL"></video>' },
    }),
    "https://cdn.site.com/live/master.m3u8"
  );

  assert.equal(
    extractVideoUrlFromWpPost({
      content: { rendered: '[video mp4="https://files.mywp.com/clip_02.mp4"]' },
    }),
    "https://files.mywp.com/clip_02.mp4"
  );

  // Ignores unsupported third-party hosts like terabox and plain images
  assert.equal(
    extractVideoUrlFromWpPost({
      content: {
        rendered: '<img src="https://site.com/banner.jpg"><a href="https://terabox.com/s/12345">Link</a>',
      },
    }),
    ""
  );
});

test("extractThumbnailFromWpPost and extractTitleFromWpPost decode WordPress metadata cleanly", () => {
  const post = {
    title: { rendered: "South Action &#8211; Full HD &amp; Dubbed" },
    _embedded: {
      "wp:featuredmedia": [{ source_url: "https://mywp.com/wp-content/uploads/poster.jpg" }],
    },
  };
  assert.equal(extractTitleFromWpPost(post), "South Action – Full HD & Dubbed");
  assert.equal(extractThumbnailFromWpPost(post), "https://mywp.com/wp-content/uploads/poster.jpg");
});

test("parseWordPressRssFeed extracts posts, enclosure video links, and media thumbnails", () => {
  const rss = `<?xml version="1.0" encoding="UTF-8"?>
  <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/" xmlns:media="http://search.yahoo.com/mrss/">
    <channel>
      <item>
        <title><![CDATA[Episode 10 &amp; Finale]]></title>
        <link>https://mywp.com/episode-10/</link>
        <guid>https://mywp.com/?p=404</guid>
        <enclosure url="https://cdn.mywp.com/ep10.mp4" type="video/mp4" />
        <media:thumbnail url="https://cdn.mywp.com/ep10.jpg" />
      </item>
    </channel>
  </rss>`;
  const items = parseWordPressRssFeed(rss, "https://mywp.com");
  assert.equal(items.length, 1);
  assert.equal(extractTitleFromWpPost(items[0]), "Episode 10 & Finale");
  assert.equal(extractVideoUrlFromWpPost(items[0]), "https://cdn.mywp.com/ep10.mp4");
  assert.equal(extractThumbnailFromWpPost(items[0]), "https://cdn.mywp.com/ep10.jpg");
});

test("runWordPressAutoSync converts posts to VidVora links, posts to Telegram channel, and prevents duplicates", async () => {
  const db = createInMemoryDb();
  const telegramCalls = [];

  const mockFetch = async () => ({
    ok: true,
    status: 200,
    async json() {
      return [
        {
          id: 202,
          link: "https://mywp.com/post-202/",
          title: { rendered: "Second Video" },
          content: { rendered: '<a href="https://cdn.mywp.com/v2.mp4">Download</a>' },
          _embedded: { "wp:featuredmedia": [{ source_url: "https://cdn.mywp.com/v2.jpg" }] },
        },
        {
          id: 201,
          link: "https://mywp.com/post-201/",
          title: { rendered: "First Video" },
          content: { rendered: '<video src="https://cdn.mywp.com/v1.mp4"></video>' },
        },
        {
          id: 200,
          link: "https://mywp.com/text-only/",
          title: { rendered: "Text Only Post" },
          content: { rendered: "<p>Hello world no video here</p>" },
        },
      ];
    },
  });

  const mockResolveBotCfg = async () => ({
    botToken: "123456:FAKE_TOKEN",
    apiBase: "https://api.telegram.org",
    shareLinkBaseUrl: "https://vidvora-share-bot.onrender.com/share?link=",
  });

  const mockTelegramApi = async (apiBase, token, method, payload) => {
    telegramCalls.push({ method, payload });
    return { ok: true, result: { message_id: telegramCalls.length + 100 } };
  };

  const configOverride = {
    enabled: true,
    siteUrl: "https://mywp.com",
    channels: ["@MyVidVoraChannel"],
    maxPostsPerRun: 5,
    uploaderMemberId: "admin",
    uploaderName: "VidVora Admin",
    categoryId: "",
  };

  // Run 1: publishes both video posts in chronological order (201 then 202), skips text-only 200
  const run1 = await runWordPressAutoSync({
    db,
    publicBaseUrl: "https://vidvora-share-bot.onrender.com",
    configOverride,
    fetchImpl: mockFetch,
    resolveBotConfigFn: mockResolveBotCfg,
    telegramApiFn: mockTelegramApi,
  });

  assert.equal(run1.ok, true);
  assert.equal(run1.postedCount, 2);
  assert.equal(run1.skippedNoVideoCount, 1);
  assert.equal(run1.duplicateCount, 0);
  assert.equal(run1.postedItems[0].title, "First Video");
  assert.equal(run1.postedItems[1].title, "Second Video");
  assert.equal(telegramCalls.length, 2);
  // First post had no thumbnail -> sendMessage; Second had featured thumbnail -> sendPhoto
  assert.equal(telegramCalls[0].method, "sendMessage");
  assert.equal(telegramCalls[1].method, "sendPhoto");
  assert.equal(telegramCalls[1].payload.chat_id, "@MyVidVoraChannel");
  assert.equal(db._store.get("videos").size, 2);
  assert.equal(db._store.get("accessLinks").size, 2);
  assert.equal(db._store.get("wpAutoPosted").size, 2);

  // Run 2: exact same WP posts must NOT be re-posted (0 new posts, 2 duplicates)
  const run2 = await runWordPressAutoSync({
    db,
    publicBaseUrl: "https://vidvora-share-bot.onrender.com",
    configOverride,
    fetchImpl: mockFetch,
    resolveBotConfigFn: mockResolveBotCfg,
    telegramApiFn: mockTelegramApi,
  });

  assert.equal(run2.postedCount, 0);
  assert.equal(run2.duplicateCount, 2);
  assert.equal(telegramCalls.length, 2);
});

test("convertAndPublishWordPressItem falls back to sendMessage when sendPhoto fails", async () => {
  const db = createInMemoryDb();
  const methodsCalled = [];
  const res = await convertAndPublishWordPressItem({
    db,
    item: {
      id: 999,
      title: "Instant Webhook Video",
      videoUrl: "https://cdn.mywp.com/instant.mp4",
      thumbnailUrl: "https://cdn.mywp.com/blocked.jpg",
    },
    siteUrl: "https://mywp.com",
    channels: ["-1001234567890"],
    publicBaseUrl: "https://vidvora-share-bot.onrender.com",
    resolveBotConfigFn: async () => ({
      botToken: "123456:FAKE_TOKEN",
      apiBase: "https://api.telegram.org",
      shareLinkBaseUrl: "https://vidvora-share-bot.onrender.com/share?link=",
    }),
    telegramApiFn: async (_, __, method) => {
      methodsCalled.push(method);
      if (method === "sendPhoto") {
        throw new Error("WEBPAGE_MEDIA_EMPTY");
      }
      return { ok: true, result: { message_id: 555 } };
    },
  });

  assert.equal(res.status, "posted");
  assert.deepEqual(methodsCalled, ["sendPhoto", "sendMessage"]);
  assert.equal(res.channelResults[0].mode, "message");
});
