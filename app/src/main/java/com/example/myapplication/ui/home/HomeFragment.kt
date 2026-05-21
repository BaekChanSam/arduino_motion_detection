package com.example.myapplication.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.example.domain.ble.BleConnectionState
import com.example.entity.SensorStatus
import com.example.myapplication.R
import com.example.myapplication.databinding.FragmentHomeBinding
import com.example.myapplication.ui.base.BaseFragment
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber
import kotlin.random.Random

class HomeFragment : BaseFragment<FragmentHomeBinding>(R.layout.fragment_home) {

    private val viewModel: HomeViewModel by viewModel()

    private val handler = Handler(Looper.getMainLooper())
    private var elapsedSecs = 0
    private val elapsedTick = object : Runnable {
        override fun run() {
            _binding?.elapsed?.text = "%02d:%02d".format(elapsedSecs / 60, elapsedSecs % 60)
            elapsedSecs++
            handler.postDelayed(this, 1000)
        }
    }

    // ── 차트 더미 데이터 ─────────────────────────────────────
    private val chartMaxPoints = 60
    private val chartTickIntervalMs = 500L
    private val chartValues = ArrayDeque<Float>()
    private var chartTickIdx = 0
    private val chartTick = object : Runnable {
        override fun run() {
            appendChartPoint(generateDummyValue())
            handler.postDelayed(this, chartTickIntervalMs)
        }
    }

