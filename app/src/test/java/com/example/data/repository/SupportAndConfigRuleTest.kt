package com.example.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.SupportChatStatus
import com.example.data.model.SupportSenderRole
import com.example.data.model.TelegramCategoryItem
import com.example.data.model.TelegramChannelCategory
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportAndConfigRuleTest : FirestoreEmulatorTestBase() {

  private fun createRepository(): FirestoreVideoRepository {
    val app = firestore.app
    val context = ApplicationProvider.getApplicationContext<Context>()
    return FirestoreVideoRepository(
      db = firestore,
      storage = FirebaseStorage.getInstance(app),
      functions = FirebaseFunctions.getInstance(app),
      appContext = context
    )
  }

  @Test
  fun updateTelegramConfig_validCategoryLinks_updatesAndStreamsConfig() = runBlocking {
    auth.signOut()
    val repo = createRepository()
    val categories = mapOf(
      TelegramChannelCategory.PAKISTANI_DRAMA.key to "t.me/pak_drama_test",
      TelegramChannelCategory.SOUTH_MOVIE.key to "https://t.me/south_movie_test",
      TelegramChannelCategory.ULLU_TV_SERIES.key to "@ullu_series_test",
      TelegramChannelCategory.BHOJPURI_MOVIE.key to "https://t.me/bhojpuri_test",
      TelegramChannelCategory.ADULT_CONTENT.key to "https://t.me/adult_test"
    )
    val customList = listOf(
      TelegramCategoryItem(
        id = "cat_bollywood_01",
        name = "Bollywood Movie",
        link = "@bollywood_hits"
      ),
      TelegramCategoryItem(
        id = "cat_webseries_02",
        name = "Web Series",
        link = "t.me/webseries_vip"
      )
    )

    val result = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.updateTelegramConfig("https://t.me/VidVoraMainTest", categories, customList)
    }
    assertTrue("Expected Telegram config write to succeed: ${result.exceptionOrNull()}", result.isSuccess)

    val observed = withTimeout(FLOW_TIMEOUT_MS) {
      repo.getTelegramConfigFlow().first {
        it.mainLink == "https://t.me/VidVoraMainTest" && it.customCategories.size == 2
      }
    }
    assertEquals("https://t.me/pak_drama_test", observed.categoryLinks[TelegramChannelCategory.PAKISTANI_DRAMA.key])
    assertEquals("https://t.me/ullu_series_test", observed.categoryLinks[TelegramChannelCategory.ULLU_TV_SERIES.key])
    assertEquals("https://t.me/adult_test", observed.categoryLinks[TelegramChannelCategory.ADULT_CONTENT.key])

    val allCategories = observed.allCategories()
    assertEquals(7, allCategories.size)
    assertEquals("Bollywood Movie", allCategories[5].name)
    assertEquals("https://t.me/bollywood_hits", allCategories[5].link)
    assertEquals("Web Series", allCategories[6].name)
    assertEquals("https://t.me/webseries_vip", allCategories[6].link)
  }

  @Test
  fun updateTelegramConfig_renameAndDeleteBuiltInCategories_updatesAndStreamsConfig() = runBlocking {
    auth.signOut()
    val repo = createRepository()
    val categories = mapOf(
      TelegramChannelCategory.PAKISTANI_DRAMA.key to "https://t.me/pak_renamed",
      TelegramChannelCategory.SOUTH_MOVIE.key to "https://t.me/south_hd",
      TelegramChannelCategory.ULLU_TV_SERIES.key to "",
      TelegramChannelCategory.BHOJPURI_MOVIE.key to "",
      TelegramChannelCategory.ADULT_CONTENT.key to "https://t.me/should_clear"
    )
    val categoryNames = mapOf(
      TelegramChannelCategory.PAKISTANI_DRAMA.key to "Pakistani Shows HD"
    )
    val deletedKeys = listOf(TelegramChannelCategory.ADULT_CONTENT.key)
    val customList = listOf(
      TelegramCategoryItem(
        id = "cat_kdrama_01",
        name = "K-Drama",
        link = "t.me/kdrama_hd"
      )
    )

    val result = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.updateTelegramConfig(
        mainLink = "https://t.me/VidVoraMainTest",
        categoryLinks = categories,
        customCategories = customList,
        categoryNames = categoryNames,
        deletedCategoryKeys = deletedKeys,
        creatorBotLink = "t.me/VidVoraUploadBot"
      )
    }
    assertTrue("Expected Telegram config rename/delete write to succeed: ${result.exceptionOrNull()}", result.isSuccess)

    val observed = withTimeout(FLOW_TIMEOUT_MS) {
      repo.getTelegramConfigFlow().first {
        it.deletedCategoryKeys.contains(TelegramChannelCategory.ADULT_CONTENT.key) &&
          it.categoryNames[TelegramChannelCategory.PAKISTANI_DRAMA.key] == "Pakistani Shows HD"
      }
    }
    assertEquals("https://t.me/VidVoraUploadBot", observed.creatorBotLink)
    val allCategories = observed.allCategories()
    assertEquals(5, allCategories.size)
    assertEquals("Pakistani Shows HD", allCategories[0].name)
    assertEquals("https://t.me/pak_renamed", allCategories[0].link)
    assertEquals("K-Drama", allCategories[4].name)
    assertEquals("https://t.me/kdrama_hd", allCategories[4].link)
    assertTrue(allCategories.none { it.id == TelegramChannelCategory.ADULT_CONTENT.key })
  }

  @Test
  fun updateTelegramConfig_afterDeletion_overwritesLegacyDocAndRefreshesFromServer() = runBlocking {
    auth.signOut()
    val repo = createRepository()

    // Simulate a legacy appConfig/general document written before new category keys existed
    withTimeout(DEFAULT_TIMEOUT_MS) {
      firestore.collection("appConfig").document("general").set(
        mapOf(
          "telegramLink" to "https://t.me/LegacyMain",
          "categoryLinks" to mapOf(
            TelegramChannelCategory.PAKISTANI_DRAMA.key to "https://t.me/pak_old",
            TelegramChannelCategory.SOUTH_MOVIE.key to "https://t.me/south_old"
          ),
          "customCategories" to listOf(
            mapOf("id" to "cat_legacy_1", "name" to "Legacy Custom", "link" to "https://t.me/legacy_custom")
          )
        )
      ).await()
    }

    // Now delete both a built-in category (SOUTH_MOVIE) and the custom category ("cat_legacy_1")
    val saveResult = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.updateTelegramConfig(
        mainLink = "https://t.me/VidVoraMainFresh",
        categoryLinks = mapOf(
          TelegramChannelCategory.PAKISTANI_DRAMA.key to "https://t.me/pak_fresh"
        ),
        customCategories = emptyList(),
        categoryNames = emptyMap(),
        deletedCategoryKeys = listOf(TelegramChannelCategory.SOUTH_MOVIE.key),
        creatorBotLink = ""
      )
    }
    assertTrue("Expected overwrite save after delete to succeed: ${saveResult.exceptionOrNull()}", saveResult.isSuccess)

    val serverConfig = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.fetchTelegramConfigFromServer()
    }
    assertNotNull("Expected server config fetch to succeed", serverConfig)
    assertEquals("https://t.me/VidVoraMainFresh", serverConfig?.mainLink)
    assertTrue(serverConfig!!.deletedCategoryKeys.contains(TelegramChannelCategory.SOUTH_MOVIE.key))
    assertTrue(serverConfig.customCategories.isEmpty())

    val remaining = serverConfig.allCategories()
    assertEquals(4, remaining.size)
    assertTrue(remaining.none { it.id == TelegramChannelCategory.SOUTH_MOVIE.key })
    assertTrue(remaining.none { it.id == "cat_legacy_1" })

    val streamed = withTimeout(FLOW_TIMEOUT_MS) {
      repo.getTelegramConfigFlow().first {
        it.mainLink == "https://t.me/VidVoraMainFresh" &&
          it.deletedCategoryKeys.contains(TelegramChannelCategory.SOUTH_MOVIE.key)
      }
    }
    assertEquals(4, streamed.allCategories().size)
  }

  @Test
  fun supportChat_userStartsAdminRepliesAndUpdatesUnreadCounts() = runBlocking {
    auth.signOut()
    val repo = createRepository()
    val chatId = "sup_" + UUID.randomUUID().toString().replace("-", "").take(12)

    // 1. User sends initial message
    val userSend = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.sendSupportMessage(
        chatId = chatId,
        senderRole = SupportSenderRole.USER,
        senderName = "Imran",
        text = "South movie link kaam nahi kar raha",
        deviceLabelOverride = "Samsung Galaxy"
      )
    }
    assertTrue("Expected user support message to succeed: ${userSend.exceptionOrNull()}", userSend.isSuccess)

    val chatAfterUser = withTimeout(FLOW_TIMEOUT_MS) {
      repo.observeSupportChat(chatId).first { it != null && it.unreadByAdminCount == 1L }
    }
    assertNotNull(chatAfterUser)
    assertEquals(SupportSenderRole.USER, chatAfterUser?.lastSenderRole)
    assertEquals(0L, chatAfterUser?.unreadByUserCount)

    // 2. Admin replies to the user's thread
    val adminReply = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.sendSupportMessage(
        chatId = chatId,
        senderRole = SupportSenderRole.ADMIN,
        senderName = "Custom Email Or Name",
        text = "Link update kar diya gaya hai, dubara check karein."
      )
    }
    assertTrue("Expected admin reply to succeed: ${adminReply.exceptionOrNull()}", adminReply.isSuccess)

    val messages = withTimeout(FLOW_TIMEOUT_MS) {
      repo.observeSupportMessages(chatId).first { it.size == 2 }
    }
    assertEquals(SupportSenderRole.USER, messages[0].senderRole)
    assertEquals(SupportSenderRole.ADMIN, messages[1].senderRole)
    assertEquals(FirestoreVideoRepository.SUPPORT_TEAM_NAME, messages[1].senderName)

    val chatAfterAdmin = withTimeout(FLOW_TIMEOUT_MS) {
      repo.observeSupportChat(chatId).first { it != null && it.lastSenderRole == SupportSenderRole.ADMIN }
    }
    assertEquals(0L, chatAfterAdmin?.unreadByAdminCount)
    assertEquals(1L, chatAfterAdmin?.unreadByUserCount)

    // 3. Mark read & toggle status to RESOLVED
    withTimeout(DEFAULT_TIMEOUT_MS) { repo.markSupportChatReadByUser(chatId) }
    withTimeout(DEFAULT_TIMEOUT_MS) { repo.updateSupportChatStatus(chatId, SupportChatStatus.RESOLVED) }

    val resolvedChat = withTimeout(FLOW_TIMEOUT_MS) {
      repo.observeSupportChat(chatId).first { it != null && it.status == SupportChatStatus.RESOLVED }
    }
    assertEquals(0L, resolvedChat?.unreadByUserCount)
    assertEquals(SupportChatStatus.RESOLVED, resolvedChat?.status)
  }

  @Test
  fun creatorSupportChat_creatorAsksTeamAndReceivesAdminReply() = runBlocking {
    auth.signOut()
    val repo = createRepository()
    val memberId = "mem_" + UUID.randomUUID().toString().replace("-", "").take(10)
    val creatorChatId = FirestoreVideoRepository.buildCreatorSupportChatId(memberId)

    // Creator sends message asking VidVora Support Team for help
    val creatorSend = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.sendSupportMessage(
        chatId = creatorChatId,
        senderRole = SupportSenderRole.USER,
        senderName = "Rohan (Creator)",
        text = "Mera UPI withdrawal kab process hoga?",
        deviceLabelOverride = "Creator • rohan@vidvora.com"
      )
    }
    assertTrue("Expected Creator support message to succeed: ${creatorSend.exceptionOrNull()}", creatorSend.isSuccess)

    val chatAfterCreator = withTimeout(FLOW_TIMEOUT_MS) {
      repo.observeSupportChat(creatorChatId).first { it != null && it.unreadByAdminCount == 1L }
    }
    assertEquals("Rohan (Creator)", chatAfterCreator?.userName)
    assertEquals("Creator • rohan@vidvora.com", chatAfterCreator?.deviceLabel)
    assertEquals(0L, chatAfterCreator?.unreadByUserCount)

    // Admin replies back as VidVora Support Team
    val adminReply = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.sendSupportMessage(
        chatId = creatorChatId,
        senderRole = SupportSenderRole.ADMIN,
        senderName = "Ignored Name",
        text = "Aapka payout aaj shaam tak clear ho jayega. https://t.me/VidVoraApp"
      )
    }
    assertTrue("Expected Admin reply to Creator chat to succeed", adminReply.isSuccess)

    val chatAfterReply = withTimeout(FLOW_TIMEOUT_MS) {
      repo.observeSupportChat(creatorChatId).first { it != null && it.unreadByUserCount == 1L }
    }
    assertEquals(SupportSenderRole.ADMIN, chatAfterReply?.lastSenderRole)

    withTimeout(DEFAULT_TIMEOUT_MS) { repo.markSupportChatReadByUser(creatorChatId) }
    val chatAfterCreatorRead = withTimeout(FLOW_TIMEOUT_MS) {
      repo.observeSupportChat(creatorChatId).first { it != null && it.unreadByUserCount == 0L }
    }
    assertEquals(0L, chatAfterCreatorRead?.unreadByUserCount)
  }

  @Test
  fun supportChat_rejectsUnknownCategoryOrSpoofedAdminInitialChat() = runBlocking {
    auth.signOut()
    val badChatId = "sup_" + UUID.randomUUID().toString().replace("-", "").take(12)

    var denied = false
    try {
      withTimeout(DEFAULT_TIMEOUT_MS) {
        firestore.collection("supportChats").document(badChatId)
          .set(
            mapOf(
              "chatId" to badChatId,
              "userName" to "Spoof",
              "deviceLabel" to "Pixel",
              "lastMessage" to "Spoof admin start",
              "lastSenderRole" to "admin",
              "lastMessageAt" to com.google.firebase.Timestamp.now(),
              "createdAt" to com.google.firebase.Timestamp.now(),
              "unreadByAdminCount" to 0L,
              "unreadByUserCount" to 1L,
              "status" to "OPEN"
            )
          )
          .await()
      }
    } catch (e: Exception) {
      val fsEx = e.unwrapFirestoreException()
      if (fsEx?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
        denied = true
      }
    }
    assertTrue("Expected spoofed initial admin chat creation to be rejected by Firestore rules", denied)
  }

  @Test
  fun member_addAndDelete_succeedsAndRemovesDocument() = runBlocking {
    auth.signOut()
    val repo = createRepository()
    val uniqueKey = "delkey_" + UUID.randomUUID().toString().replace("-", "").take(8)

    val added = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.addMember(
        name = "Temp Creator",
        emailOrPhone = "temp@creator.com",
        passkey = uniqueKey
      )
    }
    assertTrue("Expected addMember to succeed: ${added.exceptionOrNull()}", added.isSuccess)
    val memberId = added.getOrThrow().memberId

    val deleted = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.deleteMember(memberId)
    }
    assertTrue("Expected deleteMember to succeed: ${deleted.exceptionOrNull()}", deleted.isSuccess)

    val docAfter = withTimeout(DEFAULT_TIMEOUT_MS) {
      firestore.collection("members").document(memberId).get().await()
    }
    org.junit.Assert.assertFalse("Expected member document to be deleted", docAfter.exists())
  }

  @Test
  fun member_deleteByMismatchedDocId_deletesMatchingMemberDocument() = runBlocking {
    auth.signOut()
    val repo = createRepository()
    val randomDocId = "auto_doc_" + UUID.randomUUID().toString().replace("-", "").take(8)
    val logicalMemberId = "mem_field_" + UUID.randomUUID().toString().replace("-", "").take(8)

    withTimeout(DEFAULT_TIMEOUT_MS) {
      firestore.collection("members").document(randomDocId).set(
        mapOf(
          "memberId" to logicalMemberId,
          "name" to "Legacy Creator",
          "emailOrPhone" to "legacy@creator.com",
          "passkey" to "legkey_" + UUID.randomUUID().toString().replace("-", "").take(6),
          "role" to "member",
          "isActive" to true,
          "createdAt" to com.google.firebase.Timestamp.now(),
          "totalEarning" to 0.0
        )
      ).await()
    }

    val deleted = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.deleteMember(logicalMemberId)
    }
    assertTrue("Expected deleteMember by memberId field to succeed", deleted.isSuccess)

    val docAfter = withTimeout(DEFAULT_TIMEOUT_MS) {
      firestore.collection("members").document(randomDocId).get().await()
    }
    org.junit.Assert.assertFalse("Expected mismatched-ID member document to be deleted", docAfter.exists())
  }

  @Test
  fun creatorEvent_createEditToggleAndDelete_streamsLive() = runBlocking {
    auth.signOut()
    val repo = createRepository()

    val created = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.createCreatorEvent(
        title = "Mega Creator Gift",
        description = "Upload 5 reels and win a surprise hamper!",
        giftLabel = "Surprise Hamper",
        imageUrlInput = "https://example.com/hamper.jpg",
        isActive = true,
        createdBy = "Superadmin"
      )
    }
    assertTrue("Expected createCreatorEvent to succeed: ${created.exceptionOrNull()}", created.isSuccess)
    val eventId = created.getOrThrow().eventId

    val liveAfterCreate = withTimeout(FLOW_TIMEOUT_MS) {
      repo.getCreatorEventsFlow().first { list -> list.any { it.eventId == eventId } }
    }.first { it.eventId == eventId }
    assertEquals("Mega Creator Gift", liveAfterCreate.title)
    assertEquals("Surprise Hamper", liveAfterCreate.giftLabel)
    assertTrue(liveAfterCreate.isActive)

    val updated = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.updateCreatorEvent(
        eventId = eventId,
        title = "Mega Creator Gift - Updated",
        description = "Upload 3 reels and win ₹2,500 + hamper!",
        giftLabel = "₹2,500 + Hamper",
        imageUrlInput = "data:image/jpeg;base64,/9j/4AAQSkZJRg==",
        isActive = false
      )
    }
    assertTrue("Expected updateCreatorEvent to succeed: ${updated.exceptionOrNull()}", updated.isSuccess)

    val liveAfterEdit = withTimeout(FLOW_TIMEOUT_MS) {
      repo.getCreatorEventsFlow().first { list ->
        list.any { it.eventId == eventId && it.title == "Mega Creator Gift - Updated" && !it.isActive }
      }
    }.first { it.eventId == eventId }
    assertEquals("₹2,500 + Hamper", liveAfterEdit.giftLabel)
    assertEquals("Upload 3 reels and win ₹2,500 + hamper!", liveAfterEdit.description)
    org.junit.Assert.assertFalse(liveAfterEdit.isActive)

    val deleted = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.deleteCreatorEvent(eventId)
    }
    assertTrue("Expected deleteCreatorEvent to succeed: ${deleted.exceptionOrNull()}", deleted.isSuccess)

    val docAfter = withTimeout(DEFAULT_TIMEOUT_MS) {
      firestore.collection("creatorEvents").document(eventId).get().await()
    }
    org.junit.Assert.assertFalse("Expected deleted creatorEvent to be removed", docAfter.exists())
  }

  @Test
  fun member_creditViewsAndAutoCalculateEarning_updatesAtomicallyAndStreamsLive() = runBlocking {
    auth.signOut()
    val repo = createRepository()
    val uniqueKey = "viewkey_" + UUID.randomUUID().toString().replace("-", "").take(8)
    val uniqueEmail = "views_${uniqueKey}@creator.com"

    val added = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.addMember(
        name = "Views Creator",
        emailOrPhone = uniqueEmail,
        passkey = uniqueKey
      )
    }
    assertTrue("Expected addMember to succeed: ${added.exceptionOrNull()}", added.isSuccess)
    val memberId = added.getOrThrow().memberId
    assertEquals(0L, added.getOrThrow().creditedViews)
    assertEquals(190.0, added.getOrThrow().viewRatePer1000, 0.001)
    assertEquals(0.0, added.getOrThrow().totalEarning, 0.001)

    // 1. Add 1,000 views @ ₹190/1k -> ₹190
    val firstCredit = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.creditCreatorViewsAndEarning(
        memberId = memberId,
        viewsInput = 1000L,
        ratePer1000 = 190.0,
        isAddition = true
      )
    }
    assertTrue("Expected first view credit to succeed: ${firstCredit.exceptionOrNull()}", firstCredit.isSuccess)
    assertEquals(1000L, firstCredit.getOrThrow().creditedViews)
    assertEquals(190.0, firstCredit.getOrThrow().totalEarning, 0.001)

    // 2. Add another 500 views @ ₹190/1k -> +₹95 (total 1,500 views & ₹285)
    val secondCredit = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.creditCreatorViewsAndEarning(
        memberId = memberId,
        viewsInput = 500L,
        ratePer1000 = 190.0,
        isAddition = true
      )
    }
    assertTrue("Expected second view credit to succeed: ${secondCredit.exceptionOrNull()}", secondCredit.isSuccess)
    assertEquals(1500L, secondCredit.getOrThrow().creditedViews)
    assertEquals(285.0, secondCredit.getOrThrow().totalEarning, 0.001)

    // Verify live stream emits updated creditedViews & totalEarning
    val liveMember = withTimeout(FLOW_TIMEOUT_MS) {
      repo.getAllMembersFlow().first { list ->
        list.any { it.memberId == memberId && it.creditedViews == 1500L && it.totalEarning == 285.0 }
      }
    }.first { it.memberId == memberId }
    assertEquals(1500L, liveMember.creditedViews)
    assertEquals(285.0, liveMember.totalEarning, 0.001)

    // 3. Set total views to 2,000 @ ₹100/1k -> ₹200
    val setTotalCredit = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.creditCreatorViewsAndEarning(
        memberId = memberId,
        viewsInput = 2000L,
        ratePer1000 = 100.0,
        isAddition = false
      )
    }
    assertTrue("Expected set-total view credit to succeed", setTotalCredit.isSuccess)
    assertEquals(2000L, setTotalCredit.getOrThrow().creditedViews)
    assertEquals(100.0, setTotalCredit.getOrThrow().viewRatePer1000, 0.001)
    assertEquals(200.0, setTotalCredit.getOrThrow().totalEarning, 0.001)
  }

  @Test
  fun getAllVideosFlow_returnsNewlyUploadedVideosTopDownLineByLine() = runBlocking {
    auth.signOut()
    val repo = createRepository()
    val tag = UUID.randomUUID().toString().replace("-", "").take(6)
    val creatorId = "mem_sort_$tag"

    // Use IDs that would sort completely backwards alphabetically ("000" -> "999" -> "555")
    // so Firestore's default doc-ID ordering would put newest in the middle instead of on top!
    val firstOldestId = "vid_z999_${tag}_1"
    val secondMiddleId = "vid_a000_${tag}_2"
    val thirdNewestId = "vid_m555_${tag}_3"

    suspend fun insertVideo(id: String, title: String, ts: com.google.firebase.Timestamp) {
      firestore.collection("videos").document(id).set(
        mapOf(
          "videoId" to id,
          "title" to title,
          "description" to "Order test",
          "storagePath" to "https://example.com/$id.mp4",
          "createdAt" to ts,
          "isActive" to true,
          "totalViews" to 0L,
          "uploadedBy" to creatorId,
          "uploaderName" to "Order Creator"
        )
      ).await()
    }

    withTimeout(DEFAULT_TIMEOUT_MS) {
      insertVideo(firstOldestId, "Video 1 Oldest", com.google.firebase.Timestamp(1_760_000_010L, 0))
      insertVideo(secondMiddleId, "Video 2 Middle", com.google.firebase.Timestamp(1_760_000_020L, 0))
      insertVideo(thirdNewestId, "Video 3 Newest", com.google.firebase.Timestamp(1_760_000_030L, 0))
    }

    val creatorVideos = withTimeout(FLOW_TIMEOUT_MS) {
      repo.getAllVideosFlow().first { list ->
        list.count { it.uploadedBy == creatorId } == 3
      }
    }.filter { it.uploadedBy == creatorId }

    assertEquals(3, creatorVideos.size)
    assertEquals(thirdNewestId, creatorVideos[0].videoId)
    assertEquals(secondMiddleId, creatorVideos[1].videoId)
    assertEquals(firstOldestId, creatorVideos[2].videoId)
  }

  @Test
  fun videoThumbnail_encodesDataUrlDecodesCoilModelAndCachesFrames() {
    val sampleBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x11, 0x22)
    val dataUrl = FirestoreVideoRepository.encodeJpegBytesAsDataUrl(sampleBytes)
    assertTrue(dataUrl.startsWith("data:image/jpeg;base64,"))

    val decoded = com.example.notifications.AppNotificationHelper.decodeImageModel(dataUrl)
    assertTrue(decoded is ByteArray)
    assertTrue((decoded as ByteArray).contentEquals(sampleBytes))

    assertEquals(
      "https://example.com/thumb.jpg",
      com.example.notifications.AppNotificationHelper.decodeImageModel("  https://example.com/thumb.jpg  ")
    )

    val bitmap = android.graphics.Bitmap.createBitmap(16, 9, android.graphics.Bitmap.Config.ARGB_8888)
    com.example.ui.screens.admin.VideoThumbnailCache.put("vid_thumb_cache_1", bitmap)
    assertNotNull(com.example.ui.screens.admin.VideoThumbnailCache.get("vid_thumb_cache_1"))

    com.example.ui.screens.admin.VideoThumbnailCache.markFailed("vid_thumb_cache_fail")
    assertTrue(com.example.ui.screens.admin.VideoThumbnailCache.isMarkedFailed("vid_thumb_cache_fail"))
  }

  @Test
  fun changeCreatorEmail_inPlace_preservesAllDataAndSyncsLogin() = runBlocking {
    auth.signOut()
    val repo = createRepository()
    val authRepo = FirebaseAuthAdminRepository(
      auth = auth,
      db = firestore,
      appContext = ApplicationProvider.getApplicationContext()
    )
    val tag = UUID.randomUUID().toString().replace("-", "").take(6)
    val oldEmail = "rohan_$tag@gmail.com"
    val newEmail = "rohan.new_$tag@gmail.com"
    val passkey = "key_$tag"

    val member = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.addMember("Rohan", oldEmail, passkey)
    }.getOrThrow()

    // Credit 3,000 views = ₹570 earning, add 1 video, 1 withdrawal, and 1 support message
    val credited = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.creditCreatorViewsAndEarning(member.memberId, 3000L, 190.0, isAddition = true)
    }.getOrThrow()

    val videoId = "vid_email_$tag"
    withTimeout(DEFAULT_TIMEOUT_MS) {
      firestore.collection("videos").document(videoId).set(
        mapOf(
          "videoId" to videoId,
          "title" to "Old Email Upload",
          "description" to "Test video",
          "storagePath" to "https://example.com/$videoId.mp4",
          "createdAt" to com.google.firebase.Timestamp.now(),
          "isActive" to true,
          "totalViews" to 120L,
          "uploadedBy" to member.memberId,
          "uploaderName" to member.name
        )
      ).await()
    }

    val withdrawal = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.submitWithdrawalRequest(credited, 500.0, "rohan@ybl")
    }.getOrThrow()

    val chatId = FirestoreVideoRepository.buildCreatorSupportChatId(member.memberId)
    withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.sendSupportMessage(
        chatId = chatId,
        senderRole = SupportSenderRole.USER,
        senderName = "Rohan (Creator)",
        text = "Please change my email",
        deviceLabelOverride = "Creator • $oldEmail"
      )
    }

    val changeResult = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.changeCreatorEmail(member.memberId, newEmail)
    }
    assertTrue("Expected in-place email change to succeed: ${changeResult.exceptionOrNull()}", changeResult.isSuccess)
    val summary = changeResult.getOrThrow()
    org.junit.Assert.assertFalse(summary.mergedExistingAccount)
    assertEquals(newEmail, summary.member.emailOrPhone)
    assertEquals(3000L, summary.member.creditedViews)
    assertEquals(570.0, summary.member.totalEarning, 0.001)
    assertEquals(1, summary.migratedVideosCount)
    assertEquals(1, summary.syncedWithdrawalsCount)

    // Withdrawal email & support chat deviceLabel synced
    val updatedReqSnap = withTimeout(DEFAULT_TIMEOUT_MS) {
      firestore.collection("withdrawalRequests").document(withdrawal.requestId).get().await()
    }
    assertEquals(newEmail, updatedReqSnap.getString("creatorEmail"))
    assertEquals(member.memberId, updatedReqSnap.getString("memberId"))

    val updatedChatSnap = withTimeout(DEFAULT_TIMEOUT_MS) {
      firestore.collection("supportChats").document(chatId).get().await()
    }
    assertTrue((updatedChatSnap.getString("deviceLabel") ?: "").contains(newEmail))

    // Old email login fails, new email login works and preserves all earnings/data
    org.junit.Assert.assertFalse(authRepo.authenticateMemberOrAdmin(oldEmail, passkey, repo))
    assertTrue(authRepo.authenticateMemberOrAdmin(newEmail, passkey, repo))
    assertEquals(member.memberId, authRepo.currentMember.value?.memberId)
    assertEquals(570.0, authRepo.currentMember.value?.totalEarning ?: 0.0, 0.001)
  }

  @Test
  fun changeCreatorEmail_existingTarget_transfersAllVideosEarningsAndWithdrawals() = runBlocking {
    auth.signOut()
    val repo = createRepository()
    val authRepo = FirebaseAuthAdminRepository(
      auth = auth,
      db = firestore,
      appContext = ApplicationProvider.getApplicationContext()
    )
    val tag = UUID.randomUUID().toString().replace("-", "").take(6)
    val oldEmail = "old_acc_$tag@gmail.com"
    val newEmail = "new_acc_$tag@gmail.com"
    val oldKey = "oldk_$tag"
    val newKey = "newk_$tag"

    val oldMember = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.addMember("Creator Old", oldEmail, oldKey)
    }.getOrThrow()
    val targetMember = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.addMember("Creator New", newEmail, newKey)
    }.getOrThrow()

    val oldCredited = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.creditCreatorViewsAndEarning(oldMember.memberId, 3000L, 190.0, isAddition = true)
    }.getOrThrow()
    withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.creditCreatorViewsAndEarning(targetMember.memberId, 1000L, 190.0, isAddition = true)
    }.getOrThrow()

    val oldVideoId = "vid_m_old_$tag"
    val newVideoId = "vid_m_new_$tag"
    suspend fun insertVid(vidId: String, uploader: String, uploaderName: String) {
      firestore.collection("videos").document(vidId).set(
        mapOf(
          "videoId" to vidId,
          "title" to "Video $vidId",
          "description" to "Migration test",
          "storagePath" to "https://example.com/$vidId.mp4",
          "createdAt" to com.google.firebase.Timestamp.now(),
          "isActive" to true,
          "totalViews" to 50L,
          "uploadedBy" to uploader,
          "uploaderName" to uploaderName
        )
      ).await()
    }
    withTimeout(DEFAULT_TIMEOUT_MS) {
      insertVid(oldVideoId, oldMember.memberId, oldMember.name)
      insertVid(newVideoId, targetMember.memberId, targetMember.name)
    }

    val oldWithdrawal = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.submitWithdrawalRequest(oldCredited, 500.0, "creator@ybl")
    }.getOrThrow()

    val mergeResult = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.changeCreatorEmail(oldMember.memberId, newEmail)
    }
    assertTrue("Expected cross-account email merge to succeed: ${mergeResult.exceptionOrNull()}", mergeResult.isSuccess)
    val summary = mergeResult.getOrThrow()
    assertTrue(summary.mergedExistingAccount)
    assertEquals(targetMember.memberId, summary.member.memberId)
    assertEquals(4000L, summary.member.creditedViews)
    assertEquals(760.0, summary.member.totalEarning, 0.001)
    assertEquals(1, summary.migratedVideosCount)

    val oldVideoSnap = withTimeout(DEFAULT_TIMEOUT_MS) {
      firestore.collection("videos").document(oldVideoId).get().await()
    }
    assertEquals(targetMember.memberId, oldVideoSnap.getString("uploadedBy"))

    val migratedReqSnap = withTimeout(DEFAULT_TIMEOUT_MS) {
      firestore.collection("withdrawalRequests").document(oldWithdrawal.requestId).get().await()
    }
    assertEquals(targetMember.memberId, migratedReqSnap.getString("memberId"))
    assertEquals(newEmail, migratedReqSnap.getString("creatorEmail"))

    val oldMemberAfter = withTimeout(DEFAULT_TIMEOUT_MS) {
      firestore.collection("members").document(oldMember.memberId).get().await()
    }
    org.junit.Assert.assertFalse("Expected retired old email doc to be deleted", oldMemberAfter.exists())

    org.junit.Assert.assertFalse(authRepo.authenticateMemberOrAdmin(oldEmail, oldKey, repo))
    assertTrue(authRepo.authenticateMemberOrAdmin(newEmail, newKey, repo))
    assertEquals(760.0, authRepo.currentMember.value?.totalEarning ?: 0.0, 0.001)
  }

  @Test
  fun superadminMasterKey_changePersistsHashRejectsOldKeyAndPreventsMemberCollision() = runBlocking {
    auth.signOut()
    val repo = createRepository()
    val authRepo = FirebaseAuthAdminRepository(
      auth = auth,
      db = firestore,
      appContext = ApplicationProvider.getApplicationContext()
    )
    val tag = UUID.randomUUID().toString().replace("-", "").take(6)
    val memberPass = "memkey_$tag"
    val member = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.addMember("Key Guard Member", "guard_$tag@vidvora.com", memberPass)
    }.getOrThrow()
    assertNotNull(member.memberId)

    // Changing to an existing member's passkey must be rejected
    val collisionResult = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.changeSuperadminMasterKey(
        currentPasskey = FirebaseAuthAdminRepository.ADMIN_MASTER_KEY,
        newPasskey = memberPass,
        confirmPasskey = memberPass
      )
    }
    assertTrue("Expected collision with existing member passkey to fail", collisionResult.isFailure)

    // Valid Superadmin Master Key change
    val newAdminKey = "AdminCustom#$tag"
    val changeResult = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.changeSuperadminMasterKey(
        currentPasskey = FirebaseAuthAdminRepository.ADMIN_MASTER_KEY,
        newPasskey = newAdminKey,
        confirmPasskey = newAdminKey
      )
    }
    assertTrue("Expected Superadmin Master Key change to succeed: ${changeResult.exceptionOrNull()}", changeResult.isSuccess)
    val storedHash = withTimeout(DEFAULT_TIMEOUT_MS) { repo.getSuperadminMasterKeyHash() }
    assertEquals(FirestoreVideoRepository.hashSecretExact(newAdminKey), storedHash)

    // Old default key must now fail, while new custom key must succeed
    org.junit.Assert.assertFalse(
      authRepo.authenticateMemberOrAdmin(
        FirebaseAuthAdminRepository.ADMIN_EMAIL,
        FirebaseAuthAdminRepository.ADMIN_MASTER_KEY,
        repo
      )
    )
    assertTrue(
      authRepo.authenticateMemberOrAdmin(
        FirebaseAuthAdminRepository.ADMIN_EMAIL,
        newAdminKey,
        repo
      )
    )
    assertEquals("admin (dev)", authRepo.adminRole.value)

    // Subsequent change using new key works cleanly
    val secondKey = "AdminRotated#$tag"
    val rotateResult = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.changeSuperadminMasterKey(
        currentPasskey = newAdminKey,
        newPasskey = secondKey,
        confirmPasskey = secondKey
      )
    }
    assertTrue("Expected second Superadmin key rotation to succeed: ${rotateResult.exceptionOrNull()}", rotateResult.isSuccess)
    assertTrue(
      authRepo.authenticateMemberOrAdmin(
        FirebaseAuthAdminRepository.ADMIN_EMAIL,
        secondKey,
        repo
      )
    )
  }

  private companion object {
    const val DEFAULT_TIMEOUT_MS = 5000L
    const val FLOW_TIMEOUT_MS = 3000L
  }
}
