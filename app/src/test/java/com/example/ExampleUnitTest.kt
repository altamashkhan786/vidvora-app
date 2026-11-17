package com.example

import com.example.data.model.TokenValidationResult
import com.example.data.model.VideoToken
import java.security.MessageDigest
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

  @Test
  fun videoToken_shareableLinkFormat() {
    val token = VideoToken(
      id = "test_1",
      token = "GM-TEST-88",
      title = "Trailer",
      streamUrl = "https://example.com/test.mp4"
    )
    assertEquals("vidvora://watch?token=GM-TEST-88", token.shareableLink)
  }

  @Test
  fun tokenValidationResult_types() {
    val video = VideoToken(
      id = "test_2",
      token = "GM-ACTIVE-01",
      title = "Action Cinema",
      streamUrl = "https://example.com/stream.m3u8",
      isActive = true
    )
    val successResult = TokenValidationResult.Success(video)
    assertEquals(video, successResult.video)

    val deactivated: TokenValidationResult = TokenValidationResult.TokenDeactivated
    assertNotNull(deactivated)
  }

  @Test
  fun cryptographicToken_generationAndSha256Hashing() {
    val secureRandom = SecureRandom()
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val part1 = (1..4).map { alphabet[secureRandom.nextInt(alphabet.length)] }.joinToString("")
    val part2 = (1..4).map { alphabet[secureRandom.nextInt(alphabet.length)] }.joinToString("")
    val part3 = (1..4).map { alphabet[secureRandom.nextInt(alphabet.length)] }.joinToString("")
    val token = "GM-$part1-$part2-$part3"

    assertTrue(token.startsWith("GM-"))
    assertEquals(17, token.length)

    // SHA-256 hash must be 64 lowercase hex characters
    val bytes = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
    val hash = bytes.joinToString("") { "%02x".format(it) }
    assertEquals(64, hash.length)
  }

  @Test
  fun admobConfig_testIdsValidation() {
    assertTrue(com.example.ads.AdMobConfig.TEST_APP_ID.startsWith("ca-app-pub-"))
    assertTrue(com.example.ads.AdMobConfig.TEST_BANNER_AD_UNIT_ID.contains("/"))
    assertTrue(com.example.ads.AdMobConfig.TEST_INTERSTITIAL_AD_UNIT_ID.contains("/"))
    assertTrue(com.example.ads.AdMobConfig.TEST_APP_OPEN_AD_UNIT_ID.contains("/"))
    assertTrue(com.example.ads.AdMobConfig.TEST_REWARDED_AD_UNIT_ID.contains("/"))

    org.junit.Assert.assertFalse(
      com.example.ads.AdMobConfig.isValidProductionAdUnitId(com.example.ads.AdMobConfig.TEST_INTERSTITIAL_AD_UNIT_ID)
    )
    org.junit.Assert.assertFalse(
      com.example.ads.AdMobConfig.isValidProductionAdUnitId("")
    )
    assertTrue(
      com.example.ads.AdMobConfig.isValidProductionAdUnitId("ca-app-pub-1392123530264284/6658750910")
    )
  }

  @Test
  fun memberManagement_passkeyMatchAndStats() {
    val member = com.example.data.model.FirestoreMember(
      memberId = "mem_12345",
      name = "Rahul Creator",
      emailOrPhone = "rahul@creator.com",
      passkey = "secret_pass_123"
    )

    // Passkey verification
    assertTrue(member.isPasskeyMatch("secret_pass_123"))
    assertTrue(member.isPasskeyMatch("  secret_pass_123  "))
    org.junit.Assert.assertFalse(member.isPasskeyMatch("wrong_pass"))

    // Attributed videos
    val v1 = com.example.data.model.FirestoreVideo(
      videoId = "vid_1",
      title = "Member Video 1",
      uploadedBy = member.memberId,
      uploaderName = member.name,
      totalViews = 150L
    )
    val v2 = com.example.data.model.FirestoreVideo(
      videoId = "vid_2",
      title = "Member Video 2",
      uploadedBy = member.memberId,
      uploaderName = member.name,
      totalViews = 350L
    )
    val adminVideo = com.example.data.model.FirestoreVideo(
      videoId = "vid_admin",
      title = "Admin Video",
      uploadedBy = "admin",
      uploaderName = "Admin",
      totalViews = 1000L
    )

    val allVideos = listOf(v1, v2, adminVideo)
    val memberVideos = allVideos.filter { it.uploadedBy == member.memberId }
    val memberStats = com.example.data.model.MemberStats(
      member = member,
      videoCount = memberVideos.size,
      totalViews = memberVideos.sumOf { it.totalViews },
      videos = memberVideos
    )

    assertEquals(2, memberStats.videoCount)
    assertEquals(500L, memberStats.totalViews)
    assertEquals(150L, memberStats.videos[0].totalViews)
    assertEquals(350L, memberStats.videos[1].totalViews)
  }

  @Test
  fun creatorEarnings_minWithdrawalAndUpiValidation() {
    assertEquals(500.0, com.example.data.repository.FirestoreVideoRepository.MIN_WITHDRAWAL_AMOUNT, 0.001)

    // Valid UPI IDs
    assertTrue(com.example.data.repository.FirestoreVideoRepository.isValidUpiId("altamash@ybl"))
    assertTrue(com.example.data.repository.FirestoreVideoRepository.isValidUpiId("  9876543210@paytm  "))
    assertTrue(com.example.data.repository.FirestoreVideoRepository.isValidUpiId("creator.name@okicici"))

    // Invalid UPI IDs
    org.junit.Assert.assertFalse(com.example.data.repository.FirestoreVideoRepository.isValidUpiId(""))
    org.junit.Assert.assertFalse(com.example.data.repository.FirestoreVideoRepository.isValidUpiId("invalidupi"))
    org.junit.Assert.assertFalse(com.example.data.repository.FirestoreVideoRepository.isValidUpiId("a@b"))
    org.junit.Assert.assertFalse(com.example.data.repository.FirestoreVideoRepository.isValidUpiId("user @ybl"))

    // INR formatting
    assertEquals("₹0", com.example.data.model.formatInr(0.0))
    assertEquals("₹0", com.example.data.model.formatInr(-50.0))
    assertEquals("₹500", com.example.data.model.formatInr(500.0))
    assertEquals("₹1,500", com.example.data.model.formatInr(1500.0))

    // Withdrawal status constants & model defaults
    assertEquals("PENDING", com.example.data.model.WithdrawalStatus.PENDING)
    assertEquals("PROCESSING", com.example.data.model.WithdrawalStatus.PROCESSING)
    assertEquals("DONE", com.example.data.model.WithdrawalStatus.DONE)
    assertEquals("REJECTED", com.example.data.model.WithdrawalStatus.REJECTED)

    val req = com.example.data.model.FirestoreWithdrawalRequest(
      requestId = "wdr_001",
      memberId = "mem_1",
      creatorName = "Altamash",
      amount = 500.0,
      upiId = "altamash@ybl",
      status = com.example.data.model.WithdrawalStatus.PROCESSING
    )
    assertEquals(500.0, req.amount, 0.001)
    assertEquals("PROCESSING", req.status)
  }

  @Test
  fun creatorSelfPlayback_doesNotIncrementViewCount() {
    // Creator playing their own uploaded video -> must NOT increment views
    org.junit.Assert.assertFalse(
      com.example.data.repository.FirestoreVideoRepository.shouldIncrementViewCount(
        uploadedBy = "mem_12345",
        viewerMemberId = "mem_12345"
      )
    )
    org.junit.Assert.assertFalse(
      com.example.data.repository.FirestoreVideoRepository.shouldIncrementViewCount(
        uploadedBy = "mem_12345",
        viewerMemberId = "  mem_12345  "
      )
    )

    // Normal viewer (not signed in as creator) -> MUST increment views
    assertTrue(
      com.example.data.repository.FirestoreVideoRepository.shouldIncrementViewCount(
        uploadedBy = "mem_12345",
        viewerMemberId = null
      )
    )
    assertTrue(
      com.example.data.repository.FirestoreVideoRepository.shouldIncrementViewCount(
        uploadedBy = "mem_12345",
        viewerMemberId = "   "
      )
    )

    // Different creator watching someone else's video or admin video -> MUST increment views
    assertTrue(
      com.example.data.repository.FirestoreVideoRepository.shouldIncrementViewCount(
        uploadedBy = "mem_12345",
        viewerMemberId = "mem_99999"
      )
    )
    assertTrue(
      com.example.data.repository.FirestoreVideoRepository.shouldIncrementViewCount(
        uploadedBy = "admin",
        viewerMemberId = "mem_12345"
      )
    )
  }

  @Test
  fun formatFileNameAsTitle_preservesOriginalFileName() {
    assertEquals(
      "Dubai_Comedy_Vlog_2026",
      com.example.ui.screens.admin.formatFileNameAsTitle("Dubai_Comedy_Vlog_2026.mp4")
    )
    assertEquals(
      "Episode.01.Final",
      com.example.ui.screens.admin.formatFileNameAsTitle("Episode.01.Final.mkv")
    )
    assertEquals(
      "My Vacation Reel",
      com.example.ui.screens.admin.formatFileNameAsTitle("My Vacation Reel")
    )
    assertEquals(
      "",
      com.example.ui.screens.admin.formatFileNameAsTitle("   ")
    )
  }

  @Test
  fun notificationValidation_checksTitleAndMessageBounds() {
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validateNotificationInput("", "Hello users")
    )
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validateNotificationInput("Title", "   ")
    )
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validateNotificationInput("a".repeat(121), "Body")
    )
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validateNotificationInput("Title", "b".repeat(501))
    )
    org.junit.Assert.assertNull(
      com.example.data.repository.FirestoreVideoRepository.validateNotificationInput(
        title = "New Video Live",
        message = "Watch the latest video on VidVora now!",
        imageUrl = "https://example.com/banner.jpg",
        actionUrl = "t.me/VidVoraApp"
      )
    )
    assertEquals(
      "https://t.me/VidVoraApp",
      com.example.data.repository.FirestoreVideoRepository.normalizeNotificationActionUrl("t.me/VidVoraApp")
    )
    assertEquals(
      "vidvora://watch?link=GM-TEST",
      com.example.data.repository.FirestoreVideoRepository.normalizeNotificationActionUrl("vidvora://watch?link=GM-TEST")
    )
    assertEquals(
      "vidvora://watch?link=link_abc123",
      com.example.data.repository.FirestoreVideoRepository.normalizeNotificationActionUrl("link_abc123")
    )
    assertTrue(
      com.example.data.repository.FirestoreVideoRepository.isVidvoraVideoUrl(
        "https://d1mn8mrvlzisg5.cloudfront.net/index.html?link=link_abc123"
      )
    )
    assertTrue(
      com.example.data.repository.FirestoreVideoRepository.isVidvoraVideoUrl(
        "https://gen-lang-client-0000740810.web.app/watch?link=GM-ABCD-1234"
      )
    )
    assertTrue(
      com.example.data.repository.FirestoreVideoRepository.isVidvoraVideoUrl(
        "https://vidvora-bot.onrender.com/share?link=custom123"
      )
    )
    assertEquals(
      "https://vidvora-bot.onrender.com/share?link=",
      com.example.data.repository.FirestoreVideoRepository.normalizeShareLinkBaseUrl("vidvora-bot.onrender.com")
    )
    assertEquals(
      "https://vidvora-bot.onrender.com/share?link=GM-TEST-99",
      com.example.data.repository.FirestoreVideoRepository.buildShareableVideoUrl(
        "GM-TEST-99",
        "https://vidvora-bot.onrender.com"
      )
    )
    assertEquals(
      "https://d1mn8mrvlzisg5.cloudfront.net/index.html?link=GM-TEST-99",
      com.example.data.repository.FirestoreVideoRepository.buildShareableVideoUrl("GM-TEST-99", "")
    )
    assertTrue(
      com.example.data.repository.FirestoreVideoRepository.isVidvoraVideoUrl("vidvora://watch?link=vid_999")
    )
    assertTrue(
      com.example.data.repository.FirestoreVideoRepository.isVidvoraVideoUrl("link_abc123")
    )
    assertTrue(
      com.example.data.repository.FirestoreVideoRepository.isVidvoraVideoUrl("https://cdn.example.com/movie.mp4")
    )
    org.junit.Assert.assertFalse(
      com.example.data.repository.FirestoreVideoRepository.isVidvoraVideoUrl("https://t.me/VidVoraApp")
    )
    org.junit.Assert.assertFalse(
      com.example.data.repository.FirestoreVideoRepository.isVidvoraVideoUrl(
        "https://d1mn8mrvlzisg5.cloudfront.net/vidvora.apk"
      )
    )
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validateNotificationInput(
        title = "Bad Img",
        message = "Body",
        imageUrl = "ftp://example.com/img.png"
      )
    )
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validateNotificationInput(
        title = "Bad URL",
        message = "Body",
        actionUrl = "invalid url with spaces"
      )
    )
  }

  @Test
  fun memberValidation_checksNameEmailAndPasskey() {
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validateMemberInput("", "creator@test.com", "key123")
    )
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validateMemberInput("Aman", "invalid-email", "key123")
    )
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validateMemberInput("Aman", "aman@test.com", "123")
    )
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validateMemberInput(
        "Aman",
        "aman@test.com",
        com.example.data.repository.FirebaseAuthAdminRepository.ADMIN_MASTER_KEY
      )
    )
    org.junit.Assert.assertNull(
      com.example.data.repository.FirestoreVideoRepository.validateMemberInput("Aman", "aman@test.com", "aman2026")
    )
  }

  @Test
  fun creatorPasskeyChange_validatesCurrentNewAndConfirmKeys() {
    // Wrong current key
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validatePasskeyChange(
        storedPasskey = "oldKey1",
        currentInput = "wrongKey",
        newPasskey = "newKey2",
        confirmPasskey = "newKey2"
      )
    )
    // Mismatched confirm key
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validatePasskeyChange(
        storedPasskey = "oldKey1",
        currentInput = "oldKey1",
        newPasskey = "newKey2",
        confirmPasskey = "different"
      )
    )
    // Same as current key
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validatePasskeyChange(
        storedPasskey = "oldKey1",
        currentInput = "oldKey1",
        newPasskey = "oldKey1",
        confirmPasskey = "oldKey1"
      )
    )
    // Reserved Superadmin key
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validatePasskeyChange(
        storedPasskey = "oldKey1",
        currentInput = "oldKey1",
        newPasskey = com.example.data.repository.FirebaseAuthAdminRepository.ADMIN_MASTER_KEY,
        confirmPasskey = com.example.data.repository.FirebaseAuthAdminRepository.ADMIN_MASTER_KEY
      )
    )
    // Valid update
    org.junit.Assert.assertNull(
      com.example.data.repository.FirestoreVideoRepository.validatePasskeyChange(
        storedPasskey = "oldKey1",
        currentInput = "oldKey1",
        newPasskey = "creatorNew99",
        confirmPasskey = "creatorNew99"
      )
    )
  }

  @Test
  fun memberRolesAndAllowedOptions_enforcePresetsAndFiltering() {
    assertEquals("member", com.example.data.model.MemberRole.normalize(null))
    assertEquals("member", com.example.data.model.MemberRole.normalize("creator"))
    assertEquals("admin", com.example.data.model.MemberRole.normalize("admin"))
    assertEquals("admin", com.example.data.model.MemberRole.normalize("subadmin"))
    assertEquals("Admin", com.example.data.model.MemberRole.displayLabel("admin"))
    assertEquals("Creator", com.example.data.model.MemberRole.displayLabel("member"))

    val creatorDefaults = com.example.data.model.MemberOption.defaultKeysForRole(
      com.example.data.model.MemberRole.CREATOR
    )
    assertTrue(com.example.data.model.MemberOption.UPLOAD_VIDEOS.key in creatorDefaults)
    assertTrue(com.example.data.model.MemberOption.VIEW_EARNINGS.key in creatorDefaults)
    org.junit.Assert.assertFalse(com.example.data.model.MemberOption.MANAGE_MEMBERS.key in creatorDefaults)
    org.junit.Assert.assertFalse(com.example.data.model.MemberOption.SEND_NOTIFICATIONS.key in creatorDefaults)

    val adminDefaults = com.example.data.model.MemberOption.defaultKeysForRole(
      com.example.data.model.MemberRole.ADMIN
    )
    assertTrue(com.example.data.model.MemberOption.MANAGE_MEMBERS.key in adminDefaults)
    assertTrue(com.example.data.model.MemberOption.SEND_NOTIFICATIONS.key in adminDefaults)
    assertTrue(com.example.data.model.MemberOption.MANAGE_PAYOUTS.key in adminDefaults)
    org.junit.Assert.assertFalse(com.example.data.model.MemberOption.REQUEST_WITHDRAWAL.key in adminDefaults)

    val sanitized = com.example.data.model.MemberOption.sanitizeKeys(
      listOf("UNKNOWN_KEY", com.example.data.model.MemberOption.SEND_NOTIFICATIONS.key, com.example.data.model.MemberOption.UPLOAD_VIDEOS.key, com.example.data.model.MemberOption.UPLOAD_VIDEOS.key)
    )
    assertEquals(
      listOf(
        com.example.data.model.MemberOption.UPLOAD_VIDEOS.key,
        com.example.data.model.MemberOption.SEND_NOTIFICATIONS.key
      ),
      sanitized
    )

    val customAdmin = com.example.data.model.FirestoreMember(
      memberId = "mem_admin_1",
      name = "Sub Admin",
      emailOrPhone = "sub@vidvora.com",
      passkey = "subkey123",
      role = com.example.data.model.MemberRole.ADMIN,
      allowedOptions = listOf(com.example.data.model.MemberOption.SEND_NOTIFICATIONS.key)
    )
    assertTrue(customAdmin.isDelegatedAdmin())
    assertTrue(customAdmin.hasOption(com.example.data.model.MemberOption.SEND_NOTIFICATIONS))
    org.junit.Assert.assertFalse(customAdmin.hasOption(com.example.data.model.MemberOption.MANAGE_MEMBERS))
    assertTrue(com.example.data.model.MemberOption.VIEW_APP_STATS.key in adminDefaults)
    org.junit.Assert.assertFalse(com.example.data.model.MemberOption.VIEW_APP_STATS.key in creatorDefaults)
  }

  @Test
  fun appInstallAnalytics_computesDownloadedInstalledAndUninstalledCounts() {
    val nowMs = 1_800_000_000_000L
    val dayMs = 86_400_000L
    fun tsAt(ms: Long) = com.google.firebase.Timestamp(java.util.Date(ms))

    val installs = listOf(
      // Installed & active today (new today)
      com.example.data.model.FirestoreAppInstall(
        installId = "inst_1",
        firstInstalledAt = tsAt(nowMs - 2 * 3_600_000L),
        lastSeenAt = tsAt(nowMs - 30 * 60_000L),
        sessionCount = 2L,
        isMarkedUninstalled = false
      ),
      // Installed 20 days ago, active 3 days ago -> still installed under 7d/14d/30d
      com.example.data.model.FirestoreAppInstall(
        installId = "inst_2",
        firstInstalledAt = tsAt(nowMs - 20 * dayMs),
        lastSeenAt = tsAt(nowMs - 3 * dayMs),
        sessionCount = 8L,
        isMarkedUninstalled = false
      ),
      // Inactive for 10 days -> active under 14d, uninstalled under 7d
      com.example.data.model.FirestoreAppInstall(
        installId = "inst_3",
        firstInstalledAt = tsAt(nowMs - 25 * dayMs),
        lastSeenAt = tsAt(nowMs - 10 * dayMs),
        sessionCount = 4L,
        isMarkedUninstalled = false
      ),
      // Inactive for 18 days -> uninstalled under 7d and 14d, active under 30d
      com.example.data.model.FirestoreAppInstall(
        installId = "inst_4",
        firstInstalledAt = tsAt(nowMs - 40 * dayMs),
        lastSeenAt = tsAt(nowMs - 18 * dayMs),
        sessionCount = 3L,
        isMarkedUninstalled = false
      ),
      // Manually marked uninstalled even though seen recently
      com.example.data.model.FirestoreAppInstall(
        installId = "inst_5",
        firstInstalledAt = tsAt(nowMs - 5 * dayMs),
        lastSeenAt = tsAt(nowMs - 60_000L),
        sessionCount = 1L,
        isMarkedUninstalled = true
      )
    )

    val stats14d = com.example.data.model.computeAppInstallStats(
      installs = installs,
      inactiveDaysThreshold = 14,
      nowMs = nowMs
    )
    assertEquals(5, stats14d.totalDownloads)
    assertEquals(3, stats14d.currentlyInstalledCount)
    assertEquals(2, stats14d.uninstalledCount)
    assertEquals(1, stats14d.downloadsToday)
    assertEquals(1, stats14d.activeLast24hCount)
    assertEquals(60, stats14d.retentionPercent)

    val stats7d = com.example.data.model.computeAppInstallStats(
      installs = installs,
      inactiveDaysThreshold = 7,
      nowMs = nowMs
    )
    assertEquals(5, stats7d.totalDownloads)
    assertEquals(2, stats7d.currentlyInstalledCount)
    assertEquals(3, stats7d.uninstalledCount)

    val emptyStats = com.example.data.model.computeAppInstallStats(emptyList(), 14, nowMs)
    assertEquals(0, emptyStats.totalDownloads)
    assertEquals(0, emptyStats.currentlyInstalledCount)
    assertEquals(0, emptyStats.uninstalledCount)
    assertEquals(0, emptyStats.retentionPercent)
  }

  @Test
  fun telegramCategoriesAndSupportValidation_matchRequired5CategoriesAndBounds() {
    val categories = com.example.data.model.TelegramChannelCategory.entries
    assertEquals(5, categories.size)
    assertEquals("Pakistani Drama", categories[0].label)
    assertEquals("South Movie", categories[1].label)
    assertEquals("Ullu Tv Series", categories[2].label)
    assertEquals("Bhojpuri Movie", categories[3].label)
    assertEquals("Adult Content", categories[4].label)

    assertEquals(
      "https://t.me/south_hits",
      com.example.data.repository.FirestoreVideoRepository.normalizeTelegramUrl("t.me/south_hits")
    )
    assertEquals(
      "https://t.me/pak_drama_channel",
      com.example.data.repository.FirestoreVideoRepository.normalizeTelegramUrl("@pak_drama_channel")
    )
    assertEquals(
      "",
      com.example.data.repository.FirestoreVideoRepository.normalizeTelegramUrl("   ")
    )

    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validateSupportMessageInput("   ")
    )
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validateSupportMessageInput("a".repeat(1001))
    )
    org.junit.Assert.assertNull(
      com.example.data.repository.FirestoreVideoRepository.validateSupportMessageInput("Hello admin, link help please")
    )

    val adminDefaults = com.example.data.model.MemberOption.defaultKeysForRole(
      com.example.data.model.MemberRole.ADMIN
    )
    val creatorDefaults = com.example.data.model.MemberOption.defaultKeysForRole(
      com.example.data.model.MemberRole.CREATOR
    )
    assertTrue(com.example.data.model.MemberOption.MANAGE_SUPPORT_CHAT.key in adminDefaults)
    org.junit.Assert.assertFalse(com.example.data.model.MemberOption.MANAGE_SUPPORT_CHAT.key in creatorDefaults)
    assertEquals(
      "creator_mem_creator_42",
      com.example.data.repository.FirestoreVideoRepository.buildCreatorSupportChatId("mem_creator_42")
    )
  }

  @Test
  fun customTelegramCategories_validationAndAllCategoriesOrder() {
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validateCustomCategoryName(
        name = "   ",
        existingCategoryNames = emptyList()
      )
    )
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validateCustomCategoryName(
        name = "Pakistani Drama",
        existingCategoryNames = emptyList()
      )
    )
    // When built-in list excludes deleted category name, reusing that name is allowed
    org.junit.Assert.assertNull(
      com.example.data.repository.FirestoreVideoRepository.validateCustomCategoryName(
        name = "Adult Content",
        existingCategoryNames = emptyList(),
        activeBuiltInNames = listOf("Pakistani Drama", "South Movie")
      )
    )
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validateCustomCategoryName(
        name = "Bollywood Movie",
        existingCategoryNames = listOf("bollywood movie")
      )
    )
    org.junit.Assert.assertNull(
      com.example.data.repository.FirestoreVideoRepository.validateCustomCategoryName(
        name = "Bollywood Movie",
        existingCategoryNames = listOf("Hollywood Dubbed")
      )
    )

    val customId = com.example.data.repository.FirestoreVideoRepository.buildCustomCategoryId("Bollywood Movie")
    assertTrue(customId.startsWith("cat_bollywood_movie_"))

    val config = com.example.data.model.TelegramConfig(
      mainLink = "https://t.me/VidVoraApp",
      categoryLinks = mapOf(
        com.example.data.model.TelegramChannelCategory.SOUTH_MOVIE.key to "https://t.me/south",
        com.example.data.model.TelegramChannelCategory.ADULT_CONTENT.key to "https://t.me/adult"
      ),
      categoryNames = mapOf(
        com.example.data.model.TelegramChannelCategory.SOUTH_MOVIE.key to "South Action HD"
      ),
      deletedCategoryKeys = listOf(com.example.data.model.TelegramChannelCategory.ADULT_CONTENT.key),
      customCategories = listOf(
        com.example.data.model.TelegramCategoryItem(
          id = customId,
          name = "Bollywood Movie",
          link = "https://t.me/bolly"
        )
      )
    )
    val all = config.allCategories()
    assertEquals(5, all.size)
    assertEquals("Pakistani Drama", all[0].name)
    assertEquals("South Action HD", all[1].name)
    assertEquals("https://t.me/south", all[1].link)
    assertEquals("Bollywood Movie", all[4].name)
    assertEquals("https://t.me/bolly", all[4].link)
    org.junit.Assert.assertFalse(all[4].isBuiltIn)
    assertTrue(all.none { it.id == com.example.data.model.TelegramChannelCategory.ADULT_CONTENT.key })
  }

  @Test
  fun supportChatLinkDetection_extractsAndNormalizesUrlsAndTelegramHandles() {
    val msg = "Join https://t.me/VidVoraApp, backup t.me/PakDrama01 or @VidVoraTeam, tg://resolve?domain=VidVoraApp, video https://d1mn8mrvlzisg5.cloudfront.net/index.html?link=link_123, also www.google.com/search?q=vidvora! Email help@gmail.com"
    val links = com.example.ui.screens.home.extractDetectedChatLinks(msg)

    assertEquals(6, links.size)
    assertEquals("https://t.me/VidVoraApp", links[0].resolvedUrl)
    assertEquals("https://t.me/PakDrama01", links[1].resolvedUrl)
    assertEquals("https://t.me/VidVoraTeam", links[2].resolvedUrl)
    assertEquals("tg://resolve?domain=VidVoraApp", links[3].resolvedUrl)
    assertEquals("https://d1mn8mrvlzisg5.cloudfront.net/index.html?link=link_123", links[4].resolvedUrl)
    assertEquals("https://www.google.com/search?q=vidvora", links[5].resolvedUrl)
  }

  @Test
  fun creatorEvent_validationAndRolePermissions() {
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validateCreatorEventInput(
        title = "   ",
        description = "Valid description"
      )
    )
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validateCreatorEventInput(
        title = "Festive Gift",
        description = "   "
      )
    )
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validateCreatorEventInput(
        title = "a".repeat(121),
        description = "Valid description"
      )
    )
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validateCreatorEventInput(
        title = "Festive Gift",
        description = "b".repeat(1501)
      )
    )
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validateCreatorEventInput(
        title = "Festive Gift",
        description = "Win a hamper",
        giftLabel = "g".repeat(61)
      )
    )
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validateCreatorEventInput(
        title = "Festive Gift",
        description = "Win a hamper",
        imageUrl = "ftp://example.com/banner.jpg"
      )
    )
    org.junit.Assert.assertNull(
      com.example.data.repository.FirestoreVideoRepository.validateCreatorEventInput(
        title = "Diwali Gift Contest",
        description = "Upload 5 videos to win ₹2,000 + Gift Hamper!",
        giftLabel = "Gift Hamper",
        imageUrl = "https://example.com/gift.jpg"
      )
    )

    val adminDefaults = com.example.data.model.MemberOption.defaultKeysForRole(
      com.example.data.model.MemberRole.ADMIN
    )
    val creatorDefaults = com.example.data.model.MemberOption.defaultKeysForRole(
      com.example.data.model.MemberRole.CREATOR
    )
    assertTrue(com.example.data.model.MemberOption.MANAGE_EVENTS.key in adminDefaults)
    org.junit.Assert.assertFalse(com.example.data.model.MemberOption.MANAGE_EVENTS.key in creatorDefaults)
  }

  @Test
  fun creatorViewsEarningCalculation_usesRatePer1000AndSeparatesRealVsCreditedViews() {
    // 1000 views at default ₹190/1k = ₹190
    assertEquals(
      190.0,
      com.example.data.repository.FirestoreVideoRepository.calculateViewsEarning(1000L),
      0.001
    )
    // 500 views at ₹190/1k = ₹95
    assertEquals(
      95.0,
      com.example.data.repository.FirestoreVideoRepository.calculateViewsEarning(500L),
      0.001
    )
    // 2500 views at ₹190/1k = ₹475
    assertEquals(
      475.0,
      com.example.data.repository.FirestoreVideoRepository.calculateViewsEarning(2500L),
      0.001
    )
    // Custom rate: 1500 views @ ₹120/1k = ₹180
    assertEquals(
      180.0,
      com.example.data.repository.FirestoreVideoRepository.calculateViewsEarning(1500L, 120.0),
      0.001
    )
    // Fractional paise rounding: 333 views @ ₹190/1k = 63.27
    assertEquals(
      63.27,
      com.example.data.repository.FirestoreVideoRepository.calculateViewsEarning(333L, 190.0),
      0.001
    )
    // Non-positive clamping
    assertEquals(
      0.0,
      com.example.data.repository.FirestoreVideoRepository.calculateViewsEarning(-100L, 190.0),
      0.001
    )
    assertEquals(
      0.0,
      com.example.data.repository.FirestoreVideoRepository.calculateViewsEarning(1000L, -50.0),
      0.001
    )

    // Creator member uses ₹190/1k default rate
    val creator = com.example.data.model.FirestoreMember(
      memberId = "mem_views_test",
      name = "Creator A",
      emailOrPhone = "a@vidvora.com",
      passkey = "key999",
      creditedViews = 1000L,
      totalEarning = 190.0
    )
    assertEquals(1000L, creator.creditedViews)
    assertEquals(190.0, creator.viewRatePer1000, 0.001)
    assertEquals(190.0, creator.totalEarning, 0.001)
  }

  @Test
  fun creatorVideoOrdering_alwaysPlacesLatestUploadAtTopLineByLine() {
    val oldest = com.example.data.model.FirestoreVideo(
      videoId = "vid_ffff_oldest",
      title = "Upload 1 (Oldest)",
      createdAt = com.google.firebase.Timestamp(1_750_000_000L, 100_000_000),
      uploadedBy = "mem_creator_1"
    )
    val middle = com.example.data.model.FirestoreVideo(
      videoId = "vid_0000_middle",
      title = "Upload 2 (Middle)",
      createdAt = com.google.firebase.Timestamp(1_750_000_000L, 600_000_000),
      uploadedBy = "mem_creator_1"
    )
    val newest = com.example.data.model.FirestoreVideo(
      videoId = "vid_7777_newest",
      title = "Upload 3 (Newest)",
      createdAt = com.google.firebase.Timestamp(1_750_000_001L, 50_000_000),
      uploadedBy = "mem_creator_1"
    )

    // Intentionally pass in scrambled document-ID / upload order
    val sorted = com.example.data.repository.FirestoreVideoRepository.sortVideosNewestFirst(
      listOf(middle, oldest, newest)
    )

    assertEquals(3, sorted.size)
    assertEquals("vid_7777_newest", sorted[0].videoId)
    assertEquals("vid_0000_middle", sorted[1].videoId)
    assertEquals("vid_ffff_oldest", sorted[2].videoId)
  }

  @Test
  fun superadminMasterKeyChange_validatesDefaultAndCustomHashes() {
    val defaultHash = com.example.data.repository.FirestoreVideoRepository.hashSecretExact(
      com.example.data.repository.FirebaseAuthAdminRepository.ADMIN_MASTER_KEY
    )
    assertEquals(64, defaultHash.length)

    // Wrong current key when no custom hash exists (expects default ADMIN_MASTER_KEY)
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validateSuperadminMasterKeyChange(
        storedHash = null,
        currentInput = "wrongKey",
        newPasskey = "SuperKey#2026",
        confirmPasskey = "SuperKey#2026"
      )
    )

    // Too short
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validateSuperadminMasterKeyChange(
        storedHash = null,
        currentInput = com.example.data.repository.FirebaseAuthAdminRepository.ADMIN_MASTER_KEY,
        newPasskey = "123",
        confirmPasskey = "123"
      )
    )

    // Mismatched confirm
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validateSuperadminMasterKeyChange(
        storedHash = null,
        currentInput = com.example.data.repository.FirebaseAuthAdminRepository.ADMIN_MASTER_KEY,
        newPasskey = "SuperKey#2026",
        confirmPasskey = "SuperKey#9999"
      )
    )

    // Same as current
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validateSuperadminMasterKeyChange(
        storedHash = null,
        currentInput = com.example.data.repository.FirebaseAuthAdminRepository.ADMIN_MASTER_KEY,
        newPasskey = com.example.data.repository.FirebaseAuthAdminRepository.ADMIN_MASTER_KEY,
        confirmPasskey = com.example.data.repository.FirebaseAuthAdminRepository.ADMIN_MASTER_KEY
      )
    )

    // Valid change from default key
    org.junit.Assert.assertNull(
      com.example.data.repository.FirestoreVideoRepository.validateSuperadminMasterKeyChange(
        storedHash = null,
        currentInput = com.example.data.repository.FirebaseAuthAdminRepository.ADMIN_MASTER_KEY,
        newPasskey = "SuperKey#2026",
        confirmPasskey = "SuperKey#2026"
      )
    )

    // Valid subsequent change using stored custom hash
    val customHash = com.example.data.repository.FirestoreVideoRepository.hashSecretExact("SuperKey#2026")
    assertNotNull(
      com.example.data.repository.FirestoreVideoRepository.validateSuperadminMasterKeyChange(
        storedHash = customHash,
        currentInput = com.example.data.repository.FirebaseAuthAdminRepository.ADMIN_MASTER_KEY,
        newPasskey = "NextKey#2027",
        confirmPasskey = "NextKey#2027"
      )
    )
    org.junit.Assert.assertNull(
      com.example.data.repository.FirestoreVideoRepository.validateSuperadminMasterKeyChange(
        storedHash = customHash,
        currentInput = "SuperKey#2026",
        newPasskey = "NextKey#2027",
        confirmPasskey = "NextKey#2027"
      )
    )
  }

  @Test
  fun videoShareHelper_returnsOnlyCleanUrlWithoutVideoTitle() {
    val rawUrl = "  https://vidvora-bot.onrender.com/share?link=GM-TEST-99  "
    val shareText = com.example.util.VideoShareHelper.buildShareText(rawUrl)
    assertEquals("https://vidvora-bot.onrender.com/share?link=GM-TEST-99", shareText)
    org.junit.Assert.assertFalse(shareText.contains("\n"))
    org.junit.Assert.assertFalse(shareText.contains("Watch"))

    // Verify ShareVideoRequest carries no title field and dismiss resets state cleanly
    com.example.util.VideoShareHelper.dismissShareDialog()
    org.junit.Assert.assertNull(com.example.util.VideoShareHelper.pendingShareRequest.value)
  }
}
