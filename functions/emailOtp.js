const crypto = require("crypto");
const nodemailer = require("nodemailer");

const ADMIN_EMAIL = "teamaltamash@gmail.com";
const ADMIN_DEFAULT_MASTER_KEY = "7827128212";
const MIN_PASSWORD_LENGTH = 4;
const OTP_EXPIRY_MS = 10 * 60 * 1000;
const OTP_RESEND_COOLDOWN_MS = 60 * 1000;
const MAX_OTP_ATTEMPTS = 5;

const DEFAULT_CREATOR_ALLOWED_OPTIONS = [
  "UPLOAD_VIDEOS",
  "MANAGE_VIDEOS",
  "VIEW_EARNINGS",
  "REQUEST_WITHDRAWAL",
  "CHANGE_MASTER_KEY",
];

function normalizeEmail(raw) {
  return String(raw || "").trim().toLowerCase();
}

function isValidEmail(raw) {
  const email = normalizeEmail(raw);
  if (!email || email.length > 160) return false;
  return /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(email);
}

function maskEmail(email) {
  const clean = normalizeEmail(email);
  const parts = clean.split("@");
  if (parts.length !== 2) return clean;
  const local = parts[0];
  const domain = parts[1];
  if (local.length <= 2) {
    return `${local[0] || "*"}***@${domain}`;
  }
  return `${local.slice(0, 2)}${"*".repeat(Math.min(5, local.length - 2))}@${domain}`;
}

function emailDocId(email) {
  return crypto.createHash("sha256").update(normalizeEmail(email), "utf8").digest("hex");
}

function generateOtpCode() {
  return String(crypto.randomInt(100000, 1000000));
}

function hashOtp(email, otp, secret = "") {
  return crypto
    .createHash("sha256")
    .update(`${normalizeEmail(email)}:${String(otp || "").trim()}:${String(secret || "")}`, "utf8")
    .digest("hex");
}

function timingSafeHexEqual(a, b) {
  const hexA = String(a || "").trim();
  const hexB = String(b || "").trim();
  if (!hexA || hexA.length !== hexB.length) return false;
  try {
    return crypto.timingSafeEqual(Buffer.from(hexA, "utf8"), Buffer.from(hexB, "utf8"));
  } catch (_) {
    return false;
  }
}

function resolveEmailTransportConfig(env = process.env) {
  const webhookUrl = String(env.OTP_EMAIL_WEBHOOK_URL || "").trim();
  const webhookSecret = String(env.OTP_EMAIL_WEBHOOK_SECRET || "").trim();
  const brevoApiKey = String(env.BREVO_API_KEY || "").trim();
  const resendApiKey = String(env.RESEND_API_KEY || "").trim();
  const smtpUser = String(env.SMTP_USER || "").trim();
  const smtpPass = String(env.SMTP_PASS || "").trim();
  const smtpHost = String(env.SMTP_HOST || "smtp.gmail.com").trim();
  const smtpPort = parseInt(String(env.SMTP_PORT || "465").trim(), 10) || 465;
  const smtpFrom = String(env.SMTP_FROM || smtpUser || ADMIN_EMAIL).trim();
  const senderName = String(env.EMAIL_SENDER_NAME || "VidVora").trim() || "VidVora";

  const configured = Boolean(
    webhookUrl || brevoApiKey || resendApiKey || (smtpUser && smtpPass)
  );

  return {
    configured,
    webhookUrl,
    webhookSecret,
    brevoApiKey,
    resendApiKey,
    smtpUser,
    smtpPass,
    smtpHost,
    smtpPort,
    smtpFrom,
    senderName,
  };
}

function isEmailTransportConfigured(env = process.env) {
  return resolveEmailTransportConfig(env).configured;
}

