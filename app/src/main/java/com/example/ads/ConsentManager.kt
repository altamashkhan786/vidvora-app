package com.example.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

private const val TAG = "ConsentManager"

class ConsentManager(private val context: Context) {

  private val consentInformation: ConsentInformation? = try {
    UserMessagingPlatform.getConsentInformation(context)
  } catch (t: Throwable) {
    Log.w(TAG, "ConsentInformation unavailable: ${t.message}")
    null
  }

  fun gatherConsent(
    activity: Activity,
    onConsentGathered: (canRequestAds: Boolean) -> Unit
  ) {
    val info = consentInformation
    if (info == null) {
      onConsentGathered(true)
      return
    }

    try {
      val params = ConsentRequestParameters.Builder()
        .setTagForUnderAgeOfConsent(false)
        .build()

      info.requestConsentInfoUpdate(
        activity,
        params,
        {
          try {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
              if (formError != null) {
                Log.w(TAG, "Consent form error (${formError.errorCode}): ${formError.message}")
              }
              val canRequest = info.canRequestAds() ||
                info.consentStatus != ConsentInformation.ConsentStatus.REQUIRED
              onConsentGathered(canRequest)
            }
          } catch (t: Throwable) {
            Log.w(TAG, "Consent form presentation failed: ${t.message}")
            onConsentGathered(info.canRequestAds() || info.consentStatus != ConsentInformation.ConsentStatus.REQUIRED)
          }
        },
        { requestError ->
          Log.w(TAG, "Consent info update error (${requestError.errorCode}): ${requestError.message}")
          // Fallback cleanly if UMP form isn't configured in AdMob Console or offline during startup
          val canRequest = info.canRequestAds() ||
            info.consentStatus != ConsentInformation.ConsentStatus.REQUIRED
          onConsentGathered(canRequest)
        }
      )
    } catch (t: Throwable) {
      Log.w(TAG, "Consent update failed: ${t.message}")
      onConsentGathered(true)
    }
  }

  val canRequestAds: Boolean
    get() = try {
      consentInformation?.canRequestAds() == true
    } catch (_: Throwable) {
      false
    }

  val isPrivacyOptionsRequired: Boolean
    get() = try {
      consentInformation?.privacyOptionsRequirementStatus ==
          ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    } catch (_: Throwable) {
      false
    }

  fun showPrivacyOptionsForm(activity: Activity, onDismissed: () -> Unit = {}) {
    try {
      UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
        if (formError != null) {
          Log.w(TAG, "Privacy options form error: ${formError.message}")
        }
        onDismissed()
      }
    } catch (t: Throwable) {
      Log.w(TAG, "Privacy options form failed: ${t.message}")
      onDismissed()
    }
  }
}
