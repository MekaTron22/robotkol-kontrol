package com.mekatron.robotkol

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.mekatron.robotkol.bt.BluetoothLink
import com.mekatron.robotkol.core.ArmKinematics.Segment
import com.mekatron.robotkol.core.Joint
import com.mekatron.robotkol.core.JointMapping
import com.mekatron.robotkol.core.NamedPose
import com.mekatron.robotkol.core.PoseLibrary
import com.mekatron.robotkol.core.Protocol
import com.mekatron.robotkol.ui.ArmView
import com.mekatron.robotkol.ui.DriveSliderView
import com.mekatron.robotkol.ui.HSliderView
import com.mekatron.robotkol.ui.RootWheelView
import com.mekatron.robotkol.ui.RoverTheme

class MainActivity : Activity(), BluetoothLink.Listener {

    private lateinit var prefs: SharedPreferences
    private lateinit var link: BluetoothLink

    private lateinit var driveLeft: DriveSliderView
    private lateinit var driveRight: DriveSliderView
    private lateinit var rootWheel: RootWheelView
    private lateinit var gripSlider: HSliderView
    private lateinit var rollSlider: HSliderView
    private lateinit var armView: ArmView
    private lateinit var txtStatus: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnSpeed: Button
    private lateinit var btnLight: Button

    /** Arayüz açıları (servo yön terslemesi uygulanmadan önce) */
    private val uiPose = PoseLibrary.BUILT_IN[0].angles.copyOf()
    private var mapping = JointMapping()
    private var invertLeftMotor = false
    private var invertRightMotor = false

    private var poseDirty = true
    private var speedDirty = true
    private var lightOn = false
    private var speedIndex = 1
    private val speeds = intArrayOf(45, 90, 160)
    private val speedNames = arrayOf("Yavaş", "Normal", "Hızlı")

