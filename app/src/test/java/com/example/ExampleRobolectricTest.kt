package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.AdminAuthRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun readStringFromContext_matchesVidVora() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("VidVora", appName)
  }

  @Test
  fun adminAuth_validatesCorrectKeys() {
    val adminAuth = AdminAuthRepository()
    assertFalse(adminAuth.isAdminAuthenticated.value)

    assertFalse(adminAuth.authenticate("wrong_passkey"))
    assertFalse(adminAuth.authenticate("7827128212@8825254100"))
    assertFalse(adminAuth.authenticate("other@gmail.com", "7827128212"))
    assertFalse(adminAuth.isAdminAuthenticated.value)

    assertTrue(adminAuth.authenticate("teamaltamash@gmail.com", "7827128212"))
    assertTrue(adminAuth.isAdminAuthenticated.value)

    adminAuth.logout()
    assertFalse(adminAuth.isAdminAuthenticated.value)
  }

  @Test
  fun mainActivity_launchesWithoutCrashing() {
    val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
    val activity = controller.get()
    org.junit.Assert.assertNotNull(activity)
  }
}