    private val BASELINE = 25f

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        Timber.tag("UI").d("permissionLauncher ▶ $result")
        if (result.values.all { it }) {
            Timber.tag("UI").d("all granted → connect()")
            viewModel.connect()
        } else {
            Timber.tag("UI").w("permission denied: ${result.filterValues { !it }.keys}")
            Toast.makeText(requireContext(), "권한이 필요합니다", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Timber.tag("UI").d("HomeFragment.onViewCreated")
        binding.vm = viewModel

        setupChart()
        startChartTicker()
        applyIdle()

        binding.btnConnect.setOnClickListener {
            val state = viewModel.connectionState.value
            Timber.tag("UI").d("btnConnect tap, currentState=$state")
            if (state is BleConnectionState.Connected ||
                state is BleConnectionState.Connecting ||
                state is BleConnectionState.Scanning
            ) {
                viewModel.disconnect()
            } else {
                ensurePermissionsAndConnect()
            }
        }

        binding.btnMayday.setOnClickListener {
            Timber.tag("UI").d("btnMayday tap")
            Toast.makeText(requireContext(), "119 긴급 신고 (구현 예정)", Toast.LENGTH_SHORT).show()
        }

        binding.btnVoice.setOnClickListener {
            Timber.tag("UI").d("btnVoice tap → dialer")
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:01033963251"))
            startActivity(intent)
        }

        viewModel.connectionState.observe(viewLifecycleOwner) { state ->
            Timber.tag("UI").d("connectionState observe ◀ $state")
            applyConnectionState(state)
        }
        viewModel.sensorStatus.observe(viewLifecycleOwner) { status ->
            Timber.tag("UI").d("sensorStatus observe ◀ $status")
            applySensorStatus(status)
        }
    }

    override fun onDestroyView() {
        Timber.tag("UI").d("HomeFragment.onDestroyView")
        handler.removeCallbacks(elapsedTick)
        handler.removeCallbacks(chartTick)
        super.onDestroyView()
    }

    private fun ensurePermissionsAndConnect() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) !=
                    PackageManager.PERMISSION_GRANTED
        }
        Timber.tag("UI").d("ensurePermissionsAndConnect: required=${requiredPermissions.toList()} missing=$missing")
        if (missing.isEmpty()) {
            viewModel.connect()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ─────────────────────────────────────────────────────
    // BLE state → header & connect button
    // ─────────────────────────────────────────────────────
    private fun applyConnectionState(state: BleConnectionState) {
        val (statusRes, dotRes) = when (state) {
            BleConnectionState.Idle -> R.string.sl_ble_idle to R.drawable.sl_dot_ok
            BleConnectionState.Scanning -> R.string.sl_ble_scanning to R.drawable.sl_dot_accent
            is BleConnectionState.Connecting -> R.string.sl_ble_connecting to R.drawable.sl_dot_accent
            is BleConnectionState.Connected -> R.string.sl_ble_ok to R.drawable.sl_dot_accent
            BleConnectionState.Disconnected -> R.string.sl_ble_disconnected to R.drawable.sl_dot_ok
            is BleConnectionState.Error -> R.string.sl_ble_error to R.drawable.sl_dot_critical
        }
        binding.bleStatus.setText(statusRes)
        binding.bleDot.setBackgroundResource(dotRes)

        val connected = state is BleConnectionState.Connected
        val active = connected || state is BleConnectionState.Connecting ||
                state is BleConnectionState.Scanning

        // Connect/Disconnect 토글
        binding.btnConnectLabel.setText(
            if (active) R.string.sl_btn_disconnect else R.string.sl_btn_connect
        )
        binding.btnConnectSub.setText(
            if (active) R.string.sl_btn_disconnect_sub else R.string.sl_btn_connect_sub
        )

        handler.removeCallbacks(elapsedTick)
        if (connected) {
            elapsedSecs = 0
            handler.post(elapsedTick)
            // 첫 패킷 도착 전까지 — 이벤트 없음으로 가정한 기본 UI
            applyConnectedDefault()
        } else if (state is BleConnectionState.Disconnected ||
            state is BleConnectionState.Error ||
            state is BleConnectionState.Idle
        ) {
            binding.elapsed.text = "00:00"
            applyIdle()
        }
    }

    // ─────────────────────────────────────────────────────
    // SensorStatus → scenario → 화면 전체 반영
    // ─────────────────────────────────────────────────────
    private fun applySensorStatus(s: SensorStatus?) {
        if (s == null) return
        val scenario = when {
            s.isFall -> "down"
            s.hitHead -> "head"
            s.hitLeftArm && s.hitRightArm -> "arms"
            s.hitLeftArm -> "arm_l"
            s.hitRightArm -> "arm_r"
            s.hitLeftLeg && s.hitRightLeg -> "legs"
            s.hitLeftLeg -> "leg_l"
            s.hitRightLeg -> "leg_r"
            s.isFireDetected -> "fire"
            else -> "normal"
        }
        Timber.tag("UI").d("applySensorStatus ▶ scenario=$scenario alarm=${s.isAlarm}")

        val isCritical = scenario == "down" || scenario == "head" || scenario == "fire"
        val isAlarm = s.isAlarm

        binding.headerRoot.setBackgroundResource(
            if (scenario == "down") R.drawable.sl_header_critical_bg else 0
        )
        if (scenario != "down") {
            binding.headerRoot.setBackgroundColor(col(R.color.sl_bg))
        }
        binding.avatar.setBackgroundResource(
            if (scenario == "down") R.drawable.sl_avatar_critical else R.drawable.sl_avatar
        )

        applyAlertBanner(scenario)

        val hitColor = col(R.color.sl_critical)
        tintBody(binding.bodyHead, s.hitHead, hitColor)
        tintBody(binding.bodyArmL, s.hitLeftArm, hitColor)
        tintBody(binding.bodyArmR, s.hitRightArm, hitColor)
        tintBody(binding.bodyLegL, s.hitLeftLeg, hitColor)
        tintBody(binding.bodyLegR, s.hitRightLeg, hitColor)

        bindBodyRow(binding.rowHead.root, getString(R.string.sl_body_head), s.hitHead,
            if (s.hitHead) getString(R.string.sl_status_hit) else getString(R.string.sl_status_ok))
        bindBodyRow(binding.rowArmL.root, getString(R.string.sl_body_arm_l), s.hitLeftArm,
            if (s.hitLeftArm) getString(R.string.sl_status_hit) else getString(R.string.sl_status_ok))
        bindBodyRow(binding.rowArmR.root, getString(R.string.sl_body_arm_r), s.hitRightArm,
            if (s.hitRightArm) getString(R.string.sl_status_hit) else getString(R.string.sl_status_ok))
        bindBodyRow(binding.rowLegL.root, getString(R.string.sl_body_leg_l), s.hitLeftLeg,
            if (s.hitLeftLeg) getString(R.string.sl_status_hit) else getString(R.string.sl_status_ok))
        bindBodyRow(binding.rowLegR.root, getString(R.string.sl_body_leg_r), s.hitRightLeg,
            if (s.hitRightLeg) getString(R.string.sl_status_hit) else getString(R.string.sl_status_ok))

        binding.btnMayday.visibility = if (isCritical) View.VISIBLE else View.GONE

        if (isAlarm && viewModel.connectionState.value is BleConnectionState.Connected) {
            binding.bleDot.setBackgroundResource(R.drawable.sl_dot_critical)
        }
    }

    private fun applyAlertBanner(scenario: String) {
        val (bannerBg, iconBg, iconTxt, prioRes, prioColor, labelRes, subRes) = when (scenario) {
            "normal" -> AlertBannerData(
                R.drawable.sl_alert_ok, R.drawable.sl_alert_icon_ok, "✓",
                R.string.sl_alert_status_ok, R.color.sl_ok,
                R.string.sl_label_normal, R.string.sl_sub_normal
            )
            "head" -> AlertBannerData(
                R.drawable.sl_alert_critical, R.drawable.sl_alert_icon_critical, "!",
                R.string.sl_alert_prio_1, R.color.sl_critical,
                R.string.sl_label_head, R.string.sl_sub_head
            )
            "arms" -> AlertBannerData(
                R.drawable.sl_alert_warn, R.drawable.sl_alert_icon_warn, "!",
                R.string.sl_alert_prio_2, R.color.sl_warn,
                R.string.sl_label_arms, R.string.sl_sub_arms
            )
            "arm_l" -> AlertBannerData(
                R.drawable.sl_alert_warn, R.drawable.sl_alert_icon_warn, "!",
                R.string.sl_alert_prio_2, R.color.sl_warn,
                R.string.sl_label_arm_l, R.string.sl_sub_arm_single
            )
            "arm_r" -> AlertBannerData(
                R.drawable.sl_alert_warn, R.drawable.sl_alert_icon_warn, "!",
                R.string.sl_alert_prio_2, R.color.sl_warn,
                R.string.sl_label_arm_r, R.string.sl_sub_arm_single
            )
            "legs" -> AlertBannerData(
                R.drawable.sl_alert_warn, R.drawable.sl_alert_icon_warn, "!",
                R.string.sl_alert_prio_2, R.color.sl_warn,
                R.string.sl_label_legs, R.string.sl_sub_legs
            )
            "leg_l" -> AlertBannerData(
                R.drawable.sl_alert_warn, R.drawable.sl_alert_icon_warn, "!",
                R.string.sl_alert_prio_2, R.color.sl_warn,
                R.string.sl_label_leg_l, R.string.sl_sub_leg_single
            )
            "leg_r" -> AlertBannerData(
                R.drawable.sl_alert_warn, R.drawable.sl_alert_icon_warn, "!",
                R.string.sl_alert_prio_2, R.color.sl_warn,
                R.string.sl_label_leg_r, R.string.sl_sub_leg_single
            )
            "down" -> AlertBannerData(
                R.drawable.sl_alert_critical, R.drawable.sl_alert_icon_critical, "!",
                R.string.sl_alert_prio_1, R.color.sl_critical,
                R.string.sl_label_down, R.string.sl_sub_down
            )
            "fire" -> AlertBannerData(
                R.drawable.sl_alert_critical, R.drawable.sl_alert_icon_critical, "!",
                R.string.sl_alert_prio_1, R.color.sl_critical,
                R.string.sl_label_fire, R.string.sl_sub_fire
            )
            else -> AlertBannerData(
                R.drawable.sl_alert_ok, R.drawable.sl_alert_icon_ok, "✓",
                R.string.sl_alert_status_ok, R.color.sl_ok,
                R.string.sl_label_normal, R.string.sl_sub_normal
            )
        }
        binding.alertBanner.setBackgroundResource(bannerBg)
        binding.alertIcon.setBackgroundResource(iconBg)
        binding.alertIcon.text = iconTxt
        binding.alertPrio.setText(prioRes)
        binding.alertPrio.setTextColor(col(prioColor))
        binding.alertLabel.setText(labelRes)
        binding.alertSub.setText(subRes)
    }

    private fun applyIdle() {
        binding.alertBanner.setBackgroundResource(R.drawable.sl_alert_ok)
        binding.alertIcon.setBackgroundResource(R.drawable.sl_alert_icon_ok)
        binding.alertIcon.text = "·"
        binding.alertPrio.setText(R.string.sl_alert_status_idle)
        binding.alertPrio.setTextColor(col(R.color.sl_dim))
        binding.alertLabel.setText(R.string.sl_label_idle)
        binding.alertSub.setText(R.string.sl_sub_idle)
        binding.btnMayday.visibility = View.GONE

        listOf(binding.bodyHead, binding.bodyArmL, binding.bodyArmR, binding.bodyLegL, binding.bodyLegR)
            .forEach { it.setColorFilter(col(R.color.sl_panel_2), PorterDuff.Mode.SRC_IN) }

        bindBodyRow(binding.rowHead.root, getString(R.string.sl_body_head), false, "--")
        bindBodyRow(binding.rowArmL.root, getString(R.string.sl_body_arm_l), false, "--")
        bindBodyRow(binding.rowArmR.root, getString(R.string.sl_body_arm_r), false, "--")
        bindBodyRow(binding.rowLegL.root, getString(R.string.sl_body_leg_l), false, "--")
        bindBodyRow(binding.rowLegR.root, getString(R.string.sl_body_leg_r), false, "--")
    }

    /** 연결됨, 아직 이벤트 없음 — 정상(NORMAL) 상태로 깔아둠. */
    private fun applyConnectedDefault() {
        applyAlertBanner("normal")
        binding.headerRoot.setBackgroundColor(col(R.color.sl_bg))
        binding.avatar.setBackgroundResource(R.drawable.sl_avatar)
        listOf(binding.bodyHead, binding.bodyArmL, binding.bodyArmR, binding.bodyLegL, binding.bodyLegR)
            .forEach { it.setColorFilter(col(R.color.sl_ok), PorterDuff.Mode.SRC_IN) }
        val okTxt = getString(R.string.sl_status_ok)
        bindBodyRow(binding.rowHead.root, getString(R.string.sl_body_head), false, okTxt)
        bindBodyRow(binding.rowArmL.root, getString(R.string.sl_body_arm_l), false, okTxt)
        bindBodyRow(binding.rowArmR.root, getString(R.string.sl_body_arm_r), false, okTxt)
        bindBodyRow(binding.rowLegL.root, getString(R.string.sl_body_leg_l), false, okTxt)
        bindBodyRow(binding.rowLegR.root, getString(R.string.sl_body_leg_r), false, okTxt)
        binding.btnMayday.visibility = View.GONE
    }

    private fun tintBody(view: ImageView, hit: Boolean, hitColor: Int) {
        view.setColorFilter(
            if (hit) hitColor else col(R.color.sl_ok),
            PorterDuff.Mode.SRC_IN
        )
    }

    private fun bindBodyRow(row: View, label: String, hit: Boolean, value: String) {
        row.setBackgroundResource(if (hit) R.drawable.sl_body_row_hit else R.drawable.sl_body_row_normal)
        row.findViewById<View>(R.id.dot).setBackgroundResource(
            if (hit) R.drawable.sl_dot_critical else R.drawable.sl_dot_ok
        )
        row.findViewById<TextView>(R.id.label).text = label
        val v = row.findViewById<TextView>(R.id.value)
        v.text = value
        v.setTextColor(col(if (hit) R.color.sl_critical else R.color.sl_dim))
    }

    // ─────────────────────────────────────────────────────
    // MPAndroidChart — 활동 추이 (더미 실시간 데이터)
    // ─────────────────────────────────────────────────────
    private fun setupChart() {
        val chart: LineChart = binding.activityChart
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setTouchEnabled(false)
        chart.setScaleEnabled(false)
        chart.setPinchZoom(false)
        chart.setDrawGridBackground(false)
        chart.setBackgroundColor(Color.TRANSPARENT)
        chart.setViewPortOffsets(0f, 8f, 0f, 0f)

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            setDrawAxisLine(false)
            setDrawLabels(false)
        }
        chart.axisLeft.apply {
            setDrawGridLines(true)
            gridColor = col(R.color.sl_line)
            setDrawAxisLine(false)
            setDrawLabels(false)
            axisMinimum = 0f
            axisMaximum = 100f
        }
        chart.axisRight.isEnabled = false

        chartValues.clear()
        chartTickIdx = 0
        repeat(chartMaxPoints) { chartValues.addLast(generateDummyValue()) }

        val entries = chartValues.mapIndexed { i, v -> Entry(i.toFloat(), v) }.toMutableList()
        val dataSet = LineDataSet(entries, "activity").apply {
            color = col(R.color.sl_accent)
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = 2f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillColor = col(R.color.sl_accent)
            fillAlpha = 60
            setDrawHighlightIndicators(false)
        }
        chart.data = LineData(dataSet)
        chart.invalidate()
    }

    private fun startChartTicker() {
        handler.removeCallbacks(chartTick)
        handler.postDelayed(chartTick, chartTickIntervalMs)
    }

    private fun appendChartPoint(v: Float) {
        val chart = _binding?.activityChart ?: return
        chartValues.addLast(v)
        while (chartValues.size > chartMaxPoints) chartValues.removeFirst()

        val entries = chartValues.mapIndexed { i, value -> Entry(i.toFloat(), value) }
        val dataSet = (chart.data?.getDataSetByIndex(0) as? LineDataSet) ?: return
        dataSet.values = entries

        // 시나리오 따라 라인 색 — alarm이면 빨강
        val isAlarm = viewModel.sensorStatus.value?.isAlarm == true
        dataSet.color = col(if (isAlarm) R.color.sl_critical else R.color.sl_accent)
        dataSet.fillColor = col(if (isAlarm) R.color.sl_critical else R.color.sl_accent)

        chart.data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.invalidate()
    }

    /** 평상시 일자 — 충격/낙상/화재 등 알람 상태에서는 높게 튀어오름. */
    private fun generateDummyValue(): Float {
        chartTickIdx++
        val isAlarm = viewModel.sensorStatus.value?.isAlarm == true
        return if (isAlarm) {
            val noise = (Random.nextFloat() - 0.5f) * 6f
            (85f + noise).coerceIn(5f, 95f)
        } else {
            val noise = (Random.nextFloat() - 0.5f) * 1.5f
            (BASELINE + noise).coerceIn(5f, 95f)
        }
    }

    private fun col(@ColorRes id: Int) = ContextCompat.getColor(requireContext(), id)

    private data class AlertBannerData(
        @DrawableRes val bannerBg: Int,
        @DrawableRes val iconBg: Int,
        val iconTxt: String,
        val prioRes: Int,
        @ColorRes val prioColor: Int,
        val labelRes: Int,
        val subRes: Int,
    )
}
