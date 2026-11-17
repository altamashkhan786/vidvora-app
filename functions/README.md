# VidVora — Secure Video Link Authorization & Streaming Proxy

This directory contains the production-grade **Firebase Cloud Functions (2nd Gen)** for VidVora:
1. `validateVideoAccess`: Callable Cloud Function that validates video access tokens, checks active status, increments view counts, and returns short-lived playback authorization tokens.
2. `streamVideo`: Authenticated streaming proxy endpoint with HTTP 206 Partial Content (Range request) support for Android Media3 ExoPlayer scrubbing. Enforces real-time link and video revocation.

---

## 1. Required Paid Firebase Services

> [!IMPORTANT]
> **Firebase Blaze Plan (Pay-as-you-go) is REQUIRED**
> Cloud Functions (Node.js 18/20 runtime) runs on Google Cloud Run and requires the **Firebase Blaze plan** to build container images via Cloud Build and Artifact Registry.
>
> - **Cost Consideration**: Firebase provides a generous **free tier** every month on the Blaze plan:
>   - First 2,000,000 Cloud Functions invocations/month: **Free ($0.00)**
>   - First 400,000 GB-seconds and 200,000 CPU-seconds compute time/month: **Free ($0.00)**
>   - First 5 GB Cloud Storage: **Free ($0.00)**
>   - First 50,000 Cloud Firestore reads/day: **Free ($0.00)**
>   - In practice, small to medium streaming apps incur **$0.00** during development and testing.

---

## 2. Deployment Instructions

### Prerequisites
Install Firebase CLI if not already installed:
```bash
npm install -g firebase-tools
firebase login
```

### Step 1: Install Dependencies
Navigate into the `functions` directory:
```bash
cd functions
npm install
```

### Step 2: Deploy Cloud Functions to Firebase
Deploy only the functions:
```bash
firebase deploy --only functions
```

### Step 3: Deploy Cloud Storage Rules
Deploy the zero-trust Storage rules:
```bash
firebase deploy --only storage
```

### Step 4: (Optional) Set Custom Playback Signing Secret
```bash
firebase functions:secrets:set PLAYBACK_SECRET
```

---

## 3. Secure Playback Delivery Architecture

```
[Android ExoPlayer] 
       │
       ▼ 1. validateVideoAccess(rawToken)
[Cloud Function: validateVideoAccess]
       │
       ├─► 2. Rate Limit Check (10 req/min per IP)
       ├─► 3. SHA-256 Hash Token
       ├─► 4. Query Firestore: accessLinks where tokenHash == hash
       ├─► 5. Verify: link.isActive == true && expiresAt > now
       ├─► 6. Verify: videos/{videoId}.isActive == true
       ├─► 7. Atomically increment totalViews by +1
       └─► 8. Generate HMAC-SHA256 Playback Token (15 min TTL)
       │
       ▼ Returns: { streamUrl: "/streamVideo?playbackToken=...", ... }
[Android ExoPlayer]
       │
       ▼ 9. GET /streamVideo?playbackToken=... (Range: bytes=0-1048576)
[Cloud Function: streamVideo Proxy]
       │
       ├─► 10. Verify HMAC signature & 15-min expiration
       ├─► 11. Real-Time Revocation Check:
       │        Query Firestore: accessLinks & videos (returns 403 immediately if deactivated)
       └─► 12. Stream chunks from Firebase Storage with HTTP 206 Partial Content
       │
       ▼ 13. Video stream rendered smoothly in Media3 ExoPlayer
```

### Key Security Safeguards
- **Zero Permanent Storage URLs**: Permanent public Firebase Storage download URLs are never returned or stored.
- **Hash-Only Database Records**: Plaintext tokens are never stored in Cloud Firestore; only 64-character SHA-256 hashes exist in `accessLinks`.
- **Instant Admin Revocation**: Because the streaming proxy verifies `isActive` in real time, when an admin deactivates a link or video in the Admin Panel, active streaming sessions are terminated immediately.
- **Brute Force Protection**: IP-based rate limiting blocks automated token guessing.
- **No Service Account Credentials in App**: The Android APK contains only public client configuration (`google-services.json`), keeping all signing keys and backend authority strictly on the server.

---

