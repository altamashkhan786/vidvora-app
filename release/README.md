# VidVora Release APK

- **App Name:** VidVora
- **Package ID:** `com.aistudio.vidvora.vstr`
- **Version:** `1.9` (`versionCode = 10`)
- **APK File:** `release/vidvora.apk` (~34 MB)
- **SHA-256:** `16e93c21f540372a2caa3f60e575db765c4b967c2ca6d24606fd6ec163b20a9b`
- **What's New in v1.9:**
  - Creator Referral Program ("Refer & Earn 7%") inside Creator Panel with unique Referral Code, Copy & Share Invite, Referred Creators list, Pending/Successful status filters, and 7% Admin-funded bonus (zero deduction from referred creators)
  - Separate Own Views Earning vs Referral Bonus (7%) breakdown with unified Total Earning & Available Balance wallet for UPI withdrawal
  - Optional Referral Code input during Creator Sign Up and Admin Add Member
  - Includes all v1.8 improvements (AdMob eCPM/Show Rate optimization, Fast Instant Video Start, 1-Device-1-Install Tracking, Official APK Download Website)

## How to Use `release/vidvora.apk`

1. **Automatic Website Download:** Push this repo to GitHub — your Render server (`https://vidvora-share-bot.onrender.com/download` and `/download-apk`) will automatically serve `release/vidvora.apk` if CloudFront does not have it yet!
2. **Optional AWS CloudFront CDN:** Upload `release/vidvora.apk` to your AWS S3 bucket root as `vidvora.apk` for faster global CDN downloads (your website automatically switches to CloudFront once it responds HTTP 200).
3. **Direct Install:** Download **`release/vidvora.apk`** (or from **GitHub Releases** built by `.github/workflows/publish-apk.yml`) and install on Android 7.0+.

- **Privacy Policy PDF:** `release/VidVora_Privacy_Policy.pdf` (Live URL: `https://vidvora-share-bot.onrender.com/privacy-policy`)
- **Documentation PDF:** `release/VidVora_Documentation.pdf` (also in `docs/VidVora_Documentation.pdf`)
