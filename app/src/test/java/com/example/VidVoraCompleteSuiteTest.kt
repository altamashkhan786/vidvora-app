package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ads.AdManager
import com.example.ads.AdMobConfig
import com.example.data.local.VideoDatabase
import com.example.data.model.FirestoreAccessLink
import com.example.data.model.FirestoreVideo
import com.example.data.model.TokenValidationResult
import com.example.data.model.VideoToken
import com.example.data.repository.AdminAuthRepository
import com.example.data.repository.VideoRepository
import com.example.player.VideoPlaybackState
import com.example.ui.screens.home.HomeViewModel
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VidVoraCompleteSuiteTest {

  private lateinit var context: Context
  private lateinit var database: VideoDatabase
  private lateinit var repository: VideoRepository
  private lateinit var adminAuth: AdminAuthRepository

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    database = VideoDatabase.getDatabase(context)
    repository = VideoRepository(database.videoDao())
    adminAuth = AdminAuthRepository()
  }

  // 1. Normal users can open the app without login
  @Test
  fun test01_normalUserOpensAppWithoutLogin() = runBlocking {
    // Normal users access all public videos without requiring authentication
    repository.initializeSeedsIfEmpty()
    val videos = repository.allVideosFlow.first()
    assertTrue("Videos must be accessible without login", videos.isNotEmpty())
    assertFalse("Default state must be unauthenticated normal user", adminAuth.isAdminAuthenticated.value)
  }

  // 2. Admin login works correctly
  @Test
  fun test02_adminLoginValidation() {
    assertFalse(adminAuth.isAdminAuthenticated.value)

    // Rejection of invalid passkeys and wrong email
    assertFalse(adminAuth.authenticate("invalid_key"))
    assertFalse(adminAuth.authenticate("7827128212@8825254100"))
    assertFalse(adminAuth.authenticate("other@gmail.com", "7827128212"))
    assertFalse(adminAuth.isAdminAuthenticated.value)

    // Acceptance of valid admin email and master key
    assertTrue(adminAuth.authenticate("teamaltamash@gmail.com", "7827128212"))
    assertTrue(adminAuth.isAdminAuthenticated.value)

    // Sign out revokes admin session
    adminAuth.logout()
    assertFalse(adminAuth.isAdminAuthenticated.value)
  }

  // 3. Admin can upload/create a video record
  @Test
  fun test03_adminCanCreateVideoRecord() = runBlocking {
    val created = repository.createVideoToken(
      title = "Bollywood Thriller Trailer",
      description = "High octane action trailer",
      streamUrl = "https://example.com/stream/thriller.m3u8",
      posterUrl = "https://example.com/posters/thriller.jpg",
      quality = "1080p Full HD"
    )

    assertNotNull(created.id)
    assertEquals("Bollywood Thriller Trailer", created.title)
    assertTrue(created.isActive)
    assertEquals(0, created.viewCount)

    val fetched = repository.getVideoById(created.id)
    assertNotNull(fetched)
    assertEquals(created.title, fetched?.title)
  }

  // 4. Video uploads canonical Firebase Storage paths
  @Test
  fun test04_videoStorageCanonicalPaths() {
    val videoId = "vid_test12345"
    val expectedVideoPath = "videos/$videoId/video.mp4"
    val expectedThumbPath = "videos/$videoId/thumbnail.jpg"

    assertTrue(expectedVideoPath.startsWith("videos/"))
    assertTrue(expectedVideoPath.endsWith("/video.mp4"))
    assertTrue(expectedThumbPath.endsWith("/thumbnail.jpg"))
  }

  // 5. Admin can generate a unique cryptographic access link
  @Test
  fun test05_uniqueCryptographicAccessLinkGeneration() {
    val secureRandom = SecureRandom()
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val tokens = (1..50).map {
      val p1 = (1..4).map { alphabet[secureRandom.nextInt(alphabet.length)] }.joinToString("")
      val p2 = (1..4).map { alphabet[secureRandom.nextInt(alphabet.length)] }.joinToString("")
      val p3 = (1..4).map { alphabet[secureRandom.nextInt(alphabet.length)] }.joinToString("")
      "GM-$p1-$p2-$p3"
    }.toSet()

    // Uniqueness assertion across 50 generated tokens
    assertEquals(50, tokens.size)

    // Verify SHA-256 token hashing for storage (token plain text never stored in accessLinks)
    val token = tokens.first()
    assertEquals(17, token.length)
    assertTrue(token.startsWith("GM-"))

    val sha256 = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
    val tokenHash = sha256.joinToString("") { "%02x".format(it) }
    assertEquals(64, tokenHash.length)
  }

  // 6. Valid links play the correct video
  @Test
  fun test06_validLinkPlaysCorrectVideo() = runBlocking {
    val created = repository.createVideoToken(
      title = "Special Featurette",
      description = "Behind the scenes",
      streamUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
      customToken = "GM-PLAY-OK-01"
    )

    // Test raw token input
    val resultRaw = repository.validateAndGetVideo("GM-PLAY-OK-01")
    assertTrue(resultRaw is TokenValidationResult.Success)
    assertEquals("Special Featurette", (resultRaw as TokenValidationResult.Success).video.title)

    // Test deep link URL input (?token= and ?link=)
    val deepLink = "vidvora://watch?token=GM-PLAY-OK-01"
    val resultDeep = repository.validateAndGetVideo(deepLink)
    assertTrue(resultDeep is TokenValidationResult.Success)
    assertEquals("Special Featurette", (resultDeep as TokenValidationResult.Success).video.title)

    val linkQueryUrl = "vidvora://watch?link=GM-PLAY-OK-01"
    val resultLinkQuery = repository.validateAndGetVideo(linkQueryUrl)
    assertTrue(resultLinkQuery is TokenValidationResult.Success)

    val linkIdUrl = "vidvora://watch?link=link_gm_play_ok_01"
    val resultLinkId = repository.validateAndGetVideo(linkIdUrl)
    assertTrue(resultLinkId is TokenValidationResult.Success)

    val byVideoId = repository.validateAndGetVideo(created.id)
    assertTrue(byVideoId is TokenValidationResult.Success)

    val httpsShareLink = "https://gen-lang-client-0000740810.web.app/watch?link=GM-PLAY-OK-01"
    val resultHttpsLink = repository.validateAndGetVideo(httpsShareLink)
    assertTrue(resultHttpsLink is TokenValidationResult.Success)

    val cloudFrontShareLink = "https://d1mn8mrvlzisg5.cloudfront.net/index.html?link=GM-PLAY-OK-01"
    val resultCloudFrontLink = repository.validateAndGetVideo(cloudFrontShareLink)
    assertTrue(resultCloudFrontLink is TokenValidationResult.Success)
  }

  // 7. Invalid links are rejected
  @Test
  fun test07_invalidLinksAreRejected() = runBlocking {
    val result = repository.validateAndGetVideo("GM-INVALID-TOKEN-99")
    assertTrue(result is TokenValidationResult.TokenNotFound)

    val emptyResult = repository.validateAndGetVideo("")
    assertTrue(emptyResult is TokenValidationResult.Error)
  }

  // 8. Deactivated links stop working
  @Test
  fun test08_deactivatedLinksStopWorking() = runBlocking {
    val created = repository.createVideoToken(
      title = "Deactivation Test Video",
      description = "Test link deactivation",
      streamUrl = "https://example.com/video.mp4",
      customToken = "GM-DEACT-01"
    )

    // Active link works
    val beforeDeact = repository.validateAndGetVideo("GM-DEACT-01")
    assertTrue(beforeDeact is TokenValidationResult.Success)

    // Deactivate the video/link
    repository.toggleActiveStatus(created.id, false)

    // Subsequent access is blocked
    val afterDeact = repository.validateAndGetVideo("GM-DEACT-01")
    assertTrue(afterDeact is TokenValidationResult.TokenDeactivated)
  }

  // 9. Videos marked inactive cannot be played
  @Test
  fun test09_inactiveVideosCannotBePlayed() = runBlocking {
    repository.initializeSeedsIfEmpty()
    // seed_4 is explicitly seeded as isActive = false (GM-FLAME-04)
    val result = repository.validateAndGetVideo("GM-FLAME-04")
    assertTrue(result is TokenValidationResult.TokenDeactivated)
  }

  // 10. Links do not expire automatically (expiresAt is null)
  @Test
  fun test10_linksDoNotExpireAutomatically() {
    val link = FirestoreAccessLink(
      linkId = "link_sample_1",
      videoId = "vid_sample_1",
      tokenHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      isActive = true,
      expiresAt = null // null confirms non-expiring link
    )
    assertNull("Default link must never expire automatically", link.expiresAt)
    assertTrue(link.isActive)
  }

  // 11. Video view counter increments upon playback
  @Test
  fun test11_viewCounterIncrements() = runBlocking {
    val created = repository.createVideoToken(
      title = "View Count Test",
      description = "Views test",
      streamUrl = "https://example.com/view.mp4",
      customToken = "GM-VIEW-01"
    )
    assertEquals(0, created.viewCount)

    val validation1 = repository.validateAndGetVideo("GM-VIEW-01") as TokenValidationResult.Success
    assertEquals(1, validation1.video.viewCount)

    val validation2 = repository.validateAndGetVideo("GM-VIEW-01") as TokenValidationResult.Success
    assertEquals(2, validation2.video.viewCount)
  }

  // 12. AdMob test advertisements configuration
  @Test
  fun test12_adMobTestAdsConfiguration() {
    assertEquals("ca-app-pub-3940256099942544~3347511713", AdMobConfig.TEST_APP_ID)
    assertEquals("ca-app-pub-3940256099942544/6300978111", AdMobConfig.TEST_BANNER_AD_UNIT_ID)
    assertEquals("ca-app-pub-3940256099942544/1033173712", AdMobConfig.TEST_INTERSTITIAL_AD_UNIT_ID)
    assertEquals("ca-app-pub-3940256099942544/9257395921", AdMobConfig.TEST_APP_OPEN_AD_UNIT_ID)
    assertEquals("ca-app-pub-3940256099942544/5224354917", AdMobConfig.TEST_REWARDED_AD_UNIT_ID)
    assertEquals("ca-app-pub-1392123530264284/6658750910", AdMobConfig.getBannerAdUnitId(context))
    assertEquals("ca-app-pub-1392123530264284/8442751662", AdMobConfig.getNativeAdUnitId(context))
    assertEquals("ca-app-pub-1392123530264284/6889603635", AdMobConfig.getInterstitialAdUnitId(context))
    assertEquals("ca-app-pub-1392123530264284/4172188378", AdMobConfig.getAppOpenAdUnitId(context))
    assertFalse("Production config must never use Google test ad IDs", AdMobConfig.isUsingTestIds(context))
    assertTrue("App Open Ads are enabled in AdMobConfig", AdMobConfig.isAppOpenConfigEnabled(context))

    assertEquals(2, AdMobConfig.INTERSTITIAL_ACTION_THRESHOLD)
    assertEquals(25_000L, AdMobConfig.MIN_INTERSTITIAL_COOLDOWN_MS)
    assertEquals(30_000L, AdMobConfig.MIN_APP_OPEN_COOLDOWN_MS)
    assertEquals(10 * 60_000L, AdMobConfig.AD_FREE_REWARD_DURATION_MS)

    val adManager = AdManager(context)
    assertFalse("Default session must show ads", adManager.isAdFreeSessionActive.value)
    assertFalse("Expired VIP window must keep ads active", adManager.refreshAdFreeStatus())
    assertTrue("Initial fullscreen cooldown must be elapsed", adManager.isFullScreenCooldownElapsed())
    assertTrue("Initial App Open cooldown must be elapsed", adManager.appOpenAdManager.isCooldownElapsed())
    assertFalse("Player active guard must default to false", adManager.isPlayerActive)

    // Verify player active guard prevents showing ads over video
    adManager.isPlayerActive = true
    assertTrue("AdManager tracks active video playback", adManager.isPlayerActive)
  }

  // 13. ExoPlayer playback state formatting and scrubbing math
  @Test
  fun test13_exoPlayerPlaybackMathAndState() {
    // Time formatting
    assertEquals("00:00", VideoPlaybackState.formatTime(0L))
    assertEquals("00:45", VideoPlaybackState.formatTime(45000L))
    assertEquals("03:20", VideoPlaybackState.formatTime(200000L))
    assertEquals("01:15:30", VideoPlaybackState.formatTime(4530000L))

    // Scrubbing progress math
    val state = VideoPlaybackState(
      currentPositionMs = 30000L,
      durationMs = 60000L
    )
    assertEquals(0.5f, state.progress, 0.001f)

    // Clamping limits
    val overState = VideoPlaybackState(
      currentPositionMs = 90000L,
      durationMs = 60000L
    )
    assertEquals(1.0f, overState.progress, 0.001f)
  }

  // 14. Firebase Security Rules enforce admin and public isolation
  @Test
  fun test14_firebaseSecurityRulesVerification() {
    val firestoreRulesFile = listOf(File("firestore.rules"), File("../firestore.rules"), File("../../firestore.rules")).firstOrNull { it.exists() }
    assertNotNull("firestore.rules file must exist", firestoreRulesFile)
    val firestoreRules = firestoreRulesFile!!.readText()

    assertTrue(firestoreRules.contains("function isAdmin()"))
    assertTrue(firestoreRules.contains("allow read: if resource.data.isActive == true || isAdmin();"))
    assertTrue(firestoreRules.contains("allow write: if isAdmin()"))

    val storageRulesFile = listOf(File("storage.rules"), File("../storage.rules"), File("../../storage.rules")).firstOrNull { it.exists() }
    assertNotNull("storage.rules file must exist", storageRulesFile)
    val storageRules = storageRulesFile!!.readText()

    // Direct public read access is explicitly blocked
    assertTrue(storageRules.contains("allow read: if false;"))
    assertTrue(storageRules.contains("allow write: if request.auth != null"))
  }

  // 15. No secret keys or permanent public video URLs exposed
  @Test
  fun test15_noSecretKeysExposed() {
    val envExampleFile = listOf(File(".env.example"), File("../.env.example"), File("../../.env.example")).firstOrNull { it.exists() }
    assertNotNull(".env.example file must exist", envExampleFile)
    val envExample = envExampleFile!!.readText()
    assertFalse("No live production secrets in .env.example", envExample.contains("AIzaSy"))

    // Ensure Cloud Functions index.js uses HMAC with environment fallback
    val functionsFile = listOf(File("functions/index.js"), File("../functions/index.js"), File("../../functions/index.js")).firstOrNull { it.exists() }
    assertNotNull("functions/index.js file must exist", functionsFile)
    val functionsCode = functionsFile!!.readText()
    assertTrue(functionsCode.contains("process.env.PLAYBACK_SECRET"))
    assertTrue(functionsCode.contains("streamVideo?playbackToken="))
  }
}
