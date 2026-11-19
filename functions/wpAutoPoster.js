const crypto = require("crypto");
const {
  SHARE_LINK_BASE_URL,
  generateAccessToken,
  hashToken,
  resolveBotRuntimeConfig,
  telegramApiJson,
  isUnsupportedThirdPartyHostUrl,
  buildShareMessageCaption,
} = require("./telegramBot");

const DEFAULT_POLL_INTERVAL_SECONDS = 300; // 5 minutes
const MIN_POLL_INTERVAL_SECONDS = 60;
const DEFAULT_MAX_POSTS_PER_RUN = 3;
const MAX_POSTS_PER_RUN_LIMIT = 15;
const VIDEO_FILE_EXT_REGEX = /\.(?:mp4|m3u8|webm|mkv|mov|m4v)(?:[?#][^\s"'<>]*)?$/i;
const IMAGE_OR_ASSET_EXT_REGEX = /\.(?:jpg|jpeg|png|gif|webp|svg|ico|css|js|woff2?|ttf|pdf|zip|rar)(?:[?#].*)?$/i;

function decodeHtmlEntities(input) {
  const text = String(input || "");
  if (!text) return "";
  return text
    .replace(/&#(\d+);/g, (_, dec) => {
      const code = Number(dec);
      return Number.isFinite(code) && code > 0 ? String.fromCodePoint(code) : "";
    })
    .replace(/&#x([0-9a-f]+);/gi, (_, hex) => {
      const code = parseInt(hex, 16);
      return Number.isFinite(code) && code > 0 ? String.fromCodePoint(code) : "";
    })
    .replace(/&amp;/gi, "&")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">")
    .replace(/&quot;/gi, '"')
    .replace(/&apos;|&#039;/gi, "'")
    .replace(/&nbsp;/gi, " ")
    .replace(/&ndash;/gi, "–")
    .replace(/&mdash;/gi, "—")
    .replace(/\s+/g, " ")
    .trim();
}

function stripHtmlTags(input) {
  return decodeHtmlEntities(
    String(input || "")
      .replace(/<script[\s\S]*?<\/script>/gi, " ")
      .replace(/<style[\s\S]*?<\/style>/gi, " ")
      .replace(/<[^>]+>/g, " ")
  );
}

function normalizeSiteUrl(rawUrl) {
  const clean = String(rawUrl || "").trim();
  if (!clean) return "";
  const withProto = /^https?:\/\//i.test(clean) ? clean : `https://${clean}`;
  try {
    const parsed = new URL(withProto);
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return "";
    return `${parsed.origin}${parsed.pathname}`.replace(/\/+$/, "");
  } catch (_) {
    return "";
  }
}

function normalizeCandidateUrl(rawUrl, baseSiteUrl = "") {
  const clean = decodeHtmlEntities(String(rawUrl || "").trim())
    .replace(/^['"]+|['"]+$/g, "")
    .replace(/[),.;]+$/g, "")
    .trim();
  if (!clean) return "";
  try {
    const resolved = baseSiteUrl ? new URL(clean, `${baseSiteUrl}/`) : new URL(clean);
    if (resolved.protocol !== "http:" && resolved.protocol !== "https:") return "";
    return resolved.toString();
  } catch (_) {
    return "";
  }
}

function isLikelyPlayableVideoUrl(rawUrl) {
  const url = String(rawUrl || "").trim();
  if (!url || !/^https?:\/\//i.test(url)) return false;
  if (isUnsupportedThirdPartyHostUrl(url)) return false;
  if (IMAGE_OR_ASSET_EXT_REGEX.test(url)) return false;
  if (VIDEO_FILE_EXT_REGEX.test(url)) return true;

  try {
    const parsed = new URL(url);
    const full = `${parsed.pathname}${parsed.search}`.toLowerCase();
    if (
      full.includes(".mp4") ||
      full.includes(".m3u8") ||
      full.includes(".webm") ||
      full.includes(".mkv") ||
      full.includes("streamPublicVideo".toLowerCase()) ||
      full.includes("mime=video") ||
      full.includes("content-type=video")
    ) {
      return true;
    }
  } catch (_) {
    return false;
  }
  return false;
}

function collectStringsFromMetaObject(obj, preferredKeys, { allowGenericKeys = false } = {}) {
  if (!obj || typeof obj !== "object") return [];
  const results = [];
  for (const key of preferredKeys) {
    const val = obj[key];
    if (typeof val === "string" && val.trim()) {
      results.push({ url: val.trim(), explicit: true });
    } else if (Array.isArray(val)) {
      for (const item of val) {
        if (typeof item === "string" && item.trim()) {
          results.push({ url: item.trim(), explicit: true });
        }
      }
    }
  }
  if (allowGenericKeys) {
    // Scan custom ACF/meta keys containing video/stream/mp4/m3u8/download keywords
    for (const [key, val] of Object.entries(obj)) {
      if (preferredKeys.includes(key)) continue;
      if (!/(video|stream|mp4|m3u8|movie|clip|media|download)/i.test(key)) continue;
      if (typeof val === "string" && /^https?:\/\//i.test(val.trim())) {
        results.push({ url: val.trim(), explicit: false });
      }
    }
  }
  return results;
}

/**
 * Extracts the best direct playable video URL from a WordPress REST API post object,
 * RSS item, or custom webhook payload.
 */
function extractVideoUrlFromWpPost(post, siteBaseUrl = "") {
  if (!post || typeof post !== "object") return "";
  const preferredKeys = [
    "videoUrl",
    "video_url",
    "video_link",
    "videoLink",
    "mp4_url",
    "mp4Url",
    "m3u8_url",
    "m3u8Url",
    "stream_url",
    "streamUrl",
    "file_url",
    "download_link",
    "download_url",
    "vidvora_video_url",
  ];

  // 1. Explicit top-level video keys + ACF / post meta fields (never treat WordPress post.link as a video)
  const metaCandidates = [
    ...collectStringsFromMetaObject(post, preferredKeys, { allowGenericKeys: false }),
    ...collectStringsFromMetaObject(post.acf, preferredKeys, { allowGenericKeys: true }),
    ...collectStringsFromMetaObject(post.meta, preferredKeys, { allowGenericKeys: true }),
    ...collectStringsFromMetaObject(post.custom_fields, preferredKeys, { allowGenericKeys: true }),
  ];
  for (const candidate of metaCandidates) {
    const normalized = normalizeCandidateUrl(candidate.url, siteBaseUrl);
    if (!normalized || isUnsupportedThirdPartyHostUrl(normalized) || IMAGE_OR_ASSET_EXT_REGEX.test(normalized)) {
      continue;
    }
    if (candidate.explicit || isLikelyPlayableVideoUrl(normalized)) {
      return normalized;
    }
  }

  // 2. HTML content inspection (<video>, <source>, [video] shortcode, direct media href/text URLs)
  const htmlSources = [
    typeof post.content === "string" ? post.content : post.content?.rendered || "",
    typeof post.excerpt === "string" ? post.excerpt : post.excerpt?.rendered || "",
    typeof post.description === "string" ? post.description : "",
  ].filter(Boolean);

  const combinedHtml = htmlSources.join("\n");
  if (!combinedHtml) return "";

  const htmlPatterns = [
    /<source\b[^>]*\bsrc\s*=\s*["']([^"']+)["']/gi,
    /<video\b[^>]*\bsrc\s*=\s*["']([^"']+)["']/gi,
    /\[video\b[^\]]*\b(?:mp4|m3u8|webm|src)\s*=\s*["']([^"']+)["']/gi,
    /<a\b[^>]*\bhref\s*=\s*["']([^"']+)["']/gi,
    /https?:\/\/[^\s"'<>()]+/gi,
  ];

  for (const regex of htmlPatterns) {
    let match;
    while ((match = regex.exec(combinedHtml)) !== null) {
      const rawCandidate = match[1] || match[0];
      const normalized = normalizeCandidateUrl(rawCandidate, siteBaseUrl);
      if (normalized && isLikelyPlayableVideoUrl(normalized)) {
        return normalized;
      }
    }
  }

  return "";
}

function extractThumbnailFromWpPost(post, siteBaseUrl = "") {
  if (!post || typeof post !== "object") return "";

  const directCandidates = [
    post.thumbnailUrl,
    post.thumbnail_url,
    post.posterUrl,
    post.poster_url,
    post.featured_image_url,
    post.jetpack_featured_media_url,
    post._embedded?.["wp:featuredmedia"]?.[0]?.source_url,
    post.yoast_head_json?.og_image?.[0]?.url,
    post.acf?.thumbnail_url,
    post.acf?.poster_url,
    post.meta?.thumbnail_url,
  ];

  for (const cand of directCandidates) {
    if (typeof cand === "string" && cand.trim()) {
      const normalized = normalizeCandidateUrl(cand, siteBaseUrl);
      if (normalized) return normalized;
    }
  }

  const html = typeof post.content === "string" ? post.content : post.content?.rendered || "";
  if (!html) return "";

  const posterMatch = html.match(/<video\b[^>]*\bposter\s*=\s*["']([^"']+)["']/i);
  if (posterMatch?.[1]) {
    const normalized = normalizeCandidateUrl(posterMatch[1], siteBaseUrl);
    if (normalized) return normalized;
  }

  const imgMatch = html.match(/<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["']/i);
  if (imgMatch?.[1]) {
    const normalized = normalizeCandidateUrl(imgMatch[1], siteBaseUrl);
    if (normalized) return normalized;
  }

  return "";
}

function extractTitleFromWpPost(post) {
  const rawTitle =
    typeof post?.title === "string"
      ? post.title
      : post?.title?.rendered || post?.post_title || post?.name || "";
  return stripHtmlTags(rawTitle).slice(0, 200) || "New Video";
}

function extractDescriptionFromWpPost(post) {
  const rawDesc =
    typeof post?.description === "string"
      ? post.description
      : typeof post?.excerpt === "string"
        ? post.excerpt
        : post?.excerpt?.rendered || "";
  const cleaned = stripHtmlTags(rawDesc).slice(0, 600);
  return cleaned || "Auto-imported from WordPress via VidVora Bot";
}

/**
 * Parses a standard WordPress RSS 2.0 XML feed into post-like objects as a fallback
 * when WP REST API (/wp-json/wp/v2/posts) is disabled or blocked.
 */
function parseWordPressRssFeed(xmlText, siteBaseUrl = "") {
  const xml = String(xmlText || "");
  if (!xml) return [];
  const items = [];
  const itemRegex = /<item\b[^>]*>([\s\S]*?)<\/item>/gi;
  let match;
  while ((match = itemRegex.exec(xml)) !== null) {
    const block = match[1];
    const readTag = (tagName) => {
      const tagRegex = new RegExp(`<${tagName}\\b[^>]*>([\\s\\S]*?)<\\/${tagName}>`, "i");
      const m = block.match(tagRegex);
      if (!m) return "";
      return m[1].replace(/<!\[CDATA\[([\s\S]*?)\]\]>/gi, "$1").trim();
    };
    const title = decodeHtmlEntities(readTag("title"));
    const link = decodeHtmlEntities(readTag("link"));
    const guid = decodeHtmlEntities(readTag("guid"));
    const contentEncoded = readTag("content:encoded");
    const description = readTag("description");
    const enclosureMatch = block.match(/<enclosure\b[^>]*\burl\s*=\s*["']([^"']+)["'][^>]*>/i);
    const mediaContentMatch = block.match(/<media:content\b[^>]*\burl\s*=\s*["']([^"']+)["'][^>]*>/i);
    const mediaThumbMatch = block.match(/<media:thumbnail\b[^>]*\burl\s*=\s*["']([^"']+)["'][^>]*>/i);

    const postObj = {
      id: guid || link || title,
      link,
      title: { rendered: title },
      excerpt: { rendered: description },
      content: { rendered: contentEncoded || description },
      video_url: enclosureMatch?.[1] || mediaContentMatch?.[1] || "",
      thumbnail_url: mediaThumbMatch?.[1] || "",
      date_gmt: readTag("pubDate"),
    };
    const videoUrl = extractVideoUrlFromWpPost(postObj, siteBaseUrl);
    if (videoUrl) {
      postObj.video_url = videoUrl;
    }
    items.push(postObj);
  }
  return items;
}

function buildDedupeDocId({ siteUrl, wpPostId, videoUrl }) {
  const basis = wpPostId
    ? `wp_post|${normalizeSiteUrl(siteUrl)}|${String(wpPostId).trim()}`
    : `wp_url|${String(videoUrl || "").trim()}`;
  const hash = crypto.createHash("sha256").update(basis, "utf8").digest("hex").slice(0, 28);
  return `wp_${hash}`;
}

function parseChannelIds(rawChannels) {
  return String(rawChannels || "")
    .split(/[\s,]+/)
    .map((s) => s.trim())
    .filter(Boolean);
}

function getWpAutoPosterEnvConfig() {
  const siteUrl = normalizeSiteUrl(process.env.WP_SITE_URL || "");
  const channels = parseChannelIds(process.env.WP_TELEGRAM_CHANNEL_ID || process.env.WP_CHANNEL_ID || "");
  const secret = String(process.env.WP_AUTOPOST_SECRET || "").trim();
  const rawPoll = parseInt(process.env.WP_POLL_INTERVAL_SECONDS || String(DEFAULT_POLL_INTERVAL_SECONDS), 10);
  const pollIntervalSeconds = Number.isFinite(rawPoll)
    ? Math.max(MIN_POLL_INTERVAL_SECONDS, rawPoll)
    : DEFAULT_POLL_INTERVAL_SECONDS;
  const rawMax = parseInt(process.env.WP_MAX_POSTS_PER_RUN || String(DEFAULT_MAX_POSTS_PER_RUN), 10);
  const maxPostsPerRun = Number.isFinite(rawMax)
    ? Math.max(1, Math.min(MAX_POSTS_PER_RUN_LIMIT, rawMax))
    : DEFAULT_MAX_POSTS_PER_RUN;
  const uploaderMemberId = String(process.env.WP_UPLOADER_MEMBER_ID || "admin").trim() || "admin";
  const uploaderName = String(process.env.WP_UPLOADER_NAME || "VidVora Admin").trim() || "VidVora Admin";
  const categoryId = String(process.env.WP_CATEGORY_ID || "").trim();
  const enabledFlag = String(process.env.WP_AUTOPOST_ENABLED || "true").trim().toLowerCase() !== "false";

  return {
    enabled: enabledFlag && Boolean(siteUrl) && channels.length > 0,
    siteUrl,
    channels,
    secret,
    pollIntervalSeconds,
    maxPostsPerRun,
    uploaderMemberId,
    uploaderName,
    categoryId,
  };
}

async function fetchRecentWordPressPosts({ siteUrl, categoryId = "", perPage = 10, fetchImpl = fetch }) {
  const cleanSite = normalizeSiteUrl(siteUrl);
  if (!cleanSite) {
    throw new Error("WP_SITE_URL is not configured or invalid.");
  }

  const headers = {
    "User-Agent": "VidVoraAutoPoster/1.0 (+https://vidvora-share-bot.onrender.com)",
    Accept: "application/json, application/rss+xml, application/xml, text/xml;q=0.9, */*;q=0.8",
  };

  // 1. Primary: WordPress built-in REST API (/wp-json/wp/v2/posts)
  const restParams = new URLSearchParams({
    per_page: String(Math.max(1, Math.min(25, perPage))),
    _embed: "1",
  });
  if (categoryId) {
    restParams.set("categories", categoryId);
  }
  const restUrl = `${cleanSite}/wp-json/wp/v2/posts?${restParams.toString()}`;
  try {
    const restRes = await fetchImpl(restUrl, {
      headers,
      signal: AbortSignal.timeout(15_000),
    });
    if (restRes.ok) {
      const json = await restRes.json();
      if (Array.isArray(json)) {
        return { source: "wp-rest-api", posts: json };
      }
    }
  } catch (restErr) {
    console.warn("WP REST API fetch fallback to RSS:", restErr?.message || restErr);
  }

  // 2. Fallback: WordPress built-in RSS feed (/feed/)
  const rssUrl = `${cleanSite}/feed/`;
  const rssRes = await fetchImpl(rssUrl, {
    headers,
    signal: AbortSignal.timeout(15_000),
  });
  if (!rssRes.ok) {
    throw new Error(`WordPress feed fetch failed (HTTP ${rssRes.status}) for ${cleanSite}`);
  }
  const xmlText = await rssRes.text();
  return {
    source: "wp-rss-feed",
    posts: parseWordPressRssFeed(xmlText, cleanSite),
  };
}

async function postConvertedVideoToChannel({
  apiBase,
  botToken,
  channelId,
  title,
  thumbnailUrl,
  watchUrl,
  rawToken,
  uploaderName,
  resolvedShareBase,
  telegramApiFn = telegramApiJson,
}) {
  const caption = buildShareMessageCaption({
    title,
    uploaderName,
    rawToken,
    previewSeconds: 5,
    shareLinkBaseUrl: resolvedShareBase,
  });
  const replyMarkup = {
    inline_keyboard: [[{ text: "▶️ Watch Full Video on VidVora", url: watchUrl }]],
  };

  if (thumbnailUrl && /^https?:\/\//i.test(thumbnailUrl)) {
    try {
      const photoRes = await telegramApiFn(apiBase, botToken, "sendPhoto", {
        chat_id: channelId,
        photo: thumbnailUrl,
        caption,
        parse_mode: "HTML",
        reply_markup: replyMarkup,
      });
      if (photoRes?.ok) {
        return { ok: true, mode: "photo", messageId: photoRes.result?.message_id || null };
      }
    } catch (photoErr) {
      console.warn(`WP AutoPoster sendPhoto fallback for ${channelId}:`, photoErr?.message || photoErr);
    }
  }

  const msgRes = await telegramApiFn(apiBase, botToken, "sendMessage", {
    chat_id: channelId,
    text: caption,
    parse_mode: "HTML",
    disable_web_page_preview: true,
    link_preview_options: { is_disabled: true },
    reply_markup: replyMarkup,
  });
  if (!msgRes?.ok) {
    throw new Error(msgRes?.description || `Telegram sendMessage failed for channel ${channelId}`);
  }
  return { ok: true, mode: "message", messageId: msgRes.result?.message_id || null };
}

/**
 * Converts a single WordPress post (or webhook video item) into a VidVora watch link,
 * saves it into videos + accessLinks + wpAutoPosted, and publishes it to the Telegram channel(s).
 */
async function convertAndPublishWordPressItem({
  db,
  item,
  siteUrl,
  channels,
  publicBaseUrl,
  uploaderMemberId = "admin",
  uploaderName = "VidVora Admin",
  resolveBotConfigFn = resolveBotRuntimeConfig,
  telegramApiFn = telegramApiJson,
}) {
  const videoUrl = extractVideoUrlFromWpPost(item, siteUrl);
  if (!videoUrl) {
    return { status: "skipped", reason: "no_playable_video_url" };
  }

  const wpPostId = item?.id != null ? String(item.id).trim() : "";
  const wpPostUrl = String(item?.link || item?.postUrl || "").trim();
  const title = extractTitleFromWpPost(item);
  const description = extractDescriptionFromWpPost(item);
  const thumbnailUrl = extractThumbnailFromWpPost(item, siteUrl);

  const dedupeDocId = buildDedupeDocId({ siteUrl, wpPostId, videoUrl });
  const existingRecord = await db.collection("wpAutoPosted").doc(dedupeDocId).get();
  if (existingRecord.exists) {
    return {
      status: "duplicate",
      dedupeDocId,
      existingWatchUrl: existingRecord.data()?.watchUrl || "",
    };
  }

  // Secondary duplicate check by exact videoUrl in wpAutoPosted
  const existingByUrl = await db
    .collection("wpAutoPosted")
    .where("videoUrl", "==", videoUrl)
    .limit(1)
    .get();
  if (!existingByUrl.empty) {
    return {
      status: "duplicate",
      dedupeDocId: existingByUrl.docs[0].id,
      existingWatchUrl: existingByUrl.docs[0].data()?.watchUrl || "",
    };
  }

  const botCfg = await resolveBotConfigFn(db);
  if (!botCfg.botToken) {
    throw new Error("TELEGRAM_BOT_TOKEN is not configured on Render.");
  }
  const targetChannels = parseChannelIds(Array.isArray(channels) ? channels.join(",") : channels);
  if (!targetChannels.length) {
    throw new Error("WP_TELEGRAM_CHANNEL_ID is not configured on Render.");
  }

  const rawToken = generateAccessToken();
  const tokenHash = hashToken(rawToken);
  const videoId = `vid_wp_${crypto.randomBytes(6).toString("hex")}`;
  const linkId = `link_${rawToken.toLowerCase().replace(/-/g, "_")}`;
  const now = new Date();

  const resolvedShareBase =
    botCfg.shareLinkBaseUrl ||
    (publicBaseUrl && /^https:\/\//i.test(publicBaseUrl) && !publicBaseUrl.includes("localhost")
      ? `${publicBaseUrl.replace(/\/+$/, "")}/share?link=`
      : SHARE_LINK_BASE_URL);
  const watchUrl = `${resolvedShareBase}${rawToken}`;

  await db.collection("videos").doc(videoId).set({
    videoId,
    title,
    description,
    thumbnailPath: thumbnailUrl,
    storagePath: videoUrl,
    createdAt: now,
    isActive: true,
    totalViews: 0,
    uploadedBy: uploaderMemberId,
    uploaderName,
    sourceType: "wordpress_autopost",
    sourcePostUrl: wpPostUrl,
  });

  await db.collection("accessLinks").doc(linkId).set({
    linkId,
    videoId,
    tokenHash,
    isActive: true,
    createdAt: now,
    expiresAt: null,
  });

  const channelResults = [];
  for (const channelId of targetChannels) {
    const sent = await postConvertedVideoToChannel({
      apiBase: botCfg.apiBase,
      botToken: botCfg.botToken,
      channelId,
      title,
      thumbnailUrl,
      watchUrl,
      rawToken,
      uploaderName,
      resolvedShareBase,
      telegramApiFn,
    });
    channelResults.push({ channelId, ...sent });
  }

  await db.collection("wpAutoPosted").doc(dedupeDocId).set({
    dedupeDocId,
    siteUrl: normalizeSiteUrl(siteUrl),
    wpPostId,
    wpPostUrl,
    title,
    videoUrl,
    thumbnailUrl,
    videoId,
    linkId,
    rawToken,
    watchUrl,
    channels: targetChannels,
    channelResults,
    postedAt: now,
  });

  return {
    status: "posted",
    dedupeDocId,
    videoId,
    rawToken,
    watchUrl,
    title,
    videoUrl,
    thumbnailUrl,
    channelResults,
  };
}

let activeSyncPromise = null;
const wpAutoPosterStats = {
  lastRunAt: null,
  lastSource: null,
  lastResult: null,
  lastError: null,
  totalPostedSinceBoot: 0,
};

async function runWordPressAutoSync({
  db,
  publicBaseUrl,
  configOverride = null,
  fetchImpl = fetch,
  resolveBotConfigFn = resolveBotRuntimeConfig,
  telegramApiFn = telegramApiJson,
}) {
  if (activeSyncPromise) {
    return activeSyncPromise;
  }

  const runTask = async () => {
    const cfg = configOverride || getWpAutoPosterEnvConfig();
    if (!cfg.siteUrl) {
      return { ok: false, skipped: true, reason: "WP_SITE_URL not configured" };
    }
    if (!cfg.channels || !cfg.channels.length) {
      return { ok: false, skipped: true, reason: "WP_TELEGRAM_CHANNEL_ID not configured" };
    }

    const fetched = await fetchRecentWordPressPosts({
      siteUrl: cfg.siteUrl,
      categoryId: cfg.categoryId,
      perPage: Math.max(10, cfg.maxPostsPerRun * 3),
      fetchImpl,
    });

    // Reverse so oldest unposted items go to the channel first in chronological order
    const orderedPosts = [...fetched.posts].reverse();
    const summary = {
      ok: true,
      source: fetched.source,
      siteUrl: cfg.siteUrl,
      channels: cfg.channels,
      scannedCount: orderedPosts.length,
      postedCount: 0,
      duplicateCount: 0,
      skippedNoVideoCount: 0,
      postedItems: [],
      errors: [],
    };

    for (const post of orderedPosts) {
      if (summary.postedCount >= cfg.maxPostsPerRun) break;
      try {
        const res = await convertAndPublishWordPressItem({
          db,
          item: post,
          siteUrl: cfg.siteUrl,
          channels: cfg.channels,
          publicBaseUrl,
          uploaderMemberId: cfg.uploaderMemberId,
          uploaderName: cfg.uploaderName,
          resolveBotConfigFn,
          telegramApiFn,
        });
        if (res.status === "posted") {
          summary.postedCount += 1;
          wpAutoPosterStats.totalPostedSinceBoot += 1;
          summary.postedItems.push({
            title: res.title,
            watchUrl: res.watchUrl,
            videoId: res.videoId,
          });
        } else if (res.status === "duplicate") {
          summary.duplicateCount += 1;
        } else {
          summary.skippedNoVideoCount += 1;
        }
      } catch (itemErr) {
        summary.errors.push({
          postId: post?.id || "",
          error: itemErr?.message || String(itemErr),
        });
      }
    }

    wpAutoPosterStats.lastRunAt = new Date().toISOString();
    wpAutoPosterStats.lastSource = fetched.source;
    wpAutoPosterStats.lastResult = {
      scannedCount: summary.scannedCount,
      postedCount: summary.postedCount,
      duplicateCount: summary.duplicateCount,
      skippedNoVideoCount: summary.skippedNoVideoCount,
      errorCount: summary.errors.length,
    };
    wpAutoPosterStats.lastError = summary.errors.length ? summary.errors[0].error : null;

    return summary;
  };

  activeSyncPromise = runTask().finally(() => {
    activeSyncPromise = null;
  });
  return activeSyncPromise;
}

function startWordPressAutoPosterScheduler({ db, publicBaseUrl }) {
  const cfg = getWpAutoPosterEnvConfig();
  if (!cfg.enabled) {
    console.log("WP Auto-Poster scheduler inactive (set WP_SITE_URL and WP_TELEGRAM_CHANNEL_ID to enable).");
    return null;
  }

  console.log(
    `WP Auto-Poster scheduler active: site=${cfg.siteUrl}, channels=${cfg.channels.join(",")}, interval=${cfg.pollIntervalSeconds}s`
  );

  const executeTick = () => {
    runWordPressAutoSync({ db, publicBaseUrl })
      .then((res) => {
        if (res.postedCount > 0) {
          console.log(`WP Auto-Poster published ${res.postedCount} new video(s) from ${cfg.siteUrl}.`);
        }
      })
      .catch((err) => {
        wpAutoPosterStats.lastRunAt = new Date().toISOString();
        wpAutoPosterStats.lastError = err?.message || String(err);
        console.warn("WP Auto-Poster tick error:", err?.message || err);
      });
  };

  // Run initial check shortly after boot (15s delay so DB & webhook settle)
  const initialTimer = setTimeout(executeTick, 15_000);
  if (typeof initialTimer.unref === "function") initialTimer.unref();

  const intervalHandle = setInterval(executeTick, cfg.pollIntervalSeconds * 1000);
  if (typeof intervalHandle.unref === "function") intervalHandle.unref();
  return intervalHandle;
}

function getWordPressAutoPosterStatus() {
  const cfg = getWpAutoPosterEnvConfig();
  return {
    enabled: cfg.enabled,
    siteUrl: cfg.siteUrl || null,
    channels: cfg.channels,
    pollIntervalSeconds: cfg.pollIntervalSeconds,
    maxPostsPerRun: cfg.maxPostsPerRun,
    secretConfigured: Boolean(cfg.secret),
    stats: { ...wpAutoPosterStats },
  };
}

module.exports = {
  decodeHtmlEntities,
  stripHtmlTags,
  normalizeSiteUrl,
  isLikelyPlayableVideoUrl,
  extractVideoUrlFromWpPost,
  extractThumbnailFromWpPost,
  extractTitleFromWpPost,
  parseWordPressRssFeed,
  buildDedupeDocId,
  parseChannelIds,
  getWpAutoPosterEnvConfig,
  fetchRecentWordPressPosts,
  convertAndPublishWordPressItem,
  runWordPressAutoSync,
  startWordPressAutoPosterScheduler,
  getWordPressAutoPosterStatus,
};
