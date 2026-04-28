package com.example.safewalknav.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * GPS 위치 추적기
 * FusedLocationProvider 사용 (Google Play Services)
 * - 배터리 효율 좋음
 * - GPS + WiFi + 셀룰러 결합으로 정확도 높음
 */
class LocationTracker(private val context: Context) {

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    /**
     * 실시간 위치 업데이트를 Flow로 제공
     * @param intervalMs 업데이트 간격 (ms), 기본 2초
     */
    fun getLocationUpdates(intervalMs: Long = 2000L): Flow<Location> = callbackFlow {
        // 권한 체크
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            close(SecurityException("위치 권한이 없습니다"))
            return@callbackFlow
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            intervalMs
        ).apply {
            setMinUpdateDistanceMeters(1f)  // 최소 1m 이동 시에만 업데이트
            setWaitForAccurateLocation(true)
        }.build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    trySend(location)
                }
            }
        }

        fusedClient.requestLocationUpdates(
            locationRequest,
            callback,
            Looper.getMainLooper()
        )

        // Flow가 취소되면 위치 업데이트 중지
        awaitClose {
            fusedClient.removeLocationUpdates(callback)
        }
    }

    /**
     * 현재 위치 한 번만 가져오기
     */
    suspend fun getCurrentLocation(): Location? {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        return try {
            val cts = com.google.android.gms.tasks.CancellationTokenSource()
            fusedClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cts.token
            ).await()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    companion object {
        /**
         * 두 지점 간 거리 계산 (미터)
         */
        fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val results = FloatArray(1)
            Location.distanceBetween(lat1, lon1, lat2, lon2, results)
            return results[0]
        }

        /**
         * 현재 위치에서 목적지 방향 계산 (시계 방향)
         * @param userBearing 사용자의 진행 방향 (0~360)
         * @return "12시", "3시" 등 시계 방향 문자열
         */
        fun getClockDirection(
            currentLat: Double, currentLon: Double,
            targetLat: Double, targetLon: Double,
            userBearing: Float
        ): String {
            val targetLocation = Location("target").apply {
                latitude = targetLat
                longitude = targetLon
            }
            val currentLocation = Location("current").apply {
                latitude = currentLat
                longitude = currentLon
            }

            // 목적지까지의 절대 방위각
            val absoluteBearing = currentLocation.bearingTo(targetLocation)

            // 사용자 진행 방향 기준 상대 각도
            var relativeBearing = absoluteBearing - userBearing
            if (relativeBearing < 0) relativeBearing += 360f
            if (relativeBearing >= 360) relativeBearing -= 360f

            // 시계 방향으로 변환 (30도 = 1시간)
            val clockHour = ((relativeBearing + 15) / 30).toInt() % 12
            val hour = if (clockHour == 0) 12 else clockHour

            return "${hour}시"
        }
    }
}
