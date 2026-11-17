# VidVora — Complete Application Documentation

**Package / Application ID:** `com.aistudio.vidvora.vstr`  
**Platform:** Android (Kotlin, Jetpack Compose, Material Design 3) + Node.js 20 Backend (`functions/`)  
**Firebase Project ID:** `gen-lang-client-0000740810`  
**Firestore Database ID:** `ai-studio-android-garammas-738e4493-0090-419e-8ece-c55642a933ce`

---

## 1. Executive Summary & System Architecture

VidVora is a full-stack private video streaming, deep-link distribution, creator monetization, and AdMob revenue platform. Every video uploaded by a Creator or Admin is protected by a cryptographic access token (`GM-XXXXXXXX` / `link_...`), distributed via shareable web links that automatically render rich video thumbnail previews on any messaging or social platform (WhatsApp, Telegram, Facebook, Instagram, X/Twitter, Discord), and launched straight into the in-app ExoPlayer.

### High-Level Components

| Layer | Technology | Responsibility |
| :--- | :--- | :--- |
| **Android App** | Kotlin, Jetpack Compose, M3 | Home feed, deep-link resolution, ExoPlayer, AdMob ads, Creator & Admin dashboards |
| **Media Player** | AndroidX Media3 (ExoPlayer) | Adaptive video playback, pinch-to-zoom, speed/quality selection, HTTP 206 seeking |
| **Local Cache** | Room Database + SharedPreferences | Offline Watch History, install UUID heartbeat, Ad-Free VIP timer state |
| **Cloud Backend** | Cloud Firestore + Storage | Real-time videos, access links, creator balances, payouts, support chat, config |
| **Web & Bot Server** | Node.js 20 (`functions/server.js` / `index.js`) | Telegram Creator Bot webhook, 5s ffmpeg clip generator, `/streamPublicVideo`, dynamic `/share` + `/shareThumb` Open Graph server |
| **Static Edge CDN** | AWS CloudFront + S3 (`d1mn8mrvlzisg5.cloudfront.net`) | Fast HTTPS fallback opener (`index.html`), APK hosting (`vidvora.apk`), `app-ads.txt`, Android App Links (`assetlinks.json`), fallback OG banner |

---

## 2. User Features (Viewer Experience)

1. **Instant Deep-Link & Token Playback**
   - Viewers can paste a raw token (`GM-XXXX`), `vidvora://watch?link=...`, HTTPS share links (`.../share?link=...`, CloudFront, Firebase Hosting), or direct `.mp4`/`.m3u8` URLs into the Home screen or tap shared links directly from WhatsApp/Telegram/browsers.
   - `AndroidManifest.xml` handles `vidvora://watch`, `https://d1mn8mrvlzisg5.cloudfront.net/`, `https://gen-lang-client-0000740810.web.app/watch|/share`, and `https://*.onrender.com/share|/watch`.
2. **Pro In-App Video Player (`PlayerScreen`)**
   - Smooth hardware/software decoded Media3 ExoPlayer with double-tap seek (+/-10s), pinch-to-zoom / crop / fit toggle, playback speed (`0.5x` to `2.0x`), quality track selector, orientation toggle, and one-tap **Share Video** button.
3. **Watch History**
   - Automatically saves played items locally via Room (`WatchHistoryEntity`) with instant replay, copy/share (using the configured Open Graph preview base URL), and single-item delete.
4. **Telegram Channel Directory**
   - Header/Home Telegram drawer lists the **Main Official Channel** plus **All Category Channels** synced live from Firestore (`appConfig/general`), supporting built-in and custom admin categories.
5. **Broadcast Notifications & Support Chat**
   - Live unread badge for Admin broadcast notices (`FirestoreNotification`) with optional banner image and one-tap **Watch Video / Open Link** deep action.
   - Real-time **VidVora Support Team** chat directly from the Home screen for viewers experiencing playback or link issues.

---

## 3. AdMob Monetization Architecture

AdMob is integrated via `AdManager`, `AppOpenAdManager`, `AdaptiveBannerAd`, and `NativeAdCard` with strict guards (`isPlayerActive`) so ads **never** interrupt active video playback:

| Placement | Trigger / Frequency | Policy & UX Guard |
| :--- | :--- | :--- |
| **App Open Ad** | Cold app launch (12s ready window) + background-to-foreground resume | 30s resume cooldown; suppressed automatically on incoming video deep links |
| **Pre-Play Interstitial** | Tapping "Watch & Play", Watch History item replay, or incoming deep link | Every 2 gated actions (`INTERSTITIAL_ACTION_THRESHOLD = 2`) with 25s cooldown (`MIN_INTERSTITIAL_COOLDOWN_MS`) |
| **Post-Player Exit Ad** | Closing `PlayerScreen` after watching (non-admin preview) | Shared full-screen cooldown; never fires inside video stream |
| **Nav Transition Ad** | Dismissing Telegram Drawer, Notices, or Support Chat dialogs | Governed by shared 25s cooldown + VIP pass check |
| **Home Native Ads** | Primary & Secondary `NativeAdCard` slots spaced in Home LazyColumn | Automatic exponential-backoff reload on failure |
| **Adaptive Banner** | Anchored bottom banner on Home Screen | Auto-resizes to screen width; exponential retry on network drop |
| **Rewarded VIP Pass** | Optional Rewarded Video CTA ("Watch Ad for 10 Min Ad-Free") | Grants 10 minutes (`600,000 ms`) persistent Ad-Free window across all placements |

