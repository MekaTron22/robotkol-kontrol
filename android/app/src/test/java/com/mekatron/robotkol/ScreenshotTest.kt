package com.mekatron.robotkol

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.mekatron.robotkol.ui.ArmView
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Ana ekranı gerçek Android çizim motoruyla (Robolectric native graphics) çizer ve
 * build/screenshots altına PNG kaydeder. Ayrıca kolda sürükleme davranışını doğrular.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w800dp-h380dp-land-xhdpi")
class ScreenshotTest {

    private fun snap(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun touch(v: View, action: Int, x: Float, y: Float, t: Long) =
        v.dispatchTouchEvent(MotionEvent.obtain(t, SystemClock.uptimeMillis(), action, x, y, 0))

    @Test fun mainScreen() {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = act.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(760, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1600, 760)
        snap(root, "main_home")

        // Ön kolu sürükle: yalnız dirsek değişmeli
        val arm = act.findViewById<ArmView>(R.id.armView)
        val s0 = arm.shoulder; val w0 = arm.wrist; val e0 = arm.elbow
        val loc = IntArray(2); arm.getLocationInWindow(loc)
        // ön kolun ortası: ekranda kolun konumunu ArmKinematics'ten hesaplamak yerine tarayarak bul
        var grabbed = false
        val t = SystemClock.uptimeMillis()
        loop@ for (yy in 0 until arm.height step 6) for (xx in 0 until arm.width step 6) {
            touch(arm, MotionEvent.ACTION_DOWN, xx.toFloat(), yy.toFloat(), t)
            if (arm.isDragging) {
                // dokunulan segment ön kol mu? dirseği çevirmeyi dene
                touch(arm, MotionEvent.ACTION_MOVE, xx + 120f, yy + 160f, t)
                val changedOnlyElbow = arm.shoulder == s0 && arm.wrist == w0 && arm.elbow != e0
                if (changedOnlyElbow) { grabbed = true; snap(arm, "arm_dragging_elbow"); touch(arm, MotionEvent.ACTION_UP, xx + 120f, yy + 160f, t); break@loop }
                touch(arm, MotionEvent.ACTION_CANCEL, xx.toFloat(), yy.toFloat(), t)
                arm.setPose(s0, e0, w0)
            }
        }
        assertNotEquals("ön kol sürüklenemedi", false, grabbed)
        snap(root, "main_after_drag")

        // Dirsek üst sınırda: ön kol üst kola paralel (düz uzanmış)
        arm.setPose(90, 180, 90)
        snap(arm, "arm_elbow_parallel")
        // Kalibrasyon: hepsi 90 → üst kol dik, ön kol sağa ≈90°
        arm.setPose(90, 90, 90)
        snap(arm, "arm_all_90")
    }
}
