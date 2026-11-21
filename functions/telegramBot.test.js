const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { execFile } = require("child_process");
const { promisify } = require("util");

const execFileAsync = promisify(execFile);

const {
  SHARE_LINK_BASE_URL,
  hashToken,
  generateAccessToken,
  signStreamTarget,
  verifyStreamTarget,
  parseLoginCommand,
  parseTitleAndDescription,
  extractDirectVideoUrlMessage,
  isAllowedTelegramVideoMessage,
  buildShareMessageCaption,
  buildSharePlainFallbackText,
  CAPTION_BLOCK_NONE,
  parseTelegramFloodWaitSeconds,
  streamTelegramMtprotoWithRange,
  createFiveSecondPreviewAndThumbnail,
} = require("./telegramBot");

test("parseLoginCommand parses passkey-only and email+passkey commands", () => {
  assert.deepEqual(parseLoginCommand("/login GM-CREATOR-99"), {
    emailOrPhone: null,
    passkey: "GM-CREATOR-99",
  });
  assert.deepEqual(parseLoginCommand("/login@VidVoraUploadBot rohan@vidvora.com SECRET123"), {
    emailOrPhone: "rohan@vidvora.com",
    passkey: "SECRET123",
  });
  assert.equal(parseLoginCommand("hello world"), null);
});

test("parseTitleAndDescription and extractDirectVideoUrlMessage handle captions and links", () => {
  assert.deepEqual(parseTitleAndDescription("Pushpa 2 Full\n1080p Hindi Dubbed"), {
    title: "Pushpa 2 Full",
    description: "1080p Hindi Dubbed",
  });
  assert.deepEqual(parseTitleAndDescription("", "south_action_hd.mp4"), {
    title: "south action hd",
    description: "Uploaded via VidVora Telegram Creator Bot",
  });

  const urlParsed = extractDirectVideoUrlMessage("My Movie HD\nhttps://cdn.example.com/movie.mp4");
  assert.equal(urlParsed.url, "https://cdn.example.com/movie.mp4");
  assert.equal(urlParsed.caption, "My Movie HD");
});

test("isAllowedTelegramVideoMessage detects video and video documents only", () => {
  assert.equal(isAllowedTelegramVideoMessage({ video: { file_id: "vid123" } }), true);
  assert.equal(
    isAllowedTelegramVideoMessage({ document: { file_id: "doc1", mime_type: "video/mp4" } }),
    true
  );
  assert.equal(
    isAllowedTelegramVideoMessage({ document: { file_id: "doc2", file_name: "clip.mkv" } }),
    true
  );
  assert.equal(
    isAllowedTelegramVideoMessage({ document: { file_id: "doc3", mime_type: "application/pdf" } }),
    false
  );
});

test("token generation, stream HMAC signing, and share caption match VidVora format", () => {
  const token = generateAccessToken();
  assert.match(token, /^GM-[A-Z0-9]{8}$/);
  assert.equal(hashToken(token).length, 64);

  const secret = "test-secret";
  const sig = signStreamTarget("vid_123", "video", secret);
  assert.equal(verifyStreamTarget("vid_123", "video", sig, secret), true);
  assert.equal(verifyStreamTarget("vid_123", "thumb", sig, secret), false);
  assert.equal(verifyStreamTarget("vid_999", "video", sig, secret), false);

  const caption = buildShareMessageCaption({
    title: "1794916938185-2023-12-17-14-48-00",
    uploaderName: "Rohan",
    rawToken: token,
  });
  assert.ok(!caption.includes("1794916938185"));
  assert.ok(caption.includes("VidVora — Watch Video"));
  assert.ok(caption.includes("Watch Full Video:"));
  assert.ok(caption.includes(`${SHARE_LINK_BASE_URL}${token}`));
  assert.ok(caption.includes("वीडियो कैसे देखे / How to play video"));
  assert.ok(caption.includes("👇👇👇👇"));
  assert.ok(caption.includes("https://t.me/VideoKaiseDekhe_ar"));
  assert.ok(!caption.includes("Uploaded to VidVora"));
  assert.ok(!caption.includes("Preview Clip"));
  assert.ok(!caption.includes("Access Code"));
  assert.ok(!caption.includes("App Link"));
  assert.ok(!caption.includes("Forward this preview"));

  const customCaption = buildShareMessageCaption({
    title: "My Custom Post",
    rawToken: token,
    customHeader: "🔥 VIP Channel: https://t.me/MyVipHub",
    customFooter: "📢 Join Backup: https://t.me/MyBackupLink",
  });
  assert.ok(customCaption.includes("VIP Channel: https://t.me/MyVipHub"));
  assert.ok(customCaption.includes("Join Backup: https://t.me/MyBackupLink"));
  assert.ok(!customCaption.includes("VidVora — Watch Video"));
  assert.ok(!customCaption.includes("VideoKaiseDekhe"));

  const removedFooterCaption = buildShareMessageCaption({
    rawToken: token,
    customHeader: CAPTION_BLOCK_NONE,
    customFooter: CAPTION_BLOCK_NONE,
  });
  assert.ok(!removedFooterCaption.includes("VidVora — Watch Video"));
  assert.ok(!removedFooterCaption.includes("VideoKaiseDekhe"));
  assert.ok(!removedFooterCaption.includes("How to play video"));
  assert.ok(removedFooterCaption.includes(`${SHARE_LINK_BASE_URL}${token}`));

  const plainFallback = buildSharePlainFallbackText({
    rawToken: token,
    customHeader: "🔥 VIP Channel",
    customFooter: "📢 Join https://t.me/MyBackupLink",
  });
  assert.ok(plainFallback.includes("🔥 VIP Channel"));
  assert.ok(plainFallback.includes("📢 Join https://t.me/MyBackupLink"));
  assert.ok(!plainFallback.includes("VideoKaiseDekhe"));

  const defaultPlainFallback = buildSharePlainFallbackText({ rawToken: token });
  assert.ok(defaultPlainFallback.includes("वीडियो कैसे देखे / How to play video"));
  assert.ok(defaultPlainFallback.includes("https://t.me/VideoKaiseDekhe_ar"));
});

test("createFiveSecondPreviewAndThumbnail cuts a real 10s sample video down to <= 5s clip + jpg thumbnail", async () => {
  const tmpDir = await fs.promises.mkdtemp(path.join(os.tmpdir(), "vidvora-ffmpeg-test-"));
  try {
    const sampleInput = path.join(tmpDir, "sample_10s.mp4");
    const outClip = path.join(tmpDir, "preview_5s.mp4");
    const outThumb = path.join(tmpDir, "thumb.jpg");

    // Generate a real 10-second synthetic MP4 video using system ffmpeg
    await execFileAsync("ffmpeg", [
      "-y",
      "-f",
      "lavfi",
      "-i",
      "testsrc=duration=10:size=320x240:rate=15",
      "-c:v",
      "libx264",
      "-pix_fmt",
      "yuv420p",
      sampleInput,
    ]);

    const result = await createFiveSecondPreviewAndThumbnail({
      inputPathOrUrl: sampleInput,
      outputClipPath: outClip,
      outputThumbPath: outThumb,
      durationSeconds: 10,
      previewSeconds: 5,
    });

    assert.equal(result.clipExists, true);
    assert.equal(result.thumbExists, true);

    const { stdout } = await execFileAsync("ffprobe", [
      "-v",
      "error",
      "-show_entries",
      "format=duration",
      "-of",
      "default=noprint_wrappers=1:nokey=1",
      outClip,
    ]);
    const clipDuration = parseFloat(stdout.trim());
    assert.ok(clipDuration > 4.0 && clipDuration <= 5.5, `Expected ~5s clip, got ${clipDuration}s`);
  } finally {
    await fs.promises.rm(tmpDir, { recursive: true, force: true });
  }
});

test("handleStreamPublicVideo enforces HMAC signature and active Firestore video status", async () => {
  const { handleStreamPublicVideo } = require("./telegramBot");
  const createMockRes = () => {
    const out = { statusCode: 0, headers: {}, body: "" };
    return {
      writeHead(code, headers) {
        out.statusCode = code;
        out.headers = headers || {};
      },
      end(chunk) {
        if (chunk) out.body += String(chunk);
      },
      out,
    };
  };

  const fakeDb = {
    collection: () => ({
      document: () => ({
        get: async () => ({ exists: true, data: () => ({ isActive: false }) }),
      }),
    }),
  };

  const badSigRes = createMockRes();
  await handleStreamPublicVideo({
    videoId: "vid_test_1",
    kind: "video",
    sig: "invalid-sig",
    rangeHeader: null,
    res: badSigRes,
    db: fakeDb,
    storage: null,
    playbackSecret: "my-secret",
  });
  assert.equal(badSigRes.out.statusCode, 403);

  const validSig = signStreamTarget("vid_test_1", "video", "my-secret");
  const inactiveRes = createMockRes();
  await handleStreamPublicVideo({
    videoId: "vid_test_1",
    kind: "video",
    sig: validSig,
    rangeHeader: null,
    res: inactiveRes,
    db: fakeDb,
    storage: null,
    playbackSecret: "my-secret",
  });
  assert.equal(inactiveRes.out.statusCode, 403);
});

test("handleSharePageRequest and handleShareThumbnailRequest render crisp HD Open Graph previews without blur", async () => {
  const {
    normalizeShareBaseUrl,
    decodeDataImageUri,
    getImageDimensions,
    handleSharePageRequest,
    handleShareThumbnailRequest,
  } = require("./sharePreview");

  assert.equal(
    normalizeShareBaseUrl("https://vidvora-bot.onrender.com"),
    "https://vidvora-bot.onrender.com/share?link="
  );
  assert.equal(
    normalizeShareBaseUrl("https://vidvora-bot.onrender.com/share?link="),
    "https://vidvora-bot.onrender.com/share?link="
  );

  // Generate a real small 320x180 JPEG sample (simulating legacy/low-res thumbnail)
  const tmpDir = await fs.promises.mkdtemp(path.join(os.tmpdir(), "vidvora-thumb-hd-test-"));
  let jpegBytes;
  try {
    const smallJpg = path.join(tmpDir, "small.jpg");
    await execFileAsync(
      "ffmpeg",
      ["-y", "-f", "lavfi", "-i", "testsrc=size=320x180:rate=1", "-vframes", "1", smallJpg],
      { timeout: 15_000 }
    );
    jpegBytes = await fs.promises.readFile(smallJpg);
  } finally {
    await fs.promises.rm(tmpDir, { recursive: true, force: true });
  }

  const dataUri = `data:image/jpeg;base64,${jpegBytes.toString("base64")}`;
  const decoded = decodeDataImageUri(dataUri);
  assert.equal(decoded.contentType, "image/jpeg");
  assert.equal(decoded.buffer.length, jpegBytes.length);
  const srcDims = getImageDimensions(decoded.buffer, "image/jpeg");
  assert.equal(srcDims.width, 320);
  assert.equal(srcDims.height, 180);

  const expectedHash = hashToken("GM-DEMO-11");
  const mockDb = {
    collection: (name) => {
      if (name === "accessLinks") {
        return {
          where: (_field, _op, val) => ({
            limit: () => ({
              get: async () =>
                val === expectedHash
                  ? {
                      empty: false,
                      docs: [
                        {
                          id: "link_demo_1",
                          exists: true,
                          data: () => ({ linkId: "link_demo_1", videoId: "vid_demo_1", isActive: true }),
                        },
                      ],
                    }
                  : { empty: true, docs: [] },
            }),
          }),
          doc: () => ({ get: async () => ({ exists: false }) }),
        };
      }
      if (name === "videos") {
        return {
          doc: (id) => ({
            get: async () =>
              id === "vid_demo_1"
                ? {
                    id: "vid_demo_1",
                    exists: true,
                    data: () => ({
                      videoId: "vid_demo_1",
                      title: 'Blockbuster "Action" Clip',
                      description: "Watch full HD stream in VidVora",
                      thumbnailPath: dataUri,
                      isActive: true,
                    }),
                  }
                : { exists: false },
          }),
        };
      }
      return { doc: () => ({ get: async () => ({ exists: false }) }) };
    },
  };

  const makeMockRes = () => {
    const out = { statusCode: 0, headers: {}, chunks: [] };
    return {
      writeHead(code, headers) {
        out.statusCode = code;
        out.headers = headers || {};
      },
      end(chunk) {
        if (chunk) out.chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(String(chunk)));
      },
      out,
      text() {
        return Buffer.concat(out.chunks).toString("utf8");
      },
      buffer() {
        return Buffer.concat(out.chunks);
      },
    };
  };

  const htmlRes = makeMockRes();
  await handleSharePageRequest({
    reqUrl: "/share?link=GM-DEMO-11",
    hostBaseUrl: "https://vidvora-bot.onrender.com",
    res: htmlRes,
    db: mockDb,
  });
  assert.equal(htmlRes.out.statusCode, 200);
  const html = htmlRes.text();
  assert.ok(html.includes('property="og:title" content="VidVora — Watch Video"'));
  assert.ok(!html.includes("Blockbuster"));
  assert.match(html, /property="og:image" content="https:\/\/vidvora-bot\.onrender\.com\/shareThumb\?videoId=vid_demo_1&amp;v=[a-f0-9]{8}"/);
  assert.ok(html.includes('property="og:image:width" content="1280"'));
  assert.ok(html.includes('property="og:image:height" content="720"'));
  assert.ok(html.includes('name="twitter:card" content="summary_large_image"'));
  assert.ok(html.includes("intent://watch?link=GM-DEMO-11#Intent;scheme=vidvora;package=com.aistudio.vidvora.vstr;"));

  const thumbRes = makeMockRes();
  await handleShareThumbnailRequest({
    videoId: "vid_demo_1",
    res: thumbRes,
    db: mockDb,
    storage: null,
  });
  assert.equal(thumbRes.out.statusCode, 200);
  assert.equal(thumbRes.out.headers["Content-Type"], "image/jpeg");
  const servedDims = getImageDimensions(thumbRes.buffer(), "image/jpeg");
  assert.equal(servedDims.width, 1280);
  assert.equal(servedDims.height, 720);

  const headThumbRes = makeMockRes();
  await handleShareThumbnailRequest({
    videoId: "vid_demo_1",
    method: "HEAD",
    res: headThumbRes,
    db: mockDb,
    storage: null,
  });
  assert.equal(headThumbRes.out.statusCode, 200);
  assert.equal(headThumbRes.out.headers["Content-Type"], "image/jpeg");
  assert.ok(Number(headThumbRes.out.headers["Content-Length"]) > 1000);
  assert.equal(headThumbRes.buffer().length, 0);

  const fallbackRes = makeMockRes();
  await handleShareThumbnailRequest({
    videoId: "vid_missing",
    res: fallbackRes,
    db: mockDb,
    storage: null,
  });
  assert.equal(fallbackRes.out.statusCode, 200);
  assert.equal(fallbackRes.out.headers["Content-Type"], "image/png");
  assert.ok(fallbackRes.buffer().length > 1000);
});

