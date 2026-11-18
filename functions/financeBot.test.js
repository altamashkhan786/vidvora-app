const test = require("node:test");
const assert = require("node:assert/strict");

const {
  isValidUpiId,
  formatInr,
  computeCreatorEarningSummary,
  handleFinanceTelegramUpdate,
} = require("./financeBot");

function createMockFinanceDb({ members = {}, videos = {}, withdrawals = {}, botToken = "999:FINANCE_TEST" } = {}) {
  const memberMap = new Map(Object.entries(members));
  const videoMap = new Map(Object.entries(videos));
  const withdrawalMap = new Map(Object.entries(withdrawals));
  const sessionMap = new Map();

  const matchesWhere = (row, field, val) => {
    if (field === "passkey") return String(row.passkey || "").trim() === String(val || "").trim();
    if (field === "uploadedBy") return String(row.uploadedBy || "") === String(val || "");
    if (field === "memberId") return String(row.memberId || "") === String(val || "");
    return false;
  };

  const makeSnapFromEntries = (entries) => ({
    empty: entries.length === 0,
    docs: entries.map(([id, data]) => ({ id, data: () => ({ ...data }) })),
  });

  return {
    _withdrawals: withdrawalMap,
    _sessions: sessionMap,
    collection: (name) => {
      if (name === "appConfig") {
        return {
          doc: (id) => ({
            get: async () => {
              if (id === "telegramFinanceBot" && botToken) {
                return { exists: true, data: () => ({ botToken }) };
              }
              return { exists: false };
            },
          }),
        };
      }
      if (name === "members") {
        return {
          get: async () => makeSnapFromEntries([...memberMap.entries()]),
          doc: (id) => ({
            get: async () =>
              memberMap.has(id)
                ? { exists: true, data: () => ({ ...memberMap.get(id) }) }
                : { exists: false },
          }),
          where: (field, _op, val) => {
            const matched = [...memberMap.entries()].filter(([, d]) => matchesWhere(d, field, val));
            return {
              limit: (n) => ({ get: async () => makeSnapFromEntries(matched.slice(0, n)) }),
              get: async () => makeSnapFromEntries(matched),
            };
          },
        };
      }
      if (name === "videos") {
        return {
          where: (field, _op, val) => ({
            get: async () =>
              makeSnapFromEntries([...videoMap.entries()].filter(([, d]) => matchesWhere(d, field, val))),
          }),
        };
      }
      if (name === "withdrawalRequests") {
        return {
          get: async () => makeSnapFromEntries([...withdrawalMap.entries()]),
          where: (field, _op, val) => ({
            get: async () =>
              makeSnapFromEntries(
                [...withdrawalMap.entries()].filter(([, d]) => matchesWhere(d, field, val))
              ),
          }),
          doc: (id) => ({
            get: async () =>
              withdrawalMap.has(id)
                ? { exists: true, data: () => ({ ...withdrawalMap.get(id) }) }
                : { exists: false },
            set: async (payload) => {
              withdrawalMap.set(id, { ...payload });
            },
            update: async (patch) => {
              const cur = withdrawalMap.get(id) || {};
              withdrawalMap.set(id, { ...cur, ...patch });
            },
          }),
        };
      }
      if (name === "telegramFinanceSessions") {
        return {
          where: (field, _op, val) => ({
            get: async () =>
              makeSnapFromEntries(
                [...sessionMap.entries()].filter(([, d]) => matchesWhere(d, field, val))
              ),
          }),
          doc: (id) => ({
            get: async () =>
              sessionMap.has(id)
                ? { exists: true, data: () => ({ ...sessionMap.get(id) }) }
                : { exists: false },
            set: async (payload, opts) => {
              const prev = opts && opts.merge && sessionMap.has(id) ? sessionMap.get(id) : {};
              sessionMap.set(id, { ...prev, ...payload });
            },
            delete: async () => {
              sessionMap.delete(id);
            },
          }),
        };
      }
      return { doc: () => ({ get: async () => ({ exists: false }) }) };
    },
  };
}

