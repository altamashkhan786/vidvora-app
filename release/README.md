# VidVora Release APK

- **App Name:** VidVora
- **Package ID:** `com.aistudio.vidvora.vstr`
- **Version:** `1.8` (`versionCode = 9`)
- **APK File:** `release/vidvora.apk` (~34 MB)
- **SHA-256:** `ea30d7d6f0cb66690e1bddb4b147b9ef880f22e6a94bd767a341bf9a2240908a`
- **What's New in v1.8:**
  - Improved AdMob Show Rate, Match Rate & eCPM (smarter deep-link interstitial timing, zero wasted background App Open / hidden Banner & Native requests, 50-min ad freshness)
  - Fast Instant Video Start via Direct HTTP Range Streaming + Tuned ExoPlayer Buffers
  - 1-Device-1-Install Deduplication + Automatic Emulator Exclusion
  - Official VidVora APK Download Website (`/download` & `/download-apk`) with automatic release fallback
  - Chrome Analytics Dashboard with Team Viewer Access & 512 MB RAM optimizations

## How to Use `release/vidvora.apk`

1. **Automatic Website Download:** Push this repo to GitHub — your Render server (`https://vidvora-share-bot.onrender.com/download` and `/download-apk`) will automatically serve `release/vidvora.apk` if CloudFront does not have it yet!
2. **Optional AWS CloudFront CDN:** Upload `release/vidvora.apk` to your AWS S3 bucket root as `vidvora.apk` for faster global CDN downloads (your website automatically switches to CloudFront once it responds HTTP 200).
3. **Direct Install:** Download **`release/vidvora.apk`** (or from **GitHub Releases** built by `.github/workflows/publish-apk.yml`) and install on Android 7.0+.

- **Privacy Policy PDF:** `release/VidVora_Privacy_Policy.pdf` (Live URL: `https://vidvora-share-bot.onrender.com/privacy-policy`)
- **Documentation PDF:** `release/VidVora_Documentation.pdf` (also in `docs/VidVora_Documentation.pdf`)
