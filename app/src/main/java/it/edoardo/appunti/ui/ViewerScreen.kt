package it.edoardo.appunti.ui

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.provider.MediaStore
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import it.edoardo.appunti.app
import it.edoardo.appunti.data.ClaudeResponse
import it.edoardo.appunti.data.file
import it.edoardo.appunti.share.ResponseCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import it.edoardo.appunti.data.PromptKind
import it.edoardo.appunti.share.ClaudeShare
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Che cosa mostra il visualizzatore per una risposta salvata. */
enum class ViewKind { MAP, HTML, IMAGE, PDF }

/** Che cosa si apre a schermo intero: l'immagine/PDF se c'è, altrimenti la pagina o la mappa disegnata. */
fun viewKindOf(r: ClaudeResponse): ViewKind? {
    r.file()?.let { return if (it.lowercase().endsWith(".pdf")) ViewKind.PDF else ViewKind.IMAGE }
    if (r.kind == PromptKind.INFOGRAPHIC.key && ResponseCleaner.extractHtml(r.text) != null) return ViewKind.HTML
    if (r.kind == PromptKind.MINDMAP.key && MindMap.looksLikeMap(r.text)) return ViewKind.MAP
    return null
}

/** Va nella scheda Grafica: mappe, infografiche, immagini e PDF. */
fun isVisual(r: ClaudeResponse): Boolean =
    r.file() != null || r.kind == PromptKind.MINDMAP.key || r.kind == PromptKind.INFOGRAPHIC.key

/** Mappa concettuale, infografica o immagine a schermo intero, ingrandibile e salvabile. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(responseId: Long, forceMap: Boolean = false, onBack: () -> Unit) {
    val context = LocalContext.current
    var response by remember { mutableStateOf<ClaudeResponse?>(null) }
    var title by remember { mutableStateOf("") }
    LaunchedEffect(responseId) {
        val r = context.app.db.notes().getResponse(responseId)
        response = r
        title = r?.let { context.app.db.notes().get(it.noteId)?.title } ?: ""
    }
    var webView by remember { mutableStateOf<WebView?>(null) }

    val r = response
    val kind = r?.let { if (forceMap) ViewKind.MAP else viewKindOf(it) }
    val label = when (kind) {
        ViewKind.MAP -> "Mappa"
        ViewKind.HTML -> "Infografica"
        ViewKind.IMAGE -> "Immagine"
        ViewKind.PDF -> "PDF"
        null -> ""
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(if (title.isBlank()) label else "$label · $title", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") }
                },
                actions = {
                    IconButton(onClick = {
                        val file = when (kind) {
                            ViewKind.IMAGE, ViewKind.PDF -> r?.file()?.let { File(it) }
                            else -> webView?.let { capture(context, it, label) }
                        }
                        // Si condividono insieme immagine e testo (se la scheda ha anche il testo).
                        val text = r?.let { rr -> rr.text.takeIf { it.isNotBlank() && rr.file() != null && ResponseCleaner.extractHtml(it) == null } }
                        if (file != null) ClaudeShare.shareFileWithText(context, file, text, "$label – $title")
                        else Toast.makeText(context, "Immagine non pronta, riprova tra un attimo", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Default.Share, contentDescription = "Condividi") }
                    IconButton(onClick = {
                        val file = when (kind) {
                            ViewKind.IMAGE, ViewKind.PDF -> r?.file()?.let { File(it) }
                            else -> webView?.let { capture(context, it, label) }
                        }
                        if (file != null) saveToDevice(context, file) else
                            Toast.makeText(context, "Immagine non pronta, riprova tra un attimo", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Default.Download, contentDescription = "Salva sul telefono") }
                },
            )
        },
    ) { padding ->
        if (r == null || kind == null) {
            Text("Contenuto non disponibile", Modifier.padding(padding))
            return@Scaffold
        }
        var pdfPages by remember(r.id) { mutableStateOf<List<File>?>(null) }
        if (kind == ViewKind.PDF) LaunchedEffect(r.id) {
            pdfPages = withContext(Dispatchers.IO) { PdfPages.renderAll(context, r.file() ?: "") }
        }
        val html = remember(r.id, pdfPages) {
            when (kind) {
                ViewKind.PDF -> {
                    val pages = pdfPages
                    if (pages == null) "<html><body style='font-family:sans-serif;padding:24px'>Apertura del PDF…</body></html>"
                    else """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, user-scalable=yes">
<style>body{margin:0;background:#E9EBF3}img{display:block;width:100%;margin:0 0 8px 0;background:#fff}</style></head><body>""" +
                        pages.joinToString("") { """<img src="file://${it.absolutePath}">""" } + "</body></html>"
                }
                ViewKind.MAP -> MindMap.html(r.text, title.ifBlank { "Mappa" })
                ViewKind.HTML -> r.text
                ViewKind.IMAGE -> """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, user-scalable=yes">
<style>body{margin:0;background:#000;display:flex;align-items:center;justify-content:center;min-height:100vh}img{max-width:100%}</style>
</head><body><img src="file://${r.file()}"></body></html>"""
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { ctx -> createWebView(ctx).also { webView = it } },
            update = { wv ->
                if (wv.tag != html) {
                    wv.tag = html
                    wv.loadDataWithBaseURL("file:///", html, "text/html", "utf-8", null)
                }
            },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(ctx: Context) = WebView(ctx).apply {
    webViewClient = WebViewClient()
    settings.javaScriptEnabled = true // le infografiche di Claude possono usare piccoli script
    settings.allowFileAccess = true   // per mostrare le immagini salvate nell'app
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
}

/** Disegna tutta la pagina (non solo la parte visibile) in un PNG. */
@Suppress("DEPRECATION")
private fun capture(context: Context, wv: WebView, label: String): File? {
    val w = wv.width
    if (w <= 0 || wv.contentHeight <= 0) return null
    var h = (wv.contentHeight * wv.scale).toInt().coerceAtLeast(wv.height)
    h = h.coerceAtMost(14_000)
    return try {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(android.graphics.Color.WHITE)
        val sx = wv.scrollX
        val sy = wv.scrollY
        wv.scrollTo(0, 0)
        wv.draw(canvas)
        wv.scrollTo(sx, sy)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val file = File(ClaudeShare.shareDir(context), "MeNoter-${label.lowercase()}-$stamp.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        file
    } catch (e: Throwable) {
        null
    }
}

/** Immagini nella galleria (Immagini › MeNoter), PDF nei Download (Download › MeNoter). */
private fun saveToDevice(context: Context, file: File) {
    val isPdf = file.extension.lowercase() == "pdf"
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        ClaudeShare.shareFile(context, file, if (isPdf) "application/pdf" else "image/*", file.nameWithoutExtension)
        return
    }
    val mime = when (file.extension.lowercase()) {
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, if (isPdf) "Download/MeNoter" else "Pictures/MeNoter")
    }
    val collection = if (isPdf) MediaStore.Downloads.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val uri = context.contentResolver.insert(collection, values)
    if (uri == null) {
        Toast.makeText(context, "Salvataggio non riuscito", Toast.LENGTH_SHORT).show()
        return
    }
    context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
    Toast.makeText(context, if (isPdf) "Salvato in Download › MeNoter" else "Salvata in Immagini › MeNoter", Toast.LENGTH_LONG).show()
}
