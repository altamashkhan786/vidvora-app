const fs = require("fs");
const path = require("path");

const PRIVACY_PDF_PATH = path.join(__dirname, "vidvora-privacy-policy.pdf");

const PRIVACY_POLICY_META = {
  appName: "VidVora",
  packageName: "com.aistudio.vidvora.vstr",
  developerName: "VidVora Team (Altamash)",
  contactEmail: "teamaltamash@gmail.com",
  effectiveDate: "November 19, 2026",
  websiteUrl: "https://vidvora-share-bot.onrender.com/privacy-policy",
};

const PRIVACY_SECTIONS = [
  {
    id: "overview",
    heading: "1. Introduction & Scope",
    paragraphs: [
      "This Privacy Policy explains how VidVora Team (Altamash) (\"we\", \"us\", or \"our\") collects, uses, stores, and protects information when you use the VidVora Android mobile application (Package ID: com.aistudio.vidvora.vstr), its companion web share links, and optional Telegram Creator/Finance bots (collectively, the \"Service\").",
      "By installing or using VidVora, you agree to the practices described in this Privacy Policy. If you do not agree, please discontinue use of the application and uninstall it from your device.",
    ],
  },
  {
    id: "information-we-collect",
    heading: "2. Information We Collect",
    paragraphs: [
      "We collect only the minimum data required to play videos, run optional creator accounts, prevent fraud, and support the application.",
    ],
    bullets: [
      "Viewers (No Account Required): Watching videos via shared VidVora links does not require creating an account. When you open a video link, our servers process the video access code, your IP address, and standard HTTP headers to stream the video securely and prevent duplicate or automated view fraud. Watch history, playback preferences, and optional saved files are stored locally on your device.",
      "Creators & Administrators (Optional Accounts): If you sign up or log in to the Creator or Admin panel, we collect the details you voluntarily provide, including your display name, email address or phone number, passkey/login credentials, OTP email verification records, uploaded video metadata (title, description, thumbnail, stream URL), view analytics, and in-app support chat messages.",
      "Creator Payout Information: Creators who voluntarily submit withdrawal requests provide their UPI ID and payout amount so we can process earnings settlements and maintain payout history.",
      "Telegram Bot Integration (Optional): Creators who choose to connect their account to the VidVora Telegram Upload Bot or Finance Bot share their Telegram User ID, Chat ID, and media files/links sent to the bot solely to publish videos or check earnings.",
      "Device & App Diagnostics: We record an anonymous app installation identifier, app version, basic device model/OS information, and crash/error diagnostics to keep the app stable and deliver optional announcements.",
      "Advertising Data (Google AdMob): VidVora displays banner, interstitial, rewarded, and native ads powered by Google AdMob. Google and its advertising partners may collect device identifiers (such as Android Advertising ID where permitted by your device settings), IP address, approximate coarse location derived from IP, and ad interaction metrics to serve and measure advertisements.",
    ],
  },
  {
    id: "permissions",
    heading: "3. Android Permissions We Use",
    paragraphs: [
      "VidVora follows Google Play's data minimization guidelines and requests only essential permissions:",
    ],
    bullets: [
      "INTERNET & ACCESS_NETWORK_STATE: Required to validate video access links, stream video content, display ads, and sync creator dashboards.",
      "POST_NOTIFICATIONS (Optional on Android 13+): Requested at runtime only if you opt in to receive announcements, creator updates, or payout status alerts. You can disable notifications anytime in Android Settings.",
      "Zero-Permission Media Selection: When creators pick a video or thumbnail from their phone, VidVora uses Android's built-in system Photo Picker / document picker and does not request broad external storage permissions (such as READ_EXTERNAL_STORAGE, READ_MEDIA_IMAGES, or READ_MEDIA_VIDEO).",
    ],
  },
  {
    id: "how-we-use",
    heading: "4. How We Use Information",
    bullets: [
      "Validate access tokens and stream video content smoothly through the built-in media player.",
      "Create and authenticate optional creator/admin accounts, send one-time verification codes (OTP) via email, and reset forgotten passkeys.",
      "Measure genuine video views, calculate creator earnings, and process voluntary UPI withdrawal requests.",
      "Detect and prevent unauthorized stream hotlinking, automated view inflation, abuse, and policy violations.",
      "Display advertisements via Google AdMob to support free video playback and server hosting.",
      "Respond to user and creator inquiries through in-app support chat or email.",
    ],
  },
  {
    id: "third-party-services",
    heading: "5. Third-Party Services & Data Sharing",
    paragraphs: [
      "We never sell your personal data. We share limited technical or operational data only with trusted service providers necessary to operate VidVora:",
    ],
    bullets: [
      "Google AdMob (Advertising): https://policies.google.com/privacy",
      "Google Firebase (Authentication, Cloud Firestore, Cloud Storage, App Check): https://firebase.google.com/support/privacy",
      "Render & Neon PostgreSQL (Encrypted Cloud API Hosting & Database Storage): Used to host backend APIs, share pages, and application records securely over HTTPS/TLS.",
      "Telegram Bot API: Used only when creators actively send videos or commands to our official Telegram bots (https://telegram.org/privacy).",
      "Legal Requirements: We may disclose information if required to do so by law, valid court order, or to protect the safety, rights, and integrity of our users and platform.",
    ],
  },
  {
    id: "data-security",
    heading: "6. Data Security",
    paragraphs: [
      "All data transmitted between the VidVora Android app and our servers is encrypted in transit using HTTPS/TLS. Video access codes and administrative credentials are protected using SHA-256 cryptographic hashing, and video streaming links use HMAC-SHA256 signatures to prevent unauthorized access.",
      "While we apply industry-standard administrative and technical safeguards, no internet transmission or electronic storage system is 100% immune to risk.",
    ],
  },
  {
    id: "data-deletion",
    heading: "7. Data Retention & Account Deletion",
    paragraphs: [
      "We retain creator account records, uploaded video metadata, and support messages only while your account remains active or as needed to provide the Service and comply with legal/accounting obligations.",
      "How to Delete Your Account & Data (Google Play Data Deletion Policy):",
    ],
    bullets: [
      "Email Request: Send an email to teamaltamash@gmail.com with the subject line \"Delete My VidVora Account & Data\" and include your registered email address, phone number, or Creator ID.",
      "In-App Support Request: Open VidVora -> Creator Panel -> Help & Support and submit an account deletion request.",
      "Processing Timeline: Verified deletion requests are completed within 7 business days. Your creator profile, passkey credentials, Telegram bot session, uploaded video links, and support chats will be permanently deleted (completed financial payout records may be retained only where legally required for tax or audit compliance).",
      "Viewer Local Data: Viewers without an account can erase all local watch history, preferences, and cached data immediately via Android Settings -> Apps -> VidVora -> Storage -> Clear Storage.",
    ],
  },
  {
    id: "children-privacy",
    heading: "8. Children's Privacy",
    paragraphs: [
      "VidVora is not directed to children under the age of 13 (or the applicable age of digital consent in your country). We do not knowingly collect personal information from children under 13. If you are a parent or guardian and believe a child has provided personal data to us, please contact teamaltamash@gmail.com and we will promptly remove that information.",
    ],
  },
  {
    id: "your-rights",
    heading: "9. Your Privacy Rights",
    paragraphs: [
      "Depending on your region (including India's Digital Personal Data Protection Act, GDPR, and CCPA), you may have the right to access the personal data we hold about you, request correction of inaccurate information, withdraw consent for optional notifications or personalized ads, and request deletion of your personal data. Contact teamaltamash@gmail.com to exercise any of these rights.",
    ],
  },
  {
    id: "changes-and-contact",
    heading: "10. Policy Updates & Contact Us",
    paragraphs: [
      "We may update this Privacy Policy periodically to reflect app improvements or legal requirements. Any changes will be published on this page with an updated Effective Date.",
      "For privacy questions, grievance redressal, or data deletion requests, contact us at: Developer: VidVora Team (Altamash) | App: VidVora (com.aistudio.vidvora.vstr) | Email: teamaltamash@gmail.com.",
    ],
  },
];