test("authenticateCreatorMember verifies custom adminSecurity.adminMasterKeyHash and default key fallback", async () => {
  const crypto = require("crypto");
  const { authenticateCreatorMember } = require("./telegramBot");

  const makeDb = (customHash = "") => ({
    collection: (name) => {
      if (name === "appConfig") {
        return {
          doc: (docId) => ({
            get: async () =>
              docId === "adminSecurity" && customHash
                ? { exists: true, data: () => ({ adminMasterKeyHash: customHash }) }
                : { exists: false, data: () => ({}) },
          }),
        };
      }
      if (name === "members") {
        return {
          where: () => ({
            limit: () => ({
              get: async () => ({ empty: true, docs: [] }),
            }),
          }),
        };
      }
      return { doc: () => ({ get: async () => ({ exists: false }) }) };
    },
  });

  // Uncustomized -> default 7827128212 accepted
  const defaultDb = makeDb("");
  const defaultAdmin = await authenticateCreatorMember(defaultDb, null, "7827128212");
  assert.equal(defaultAdmin?.memberId, "admin");
  assert.equal(await authenticateCreatorMember(defaultDb, null, "WrongAdminKey"), null);

  // Customized -> old 7827128212 rejected, new custom key accepted
  const customHash = crypto.createHash("sha256").update("NewAdmin#99", "utf8").digest("hex");
  const customDb = makeDb(customHash);
  assert.equal(await authenticateCreatorMember(customDb, null, "7827128212"), null);
  const customAdmin = await authenticateCreatorMember(customDb, "teamaltamash@gmail.com", "NewAdmin#99");
  assert.equal(customAdmin?.memberId, "admin");
  assert.equal(customAdmin?.role, "admin");
});

test("extractVidVoraShareTarget detects share links, CloudFront links, deep links, /convert, and raw tokens", () => {
  const { extractVidVoraShareTarget } = require("./telegramBot");

  const shareUrl = extractVidVoraShareTarget("https://vidvora-share-bot.onrender.com/share?link=GM-AAA123");
  assert.deepEqual(shareUrl, { code: "GM-AAA123", customTitle: "" });

  const cfUrl = extractVidVoraShareTarget("https://d1mn8mrvlzisg5.cloudfront.net/index.html?link=GM-CF999\nMera Naya Title");
  assert.deepEqual(cfUrl, { code: "GM-CF999", customTitle: "Mera Naya Title" });

  const deepLink = extractVidVoraShareTarget("vidvora://watch?link=GM-APP77");
  assert.deepEqual(deepLink, { code: "GM-APP77", customTitle: "" });

  const convertCmd = extractVidVoraShareTarget("/convert GM-CMD55 Custom Title");
  assert.deepEqual(convertCmd, { code: "GM-CMD55", customTitle: "Custom Title" });

  const rawCode = extractVidVoraShareTarget("GM-RAW8888");
  assert.deepEqual(rawCode, { code: "GM-RAW8888", customTitle: "" });

  const forwardedBtn = extractVidVoraShareTarget("Forwarded post", {
    reply_markup: {
      inline_keyboard: [[{ text: "Watch", url: "https://vidvora-share-bot.onrender.com/share?link=GM-BTN11" }]],
    },
  });
  assert.deepEqual(forwardedBtn, { code: "GM-BTN11", customTitle: "Forwarded post" });

  const forwardedFooterPost = extractVidVoraShareTarget(
    [
      "🎬 *VidVora — Watch Video*",
      "",
      "🔗 *Watch Full Video:*",
      "https://vidvora-share-bot.onrender.com/share?link=GM-FWD99",
      "",
      "वीडियो कैसे देखे / How to play video",
      "👇👇👇👇",
      "https://t.me/VideoKaiseDekhe_ar",
    ].join("\n")
  );
  assert.deepEqual(forwardedFooterPost, { code: "GM-FWD99", customTitle: "" });

  // Normal .mp4 URL must NOT be treated as a VidVora share link
  assert.equal(extractVidVoraShareTarget("https://cdn.example.com/movies/action.mp4\nAction Film"), null);
});

test("handleTelegramUpdate converts Creator A's VidVora link into Creator B's own tracked link", async () => {
  const { handleTelegramUpdate, hashToken } = require("./telegramBot");

  const sourceToken = "GM-CREATORA1";
  const sourceTokenHash = hashToken(sourceToken);

  const videosStore = new Map([
    [
      "vid_creator_a",
      {
        videoId: "vid_creator_a",
        title: "Creator A Original Reel",
        description: "Original description",
        storagePath: "http://127.0.0.1:9/streamPublicVideo?videoId=vid_creator_a&sig=abc",
        thumbnailPath: "https://cdn.example.com/thumb_a.jpg",
        tgFileId: "tg_file_a_123",
        isActive: true,
        totalViews: 42,
        uploadedBy: "creator_a",
        uploaderName: "Creator A",
      },
    ],
  ]);

  const accessLinksStore = new Map([
    [
      "link_creator_a",
      {
        linkId: "link_creator_a",
        videoId: "vid_creator_a",
        tokenHash: sourceTokenHash,
        isActive: true,
        expiresAt: null,
      },
    ],
  ]);

  const sessionsStore = new Map([
    [
      "tg_user_b",
      {
        telegramUserId: "tg_user_b",
        chatId: "999001",
        memberId: "creator_b",
        memberName: "Creator B",
        role: "member",
        uploadCount: 2,
      },
    ],
  ]);

  const mockDb = {
    collection: (name) => {
      if (name === "appConfig") {
        return {
          doc: (id) => ({
            get: async () =>
              id === "telegramBot"
                ? { exists: true, data: () => ({ botToken: "123:TEST_BOT_TOKEN" }) }
                : { exists: false, data: () => ({}) },
          }),
        };
      }
      if (name === "members") {
        return {
          doc: (id) => ({
            get: async () =>
              id === "creator_b"
                ? {
                    exists: true,
                    data: () => ({
                      memberId: "creator_b",
                      name: "Creator B",
                      isActive: true,
                      allowedOptions: ["UPLOAD_VIDEOS"],
                    }),
                  }
                : { exists: false },
          }),
        };
      }
      if (name === "telegramBotSessions") {
        return {
          doc: (id) => ({
            get: async () => {
              const row = sessionsStore.get(id);
              return row ? { exists: true, data: () => row } : { exists: false };
            },
            set: async (payload, opts) => {
              const prev = opts?.merge ? sessionsStore.get(id) || {} : {};
              sessionsStore.set(id, { ...prev, ...payload });
            },
          }),
        };
      }
      if (name === "accessLinks") {
        return {
          where: (field, _op, val) => ({
            limit: () => ({
              get: async () => {
                const matches = [];
                for (const [id, row] of accessLinksStore.entries()) {
                  if (row[field] === val) matches.push({ id, exists: true, data: () => row });
                }
                return { empty: matches.length === 0, docs: matches };
              },
            }),
          }),
          doc: (id) => ({
            get: async () => {
              const row = accessLinksStore.get(id);
              return row ? { id, exists: true, data: () => row } : { exists: false };
            },
            set: async (payload) => {
              accessLinksStore.set(id, payload);
            },
          }),
        };
      }
      if (name === "videos") {
        return {
          doc: (id) => ({
            get: async () => {
              const row = videosStore.get(id);
              return row ? { id, exists: true, data: () => row } : { exists: false };
            },
            set: async (payload) => {
              videosStore.set(id, payload);
            },
          }),
        };
      }
      return { doc: () => ({ get: async () => ({ exists: false }) }) };
    },
  };

  const sentTelegramCalls = [];
  const origFetch = global.fetch;
  global.fetch = async (url, init = {}) => {
    const method = String(url).split("/").pop();
    const parsedBody = typeof init.body === "string" ? JSON.parse(init.body) : {};
    sentTelegramCalls.push({ method, body: parsedBody });
    return {
      ok: true,
      json: async () => ({ ok: true, result: { message_id: 777 } }),
    };
  };

  try {
    const result = await handleTelegramUpdate({
      update: {
        message: {
          message_id: 55,
          chat: { id: 999001 },
          from: { id: "tg_user_b" },
          text: `https://vidvora-share-bot.onrender.com/share?link=${sourceToken}`,
        },
      },
      db: mockDb,
      storage: null,
      functionBaseUrl: "https://vidvora-share-bot.onrender.com",
      playbackSecret: "test-secret",
    });

    assert.equal(result.ok, true);
    assert.ok(result.rawToken.startsWith("GM-"));
    assert.notEqual(result.rawToken, sourceToken);

    const newVideo = videosStore.get(result.videoId);
    assert.ok(newVideo);
    assert.equal(newVideo.uploadedBy, "creator_b");
    assert.equal(newVideo.uploaderName, "Creator B");
    assert.equal(newVideo.totalViews, 0);
    assert.equal(newVideo.storagePath, "http://127.0.0.1:9/streamPublicVideo?videoId=vid_creator_a&sig=abc");
    assert.equal(newVideo.thumbnailPath, "https://cdn.example.com/thumb_a.jpg");
    assert.equal(newVideo.tgFileId, "tg_file_a_123");

    // Source Creator A video remains completely untouched
    assert.equal(videosStore.get("vid_creator_a").uploadedBy, "creator_a");
    assert.equal(videosStore.get("vid_creator_a").totalViews, 42);

    // Creator B's session uploadCount incremented
    assert.equal(sessionsStore.get("tg_user_b").uploadCount, 3);

    // Bot replied with thumbnail photo + Creator B's new watch link button
    const photoCall = sentTelegramCalls.find((c) => c.method === "sendPhoto");
    assert.ok(photoCall);
    assert.equal(photoCall.body.photo, "https://cdn.example.com/thumb_a.jpg");
    assert.ok(photoCall.body.caption.includes(result.rawToken));
    assert.ok(photoCall.body.reply_markup.inline_keyboard[0][0].url.includes(result.rawToken));
  } finally {
    global.fetch = origFetch;
  }
});

test("handleStreamPublicVideo caches Telegram tgFileId and serves HTTP 206 byte ranges with video/mp4", async () => {
  const { PassThrough } = require("stream");
  const { handleStreamPublicVideo, signStreamTarget } = require("./telegramBot");

  const fakeVideoId = `vid_range_${Date.now()}`;
  const fakePayload = Buffer.from("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ");
  const secret = "test-secret";
  const sig = signStreamTarget(fakeVideoId, "video", secret);

  const mockDb = {
    collection: (name) => {
      if (name === "appConfig") {
        return {
          doc: () => ({
            get: async () => ({ exists: true, data: () => ({ botToken: "123:TESTTOKEN" }) }),
          }),
        };
      }
      if (name === "videos") {
        return {
          doc: (id) => ({
            get: async () =>
              id === fakeVideoId
                ? {
                    exists: true,
                    data: () => ({
                      videoId: fakeVideoId,
                      isActive: true,
                      tgFileId: "tg_file_range_test",
                    }),
                  }
                : { exists: false },
          }),
        };
      }
      return { doc: () => ({ get: async () => ({ exists: false }) }) };
    },
  };

  let downloadCalls = 0;
  const origFetch = global.fetch;
  global.fetch = async (url) => {
    if (String(url).includes("/getFile")) {
      return {
        ok: true,
        status: 200,
        json: async () => ({ ok: true, result: { file_path: "videos/file_0.mp4" } }),
      };
    }
    if (String(url).includes("/file/bot")) {
      downloadCalls += 1;
      return {
        ok: true,
        status: 200,
        headers: new Map([["content-type", "application/octet-stream"]]),
        arrayBuffer: async () => fakePayload,
      };
    }
    throw new Error(`Unexpected fetch URL: ${url}`);
  };

  const makeMockStreamRes = () => {
    const stream = new PassThrough();
    const chunks = [];
    let statusCode = 0;
    let headers = {};
    const done = new Promise((resolve) => {
      stream.on("data", (c) => chunks.push(c));
      stream.on("end", () => resolve({ statusCode, headers, body: Buffer.concat(chunks) }));
      stream.on("finish", () => {
        if (!stream.readableEnded) stream.end();
      });
    });
    stream.writeHead = (code, hdrs) => {
      statusCode = code;
      headers = hdrs || {};
    };
    const origEnd = stream.end.bind(stream);
    stream.end = (chunk, ...rest) => {
      if (chunk) chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
      return origEnd(...rest);
    };
    return { res: stream, done };
  };

  try {
    // 1. Byte range request (tail moov-atom probe)
    const rangeReq = makeMockStreamRes();
    await handleStreamPublicVideo({
      videoId: fakeVideoId,
      kind: "video",
      sig,
      method: "GET",
      rangeHeader: "bytes=10-19",
      res: rangeReq.res,
      db: mockDb,
      storage: null,
      playbackSecret: secret,
    });
    const r1 = await rangeReq.done;
    assert.equal(r1.statusCode, 206);
    assert.equal(r1.headers["Content-Type"], "video/mp4");
    assert.equal(r1.headers["Accept-Ranges"], "bytes");
    assert.equal(r1.headers["Content-Range"], `bytes 10-19/${fakePayload.length}`);
    assert.equal(r1.headers["Content-Length"], 10);
    assert.equal(r1.body.toString(), "ABCDEFGHIJ");

    // 2. Second range request hits cache without re-fetching from Telegram
    const rangeReq2 = makeMockStreamRes();
    await handleStreamPublicVideo({
      videoId: fakeVideoId,
      kind: "video",
      sig,
      method: "GET",
      rangeHeader: "bytes=0-3",
      res: rangeReq2.res,
      db: mockDb,
      storage: null,
      playbackSecret: secret,
    });
    const r2 = await rangeReq2.done;
    assert.equal(r2.statusCode, 206);
    assert.equal(r2.body.toString(), "0123");
    assert.equal(downloadCalls, 1);

    // 3. HEAD request returns headers with empty body
    const headReq = makeMockStreamRes();
    await handleStreamPublicVideo({
      videoId: fakeVideoId,
      kind: "video",
      sig,
      method: "HEAD",
      rangeHeader: null,
      res: headReq.res,
      db: mockDb,
      storage: null,
      playbackSecret: secret,
    });
    const r3 = await headReq.done;
    assert.equal(r3.statusCode, 200);
    assert.equal(r3.headers["Content-Type"], "video/mp4");
    assert.equal(r3.headers["Content-Length"], fakePayload.length);
    assert.equal(r3.body.length, 0);
  } finally {
    global.fetch = origFetch;
    await fs.promises.unlink(path.join(os.tmpdir(), "vidvora-stream-cache", `${fakeVideoId}.mp4`)).catch(() => {});
  }
});

