package fr.jmethod.app.pronote

import android.graphics.BitmapFactory
import android.util.Base64
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.json.JSONObject

/** Décodage natif (ZXing) du QR PNG produit par la page officielle. Le contenu n’est jamais journalisé. */
object QrImageDecoder {
    fun decode(dataUrl: String): JSONObject {
        if (!dataUrl.startsWith("data:image/png;base64,") || dataUrl.length >= BridgeGate.MAX_MESSAGE) {
            throw PronoteException("qr", "Impossible de lire le QR code. Relance la connexion.")
        }
        val bytes = Base64.decode(dataUrl.substringAfter(','), Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) {
            throw PronoteException("qr", "Impossible de lire le QR code. Relance la connexion.")
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw PronoteException("qr", "Impossible de lire le QR code. Relance la connexion.")
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val text = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))).text
            JSONObject(text)
        } catch (_: Exception) {
            throw PronoteException("qr", "Impossible de lire le QR code. Relance la connexion.")
        } finally {
            bitmap.recycle()
        }
    }
}
