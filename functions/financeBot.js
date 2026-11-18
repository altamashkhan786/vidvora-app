const {
  escapeMarkdown,
  telegramApiJson,
  parseLoginCommand,
  authenticateCreatorMember,
} = require("./telegramBot");

const MIN_WITHDRAWAL_INR = 500;
const DEFAULT_VIEW_RATE_PER_1000 = 190;

const CREATOR_REPLY_KEYBOARD = {
  keyboard: [
    [{ text: "💰 Balance" }, { text: "💸 Withdraw UPI" }],
    [{ text: "📜 Payout History" }, { text: "❓ Help" }],
  ],
  resize_keyboard: true,
  is_persistent: true,
};

const ADMIN_REPLY_KEYBOARD = {
  keyboard: [
    [{ text: "⏳ Pending Payouts" }, { text: "📊 Finance Summary" }],
    [{ text: "💰 Balance" }, { text: "❓ Help" }],
  ],
  resize_keyboard: true,
  is_persistent: true,
};

function buildAmountReplyKeyboard(availableBalance) {
  const safeMax = Math.max(0, Math.floor(Number(availableBalance) || 0));
  const candidates = [500, 1000, 2000, 5000].filter((amt) => amt <= safeMax && amt !== safeMax);
  const buttons = [{ text: `💵 All ${formatInr(availableBalance)}` }];
  for (const amt of candidates) {
    buttons.push({ text: `💸 ${formatInr(amt)}` });
  }
  const rows = [];
  for (let i = 0; i < buttons.length; i += 2) {
    rows.push(buttons.slice(i, i + 2));
  }
  rows.push([{ text: "❌ Cancel" }]);
  return {
    keyboard: rows,
    resize_keyboard: true,
    one_time_keyboard: true,
  };
}

function buildUpiReplyKeyboard(lastUpiId) {
  const rows = [];
  if (lastUpiId && isValidUpiId(lastUpiId)) {
    rows.push([{ text: `✅ Use ${lastUpiId}` }]);
  }
  rows.push([{ text: "❌ Cancel" }]);
  return {
    keyboard: rows,
    resize_keyboard: true,
    one_time_keyboard: true,
  };
}

function resolveMemberSavedUpiId(existingSession, snapshot) {
  const sessionUpi = String(existingSession?.lastUpiId || "").trim();
  if (isValidUpiId(sessionUpi)) return sessionUpi;
  const recentReq = (snapshot?.requests || []).find((r) => isValidUpiId(r?.upiId));
  return recentReq ? String(recentReq.upiId).trim() : "";
}

function parseQuickAmountInput(rawText, availableBalance) {
  const clean = String(rawText || "").trim();
  const lower = clean.toLowerCase();
  if (
    lower === "all" ||
    lower === "full" ||
    lower === "max" ||
    /^💵\s*all\b/i.test(clean)
  ) {
    return Math.max(0, Number(availableBalance) || 0);
  }
  const stripped = clean
    .replace(/^💸\s*/g, "")
    .replace(/[₹,\s]/g, "");
  return Number(stripped);
}

function isValidUpiId(upiId) {
  const clean = String(upiId || "").trim();
  return /^[^\s@]{2,}@[^\s@]{2,}$/.test(clean);
}