    private var lastDriveL = 0
    private var lastDriveR = 0
    private var lastDriveMs = 0L
    private var lastPoseMs = 0L
    private var lastAnyMs = 0L
    private var syncedFromRover = false
    private var slaveOk = false
    private var lastTelemetryMs = 0L
    private var deviceName: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { pump(); handler.postDelayed(this, 50) }
    }

    companion object {
        private const val REQ_BT = 1
        private const val REQ_ENABLE = 2
        private const val PREF_INVERT = "invert"
        private const val PREF_POSES = "poses"
        private const val PREF_LAST_DEVICE = "lastDevice"
        private const val PREF_SPEED = "speed"
    }

    // ------------------------------------------------------------------ yaşam döngüsü
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("rover", Context.MODE_PRIVATE)
        link = BluetoothLink(this)

        driveLeft = findViewById(R.id.driveLeft)
        driveRight = findViewById(R.id.driveRight)
        rootWheel = findViewById(R.id.rootWheel)
        gripSlider = findViewById(R.id.gripSlider)
        rollSlider = findViewById(R.id.rollSlider)
        armView = findViewById(R.id.armView)
        txtStatus = findViewById(R.id.txtStatus)
        btnConnect = findViewById(R.id.btnConnect)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnLight = findViewById(R.id.btnLight)

        driveLeft.label = "SOL DC"
        driveRight.label = "SAĞ DC"
        gripSlider.apply { label = "KISKAÇ"; leftText = "açık"; rightText = "kapalı" }
        rollSlider.apply { label = "ROTASYON"; leftText = "sol"; rightText = "sağ"; centerValue = 90; fillFromCenter = true }

        speedIndex = prefs.getInt(PREF_SPEED, 1).coerceIn(0, 2)
        loadInversion()

        rootWheel.onAngleChanged = { setJoint(Joint.ROOT, it) }
        gripSlider.onValueChanged = { setJoint(Joint.GRIP, it) }
        rollSlider.onValueChanged = { setJoint(Joint.WRIST_ROLL, it) }
        armView.onArmChanged = { s, e, w ->
            uiPose[Joint.SHOULDER.ordinal] = s
            uiPose[Joint.ELBOW.ordinal] = e
            uiPose[Joint.WRIST_PITCH.ordinal] = w
            poseDirty = true
        }

        btnConnect.setOnClickListener { onConnectClicked() }
        btnSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % speeds.size
            prefs.edit().putInt(PREF_SPEED, speedIndex).apply()
            speedDirty = true; updateButtons()
        }
        btnLight.setOnClickListener { lightOn = !lightOn; send(Protocol.light(lightOn)); updateButtons() }
        findViewById<Button>(R.id.btnHome).setOnClickListener { applyServoPose(PoseLibrary.BUILT_IN[0].angles) }
        findViewById<Button>(R.id.btnPoses).setOnClickListener { showPoses() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { emergencyStop() }

        refreshViewsFromPose()
        updateButtons()
        updateStatus()
    }

    override fun onResume() { super.onResume(); handler.post(tick) }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        // Uygulama arka plana giderse rover durmalı
        if (link.state == BluetoothLink.State.CONNECTED) send(Protocol.drive(0, 0))
        driveLeft.reset(); driveRight.reset()
    }

    override fun onDestroy() { link.disconnect(); super.onDestroy() }

    // ------------------------------------------------------------------ gönderim döngüsü (20 Hz)
    private fun pump() {
        if (link.state != BluetoothLink.State.CONNECTED) return
        val now = SystemClock.uptimeMillis()

        var l = driveLeft.value; var r = driveRight.value
        if (invertLeftMotor) l = -l
        if (invertRightMotor) r = -r
        val driveChanged = l != lastDriveL || r != lastDriveR
        val driving = l != 0 || r != 0
        if (driveChanged || (driving && now - lastDriveMs >= 100)) {
            send(Protocol.drive(l, r)); lastDriveL = l; lastDriveR = r; lastDriveMs = now
        }
        if (speedDirty) { send(Protocol.speed(speeds[speedIndex])); speedDirty = false }
        if (poseDirty && now - lastPoseMs >= 60) {
            send(Protocol.pose(mapping.toServoPose(uiPose))); poseDirty = false; lastPoseMs = now
        }
        if (now - lastAnyMs >= 200) send(Protocol.heartbeat())

        if (slaveOk && now - lastTelemetryMs > 1500) { slaveOk = false; updateStatus() }
    }

    private fun send(frame: String) {
        if (link.state != BluetoothLink.State.CONNECTED) return
        link.send(frame)
        lastAnyMs = SystemClock.uptimeMillis()
    }

    // ------------------------------------------------------------------ kol durumu
    private fun setJoint(j: Joint, v: Int) {
        uiPose[j.ordinal] = mapping.clampUi(j, v)
        if (j == Joint.GRIP) armView.gripOpen = gripOpenFraction()
        if (j == Joint.WRIST_ROLL) armView.wristRoll = v
        poseDirty = true
    }

    private fun gripOpenFraction(): Float {
        val lo = mapping.uiMin(Joint.GRIP); val hi = mapping.uiMax(Joint.GRIP)
        return 1f - (uiPose[Joint.GRIP.ordinal] - lo).toFloat() / (hi - lo).coerceAtLeast(1)
    }

    private fun refreshViewsFromPose() {
        rootWheel.minAngle = mapping.uiMin(Joint.ROOT); rootWheel.maxAngle = mapping.uiMax(Joint.ROOT)
        gripSlider.min = mapping.uiMin(Joint.GRIP); gripSlider.max = mapping.uiMax(Joint.GRIP)
        gripSlider.centerValue = (gripSlider.min + gripSlider.max) / 2
        rollSlider.min = mapping.uiMin(Joint.WRIST_ROLL); rollSlider.max = mapping.uiMax(Joint.WRIST_ROLL)
        armView.setLimits(Segment.UPPER, mapping.uiMin(Joint.SHOULDER), mapping.uiMax(Joint.SHOULDER))
        armView.setLimits(Segment.FORE, mapping.uiMin(Joint.ELBOW), mapping.uiMax(Joint.ELBOW))
        armView.setLimits(Segment.HAND, mapping.uiMin(Joint.WRIST_PITCH), mapping.uiMax(Joint.WRIST_PITCH))

        for (j in Joint.values()) uiPose[j.ordinal] = mapping.clampUi(j, uiPose[j.ordinal])
        rootWheel.setAngle(uiPose[Joint.ROOT.ordinal])
        gripSlider.setValue(uiPose[Joint.GRIP.ordinal])
        rollSlider.setValue(uiPose[Joint.WRIST_ROLL.ordinal])
        armView.setPose(uiPose[Joint.SHOULDER.ordinal], uiPose[Joint.ELBOW.ordinal], uiPose[Joint.WRIST_PITCH.ordinal])
        armView.gripOpen = gripOpenFraction()
        armView.wristRoll = uiPose[Joint.WRIST_ROLL.ordinal]
    }

    /** Servo açılarıyla verilmiş bir duruşu uygular (rampayı slave yapar) */
    private fun applyServoPose(servo: IntArray) {
        for (j in Joint.values()) uiPose[j.ordinal] = mapping.fromServo(j, servo[j.ordinal])
        refreshViewsFromPose()
        poseDirty = true
    }

    private fun emergencyStop() {
        driveLeft.reset(); driveRight.reset()
        send(Protocol.emergencyStop())
        lastDriveL = 0; lastDriveR = 0
        poseDirty = false
        // Arayüzü servoların gerçek konumuna eşitlemek için bir sonraki telemetriyi bekle
        syncedFromRover = false
        Toast.makeText(this, "ACİL DUR: motorlar durdu, kol yerinde kilitlendi", Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ Bluetooth
    private fun hasBtPermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun adapter(): BluetoothAdapter? = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    @SuppressLint("MissingPermission")  // hasBtPermission() ile kontrol edildi
    private fun onConnectClicked() {
        if (link.state != BluetoothLink.State.DISCONNECTED) { link.disconnect(); return }
        if (!hasBtPermission()) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT); return
        }
        val a = adapter()
        if (a == null) { toast("Bu cihazda Bluetooth yok"); return }
        if (!a.isEnabled) { startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE); return }
        showDevicePicker(a)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) onConnectClicked()
            else toast("Bluetooth izni olmadan bağlanılamaz")
        }
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ENABLE && resultCode == RESULT_OK) onConnectClicked()
    }

    @SuppressLint("MissingPermission")
    private fun showDevicePicker(a: BluetoothAdapter) {
        val last = prefs.getString(PREF_LAST_DEVICE, null)
        val devices = a.bondedDevices.orEmpty().sortedWith(
            compareByDescending<BluetoothDevice> { it.address == last }
                .thenByDescending { (it.name ?: "").contains("HC", ignoreCase = true) }
                .thenBy { it.name ?: "" }
        )
        if (devices.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Eşleşmiş cihaz yok")
                .setMessage("Önce telefonun Bluetooth ayarlarından HC-05'i eşleştirin (PIN genelde 1234 veya 0000).")
                .setPositiveButton("Ayarları aç") { _, _ -> startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton("Kapat", null).show()
            return
        }
        val names = devices.map { "${it.name ?: "?"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("HC-05 seçin")
            .setItems(names) { _, i ->
                prefs.edit().putString(PREF_LAST_DEVICE, devices[i].address).apply()
                link.connect(devices[i])
            }
            .setNegativeButton("İptal", null).show()
    }

    override fun onStateChanged(state: BluetoothLink.State, info: String?) {
        when (state) {
            BluetoothLink.State.CONNECTED -> {
                deviceName = info
                syncedFromRover = false
                speedDirty = true
                send(Protocol.hello())
                send(Protocol.light(lightOn))
            }
            BluetoothLink.State.DISCONNECTED -> {
                slaveOk = false
                driveLeft.reset(); driveRight.reset()
                if (info != null) toast(info)
            }
            BluetoothLink.State.CONNECTING -> deviceName = info
        }
        updateButtons(); updateStatus()
    }

    override fun onMessage(msg: Protocol.Message) {
        when (msg) {
            is Protocol.Message.Telemetry -> {
                lastTelemetryMs = SystemClock.uptimeMillis()
                val changed = slaveOk != msg.slaveOk
                slaveOk = msg.slaveOk
                // Bağlanınca kol çizimini gerçek servo konumuna eşitle (kullanıcı sürüklemiyorsa)
                if (!syncedFromRover && msg.slaveOk && !armView.isDragging && !poseDirty) {
                    applyServoPose(msg.pose.toArray()); poseDirty = false; syncedFromRover = true
                }
                if (changed) updateStatus()
            }
            is Protocol.Message.Hello -> updateStatus()
        }
    }

    // ------------------------------------------------------------------ arayüz yardımcıları
    private fun updateButtons() {
        btnConnect.text = if (link.state == BluetoothLink.State.DISCONNECTED) getString(R.string.connect) else getString(R.string.disconnect)
        btnSpeed.text = "Hız: ${speedNames[speedIndex]}"
        btnLight.text = if (lightOn) "Far ● açık" else getString(R.string.light)
        btnLight.setTextColor(if (lightOn) RoverTheme.ORANGE_LIGHT else RoverTheme.TEXT)
    }

    private fun updateStatus() {
        val s = when (link.state) {
            BluetoothLink.State.DISCONNECTED -> "● Bağlı değil"
            BluetoothLink.State.CONNECTING -> "● Bağlanıyor: ${deviceName ?: ""}"
            BluetoothLink.State.CONNECTED -> "● ${deviceName ?: "HC-05"}  ·  " + if (slaveOk) "kol hazır" else "kol yanıt vermiyor"
        }
        txtStatus.text = s
        txtStatus.setTextColor(
            when {
                link.state != BluetoothLink.State.CONNECTED -> RoverTheme.TEXT_DIM
                slaveOk -> RoverTheme.GREEN
                else -> RoverTheme.ORANGE
            }
        )
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ duruşlar
    private fun userPoses() = PoseLibrary.decodeAll(prefs.getString(PREF_POSES, null))

    private fun currentServoAngles() = mapping.toServoPose(uiPose).toArray()

    private fun showPoses() {
        val all = PoseLibrary.BUILT_IN + userPoses()
        val labels = all.map { p ->
            val tag = if (p.builtIn) "" else "  ★"
            "${p.name}$tag\n" + Joint.values().joinToString("  ") { "${it.label.take(3)} ${p.angles[it.ordinal]}" }
        }.toTypedArray()
        val dlg = AlertDialog.Builder(this)
            .setTitle("Duruşlar (servo açıları)")
            .setItems(labels) { _, i -> applyServoPose(all[i].angles); toast("${all[i].name} uygulanıyor") }
            .setPositiveButton("Mevcut duruşu kaydet") { _, _ -> saveCurrentPose() }
            .setNeutralButton("Şu anki açılar") { _, _ ->
                val a = currentServoAngles()
                AlertDialog.Builder(this).setTitle("Mevcut servo açıları")
                    .setMessage(Joint.values().joinToString("\n") { "${it.label}: ${a[it.ordinal]}°" } +
                        "\n\n(A2 omuz servosu = 180 − Omuz)")
                    .setPositiveButton("Tamam", null).show()
            }
            .setNegativeButton("Kapat", null)
            .create()
        dlg.setOnShowListener {
            // Uzun basış: kayıtlı (★) duruşu sil
            dlg.listView.setOnItemLongClickListener { _, _, i, _ ->
                val p = all[i]
                if (p.builtIn) { toast("Hazır duruş silinemez"); return@setOnItemLongClickListener true }
                val rest = userPoses().filterNot { it == p }
                prefs.edit().putString(PREF_POSES, PoseLibrary.encodeAll(rest)).apply()
                toast("${p.name} silindi"); dlg.dismiss(); true
            }
        }
        dlg.show()
    }

    private fun saveCurrentPose() {
        val input = EditText(this).apply { hint = "Duruş adı"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("Duruşu kaydet").setView(input)
            .setPositiveButton("Kaydet") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Duruş ${userPoses().size + 1}" }
                val list = userPoses() + NamedPose(name, currentServoAngles())
                prefs.edit().putString(PREF_POSES, PoseLibrary.encodeAll(list)).apply()
                toast("$name kaydedildi")
            }
            .setNegativeButton("İptal", null).show()
    }

    // ------------------------------------------------------------------ ayarlar (yön terslemesi)
    private val invertLabels = Joint.values().map { "${it.label} servosu ters" } + listOf("Sol motor ters", "Sağ motor ters")

    private fun loadInversion() {
        val bits = prefs.getInt(PREF_INVERT, 0)
        mapping = JointMapping(Joint.values().filter { bits and (1 shl it.ordinal) != 0 }.toSet())
        invertLeftMotor = bits and (1 shl Joint.COUNT) != 0
        invertRightMotor = bits and (1 shl (Joint.COUNT + 1)) != 0
    }

    private fun showSettings() {
        val servoBefore = currentServoAngles()
        val bits = prefs.getInt(PREF_INVERT, 0)
        val checked = BooleanArray(invertLabels.size) { bits and (1 shl it) != 0 }
        AlertDialog.Builder(this)
            .setTitle("Yön ayarları")
            .setMultiChoiceItems(invertLabels.toTypedArray(), checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("Kaydet") { _, _ ->
                var nb = 0
                checked.forEachIndexed { i, v -> if (v) nb = nb or (1 shl i) }
                prefs.edit().putInt(PREF_INVERT, nb).apply()
                loadInversion()
                // servolar yerinde kalsın; yalnız arayüz yönü değişsin
                applyServoPose(servoBefore)
                poseDirty = false
            }
            .setNegativeButton("İptal", null).show()
    }
}