test("computeCreatorEarningSummary, isValidUpiId, and formatInr match VidVora rules", () => {
  assert.equal(isValidUpiId("rohan@ybl"), true);
  assert.equal(isValidUpiId("9876543210@paytm"), true);
  assert.equal(isValidUpiId("invalid_upi"), false);
  assert.equal(isValidUpiId("a@b"), false);

  assert.equal(formatInr(1250), "₹1,250");
  assert.equal(formatInr(500.5), "₹500.50");

  const summary = computeCreatorEarningSummary(2000, [
    { amount: 500, status: "DONE" },
    { amount: 600, status: "PENDING" },
    { amount: 700, status: "REJECTED" },
  ]);
  assert.equal(summary.totalEarning, 2000);
  assert.equal(summary.paidAmount, 500);
  assert.equal(summary.pendingOrProcessingAmount, 600);
  assert.equal(summary.availableBalance, 900);
});

test("Creator logs in with Password, checks balance, and submits interactive 2-step UPI withdrawal", async () => {
  const db = createMockFinanceDb({
    members: {
      mem_rohan: {
        memberId: "mem_rohan",
        name: "Rohan Creator",
        emailOrPhone: "rohan@gmail.com",
        passkey: "ROHAN-PASS",
        isActive: true,
        totalEarning: 1800,
        creditedViews: 12000,
        viewRatePer1000: 150,
        allowedOptions: ["UPLOAD_VIDEOS", "VIEW_EARNINGS", "REQUEST_WITHDRAWAL"],
      },
    },
    videos: {
      vid_1: { uploadedBy: "mem_rohan", title: "Viral Clip", totalViews: 9500, isActive: true },
      vid_2: { uploadedBy: "mem_rohan", title: "Part 2", totalViews: 2500, isActive: true },
    },
    withdrawals: {
      wdr_done: {
        requestId: "wdr_done",
        memberId: "mem_rohan",
        amount: 500,
        upiId: "rohan@ybl",
        status: "DONE",
        requestedAt: new Date(Date.now() - 86400000),
      },
    },
  });

  const sentMessages = [];
  const origFetch = global.fetch;
  global.fetch = async (_url, opts) => {
    sentMessages.push(JSON.parse(opts.body));
    return { ok: true, json: async () => ({ ok: true, result: { message_id: sentMessages.length } }) };
  };

  const sendMsg = (text, userId = "tg_rohan", chatId = 1001) =>
    handleFinanceTelegramUpdate({
      db,
      update: {
        message: {
          message_id: sentMessages.length + 1,
          chat: { id: chatId },
          from: { id: userId },
          text,
        },
      },
    });

  try {
    // 1. Direct Password login
    const rLogin = await sendMsg("ROHAN-PASS");
    assert.equal(rLogin.action, "login_success");
    assert.match(sentMessages.at(-1).text, /Welcome \*Rohan Creator\*/);
    assert.match(sentMessages.at(-1).text, /₹1,300/);

    // 2. Check /balance
    const rBal = await sendMsg("/balance");
    assert.equal(rBal.action, "balance");
    assert.match(sentMessages.at(-1).text, /Available Balance:\* ₹1,300/);
    assert.match(sentMessages.at(-1).text, /Total Paid:\* ₹500/);
    assert.doesNotMatch(sentMessages.at(-1).text, /Credited Views:|View Rate:|Videos:/);
    assert.doesNotMatch(JSON.stringify(sentMessages.at(-1).reply_markup), /Video Stats/);

    const rVideos = await sendMsg("/videos");
    assert.equal(rVideos.action, "menu_hint");
    assert.doesNotMatch(sentMessages.at(-1).text, /views|\/videos/i);

    // 3. Guided 2-step UPI withdrawal using 1-tap Quick Amount button
    const rStartW = await sendMsg("💸 Withdraw UPI");
    assert.equal(rStartW.action, "withdraw_prompt_amount");
    const amtKeyboard = JSON.stringify(sentMessages.at(-1).reply_markup);
    assert.match(amtKeyboard, /All ₹1,300/);
    assert.match(amtKeyboard, /₹500/);

    const rBadAmt = await sendMsg("300");
    assert.equal(rBadAmt.action, "withdraw_invalid_amount");

    const rTapAmt = await sendMsg("💸 ₹500");
    assert.equal(rTapAmt.action, "withdraw_prompt_upi");
    const upiKeyboard1 = JSON.stringify(sentMessages.at(-1).reply_markup);
    assert.match(upiKeyboard1, /✅ Use rohan@ybl/);

    const rGoodUpi = await sendMsg("rohan@okicici");
    assert.equal(rGoodUpi.action, "withdraw_created");
    assert.match(sentMessages.at(-1).text, /UPI Withdrawal Request Submitted/);
    assert.match(sentMessages.at(-1).text, /₹500/);

    const createdDoc = db._withdrawals.get(rGoodUpi.requestId);
    assert.equal(createdDoc.memberId, "mem_rohan");
    assert.equal(createdDoc.amount, 500);
    assert.equal(createdDoc.upiId, "rohan@okicici");
    assert.equal(createdDoc.status, "PENDING");

    // 3b. Next withdrawal offers 1-tap saved UPI button for rohan@okicici & supports All button
    const rStartW2 = await sendMsg("💸 Withdraw UPI");
    assert.equal(rStartW2.action, "withdraw_prompt_amount");
    const rTapAll = await sendMsg("💵 All ₹800");
    assert.equal(rTapAll.action, "withdraw_prompt_upi");
    const upiKeyboard2 = JSON.stringify(sentMessages.at(-1).reply_markup);
    assert.match(upiKeyboard2, /✅ Use rohan@okicici/);
    const rCancel = await sendMsg("❌ Cancel");
    assert.equal(rCancel.action, "cancelled");

    // 4. Superadmin approves payout and creator gets instant Telegram push notification
    const adminLogin = await sendMsg("GM-ADMIN-2026-MASTER", "tg_admin", 2002);
    assert.equal(adminLogin.action, "login_success");

    const rPending = await sendMsg("/pending", "tg_admin", 2002);
    assert.equal(rPending.action, "admin_pending");
    assert.match(sentMessages.at(-1).text, new RegExp(rGoodUpi.requestId));

    const rApprove = await sendMsg(`/approve ${rGoodUpi.requestId} UTR998877`, "tg_admin", 2002);
    assert.equal(rApprove.action, "admin_approve");
    assert.equal(db._withdrawals.get(rGoodUpi.requestId).status, "DONE");
    assert.equal(db._withdrawals.get(rGoodUpi.requestId).adminNote, "UTR998877");

    // Verify notification was sent to Creator chatId 1001
    const creatorPush = sentMessages.find(
      (m) => String(m.chat_id) === "1001" && /Payout Status Update/.test(m.text)
    );
    assert.ok(creatorPush, "Creator should receive Telegram payout confirmation notification");
    assert.match(creatorPush.text, /PAID \(DONE\)/);
    assert.match(creatorPush.text, /UTR998877/);
  } finally {
    global.fetch = origFetch;
  }
});