---

## 4. Creator Hub & Earnings System

Creators sign in via **Admin/Creator Login** using their personal Master Key (`passkey`) + optional email/phone:

- **Upload Videos (`VIDEO` File or Direct URL):**
  - Supports uploading local MP4/MKV/MOV videos or pasting external HTTPS/CDN stream URLs, with custom Title, Description, and Thumbnail image (stored cleanly in Firebase Storage or compressed inline Base64 fallback).
  - Creator's video list displays newest uploads strictly at the top (`sortVideosNewestFirst`).
- **Share Links with Universal Thumbnail Previews:**
  - Copy or share links straight from the Creator Video Card or Player. When a Share Preview Server URL is set, shared links show the exact video thumbnail & title on every social app.
- **View Counting & ₹ Earnings Calculation:**
  - Formula: `Earning (₹) = round((views / 1000.0) * 190.0, 2)` (`₹190 per 1,000 views`).
  - Creators can inspect Credited Views, Total Earned, Pending/Processing/Paid payouts, and Available Balance.
- **UPI Withdrawals:**
  - Minimum withdrawal: **₹500.00** (`MIN_WITHDRAWAL_AMOUNT`).
  - Validates UPI format (`name@bank`). Tracks status (`PENDING -> PROCESSING -> PAID / REJECTED`).
- **Creator Events & Support:**
  - View Admin-published **Creator Events & Gift Bonuses** and chat directly with Superadmin/Support via Creator Support Chat.

---

## 5. Superadmin & Delegated Admin Panel

- **Superadmin Access:** Unlocks all management tabs via Master Key (`GM-ADMIN-2026-MASTER`).
- **Delegated Admins (`MemberRole.ADMIN`):** Superadmin can grant granular permissions (`MemberOption`) per admin:
  - `UPLOAD_VIDEOS`, `MANAGE_VIDEOS`, `MANAGE_MEMBERS`, `MANAGE_PAYOUTS`, `SEND_NOTIFICATIONS`, `MANAGE_TELEGRAM`, `MANAGE_SUPPORT`, `MANAGE_EVENTS`, `VIEW_ANALYTICS`.
- **Key Management Workflows:**
  - **Videos:** Edit title/description, toggle video or individual access-link active kill-switch, delete video + associated links.
  - **Members:** Add/Pause/Delete members, change email/phone with automatic historical **Video & Payout Transfer/Merge**, manually credit views/earnings.
  - **Payouts:** Filter `ALL / PENDING / PROCESSING / PAID / REJECTED`, approve or mark UPI payouts Paid/Rejected with transaction notes.
  - **Telegram & Share Preview Config (`EditTelegramLinkDialog`):** Configure Main Channel, Creator Upload Bot link, **Share Preview Server URL**, rename/delete built-in categories, and add up to 40 custom categories.
  - **Install Analytics (`appInstalls`):** Tracks unique installs, app version updates, and Active vs. Inactive devices based on configurable inactivity threshold.

---

## 6. Telegram Creator Auto-Upload Bot (`functions/telegramBot.js`)

Creators can upload videos directly from Telegram without opening the Android app:

### Supported Commands
- `/start` or `/help` — Shows quick setup & command guide.
- `/login <MASTER_KEY>` (or `/login <email> <MASTER_KEY>`) — Links Creator/Admin account (`telegramBotSessions`).
- `/status` — Displays linked creator name and bot upload count.
- `/logout` — Unlinks Telegram chat from VidVora account.

### Auto-Upload & 5-Second Preview Clip Flow
1. Creator sends any Telegram video (`<= 20 MB` on standard Bot API) OR any size direct `https://...mp4` URL with caption (`Line 1 = Title`, `Line 2+ = Description`).
2. Backend (`telegramBot.js`) verifies active Creator `UPLOAD_VIDEOS` permission in Firestore.
3. **ffmpeg** extracts a **5-second compressed MP4 preview clip** (`scale='min(720,iw)':-2`, H.264/AAC) + **JPG thumbnail**.
4. **Zero-Cost Spark Plan Support:** If Firebase Storage is unavailable (Spark plan without Blaze billing), the bot automatically stores `tgFileId` + Base64 JPEG thumbnail in Firestore (`videos/{videoId}`) and streams bytes live via HMAC-signed `/streamPublicVideo`.
5. Bot replies in Telegram with the **5-second preview video**, **inline watch button**, **Access Code (`GM-XXXX`)**, and **Thumbnail-Preview Share Link**.