function formatInr(amount) {
  const safe = Math.max(0, Number(amount) || 0);
  if (Math.abs(safe - Math.round(safe)) < 0.005) {
    return `₹${Math.round(safe).toLocaleString("en-IN")}`;
  }
  return `₹${safe.toLocaleString("en-IN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
}

function formatTimestampShort(ts) {
  try {
    if (!ts) return "Just now";
    const date =
      typeof ts.toDate === "function"
        ? ts.toDate()
        : ts._seconds
          ? new Date(ts._seconds * 1000)
          : new Date(ts);
    if (Number.isNaN(date.getTime())) return "Just now";
    return date.toLocaleDateString("en-IN", {
      day: "2-digit",
      month: "short",
      year: "numeric",
    });
  } catch (_) {
    return "Just now";
  }
}

function toEpochMs(ts) {
  try {
    if (!ts) return 0;
    if (typeof ts.toMillis === "function") return ts.toMillis();
    if (typeof ts.toDate === "function") return ts.toDate().getTime();
    if (typeof ts._seconds === "number") return ts._seconds * 1000;
    const d = new Date(ts);
    return Number.isNaN(d.getTime()) ? 0 : d.getTime();
  } catch (_) {
    return 0;
  }
}

function computeCreatorEarningSummary(totalEarning, withdrawalRequests) {
  const total = Math.max(0, Number(totalEarning) || 0);
  let pendingOrProcessingAmount = 0;
  let paidAmount = 0;

  for (const req of withdrawalRequests || []) {
    const amt = Math.max(0, Number(req.amount) || 0);
    const status = String(req.status || "PENDING").trim().toUpperCase();
    if (status === "PENDING" || status === "PROCESSING") {
      pendingOrProcessingAmount += amt;
    } else if (status === "DONE") {
      paidAmount += amt;
    }
  }

  const availableBalance = Math.max(0, total - pendingOrProcessingAmount - paidAmount);
  return {
    totalEarning: total,
    pendingOrProcessingAmount,
    paidAmount,
    availableBalance,
  };
}

function statusBadge(status) {
  switch (String(status || "PENDING").trim().toUpperCase()) {
    case "DONE":
      return "✅ PAID (DONE)";
    case "PROCESSING":
      return "🔄 PROCESSING";
    case "REJECTED":
      return "❌ REJECTED";
    default:
      return "⏳ PENDING";
  }
}

function normalizeDocData(doc) {
  if (!doc) return null;
  if (typeof doc.data === "function") {
    return { id: doc.id, ...(doc.data() || {}) };
  }
  return { ...doc };
}

function getAllDocsFromQuerySnapshot(snap) {
  if (!snap || snap.empty || !Array.isArray(snap.docs)) return [];
  return snap.docs.map(normalizeDocData).filter(Boolean);
}

async function resolveFinanceBotRuntimeConfig(db) {
  let botToken = (
    process.env.FINANCE_BOT_TOKEN ||
    process.env.FINANCE_TELEGRAM_BOT_TOKEN ||
    ""
  ).trim();
  const apiBase = (process.env.TELEGRAM_API_BASE || "https://api.telegram.org")
    .trim()
    .replace(/\/+$/, "");
  let webhookSecret = (
    process.env.FINANCE_TELEGRAM_WEBHOOK_SECRET ||
    process.env.TELEGRAM_WEBHOOK_SECRET ||
    ""
  ).trim();

  if (!botToken) {
    try {
      const financeSnap = await db.collection("appConfig").doc("telegramFinanceBot").get();
      if (financeSnap.exists) {
        const fData = financeSnap.data() || {};
        if (typeof fData.botToken === "string" && fData.botToken.trim()) {
          botToken = fData.botToken.trim();
        }
        if (!webhookSecret && typeof fData.webhookSecret === "string" && fData.webhookSecret.trim()) {
          webhookSecret = fData.webhookSecret.trim();
        }
      }
    } catch (_) {
      // Ignore config read errors
    }
  }

  if (!botToken) {
    try {
      const botSnap = await db.collection("appConfig").doc("telegramBot").get();
      if (botSnap.exists) {
        const bData = botSnap.data() || {};
        if (typeof bData.financeBotToken === "string" && bData.financeBotToken.trim()) {
          botToken = bData.financeBotToken.trim();
        }
      }
    } catch (_) {
      // Ignore config read errors
    }
  }

  return { botToken, apiBase, webhookSecret };
}

function hasCreatorFinanceAccess(authUser) {
  if (!authUser) return false;
  if (authUser.isAdmin || authUser.memberId === "admin") return true;
  const opts = Array.isArray(authUser.allowedOptions) ? authUser.allowedOptions : null;
  if (!opts) return true;
  return (
    opts.includes("VIEW_EARNINGS") ||
    opts.includes("REQUEST_WITHDRAWAL") ||
    opts.includes("MANAGE_PAYOUTS")
  );
}

function canCreatorViewEarnings(memberData) {
  if (!memberData || memberData.isActive === false) return false;
  const opts = Array.isArray(memberData.allowedOptions) ? memberData.allowedOptions : null;
  if (!opts) return true;
  return opts.includes("VIEW_EARNINGS") || opts.includes("REQUEST_WITHDRAWAL");
}

function canCreatorWithdraw(memberData) {
  if (!memberData || memberData.isActive === false) return false;
  const opts = Array.isArray(memberData.allowedOptions) ? memberData.allowedOptions : null;
  if (!opts) return true;
  return opts.includes("REQUEST_WITHDRAWAL");
}

function isFinanceAdminSession(session, memberData) {
  if (session && session.memberId === "admin") return true;
  if (!memberData || memberData.isActive === false) return false;
  const opts = Array.isArray(memberData.allowedOptions) ? memberData.allowedOptions : [];
  return opts.includes("MANAGE_PAYOUTS");
}

async function fetchCreatorFinanceSnapshot(db, memberId) {
  const memberSnap = await db.collection("members").doc(memberId).get();
  if (!memberSnap.exists) {
    return null;
  }
  const memberData = { memberId, ...(memberSnap.data() || {}) };

  const videosQuery = await db.collection("videos").where("uploadedBy", "==", memberId).get();
  const memberVideos = getAllDocsFromQuerySnapshot(videosQuery).sort(
    (a, b) => (Number(b.totalViews) || 0) - (Number(a.totalViews) || 0)
  );

  const withdrawQuery = await db
    .collection("withdrawalRequests")
    .where("memberId", "==", memberId)
    .get();
  const requests = getAllDocsFromQuerySnapshot(withdrawQuery).sort(
    (a, b) => toEpochMs(b.requestedAt) - toEpochMs(a.requestedAt)
  );

  const realViews = memberVideos.reduce((sum, v) => sum + Math.max(0, Number(v.totalViews) || 0), 0);
  const creditedViews = Math.max(0, Number(memberData.creditedViews) || 0);
  const viewRate =
    Number(memberData.viewRatePer1000) > 0
      ? Number(memberData.viewRatePer1000)
      : DEFAULT_VIEW_RATE_PER_1000;
  const totalEarning = Math.max(0, Number(memberData.totalEarning) || 0);
  const summary = computeCreatorEarningSummary(totalEarning, requests);

  return {
    member: memberData,
    videos: memberVideos,
    activeVideosCount: memberVideos.filter((v) => v.isActive !== false).length,
    realViews,
    creditedViews,
    viewRate,
    requests,
    summary,
  };
}

async function createCreatorWithdrawalRequest(db, snapshot, amount, upiId) {
  const member = snapshot.member;
  if (!canCreatorWithdraw(member)) {
    return {
      ok: false,
      error: "⛔ Withdrawal permission is disabled on your Creator account.",
    };
  }
  const cleanUpi = String(upiId || "").trim();
  if (!isValidUpiId(cleanUpi)) {
    return {
      ok: false,
      error: "❌ Invalid UPI ID. Example: `name@ybl`, `9876543210@paytm`, `user@okicici`.",
    };
  }
  const parsedAmount = Number(amount);
  if (!Number.isFinite(parsedAmount) || parsedAmount < MIN_WITHDRAWAL_INR) {
    return {
      ok: false,
      error: `⚠️ Minimum withdrawal amount *${formatInr(MIN_WITHDRAWAL_INR)}* hai.`,
    };
  }
  if (parsedAmount > snapshot.summary.availableBalance + 0.001) {
    return {
      ok: false,
      error: `❌ Insufficient balance. Aapka Available Balance *${formatInr(snapshot.summary.availableBalance)}* hai.`,
    };
  }

  const crypto = require("crypto");
  const requestId = "wdr_" + crypto.randomUUID().replace(/-/g, "").slice(0, 12);
  const now = new Date();
  const payload = {
    requestId,
    memberId: member.memberId,
    creatorName: String(member.name || "Creator").trim() || "Creator",
    creatorEmail: String(member.emailOrPhone || "").trim(),
    amount: parsedAmount,
    upiId: cleanUpi,
    status: "PENDING",
    adminNote: "",
    requestedAt: now,
    updatedAt: now,
  };

  await db.collection("withdrawalRequests").doc(requestId).set(payload);
  return { ok: true, request: payload };
}

async function notifyCreatorFinanceStatusChange({
  db,
  memberId,
  requestId,
  amount,
  upiId,
  status,
  adminNote,
}) {
  try {
    if (!db || !memberId) return { notifiedCount: 0 };
    const { botToken, apiBase } = await resolveFinanceBotRuntimeConfig(db);
    if (!botToken) return { notifiedCount: 0 };

    const sessionSnap = await db
      .collection("telegramFinanceSessions")
      .where("memberId", "==", memberId)
      .get();
    const sessions = getAllDocsFromQuerySnapshot(sessionSnap);
    if (!sessions.length) return { notifiedCount: 0 };

    const noteLine = adminNote ? `\n📝 *Admin Note:* ${escapeMarkdown(adminNote)}` : "";
    const text = [
      `📣 *Payout Status Update*`,
      "",
      `• *Request ID:* \`${requestId}\``,
      `• *Amount:* ${formatInr(amount)}`,
      `• *UPI ID:* \`${escapeMarkdown(upiId)}\``,
      `• *Status:* ${statusBadge(status)}${noteLine}`,
    ].join("\n");

    let notifiedCount = 0;
    for (const s of sessions) {
      if (!s.chatId) continue;
      try {
        await telegramApiJson(apiBase, botToken, "sendMessage", {
          chat_id: s.chatId,
          text,
          parse_mode: "Markdown",
        });
        notifiedCount += 1;
      } catch (_) {
        // Ignore delivery errors per user
      }
    }
    return { notifiedCount };
  } catch (_) {
    return { notifiedCount: 0 };
  }
}