test("handleTelegramUpdate allows direct Master Key login and switching accounts without /login", async () => {
  const { handleTelegramUpdate } = require("./telegramBot");

  const sessions = new Map([
    ["tg_switch_user", { telegramUserId: "tg_switch_user", memberId: "creator_old", memberName: "Old Creator" }],
  ]);

  const mockDb = {
    collection: (name) => {
      if (name === "appConfig") {
        return {
          doc: () => ({
            get: async () => ({ exists: true, data: () => ({ botToken: "123:TEST" }) }),
          }),
        };
      }
      if (name === "members") {
        return {
          where: (_f, _op, val) => ({
            limit: () => ({
              get: async () =>
                val === "CREATOR-777"
                  ? {
                      empty: false,
                      docs: [
                        {
                          id: "creator_new",
                          data: () => ({
                            memberId: "creator_new",
                            name: "Altamash New",
                            passkey: "CREATOR-777",
                            isActive: true,
                            allowedOptions: ["UPLOAD_VIDEOS"],
                          }),
                        },
                      ],
                    }
                  : { empty: true, docs: [] },
            }),
          }),
        };
      }
      if (name === "telegramBotSessions") {
        return {
          doc: (id) => ({
            get: async () =>
              sessions.has(id)
                ? { exists: true, data: () => sessions.get(id) }
                : { exists: false },
            set: async (payload) => {
              sessions.set(id, payload);
            },
          }),
        };
      }
      return { doc: () => ({ get: async () => ({ exists: false }) }) };
    },
  };

  const sent = [];
  const origFetch = global.fetch;
  global.fetch = async (url, opts) => {
    sent.push({ url, body: JSON.parse(opts.body) });
    return { ok: true, json: async () => ({ ok: true, result: { message_id: 1 } }) };
  };

  try {
    const res = await handleTelegramUpdate({
      update: {
        message: {
          message_id: 99,
          chat: { id: 555 },
          from: { id: "tg_switch_user" },
          text: "CREATOR-777",
        },
      },
      db: mockDb,
      storage: null,
      functionBaseUrl: "https://vidvora-share-bot.onrender.com",
      playbackSecret: "sec",
    });

    assert.equal(res.ok, true);
    assert.equal(res.action, "login_success");
    assert.equal(sessions.get("tg_switch_user").memberId, "creator_new");
    assert.ok(sent[0].body.text.includes("Welcome *Altamash New*"));
  } finally {
    global.fetch = origFetch;
  }
});

function buildFakeTelegramBotFileId({
  typeId = 4,
  dcId = 5,
  fileReference = Buffer.from([1, 2, 3, 4, 5]),
  id = 9876543210123456n,
  accessHash = 1122334455667788n,
}) {
  const chunks = [];
  const head = Buffer.alloc(8);
  head.writeUInt32LE((typeId | (1 << 25)) >>> 0, 0);
  head.writeInt32LE(dcId, 4);
  chunks.push(head);

  const lenBuf = Buffer.from([fileReference.length]);
  const totalLen = 1 + fileReference.length;
  const padLen = (4 - (totalLen % 4)) % 4;
  chunks.push(lenBuf, fileReference, Buffer.alloc(padLen));

  const ids = Buffer.alloc(16);
  ids.writeBigInt64LE(id, 0);
  ids.writeBigInt64LE(accessHash, 8);
  chunks.push(ids);

  chunks.push(Buffer.from([35, 4])); // subVersion=35, majorVersion=4
  const raw = Buffer.concat(chunks);

  // Zero-byte RLE encode (matching Telegram Bot API file_id encoding)
  const encoded = [];
  for (let i = 0; i < raw.length; i += 1) {
    if (raw[i] === 0x00) {
      let count = 0;
      while (i + count < raw.length && raw[i + count] === 0x00 && count < 255) {
        count += 1;
      }
      encoded.push(0x00, count);
      i += count - 1;
    } else {
      encoded.push(raw[i]);
    }
  }
  return Buffer.from(encoded)
    .toString("base64")
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

test("decodeTelegramBotFileId extracts dcId, id, accessHash, and fileReference cleanly", () => {
  const { decodeTelegramBotFileId } = require("./telegramBot");
  const fakeRef = Buffer.from("ref_bytes_123");
  const fileId = buildFakeTelegramBotFileId({
    typeId: 4,
    dcId: 5,
    fileReference: fakeRef,
    id: 123456789012345n,
    accessHash: -4455667788990011n,
  });

  const decoded = decodeTelegramBotFileId(fileId);
  assert.ok(decoded);
  assert.equal(decoded.typeId, 4);
  assert.equal(decoded.dcId, 5);
  assert.equal(decoded.id, "123456789012345");
  assert.equal(decoded.accessHash, "-4455667788990011");
  assert.equal(Buffer.compare(decoded.fileReference, fakeRef), 0);
});

test("handleTelegramUpdate gives clear MTProto 2GB setup guide for >20MB video when apiId/apiHash missing", async () => {
  const { handleTelegramUpdate } = require("./telegramBot");

  const mockDb = {
    collection: (name) => {
      if (name === "appConfig") {
        return {
          doc: (id) => ({
            get: async () =>
              id === "telegramBot"
                ? { exists: true, data: () => ({ botToken: "123:TESTTOKEN" }) }
                : { exists: false, data: () => ({}) },
          }),
        };
      }
      if (name === "telegramBotSessions") {
        return {
          doc: () => ({
            get: async () => ({ exists: true, data: () => ({ memberId: "admin", memberName: "Admin" }) }),
          }),
        };
      }
      return { doc: () => ({ get: async () => ({ exists: false }) }) };
    },
  };

  const sentCalls = [];
  const origFetch = global.fetch;
  global.fetch = async (url, opts) => {
    const method = String(url).split("/").pop();
    const body = typeof opts?.body === "string" ? JSON.parse(opts.body) : {};
    sentCalls.push({ method, body });
    return { ok: true, json: async () => ({ ok: true, result: { message_id: 99 } }) };
  };

  try {
    const res = await handleTelegramUpdate({
      update: {
        message: {
          message_id: 101,
          chat: { id: 777 },
          from: { id: "admin_user" },
          video: {
            file_id: "BAAC_LARGE_NO_MTPROTO",
            file_size: 45 * 1024 * 1024,
            mime_type: "video/mp4",
          },
          caption: "Large Action Movie",
        },
      },
      db: mockDb,
      storage: null,
      functionBaseUrl: "https://vidvora-share-bot.onrender.com",
      playbackSecret: "sec",
    });

    assert.equal(res.ok, false);
    assert.equal(res.reason, "file_too_big");
    const editCall = sentCalls.find((c) => c.method === "editMessageText");
    assert.ok(editCall);
    assert.ok(editCall.body.text.includes("TELEGRAM_API_ID"));
    assert.ok(editCall.body.text.includes("TELEGRAM_API_HASH"));
    assert.ok(editCall.body.text.includes("2 GB"));
  } finally {
    global.fetch = origFetch;
  }
});

test("handleTelegramUpdate publishes 150MB video immediately via MTProto mode & handleStreamPublicVideo streams HTTP 206 byte ranges", async () => {
  const { PassThrough } = require("stream");
  const { handleTelegramUpdate, handleStreamPublicVideo, MTPROTO_CHUNK_SIZE } = require("./telegramBot");

  // Generate real 1x1 JPEG bytes for thumbnail
  const tmpDir = await fs.promises.mkdtemp(path.join(os.tmpdir(), "vidvora-mtproto-test-"));
  let thumbBytes;
  try {
    const jpgPath = path.join(tmpDir, "thumb.jpg");
    await execFileAsync("ffmpeg", ["-y", "-f", "lavfi", "-i", "color=c=blue:s=64x64", "-vframes", "1", jpgPath]);
    thumbBytes = await fs.promises.readFile(jpgPath);
  } finally {
    await fs.promises.rm(tmpDir, { recursive: true, force: true });
  }

  const largeFileSize = 150 * 1024 * 1024;
  const largeFileId = buildFakeTelegramBotFileId({
    typeId: 4,
    dcId: 5,
    fileReference: Buffer.from("large_video_ref"),
    id: 555666777888n,
    accessHash: 999888777666n,
  });

  const videosStore = new Map();
  const accessLinksStore = new Map();

  const mockDb = {
    collection: (name) => {
      if (name === "appConfig") {
        return {
          doc: (id) => ({
            get: async () =>
              id === "telegramBot"
                ? {
                    exists: true,
                    data: () => ({
                      botToken: "123:TESTTOKEN",
                      apiId: 2040,
                      apiHash: "abcdef1234567890abcdef1234567890",
                    }),
                  }
                : { exists: false, data: () => ({}) },
          }),
        };
      }
      if (name === "telegramBotSessions") {
        return {
          doc: () => ({
            get: async () => ({
              exists: true,
              data: () => ({ memberId: "admin", memberName: "Admin", uploadCount: 0 }),
            }),
            set: async () => {},
          }),
        };
      }
      if (name === "videos") {
        return {
          doc: (id) => ({
            get: async () => (videosStore.has(id) ? { exists: true, data: () => videosStore.get(id) } : { exists: false }),
            set: async (payload) => {
              videosStore.set(id, payload);
            },
          }),
        };
      }
      if (name === "accessLinks") {
        return {
          doc: (id) => ({
            set: async (payload) => {
              accessLinksStore.set(id, payload);
            },
          }),
        };
      }
      return { doc: () => ({ get: async () => ({ exists: false }) }) };
    },
  };

  const sentTelegram = [];
  const origFetch = global.fetch;
  global.fetch = async (url, opts = {}) => {
    const u = String(url);
    if (u.endsWith("/getFile")) {
      return {
        ok: true,
        json: async () => ({ ok: true, result: { file_path: "thumbnails/t1.jpg" } }),
      };
    }
    if (u.includes("/file/bot")) {
      return {
        ok: true,
        arrayBuffer: async () => thumbBytes,
      };
    }
    const method = u.split("/").pop();
    const body = typeof opts.body === "string" ? JSON.parse(opts.body) : { formData: true };
    sentTelegram.push({ method, body });
    return { ok: true, json: async () => ({ ok: true, result: { message_id: 222 } }) };
  };

  let uploadResult;
  try {
    uploadResult = await handleTelegramUpdate({
      update: {
        message: {
          message_id: 300,
          chat: { id: 888999 },
          from: { id: "admin_user" },
          video: {
            file_id: largeFileId,
            file_size: largeFileSize,
            mime_type: "video/mp4",
            thumbnail: { file_id: "THUMB_SMALL_ID" },
          },
          caption: "Full HD 150MB Movie",
        },
      },
      db: mockDb,
      storage: null,
      functionBaseUrl: "https://vidvora-share-bot.onrender.com",
      playbackSecret: "mtproto-secret",
    });

    assert.equal(uploadResult.ok, true);
    const savedDoc = videosStore.get(uploadResult.videoId);
    assert.ok(savedDoc);
    assert.equal(savedDoc.tgFileId, largeFileId);
    assert.equal(savedDoc.tgFileSize, largeFileSize);
    assert.equal(savedDoc.tgChatId, "888999");
    assert.equal(savedDoc.tgMessageId, 300);
    assert.ok(
      String(savedDoc.thumbnailPath || "").includes("/shareThumb?videoId=") ||
        String(savedDoc.thumbnailPath || "").startsWith("data:image/jpeg;base64,")
    );
    assert.ok(sentTelegram.some((c) => c.method === "sendPhoto"));
  } finally {
    global.fetch = origFetch;
  }

  // Now verify HTTP 206 MTProto range streaming starting inside chunk #1 (>512 KB)
  const targetUrl = new URL(videosStore.get(uploadResult.videoId).storagePath);
  const sig = targetUrl.searchParams.get("sig");

  const fakeMtprotoChunk = Buffer.alloc(MTPROTO_CHUNK_SIZE);
  for (let i = 0; i < MTPROTO_CHUNK_SIZE; i += 1) {
    fakeMtprotoChunk[i] = i % 256;
  }

  const fakeTelegramLib = {
    StringSession: class {},
    Api: {
      InputDocumentFileLocation: class {
        constructor(params) {
          Object.assign(this, params);
        }
      },
    },
    bigInt: require("big-integer"),
    TelegramClient: class {
      async start() {}
      setLogLevel() {}
      iterDownload({ offset }) {
        assert.equal(Number(offset.toString()), MTPROTO_CHUNK_SIZE);
        return (async function* () {
          yield fakeMtprotoChunk;
        })();
      }
    },
  };

  const makeMockRes = () => {
    const stream = new PassThrough();
    const chunks = [];
    let statusCode = 0;
    let headers = {};
    const done = new Promise((resolve) => {
      stream.on("data", (c) => chunks.push(c));
      stream.on("end", () => resolve({ statusCode, headers, body: Buffer.concat(chunks) }));
    });
    stream.writeHead = (code, hdrs) => {
      statusCode = code;
      headers = hdrs || {};
    };
    const origEnd = stream.end.bind(stream);
    stream.end = (chunk, ...rest) => {
      if (chunk) chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
      return origEnd(...rest);
    };
    return { res: stream, done };
  };

  const rangeStart = MTPROTO_CHUNK_SIZE + 100;
  const rangeEnd = MTPROTO_CHUNK_SIZE + 115;
  const rangeCall = makeMockRes();
  await handleStreamPublicVideo({
    videoId: uploadResult.videoId,
    kind: "video",
    sig,
    method: "GET",
    rangeHeader: `bytes=${rangeStart}-${rangeEnd}`,
    res: rangeCall.res,
    db: mockDb,
    storage: null,
    playbackSecret: "mtproto-secret",
    telegramLibOverride: fakeTelegramLib,
  });

  const streamed = await rangeCall.done;
  assert.equal(streamed.statusCode, 206);
  assert.equal(streamed.headers["Content-Type"], "video/mp4");
  assert.equal(streamed.headers["Content-Range"], `bytes ${rangeStart}-${rangeEnd}/${largeFileSize}`);
  assert.equal(streamed.headers["Content-Length"], 16);
  assert.equal(Buffer.compare(streamed.body, fakeMtprotoChunk.subarray(100, 116)), 0);
});

test("handleTelegramUpdate falls back to instant MTProto stream when 45MB preview download fails", async () => {
  const { handleTelegramUpdate } = require("./telegramBot");

  const mediumLargeFileSize = 45 * 1024 * 1024;
  const mediumLargeFileId = buildFakeTelegramBotFileId({
    typeId: 4,
    dcId: 5,
    fileReference: Buffer.from("med_large_ref"),
    id: 111222333444n,
    accessHash: 555444333222n,
  });

  const videosStore = new Map();
  const mockDb = {
    collection: (name) => {
      if (name === "appConfig") {
        return {
          doc: (id) => ({
            get: async () =>
              id === "telegramBot"
                ? {
                    exists: true,
                    data: () => ({
                      botToken: "123:TESTTOKEN",
                      apiId: 2040,
                      apiHash: "abcdef1234567890abcdef1234567890",
                    }),
                  }
                : { exists: false, data: () => ({}) },
          }),
        };
      }
      if (name === "telegramBotSessions") {
        return {
          doc: () => ({
            get: async () => ({
              exists: true,
              data: () => ({ memberId: "admin", memberName: "Admin", uploadCount: 1 }),
            }),
            set: async () => {},
          }),
        };
      }
      if (name === "videos") {
        return {
          doc: (id) => ({
            set: async (payload) => videosStore.set(id, payload),
          }),
        };
      }
      if (name === "accessLinks") {
        return { doc: () => ({ set: async () => {} }) };
      }
      return { doc: () => ({ get: async () => ({ exists: false }) }) };
    },
  };

  const failingMtprotoLib = {
    StringSession: class {},
    Api: { InputDocumentFileLocation: class {} },
    bigInt: require("big-integer"),
    TelegramClient: class {
      async start() {
        throw new Error("Simulated MTProto preview timeout");
      }
      setLogLevel() {}
    },
  };

  const origFetch = global.fetch;
  global.fetch = async (url) => {
    const u = String(url);
    if (u.endsWith("/getFile")) {
      return { ok: true, json: async () => ({ ok: true, result: { file_path: "thumbnails/t2.jpg" } }) };
    }
    if (u.includes("/file/bot")) {
      return { ok: true, arrayBuffer: async () => Buffer.from([0xff, 0xd8, 0xff, 0xd9]) };
    }
    return { ok: true, json: async () => ({ ok: true, result: { message_id: 333 } }) };
  };

  try {
    const res = await handleTelegramUpdate({
      update: {
        message: {
          message_id: 401,
          chat: { id: 888999 },
          from: { id: "admin_user" },
          document: {
            file_id: mediumLargeFileId,
            file_size: mediumLargeFileSize,
            file_name: "Episode_45MB.mp4",
            mime_type: "video/mp4",
            thumbnail: { file_id: "THUMB_45MB" },
          },
          caption: "45MB Episode",
        },
      },
      db: mockDb,
      storage: null,
      functionBaseUrl: "https://vidvora-share-bot.onrender.com",
      playbackSecret: "mtproto-secret",
      telegramLibOverride: failingMtprotoLib,
    });

    assert.equal(res.ok, true);
    const saved = videosStore.get(res.videoId);
    assert.ok(saved);
    assert.equal(saved.tgFileId, mediumLargeFileId);
    assert.equal(saved.tgFileSize, mediumLargeFileSize);
  } finally {
    global.fetch = origFetch;
  }
});

test("handleSharePageRequest skips blocking DB lookups for Android browsers and redirects immediately in head", async () => {
  const { handleSharePageRequest } = require("./sharePreview");
  let dbCalls = 0;
  const mockDb = {
    collection: () => {
      dbCalls += 1;
      throw new Error("Should not query Firestore on Android browser redirect");
    },
  };
  const out = { statusCode: 0, headers: {}, chunks: [] };
  const mockRes = {
    writeHead(code, headers) {
      out.statusCode = code;
      out.headers = headers || {};
    },
    end(chunk) {
      if (chunk) out.chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(String(chunk)));
    },
  };

  await handleSharePageRequest({
    reqUrl: "/share?link=GM-FAST123",
    hostBaseUrl: "https://vidvora-share-bot.onrender.com",
    res: mockRes,
    db: mockDb,
    userAgent: "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36",
  });

  assert.equal(dbCalls, 0);
  assert.equal(out.statusCode, 200);
  const html = Buffer.concat(out.chunks).toString("utf8");
  assert.ok(html.includes('window.location.replace("intent://watch?link=GM-FAST123'));
  assert.ok(!html.includes("setTimeout("));
});