function buildOtpEmailContent({ toEmail, recipientName, otp, purpose = "signup" }) {
  const safeName = String(recipientName || "Creator").trim() || "Creator";
  const isReset = purpose === "reset";
  const subject = isReset
    ? `${otp} is your VidVora Password Reset OTP`
    : `${otp} is your VidVora Account Verification OTP`;

  const actionLine = isReset
    ? "Aapne apne VidVora account ka password reset karne ke liye OTP request ki hai."
    : "VidVora Creator Portal par apna account create karne ke liye niche diya gaya 6-digit OTP enter karein.";

  const text = [
    `Hello ${safeName},`,
    "",
    actionLine,
    "",
    `Your VidVora OTP: ${otp}`,
    "",
    "Yeh OTP 10 minutes ke liye valid hai. Kisi ke saath share na karein.",
    "",
    "- VidVora Team",
  ].join("\n");

  const html = `
  <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background:#0F0F12; color:#F5F5F7; padding:24px;">
    <div style="max-width:480px; margin:0 auto; background:#18181D; border:1px solid #2E2E38; border-radius:16px; padding:24px;">
      <div style="font-size:20px; font-weight:800; color:#D4AF37; letter-spacing:0.5px;">VIDVORA</div>
      <h2 style="margin:12px 0 8px; font-size:18px; color:#FFFFFF;">${isReset ? "Reset Password Verification" : "Gmail Verification OTP"}</h2>
      <p style="margin:0 0 18px; font-size:14px; line-height:1.5; color:#C4C4CC;">
        Hello <strong>${safeName.replace(/[<>&"]/g, "")}</strong>,<br/>
        ${actionLine}
      </p>
      <div style="background:#101014; border:1px dashed #D4AF37; border-radius:12px; padding:16px; text-align:center; margin:16px 0;">
        <div style="font-size:11px; text-transform:uppercase; color:#9E9EA9; letter-spacing:1.5px; margin-bottom:6px;">Your 6-Digit OTP</div>
        <div style="font-size:32px; font-weight:900; letter-spacing:8px; color:#D4AF37;">${otp}</div>
      </div>
      <p style="margin:16px 0 0; font-size:12px; color:#8E8E99;">
        Email: ${normalizeEmail(toEmail)}<br/>
        Valid for <strong>10 minutes</strong>. Do not share this code with anyone.
      </p>
    </div>
  </div>`;

  return { subject, text, html };
}