async function buildPlatformFinanceSummary(db) {
  const [membersSnap, withdrawalsSnap] = await Promise.all([
    db.collection("members").get(),
    db.collection("withdrawalRequests").get(),
  ]);
  const members = getAllDocsFromQuerySnapshot(membersSnap);
  const requests = getAllDocsFromQuerySnapshot(withdrawalsSnap).sort(
    (a, b) => toEpochMs(b.requestedAt) - toEpochMs(a.requestedAt)
  );

  let totalCreatorEarned = 0;
  for (const m of members) {
    totalCreatorEarned += Math.max(0, Number(m.totalEarning) || 0);
  }
  let pendingSum = 0;
  let pendingCount = 0;
  let processingSum = 0;
  let processingCount = 0;
  let paidSum = 0;
  let paidCount = 0;

  for (const r of requests) {
    const amt = Math.max(0, Number(r.amount) || 0);
    const st = String(r.status || "PENDING").toUpperCase();
    if (st === "PENDING") {
      pendingSum += amt;
      pendingCount += 1;
    } else if (st === "PROCESSING") {
      processingSum += amt;
      processingCount += 1;
    } else if (st === "DONE") {
      paidSum += amt;
      paidCount += 1;
    }
  }

  return {
    totalCreators: members.length,
    totalCreatorEarned,
    pendingSum,
    pendingCount,
    processingSum,
    processingCount,
    paidSum,
    paidCount,
    actionableRequests: requests.filter((r) => {
      const st = String(r.status || "PENDING").toUpperCase();
      return st === "PENDING" || st === "PROCESSING";
    }),
  };
}

function normalizeCommandIntent(rawText) {
  const clean = String(rawText || "").trim();
  const lower = clean.toLowerCase();
  if (/^\/start(?:@\w+)?/i.test(clean) || /^\/help(?:@\w+)?/i.test(clean) || clean === "❓ Help") {
    return "HELP";
  }
  if (/^\/logout(?:@\w+)?/i.test(clean)) return "LOGOUT";
  if (/^\/cancel(?:@\w+)?/i.test(clean) || clean === "❌ Cancel" || lower === "cancel") {
    return "CANCEL";
  }
  if (/^\/status(?:@\w+)?/i.test(clean)) return "STATUS";
  if (
    /^\/(balance|earnings|wallet)(?:@\w+)?/i.test(clean) ||
    clean === "💰 Balance" ||
    lower === "balance" ||
    lower === "earning" ||
    lower === "earnings"
  ) {
    return "BALANCE";
  }
  if (/^\/withdraw(?:@\w+)?/i.test(clean) || clean === "💸 Withdraw UPI" || lower === "withdraw") {
    return "WITHDRAW";
  }
  if (
    /^\/(history|payouts)(?:@\w+)?/i.test(clean) ||
    clean === "📜 Payout History" ||
    lower === "history" ||
    lower === "payouts"
  ) {
    return "HISTORY";
  }
  if (/^\/pending(?:@\w+)?/i.test(clean) || clean === "⏳ Pending Payouts") {
    return "ADMIN_PENDING";
  }
  if (/^\/summary(?:@\w+)?/i.test(clean) || clean === "📊 Finance Summary") {
    return "ADMIN_SUMMARY";
  }
  if (/^\/approve(?:@\w+)?(?:\s+|$)/i.test(clean)) return "ADMIN_APPROVE";
  if (/^\/processing(?:@\w+)?(?:\s+|$)/i.test(clean)) return "ADMIN_PROCESSING";
  if (/^\/reject(?:@\w+)?(?:\s+|$)/i.test(clean)) return "ADMIN_REJECT";
  return null;
}