test("isUnsupportedThirdPartyHostUrl detects TeraBox/Tenbox/Flezen/Vidbunker links and handleTelegramUpdate rejects them cleanly without ffmpeg", async () => {
  const { handleTelegramUpdate, isUnsupportedThirdPartyHostUrl } = require("./telegramBot");

  assert.equal(isUnsupportedThirdPartyHostUrl("https://terabox.com/s/1abcXYZ"), true);
  assert.equal(isUnsupportedThirdPartyHostUrl("https://www.1024terabox.com/sharing/link?surl=123"), true);
  assert.equal(isUnsupportedThirdPartyHostUrl("https://tenbox.in/v/abc123"), true);
  assert.equal(isUnsupportedThirdPartyHostUrl("https://1024tenbox.com/f/file/DYCdmeBFQV6PZdWnWDGtZg"), true);
  assert.equal(isUnsupportedThirdPartyHostUrl("https://flezen.com/watch/demo99"), true);
  assert.equal(isUnsupportedThirdPartyHostUrl("https://vidbunker.com/share/vid888"), true);
  assert.equal(isUnsupportedThirdPartyHostUrl("https://cdn.example.com/movies/sample.mp4"), false);

  const mockDb = {
    collection: (name) => {
      if (name === "appConfig") {
        return {
          doc: () => ({
            get: async () => ({ exists: true, data: () => ({ botToken: "123:TESTTOKEN" }) }),
          }),
        };
      }
      if (name === "telegramBotSessions") {
        return {
          doc: () => ({
            get: async () => ({
              exists: true,
              data: () => ({ memberId: "admin", memberName: "Admin", uploadCount: 1 }),
            }),
          }),
        };
      }
      throw new Error(`Unexpected DB write/query to ${name}`);
    },
  };

  const sent = [];
  const origFetch = global.fetch;
  global.fetch = async (url, opts) => {
    sent.push({ method: String(url).split("/").pop(), body: JSON.parse(opts.body) });
    return { ok: true, json: async () => ({ ok: true, result: { message_id: 404 } }) };
  };

  try {
    for (const badUrl of [
      "https://terabox.com/s/1abcdEFG",
      "https://vidbunker.com/watch/demo1234",
      "https://flezen.com/share/clip999",
    ]) {
      const res = await handleTelegramUpdate({
        update: {
          message: {
            message_id: 600 + sent.length,
            chat: { id: 12345 },
            from: { id: "admin_user" },
            text: badUrl,
          },
        },
        db: mockDb,
        storage: null,
        functionBaseUrl: "https://vidvora-share-bot.onrender.com",
        playbackSecret: "sec",
      });

      assert.equal(res.ok, false);
      assert.equal(res.reason, "unsupported_third_party_host");
    }

    assert.equal(sent.length, 3);
    assert.ok(sent[0].body.text.includes("Third-Party Web Link Supported Nahi Hai"));
    assert.ok(sent[0].body.text.includes("Batch Forward Videos"));
  } finally {
    global.fetch = origFetch;
  }
});

test("handleTelegramUpdate uploads forwarded Telegram video even when caption contains unrelated /watch or third-party links", async () => {
  const { handleTelegramUpdate } = require("./telegramBot");

  const tmpDir = await fs.promises.mkdtemp(path.join(os.tmpdir(), "vidvora-fwd-promo-"));
  let sampleVideoBytes;
  try {
    const sampleMp4 = path.join(tmpDir, "sample.mp4");
    await execFileAsync(
      "ffmpeg",
      ["-y", "-f", "lavfi", "-i", "testsrc=duration=2:size=160x120:rate=10", "-c:v", "libx264", "-pix_fmt", "yuv420p", sampleMp4],
      { timeout: 15_000 }
    );
    sampleVideoBytes = await fs.promises.readFile(sampleMp4);
  } finally {
    await fs.promises.rm(tmpDir, { recursive: true, force: true });
  }

  const videosStore = new Map();
  const accessLinksStore = new Map();
  const mockDb = {
    collection: (name) => {
      if (name === "appConfig") {
        return {
          doc: () => ({
            get: async () => ({ exists: true, data: () => ({ botToken: "123:TESTTOKEN" }) }),
          }),
        };
      }
      if (name === "telegramBotSessions") {
        return {
          doc: () => ({
            get: async () => ({
              exists: true,
              data: () => ({ memberId: "admin", memberName: "Admin", uploadCount: 0 }),
            }),
            set: async () => {},
          }),
        };
      }
      if (name === "videos") {
        return {
          doc: (id) => ({
            set: async (payload) => videosStore.set(id, payload),
          }),
        };
      }
      if (name === "accessLinks") {
        return {
          doc: (id) => ({
            set: async (payload) => accessLinksStore.set(id, payload),
          }),
        };
      }
      return { doc: () => ({ get: async () => ({ exists: false }) }) };
    },
  };

  const origFetch = global.fetch;
  global.fetch = async (url) => {
    const u = String(url);
    if (u.endsWith("/getFile")) {
      return { ok: true, json: async () => ({ ok: true, result: { file_path: "videos/fwd.mp4" } }) };
    }
    if (u.includes("/file/bot")) {
      return { ok: true, arrayBuffer: async () => sampleVideoBytes };
    }
    return { ok: true, json: async () => ({ ok: true, result: { message_id: 901 } }) };
  };

  try {
    const res = await handleTelegramUpdate({
      update: {
        message: {
          message_id: 808,
          chat: { id: 777111 },
          from: { id: "admin_user" },
          video: {
            file_id: "BAAC_FWD_VIDEO_1",
            file_size: sampleVideoBytes.length,
            duration: 2,
            mime_type: "video/mp4",
          },
          caption: "New Channel Episode 1\nBackup: https://terabox.com/share/1xyz\nPromo: https://otherchannel.site/watch/ep101",
        },
      },
      db: mockDb,
      storage: null,
      functionBaseUrl: "https://vidvora-share-bot.onrender.com",
      playbackSecret: "test-secret",
    });

    assert.equal(res.ok, true);
    assert.ok(res.rawToken.startsWith("GM-"));
    const saved = videosStore.get(res.videoId);
    assert.ok(saved);
    assert.equal(saved.title, "New Channel Episode 1");
    assert.equal(saved.tgFileId, "BAAC_FWD_VIDEO_1");
  } finally {
    await fs.promises.rm(path.join(os.tmpdir(), "vidvora-stream-cache"), { recursive: true, force: true }).catch(() => {});
    global.fetch = origFetch;
  }
});

test("handleTelegramUpdate retries transient terminated download errors and serializes concurrent batch uploads", async () => {
  const { handleTelegramUpdate } = require("./telegramBot");

  const tmpDir = await fs.promises.mkdtemp(path.join(os.tmpdir(), "vidvora-batch-serial-"));
  let sampleVideoBytes;
  try {
    const sampleMp4 = path.join(tmpDir, "sample.mp4");
    await execFileAsync(
      "ffmpeg",
      ["-y", "-f", "lavfi", "-i", "testsrc=duration=2:size=160x120:rate=10", "-c:v", "libx264", "-pix_fmt", "yuv420p", sampleMp4],
      { timeout: 15_000 }
    );
    sampleVideoBytes = await fs.promises.readFile(sampleMp4);
  } finally {
    await fs.promises.rm(tmpDir, { recursive: true, force: true });
  }

  const videosStore = new Map();
  const mockDb = {
    collection: (name) => {
      if (name === "appConfig") {
        return {
          doc: () => ({
            get: async () => ({ exists: true, data: () => ({ botToken: "123:TESTTOKEN" }) }),
          }),
        };
      }
      if (name === "telegramBotSessions") {
        return {
          doc: () => ({
            get: async () => ({
              exists: true,
              data: () => ({ memberId: "admin", memberName: "Admin", uploadCount: 0 }),
            }),
            set: async () => {},
          }),
        };
      }
      if (name === "videos") {
        return {
          doc: (id) => ({
            set: async (payload) => videosStore.set(id, payload),
          }),
        };
      }
      if (name === "accessLinks") {
        return { doc: () => ({ set: async () => {} }) };
      }
      return { doc: () => ({ get: async () => ({ exists: false }) }) };
    },
  };

  let downloadCalls = 0;
  let activeDownloads = 0;
  let maxConcurrentDownloads = 0;
  const origFetch = global.fetch;
  global.fetch = async (url) => {
    const u = String(url);
    if (u.endsWith("/getFile")) {
      return { ok: true, json: async () => ({ ok: true, result: { file_path: "videos/batch.mp4" } }) };
    }
    if (u.includes("/file/bot")) {
      downloadCalls += 1;
      activeDownloads += 1;
      maxConcurrentDownloads = Math.max(maxConcurrentDownloads, activeDownloads);
      try {
        if (downloadCalls === 1) {
          throw new TypeError("terminated");
        }
        await new Promise((r) => setTimeout(r, 30));
        return { ok: true, arrayBuffer: async () => sampleVideoBytes };
      } finally {
        activeDownloads -= 1;
      }
    }
    return { ok: true, json: async () => ({ ok: true, result: { message_id: 950 } }) };
  };

  try {
    const [r1, r2, r3] = await Promise.all([
      handleTelegramUpdate({
        update: {
          message: {
            message_id: 901,
            chat: { id: 444111 },
            from: { id: "admin_user" },
            video: { file_id: "VID_B1", file_size: sampleVideoBytes.length, duration: 2, mime_type: "video/mp4" },
            caption: "Batch 1",
          },
        },
        db: mockDb,
        storage: null,
        functionBaseUrl: "https://vidvora-share-bot.onrender.com",
        playbackSecret: "test-secret",
      }),
      handleTelegramUpdate({
        update: {
          message: {
            message_id: 902,
            chat: { id: 444111 },
            from: { id: "admin_user" },
            video: { file_id: "VID_B2", file_size: sampleVideoBytes.length, duration: 2, mime_type: "video/mp4" },
            caption: "Batch 2",
          },
        },
        db: mockDb,
        storage: null,
        functionBaseUrl: "https://vidvora-share-bot.onrender.com",
        playbackSecret: "test-secret",
      }),
      handleTelegramUpdate({
        update: {
          message: {
            message_id: 903,
            chat: { id: 444111 },
            from: { id: "admin_user" },
            video: { file_id: "VID_B3", file_size: sampleVideoBytes.length, duration: 2, mime_type: "video/mp4" },
            caption: "Batch 3",
          },
        },
        db: mockDb,
        storage: null,
        functionBaseUrl: "https://vidvora-share-bot.onrender.com",
        playbackSecret: "test-secret",
      }),
    ]);

    assert.equal(r1.ok, true);
    assert.equal(r2.ok, true);
    assert.equal(r3.ok, true);
    assert.equal(videosStore.size, 3);
    assert.equal(maxConcurrentDownloads, 1);
    assert.equal(downloadCalls, 4); // 1 transient 'terminated' retry + 3 successful downloads
  } finally {
    await fs.promises.rm(path.join(os.tmpdir(), "vidvora-stream-cache"), { recursive: true, force: true }).catch(() => {});
    global.fetch = origFetch;
  }
});

test("telegramApiJson automatically waits and retries on Telegram 429 Too Many Requests", async () => {
  const { telegramApiJson } = require("./telegramBot");
  let attempts = 0;
  const origFetch = global.fetch;
  global.fetch = async () => {
    attempts += 1;
    if (attempts === 1) {
      return {
        ok: false,
        json: async () => ({
          ok: false,
          error_code: 429,
          description: "Too Many Requests: retry after 1",
          parameters: { retry_after: 1 },
        }),
      };
    }
    return { ok: true, json: async () => ({ ok: true, result: { message_id: 777 } }) };
  };

  try {
    const res = await telegramApiJson("https://api.telegram.org", "123:TEST", "sendMessage", {
      chat_id: 111,
      text: "Hello",
    });
    assert.equal(attempts, 2);
    assert.equal(res.message_id, 777);
  } finally {
    global.fetch = origFetch;
  }
});

test("isAllowedTelegramVideoMessage accepts muted animation videos and video_note messages", () => {
  assert.equal(
    isAllowedTelegramVideoMessage({ animation: { file_id: "anim_1", mime_type: "video/mp4", duration: 21 } }),
    true
  );
  assert.equal(isAllowedTelegramVideoMessage({ video_note: { file_id: "vn_1", duration: 15 } }), true);
});