## 4. Telegram Creator Auto-Upload Bot (5-Second Preview Clip + Instant Watch Link)

`telegramWebhook` lets Creators link their VidVora Master Key in Telegram and send any video (or `.mp4` link). The Cloud Function automatically:
1. Verifies the Creator's active `members` account & `UPLOAD_VIDEOS` permission.
2. Uploads the full video + thumbnail to Firebase Storage & creates `videos/{videoId}` + `accessLinks/{linkId}` in Firestore.
3. Cuts a **5-second preview MP4 clip** via `ffmpeg-static`.
4. Sends the **5-second clip** right back to Telegram with the VidVora Watch Link & Code ready to forward to channels.

### Bot Setup Steps
1. Create a bot via `@BotFather` on Telegram and copy your `BOT_TOKEN`.
2. Set the token & optional secrets before deploying Cloud Functions:
   ```bash
   firebase functions:secrets:set TELEGRAM_BOT_TOKEN
   firebase functions:secrets:set PLAYBACK_SECRET
   firebase deploy --only functions
   ```
3. Register the Telegram webhook (replace `<BOT_TOKEN>` and `<REGION>-<PROJECT_ID>`):
   ```bash
   curl -X POST "https://api.telegram.org/bot<BOT_TOKEN>/setWebhook" \
     -H "Content-Type: application/json" \
     -d '{"url": "https://<REGION>-<PROJECT_ID>.cloudfunctions.net/telegramWebhook"}'
   ```
4. In VidVora Admin Panel → **Telegram Channel Links → Creator Upload Bot**, paste your bot link (e.g. `https://t.me/YourVidVoraBot`) so Creators can tap **Open Bot** & **Copy `/login` Command** directly inside the app!
5. *(Optional for >20 MB files via Telegram upload)* Deploy a self-hosted Telegram Local Bot API Server and set `TELEGRAM_API_BASE` in your Cloud Function environment variables, or tell Creators to send direct HTTPS `.mp4` CDN links (unlimited size!).

---

## 5. 100% Free Option (No Firebase Blaze Plan / No Credit Card Required)

If Firebase Blaze billing fails or you want to stay on the free **Spark Plan**, run `server.js` on a free Node.js host (such as **Render.com** or **Koyeb**):

1. Open Firebase Console → **Project Settings** → **Service accounts** → **Generate new private key** (downloads a JSON key).
2. Deploy the `functions` directory as a Node Web Service (`Build: npm install`, `Start: npm start`).
3. Set Environment Variables:
   - `TELEGRAM_BOT_TOKEN` = Your `@BotFather` token
   - `FIREBASE_SERVICE_ACCOUNT_JSON` = Full JSON contents from step 1
   - `PLAYBACK_SECRET` = Any strong secret string
   - `PUBLIC_BASE_URL` = Your deployed `https://...` service URL
4. Register Telegram webhook to `<PUBLIC_BASE_URL>/telegramWebhook`.
   `telegramBot.js` automatically falls back to inline thumbnails & Telegram `file_id` streaming when Cloud Storage is unprovisioned on Spark plan.

---

## 6. Cross-Platform Video Link Thumbnails (`/share` & `/shareThumb`)

Social crawlers (WhatsApp, Telegram, Facebook, Instagram, X/Twitter, Discord) do not execute client-side JavaScript on static S3 pages and reject `data:image/...` URIs. `sharePreview.js` adds dynamic Open Graph routes to both `server.js` (Free Render) and `index.js` (Firebase Cloud Functions):

- **`GET /share?link=<TOKEN_OR_VIDEO_ID>`**: Looks up `accessLinks` + `videos` in Firestore and renders server-side `<meta property="og:title">`, `<meta property="og:image" content="https://<HOST>/shareThumb?videoId=<ID>">`, and `<meta name="twitter:card" content="summary_large_image">`.
- **`GET /shareThumb?videoId=<ID>`**: Returns raw HTTP `200` JPEG/PNG bytes by decoding inline Base64 thumbnails, reading Firebase Storage, proxying external HTTPS images, or serving `vidvora-og-banner.png`.
- Paste your live server URL (`https://your-app.onrender.com/share?link=`) into **Admin Panel → Telegram Links → Share Preview Server URL** so every Creator & Player share button outputs rich thumbnail-preview links automatically.