---

## 7. AWS CloudFront CDN & Cross-Platform Share Link Previews

### What AWS Is Used For (`https://d1mn8mrvlzisg5.cloudfront.net/...`)
Your AWS setup consists of an **Amazon S3 Static Website Bucket** fronted by **Amazon CloudFront CDN (`d1mn8mrvlzisg5.cloudfront.net`)**:
- `index.html` (`web-pages/index.html`) — Fast static HTTPS opener that translates `?link=GM-XXXX` into `intent://watch?link=...#Intent;scheme=vidvora;package=com.aistudio.vidvora.vstr;...`.
- `vidvora.apk` — Direct APK download fallback when a friend opens the link without VidVora installed.
- `vidvora-og-banner.png` — Static branded `1200x630` fallback Open Graph preview image.
- `app-ads.txt` & `.well-known/assetlinks.json` — AdMob authorized seller verification & Android App Links domain verification.

### How Per-Video Link Thumbnails Work on ANY Platform
Social crawlers (**WhatsApp, Telegram, Facebook, Instagram, X/Twitter, Discord, LinkedIn**) do **not** run JavaScript inside static S3 HTML and ignore `data:image/...;base64` URIs. Therefore, VidVora provides dynamic server-side Open Graph endpoints in both `functions/server.js` (Free Render hosting) and `functions/index.js` (Firebase Functions + Hosting):

1. **`GET /share?link=<TOKEN_OR_VIDEO_ID>`** (also `/watch`):
   - Resolves `accessLinks` (SHA-256 `tokenHash` or doc ID) -> loads `videos/{videoId}` from Firestore.
   - Injects `<meta property="og:title">`, `<meta property="og:description">`, `<meta property="og:image" content="https://<HOST>/shareThumb?videoId=<ID>">`, `<meta property="og:image:width" content="1280">`, `<meta property="og:image:height" content="720">`, and `<meta name="twitter:card" content="summary_large_image">`.
   - Renders the VidVora player card showing the video's thumbnail, title, **Watch in VidVora App** (`intent://` + `vidvora://`) button, and APK download fallback.
2. **`GET /shareThumb?videoId=<ID>`**:
   - Returns raw HTTP 200 image bytes (`image/jpeg` or `image/png`) by decoding inline Base64 thumbnails (`data:image/...`), downloading from Firebase Storage, proxying external HTTPS thumbnails cleanly, or falling back to `vidvora-og-banner.png`.
3. **One-Click Activation in Admin Panel:**
   - Open **Admin Panel -> Telegram Links -> Share Preview Server URL** and paste your live Render URL (e.g., `https://vidvora-bot.onrender.com/share?link=`). Immediately, every Creator, Admin, Player, Home History, and Telegram Bot share link dynamically displays that video's exact thumbnail across all platforms.

---

## 8. Security & Access Control Summary

- **Token Hashing:** Access tokens are normalized uppercase and hashed with **SHA-256** (`tokenHash`) in `accessLinks` and `playback`.
- **HMAC-Signed Streaming (`streamPublicVideo`):** Validates cryptographic HMAC-SHA256 signatures (`verifyStreamTarget`) AND checks real-time Firestore `videos/{videoId}.isActive` on every stream request.
- **Instant Revocation:** Deactivating either a single `accessLink` or parent `video` blocks both app playback and public proxy streaming immediately.
- **Role Enforcement:** Firestore Security Rules + ViewModel guards isolate Creator views, Delegated Admin capabilities, and Superadmin controls.

---

## 9. Deployment & Maintenance Quick Checklist

1. **Android APK (`release/VidVora.apk`):** Build via `gradle :app:assembleDebug` (or `compile_applet`) and upload latest APK to CloudFront S3 as `/vidvora.apk` + share via GitHub Releases / Telegram.
2. **Free Bot & OG Preview Server (`functions/server.js`):** Deploy `functions/` folder on **Render.com** (Free Web Service, Build Command: `npm install`, Start Command: `npm start`) with `TELEGRAM_BOT_TOKEN`, `FIREBASE_SERVICE_ACCOUNT_JSON`, and `PUBLIC_BASE_URL`.
3. **Telegram Webhook:** Set webhook via:
   `https://api.telegram.org/bot<TOKEN>/setWebhook?url=https://<YOUR_RENDER_URL>/telegramWebhook`
4. **Enable Dynamic Thumbnails Everywhere:** Paste `https://<YOUR_RENDER_URL>/share?link=` into Admin Panel -> **Share Preview Server URL**.
5. *(Optional Pro Setup)* In **AWS CloudFront**, add Render as a second Origin and add Cache Behaviors for `/share*`, `/shareThumb*`, `/watch*` to keep your `d1mn8mrvlzisg5.cloudfront.net` domain while serving per-video thumbnails dynamically.
