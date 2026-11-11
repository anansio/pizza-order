package io.github.kaerbr.pizzaorder

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * The QR code the admin holds up to a guest's phone, as a plain matrix so it can be drawn by Compose and
 * checked in a JVM test.
 *
 * No Android and no bitmap: the panel draws squares, and a test can decode what it drew.
 */
object GuestQr {

    /** Quiet zone in modules. Four is what the QR spec asks for; less and scanners fail at the edges. */
    const val QUIET_ZONE = 4

    /**
     * [text] as square modules, quiet zone included: `false` is background.
     *
     * @param modules the wanted width in modules. The size that comes back depends on the QR version the
     *   text needs plus the quiet zone, so callers scale whatever they get instead of assuming a width.
     */
    fun matrix(text: String, modules: Int = 33): Array<BooleanArray> {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            // The quiet zone is added below, in one place, so the size maths stays in this file.
            EncodeHintType.MARGIN to 0,
        )
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, modules, modules, hints)
        val body = Array(bits.height) { y -> BooleanArray(bits.width) { x -> bits.get(x, y) } }
        return quietZone(body, QUIET_ZONE)
    }

    /**
     * Wraps [code] in [width] modules of background. A scanner needs that margin to find the code at all —
     * right up against the card's edge or the screen's bezel it looks like noise.
     */
    private fun quietZone(code: Array<BooleanArray>, width: Int): Array<BooleanArray> {
        val size = code.size + 2 * width
        val frame = Array(size) { BooleanArray(size) }
        code.forEachIndexed { y, row ->
            row.copyInto(frame[y + width], width)
        }
        return frame
    }
}
