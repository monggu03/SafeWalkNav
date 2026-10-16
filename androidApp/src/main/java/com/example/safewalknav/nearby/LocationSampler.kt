package com.example.safewalknav.nearby

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.location.LocationManagerCompat
import com.example.safewalknav.navigation.nearby.LocationFusion
import com.example.safewalknav.navigation.nearby.LocationSample
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 몇 초 동안 위치 픽스를 모은다 (GPS 오차 보정의 수집 단계).
 *
 * - 고정밀(PRIORITY_HIGH_ACCURACY)로 1초마다 받는다.
 * - 오차 8m 이하 픽스가 2개 모이면 일찍 끝낸다 ([LocationFusion.isGoodEnough]).
 * - 오래된 캐시 픽스(10초 넘은 것)는 버린다 — Fused 는 첫 결과로 예전 위치를 줄 수 있는데,
 *   그건 "지금 여기"가 아니다.
 *
 * 합치는 일(가중 평균·튄 값 제거)은 shared 의 [LocationFusion] 이 한다.
 * 콜백은 메인 루퍼에서 오고, 호출자도 메인(lifecycleScope)이라 리스트 접근이 한 스레드다.
 */
class LocationSampler(context: Context) {

    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)

    /** 휴대폰의 위치 기능(설정)이 켜져 있는가. 꺼져 있으면 아무리 기다려도 픽스가 안 온다. */
    fun isLocationEnabled(): Boolean {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    /**
     * @param maxDurationMs 최대 대기 시간. 조기 종료 조건을 못 채우면 이때까지 모은 걸 돌려준다.
     * @return 모은 픽스 (비어 있을 수 있음). 권한 확인은 호출자 책임.
     */
    @SuppressLint("MissingPermission")
    suspend fun sample(maxDurationMs: Long = DEFAULT_MAX_DURATION_MS): List<LocationSample> {
        val samples = mutableListOf<LocationSample>()
        val enough = CompletableDeferred<Unit>()
        // 단조 시계로 비교한다. loc.time 은 GPS 시각일 수 있어, 휴대폰 시계가 10초 넘게 틀린 기기에선
        // 벽시계 비교가 모든 픽스를 "오래됨"으로 버린다 → 영원히 "위치를 찾지 못함".
        val startedAtNanos = SystemClock.elapsedRealtimeNanos()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    if (!loc.hasAccuracy()) continue
                    // 캐시된 옛 위치 거르기
                    if (startedAtNanos - loc.elapsedRealtimeNanos > STALE_FIX_NANOS) continue
                    samples += LocationSample(loc.latitude, loc.longitude, loc.accuracy, loc.time)
                }
                if (!enough.isCompleted && LocationFusion.isGoodEnough(samples)) enough.complete(Unit)
            }
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
            .setWaitForAccurateLocation(false)
            .build()

        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            withTimeoutOrNull(maxDurationMs) { enough.await() }
        } finally {
            client.removeLocationUpdates(callback)
        }
        return samples.toList()
    }

    companion object {
        const val DEFAULT_MAX_DURATION_MS = 8_000L
        private const val INTERVAL_MS = 1_000L
        private const val MIN_INTERVAL_MS = 500L
        private const val STALE_FIX_NANOS = 10_000_000_000L   // 10초
    }
}