test("handleTelegramUpdate instantly converts forwarded VidVora video post by reusing preview file_id with HTML caption", async () => {
  const { handleTelegramUpdate, hashToken } = require("./telegramBot");

  const sourceToken = "GM-DAVUMPAK";
  const sourceHash = hashToken(sourceToken);
  const videosStore = new Map([
    [
      "vid_orig_dav",
      {
        videoId: "vid_orig_dav",
        title: "Original Clip",
        description: "Desc",
        storagePath: "https://vidvora-share-bot.onrender.com/streamPublicVideo?videoId=vid_orig_dav&sig=abc",
        thumbnailPath: "data:image/jpeg;base64,/9j/4AAQ",
        tgFileId: "tg_orig_full_file",
        isActive: true,
        totalViews: 10,
        uploadedBy: "creator_old",
        uploaderName: "Old Creator",
      },
    ],
  ]);

  const mockDb = {
    collection: (name) => {
      if (name === "appConfig") {
        return {
          doc: (id) => ({
            get: async () =>
              id === "telegramBot"
                ? { exists: true, data: () => ({ botToken: "123:TEST_TOKEN" }) }
                : { exists: false, data: () => ({}) },
          }),
        };
      }
      if (name === "telegramBotSessions") {
        return {
          doc: () => ({
            get: async () => ({
              exists: true,
              data: () => ({ memberId: "admin", memberName: "Admin", uploadCount: 5 }),
            }),
            set: async () => {},
          }),
        };
      }
      if (name === "accessLinks") {
        return {
          where: (_f, _op, val) => ({
            limit: () => ({
              get: async () =>
                val === sourceHash
                  ? {
                      empty: false,
                      docs: [{ id: "link_orig", exists: true, data: () => ({ videoId: "vid_orig_dav", isActive: true }) }],
                    }
                  : { empty: true, docs: [] },
            }),
          }),
          doc: () => ({ get: async () => ({ exists: false }), set: async () => {} }),
        };
      }
      if (name === "videos") {
        return {
          doc: (id) => ({
            get: async () => (videosStore.has(id) ? { id, exists: true, data: () => videosStore.get(id) } : { exists: false }),
            set: async (payload) => videosStore.set(id, payload),
          }),
        };
      }
      return { doc: () => ({ get: async () => ({ exists: false }) }) };
    },
  };

  const apiCalls = [];
  const origFetch = global.fetch;
  global.fetch = async (url, init = {}) => {
    const method = String(url).split("/").pop();
    const body = typeof init.body === "string" ? JSON.parse(init.body) : {};
    apiCalls.push({ method, body });
    return { ok: true, json: async () => ({ ok: true, result: { message_id: 888 } }) };
  };

  try {
    const res = await handleTelegramUpdate({
      update: {
        update_id: 999888777,
        message: {
          message_id: 401,
          chat: { id: 123456 },
          from: { id: "admin_tg" },
          video: { file_id: "FORWARDED_PREVIEW_CLIP_ID", duration: 5, file_size: 400_000, mime_type: "video/mp4" },
          caption: "🎬 VidVora — Watch Video\n\n🔗 Watch Full Video:\nhttps://vidvora-share-bot.onrender.com/share?link=GM-DAVUMPAK",
          reply_markup: {
            inline_keyboard: [[{ text: "▶️ Watch Full Video on VidVora", url: "https://vidvora-share-bot.onrender.com/share?link=GM-DAVUMPAK" }]],
          },
        },
      },
      db: mockDb,
      storage: null,
      functionBaseUrl: "https://vidvora-share-bot.onrender.com",
      playbackSecret: "sec",
    });

    assert.equal(res.ok, true);
    assert.ok(res.rawToken.startsWith("GM-"));
    assert.notEqual(res.rawToken, sourceToken);

    const sendVideoCall = apiCalls.find((c) => c.method === "sendVideo");
    assert.ok(sendVideoCall, "Expected instant sendVideo reusing forwarded preview file_id");
    assert.equal(sendVideoCall.body.video, "FORWARDED_PREVIEW_CLIP_ID");
    assert.equal(sendVideoCall.body.parse_mode, "HTML");
    assert.ok(sendVideoCall.body.caption.includes(res.rawToken));
    assert.ok(sendVideoCall.body.caption.includes("https://t.me/VideoKaiseDekhe_ar"));
    assert.ok(sendVideoCall.body.caption.includes("How to play video"));
    assert.ok(!sendVideoCall.body.caption.includes("\\_"));
  } finally {
    global.fetch = origFetch;
  }
});

test("handleSharePageRequest resolves Postgres stream URL with vv tag for old+new APK compatibility while protecting inactive links and third-party URLs", async () => {
  const { handleSharePageRequest, buildDeepLinkTarget } = require("./sharePreview");

  const activeCode = "GM-PGPLAY88";
  const activeHash = hashToken(activeCode);
  const streamUrl =
    "https://vidvora-share-bot.onrender.com/streamPublicVideo?videoId=vid_pg_88&kind=video&sig=abcdef1234";

  const mockPgDb = {
    isPostgresAdapter: true,
    collection: (name) => {
      if (name === "accessLinks") {
        return {
          where: (_field, _op, val) => ({
            limit: () => ({
              get: async () =>
                val === activeHash
                  ? {
                      empty: false,
                      docs: [
                        {
                          id: "link_pg_88",
                          exists: true,
                          data: () => ({ linkId: "link_pg_88", videoId: "vid_pg_88", isActive: true }),
                        },
                      ],
                    }
                  : { empty: true, docs: [] },
            }),
          }),
          doc: () => ({ get: async () => ({ exists: false }) }),
        };
      }
      if (name === "videos") {
        return {
          doc: (id) => ({
            get: async () =>
              id === "vid_pg_88"
                ? {
                    id: "vid_pg_88",
                    exists: true,
                    data: () => ({
                      videoId: "vid_pg_88",
                      title: "Neon Postgres Video",
                      storagePath: streamUrl,
                      isActive: true,
                    }),
                  }
                : { exists: false },
          }),
        };
      }
      return { doc: () => ({ get: async () => ({ exists: false }) }) };
    },
  };

  const out = { statusCode: 0, headers: {}, chunks: [] };
  const mockRes = {
    writeHead(code, headers) {
      out.statusCode = code;
      out.headers = headers || {};
    },
    end(chunk) {
      if (chunk) out.chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(String(chunk)));
    },
  };

  await handleSharePageRequest({
    reqUrl: `/share?link=${activeCode}`,
    hostBaseUrl: "https://vidvora-share-bot.onrender.com",
    res: mockRes,
    db: mockPgDb,
    userAgent: "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36",
  });

  assert.equal(out.statusCode, 200);
  const html = Buffer.concat(out.chunks).toString("utf8");
  const expectedDeepLink = `${streamUrl}&vv=${activeCode}`;
  assert.ok(
    html.includes(`intent://watch?link=${encodeURIComponent(expectedDeepLink)}#Intent;scheme=vidvora;package=com.aistudio.vidvora.vstr;`),
    "Expected Android intent URI to carry direct stream URL with &vv= share code"
  );
  // Fallback install URL and copied share link must still stay clean short ?link=GM-...
  assert.ok(
    html.includes(encodeURIComponent(`https://vidvora-share-bot.onrender.com/share?link=${activeCode}&install=1`))
  );
  assert.ok(html.includes(`var cleanShareUrl = "https://vidvora-share-bot.onrender.com/share?link=${activeCode}"`));

  // Third-party signed URL must NOT be mutated with &vv=
  const signedCdn = "https://cdn.example.com/movie.mp4?X-Amz-Signature=deadbeef";
  assert.equal(
    buildDeepLinkTarget("GM-EXT1", { found: true, isActive: true, storagePath: signedCdn }),
    signedCdn
  );

  // Inactive video must never leak storagePath
  assert.equal(
    buildDeepLinkTarget("GM-OFF1", { found: true, isActive: false, storagePath: streamUrl }),
    "GM-OFF1"
  );
});

test("handlePrivacyPolicyRequest and handlePrivacyPolicyPdfRequest serve Play Store HTML policy and valid PDF file", async () => {
  const { handlePrivacyPolicyRequest, handlePrivacyPolicyPdfRequest } = require("./privacyPolicy");

  const makeMockRes = () => {
    const out = { statusCode: 0, headers: {}, chunks: [] };
    return {
      writeHead(code, headers) {
        out.statusCode = code;
        out.headers = headers || {};
      },
      end(chunk) {
        if (chunk) out.chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(String(chunk)));
      },
      out,
      buffer() {
        return Buffer.concat(out.chunks);
      },
      text() {
        return Buffer.concat(out.chunks).toString("utf8");
      },
    };
  };

  const htmlRes = makeMockRes();
  handlePrivacyPolicyRequest({ res: htmlRes, hostBaseUrl: "https://vidvora-share-bot.onrender.com" });
  assert.equal(htmlRes.out.statusCode, 200);
  assert.ok(htmlRes.text().includes("com.aistudio.vidvora.vstr"));
  assert.ok(htmlRes.text().includes("teamaltamash@gmail.com"));
  assert.ok(htmlRes.text().includes('id="data-deletion"'));
  assert.ok(htmlRes.text().includes("https://vidvora-share-bot.onrender.com/privacy-policy.pdf"));

  const pdfRes = makeMockRes();
  await handlePrivacyPolicyPdfRequest({ res: pdfRes });
  assert.equal(pdfRes.out.statusCode, 200);
  assert.equal(pdfRes.out.headers["Content-Type"], "application/pdf");
  const pdfBuf = pdfRes.buffer();
  assert.ok(pdfBuf.length > 5000);
  assert.equal(pdfBuf.subarray(0, 8).toString("ascii"), "%PDF-1.4");
});

test("handleStreamPublicVideo streams HTTP 206 Range directly from Telegram without full download and caches getFile URL", async () => {
  const { PassThrough, Readable } = require("stream");
  const { handleStreamPublicVideo, signStreamTarget } = require("./telegramBot");

  const fakeVideoId = `vid_direct_206_${Date.now()}`;
  const fullPayload = Buffer.from("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789");
  const secret = "fast-stream-secret";
  const sig = signStreamTarget(fakeVideoId, "video", secret);

  const mockDb = {
    collection: (name) => {
      if (name === "appConfig") {
        return {
          doc: () => ({
            get: async () => ({ exists: true, data: () => ({ botToken: "999:FASTTOKEN" }) }),
          }),
        };
      }
      if (name === "videos") {
        return {
          doc: (id) => ({
            get: async () =>
              id === fakeVideoId
                ? {
                    exists: true,
                    data: () => ({
                      videoId: fakeVideoId,
                      isActive: true,
                      tgFileId: `tg_fast_206_${Date.now()}`,
                    }),
                  }
                : { exists: false },
          }),
        };
      }
      return { doc: () => ({ get: async () => ({ exists: false }) }) };
    },
  };

  let getFileCalls = 0;
  let rangeFetchCount = 0;
  const origFetch = global.fetch;
  global.fetch = async (url, init = {}) => {
    if (String(url).includes("/getFile")) {
      getFileCalls += 1;
      return {
        ok: true,
        status: 200,
        json: async () => ({
          ok: true,
          result: { file_path: "videos/direct_206.mp4", file_size: fullPayload.length },
        }),
      };
    }
    if (String(url).includes("/file/bot")) {
      rangeFetchCount += 1;
      const range = String(init?.headers?.Range || "");
      const m = range.match(/^bytes=(\d+)-(\d+)$/);
      assert.ok(m, `Expected Range header, got: ${range}`);
      const start = parseInt(m[1], 10);
      const end = parseInt(m[2], 10);
      const slice = fullPayload.subarray(start, end + 1);
      return {
        ok: true,
        status: 206,
        headers: new Map([
          ["content-type", "application/octet-stream"],
          ["content-range", `bytes ${start}-${end}/${fullPayload.length}`],
          ["content-length", String(slice.length)],
        ]),
        body: Readable.from([slice]),
      };
    }
    throw new Error(`Unexpected fetch URL: ${url}`);
  };

  const makeMockStreamRes = () => {
    const stream = new PassThrough();
    const chunks = [];
    let statusCode = 0;
    let headers = {};
    const done = new Promise((resolve) => {
      stream.on("data", (c) => chunks.push(c));
      stream.on("end", () => resolve({ statusCode, headers, body: Buffer.concat(chunks) }));
      stream.on("finish", () => {
        if (!stream.readableEnded) stream.end();
      });
    });
    stream.writeHead = (code, hdrs) => {
      statusCode = code;
      headers = hdrs || {};
    };
    const origEnd = stream.end.bind(stream);
    stream.end = (chunk, ...rest) => {
      if (chunk) chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
      return origEnd(...rest);
    };
    return { res: stream, done };
  };

  try {
    const req1 = makeMockStreamRes();
    await handleStreamPublicVideo({
      videoId: fakeVideoId,
      kind: "video",
      sig,
      method: "GET",
      rangeHeader: "bytes=0-4",
      res: req1.res,
      db: mockDb,
      storage: null,
      playbackSecret: secret,
    });
    const r1 = await req1.done;
    assert.equal(r1.statusCode, 206);
    assert.equal(r1.headers["Content-Type"], "video/mp4");
    assert.equal(r1.headers["Content-Range"], `bytes 0-4/${fullPayload.length}`);
    assert.equal(r1.headers["Content-Length"], 5);
    assert.equal(r1.body.toString(), "ABCDE");

    // Second range probe should reuse cached getFile URL (getFileCalls stays 1!)
    const req2 = makeMockStreamRes();
    await handleStreamPublicVideo({
      videoId: fakeVideoId,
      kind: "video",
      sig,
      method: "GET",
      rangeHeader: "bytes=26-30",
      res: req2.res,
      db: mockDb,
      storage: null,
      playbackSecret: secret,
    });
    const r2 = await req2.done;
    assert.equal(r2.statusCode, 206);
    assert.equal(r2.body.toString(), "01234");
    assert.equal(getFileCalls, 1);
    assert.equal(rangeFetchCount, 2);
  } finally {
    global.fetch = origFetch;
  }
});

test("handleTelegramUpdate text fallback disables Telegram web page preview so generic VidVora OG banner never appears", async () => {
  const { handleTelegramUpdate, hashToken } = require("./telegramBot");

  const srcCode = "GM-NOPREVIEW1";
  const srcHash = hashToken(srcCode);
  const store = new Map([
    [
      "appConfig/telegramBot",
      {
        botToken: "123:TEST",
      },
    ],
    [
      "members/mem_noprev",
      {
        memberId: "mem_noprev",
        name: "Creator Clean",
        role: "CREATOR",
        isActive: true,
      },
    ],
    [
      "telegramBotSessions/900123",
      {
        telegramUserId: "900123",
        role: "CREATOR",
        memberId: "mem_noprev",
        memberName: "Creator Clean",
        uploadCount: 0,
      },
    ],
    [
      "accessLinks/link_src_1",
      {
        linkId: "link_src_1",
        videoId: "vid_src_1",
        tokenHash: srcHash,
        isActive: true,
      },
    ],
    [
      "videos/vid_src_1",
      {
        videoId: "vid_src_1",
        title: "Clean Fallback Test",
        storagePath: "https://vidvora-share-bot.onrender.com/streamPublicVideo?videoId=vid_src_1&sig=abc",
        thumbnailPath: null,
        isActive: true,
      },
    ],
  ]);

  const mockDb = {
    collection: (col) => {
      const makeQuery = (filters = []) => ({
        where: (field, op, val) => makeQuery([...filters, { field, val }]),
        limit: () => makeQuery(filters),
        get: async () => {
          const matches = [];
          for (const [k, v] of store.entries()) {
            if (k.startsWith(`${col}/`) && filters.every((f) => v[f.field] === f.val)) {
              matches.push({ exists: true, id: k.split("/")[1], data: () => v });
            }
          }
          return { empty: matches.length === 0, docs: matches };
        },
      });
      return {
        doc: (id) => ({
          get: async () => {
            const v = store.get(`${col}/${id}`);
            return v ? { exists: true, id, data: () => v } : { exists: false, id };
          },
          set: async (data, opts) => {
            const prev = opts?.merge ? store.get(`${col}/${id}`) || {} : {};
            store.set(`${col}/${id}`, { ...prev, ...data });
          },
        }),
        where: (field, op, val) => makeQuery([{ field, val }]),
      };
    },
  };

  const editPayloads = [];
  const origFetch = global.fetch;
  global.fetch = async (url, init = {}) => {
    const urlStr = String(url);
    const body = init.body ? JSON.parse(init.body) : {};
    if (urlStr.endsWith("/sendMessage")) {
      return { ok: true, status: 200, json: async () => ({ ok: true, result: { message_id: 777 } }) };
    }
    if (urlStr.endsWith("/editMessageText")) {
      editPayloads.push(body);
      return { ok: true, status: 200, json: async () => ({ ok: true, result: { message_id: 777 } }) };
    }
    return { ok: true, status: 200, json: async () => ({ ok: true, result: true }) };
  };

  try {
    const result = await handleTelegramUpdate({
      update: {
        message: {
          message_id: 101,
          chat: { id: 900123 },
          from: { id: 900123 },
          text: `https://vidvora-share-bot.onrender.com/share?link=${srcCode}`,
        },
      },
      db: mockDb,
      storage: null,
      botToken: "123:TEST",
      functionBaseUrl: "https://vidvora-share-bot.onrender.com",
      playbackSecret: "sec",
    });

    assert.equal(result.ok, true);
    assert.ok(editPayloads.length >= 1);
    const finalEdit = editPayloads[editPayloads.length - 1];
    assert.equal(finalEdit.disable_web_page_preview, true);
    assert.deepEqual(finalEdit.link_preview_options, { is_disabled: true });
  } finally {
    global.fetch = origFetch;
  }
});