function escapeHtml(text) {
  return String(text || "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function buildPrivacyPolicyHtml(hostBaseUrl = "https://vidvora-share-bot.onrender.com") {
  const cleanHost = String(hostBaseUrl || "https://vidvora-share-bot.onrender.com").replace(/\/+$/, "");
  const pdfUrl = `${cleanHost}/privacy-policy.pdf`;

  const sectionHtml = PRIVACY_SECTIONS.map((sec) => {
    const paras = (sec.paragraphs || [])
      .map((p) => `<p>${escapeHtml(p)}</p>`)
      .join("\n");
    const bullets =
      sec.bullets && sec.bullets.length > 0
        ? `<ul>${sec.bullets.map((b) => `<li>${escapeHtml(b)}</li>`).join("")}</ul>`
        : "";
    return `<section id="${escapeHtml(sec.id)}">
      <h2>${escapeHtml(sec.heading)}</h2>
      ${paras}
      ${bullets}
    </section>`;
  }).join("\n");

  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>Privacy Policy — ${escapeHtml(PRIVACY_POLICY_META.appName)}</title>
  <meta name="description" content="Official Google Play Privacy Policy and Data Deletion instructions for the VidVora Android app." />
  <meta name="theme-color" content="#070d1a" />
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      background: #f5f7fb;
      color: #152238;
      line-height: 1.65;
      padding: 28px 16px 56px;
    }
    .container {
      max-width: 840px;
      margin: 0 auto;
      background: #ffffff;
      border-radius: 18px;
      border: 1px solid #dbe4f3;
      box-shadow: 0 12px 32px rgba(10, 26, 56, 0.08);
      overflow: hidden;
    }
    .hero {
      background: linear-gradient(135deg, #070d1a 0%, #0d2552 65%, #0058d6 100%);
      color: #ffffff;
      padding: 32px 28px;
    }
    .badge {
      display: inline-block;
      background: rgba(1, 231, 253, 0.16);
      color: #79f2ff;
      border: 1px solid rgba(1, 231, 253, 0.4);
      border-radius: 999px;
      padding: 4px 12px;
      font-size: 12px;
      font-weight: 700;
      letter-spacing: 0.6px;
      margin-bottom: 12px;
    }
    .hero h1 { font-size: 28px; font-weight: 800; margin-bottom: 8px; }
    .meta-grid {
      display: flex;
      flex-wrap: wrap;
      gap: 10px 20px;
      font-size: 13px;
      color: #d6e4ff;
      margin-top: 10px;
    }
    .actions { margin-top: 18px; display: flex; flex-wrap: wrap; gap: 10px; }
    .btn {
      display: inline-block;
      padding: 10px 18px;
      border-radius: 10px;
      font-size: 14px;
      font-weight: 700;
      text-decoration: none;
    }
    .btn-primary { background: #01c8fc; color: #051126; }
    .btn-outline { background: rgba(255,255,255,0.12); color: #ffffff; border: 1px solid rgba(255,255,255,0.3); }
    .content { padding: 28px; }
    section { margin-bottom: 26px; }
    h2 {
      font-size: 18px;
      font-weight: 800;
      color: #0b2657;
      border-bottom: 2px solid #e6eefc;
      padding-bottom: 6px;
      margin-bottom: 12px;
    }
    p { margin-bottom: 10px; font-size: 15px; color: #243652; }
    ul { padding-left: 20px; margin-top: 6px; }
    li { margin-bottom: 8px; font-size: 14.5px; color: #243652; }
    footer {
      border-top: 1px solid #e6eefc;
      padding: 18px 28px;
      background: #f8faff;
      font-size: 13px;
      color: #53678a;
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      gap: 8px;
    }
    a.email { color: #0058d6; font-weight: 700; text-decoration: none; }
  </style>
</head>
<body>
  <div class="container">
    <div class="hero">
      <div class="badge">GOOGLE PLAY PRIVACY POLICY</div>
      <h1>${escapeHtml(PRIVACY_POLICY_META.appName)} Privacy Policy</h1>
      <div class="meta-grid">
        <span><strong>Developer:</strong> ${escapeHtml(PRIVACY_POLICY_META.developerName)}</span>
        <span><strong>Package ID:</strong> ${escapeHtml(PRIVACY_POLICY_META.packageName)}</span>
        <span><strong>Effective Date:</strong> ${escapeHtml(PRIVACY_POLICY_META.effectiveDate)}</span>
        <span><strong>Contact:</strong> ${escapeHtml(PRIVACY_POLICY_META.contactEmail)}</span>
      </div>
      <div class="actions">
        <a class="btn btn-primary" href="${escapeHtml(pdfUrl)}" download="VidVora_Privacy_Policy.pdf">Download PDF Copy</a>
        <a class="btn btn-outline" href="#data-deletion">Account &amp; Data Deletion</a>
      </div>
    </div>
    <div class="content">
      ${sectionHtml}
    </div>
    <footer>
      <span>&copy; 2026 ${escapeHtml(PRIVACY_POLICY_META.appName)} &mdash; All rights reserved.</span>
      <span>Contact: <a class="email" href="mailto:${escapeHtml(PRIVACY_POLICY_META.contactEmail)}">${escapeHtml(PRIVACY_POLICY_META.contactEmail)}</a></span>
    </footer>
  </div>
</body>
</html>`;
}

function handlePrivacyPolicyRequest({ res, method = "GET", hostBaseUrl }) {
  const html = buildPrivacyPolicyHtml(hostBaseUrl);
  const buf = Buffer.from(html, "utf8");
  res.writeHead(200, {
    "Content-Type": "text/html; charset=utf-8",
    "Content-Length": buf.length,
    "Cache-Control": "public, max-age=3600",
  });
  if (String(method || "GET").toUpperCase() === "HEAD") {
    res.end();
  } else {
    res.end(buf);
  }
}

async function handlePrivacyPolicyPdfRequest({ res, method = "GET", pdfPath = PRIVACY_PDF_PATH }) {
  const fallbackReleasePath = path.join(__dirname, "..", "release", "VidVora_Privacy_Policy.pdf");
  const targetPath = fs.existsSync(pdfPath) ? pdfPath : fallbackReleasePath;
  if (!fs.existsSync(targetPath)) {
    res.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
    res.end("Privacy Policy PDF not found.");
    return;
  }
  const buf = await fs.promises.readFile(targetPath);
  res.writeHead(200, {
    "Content-Type": "application/pdf",
    "Content-Length": buf.length,
    "Content-Disposition": 'attachment; filename="VidVora_Privacy_Policy.pdf"',
    "Cache-Control": "public, max-age=3600",
  });
  if (String(method || "GET").toUpperCase() === "HEAD") {
    res.end();
  } else {
    res.end(buf);
  }
}

module.exports = {
  PRIVACY_POLICY_META,
  PRIVACY_SECTIONS,
  buildPrivacyPolicyHtml,
  handlePrivacyPolicyRequest,
  handlePrivacyPolicyPdfRequest,
};
