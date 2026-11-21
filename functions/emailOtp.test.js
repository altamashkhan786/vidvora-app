const test = require("node:test");
const assert = require("node:assert/strict");

const {
  ADMIN_EMAIL,
  DEFAULT_CREATOR_ALLOWED_OPTIONS,
  normalizeEmail,
  isValidEmail,
  maskEmail,
  isEmailTransportConfigured,
  handleSendOtp,
  handleDirectSignup,
  handleVerifySignupOtp,
  handleVerifyResetOtp,
} = require("./emailOtp");

function createMemoryDb(initialMembers = []) {
  const membersMap = new Map();
  for (const m of initialMembers) {
    membersMap.set(m.memberId, { ...m });
  }
  const otpMap = new Map();

  return {
    _members: membersMap,
    _otps: otpMap,
    collection(name) {
      if (name === "members") {
        return {
          get: async () => ({
            empty: membersMap.size === 0,
            docs: Array.from(membersMap.entries()).map(([id, data]) => ({
              id,
              data: () => ({ ...data }),
              ref: {
                update: async (patch) => {
                  const prev = membersMap.get(id) || {};
                  membersMap.set(id, { ...prev, ...patch });
                },
              },
            })),
          }),
          doc: (id) => ({
            set: async (data) => {
              membersMap.set(id, { ...data });
            },
          }),
        };
      }
      if (name === "emailOtpCodes") {
        return {
          doc: (id) => ({
            get: async () =>
              otpMap.has(id)
                ? { exists: true, data: () => ({ ...otpMap.get(id) }) }
                : { exists: false, data: () => undefined },
            set: async (data, opts) => {
              if (opts && opts.merge && otpMap.has(id)) {
                otpMap.set(id, { ...otpMap.get(id), ...data });
              } else {
                otpMap.set(id, { ...data });
              }
            },
            delete: async () => {
              otpMap.delete(id);
            },
          }),
        };
      }
      throw new Error(`Unexpected collection: ${name}`);
    },
  };
}

test("email validation, normalization and email transport configuration checks", () => {
  assert.equal(normalizeEmail("  Creator.One@GMAIL.com "), "creator.one@gmail.com");
  assert.equal(isValidEmail("creator@gmail.com"), true);
  assert.equal(isValidEmail("bad-email"), false);
  assert.equal(maskEmail("altamashkhan@gmail.com"), "al*****@gmail.com");

  assert.equal(isEmailTransportConfigured({}), false);
  assert.equal(
    isEmailTransportConfigured({ SMTP_USER: "bot@gmail.com", SMTP_PASS: "apppass" }),
    true
  );
  assert.equal(isEmailTransportConfigured({ BREVO_API_KEY: "xkeysib-123" }), true);
  assert.equal(isEmailTransportConfigured({ RESEND_API_KEY: "re_123" }), true);
  assert.equal(
    isEmailTransportConfigured({ OTP_EMAIL_WEBHOOK_URL: "https://script.google.com/macros/s/abc/exec" }),
    true
  );
});

test("self-serve creator signup sends OTP, enforces cooldown/attempts, and creates default creator member", async () => {
  const db = createMemoryDb();
  const sentEmails = [];
  const fakeSender = async (mail) => {
    sentEmails.push(mail);
  };

  // 1. Send OTP at t = 1_000_000
  const sendRes = await handleSendOtp({
    body: { name: "Rohan Creator", email: "Rohan@Gmail.com", password: "rohan@2026" },
    db,
    playbackSecret: "test-secret",
    sendEmailFn: fakeSender,
    nowMs: 1_000_000,
  });
  assert.equal(sendRes.statusCode, 200);
  assert.equal(sendRes.payload.ok, true);
  assert.equal(sentEmails.length, 1);
  assert.equal(sentEmails[0].toEmail, "rohan@gmail.com");
  assert.match(sentEmails[0].otp, /^\d{6}$/);

  // 2. Resend within 60s hits cooldown
  const cooldownRes = await handleSendOtp({
    body: { name: "Rohan Creator", email: "rohan@gmail.com", password: "rohan@2026" },
    db,
    playbackSecret: "test-secret",
    sendEmailFn: fakeSender,
    nowMs: 1_020_000,
  });
  assert.equal(cooldownRes.statusCode, 429);
  assert.ok(cooldownRes.payload.retryAfterSec >= 35);

  // 3. Wrong OTP decrements attempts instead of creating account
  const wrongOtpRes = await handleVerifySignupOtp({
    body: { name: "Rohan Creator", email: "rohan@gmail.com", password: "rohan@2026", otp: "000000" },
    db,
    playbackSecret: "test-secret",
    nowMs: 1_030_000,
  });
  assert.equal(wrongOtpRes.statusCode, 400);
  assert.equal(db._members.size, 0);

  // 4. Correct OTP creates member with Creator defaults & consumes OTP
  const verifyRes = await handleVerifySignupOtp({
    body: {
      name: "Rohan Creator",
      email: "rohan@gmail.com",
      password: "rohan@2026",
      otp: sentEmails[0].otp,
    },
    db,
    playbackSecret: "test-secret",
    nowMs: 1_040_000,
  });
  assert.equal(verifyRes.statusCode, 200);
  assert.equal(verifyRes.payload.ok, true);
  assert.equal(verifyRes.payload.member.emailOrPhone, "rohan@gmail.com");
  assert.equal(verifyRes.payload.member.role, "member");
  assert.deepEqual(verifyRes.payload.member.allowedOptions, DEFAULT_CREATOR_ALLOWED_OPTIONS);
  assert.equal(db._members.size, 1);
  assert.equal(db._otps.size, 0);

  // 5. Re-using same Gmail for signup is rejected
  const dupRes = await handleSendOtp({
    body: { name: "Another Rohan", email: "rohan@gmail.com", password: "newpass99" },
    db,
    playbackSecret: "test-secret",
    sendEmailFn: fakeSender,
    nowMs: 1_200_000,
  });
  assert.equal(dupRes.statusCode, 409);

  // 6. Superadmin email cannot be registered via creator signup
  const superadminRes = await handleSendOtp({
    body: { name: "Fake Admin", email: ADMIN_EMAIL, password: "hacked123" },
    db,
    playbackSecret: "test-secret",
    sendEmailFn: fakeSender,
    nowMs: 1_200_000,
  });
  assert.equal(superadminRes.statusCode, 403);
});

