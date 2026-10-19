package com.example.safewalknav.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.example.safewalknav.R
import com.example.safewalknav.a11y.AppSettings
import com.example.safewalknav.a11y.PermissionGate
import com.example.safewalknav.a11y.Speaker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 기능3: 음향신호기 켜기 — **현재는 현장 조사 모드.**
 *
 * 왜 아직 "켜기"가 아닌가:
 *  - 휴대폰으로 켤 수 있는 건 블루투스(BLE)를 단 '스마트 음향신호기'뿐이다. 옛 기기는 전용
 *    무선 리모컨 신호만 받아 휴대폰으로는 원리적으로 켤 수 없다.
 *  - 스마트 음향신호기의 명령 형식은 경찰청 「시각장애인용 음향신호기 규격서」의 공용 프로토콜에
 *    정해져 있는데, 아직 확보하지 못했다. 공공 교통시설에 추측한 명령을 보내지 않는다.
 *
 * 그래서 지금은:
 *  - 10초 동안 주변 BLE 광고를 모아 이름·주소·신호 세기·서비스 UUID·제조사 ID·원본 바이트를 보여 주고
 *  - [기록 공유] 로 팀에 보낸다. 교차로마다 모으면 "서울에 스마트 음향신호기가 실제로 있는지",
 *    "어떤 이름으로 광고하는지"를 규격서 도착 전에 확인할 수 있다.
 *
 * ⚠️ 안드로이드는 앱당 30초에 startScan 5회까지만 허용한다(넘기면 조용히 결과가 안 온다).
 *    그래서 스캔 중에는 [다시 찾기] 를 막는다.
 */
class BleScanActivity : AppCompatActivity() {

    /** 한 기기에 대해 모은 정보. */
    private class Seen(
        val address: String,
        var name: String?,
        var bestRssi: Int,
        var lastRssi: Int,
        var count: Int,
        val serviceUuids: MutableSet<String>,
        val manufacturerIds: MutableSet<Int>,
        var rawHex: String,
    )

    private lateinit var tvStatus: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var btnScan: Button
    private lateinit var btnShare: Button
    private lateinit var speaker: Speaker

    private val seen = LinkedHashMap<String, Seen>()
    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var lastScanAt: Long = 0L

