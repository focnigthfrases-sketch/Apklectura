package com.readicop.lettor.monetization

import android.app.Activity
import android.content.Context
import android.util.DisplayMetrics
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.readicop.lettor.R
import com.readicop.lettor.data.preferences.ReaderPreferencesRepository
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manages respectful AdMob monetization:
 * - Adaptive Banner in Library only (never inside the reader)
 * - Max 3 Interstitials per day after successful non-cancelled imports
 * - Optional Rewarded Ad in extra tools/settings
 * - Silent graceful fallback if any ad fails to load
 */
class AdMobManager(
  private val context: Context,
  private val preferencesRepository: ReaderPreferencesRepository
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  @Volatile
  private var isInitialized = false
  private var interstitialAd: InterstitialAd? = null
  private var rewardedAd: RewardedAd? = null

  fun initialize() {
    if (isInitialized) return
    isInitialized = true
    runCatching {
      MobileAds.initialize(context) {
        preloadInterstitial()
        preloadRewarded()
      }
    }
  }

  fun preloadInterstitial() {
    runCatching {
      val adUnitId = context.getString(R.string.admob_interstitial_id)
      InterstitialAd.load(
        context,
        adUnitId,
        AdRequest.Builder().build(),
        object : InterstitialAdLoadCallback() {
          override fun onAdLoaded(ad: InterstitialAd) {
            interstitialAd = ad
          }

          override fun onAdFailedToLoad(error: LoadAdError) {
            interstitialAd = null
          }
        }
      )
    }
  }

  fun preloadRewarded() {
    runCatching {
      val adUnitId = context.getString(R.string.admob_rewarded_id)
      RewardedAd.load(
        context,
        adUnitId,
        AdRequest.Builder().build(),
        object : RewardedAdLoadCallback() {
          override fun onAdLoaded(ad: RewardedAd) {
            rewardedAd = ad
          }

          override fun onAdFailedToLoad(error: LoadAdError) {
            rewardedAd = null
          }
        }
      )
    }
  }

  /**
   * Called strictly AFTER a book import succeeds (not cancelled, not failed).
   * Enforces daily cap of max 3 interstitials/day, resetting every day.
   */
  fun onImportCompletedSuccessfully(activity: Activity?, onContinue: () -> Unit) {
    scope.launch {
      val allowedToday = preferencesRepository.consumeDailyImportAdSlotIfAllowed()
      val ad = interstitialAd
      if (!allowedToday || ad == null || activity == null || activity.isFinishing) {
        if (allowedToday && ad == null) preloadInterstitial()
        onContinue()
        return@launch
      }

      ad.fullScreenContentCallback = object : FullScreenContentCallback() {
        override fun onAdDismissedFullScreenContent() {
          interstitialAd = null
          preloadInterstitial()
          onContinue()
        }

        override fun onAdFailedToShowFullScreenContent(adError: AdError) {
          interstitialAd = null
          preloadInterstitial()
          onContinue()
        }
      }
      ad.show(activity)
    }
  }

  /**
   * Optional rewarded ad for extra tools / supporting Lettor.
   * Always falls back gracefully if no ad is loaded.
   */
  fun showOptionalRewardedAd(
    activity: Activity?,
    onRewardGranted: () -> Unit,
    onUnavailableOrClosed: (wasRewarded: Boolean) -> Unit
  ) {
    val ad = rewardedAd
    if (ad == null || activity == null || activity.isFinishing) {
      preloadRewarded()
      onUnavailableOrClosed(false)
      return
    }

    var earned = false
    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
      override fun onAdDismissedFullScreenContent() {
        rewardedAd = null
        preloadRewarded()
        onUnavailableOrClosed(earned)
      }

      override fun onAdFailedToShowFullScreenContent(error: AdError) {
        rewardedAd = null
        preloadRewarded()
        onUnavailableOrClosed(false)
      }
    }

    ad.show(activity) {
      earned = true
      scope.launch {
        preferencesRepository.markRewardedToolsUnlockedToday()
      }
      onRewardGranted()
    }
  }
}

@Composable
fun LibraryAdaptiveBanner(
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val bannerAdUnitId = remember(context) { context.getString(R.string.admob_banner_id) }

  val adView = remember(context, bannerAdUnitId) {
    runCatching {
      AdView(context).apply {
        adUnitId = bannerAdUnitId
        val metrics: DisplayMetrics = context.resources.displayMetrics
        val adWidthDp = (metrics.widthPixels / metrics.density).toInt().coerceAtLeast(320)
        setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, adWidthDp))
        loadAd(AdRequest.Builder().build())
      }
    }.getOrNull()
  }

  if (adView != null) {
    DisposableEffect(adView) {
      onDispose { runCatching { adView.destroy() } }
    }
    AndroidView(
      factory = { adView },
      modifier = modifier.fillMaxWidth()
    )
  }
}