test("parseChannelTargetInput, /setchannel, auto-channel post on upload/convert, and /clearchannel work end-to-end", async () => {
  const {
    parseChannelTargetInput,
    extractForwardedChannelInfo,
    handleTelegramUpdate,
    hashToken,
  } = require("./telegramBot");

  assert.deepEqual(parseChannelTargetInput("@VidVoraMovies"), { valid: true, target: "@VidVoraMovies" });
  assert.deepEqual(parseChannelTargetInput("https://t.me/VidVoraMovies"), { valid: true, target: "@VidVoraMovies" });
  assert.deepEqual(parseChannelTargetInput("-1001987654321"), { valid: true, target: "-1001987654321" });
  assert.equal(parseChannelTargetInput("https://t.me/+AbCdEf12345").valid, false);
  assert.equal(parseChannelTargetInput("https://t.me/+AbCdEf12345").reason, "invite_link");

  assert.deepEqual(
    extractForwardedChannelInfo({
      forward_origin: {
        type: "channel",
        chat: { id: -100555666777, title: "Private VIP", username: "" },
      },
    }),
    { id: "-100555666777", title: "Private VIP", username: "" }
  );

  const srcCode = "GM-AUTOPOST1";
  const srcHash = hashToken(srcCode);
  const store = new Map([
    ["appConfig/telegramBot", { botToken: "123:AUTOPOST" }],
    [
      "members/mem_ch_1",
      { memberId: "mem_ch_1", name: "Altamash", role: "CREATOR", isActive: true },
    ],
    [
      "telegramBotSessions/700555",
      {
        telegramUserId: "700555",
        role: "CREATOR",
        memberId: "mem_ch_1",
        memberName: "Altamash",
        uploadCount: 0,
      },
    ],
    [
      "accessLinks/link_ap_1",
      { linkId: "link_ap_1", videoId: "vid_ap_1", tokenHash: srcHash, isActive: true },
    ],
    [
      "videos/vid_ap_1",
      {
        videoId: "vid_ap_1",
        title: "Auto Post Movie",
        storagePath: "https://vidvora-share-bot.onrender.com/streamPublicVideo?videoId=vid_ap_1&sig=xyz",
        thumbnailPath: "https://cdn.example.com/auto_thumb.jpg",
        isActive: true,
      },
    ],
  ]);

  const mockDb = {
    collection: (col) => {
      const makeQuery = (filters = []) => ({
        where: (field, op, val) => makeQuery([...filters, { field, val }]),
        limit: () => makeQuery(filters),
        get: async () => {
          const matches = [];
          for (const [k, v] of store.entries()) {
            if (k.startsWith(`${col}/`) && filters.every((f) => v[f.field] === f.val)) {
              matches.push({ exists: true, id: k.split("/")[1], data: () => v });
            }
          }
          return { empty: matches.length === 0, docs: matches };
        },
      });
      return {
        doc: (id) => ({
          get: async () => {
            const v = store.get(`${col}/${id}`);
            return v ? { exists: true, id, data: () => v } : { exists: false, id };
          },
          set: async (data, opts) => {
            const prev = opts?.merge ? store.get(`${col}/${id}`) || {} : {};
            store.set(`${col}/${id}`, { ...prev, ...data });
          },
        }),
        where: (field, op, val) => makeQuery([{ field, val }]),
      };
    },
  };

  const apiCalls = [];
  const origFetch = global.fetch;
  global.fetch = async (url, init = {}) => {
    const method = String(url).split("/").pop();
    const body = typeof init.body === "string" ? JSON.parse(init.body) : {};
    apiCalls.push({ method, body });
    if (method === "getChat") {
      return {
        ok: true,
        status: 200,
        json: async () => ({
          ok: true,
          result: { id: -100999888777, title: "VidVora Official", username: "VidVoraOfficial", type: "channel" },
        }),
      };
    }
    return { ok: true, status: 200, json: async () => ({ ok: true, result: { message_id: 888 } }) };
  };

  try {
    // 1. User links channel with /setchannel
    const setRes = await handleTelegramUpdate({
      update: {
        message: {
          message_id: 1,
          chat: { id: 700555 },
          from: { id: 700555 },
          text: "/setchannel @VidVoraOfficial",
        },
      },
      db: mockDb,
      storage: null,
      functionBaseUrl: "https://vidvora-share-bot.onrender.com",
      playbackSecret: "sec",
    });
    assert.equal(setRes.ok, true);
    assert.equal(setRes.action, "channel_set");
    assert.equal(setRes.channelId, "-100999888777");
    assert.equal(store.get("telegramBotSessions/700555").autoPostChannelId, "-100999888777");

    // 2. User sends video/link -> automatically posted to channel (-100999888777) without reply_to_message_id
    const postRes = await handleTelegramUpdate({
      update: {
        message: {
          message_id: 2,
          chat: { id: 700555 },
          from: { id: 700555 },
          text: `https://vidvora-share-bot.onrender.com/share?link=${srcCode}`,
        },
      },
      db: mockDb,
      storage: null,
      functionBaseUrl: "https://vidvora-share-bot.onrender.com",
      playbackSecret: "sec",
    });
    assert.equal(postRes.ok, true);
    assert.equal(postRes.postedToChannel, "-100999888777");

    const channelPhotoCall = apiCalls.find(
      (c) => c.method === "sendPhoto" && String(c.body.chat_id) === "-100999888777"
    );
    assert.ok(channelPhotoCall, "Expected sendPhoto directly to target channel");
    assert.equal(channelPhotoCall.body.reply_to_message_id, undefined);
    assert.ok(channelPhotoCall.body.caption.includes(postRes.rawToken));

    const dmConfirmEdit = apiCalls.find(
      (c) => c.method === "editMessageText" && String(c.body.chat_id) === "700555" && String(c.body.text).includes("Channel par post ho gaya")
    );
    assert.ok(dmConfirmEdit, "Expected DM confirmation message");

    // 3. User disables auto-post with /clearchannel
    const clearRes = await handleTelegramUpdate({
      update: {
        message: {
          message_id: 3,
          chat: { id: 700555 },
          from: { id: 700555 },
          text: "/clearchannel",
        },
      },
      db: mockDb,
      storage: null,
      functionBaseUrl: "https://vidvora-share-bot.onrender.com",
      playbackSecret: "sec",
    });
    assert.equal(clearRes.ok, true);
    assert.equal(clearRes.action, "channel_cleared");
    assert.equal(store.get("telegramBotSessions/700555").autoPostChannelId, "");
  } finally {
    global.fetch = origFetch;
  }
});

test("handleTelegramUpdate uploads local JPEG thumbnail via multipart FormData and retries HTTP thumbnail URLs as multipart", async () => {
  const { handleTelegramUpdate, hashToken } = require("./telegramBot");

  const srcCode = "GM-HTTPTHUMB1";
  const srcHash = hashToken(srcCode);
  const store = new Map([
    ["appConfig/telegramBot", { botToken: "123:THUMBTEST" }],
    ["members/mem_th_1", { memberId: "mem_th_1", name: "Thumb Creator", role: "CREATOR", isActive: true }],
    [
      "telegramBotSessions/800444",
      {
        telegramUserId: "800444",
        role: "CREATOR",
        memberId: "mem_th_1",
        memberName: "Thumb Creator",
        uploadCount: 0,
      },
    ],
    ["accessLinks/link_th_1", { linkId: "link_th_1", videoId: "vid_th_1", tokenHash: srcHash, isActive: true }],
    [
      "videos/vid_th_1",
      {
        videoId: "vid_th_1",
        title: "Thumb Fallback Video",
        storagePath: "https://vidvora-share-bot.onrender.com/streamPublicVideo?videoId=vid_th_1&sig=abc",
        thumbnailPath: "https://cdn.example.com/slow_thumb.jpg",
        isActive: true,
      },
    ],
  ]);

  const mockDb = {
    collection: (col) => {
      const makeQuery = (filters = []) => ({
        where: (field, op, val) => makeQuery([...filters, { field, val }]),
        limit: () => makeQuery(filters),
        get: async () => {
          const matches = [];
          for (const [k, v] of store.entries()) {
            if (k.startsWith(`${col}/`) && filters.every((f) => v[f.field] === f.val)) {
              matches.push({ exists: true, id: k.split("/")[1], data: () => v });
            }
          }
          return { empty: matches.length === 0, docs: matches };
        },
      });
      return {
        doc: (id) => ({
          get: async () => {
            const v = store.get(`${col}/${id}`);
            return v ? { exists: true, id, data: () => v } : { exists: false, id };
          },
          set: async (data, opts) => {
            const prev = opts?.merge ? store.get(`${col}/${id}`) || {} : {};
            store.set(`${col}/${id}`, { ...prev, ...data });
          },
        }),
        where: (field, op, val) => makeQuery([{ field, val }]),
      };
    },
  };

  let jsonPhotoFailedOnce = false;
  let multipartPhotoUploaded = false;
  const origFetch = global.fetch;
  global.fetch = async (url, init = {}) => {
    const urlStr = String(url);
    if (urlStr === "https://cdn.example.com/slow_thumb.jpg") {
      return {
        ok: true,
        status: 200,
        headers: new Map([["content-type", "image/jpeg"]]),
        arrayBuffer: async () => Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46]),
      };
    }
    if (urlStr.endsWith("/sendPhoto")) {
      if (typeof init.body === "string") {
        // Simulate Telegram rejecting direct HTTP URL fetch
        jsonPhotoFailedOnce = true;
        return {
          ok: false,
          status: 400,
          json: async () => ({ ok: false, description: "Bad Request: failed to get HTTP URL content" }),
        };
      }
      // Multipart FormData retry must succeed!
      multipartPhotoUploaded = true;
      return { ok: true, status: 200, json: async () => ({ ok: true, result: { message_id: 901 } }) };
    }
    return { ok: true, status: 200, json: async () => ({ ok: true, result: { message_id: 900 } }) };
  };

  try {
    const res = await handleTelegramUpdate({
      update: {
        message: {
          message_id: 10,
          chat: { id: 800444 },
          from: { id: 800444 },
          text: `https://vidvora-share-bot.onrender.com/share?link=${srcCode}`,
        },
      },
      db: mockDb,
      storage: null,
      functionBaseUrl: "https://vidvora-share-bot.onrender.com",
      playbackSecret: "sec",
    });

    assert.equal(res.ok, true);
    assert.equal(jsonPhotoFailedOnce, true);
    assert.equal(multipartPhotoUploaded, true);
  } finally {
    global.fetch = origFetch;
  }
});