    private val stopRunnable = Runnable { stopScan(announce = true) }
    // 목록은 스캔이 끝났을 때 한 번만 그린다. 스캔 중 매초 다시 그리면 행이 통째로 바뀌어
    // TalkBack 포커스가 날아간다(읽던 줄을 잃음).

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            runOnUiThread { record(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            runOnUiThread { results.forEach { record(it) } }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                stopScan(announce = false)
                speaker.speak("블루투스 검색에 실패했습니다. 잠시 후 다시 찾기를 눌러 주세요.")
            }
        }
    }

    /**
     * 권한을 "다시 묻지 않음"으로 거부해 시스템이 더는 창을 띄우지 않는 상태.
     * 이때 [다시 찾기] 는 앱 설정 화면을 연다 — 같은 안내만 반복하면 빠져나갈 길이 없다.
     */
    private var permissionBlocked = false

    /** 설정 화면으로 보냈다 — 돌아왔을 때 권한이 생겼으면 바로 찾기 시작, 아니면 무엇이 빠졌는지 말한다. */
    private var sentToSettings = false

    /** 마지막 권한 요청 시각 — 창이 뜨지도 않고 즉시 거부됐는지 판단용([PermissionGate]). */
    private var permissionRequestedAt = 0L

    /** 우리가 띄운 권한 창이 떠 있다 — 그동안의 onPause 는 "떠남"이 아니다. */
    private var permissionDialogShowing = false

    /** 안내를 말하던 중에 화면을 떠났다 — 돌아오면 지금 상태를 다시 말해 준다. */
    private var speechCutOff = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionDialogShowing = false
            if (hasScanPermissions()) {
                permissionBlocked = false
                startScan()
                return@registerForActivityResult
            }
            // 영구 거부 판정 — 창을 그냥 닫은 것은 영구 거부가 아니다(PermissionGate 주석).
            val missing = missingPermissions()
            // map 먼저 — any{}/all{} 로 바로 돌리면 중간에 멈춰, 나머지 권한의 거부 이력이 기록되지 않는다.
            // 하나라도 다시 물을 수 없으면 그 권한은 설정 화면에서만 켤 수 있다 → 설정으로 보낸다.
            val blocked = missing.map { PermissionGate.isBlocked(this, it, permissionRequestedAt) }
            permissionBlocked = blocked.any { it }
            speaker.speak(permissionMessage())
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ble_scan)
        title = "음향신호기 켜기"

        tvStatus = findViewById(R.id.tvBleStatus)
        listContainer = findViewById(R.id.bleList)
        btnScan = findViewById(R.id.btnBleScan)
        btnShare = findViewById(R.id.btnBleShare)
        speaker = Speaker(this, tvStatus)

        btnScan.setOnClickListener { ensurePermissionsThenScan() }
        btnShare.setOnClickListener { shareLog() }

        if (savedInstanceState == null) {
            // 권한 창 예고는 첫 안내에 붙여 한 번에 — 따로 말하면 엔진 준비 전이라 첫 안내가 덮인다.
            val dialogHint = if (!hasScanPermissions() && !speaker.isScreenReaderOn()) " $PERMISSION_DIALOG_HINT" else ""
            speaker.speak(INTRO + dialogHint)
            ensurePermissionsThenScan(announceDialog = false)
        } else {
            // 재생성(프로세스 복원 등) 때는 권한 창을 또 띄우지 않는다 — 떠 있던 창의 결과는 새 인스턴스로 온다.
            // 대신 상태를 되살리고 지금 어떤 상태인지 한 번 말한다(글씨는 저장되지 않아 빈 화면이 된다).
            sentToSettings = savedInstanceState.getBoolean(STATE_SENT_TO_SETTINGS, false)
            permissionBlocked = savedInstanceState.getBoolean(STATE_PERMISSION_BLOCKED, false)
            if (!sentToSettings) {
                speaker.speak(if (hasScanPermissions()) RESTORED else permissionMessage())
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SENT_TO_SETTINGS, sentToSettings)
        outState.putBoolean(STATE_PERMISSION_BLOCKED, permissionBlocked)
    }

    override fun onResume() {
        super.onResume()
        if (sentToSettings) {
            sentToSettings = false
            speechCutOff = false
            if (hasScanPermissions()) {
                permissionBlocked = false
                startScan()
            } else {
                // 설정에서 허용하지 않고(또는 하나만 허용하고) 돌아옴 — 예전엔 아무 말도 없었다.
                speaker.speak(permissionMessage())
            }
            return
        }
        // 안내를 듣다가 나갔다 돌아옴 — 조용하면 어느 화면인지 모른다. 지금 상태를 한 마디로.
        if (speechCutOff) {
            speechCutOff = false
            speaker.speak(if (hasScanPermissions()) RESTORED else permissionMessage())
        }
    }

    /**
     * 홈 버튼·최근 앱·화면 끄기 → **즉시** 스캔과 말을 멈춘다(예전엔 말은 onStop 에서야 멈춰 앱 밖에서도 조금 더 들렸다).
     * 예외: 우리가 띄운 권한 창이 이 화면을 가린 경우(창을 설명하는 안내를 끊지 않게),
     *       우리가 연 설정 화면으로 가는 경우("허용한 뒤 돌아오세요"를 끝까지).
     */
    override fun onPause() {
        super.onPause()
        // 화면을 떠나면 스캔을 멈춘다 — 배터리, 그리고 백그라운드 스캔 제한.
        stopScan(announce = false)
        if (!permissionDialogShowing && !sentToSettings) {
            if (speaker.isBusy()) speechCutOff = true
            speaker.stop()
        }
    }

    /** 안전망 — 권한 창이 떠 있는 동안 홈 버튼을 누른 경우(onPause 에서는 일부러 안 멈춤)가 여기서 걸린다. */
    override fun onStop() {
        super.onStop()
        if (!sentToSettings) {
            if (speaker.isBusy()) speechCutOff = true
            speaker.stop()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        speaker.shutdown()
        super.onDestroy()
    }

    // ==================== 권한 ====================

    /**
     * 요청할 권한. targetSdk 31+ 에서는 FINE 만 따로 요청하면 시스템이 무시하므로 COARSE 를 함께 넣는다.
     * 스캔에 실제로 필요한 건 BLUETOOTH_SCAN(12+) 과 FINE 위치다 — [hasScanPermissions] 는 그것만 본다.
     */
    private fun requestedPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }

    private fun neededPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun granted(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun missingPermissions(): List<String> = neededPermissions().filterNot { granted(it) }

    private fun hasScanPermissions(): Boolean = missingPermissions().isEmpty()

    private fun onlyApproximateLocation(): Boolean =
        granted(Manifest.permission.ACCESS_COARSE_LOCATION) && !granted(Manifest.permission.ACCESS_FINE_LOCATION)

    /** 지금 빠진 권한에 맞는 안내. 무엇을 켜야 하는지 구체적으로 말한다. */
    private fun permissionMessage(): String {
        val missing = missingPermissions()
        val needsPrecise = onlyApproximateLocation()
        val needsNearby = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            Manifest.permission.BLUETOOTH_SCAN in missing
        return when {
            missing.isEmpty() -> RESTORED
            // 안드로이드 12+: 위치를 "대략적"으로만 허용함. 블루투스 검색에는 정확한 위치가 필요하다.
            // 영구 거부 상태면 설정의 위치 항목에서 "정확한 위치 사용"을 켜야 한다 — 그냥 "위치를 허용하세요"라고
            // 하면, 설정에는 이미 '허용'으로 보여서 무엇을 바꿔야 하는지 모른다.
            permissionBlocked && needsPrecise && !needsNearby -> PERMISSION_BLOCKED_APPROXIMATE
            permissionBlocked -> PERMISSION_BLOCKED
            needsPrecise && !needsNearby -> PERMISSION_APPROXIMATE
            else -> PERMISSION_DENIED
        }
    }

    private fun ensurePermissionsThenScan(announceDialog: Boolean = true) {
        when {
            hasScanPermissions() -> startScan()
            permissionBlocked -> {
                speaker.speak(OPENING_SETTINGS)
                sentToSettings = AppSettings.open(this)
            }
            else -> {
                // TalkBack 없이 쓰는 사람은 권한 창이 떴다는 것도 알 수 없다 — 미리 말해 둔다.
                // (TalkBack 사용자는 TalkBack 이 창을 읽는다)
                if (announceDialog && !speaker.isScreenReaderOn()) speaker.speak(PERMISSION_DIALOG_HINT)
                permissionRequestedAt = PermissionGate.markRequested()
                permissionDialogShowing = true   // 권한 창이 이 화면을 가려도(onPause) 위 안내는 끝까지 들려준다
                permissionLauncher.launch(requestedPermissions())
            }
        }
    }

    // ==================== 스캔 ====================

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (scanning) return
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            speaker.speak("이 휴대폰은 블루투스를 지원하지 않습니다.")
            return
        }
        if (!adapter.isEnabled) {
            speaker.speak("블루투스가 꺼져 있습니다. 블루투스를 켠 뒤 다시 찾기를 눌러 주세요.")
            return
        }
        // neverForLocation 을 안 쓰므로, 휴대폰 위치 기능이 꺼져 있으면 시스템이 스캔 결과를 전부 버린다.
        // 그대로 두면 "찾지 못했습니다"라는 거짓 결과가 나간다.
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null || !LocationManagerCompat.isLocationEnabled(lm)) {
            speaker.speak("휴대폰의 위치 기능이 꺼져 있으면 주변 장치를 찾을 수 없습니다. 위치를 켠 뒤 다시 찾기를 눌러 주세요.")
            return
        }
        val s = adapter.bluetoothLeScanner
        if (s == null) {
            speaker.speak("블루투스 검색을 시작할 수 없습니다.")
            return
        }
        // 시스템 제한(앱당 30초에 5회)을 넘기면 오류 없이 결과만 안 온다 → "찾지 못했습니다"라는 거짓 결과.
        // 화면을 빠르게 들락날락해도 넘지 않게 프로세스 전체에서 센다.
        val waitMs = ScanBudget.waitBeforeNextStart(SystemClock.elapsedRealtime())
        if (waitMs > 0) {
            val sec = ((waitMs + 999) / 1000).coerceAtLeast(1)
            speaker.speak("블루투스 검색을 너무 자주 했습니다. ${sec}초 뒤에 다시 찾기를 눌러 주세요.")
            return
        }
        scanner = s
        seen.clear()
        listContainer.removeAllViews()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            s.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            speaker.speak("주변 장치를 찾으려면 근처 기기 권한이 필요합니다.")
            return
        } catch (e: IllegalStateException) {
            // 확인 직후 블루투스가 꺼짐(BT adapter is not turned ON)
            speaker.speak("블루투스가 꺼져 있습니다. 블루투스를 켠 뒤 다시 찾기를 눌러 주세요.")
            return
        }
        ScanBudget.recordStart(SystemClock.elapsedRealtime())
        scanning = true
        lastScanAt = System.currentTimeMillis()
        // 상태 글씨는 여기서 바꾸지 않는다 — 라이브 리전이라, 바꾸면 TalkBack 이 읽던 첫 안내를 끊는다.
        // 버튼 비활성(“사용할 수 없음”)이 스캔 중이라는 표시가 된다.
        btnScan.isEnabled = false
        handler.postDelayed(stopRunnable, SCAN_DURATION_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(announce: Boolean) {
        handler.removeCallbacks(stopRunnable)
        if (!scanning) return
        scanning = false
        try {
            scanner?.stopScan(callback)
        } catch (_: Exception) {
            // 블루투스가 도중에 꺼졌거나 권한이 회수됨 — 멈추는 중이므로 무시
        }
        btnScan.isEnabled = true
        render()
        if (announce) {
            val n = seen.size
            speaker.speak(
                if (n == 0) "주변에서 블루투스 장치를 찾지 못했습니다."
                else "주변 블루투스 장치 ${n}개를 찾았습니다. 기록 공유를 누르면 팀에 보낼 수 있습니다."
            )
        }
    }

    private fun record(result: ScanResult) {
        if (!scanning) return
        val rec = result.scanRecord
        val address = result.device.address ?: return
        val s = seen.getOrPut(address) {
            Seen(
                address = address,
                name = null,
                bestRssi = result.rssi,
                lastRssi = result.rssi,
                count = 0,
                serviceUuids = mutableSetOf(),
                manufacturerIds = mutableSetOf(),
                rawHex = "",
            )
        }
        s.count++
        s.lastRssi = result.rssi
        if (result.rssi > s.bestRssi) s.bestRssi = result.rssi
        // 광고에 실린 이름 — BluetoothDevice.getName() 과 달리 BLUETOOTH_CONNECT 권한이 필요 없다.
        rec?.deviceName?.takeIf { it.isNotBlank() }?.let { s.name = it }
        rec?.serviceUuids?.forEach { s.serviceUuids += it.uuid.toString() }
        rec?.manufacturerSpecificData?.let { sa ->
            for (i in 0 until sa.size()) s.manufacturerIds += sa.keyAt(i)
        }
        rec?.bytes?.let { s.rawHex = toHexTrimmed(it) }
    }

    // ==================== 표시 / 공유 ====================

    private fun sorted(): List<Seen> = seen.values.sortedByDescending { it.bestRssi }

    private fun render() {
        listContainer.removeAllViews()
        for (s in sorted()) {
            listContainer.addView(TextView(this).apply {
                text = buildString {
                    append(s.name ?: "(이름 없음)")
                    append("\n").append(s.address)
                    append("  ").append(s.bestRssi).append(" dBm  (").append(s.count).append("회)")
                    if (s.serviceUuids.isNotEmpty()) append("\nUUID: ").append(s.serviceUuids.joinToString())
                    if (s.manufacturerIds.isNotEmpty()) {
                        append("\n제조사 ID: ").append(s.manufacturerIds.joinToString { "0x%04X".format(it) })
                    }
                }
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(0, dp(10), 0, dp(10))
                isFocusable = true
                contentDescription = "${s.name ?: "이름 없음"}, 신호 세기 ${s.bestRssi}"
            })
        }
    }

    private fun shareLog() {
        if (seen.isEmpty()) {
            speaker.speak("공유할 기록이 없습니다. 먼저 다시 찾기를 눌러 주세요.")
            return
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date(lastScanAt))
        // 번화가에서는 수백 대가 잡힌다. 전부 넣으면 인텐트가 커져(약 1MB 제한) 공유 창이 안 뜨고 앱이 죽는다.
        // 음향신호기는 가까이 있으니 신호가 센 순서로 앞쪽만 보낸다.
        val all = sorted()
        val shown = all.take(SHARE_MAX_DEVICES)
        val text = buildString {
            append("SafeWalk 음향신호기 BLE 조사\n")
            append("시각: ").append(stamp).append('\n')
            append("기기: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (Android ").append(Build.VERSION.RELEASE).append(")\n")
            append("장소: (직접 적어 주세요 — 교차로 이름·모퉁이)\n")
            if (all.size > shown.size) {
                append("(전체 ").append(all.size).append("대 중 신호가 센 ").append(shown.size).append("대)\n")
            }
            append('\n')
            for (s in shown) {
                append("- ").append(s.name ?: "(이름 없음)").append(" | ").append(s.address)
                append(" | best ").append(s.bestRssi).append(" dBm | n=").append(s.count).append('\n')
                if (s.serviceUuids.isNotEmpty()) append("  uuid: ").append(s.serviceUuids.joinToString()).append('\n')
                if (s.manufacturerIds.isNotEmpty()) {
                    append("  mfr: ").append(s.manufacturerIds.joinToString { "0x%04X".format(it) }).append('\n')
                }
                if (s.rawHex.isNotEmpty()) append("  raw: ").append(s.rawHex).append('\n')
            }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "SafeWalk BLE 조사 $stamp")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(Intent.createChooser(send, "기록 공유"))
        } catch (_: Exception) {
            // 공유할 앱이 없음(ActivityNotFound) 등
            speaker.speak("기록을 공유할 수 없습니다. 메시지나 메일 앱이 있는지 확인해 주세요.")
        }
    }

    /** 광고 바이트를 16진수로. 뒤쪽 0 패딩은 잘라 읽기 쉽게. */
    private fun toHexTrimmed(bytes: ByteArray): String {
        var end = bytes.size
        while (end > 0 && bytes[end - 1].toInt() == 0) end--
        return (0 until end).joinToString("") { "%02X".format(bytes[it]) }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val SCAN_DURATION_MS = 10_000L
        private const val SHARE_MAX_DEVICES = 100

        private const val INTRO =
            "음향신호기 켜기는 아직 준비 중입니다. 지금은 주변 블루투스 장치를 찾아 기록합니다."
        private const val PERMISSION_DENIED =
            "주변 장치를 찾으려면 근처 기기와 위치 권한이 필요합니다. 다시 찾기를 누르면 권한을 다시 요청합니다."
        private const val PERMISSION_APPROXIMATE =
            "위치가 대략적으로만 허용되어 있습니다. 블루투스 장치를 찾으려면 정확한 위치가 필요합니다. 다시 찾기를 눌러 정확한 위치를 허용해 주세요."
        private const val PERMISSION_BLOCKED =
            "권한이 거부되어 있어 다시 물을 수 없습니다. 다시 찾기를 누르면 설정 화면이 열립니다. 권한에서 근처 기기와 위치를 허용해 주세요."
        private const val PERMISSION_BLOCKED_APPROXIMATE =
            "위치가 대략적으로만 허용되어 있습니다. 다시 찾기를 누르면 설정 화면이 열립니다. 권한의 위치 항목에서 정확한 위치 사용을 켜 주세요."
        private const val OPENING_SETTINGS =
            "설정 화면을 엽니다. 권한에서 근처 기기와 위치를 허용한 뒤 돌아오세요."
        private const val PERMISSION_DIALOG_HINT =
            "곧 권한을 묻는 창이 뜹니다. 허용을 눌러 주세요."
        private const val RESTORED =
            "음향신호기 조사 화면입니다. 다시 찾기를 누르면 주변 블루투스 장치를 찾습니다."

        private const val STATE_SENT_TO_SETTINGS = "sentToSettings"
        private const val STATE_PERMISSION_BLOCKED = "permissionBlocked"
    }
}

/**
 * 안드로이드의 BLE 스캔 시작 제한(앱당 30초에 5회) 회계. 프로세스 전체에서 공유한다 —
 * 화면을 새로 열 때마다 자동으로 찾기를 시작하므로, 들락날락하면 화면 하나 안에서 세는 걸로는 부족하다.
 * 여유를 두고 4회로 센다(시스템이 세는 시작에는 우리가 모르는 재시도가 섞일 수 있다).
 */
internal object ScanBudget {
    private const val WINDOW_MS = 30_000L
    private const val MAX_STARTS = 4
    private val starts = ArrayDeque<Long>()

    /** 지금 시작해도 되면 0, 아니면 기다려야 할 ms. */
    fun waitBeforeNextStart(nowMs: Long): Long {
        while (starts.isNotEmpty() && nowMs - starts.first() >= WINDOW_MS) starts.removeFirst()
        if (starts.size < MAX_STARTS) return 0L
        return WINDOW_MS - (nowMs - starts.first())
    }

    fun recordStart(nowMs: Long) {
        starts.addLast(nowMs)
    }
}