async function sendOtpEmail({
  toEmail,
  recipientName,
  otp,
  purpose = "signup",
  env = process.env,
  fetchImpl = global.fetch,
}) {
  const cfg = resolveEmailTransportConfig(env);
  if (!cfg.configured) {
    const err = new Error(
      "Email OTP service server par configured nahi hai. Render Environment me SMTP_USER + SMTP_PASS (Gmail App Password), BREVO_API_KEY, RESEND_API_KEY ya OTP_EMAIL_WEBHOOK_URL set karein."
    );
    err.code = "EMAIL_NOT_CONFIGURED";
    err.statusCode = 503;
    throw err;
  }

  const cleanTo = normalizeEmail(toEmail);
  const { subject, text, html } = buildOtpEmailContent({
    toEmail: cleanTo,
    recipientName,
    otp,
    purpose,
  });

  // 1. Optional HTTPS Apps Script / Email Webhook (works reliably even if SMTP ports are restricted)
  if (cfg.webhookUrl) {
    const res = await fetchImpl(cfg.webhookUrl, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(cfg.webhookSecret ? { "x-webhook-secret": cfg.webhookSecret } : {}),
      },
      body: JSON.stringify({
        to: cleanTo,
        subject,
        text,
        html,
        otp,
        recipientName: String(recipientName || "Creator"),
        secret: cfg.webhookSecret || undefined,
      }),
    });
    if (!res.ok) {
      const bodyText = await res.text().catch(() => "");
      throw new Error(`Email webhook failed (${res.status}): ${bodyText.slice(0, 180)}`);
    }
    return { provider: "webhook" };
  }

  // 2. Brevo Transactional HTTPS API (works over port 443 with verified Gmail sender)
  if (cfg.brevoApiKey) {
    const res = await fetchImpl("https://api.brevo.com/v3/smtp/email", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json",
        "api-key": cfg.brevoApiKey,
      },
      body: JSON.stringify({
        sender: { name: cfg.senderName, email: cfg.smtpFrom },
        to: [{ email: cleanTo, name: String(recipientName || "Creator") }],
        subject,
        htmlContent: html,
        textContent: text,
      }),
    });
    if (!res.ok) {
      const bodyText = await res.text().catch(() => "");
      throw new Error(`Brevo email delivery failed (${res.status}): ${bodyText.slice(0, 180)}`);
    }
    return { provider: "brevo" };
  }

  // 3. Resend Transactional HTTPS API
  if (cfg.resendApiKey) {
    const res = await fetchImpl("https://api.resend.com/emails", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${cfg.resendApiKey}`,
      },
      body: JSON.stringify({
        from: `${cfg.senderName} <${cfg.smtpFrom}>`,
        to: [cleanTo],
        subject,
        html,
        text,
      }),
    });
    if (!res.ok) {
      const bodyText = await res.text().catch(() => "");
      throw new Error(`Resend email delivery failed (${res.status}): ${bodyText.slice(0, 180)}`);
    }
    return { provider: "resend" };
  }

  // 4. Nodemailer SMTP (Gmail App Password / Custom SMTP)
  const transporter = nodemailer.createTransport({
    host: cfg.smtpHost,
    port: cfg.smtpPort,
    secure: cfg.smtpPort === 465,
    auth: {
      user: cfg.smtpUser,
      pass: cfg.smtpPass,
    },
    connectionTimeout: 12000,
    greetingTimeout: 12000,
    socketTimeout: 15000,
  });

  await transporter.sendMail({
    from: `"${cfg.senderName}" <${cfg.smtpFrom}>`,
    to: cleanTo,
    subject,
    text,
    html,
  });
  return { provider: "smtp" };
}

async function findMemberByEmail(db, email) {
  const cleanEmail = normalizeEmail(email);
  if (!cleanEmail) return null;
  const membersSnap = await db.collection("members").get();
  for (const doc of membersSnap.docs) {
    const data = doc.data() || {};
    if (normalizeEmail(data.emailOrPhone) === cleanEmail) {
      return { id: doc.id, ref: doc.ref, data };
    }
  }
  return null;
}

function validateSignupFields({ name, email, password }) {
  const cleanName = String(name || "").trim();
  const cleanEmail = normalizeEmail(email);
  const cleanPass = String(password || "").trim();

  if (!cleanName || cleanName.length > 80) {
    return "Kripya apna valid Name enter karein (max 80 chars).";
  }
  if (!isValidEmail(cleanEmail)) {
    return "Kripya valid Gmail / Email address enter karein.";
  }
  if (cleanEmail === ADMIN_EMAIL) {
    return "Yeh email Superadmin ke liye reserved hai. Login tab se sign in karein.";
  }
  if (cleanPass.length < MIN_PASSWORD_LENGTH || cleanPass.length > 128) {
    return `Password kam se kam ${MIN_PASSWORD_LENGTH} characters ka hona chahiye.`;
  }
  if (cleanPass === ADMIN_DEFAULT_MASTER_KEY) {
    return "Kripya koi dusra Password chunein.";
  }
  return null;
}

function serializeMemberForClient(docData, fallbackId = "") {
  const allowed = Array.isArray(docData.allowedOptions) && docData.allowedOptions.length > 0
    ? docData.allowedOptions
    : [...DEFAULT_CREATOR_ALLOWED_OPTIONS];
  return {
    memberId: String(docData.memberId || fallbackId || ""),
    name: String(docData.name || "Creator"),
    emailOrPhone: String(docData.emailOrPhone || ""),
    passkey: String(docData.passkey || ""),
    role: String(docData.role || "member"),
    allowedOptions: allowed,
    isActive: docData.isActive !== false,
    totalEarning: Number(docData.totalEarning || 0),
    creditedViews: Number(docData.creditedViews || 0),
    viewRatePer1000: Number(docData.viewRatePer1000 || 190),
  };
}

async function handleSendOtp({
  body = {},
  db,
  playbackSecret = "",
  purpose = "signup",
  sendEmailFn = sendOtpEmail,
  nowMs = Date.now(),
}) {
  const resolvedPurpose = purpose === "reset" || body.purpose === "reset" ? "reset" : "signup";
  const cleanEmail = normalizeEmail(body.email);

  if (!isValidEmail(cleanEmail)) {
    return {
      statusCode: 400,
      payload: { ok: false, error: "Kripya valid Gmail / Email address enter karein." },
    };
  }
  if (cleanEmail === ADMIN_EMAIL) {
    return {
      statusCode: 403,
      payload: {
        ok: false,
        error: "Superadmin email par OTP signup/reset allow nahi hai. Login tab use karein.",
      },
    };
  }

  let recipientName = String(body.name || "").trim();

  if (resolvedPurpose === "signup") {
    const fieldErr = validateSignupFields({
      name: recipientName,
      email: cleanEmail,
      password: body.password,
    });
    if (fieldErr) {
      return { statusCode: 400, payload: { ok: false, error: fieldErr } };
    }
    const existing = await findMemberByEmail(db, cleanEmail);
    if (existing) {
      return {
        statusCode: 409,
        payload: {
          ok: false,
          error: "Yeh Gmail pehle se registered hai. Kripya Login tab se sign in karein.",
        },
      };
    }
  } else {
    const existing = await findMemberByEmail(db, cleanEmail);
    if (!existing) {
      return {
        statusCode: 404,
        payload: { ok: false, error: "Is Gmail par koi VidVora account nahi mila." },
      };
    }
    if (existing.data.isActive === false) {
      return {
        statusCode: 403,
        payload: { ok: false, error: "Aapka account Admin dwara paused hai." },
      };
    }
    recipientName = String(existing.data.name || "Creator").trim() || "Creator";
  }

  const docId = emailDocId(cleanEmail);
  const otpRef = db.collection("emailOtpCodes").doc(docId);
  const prevSnap = await otpRef.get();
  if (prevSnap.exists) {
    const prevData = prevSnap.data() || {};
    const lastSent = Number(prevData.lastSentAtMs || 0);
    const elapsed = nowMs - lastSent;
    if (lastSent > 0 && elapsed < OTP_RESEND_COOLDOWN_MS && prevData.purpose === resolvedPurpose) {
      const waitSec = Math.max(1, Math.ceil((OTP_RESEND_COOLDOWN_MS - elapsed) / 1000));
      return {
        statusCode: 429,
        payload: {
          ok: false,
          retryAfterSec: waitSec,
          error: `OTP abhi bheja gaya hai. ${waitSec}s baad Resend karein.`,
        },
      };
    }
  }

  const otp = generateOtpCode();
  const otpHash = hashOtp(cleanEmail, otp, playbackSecret);

  try {
    await sendEmailFn({
      toEmail: cleanEmail,
      recipientName,
      otp,
      purpose: resolvedPurpose,
    });
  } catch (err) {
    const statusCode = err?.statusCode || 502;
    return {
      statusCode,
      payload: {
        ok: false,
        code: err?.code || "EMAIL_SEND_FAILED",
        error: err?.message || "Gmail par OTP send nahi ho paya.",
      },
    };
  }

  await otpRef.set({
    email: cleanEmail,
    purpose: resolvedPurpose,
    otpHash,
    attempts: 0,
    createdAtMs: nowMs,
    lastSentAtMs: nowMs,
    expiresAtMs: nowMs + OTP_EXPIRY_MS,
  });

  return {
    statusCode: 200,
    payload: {
      ok: true,
      email: cleanEmail,
      maskedEmail: maskEmail(cleanEmail),
      expiresInSeconds: Math.floor(OTP_EXPIRY_MS / 1000),
      cooldownSeconds: Math.floor(OTP_RESEND_COOLDOWN_MS / 1000),
      message: `${cleanEmail} par 6-digit OTP bhej diya gaya hai (Spam/Promotions folder bhi check karein).`,
    },
  };
}

async function verifyStoredOtpRecord({ otpRef, email, otp, expectedPurpose, playbackSecret, nowMs }) {
  const cleanOtp = String(otp || "").trim();
  if (!/^\d{6}$/.test(cleanOtp)) {
    return {
      ok: false,
      statusCode: 400,
      error: "Kripya 6-digit numeric OTP enter karein.",
    };
  }

  const snap = await otpRef.get();
  if (!snap.exists) {
    return {
      ok: false,
      statusCode: 400,
      error: "OTP expire ho chuka hai ya bheja nahi gaya. Naya OTP send karein.",
    };
  }

  const data = snap.data() || {};
  if (data.purpose && data.purpose !== expectedPurpose) {
    return {
      ok: false,
      statusCode: 400,
      error: "OTP purpose match nahi kar raha. Dobara OTP send karein.",
    };
  }

  if (nowMs > Number(data.expiresAtMs || 0)) {
    await otpRef.delete().catch(() => {});
    return {
      ok: false,
      statusCode: 400,
      error: "OTP expire ho chuka hai. Kripya Resend OTP dabaye.",
    };
  }

  const expectedHash = hashOtp(email, cleanOtp, playbackSecret);
  if (!timingSafeHexEqual(expectedHash, data.otpHash)) {
    const nextAttempts = Number(data.attempts || 0) + 1;
    if (nextAttempts >= MAX_OTP_ATTEMPTS) {
      await otpRef.delete().catch(() => {});
      return {
        ok: false,
        statusCode: 429,
        error: "Bahut baar galat OTP dala gaya. Kripya naya OTP mangwayein.",
      };
    }
    await otpRef.set({ attempts: nextAttempts }, { merge: true });
    const left = MAX_OTP_ATTEMPTS - nextAttempts;
    return {
      ok: false,
      statusCode: 400,
      error: `Galat OTP hai. (${left} attempts bache hain)`,
    };
  }

  await otpRef.delete().catch(() => {});
  return { ok: true };
}

async function handleVerifySignupOtp({
  body = {},
  db,
  playbackSecret = "",
  nowMs = Date.now(),
}) {
  const cleanName = String(body.name || "").trim();
  const cleanEmail = normalizeEmail(body.email);
  const cleanPass = String(body.password || "").trim();

  const fieldErr = validateSignupFields({
    name: cleanName,
    email: cleanEmail,
    password: cleanPass,
  });
  if (fieldErr) {
    return { statusCode: 400, payload: { ok: false, error: fieldErr } };
  }

  const otpRef = db.collection("emailOtpCodes").doc(emailDocId(cleanEmail));
  const otpCheck = await verifyStoredOtpRecord({
    otpRef,
    email: cleanEmail,
    otp: body.otp,
    expectedPurpose: "signup",
    playbackSecret,
    nowMs,
  });
  if (!otpCheck.ok) {
    return {
      statusCode: otpCheck.statusCode,
      payload: { ok: false, error: otpCheck.error },
    };
  }

  const existing = await findMemberByEmail(db, cleanEmail);
  if (existing) {
    return {
      statusCode: 409,
      payload: {
        ok: false,
        error: "Yeh Gmail pehle se registered hai. Kripya Login tab se sign in karein.",
      },
    };
  }

  const memberId = "mem_" + crypto.randomUUID().replace(/-/g, "").slice(0, 10);
  const newMemberData = {
    memberId,
    name: cleanName,
    emailOrPhone: cleanEmail,
    passkey: cleanPass,
    role: "member",
    allowedOptions: [...DEFAULT_CREATOR_ALLOWED_OPTIONS],
    isActive: true,
    createdAt: new Date(nowMs),
    totalEarning: 0,
    creditedViews: 0,
    viewRatePer1000: 190,
  };

  const membersCol = db.collection("members");
  const memberDocRef = typeof membersCol.doc === "function"
    ? membersCol.doc(memberId)
    : membersCol.document(memberId);
  await memberDocRef.set(newMemberData);

  return {
    statusCode: 200,
    payload: {
      ok: true,
      message: "Gmail verify ho gaya! Account create ho chuka hai.",
      member: serializeMemberForClient(newMemberData, memberId),
    },
  };
}

async function handleDirectSignup({
  body = {},
  db,
  nowMs = Date.now(),
}) {
  const cleanName = String(body.name || "").trim();
  const cleanEmail = normalizeEmail(body.email);
  const cleanPass = String(body.password || "").trim();
  const cleanConfirm = String(body.confirmPassword ?? body.confirm_password ?? "").trim();

  const fieldErr = validateSignupFields({
    name: cleanName,
    email: cleanEmail,
    password: cleanPass,
  });
  if (fieldErr) {
    return { statusCode: 400, payload: { ok: false, error: fieldErr } };
  }

  if (!cleanConfirm || cleanConfirm !== cleanPass) {
    return {
      statusCode: 400,
      payload: {
        ok: false,
        error: "Dono Password match nahi kar rahe hain. Kripya same password dalein.",
      },
    };
  }

  const existing = await findMemberByEmail(db, cleanEmail);
  if (existing) {
    return {
      statusCode: 409,
      payload: {
        ok: false,
        error: "Yeh Gmail pehle se registered hai. Kripya Login tab se sign in karein.",
      },
    };
  }

  const memberId = "mem_" + crypto.randomUUID().replace(/-/g, "").slice(0, 10);
  const newMemberData = {
    memberId,
    name: cleanName,
    emailOrPhone: cleanEmail,
    passkey: cleanPass,
    role: "member",
    allowedOptions: [...DEFAULT_CREATOR_ALLOWED_OPTIONS],
    isActive: true,
    createdAt: new Date(nowMs),
    totalEarning: 0,
    creditedViews: 0,
    viewRatePer1000: 190,
  };

  const membersCol = db.collection("members");
  const memberDocRef = typeof membersCol.doc === "function"
    ? membersCol.doc(memberId)
    : membersCol.document(memberId);
  await memberDocRef.set(newMemberData);

  return {
    statusCode: 200,
    payload: {
      ok: true,
      message: "Account create ho gaya! Creator Panel khul raha hai.",
      member: serializeMemberForClient(newMemberData, memberId),
    },
  };
}

async function handleVerifyResetOtp({
  body = {},
  db,
  playbackSecret = "",
  nowMs = Date.now(),
}) {
  const cleanEmail = normalizeEmail(body.email);
  const cleanPass = String(body.newPassword || body.password || "").trim();

  if (!isValidEmail(cleanEmail)) {
    return { statusCode: 400, payload: { ok: false, error: "Valid Gmail address enter karein." } };
  }
  if (cleanEmail === ADMIN_EMAIL) {
    return {
      statusCode: 403,
      payload: { ok: false, error: "Superadmin password Admin Panel settings se change karein." },
    };
  }
  if (cleanPass.length < MIN_PASSWORD_LENGTH || cleanPass.length > 128) {
    return {
      statusCode: 400,
      payload: { ok: false, error: `New Password kam se kam ${MIN_PASSWORD_LENGTH} characters ka hona chahiye.` },
    };
  }
  if (cleanPass === ADMIN_DEFAULT_MASTER_KEY) {
    return {
      statusCode: 400,
      payload: { ok: false, error: "Kripya koi dusra Password chunein." },
    };
  }

  const existing = await findMemberByEmail(db, cleanEmail);
  if (!existing) {
    return { statusCode: 404, payload: { ok: false, error: "Is Gmail par koi account nahi mila." } };
  }
  if (existing.data.isActive === false) {
    return { statusCode: 403, payload: { ok: false, error: "Aapka account Admin dwara paused hai." } };
  }

  const otpRef = db.collection("emailOtpCodes").doc(emailDocId(cleanEmail));
  const otpCheck = await verifyStoredOtpRecord({
    otpRef,
    email: cleanEmail,
    otp: body.otp,
    expectedPurpose: "reset",
    playbackSecret,
    nowMs,
  });
  if (!otpCheck.ok) {
    return {
      statusCode: otpCheck.statusCode,
      payload: { ok: false, error: otpCheck.error },
    };
  }

  await existing.ref.update({ passkey: cleanPass });
  const updatedMember = serializeMemberForClient(
    { ...existing.data, passkey: cleanPass },
    existing.id
  );

  return {
    statusCode: 200,
    payload: {
      ok: true,
      message: "Password update ho gaya aur aap login ho gaye!",
      member: updatedMember,
    },
  };
}

module.exports = {
  ADMIN_EMAIL,
  DEFAULT_CREATOR_ALLOWED_OPTIONS,
  OTP_EXPIRY_MS,
  OTP_RESEND_COOLDOWN_MS,
  MAX_OTP_ATTEMPTS,
  normalizeEmail,
  isValidEmail,
  maskEmail,
  emailDocId,
  generateOtpCode,
  hashOtp,
  resolveEmailTransportConfig,
  isEmailTransportConfigured,
  buildOtpEmailContent,
  sendOtpEmail,
  findMemberByEmail,
  handleSendOtp,
  handleDirectSignup,
  handleVerifySignupOtp,
  handleVerifyResetOtp,
};