test("OTP expiry and password reset via Gmail OTP update existing creator passkey", async () => {
  const db = createMemoryDb([
    {
      memberId: "mem_existing",
      name: "Zoya Creator",
      emailOrPhone: "zoya@gmail.com",
      passkey: "oldpass123",
      role: "member",
      allowedOptions: DEFAULT_CREATOR_ALLOWED_OPTIONS,
      isActive: true,
    },
  ]);
  const sent = [];
  const fakeSender = async (m) => sent.push(m);

  // Request reset OTP
  const reqReset = await handleSendOtp({
    body: { email: "zoya@gmail.com" },
    db,
    playbackSecret: "reset-secret",
    purpose: "reset",
    sendEmailFn: fakeSender,
    nowMs: 2_000_000,
  });
  assert.equal(reqReset.statusCode, 200);
  assert.equal(sent.length, 1);

  // Expired OTP fails (after 10 mins)
  const expired = await handleVerifyResetOtp({
    body: { email: "zoya@gmail.com", otp: sent[0].otp, newPassword: "newpass999" },
    db,
    playbackSecret: "reset-secret",
    nowMs: 2_000_000 + 11 * 60 * 1000,
  });
  assert.equal(expired.statusCode, 400);

  // Re-send and verify before expiry
  const resend = await handleSendOtp({
    body: { email: "zoya@gmail.com" },
    db,
    playbackSecret: "reset-secret",
    purpose: "reset",
    sendEmailFn: fakeSender,
    nowMs: 3_000_000,
  });
  assert.equal(resend.statusCode, 200);
  const verifyReset = await handleVerifyResetOtp({
    body: { email: "zoya@gmail.com", otp: sent[1].otp, newPassword: "newpass999" },
    db,
    playbackSecret: "reset-secret",
    nowMs: 3_020_000,
  });
  assert.equal(verifyReset.statusCode, 200);
  assert.equal(db._members.get("mem_existing").passkey, "newpass999");
});

test("direct creator signup works without OTP and enforces confirmPassword matching & unique email", async () => {
  const db = createMemoryDb();

  // Mismatched confirm password rejected
  const mismatch = await handleDirectSignup({
    body: {
      name: "Yusuf Khan",
      email: "yusuf@gmail.com",
      password: "pass1234",
      confirmPassword: "pass9999",
    },
    db,
    nowMs: 5_000_000,
  });
  assert.equal(mismatch.statusCode, 400);
  assert.equal(db._members.size, 0);

  // Matching passwords create creator immediately without OTP
  const okRes = await handleDirectSignup({
    body: {
      name: "Yusuf Khan",
      email: "Yusuf@Gmail.com",
      password: "pass1234",
      confirmPassword: "pass1234",
    },
    db,
    nowMs: 5_000_000,
  });
  assert.equal(okRes.statusCode, 200);
  assert.equal(okRes.payload.ok, true);
  assert.equal(okRes.payload.member.emailOrPhone, "yusuf@gmail.com");
  assert.equal(okRes.payload.member.name, "Yusuf Khan");
  assert.equal(db._members.size, 1);

  // Duplicate email rejected
  const dup = await handleDirectSignup({
    body: {
      name: "Yusuf 2",
      email: "yusuf@gmail.com",
      password: "pass5678",
      confirmPassword: "pass5678",
    },
    db,
    nowMs: 5_010_000,
  });
  assert.equal(dup.statusCode, 409);
});

test("direct creator signup links valid referral code and rejects unknown referral code", async () => {
  const db = createMemoryDb([
    {
      memberId: "mem_abc12345",
      name: "Arjun Referrer",
      emailOrPhone: "arjun@gmail.com",
      passkey: "pass1234",
      role: "member",
      referralCode: "VV-ARJUN1",
      allowedOptions: DEFAULT_CREATOR_ALLOWED_OPTIONS,
      isActive: true,
    },
  ]);

  // Unknown referral code rejected
  const invalidRef = await handleDirectSignup({
    body: {
      name: "Neha New",
      email: "neha@gmail.com",
      password: "pass1234",
      confirmPassword: "pass1234",
      referralCode: "VV-NOPE99",
    },
    db,
    nowMs: 6_000_000,
  });
  assert.equal(invalidRef.statusCode, 400);
  assert.equal(db._members.size, 1);

  // Valid referral code links referredByMemberId & referredByName and generates own referralCode
  const validRef = await handleDirectSignup({
    body: {
      name: "Neha New",
      email: "neha@gmail.com",
      password: "pass1234",
      confirmPassword: "pass1234",
      referralCode: "vv-arjun1",
    },
    db,
    nowMs: 6_005_000,
  });
  assert.equal(validRef.statusCode, 200);
  assert.equal(validRef.payload.member.referredByMemberId, "mem_abc12345");
  assert.equal(validRef.payload.member.referredByName, "Arjun Referrer");
  assert.equal(validRef.payload.member.referralBonusEarning, 0);
  assert.equal(validRef.payload.member.referralCommissionGenerated, 0);
  assert.match(validRef.payload.member.referralCode, /^VV-[A-Z0-9]{6}$/);
});

