package it.edoardo.appunti.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/** Pagine di un PDF trasformate in immagini con il lettore PDF integrato in Android. */
object PdfPages {

    /** Prima pagina come immagine larga [width] pixel, per l'anteprima nella nota. */
    fun firstPage(path: String, width: Int = 1080): Bitmap? = try {
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r -> if (r.pageCount > 0) render(r, 0, width) else null }
        }
    } catch (e: Throwable) {
        null
    }

    /** Tutte le pagine (al massimo [maxPages]) salvate come PNG nella cache, per il visualizzatore. */
    fun renderAll(context: Context, path: String, width: Int = 1600, maxPages: Int = 30): List<File> = try {
        val dir = File(context.cacheDir, "pdf/${File(path).nameWithoutExtension}").apply { mkdirs() }
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                (0 until minOf(r.pageCount, maxPages)).map { i ->
                    val out = File(dir, "p$i.png")
                    if (!out.exists()) {
                        val bmp = render(r, i, width)
                        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bmp.recycle()
                    }
                    out
                }
            }
        }
    } catch (e: Throwable) {
        emptyList()
    }

    private fun render(r: PdfRenderer, index: Int, width: Int): Bitmap {
        r.openPage(index).use { page ->
            val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        }
    }
}
