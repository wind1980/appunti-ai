package it.edoardo.appunti.whisper

import it.edoardo.appunti.data.Segment

/** Ricevuto dal codice nativo durante la trascrizione. */
interface TranscribeListener {
    fun onProgress(percent: Int)
    fun isCancelled(): Boolean
}

/** Funzioni native definite in app/src/main/cpp/whisper_jni.cpp. */
object WhisperNative {
    init {
        System.loadLibrary("appunti_whisper")
    }

    @JvmStatic external fun initContext(modelPath: String): Long
    @JvmStatic external fun freeContext(ptr: Long)
    @JvmStatic external fun systemInfo(): String

    /** Interrompe subito la trascrizione in corso (la chiamata a [transcribe] restituisce null). */
    @JvmStatic external fun cancel()

    /** Restituisce righe UTF-8 "inizioMs\tfineMs\ttesto", oppure null se fallisce o viene annullata. */
    @JvmStatic external fun transcribe(
        ptr: Long,
        samples: FloatArray,
        numSamples: Int,
        language: String,
        threads: Int,
        initialPrompt: String?,
        listener: TranscribeListener,
    ): ByteArray?
}

/** Tiene caricato un solo modello alla volta. Le chiamate devono essere serializzate da chi lo usa. */
object WhisperEngine {
    private var ctx = 0L
    private var loadedPath: String? = null

    @Synchronized
    fun load(modelPath: String): Long {
        if (loadedPath == modelPath && ctx != 0L) return ctx
        release()
        val p = WhisperNative.initContext(modelPath)
        check(p != 0L) { "Il modello non si carica: il file potrebbe essere incompleto o non in formato ggml." }
        ctx = p
        loadedPath = modelPath
        return ctx
    }

    @Synchronized
    fun release() {
        if (ctx != 0L) WhisperNative.freeContext(ctx)
        ctx = 0L
        loadedPath = null
    }

    fun parse(bytes: ByteArray, offsetMs: Long): List<Segment> =
        bytes.toString(Charsets.UTF_8).lineSequence().mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            if (parts.size < 3) return@mapNotNull null
            val t0 = parts[0].toLongOrNull() ?: return@mapNotNull null
            val t1 = parts[1].toLongOrNull() ?: t0
            val text = parts[2].trim()
            if (text.isEmpty() || isNoise(text)) null else Segment(t0 + offsetMs, t1 + offsetMs, text)
        }.toList()

    /** Whisper a volte scrive etichette come "[Musica]" o "(applausi)" nei silenzi. */
    private fun isNoise(text: String): Boolean {
        val t = text.trim()
        return (t.startsWith("[") && t.endsWith("]")) || (t.startsWith("(") && t.endsWith(")"))
    }
}
