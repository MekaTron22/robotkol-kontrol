package com.mekatron.robotkol.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import com.mekatron.robotkol.core.Protocol
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

/**
 * HC-05 ile klasik Bluetooth SPP (seri port) bağlantısı.
 * Bağlanma ve okuma arka plan iş parçacığında; geri çağrılar ana iş parçacığında.
 */
@SuppressLint("MissingPermission")  // izin MainActivity'de kontrol edilir
class BluetoothLink(private val listener: Listener) {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    interface Listener {
        fun onStateChanged(state: State, info: String?)
        fun onMessage(msg: Protocol.Message)
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile var state = State.DISCONNECTED; private set
    @Volatile private var generation = 0   // eski okuma iş parçacıklarının durum değiştirmesini engeller

    fun connect(device: BluetoothDevice) {
        disconnect()
        val gen = ++generation
        setState(State.CONNECTING, device.name)
        Thread({
            val s = openSocket(device)
            if (gen != generation) { s?.closeQuietly(); return@Thread }
            if (s == null) { setState(State.DISCONNECTED, "Bağlanılamadı: ${device.name}"); return@Thread }
            socket = s
            out = s.outputStream
            setState(State.CONNECTED, device.name)
            readLoop(s, gen)
        }, "bt-connect").start()
    }

    private fun openSocket(device: BluetoothDevice): BluetoothSocket? {
        // 1) standart güvenli soket, 2) güvensiz soket, 3) bazı HC-05/telefonlar için kanal 1 yansıması
        val attempts = listOf<() -> BluetoothSocket>(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            },
        )
        for (make in attempts) {
            var s: BluetoothSocket? = null
            try {
                s = make()
                s.connect()
                return s
            } catch (e: Exception) {
                s?.closeQuietly()
            }
        }
        return null
    }

    private fun readLoop(s: BluetoothSocket, gen: Int) {
        val parser = Protocol.Parser()
        val buf = ByteArray(256)
        try {
            val input = s.inputStream
            while (gen == generation) {
                val n = input.read(buf)
                if (n < 0) break
                val msgs = parser.feed(buf, n)
                if (msgs.isNotEmpty()) main.post { msgs.forEach(listener::onMessage) }
            }
        } catch (_: IOException) {
        }
        if (gen == generation) {
            cleanup()
            setState(State.DISCONNECTED, "Bağlantı koptu")
        }
    }

    fun send(frame: String) {
        val o = out ?: return
        val bytes = frame.toByteArray(Charsets.US_ASCII)
        writer.execute {
            try { o.write(bytes); o.flush() } catch (_: IOException) { /* okuma döngüsü kopmayı bildirir */ }
        }
    }

    fun disconnect() {
        generation++
        val wasActive = state != State.DISCONNECTED
        cleanup()
        if (wasActive) setState(State.DISCONNECTED, null)
    }

    private fun cleanup() {
        out = null
        socket?.closeQuietly()
        socket = null
    }

    private fun setState(s: State, info: String?) {
        state = s
        main.post { listener.onStateChanged(s, info) }
    }

    private fun BluetoothSocket.closeQuietly() = try { close() } catch (_: IOException) {}
}