async function handleFinanceTelegramUpdate({ update, db }) {
  const message = update && (update.message || update.edited_message);
  if (!message || !message.chat) {
    return { ok: true, skipped: "no_message" };
  }

  const chatId = message.chat.id;
  const telegramUserId = String((message.from && message.from.id) || chatId);
  const { botToken, apiBase } = await resolveFinanceBotRuntimeConfig(db);
  if (!botToken) {
    throw new Error("FINANCE_BOT_TOKEN not configured");
  }

  const replyText = async (text, extra = {}) =>
    telegramApiJson(apiBase, botToken, "sendMessage", {
      chat_id: chatId,
      text,
      ...extra,
    });

  const textBody = String(message.text || "").trim();
  const cmd = normalizeCommandIntent(textBody);

  if (cmd === "HELP") {
    await replyText(
      [
        "💰 *VidVora Creator Finance Bot*",
        "",
        "1️⃣ *Account Link Karein:*",
        "_(Bas apna VidVora Password directly bhejein)_",
        "",
        "2️⃣ *Creator Commands:*",
        "• `/balance` — Total & Available ₹ Balance",
        "• `/withdraw` — UPI Withdrawal Request (Min ₹500)",
        "• `/history` — Payout Requests & Status",
        "",
        "3️⃣ *Admin Payout Commands:*",
        "• `/pending` • `/summary` • `/approve ID` • `/reject ID`",
        "",
        "Other: `/status` • `/cancel` • `/logout`",
      ].join("\n"),
      { parse_mode: "Markdown" }
    );
    return { ok: true, action: "help" };
  }

  if (cmd === "LOGOUT") {
    await db.collection("telegramFinanceSessions").doc(telegramUserId).delete();
    await replyText(
      "🔓 Finance Bot se account unlink ho gaya hai. Dobara connect karne ke liye apna *Password* ya `/login YOUR_PASSWORD` bhejein.",
      { parse_mode: "Markdown", reply_markup: { remove_keyboard: true } }
    );
    return { ok: true, action: "logout" };
  }

  // Handle /login command
  const loginCmd = parseLoginCommand(textBody);
  if (loginCmd) {
    const authUser = await authenticateCreatorMember(db, loginCmd.emailOrPhone, loginCmd.passkey);
    if (!authUser) {
      await replyText("❌ Invalid or paused Password. Apna sahi VidVora Password check karke dobara bhejein.");
      return { ok: true, action: "login_failed" };
    }
    if (!hasCreatorFinanceAccess(authUser)) {
      await replyText("⛔ Aapke account par Earnings ya Payout access enabled nahi hai.");
      return { ok: true, action: "login_forbidden" };
    }

    const isAdminAccount =
      authUser.memberId === "admin" ||
      (Array.isArray(authUser.allowedOptions) && authUser.allowedOptions.includes("MANAGE_PAYOUTS"));

    await db.collection("telegramFinanceSessions").doc(telegramUserId).set(
      {
        telegramUserId,
        chatId: String(chatId),
        memberId: authUser.memberId,
        memberName: authUser.name,
        role: authUser.role || "member",
        linkedAt: new Date(),
        pendingWithdrawStep: null,
        pendingWithdrawAmount: null,
      },
      { merge: true }
    );

    if (authUser.memberId === "admin") {
      await replyText(
        `✅ Welcome *${escapeMarkdown(authUser.name)}*!\n\n🛡️ *Admin Finance Mode* active hai. Niche buttons se Pending Payouts aur Platform Summary dekh sakte hain.`,
        { parse_mode: "Markdown", reply_markup: ADMIN_REPLY_KEYBOARD }
      );
    } else {
      const snap = await fetchCreatorFinanceSnapshot(db, authUser.memberId);
      const balLine = snap
        ? `\n💵 *Available Balance:* ${formatInr(snap.summary.availableBalance)}`
        : "";
      await replyText(
        `✅ Welcome *${escapeMarkdown(authUser.name)}*!${balLine}\n\nAapka VidVora Finance account link ho gaya hai. Niche buttons se apni earning, UPI withdrawal aur payout history check karein.`,
        {
          parse_mode: "Markdown",
          reply_markup: isAdminAccount ? ADMIN_REPLY_KEYBOARD : CREATOR_REPLY_KEYBOARD,
        }
      );
    }
    return { ok: true, action: "login_success" };
  }

  const sessionSnap = await db.collection("telegramFinanceSessions").doc(telegramUserId).get();

  // Direct Password login without /login (works even to switch accounts when not mid-withdraw)
  const existingSession = sessionSnap.exists ? sessionSnap.data() || {} : null;
  if (
    !cmd &&
    (!existingSession || !existingSession.pendingWithdrawStep) &&
    textBody &&
    !textBody.startsWith("/") &&
    !/\s/.test(textBody) &&
    textBody.length >= 4 &&
    textBody.length <= 80
  ) {
    const directUser = await authenticateCreatorMember(db, null, textBody);
    if (directUser) {
      if (!hasCreatorFinanceAccess(directUser)) {
        await replyText("⛔ Aapke account par Earnings ya Payout access enabled nahi hai.");
        return { ok: true, action: "login_forbidden" };
      }

      const isAdminAccount =
        directUser.memberId === "admin" ||
        (Array.isArray(directUser.allowedOptions) &&
          directUser.allowedOptions.includes("MANAGE_PAYOUTS"));

      await db.collection("telegramFinanceSessions").doc(telegramUserId).set(
        {
          telegramUserId,
          chatId: String(chatId),
          memberId: directUser.memberId,
          memberName: directUser.name,
          role: directUser.role || "member",
          linkedAt: new Date(),
          pendingWithdrawStep: null,
          pendingWithdrawAmount: null,
        },
        { merge: true }
      );

      if (directUser.memberId === "admin") {
        await replyText(
          `✅ Welcome *${escapeMarkdown(directUser.name)}*!\n\n🛡️ *Admin Finance Mode* active hai. Niche buttons se Pending Payouts check karein.`,
          { parse_mode: "Markdown", reply_markup: ADMIN_REPLY_KEYBOARD }
        );
      } else {
        const snap = await fetchCreatorFinanceSnapshot(db, directUser.memberId);
        const balLine = snap
          ? `\n💵 *Available Balance:* ${formatInr(snap.summary.availableBalance)}`
          : "";
        await replyText(
          `✅ Welcome *${escapeMarkdown(directUser.name)}*!${balLine}\n\nAapka VidVora Finance account link ho gaya hai. Niche buttons use karein:`,
          {
            parse_mode: "Markdown",
            reply_markup: isAdminAccount ? ADMIN_REPLY_KEYBOARD : CREATOR_REPLY_KEYBOARD,
          }
        );
      }
      return { ok: true, action: "login_success" };
    }
  }

  if (!existingSession) {
    await replyText(
      "🔐 Pehle apna VidVora Creator / Admin account link karein:\n\nApna *Password* directly bhejein (ya `/login YOUR_PASSWORD` likhein).",
      { parse_mode: "Markdown" }
    );
    return { ok: true, action: "auth_required" };
  }

  // Re-verify member status if non-superadmin
  let liveMemberData = null;
  if (existingSession.memberId !== "admin") {
    const mSnap = await db.collection("members").doc(existingSession.memberId).get();
    liveMemberData = mSnap.exists ? { memberId: existingSession.memberId, ...(mSnap.data() || {}) } : null;
    if (!liveMemberData || liveMemberData.isActive === false) {
      await replyText("⛔ Aapka Creator account is waqt paused ya disabled hai.");
      return { ok: true, action: "member_disabled" };
    }
  }

  const sessionRef = db.collection("telegramFinanceSessions").doc(telegramUserId);
  const clearWithdrawState = async () => {
    if (existingSession.pendingWithdrawStep) {
      await sessionRef.set(
        { pendingWithdrawStep: null, pendingWithdrawAmount: null },
        { merge: true }
      );
    }
  };

  if (cmd === "CANCEL") {
    await clearWithdrawState();
    await replyText("✅ Cancelled. Niche menu se koi bhi option chunein.", {
      reply_markup: isFinanceAdminSession(existingSession, liveMemberData)
        ? ADMIN_REPLY_KEYBOARD
        : CREATOR_REPLY_KEYBOARD,
    });
    return { ok: true, action: "cancelled" };
  }

  if (cmd === "STATUS") {
    await clearWithdrawState();
    if (existingSession.memberId === "admin") {
      const summary = await buildPlatformFinanceSummary(db);
      await replyText(
        `🛡️ *Linked:* ${escapeMarkdown(existingSession.memberName || "Superadmin")} (Admin)\n⏳ *Pending Requests:* ${summary.pendingCount} (${formatInr(summary.pendingSum)})`,
        { parse_mode: "Markdown", reply_markup: ADMIN_REPLY_KEYBOARD }
      );
      return { ok: true, action: "status" };
    }
    const snap = await fetchCreatorFinanceSnapshot(db, existingSession.memberId);
    const bal = snap ? formatInr(snap.summary.availableBalance) : "₹0";
    await replyText(
      `✅ *Linked Creator:* ${escapeMarkdown(liveMemberData.name || existingSession.memberName || "Creator")}\n💵 *Available Balance:* ${bal}`,
      { parse_mode: "Markdown", reply_markup: CREATOR_REPLY_KEYBOARD }
    );
    return { ok: true, action: "status" };
  }

  // Admin Payout Actions (available to Superadmin OR Delegated Admin with MANAGE_PAYOUTS)
  if (
    cmd === "ADMIN_PENDING" ||
    cmd === "ADMIN_SUMMARY" ||
    cmd === "ADMIN_APPROVE" ||
    cmd === "ADMIN_PROCESSING" ||
    cmd === "ADMIN_REJECT"
  ) {
    await clearWithdrawState();
    if (!isFinanceAdminSession(existingSession, liveMemberData)) {
      await replyText("⛔ Ye command sirf Admin / Payout Manager ke liye hai.");
      return { ok: true, action: "admin_forbidden" };
    }

    if (cmd === "ADMIN_SUMMARY") {
      const s = await buildPlatformFinanceSummary(db);
      await replyText(
        [
          "📊 *VidVora Platform Finance Summary*",
          "",
          `👥 *Total Creators:* ${s.totalCreators}`,
          `💰 *Total Creator Earnings:* ${formatInr(s.totalCreatorEarned)}`,
          `⏳ *Pending Payouts (${s.pendingCount}):* ${formatInr(s.pendingSum)}`,
          `🔄 *Processing Payouts (${s.processingCount}):* ${formatInr(s.processingSum)}`,
          `✅ *Total Paid Out (${s.paidCount}):* ${formatInr(s.paidSum)}`,
        ].join("\n"),
        { parse_mode: "Markdown", reply_markup: ADMIN_REPLY_KEYBOARD }
      );
      return { ok: true, action: "admin_summary" };
    }

    if (cmd === "ADMIN_PENDING") {
      const s = await buildPlatformFinanceSummary(db);
      if (!s.actionableRequests.length) {
        await replyText("✅ Koi bhi Pending ya Processing withdrawal request nahi hai!", {
          reply_markup: ADMIN_REPLY_KEYBOARD,
        });
        return { ok: true, action: "admin_pending_empty" };
      }
      const lines = ["⏳ *Pending & Processing Payouts:*", ""];
      for (const r of s.actionableRequests.slice(0, 12)) {
        lines.push(
          `• *${escapeMarkdown(r.creatorName || "Creator")}* — *${formatInr(r.amount)}* (${statusBadge(r.status)})\n  UPI: \`${escapeMarkdown(r.upiId)}\` | ID: \`${r.requestId}\``
        );
      }
      lines.push(
        "",
        "Approve: `/approve REQUEST_ID UTR_NOTE`",
        "Processing: `/processing REQUEST_ID`",
        "Reject: `/reject REQUEST_ID REASON`"
      );
      await replyText(lines.join("\n"), {
        parse_mode: "Markdown",
        reply_markup: ADMIN_REPLY_KEYBOARD,
      });
      return { ok: true, action: "admin_pending" };
    }

    const match = textBody.match(/^\/(approve|processing|reject)(?:@\w+)?\s+(\S+)(?:\s+([\s\S]+))?$/i);
    if (!match) {
      await replyText(
        "Usage:\n`/approve REQUEST_ID optional_note`\n`/processing REQUEST_ID optional_note`\n`/reject REQUEST_ID reason`",
        { parse_mode: "Markdown" }
      );
      return { ok: true, action: "admin_invalid_syntax" };
    }

    const verb = match[1].toLowerCase();
    const targetId = match[2].trim();
    const note = (match[3] || "").trim();
    const targetStatus =
      verb === "approve" ? "DONE" : verb === "processing" ? "PROCESSING" : "REJECTED";

    const reqSnap = await db.collection("withdrawalRequests").doc(targetId).get();
    if (!reqSnap.exists) {
      await replyText(`❌ Withdrawal Request \`${targetId}\` nahi mili.`, {
        parse_mode: "Markdown",
      });
      return { ok: true, action: "request_not_found" };
    }
    const reqData = reqSnap.data() || {};
    await db
      .collection("withdrawalRequests")
      .doc(targetId)
      .update({
        status: targetStatus,
        adminNote: note,
        updatedAt: new Date(),
      });

    await notifyCreatorFinanceStatusChange({
      db,
      memberId: reqData.memberId,
      requestId: targetId,
      amount: reqData.amount,
      upiId: reqData.upiId,
      status: targetStatus,
      adminNote: note,
    });

    await replyText(
      `✅ Updated \`${targetId}\` (${escapeMarkdown(reqData.creatorName || "Creator")} — ${formatInr(reqData.amount)}) to *${targetStatus}*.`,
      { parse_mode: "Markdown", reply_markup: ADMIN_REPLY_KEYBOARD }
    );
    return { ok: true, action: `admin_${verb}`, requestId: targetId, status: targetStatus };
  }

  // Superadmin personal stats notice when tapping creator commands without a creator member doc
  if (existingSession.memberId === "admin") {
    const s = await buildPlatformFinanceSummary(db);
    await replyText(
      [
        "🛡️ *Superadmin Finance Mode*",
        "",
        `👥 Creators: *${s.totalCreators}*`,
        `⏳ Pending Payouts: *${s.pendingCount}* (${formatInr(s.pendingSum)})`,
        `✅ Paid Total: *${formatInr(s.paidSum)}*`,
        "",
        "Creator balance check karne ke liye creator ke Password se login karein, ya `/pending` / `/summary` use karein.",
      ].join("\n"),
      { parse_mode: "Markdown", reply_markup: ADMIN_REPLY_KEYBOARD }
    );
    return { ok: true, action: "admin_info" };
  }

  // Creator Finance Flows
  const snapshot = await fetchCreatorFinanceSnapshot(db, existingSession.memberId);
  if (!snapshot) {
    await replyText("❌ Aapka Creator record Firestore mein nahi mila.");
    return { ok: true, action: "member_not_found" };
  }

  if (cmd === "BALANCE") {
    await clearWithdrawState();
    if (!canCreatorViewEarnings(snapshot.member)) {
      await replyText("⛔ Aapke account par View Earnings permission enabled nahi hai.");
      return { ok: true, action: "earnings_forbidden" };
    }
    const { summary } = snapshot;
    const withdrawHint =
      summary.availableBalance >= MIN_WITHDRAWAL_INR
        ? "✅ Withdraw karne ke liye niche *💸 Withdraw UPI* button dabayein."
        : `ℹ️ Minimum withdrawal *${formatInr(MIN_WITHDRAWAL_INR)}* hai (${formatInr(MIN_WITHDRAWAL_INR - summary.availableBalance)} aur chahiye).`;

    await replyText(
      [
        `💰 *${escapeMarkdown(snapshot.member.name || "Creator")} — Earnings Summary*`,
        "",
        `💵 *Available Balance:* ${formatInr(summary.availableBalance)}`,
        `🏆 *Total Earning:* ${formatInr(summary.totalEarning)}`,
        `⏳ *Pending / Processing:* ${formatInr(summary.pendingOrProcessingAmount)}`,
        `✅ *Total Paid:* ${formatInr(summary.paidAmount)}`,
        "",
        withdrawHint,
      ].join("\n"),
      { parse_mode: "Markdown", reply_markup: CREATOR_REPLY_KEYBOARD }
    );
    return { ok: true, action: "balance" };
  }

  if (cmd === "HISTORY") {
    await clearWithdrawState();
    if (!canCreatorViewEarnings(snapshot.member)) {
      await replyText("⛔ Aapke account par Payout History permission enabled nahi hai.");
      return { ok: true, action: "earnings_forbidden" };
    }
    if (!snapshot.requests.length) {
      await replyText(
        "📜 Abhi tak koi withdrawal request nahi bheji gayi hai.\n\nNayi request ke liye *💸 Withdraw UPI* par tap karein.",
        { parse_mode: "Markdown", reply_markup: CREATOR_REPLY_KEYBOARD }
      );
      return { ok: true, action: "history_empty" };
    }

    const lines = ["📜 *Recent Payout Requests:*", ""];
    for (const req of snapshot.requests.slice(0, 10)) {
      const note = req.adminNote ? `\n  _Note: ${escapeMarkdown(req.adminNote)}_` : "";
      lines.push(
        `• *${formatInr(req.amount)}* → \`${escapeMarkdown(req.upiId)}\`\n  ${statusBadge(req.status)} • ${formatTimestampShort(req.requestedAt)}${note}`
      );
    }
    await replyText(lines.join("\n"), {
      parse_mode: "Markdown",
      reply_markup: CREATOR_REPLY_KEYBOARD,
    });
    return { ok: true, action: "history" };
  }

  if (cmd === "WITHDRAW") {
    if (!canCreatorWithdraw(snapshot.member)) {
      await clearWithdrawState();
      await replyText("⛔ Aapke account par UPI Withdrawal request permission disabled hai.", {
        reply_markup: CREATOR_REPLY_KEYBOARD,
      });
      return { ok: true, action: "withdraw_forbidden" };
    }

    const savedUpiId = resolveMemberSavedUpiId(existingSession, snapshot);

    // Check one-shot syntax: `/withdraw 500 name@ybl`
    const oneShot = textBody.match(/^\/withdraw(?:@\w+)?\s+([0-9]+(?:\.[0-9]+)?)\s+(\S+)$/i);
    if (oneShot) {
      await clearWithdrawState();
      const amount = Number(oneShot[1]);
      const upiId = oneShot[2].trim();
      const created = await createCreatorWithdrawalRequest(db, snapshot, amount, upiId);
      if (!created.ok) {
        await replyText(created.error, {
          parse_mode: "Markdown",
          reply_markup: CREATOR_REPLY_KEYBOARD,
        });
        return { ok: true, action: "withdraw_rejected_validation" };
      }
      await sessionRef.set({ lastUpiId: created.request.upiId }, { merge: true });
      await replyText(
        [
          "✅ *UPI Withdrawal Request Submitted!*",
          "",
          `• *Amount:* ${formatInr(created.request.amount)}`,
          `• *UPI ID:* \`${escapeMarkdown(created.request.upiId)}\``,
          `• *Status:* ⏳ PENDING`,
          "",
          "Admin approve karte hi aapko yahan update message mil jayega!",
        ].join("\n"),
        { parse_mode: "Markdown", reply_markup: CREATOR_REPLY_KEYBOARD }
      );
      return { ok: true, action: "withdraw_created", requestId: created.request.requestId };
    }

    if (snapshot.summary.availableBalance < MIN_WITHDRAWAL_INR) {
      await clearWithdrawState();
      await replyText(
        `⚠️ Abhi withdraw karne ke liye minimum *${formatInr(MIN_WITHDRAWAL_INR)}* balance chahiye.\n\nAapka Available Balance: *${formatInr(snapshot.summary.availableBalance)}*`,
        { parse_mode: "Markdown", reply_markup: CREATOR_REPLY_KEYBOARD }
      );
      return { ok: true, action: "withdraw_insufficient" };
    }

    // Check amount-only shorthand: `/withdraw 500` -> jump directly to UPI step
    const amountOnly = textBody.match(/^\/withdraw(?:@\w+)?\s+([0-9]+(?:\.[0-9]+)?)$/i);
    if (amountOnly) {
      const amount = Number(amountOnly[1]);
      if (!Number.isFinite(amount) || amount < MIN_WITHDRAWAL_INR) {
        await replyText(
          `⚠️ Minimum withdrawal *${formatInr(MIN_WITHDRAWAL_INR)}* hai. Sahi amount chunein:`,
          {
            parse_mode: "Markdown",
            reply_markup: buildAmountReplyKeyboard(snapshot.summary.availableBalance),
          }
        );
        await sessionRef.set(
          { pendingWithdrawStep: "AWAITING_AMOUNT", pendingWithdrawAmount: null },
          { merge: true }
        );
        return { ok: true, action: "withdraw_invalid_amount" };
      }
      if (amount > snapshot.summary.availableBalance + 0.001) {
        await replyText(
          `❌ Aapka Available Balance sirf *${formatInr(snapshot.summary.availableBalance)}* hai. Niche se amount chunein:`,
          {
            parse_mode: "Markdown",
            reply_markup: buildAmountReplyKeyboard(snapshot.summary.availableBalance),
          }
        );
        await sessionRef.set(
          { pendingWithdrawStep: "AWAITING_AMOUNT", pendingWithdrawAmount: null },
          { merge: true }
        );
        return { ok: true, action: "withdraw_amount_too_high" };
      }

      await sessionRef.set(
        { pendingWithdrawStep: "AWAITING_UPI", pendingWithdrawAmount: amount },
        { merge: true }
      );
      const savedHint = savedUpiId
        ? `\nYa niche *✅ Use ${escapeMarkdown(savedUpiId)}* button dabayein!`
        : "";
      await replyText(
        `✅ Amount: *${formatInr(amount)}*\n\nAb apni *UPI ID* bhejein (jaise \`name@ybl\`):${savedHint}`,
        { parse_mode: "Markdown", reply_markup: buildUpiReplyKeyboard(savedUpiId) }
      );
      return { ok: true, action: "withdraw_prompt_upi" };
    }

    await sessionRef.set(
      { pendingWithdrawStep: "AWAITING_AMOUNT", pendingWithdrawAmount: null },
      { merge: true }
    );
    await replyText(
      [
        `💸 *Step 1/2: Amount Chunein*`,
        `Available Balance: *${formatInr(snapshot.summary.availableBalance)}*`,
        "",
        `Niche button dabayein ya koi amount (jaise \`500\`) likh kar bhejein 👇`,
      ].join("\n"),
      {
        parse_mode: "Markdown",
        reply_markup: buildAmountReplyKeyboard(snapshot.summary.availableBalance),
      }
    );
    return { ok: true, action: "withdraw_prompt_amount" };
  }

  // Interactive 2-step withdrawal handler (with 1-tap buttons)
  if (existingSession.pendingWithdrawStep === "AWAITING_AMOUNT") {
    const amount = parseQuickAmountInput(textBody, snapshot.summary.availableBalance);
    if (!Number.isFinite(amount) || amount < MIN_WITHDRAWAL_INR) {
      await replyText(
        `⚠️ Minimum withdrawal *${formatInr(MIN_WITHDRAWAL_INR)}* hai. Niche button dabayein ya sahi amount bhejein:`,
        {
          parse_mode: "Markdown",
          reply_markup: buildAmountReplyKeyboard(snapshot.summary.availableBalance),
        }
      );
      return { ok: true, action: "withdraw_invalid_amount" };
    }
    if (amount > snapshot.summary.availableBalance + 0.001) {
      await replyText(
        `❌ Aapka Available Balance sirf *${formatInr(snapshot.summary.availableBalance)}* hai. Usse kam amount chunein:`,
        {
          parse_mode: "Markdown",
          reply_markup: buildAmountReplyKeyboard(snapshot.summary.availableBalance),
        }
      );
      return { ok: true, action: "withdraw_amount_too_high" };
    }

    const savedUpiId = resolveMemberSavedUpiId(existingSession, snapshot);
    await sessionRef.set(
      { pendingWithdrawStep: "AWAITING_UPI", pendingWithdrawAmount: amount },
      { merge: true }
    );
    const savedHint = savedUpiId
      ? `\nYa niche *✅ Use ${escapeMarkdown(savedUpiId)}* dabayein!`
      : "";
    await replyText(
      `✅ Amount: *${formatInr(amount)}*\n\n*Step 2/2:* Apni *UPI ID* bhejein (jaise \`name@ybl\`):${savedHint}`,
      { parse_mode: "Markdown", reply_markup: buildUpiReplyKeyboard(savedUpiId) }
    );
    return { ok: true, action: "withdraw_prompt_upi" };
  }

  if (existingSession.pendingWithdrawStep === "AWAITING_UPI") {
    const amount = Number(existingSession.pendingWithdrawAmount) || 0;
    const cleanedUpiInput = textBody.replace(/^✅\s*Use\s+/i, "").trim();
    const created = await createCreatorWithdrawalRequest(db, snapshot, amount, cleanedUpiInput);
    if (!created.ok) {
      const savedUpiId = resolveMemberSavedUpiId(existingSession, snapshot);
      await replyText(`${created.error}\n\nDobara sahi UPI ID bhejein ya \`❌ Cancel\` dabayein:`, {
        parse_mode: "Markdown",
        reply_markup: buildUpiReplyKeyboard(savedUpiId),
      });
      return { ok: true, action: "withdraw_invalid_upi" };
    }
    await sessionRef.set(
      {
        pendingWithdrawStep: null,
        pendingWithdrawAmount: null,
        lastUpiId: created.request.upiId,
      },
      { merge: true }
    );
    await replyText(
      [
        "✅ *UPI Withdrawal Request Submitted!*",
        "",
        `• *Amount:* ${formatInr(created.request.amount)}`,
        `• *UPI ID:* \`${escapeMarkdown(created.request.upiId)}\``,
        `• *Status:* ⏳ PENDING`,
        "",
        "Admin approve karte hi aapko yahan confirmation message mil jayega!",
      ].join("\n"),
      { parse_mode: "Markdown", reply_markup: CREATOR_REPLY_KEYBOARD }
    );
    return { ok: true, action: "withdraw_created", requestId: created.request.requestId };
  }

  await replyText(
    "💰 Niche buttons use karein ya command bhejein:\n`/balance` • `/withdraw` • `/history` • `/help`",
    { parse_mode: "Markdown", reply_markup: CREATOR_REPLY_KEYBOARD }
  );
  return { ok: true, action: "menu_hint" };
}

module.exports = {
  MIN_WITHDRAWAL_INR,
  DEFAULT_VIEW_RATE_PER_1000,
  isValidUpiId,
  formatInr,
  computeCreatorEarningSummary,
  resolveFinanceBotRuntimeConfig,
  fetchCreatorFinanceSnapshot,
  createCreatorWithdrawalRequest,
  notifyCreatorFinanceStatusChange,
  buildPlatformFinanceSummary,
  handleFinanceTelegramUpdate,
};
