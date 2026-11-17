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
  assert.ok(caption.includes(`${SHARE_LINK_BASE_URL}${token}`));
  assert.ok(caption.includes(`vidvora://watch?link=${token}`));
  assert.ok(caption.includes("5s"));
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