test("link convert reuses 5-second clip file_id even on text-only link, avoids base64 duplication, and /shareThumb serves from Telegram cloud", async () => {
  const { handleTelegramUpdate } = require("./telegramBot");
  const { handleShareThumbnailRequest } = require("./sharePreview");
  const videosStore = new Map();
  const linksStore = new Map();
  const sessionsStore = new Map();
  const membersStore = new Map();
  const appConfigStore = new Map([["telegramBot", { botToken: "123456:TEST_TOKEN_CLIP" }]]);

  const srcCode = "GM-CLIP5S01";
  const srcVideoId = "vid_main_clip_1";
  const legacyCode = "GM-LEGB6401";
  const legacyVideoId = "vid_legacy_b64_1";

  videosStore.set(srcVideoId, {
    videoId: srcVideoId,
    title: "Main Channel Hit",
    description: "Original",
    storagePath: "https://vidvora-share-bot.onrender.com/streamPublicVideo?videoId=vid_main_clip_1&kind=video&sig=abc",
    thumbnailPath: "https://vidvora-share-bot.onrender.com/shareThumb?videoId=vid_main_clip_1",
    tgPreviewClipFileId: "BAAC_5SEC_PREVIEW_CLIP_ID",
    tgThumbFileId: "AgAC_MAIN_TG_THUMB_ID",
    isActive: true,
    uploadedBy: "admin",
  });
  linksStore.set("lnk_clip_1", {
    linkId: "lnk_clip_1",
    videoId: srcVideoId,
    tokenHash: hashToken(srcCode),
    isActive: true,
  });

  videosStore.set(legacyVideoId, {
    videoId: legacyVideoId,
    title: "Legacy Base64 Video",
    description: "Old",
    storagePath: "https://vidvora-share-bot.onrender.com/streamPublicVideo?videoId=vid_legacy_b64_1&kind=video&sig=xyz",
    thumbnailPath: "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AVN//2Q==",
    isActive: true,
    uploadedBy: "admin",
  });
  linksStore.set("lnk_leg_1", {
    linkId: "lnk_leg_1",
    videoId: legacyVideoId,
    tokenHash: hashToken(legacyCode),
    isActive: true,
  });

  membersStore.set("mem_conv_1", {
    memberId: "mem_conv_1",
    name: "Meraj",
    role: "CREATOR",
    isActive: true,
  });
  sessionsStore.set("900555", {
    telegramUserId: "900555",
    memberId: "mem_conv_1",
    memberName: "Meraj",
    role: "CREATOR",
    uploadCount: 0,
  });

  const makeDoc = (store, id) => ({
    async get() {
      const val = store.get(id);
      return { exists: Boolean(val), id, data: () => (val ? { ...val } : undefined) };
    },
    async set(data, opts) {
      const prev = opts?.merge ? store.get(id) || {} : {};
      store.set(id, { ...prev, ...data });
    },
    async delete() {
      store.delete(id);
    },
  });

  const mockDb = {
    collection(name) {
      const map =
        name === "videos"
          ? videosStore
          : name === "accessLinks"
          ? linksStore
          : name === "members"
          ? membersStore
          : name === "telegramBotSessions"
          ? sessionsStore
          : name === "appConfig"
          ? appConfigStore
          : new Map();
      const makeQuery = (filters = []) => ({
        where: (field, op, value) => makeQuery([...filters, { field, op, value }]),
        limit: () => makeQuery(filters),
        async get() {
          const docs = [];
          for (const [id, data] of map.entries()) {
            if (filters.every((f) => f.op === "==" && data[f.field] === f.value)) {
              docs.push({ exists: true, id, data: () => ({ ...data }) });
            }
          }
          return { empty: docs.length === 0, docs };
        },
      });
      return {
        doc: (id) => makeDoc(map, id),
        document: (id) => makeDoc(map, id),
        where: (field, op, value) => makeQuery([{ field, op, value }]),
      };
    },
  };

  const sentCalls = [];
  const fakeJpegBytes = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01, 0xff, 0xd9]);
  const origFetch = global.fetch;
  global.fetch = async (url, init = {}) => {
    const sUrl = String(url);
    if (sUrl.includes("/getFile")) {
      return {
        ok: true,
        status: 200,
        json: async () => ({ ok: true, result: { file_path: "photos/main_thumb.jpg" } }),
      };
    }
    if (sUrl.includes("/file/bot")) {
      return {
        ok: true,
        status: 200,
        headers: { get: (k) => (k.toLowerCase() === "content-type" ? "image/jpeg" : null) },
        arrayBuffer: async () => fakeJpegBytes.buffer.slice(fakeJpegBytes.byteOffset, fakeJpegBytes.byteOffset + fakeJpegBytes.byteLength),
      };
    }
    if (sUrl.includes("/sendVideo")) {
      const body = typeof init.body === "string" ? JSON.parse(init.body) : {};
      sentCalls.push({ method: "sendVideo", video: body.video });
      return {
        ok: true,
        status: 200,
        json: async () => ({
          ok: true,
          result: { message_id: 950, video: { file_id: body.video || "BAAC_5SEC_PREVIEW_CLIP_ID" } },
        }),
      };
    }
    if (sUrl.includes("/sendPhoto")) {
      sentCalls.push({ method: "sendPhoto" });
      return {
        ok: true,
        status: 200,
        json: async () => ({
          ok: true,
          result: { message_id: 951, photo: [{ file_id: "AgAC_POSTED_PHOTO_ID" }] },
        }),
      };
    }
    return { ok: true, status: 200, json: async () => ({ ok: true, result: { message_id: 949 } }) };
  };

  const prevBotToken = process.env.TELEGRAM_BOT_TOKEN;
  process.env.TELEGRAM_BOT_TOKEN = "123456:TEST_TOKEN_CLIP";
  try {
    // 1. Creator converts via TEXT-ONLY link -> must still send 5-sec clip via sendVideo!
    const res1 = await handleTelegramUpdate({
      update: {
        message: {
          message_id: 21,
          chat: { id: 900555 },
          from: { id: 900555 },
          text: `https://vidvora-share-bot.onrender.com/share?link=${srcCode}`,
        },
      },
      db: mockDb,
      storage: null,
      functionBaseUrl: "https://vidvora-share-bot.onrender.com",
      playbackSecret: "sec",
    });
    assert.equal(res1.ok, true);
    assert.ok(sentCalls.some((c) => c.method === "sendVideo" && c.video === "BAAC_5SEC_PREVIEW_CLIP_ID"));
    const convDoc1 = videosStore.get(res1.videoId);
    assert.equal(convDoc1.tgPreviewClipFileId, "BAAC_5SEC_PREVIEW_CLIP_ID");
    assert.equal(convDoc1.tgThumbFileId, "AgAC_MAIN_TG_THUMB_ID");
    assert.ok(!String(convDoc1.thumbnailPath || "").startsWith("data:image/"));

    // 2. Creator converts a LEGACY base64 video -> must NOT duplicate base64 into converted doc!
    const res2 = await handleTelegramUpdate({
      update: {
        message: {
          message_id: 22,
          chat: { id: 900555 },
          from: { id: 900555 },
          text: `https://vidvora-share-bot.onrender.com/share?link=${legacyCode}`,
        },
      },
      db: mockDb,
      storage: null,
      functionBaseUrl: "https://vidvora-share-bot.onrender.com",
      playbackSecret: "sec",
    });
    assert.equal(res2.ok, true);
    const convDoc2 = videosStore.get(res2.videoId);
    assert.ok(!String(convDoc2.thumbnailPath || "").startsWith("data:image/"));
    assert.ok(String(convDoc2.thumbnailPath || "").includes("/shareThumb?videoId="));

    // 3. /shareThumb serves JPEG directly from Telegram cloud using tgThumbFileId!
    let servedStatus = 0;
    let servedBody = Buffer.alloc(0);
    const fakeRes = {
      writeHead(code) {
        servedStatus = code;
      },
      end(buf) {
        servedBody = Buffer.isBuffer(buf) ? buf : Buffer.from(buf || "");
      },
    };
    await handleShareThumbnailRequest({
      videoId: res1.videoId,
      method: "GET",
      res: fakeRes,
      db: mockDb,
      storage: null,
      resolveBotConfig: async () => ({ botToken: "123456:TEST_TOKEN_CLIP", apiBase: "https://api.telegram.org" }),
    });
    assert.equal(servedStatus, 200);
    assert.deepEqual(servedBody, fakeJpegBytes);
  } finally {
    global.fetch = origFetch;
    if (prevBotToken === undefined) delete process.env.TELEGRAM_BOT_TOKEN;
    else process.env.TELEGRAM_BOT_TOKEN = prevBotToken;
  }
});

test("creator Telegram bot allows editing custom Header & Footer via inline buttons, link replies, and commands, and applies them to video posts", async () => {
  const { handleTelegramUpdate } = require("./telegramBot");

  const sourceCode = "GM-HDRFTR99";
  const sourceHash = hashToken(sourceCode);
  const sessionsStore = new Map([
    [
      "777111",
      {
        telegramUserId: "777111",
        memberId: "creator_hdr",
        memberName: "Aman Creator",
        memberEmail: "aman@vidvora.com",
        role: "MEMBER",
      },
    ],
  ]);
  const membersStore = new Map([
    [
      "creator_hdr",
      {
        memberId: "creator_hdr",
        name: "Aman Creator",
        email: "aman@vidvora.com",
        role: "MEMBER",
        isActive: true,
      },
    ],
  ]);
  const videosStore = new Map([
    [
      "vid_src_hdr",
      {
        videoId: "vid_src_hdr",
        title: "Sample Clip",
        description: "desc",
        storagePath: "https://cdn.example.com/sample.mp4",
        tgPreviewClipFileId: "BAAC_PREVIEW_HDR",
        tgThumbFileId: "AgAC_THUMB_HDR",
        isActive: true,
      },
    ],
  ]);
  const linksStore = new Map([
    [
      "link_src_hdr",
      {
        linkId: "link_src_hdr",
        tokenHash: sourceHash,
        videoId: "vid_src_hdr",
        isActive: true,
      },
    ],
  ]);

  const makeStoreCol = (map) => ({
    doc: (id) => ({
      get: async () => ({ exists: map.has(id), id, data: () => map.get(id) }),
      set: async (data, opts) => {
        const prev = opts?.merge && map.has(id) ? map.get(id) : {};
        map.set(id, { ...prev, ...data });
      },
    }),
    where: (field, _op, val) => ({
      limit: () => ({
        get: async () => {
          const docs = [];
          for (const [id, item] of map.entries()) {
            if (item && item[field] === val) {
              docs.push({ id, exists: true, data: () => item });
            }
          }
          return { empty: docs.length === 0, docs };
        },
      }),
    }),
  });

  const mockDb = {
    collection: (name) => {
      if (name === "telegramBotSessions") return makeStoreCol(sessionsStore);
      if (name === "members") return makeStoreCol(membersStore);
      if (name === "videos") return makeStoreCol(videosStore);
      if (name === "accessLinks") return makeStoreCol(linksStore);
      return {
        doc: () => ({ get: async () => ({ exists: false }), set: async () => {} }),
        where: () => ({ limit: () => ({ get: async () => ({ empty: true, docs: [] }) }) }),
      };
    },
  };

  const apiCalls = [];
  const origFetch = global.fetch;
  const prevBotToken = process.env.TELEGRAM_BOT_TOKEN;
  process.env.TELEGRAM_BOT_TOKEN = "123456:TEST_HDR_TOKEN";
  global.fetch = async (url, init = {}) => {
    const method = String(url).split("/").pop();
    const body = typeof init.body === "string" ? JSON.parse(init.body) : {};
    apiCalls.push({ method, body });
    return { ok: true, json: async () => ({ ok: true, result: { message_id: apiCalls.length + 10 } }) };
  };

  try {
    // 0. /start returns clean welcome without Channel Auto-Post or 3rd/4th steps and shows bottom persistent keyboard options
    const r0 = await handleTelegramUpdate({
      update: { message: { message_id: 0, chat: { id: 777111 }, from: { id: 777111 }, text: "/start" } },
      db: mockDb,
      storage: null,
      functionBaseUrl: "https://vidvora-share-bot.onrender.com",
      playbackSecret: "sec",
    });
    assert.equal(r0.ok, true);
    assert.equal(r0.action, "help");
    const startCall = apiCalls.find((c) => c.method === "sendMessage");
    assert.ok(!startCall.body.text.includes("Channel Auto-Post"));
    assert.ok(!startCall.body.text.includes("3️⃣"));
    assert.ok(!startCall.body.text.includes("4️⃣"));
    assert.ok(!startCall.body.text.includes("/setchannel"));
    assert.ok(!startCall.body.text.includes("Commands:"));
    assert.equal(startCall.body.reply_markup?.is_persistent, true);
    assert.ok(startCall.body.reply_markup?.keyboard?.flat().some((b) => b.text === "✏️ Edit Header"));
    assert.ok(startCall.body.reply_markup?.keyboard?.flat().some((b) => b.text === "✏️ Edit Footer"));
    assert.ok(startCall.body.reply_markup?.keyboard?.flat().some((b) => b.text === "⚙️ Header / Footer Settings"));

    // 1. Tapping bottom button "⚙️ Header / Footer Settings" opens interactive settings menu with inline buttons (no slash command needed!)
    const r1 = await handleTelegramUpdate({
      update: {
        message: { message_id: 1, chat: { id: 777111 }, from: { id: 777111 }, text: "⚙️ Header / Footer Settings" },
      },
      db: mockDb,
      storage: null,
      functionBaseUrl: "https://vidvora-share-bot.onrender.com",
      playbackSecret: "sec",
    });
    assert.equal(r1.ok, true);
    assert.equal(r1.action, "caption_settings");
    const menuCall = apiCalls[apiCalls.length - 1];
    assert.ok(menuCall.body.reply_markup?.inline_keyboard?.flat().some((b) => b.callback_data === "caption:edit_header"));
    assert.ok(menuCall.body.reply_markup?.inline_keyboard?.flat().some((b) => b.callback_data === "caption:edit_footer"));

    // 1b. Tapping bottom button "📊 My Status" works without commands and hides Auto-Post Channel
    const r1b = await handleTelegramUpdate({
      update: { message: { message_id: 101, chat: { id: 777111 }, from: { id: 777111 }, text: "📊 My Status" } },
      db: mockDb,
      storage: null,
      functionBaseUrl: "https://vidvora-share-bot.onrender.com",
      playbackSecret: "sec",
    });
    assert.equal(r1b.ok, true);
    assert.equal(r1b.action, "status");
    const statusCall = apiCalls[apiCalls.length - 1];
    assert.ok(!statusCall.body.text.includes("Auto-Post Channel"));
    assert.equal(statusCall.body.reply_markup?.is_persistent, true);

    // 2. Click "Edit Header" inline button -> sets pendingInputAction = "set_header"
    const r2 = await handleTelegramUpdate({
      update: {
        callback_query: {
          id: "cb_1",
          from: { id: 777111 },
          data: "caption:edit_header",
          message: { message_id: 11, chat: { id: 777111 } },
        },
      },
      db: mockDb,
      storage: null,
      functionBaseUrl: "https://vidvora-share-bot.onrender.com",
      playbackSecret: "sec",
    });
    assert.equal(r2.ok, true);
    assert.equal(r2.action, "prompt_header");
    assert.equal(sessionsStore.get("777111").pendingInputAction, "set_header");

    // 3. Creator replies with a Header containing a custom Telegram link (must NOT trigger URL rejection!)
    const r3 = await handleTelegramUpdate({
      update: {
        message: {
          message_id: 2,
          chat: { id: 777111 },
          from: { id: 777111 },
          text: "🔥 Aman Movies HD\nhttps://t.me/AmanMoviesOfficial",
        },
      },
      db: mockDb,
      storage: null,
      functionBaseUrl: "https://vidvora-share-bot.onrender.com",
      playbackSecret: "sec",
    });
    assert.equal(r3.ok, true);
    assert.equal(r3.action, "header_updated");
    assert.equal(sessionsStore.get("777111").customCaptionHeader, "🔥 Aman Movies HD\nhttps://t.me/AmanMoviesOfficial");
    assert.equal(membersStore.get("creator_hdr").customCaptionHeader, "🔥 Aman Movies HD\nhttps://t.me/AmanMoviesOfficial");

    // 4. Direct /footer command sets custom Footer link
    const r4 = await handleTelegramUpdate({
      update: {
        message: {
          message_id: 3,
          chat: { id: 777111 },
          from: { id: 777111 },
          text: "/footer 📢 Join Backup: https://t.me/AmanBackupChannel",
        },
      },
      db: mockDb,
      storage: null,
      functionBaseUrl: "https://vidvora-share-bot.onrender.com",
      playbackSecret: "sec",
    });
    assert.equal(r4.ok, true);
    assert.equal(r4.action, "footer_updated");
    assert.equal(sessionsStore.get("777111").customCaptionFooter, "📢 Join Backup: https://t.me/AmanBackupChannel");

    // 5. Convert a VidVora link -> sendVideo caption uses custom Header & Footer and NO VideoKaiseDekhe_ar!
    const r5 = await handleTelegramUpdate({
      update: {
        message: {
          message_id: 4,
          chat: { id: 777111 },
          from: { id: 777111 },
          text: `https://vidvora-share-bot.onrender.com/share?link=${sourceCode}`,
        },
      },
      db: mockDb,
      storage: null,
      functionBaseUrl: "https://vidvora-share-bot.onrender.com",
      playbackSecret: "sec",
    });
    assert.equal(r5.ok, true);
    const sendVideoCall = apiCalls.find((c) => c.method === "sendVideo");
    assert.ok(sendVideoCall, "Expected sendVideo with custom caption");
    assert.ok(sendVideoCall.body.caption.includes("Aman Movies HD"));
    assert.ok(sendVideoCall.body.caption.includes("https://t.me/AmanMoviesOfficial"));
    assert.ok(sendVideoCall.body.caption.includes("https://t.me/AmanBackupChannel"));
    assert.ok(!sendVideoCall.body.caption.includes("VideoKaiseDekhe"));
    assert.ok(!sendVideoCall.body.caption.includes("How to play video"));

    // 6. Reset Header to default via inline callback button, Remove Footer completely via /removefooter, and Reset Footer back to default via /resetfooter
    const r6 = await handleTelegramUpdate({
      update: {
        callback_query: {
          id: "cb_2",
          from: { id: 777111 },
          data: "caption:reset_header",
          message: { message_id: 11, chat: { id: 777111 } },
        },
      },
      db: mockDb,
      storage: null,
      functionBaseUrl: "https://vidvora-share-bot.onrender.com",
      playbackSecret: "sec",
    });
    assert.equal(r6.ok, true);
    assert.equal(r6.action, "header_reset");
    assert.equal(sessionsStore.get("777111").customCaptionHeader, "");

    const r7 = await handleTelegramUpdate({
      update: {
        message: { message_id: 5, chat: { id: 777111 }, from: { id: 777111 }, text: "/removefooter" },
      },
      db: mockDb,
      storage: null,
      functionBaseUrl: "https://vidvora-share-bot.onrender.com",
      playbackSecret: "sec",
    });
    assert.equal(r7.ok, true);
    assert.equal(r7.action, "footer_removed");
    assert.equal(sessionsStore.get("777111").customCaptionFooter, CAPTION_BLOCK_NONE);

    // Converting a link with footer removed must NOT include default VideoKaiseDekhe_ar!
    const r8 = await handleTelegramUpdate({
      update: {
        message: {
          message_id: 6,
          chat: { id: 777111 },
          from: { id: 777111 },
          text: `https://vidvora-share-bot.onrender.com/share?link=${sourceCode}`,
        },
      },
      db: mockDb,
      storage: null,
      functionBaseUrl: "https://vidvora-share-bot.onrender.com",
      playbackSecret: "sec",
    });
    assert.equal(r8.ok, true);
    const sendVideoCalls = apiCalls.filter((c) => c.method === "sendVideo");
    const removedFooterPost = sendVideoCalls[sendVideoCalls.length - 1];
    assert.ok(!removedFooterPost.body.caption.includes("VideoKaiseDekhe"));
    assert.ok(removedFooterPost.body.caption.includes("VidVora — Watch Video"));

    // Resetting footer restores default How to play video + VideoKaiseDekhe_ar link
    const r9 = await handleTelegramUpdate({
      update: {
        message: { message_id: 7, chat: { id: 777111 }, from: { id: 777111 }, text: "/resetfooter" },
      },
      db: mockDb,
      storage: null,
      functionBaseUrl: "https://vidvora-share-bot.onrender.com",
      playbackSecret: "sec",
    });
    assert.equal(r9.ok, true);
    assert.equal(r9.action, "footer_reset");
    assert.equal(sessionsStore.get("777111").customCaptionFooter, "");
  } finally {
    global.fetch = origFetch;
    if (prevBotToken === undefined) delete process.env.TELEGRAM_BOT_TOKEN;
    else process.env.TELEGRAM_BOT_TOKEN = prevBotToken;
  }
});

