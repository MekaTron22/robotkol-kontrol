package com.mekatron.robotkol.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mekatron.robotkol.R
import com.mekatron.robotkol.core.Joint
import com.mekatron.robotkol.core.JointLimits

/**
 * "Eklem sınırları" ayar penceresi. Her eklem için çalışma aralığı (servo derecesi) seçilir;
 * değerler donanım (mutlak) sınırlarının dışına çıkamaz.
 *
 * Canlı mod açıkken bir sınırı değiştirmek o eklemi robotta o açıya götürür ([onPreview]);
 * böylece kıskacın tam kapandığı/açıldığı nokta gözle bulunup sınır yapılabilir.
 * Kaydet → [onSave], İptal → [onCancel] (önizlemeyle oynanan kol eski duruşuna döner).
 */
class LimitsDialog(
    private val ctx: Context,
    initial: JointLimits,
    private val liveAvailable: Boolean,
    private val onPreview: (Joint, Int) -> Unit,
    private val onSave: (JointLimits) -> Unit,
    private val onCancel: () -> Unit,
) {
    private var limits = initial
    private var live = liveAvailable
    private val rows = HashMap<Joint, Row>()
    private val handler = Handler(Looper.getMainLooper())

    private fun dp(v: Float) = RoverTheme.dp(ctx, v).toInt()

    private companion object { val TR: java.util.Locale = java.util.Locale.forLanguageTag("tr") }

    private inner class Row(val j: Joint) {
        lateinit var minText: TextView
        lateinit var maxText: TextView
        lateinit var bar: RangeBar
        lateinit var resetBtn: Button
        fun refresh() {
            minText.text = "${limits.min(j)}°"
            maxText.text = "${limits.max(j)}°"
            bar.lo = limits.min(j); bar.hi = limits.max(j); bar.invalidate()
            resetBtn.visibility = if (limits.isDefault(j)) View.INVISIBLE else View.VISIBLE
        }
    }

    fun show() {
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(4f), dp(16f), dp(8f))
        }
        content.addView(TextView(ctx).apply {
            text = "Servo derecesi. Gri çubuk donanım sınırı, turuncu kısım kolun çalışacağı aralık."
            setTextColor(RoverTheme.TEXT_DIM); textSize = 12f
            setPadding(0, 0, 0, dp(6f))
        })
        content.addView(CheckBox(ctx).apply {
            text = if (liveAvailable) "Canlı: değiştirdiğim sınıra eklemi götür" else "Canlı önizleme (bağlıyken kullanılabilir)"
            isChecked = live; isEnabled = liveAvailable
            setTextColor(RoverTheme.TEXT); textSize = 13f
            buttonTintList = android.content.res.ColorStateList.valueOf(RoverTheme.ORANGE)
            setOnCheckedChangeListener { _, v -> live = v }
        })
        for (j in Joint.values()) content.addView(buildRow(j))

        val dlg = AlertDialog.Builder(ctx)
            .setTitle("Eklem sınırları")
            .setView(ScrollView(ctx).apply { addView(content) })
            .setPositiveButton("Kaydet") { _, _ -> onSave(limits) }
            .setNeutralButton("Tümü varsayılan", null)
            .setNegativeButton("İptal") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .create()
        dlg.setOnShowListener {
            dlg.window?.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                setColor(RoverTheme.PANEL); cornerRadius = RoverTheme.dp(ctx, 20f)
            })
            for (b in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL))
                dlg.getButton(b)?.setTextColor(if (b == AlertDialog.BUTTON_POSITIVE) RoverTheme.ORANGE else RoverTheme.TEXT_DIM)
            // pencereyi kapatmadan hepsini sıfırla
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                limits = JointLimits.DEFAULT
                rows.values.forEach { it.refresh() }
            }
        }
        dlg.setOnDismissListener { handler.removeCallbacksAndMessages(null) }
        dlg.show()
    }

    /** Tek satır: [AD / donanım]  MIN − v +  [aralık çubuğu]  − v + MAKS  [↺] */
    private fun buildRow(j: Joint): View {
        val row = Row(j); rows[j] = row
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_status)
            setPadding(dp(12f), dp(6f), dp(8f), dp(6f))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) }
        }
        box.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(84f), -2)
            addView(TextView(ctx).apply {
                text = j.label.uppercase(TR); setTextColor(RoverTheme.TEXT); textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(ctx).apply {
                text = "donanım ${j.servoMin}–${j.servoMax}°"; setTextColor(RoverTheme.TEXT_FAINT); textSize = 10f
            })
        })
        row.minText = valueText(); row.maxText = valueText()
        box.addView(caption("MIN"))
        box.addView(stepButton("−") { change(j, isMin = true, delta = -1) })
        box.addView(row.minText.also { t -> t.setOnClickListener { typeValue(j, isMin = true) } })
        box.addView(stepButton("+") { change(j, isMin = true, delta = 1) })
        row.bar = RangeBar(ctx, j.servoMin, j.servoMax).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(18f), 1f).apply { marginStart = dp(8f); marginEnd = dp(8f) }
        }
        box.addView(row.bar)
        box.addView(stepButton("−") { change(j, isMin = false, delta = -1) })
        box.addView(row.maxText.also { t -> t.setOnClickListener { typeValue(j, isMin = false) } })
        box.addView(stepButton("+") { change(j, isMin = false, delta = 1) })
        box.addView(caption("MAKS").apply { setPadding(dp(6f), 0, 0, 0) })
        row.resetBtn = smallButton("↺") {
            limits = limits.with(j, j.servoMin, j.servoMax); row.refresh()
        }.apply { contentDescription = "${j.label} varsayılan" }
        box.addView(row.resetBtn)

        row.refresh()
        return box
    }

    /** Bir sınırı değiştirir; diğer uçla çakışmaz, donanım sınırını aşmaz. */
    private fun change(j: Joint, isMin: Boolean, delta: Int = 0, absolute: Int? = null) {
        val lo = limits.min(j); val hi = limits.max(j)
        if (isMin) {
            val v = (absolute ?: (lo + delta)).coerceIn(j.servoMin, hi - 1)
            if (v == lo) return
            limits = limits.with(j, v, hi)
        } else {
            val v = (absolute ?: (hi + delta)).coerceIn(lo + 1, j.servoMax)
            if (v == hi) return
            limits = limits.with(j, lo, v)
        }
        rows[j]?.refresh()
        if (live) onPreview(j, if (isMin) limits.min(j) else limits.max(j))
    }

    private fun typeValue(j: Joint, isMin: Boolean) {
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((if (isMin) limits.min(j) else limits.max(j)).toString()); selectAll()
        }
        AlertDialog.Builder(ctx)
            .setTitle("${j.label} ${if (isMin) "MIN" else "MAKS"} (${j.servoMin}–${j.servoMax}°)")
            .setView(input)
            .setPositiveButton("Tamam") { _, _ ->
                input.text.toString().trim().toIntOrNull()?.let { change(j, isMin, absolute = it) }
            }
            .setNegativeButton("İptal", null).show()
    }

    // ---- küçük görünüm yardımcıları ----
    private fun caption(s: String) = TextView(ctx).apply {
        text = s; setTextColor(RoverTheme.TEXT_DIM); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, dp(6f), 0)
    }

    private fun valueText() = TextView(ctx).apply {
        setTextColor(RoverTheme.ORANGE_LIGHT); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER; minWidth = dp(48f)
        setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
    }

    private fun smallButton(s: String, onClick: () -> Unit) = Button(ctx, null, 0, R.style.BarButton).apply {
        text = s; textSize = 16f
        layoutParams = LinearLayout.LayoutParams(dp(40f), dp(36f)).apply { marginStart = dp(6f) }
        setPadding(0, 0, 0, 0); minWidth = 0; minimumWidth = 0
        setOnClickListener { onClick() }
    }

    /** −/+ düğmesi: dokun = 1 adım, basılı tut = sürekli (≈14 adım/s). */
    @SuppressLint("ClickableViewAccessibility")
    private fun stepButton(s: String, step: () -> Unit) = Button(ctx, null, 0, R.style.BarButton).apply {
        text = s; textSize = 18f
        layoutParams = LinearLayout.LayoutParams(dp(42f), dp(40f)).apply { marginStart = dp(2f); marginEnd = dp(2f) }
        setPadding(0, 0, 0, 0); minWidth = 0; minimumWidth = 0
        val repeat = object : Runnable {
            override fun run() { step(); handler.postDelayed(this, 70) }
        }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; step(); handler.postDelayed(repeat, 400) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; handler.removeCallbacks(repeat) }
            }
            true
        }
    }

    /** Donanım aralığı (gri) içinde seçili çalışma aralığını (turuncu) gösteren ince çubuk. */
    private class RangeBar(ctx: Context, val hardMin: Int, val hardMax: Int) : View(ctx) {
        var lo = hardMin
        var hi = hardMax
        private val track = RoverTheme.fill(RoverTheme.PANEL_2)
        private val sel = RoverTheme.fill(RoverTheme.ORANGE)
        private val r = RoverTheme.dp(ctx, 4f)

        override fun onDraw(c: Canvas) {
            val h = RoverTheme.dp(context, 8f); val y = height / 2f
            val w = width.toFloat()
            c.drawRoundRect(0f, y - h / 2, w, y + h / 2, r, r, track)
            val span = (hardMax - hardMin).coerceAtLeast(1).toFloat()
            val x0 = (lo - hardMin) / span * w; val x1 = (hi - hardMin) / span * w
            c.drawRoundRect(x0, y - h / 2, x1.coerceAtLeast(x0 + h), y + h / 2, r, r, sel)
        }
    }
}
