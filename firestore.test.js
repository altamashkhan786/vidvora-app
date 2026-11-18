const { initializeTestEnvironment, assertFails, assertSucceeds } = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const DATABASE_ID = "ai-studio-android-garammas-738e4493-0090-419e-8ece-c55642a933ce";
const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);
let testEnv;

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      databaseId: DATABASE_ID,
      rules: fs.readFileSync("./firestore.rules", "utf8"),
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) await testEnv.cleanup();
});

beforeEach(async () => {
  if (testEnv) await testEnv.clearFirestore();
});

test("withdrawalRequests rejects amount below 500 INR", async () => {
  const db = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    db.collection("withdrawalRequests").doc("req_low").set({
      requestId: "req_low",
      memberId: "mem_1",
      creatorName: "Altamash",
      creatorEmail: "creator@example.com",
      amount: 499,
      upiId: "altamash@ybl",
      status: "PENDING",
      adminNote: "",
      requestedAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("withdrawalRequests rejects invalid UPI ID without @", async () => {
  const db = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    db.collection("withdrawalRequests").doc("req_bad_upi").set({
      requestId: "req_bad_upi",
      memberId: "mem_1",
      creatorName: "Altamash",
      creatorEmail: "creator@example.com",
      amount: 500,
      upiId: "invalidupi",
      status: "PENDING",
      adminNote: "",
      requestedAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("withdrawalRequests allows >= 500 INR PENDING create and admin PROCESSING/DONE updates", async () => {
  const db = testEnv.unauthenticatedContext().firestore();
  const docRef = db.collection("withdrawalRequests").doc("req_ok");
  const pastTime = new Date(Date.now() - 1000);

  await assertSucceeds(
    docRef.set({
      requestId: "req_ok",
      memberId: "mem_1",
      creatorName: "Altamash",
      creatorEmail: "creator@example.com",
      amount: 500,
      upiId: "altamash@ybl",
      status: "PENDING",
      adminNote: "",
      requestedAt: pastTime,
      updatedAt: pastTime,
    })
  );

  await assertSucceeds(
    docRef.update({
      status: "PROCESSING",
      adminNote: "Initiated",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  await assertSucceeds(
    docRef.update({
      status: "DONE",
      adminNote: "UTR123456",
      updatedAt: new Date(Date.now() - 200),
    })
  );

  // Cannot tamper with amount after creation
  await assertFails(
    docRef.update({
      amount: 5000,
    })
  );

  // Syncing creatorEmail / migrating memberId during creator email change succeeds when financial fields stay intact
  await assertSucceeds(
    docRef.update({
      memberId: "mem_new_account",
      creatorEmail: "newemail@example.com",
      creatorName: "Altamash Updated",
      updatedAt: new Date(Date.now() - 100),
    })
  );

  // Cannot change memberId together with status/amount in the same write
  await assertFails(
    docRef.update({
      memberId: "mem_hijack",
      status: "REJECTED",
    })
  );
});

test("members rejects negative totalEarning/creditedViews/viewRatePer1000 and allows valid values", async () => {
  const db = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    db.collection("members").doc("mem_1").set({
      memberId: "mem_1",
      name: "Altamash",
      emailOrPhone: "creator@example.com",
      passkey: "pass123",
      role: "member",
      isActive: true,
      totalEarning: -10,
    })
  );

  await assertFails(
    db.collection("members").doc("mem_bad_views").set({
      memberId: "mem_bad_views",
      name: "Altamash",
      emailOrPhone: "creator@example.com",
      passkey: "pass124",
      role: "member",
      isActive: true,
      totalEarning: 190,
      creditedViews: -100,
      viewRatePer1000: 190,
    })
  );

  await assertFails(
    db.collection("members").doc("mem_bad_rate").set({
      memberId: "mem_bad_rate",
      name: "Altamash",
      emailOrPhone: "creator@example.com",
      passkey: "pass125",
      role: "member",
      isActive: true,
      totalEarning: 0,
      creditedViews: 1000,
      viewRatePer1000: -190,
    })
  );

  await assertSucceeds(
    db.collection("members").doc("mem_1").set({
      memberId: "mem_1",
      name: "Altamash",
      emailOrPhone: "creator@example.com",
      passkey: "pass123",
      role: "member",
      isActive: true,
      totalEarning: 190,
      creditedViews: 1000,
      viewRatePer1000: 190,
    })
  );

  await assertSucceeds(
    db.collection("members").doc("mem_1").update({
      creditedViews: 2500,
      totalEarning: 475,
      viewRatePer1000: 190,
    })
  );

  // Admin can delete member document cleanly
  await assertSucceeds(db.collection("members").doc("mem_1").delete());
});

test("notifications rejects empty title or oversized message, and allows valid broadcast", async () => {
  const db = testEnv.unauthenticatedContext().firestore();
  const pastTime = new Date(Date.now() - 1000);

  await assertFails(
    db.collection("notifications").doc("ntf_bad").set({
      notificationId: "ntf_bad",
      title: "",
      message: "Hello downloaders",
      sentBy: "Admin",
      createdAt: pastTime,
      isActive: true,
    })
  );

  await assertFails(
    db.collection("notifications").doc("ntf_long").set({
      notificationId: "ntf_long",
      title: "Update",
      message: "x".repeat(501),
      sentBy: "Admin",
      createdAt: pastTime,
      isActive: true,
    })
  );

  const validRef = db.collection("notifications").doc("ntf_ok");
  await assertSucceeds(
    validRef.set({
      notificationId: "ntf_ok",
      title: "New Movie Uploaded",
      message: "Check the latest video link shared on our channel!",
      imageUrl: "https://example.com/poster.jpg",
      actionUrl: "https://t.me/VidVoraApp",
      sentBy: "Superadmin",
      createdAt: pastTime,
      isActive: true,
    })
  );

  await assertFails(
    db.collection("notifications").doc("ntf_bad_url").set({
      notificationId: "ntf_bad_url",
      title: "Bad URL",
      message: "Too long URL",
      actionUrl: "https://example.com/" + "a".repeat(1000),
      sentBy: "Admin",
      createdAt: pastTime,
      isActive: true,
    })
  );

  await assertSucceeds(validRef.get());
});

test("appInstalls validates schema, prevents ghost fields/negative sessions, and allows heartbeat updates", async () => {
  const db = testEnv.unauthenticatedContext().firestore();
  const pastTime = new Date(Date.now() - 2000);
  const recentTime = new Date(Date.now() - 500);

  // Reject missing fields or invalid sessionCount
  await assertFails(
    db.collection("appInstalls").doc("inst_bad_1").set({
      installId: "inst_bad_1",
      firstInstalledAt: pastTime,
      lastSeenAt: pastTime,
      appVersion: "1.0",
      deviceLabel: "Pixel 8",
      androidVersion: "Android 15",
      sessionCount: 0,
      isMarkedUninstalled: false,
    })
  );

  // Reject ghost fields
  await assertFails(
    db.collection("appInstalls").doc("inst_bad_ghost").set({
      installId: "inst_bad_ghost",
      firstInstalledAt: pastTime,
      lastSeenAt: pastTime,
      appVersion: "1.0",
      deviceLabel: "Pixel 8",
      androidVersion: "Android 15",
      sessionCount: 1,
      isMarkedUninstalled: false,
      imei: "1234567890",
    })
  );

  // Allow valid initial install registration
  const installRef = db.collection("appInstalls").doc("inst_valid_01");
  await assertSucceeds(
    installRef.set({
      installId: "inst_valid_01",
      firstInstalledAt: pastTime,
      lastSeenAt: pastTime,
      appVersion: "1.0",
      deviceLabel: "Samsung Galaxy",
      androidVersion: "Android 14",
      sessionCount: 1,
      isMarkedUninstalled: false,
    })
  );

  // Allow valid heartbeat update preserving firstInstalledAt and installId
  await assertSucceeds(
    installRef.update({
      lastSeenAt: recentTime,
      sessionCount: 2,
      appVersion: "1.1",
    })
  );

  // Reject tampering with firstInstalledAt on update
  await assertFails(
    installRef.update({
      firstInstalledAt: recentTime,
    })
  );

  await assertSucceeds(installRef.get());
});

test("appConfig validates main, 5 built-in categories, and admin customCategories", async () => {
  const db = testEnv.unauthenticatedContext().firestore();
  const configRef = db.collection("appConfig").doc("general");
  const pastTime = new Date(Date.now() - 1000);

  await assertFails(
    configRef.set({
      telegramLink: "https://t.me/VidVoraApp",
      categoryLinks: {
        unknown_category: "https://t.me/bad",
      },
      updatedAt: pastTime,
    })
  );

  // Reject malformed customCategories (empty name)
  await assertFails(
    configRef.set({
      telegramLink: "https://t.me/VidVoraApp",
      categoryLinks: {
        pakistani_drama: "https://t.me/pak_drama",
      },
      customCategories: [
        { id: "cat_bollywood_1", name: "", link: "https://t.me/bolly" },
      ],
      updatedAt: pastTime,
    })
  );

  // Reject invalid built-in key in deletedCategoryKeys
  await assertFails(
    configRef.set({
      telegramLink: "https://t.me/VidVoraApp",
      deletedCategoryKeys: ["invalid_built_in_key"],
      updatedAt: pastTime,
    })
  );

  // Reject empty string in categoryNames
  await assertFails(
    configRef.set({
      telegramLink: "https://t.me/VidVoraApp",
      categoryNames: {
        pakistani_drama: "",
      },
      updatedAt: pastTime,
    })
  );

  await assertSucceeds(
    configRef.set({
      telegramLink: "https://t.me/VidVoraApp",
      creatorBotLink: "https://t.me/VidVoraUploadBot",
      categoryLinks: {
        pakistani_drama: "https://t.me/pak_drama",
        south_movie: "https://t.me/south_movie",
        ullu_tv_series: "https://t.me/ullu_tv",
        bhojpuri_movie: "https://t.me/bhojpuri_hits",
        adult_content: "",
      },
      categoryNames: {
        pakistani_drama: "Pakistani Shows",
      },
      deletedCategoryKeys: ["adult_content"],
      customCategories: [
        { id: "cat_bollywood_1", name: "Bollywood Movie", link: "https://t.me/bollywood_hits" },
        { id: "cat_web_series_2", name: "Web Series", link: "https://t.me/web_series_vip" },
      ],
      updatedAt: pastTime,
    })
  );

  await assertSucceeds(configRef.get());
});

test("appConfig/appUpdate validates apkUrl protocol, versionCode >= 1, and allows valid config", async () => {
  const db = testEnv.unauthenticatedContext().firestore();
  const updateRef = db.collection("appConfig").doc("appUpdate");
  const pastTime = new Date(Date.now() - 1000);

  // Reject non-http apkUrl
  await assertFails(
    updateRef.set({
      isEnabled: true,
      latestVersionCode: 2,
      latestVersionName: "1.1",
      title: "Update Available",
      message: "Bug fixes",
      apkUrl: "ftp://example.com/vidvora.apk",
      isForceUpdate: false,
      updatedAt: pastTime,
    })
  );

  // Reject versionCode <= 0
  await assertFails(
    updateRef.set({
      isEnabled: true,
      latestVersionCode: 0,
      latestVersionName: "1.0",
      title: "Update Available",
      message: "Bug fixes",
      apkUrl: "https://d1mn8mrvlzisg5.cloudfront.net/vidvora.apk",
      isForceUpdate: false,
      updatedAt: pastTime,
    })
  );

  // Allow valid self-hosted update config
  await assertSucceeds(
    updateRef.set({
      isEnabled: true,
      latestVersionCode: 2,
      latestVersionName: "1.1",
      title: "VidVora 1.1 Available",
      message: "Faster streaming and UI improvements.",
      apkUrl: "https://d1mn8mrvlzisg5.cloudfront.net/vidvora.apk",
      isForceUpdate: true,
      updatedAt: pastTime,
    })
  );

  await assertSucceeds(updateRef.get());
});

test("supportChats and messages enforce valid schema, user chat start, admin reply, and immutability", async () => {
  const db = testEnv.unauthenticatedContext().firestore();
  const pastTime = new Date(Date.now() - 2000);
  const recentTime = new Date(Date.now() - 500);
  const chatRef = db.collection("supportChats").doc("sup_user_01");

  // Reject creating a chat pre-marked with lastSenderRole = admin
  await assertFails(
    chatRef.set({
      chatId: "sup_user_01",
      userName: "Guest",
      deviceLabel: "Pixel 8",
      lastMessage: "Spoofed admin message",
      lastSenderRole: "admin",
      lastMessageAt: pastTime,
      createdAt: pastTime,
      unreadByAdminCount: 0,
      unreadByUserCount: 1,
      status: "OPEN",
    })
  );

  // Allow valid user-initiated support chat
  await assertSucceeds(
    chatRef.set({
      chatId: "sup_user_01",
      userName: "Rahul",
      deviceLabel: "Redmi Note 12",
      lastMessage: "Link play nahi ho raha",
      lastSenderRole: "user",
      lastMessageAt: pastTime,
      createdAt: pastTime,
      unreadByAdminCount: 1,
      unreadByUserCount: 0,
      status: "OPEN",
    })
  );

  // User message
  const userMsgRef = chatRef.collection("messages").doc("msg_01");
  await assertSucceeds(
    userMsgRef.set({
      messageId: "msg_01",
      chatId: "sup_user_01",
      senderRole: "user",
      senderName: "Rahul",
      text: "Link play nahi ho raha",
      createdAt: pastTime,
    })
  );

  // Reject empty message text
  await assertFails(
    chatRef.collection("messages").doc("msg_empty").set({
      messageId: "msg_empty",
      chatId: "sup_user_01",
      senderRole: "user",
      senderName: "Rahul",
      text: "",
      createdAt: pastTime,
    })
  );

  // Admin reply message and chat thread update
  const adminMsgRef = chatRef.collection("messages").doc("msg_02");
  await assertSucceeds(
    adminMsgRef.set({
      messageId: "msg_02",
      chatId: "sup_user_01",
      senderRole: "admin",
      senderName: "VidVora Admin",
      text: "Naya link paste karein, abhi update kar diya hai.",
      createdAt: recentTime,
    })
  );

  await assertSucceeds(
    chatRef.update({
      lastMessage: "Naya link paste karein, abhi update kar diya hai.",
      lastSenderRole: "admin",
      lastMessageAt: recentTime,
      unreadByAdminCount: 0,
      unreadByUserCount: 1,
      status: "OPEN",
    })
  );

  // Reject mutating an already sent message
  await assertFails(
    adminMsgRef.update({
      text: "Tampered text",
    })
  );

  await assertSucceeds(chatRef.get());
});

test("creatorEvents validates schema, allows admin create/edit/delete, and protects immutable createdAt", async () => {
  const db = testEnv.unauthenticatedContext().firestore();
  const pastTime = new Date(Date.now() - 2000);
  const recentTime = new Date(Date.now() - 500);
  const eventRef = db.collection("creatorEvents").doc("evt_gift_01");

  // Reject empty title
  await assertFails(
    db.collection("creatorEvents").doc("evt_bad_title").set({
      eventId: "evt_bad_title",
      title: "",
      description: "Win an iPhone",
      giftLabel: "Special Gift",
      imageUrl: "https://example.com/gift.jpg",
      createdBy: "Superadmin",
      createdAt: pastTime,
      updatedAt: pastTime,
      isActive: true,
    })
  );

  // Reject invalid imageUrl protocol
  await assertFails(
    db.collection("creatorEvents").doc("evt_bad_img").set({
      eventId: "evt_bad_img",
      title: "Festive Event",
      description: "Upload 10 videos to win!",
      giftLabel: "Gift Hamper",
      imageUrl: "ftp://example.com/gift.jpg",
      createdBy: "Superadmin",
      createdAt: pastTime,
      updatedAt: pastTime,
      isActive: true,
    })
  );

  // Allow valid create
  await assertSucceeds(
    eventRef.set({
      eventId: "evt_gift_01",
      title: "Diwali Creator Gift Challenge",
      description: "Upload 5 viral videos this week to win a surprise gift hamper + ₹2,000 bonus!",
      giftLabel: "Gift Hamper",
      imageUrl: "https://example.com/diwali-gift.png",
      createdBy: "Superadmin",
      createdAt: pastTime,
      updatedAt: pastTime,
      isActive: true,
    })
  );

  // Allow valid edit updating title, description, giftLabel, imageUrl, isActive, and updatedAt
  await assertSucceeds(
    eventRef.update({
      title: "Diwali Creator Mega Gift",
      description: "Upload 3 viral videos to win ₹3,000 bonus!",
      giftLabel: "₹3,000 Bonus",
      imageUrl: "data:image/jpeg;base64,/9j/4AAQSkZJRg==",
      updatedAt: recentTime,
      isActive: true,
    })
  );

  // Reject tampering with createdAt on update
  await assertFails(
    eventRef.update({
      createdAt: recentTime,
      updatedAt: recentTime,
    })
  );

  await assertSucceeds(eventRef.get());
  await assertSucceeds(eventRef.delete());
});