test("MTProto session persistence saves StringSession on first login and reuses it on subsequent boots without re-calling auth.ImportBotAuthorization", async () => {
  assert.equal(
    parseTelegramFloodWaitSeconds(new Error("A wait of 1754 seconds is required (caused by auth.ImportBotAuthorization)")),
    1754
  );
  assert.equal(parseTelegramFloodWaitSeconds({ errorMessage: "FLOOD_WAIT_900" }), 900);
  assert.equal(parseTelegramFloodWaitSeconds({ seconds: 42 }), 42);

  const appConfigStore = new Map();
  const mockDb = {
    collection: (name) => {
      if (name === "appConfig") {
        return {
          doc: (id) => ({
            get: async () => ({ exists: appConfigStore.has(id), id, data: () => appConfigStore.get(id) }),
            set: async (data, opts) => {
              const prev = opts?.merge && appConfigStore.has(id) ? appConfigStore.get(id) : {};
              appConfigStore.set(id, { ...prev, ...data });
            },
          }),
        };
      }
      return { doc: () => ({ get: async () => ({ exists: false }) }) };
    },
  };

  let startCalls = 0;
  let connectCalls = 0;

  class FakeStringSession {
    constructor(val = "") {
      this._val = String(val || "");
    }
    save() {
      return this._val || "PERSISTED_GRAMJS_SESSION_STRING_V1";
    }
  }

  class FakeTelegramClient {
    constructor(session) {
      this.session = session;
      this.disconnected = false;
    }
    setLogLevel() {}
    async connect() {
      connectCalls += 1;
    }
    async checkAuthorization() {
      return Boolean(this.session && this.session._val === "PERSISTED_GRAMJS_SESSION_STRING_V1");
    }
    async start() {
      startCalls += 1;
      this.session._val = "PERSISTED_GRAMJS_SESSION_STRING_V1";
    }
    async getMessages() {
      return [
        {
          id: 55,
          media: {
            document: {
              id: 1001n,
              accessHash: 2002n,
              fileReference: Buffer.from([1, 2, 3]),
              size: 16,
              mimeType: "video/mp4",
            },
          },
        },
      ];
    }
    iterDownload() {
      // Simulate real GramJS DirectDownloadIter: implements [Symbol.asyncIterator]() ONLY (no direct .next())
      return {
        async *[Symbol.asyncIterator]() {
          yield Buffer.from("0123456789abcdef");
        },
      };
    }
  }

  const fakeTelegramLib = {
    TelegramClient: FakeTelegramClient,
    StringSession: FakeStringSession,
    Api: {
      InputDocumentFileLocation: class {
        constructor(args) {
          Object.assign(this, args);
        }
      },
    },
    bigInt: (v) => BigInt(v),
  };

  const prevEnv = {
    botToken: process.env.TELEGRAM_BOT_TOKEN,
    apiId: process.env.TELEGRAM_API_ID,
    apiHash: process.env.TELEGRAM_API_HASH,
    mtprotoSess: process.env.TELEGRAM_MTPROTO_SESSION,
  };
  process.env.TELEGRAM_BOT_TOKEN = "999999:MTPROTO_PERSIST_TEST";
  process.env.TELEGRAM_API_ID = "123456";
  process.env.TELEGRAM_API_HASH = "abcdef1234567890abcdef1234567890";
  delete process.env.TELEGRAM_MTPROTO_SESSION;

  const makeMockRes = () => {
    const chunks = [];
    return {
      status: 0,
      headers: {},
      headersSent: false,
      writableEnded: false,
      writeHead(code, headers) {
        this.status = code;
        this.headers = headers || {};
        this.headersSent = true;
      },
      write(chunk) {
        chunks.push(Buffer.from(chunk));
        return true;
      },
      end(chunk) {
        if (chunk) chunks.push(Buffer.from(chunk));
        this.writableEnded = true;
      },
      getBody() {
        return Buffer.concat(chunks).toString("utf8");
      },
    };
  };

  try {
    // 1st boot: DB has no saved session -> calls start() ONCE and persists StringSession to appConfig
    const res1 = makeMockRes();
    await streamTelegramMtprotoWithRange({
      videoData: { tgChatId: "12345", tgMessageId: 55, tgFileSize: 16, tgMimeType: "video/mp4" },
      method: "GET",
      rangeHeader: "",
      res: res1,
      db: mockDb,
      telegramLibOverride: fakeTelegramLib,
    });
    assert.equal(res1.status, 200);
    assert.equal(res1.getBody(), "0123456789abcdef");
    assert.equal(startCalls, 1);
    assert.equal(
      appConfigStore.get("telegramMtprotoSession")?.sessionString,
      "PERSISTED_GRAMJS_SESSION_STRING_V1"
    );

    // 2nd boot (simulating Render redeploy/restart): restores saved StringSession via connect() with ZERO start() calls!
    const res2 = makeMockRes();
    await streamTelegramMtprotoWithRange({
      videoData: { tgChatId: "12345", tgMessageId: 55, tgFileSize: 16, tgMimeType: "video/mp4" },
      method: "GET",
      rangeHeader: "",
      res: res2,
      db: mockDb,
      telegramLibOverride: fakeTelegramLib,
    });
    assert.equal(res2.status, 200);
    assert.equal(res2.getBody(), "0123456789abcdef");
    assert.equal(startCalls, 1, "Must NOT call client.start() / auth.ImportBotAuthorization again!");
    assert.equal(connectCalls, 1, "Must reconnect using persisted StringSession!");
  } finally {
    if (prevEnv.botToken === undefined) delete process.env.TELEGRAM_BOT_TOKEN;
    else process.env.TELEGRAM_BOT_TOKEN = prevEnv.botToken;
    if (prevEnv.apiId === undefined) delete process.env.TELEGRAM_API_ID;
    else process.env.TELEGRAM_API_ID = prevEnv.apiId;
    if (prevEnv.apiHash === undefined) delete process.env.TELEGRAM_API_HASH;
    else process.env.TELEGRAM_API_HASH = prevEnv.apiHash;
    if (prevEnv.mtprotoSess === undefined) delete process.env.TELEGRAM_MTPROTO_SESSION;
    else process.env.TELEGRAM_MTPROTO_SESSION = prevEnv.mtprotoSess;
  }
});

test("multi-GB (3.8 GB) videos handle BigInteger doc.size, high-offset Range seek, idle socket reconnect, and MKV/TS document uploads cleanly", async () => {
  const { toSafeByteLength, inferVideoMimeType, isAllowedTelegramVideoMessage } = require("./telegramBot");

  // 1. GramJS BigInteger object (> 2 GB / 3.8 GB) must NEVER become NaN
  const fakeBigInt38Gb = { toJSNumber: () => 3_800_000_000, toString: () => "3800000000" };
  assert.equal(toSafeByteLength(fakeBigInt38Gb), 3_800_000_000);
  assert.equal(toSafeByteLength(4_294_967_296n), 4_294_967_296);
  assert.equal(toSafeByteLength("6442450944"), 6_442_450_944);

  // 2. MKV / TS / MOV / AVI documents sent with generic application/octet-stream are accepted & typed properly
  assert.equal(
    isAllowedTelegramVideoMessage({
      document: { file_id: "doc_mkv", file_name: "Movie_4K_3.8GB.mkv", mime_type: "application/octet-stream" },
    }),
    true
  );
  assert.equal(
    isAllowedTelegramVideoMessage({
      document: { file_id: "doc_ts", file_name: "Live_2GB.ts", mime_type: "application/octet-stream" },
    }),
    true
  );
  assert.equal(inferVideoMimeType("Movie_4K_3.8GB.mkv", "application/octet-stream"), "video/x-matroska");
  assert.equal(inferVideoMimeType("Clip.webm", ""), "video/webm");

  // 3. Seeking to byte 3,500,000,000 in a 3.8 GB file + recovering from an idle socket disconnect before headers
  let reconnectCount = 0;
  let iterAttempts = 0;
  let capturedOffset = null;
  const appConfigStore = new Map([
    [
      "telegramMtprotoSession",
      { sessionString: "SAVED_MULTI_GB_SESSION" },
    ],
  ]);
  const videosBackfill = new Map();
  const mockDb = {
    collection: (name) => ({
      doc: (id) => ({
        get: async () =>
          name === "appConfig"
            ? { exists: appConfigStore.has(id), id, data: () => appConfigStore.get(id) }
            : { exists: false },
        set: async (data) => {
          if (name === "videos") videosBackfill.set(id, data);
          if (name === "appConfig") appConfigStore.set(id, data);
        },
      }),
    }),
  };

  class FakeSession {
    constructor(v = "") {
      this._v = v;
    }
    save() {
      return this._v;
    }
  }
  class FakeClient {
    constructor(session) {
      this.session = session;
    }
    setLogLevel() {}
    async connect() {
      reconnectCount += 1;
    }
    async checkAuthorization() {
      return true;
    }
    async getMessages() {
      return [
        {
          id: 99,
          media: {
            document: {
              id: 500n,
              accessHash: 600n,
              fileReference: Buffer.from([9, 8]),
              size: fakeBigInt38Gb,
            },
          },
        },
      ];
    }
    iterDownload(opts) {
      capturedOffset = opts.offset;
      iterAttempts += 1;
      const attempt = iterAttempts;
      return {
        async *[Symbol.asyncIterator]() {
          if (attempt === 1) {
            throw new Error("Connection closed: Not connected");
          }
          // Aligned offset = floor(3500000000 / 524288) * 524288 = 3499622400 -> skipBytes = 377600
          const buf = Buffer.alloc(524288, 0x41);
          Buffer.from("VIDVORA_38GB_OK!").copy(buf, 377600);
          yield buf;
        },
      };
    }
  }

  const prevEnv = {
    botToken: process.env.TELEGRAM_BOT_TOKEN,
    apiId: process.env.TELEGRAM_API_ID,
    apiHash: process.env.TELEGRAM_API_HASH,
  };
  process.env.TELEGRAM_BOT_TOKEN = "777:MULTI_GB_TEST";
  process.env.TELEGRAM_API_ID = "123456";
  process.env.TELEGRAM_API_HASH = "abcdef1234567890abcdef1234567890";

  const chunks = [];
  const mockRes = {
    status: 0,
    headers: {},
    headersSent: false,
    writableEnded: false,
    writeHead(code, headers) {
      this.status = code;
      this.headers = headers || {};
      this.headersSent = true;
    },
    write(chunk) {
      chunks.push(Buffer.from(chunk));
      return true;
    },
    end(chunk) {
      if (chunk) chunks.push(Buffer.from(chunk));
      this.writableEnded = true;
    },
  };

  try {
    await streamTelegramMtprotoWithRange({
      videoData: {
        videoId: "vid_38gb",
        title: "Movie_4K_3.8GB.mkv",
        tgChatId: "12345",
        tgMessageId: 99,
        tgFileSize: 0,
        tgMimeType: "application/octet-stream",
      },
      method: "GET",
      rangeHeader: "bytes=3500000000-3500000015",
      res: mockRes,
      db: mockDb,
      telegramLibOverride: {
        TelegramClient: FakeClient,
        StringSession: FakeSession,
        Api: { InputDocumentFileLocation: class {} },
        bigInt: (v) => BigInt(v),
      },
    });
    assert.equal(mockRes.status, 206);
    assert.equal(mockRes.headers["Content-Range"], "bytes 3500000000-3500000015/3800000000");
    assert.equal(mockRes.headers["Content-Type"], "video/x-matroska");
    assert.equal(Buffer.concat(chunks).toString("utf8"), "VIDVORA_38GB_OK!");
    assert.equal(iterAttempts, 2, "Must auto-reconnect and retry after idle socket disconnect!");
    assert.equal(reconnectCount, 2);
    assert.equal(capturedOffset, 3499622400n);
    assert.equal(videosBackfill.get("vid_38gb")?.tgFileSize, 3_800_000_000);
  } finally {
    if (prevEnv.botToken === undefined) delete process.env.TELEGRAM_BOT_TOKEN;
    else process.env.TELEGRAM_BOT_TOKEN = prevEnv.botToken;
    if (prevEnv.apiId === undefined) delete process.env.TELEGRAM_API_ID;
    else process.env.TELEGRAM_API_ID = prevEnv.apiId;
    if (prevEnv.apiHash === undefined) delete process.env.TELEGRAM_API_HASH;
    else process.env.TELEGRAM_API_HASH = prevEnv.apiHash;
  }
});