test("Creator without withdrawal permission cannot submit withdrawal via Finance Bot", async () => {
  const db = createMockFinanceDb({
    members: {
      mem_view_only: {
        memberId: "mem_view_only",
        name: "View Only",
        emailOrPhone: "view@gmail.com",
        passkey: "VIEW-1234",
        isActive: true,
        totalEarning: 900,
        allowedOptions: ["VIEW_EARNINGS"],
      },
    },
  });

  const sentMessages = [];
  const origFetch = global.fetch;
  global.fetch = async (_url, opts) => {
    sentMessages.push(JSON.parse(opts.body));
    return { ok: true, json: async () => ({ ok: true, result: { message_id: 1 } }) };
  };

  try {
    await handleFinanceTelegramUpdate({
      db,
      update: { message: { message_id: 1, chat: { id: 3003 }, from: { id: "tg_v" }, text: "VIEW-1234" } },
    });
    const res = await handleFinanceTelegramUpdate({
      db,
      update: {
        message: {
          message_id: 2,
          chat: { id: 3003 },
          from: { id: "tg_v" },
          text: "/withdraw 500 view@ybl",
        },
      },
    });
    assert.equal(res.action, "withdraw_forbidden");
    assert.equal(db._withdrawals.size, 0);
  } finally {
    global.fetch = origFetch;
  }
});
