package it.edoardo.appunti.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.floor

const val SAMPLE_RATE = 16_000

/** Audio PCM 16 bit mono a 16 kHz dentro un file, a partire da [offset] byte. */
class PcmSource(val file: File, val offset: Long, val numSamples: Long) {

    /** Legge [count] campioni dalla posizione [start] come float tra -1 e 1. */
    fun read(start: Long, count: Int, into: FloatArray) {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset + start * 2)
            val bytes = ByteArray(count * 2)
            raf.readFully(bytes)
            val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            for (i in 0 until count) into[i] = sb.get(i) / 32768f
        }
    }

    /**
     * Cerca un punto di taglio silenzioso tra [from] e [to] (finestre da 20 ms),
     * così i blocchi non spezzano le parole.
     */
    fun quietestPoint(from: Long, to: Long): Long {
        val win = SAMPLE_RATE / 50
        val len = (to - from).toInt()
        if (len < win * 2) return to
        val buf = FloatArray(len)
        read(from, len, buf)
        var best = to
        var bestEnergy = Double.MAX_VALUE
        var i = 0
        while (i + win <= len) {
            var e = 0.0
            for (k in i until i + win) e += (buf[k] * buf[k]).toDouble()
            if (e < bestEnergy) {
                bestEnergy = e
                best = from + i + win / 2
            }
            i += win
        }
        return best
    }
}

object AudioDecoder {

    /**
     * Se il file è già un WAV PCM 16 bit mono a 16 kHz (le registrazioni dell'app)
     * lo usa direttamente; altrimenti lo decodifica in [pcmOut].
     */
    fun prepare(
        input: File,
        pcmOut: File,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): PcmSource {
        Wav.readHeader(input)?.let { h ->
            if (h.sampleRate == SAMPLE_RATE && h.channels == 1 && h.bitsPerSample == 16) {
                return PcmSource(input, h.dataOffset, h.dataBytes / 2)
            }
        }
        val n = decode(input, pcmOut, onProgress, isCancelled)
        return PcmSource(pcmOut, 0, n)
    }

    /** Decodifica qualsiasi audio/video supportato da Android in PCM 16 bit mono 16 kHz. */
    fun decode(
        input: File,
        out: File,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(input.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("Il file non contiene una traccia audio")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler = LinearResampler(sampleRate)

            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var mono = FloatArray(0)

            BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { os ->
                val writer = Pcm16Writer(os)
                while (!outputDone) {
                    if (isCancelled()) throw CancellationException("Annullato")

                    if (!inputDone) {
                        val inIdx = dec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = dec.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                dec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = dec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIdx >= 0 -> {
                            if (info.size > 0) {
                                val buf = dec.getOutputBuffer(outIdx)!!
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                buf.order(ByteOrder.nativeOrder())
                                val frames: Int
                                if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                    val fb = buf.asFloatBuffer()
                                    frames = fb.remaining() / channels
                                    if (mono.size < frames) mono = FloatArray(frames)
                                    for (f in 0 until frames) {
                                        var sum = 0f
                                        for (c in 0 until channels) sum += fb.get(f * channels + c)
                                        mono[f] = sum / channels
                                    }
                                } else {
                                    val sb = buf.asShortBuffer()
                                    frames = sb.remaining() / channels
                                    if (mono.size < frames) mono = FloatArray(frames)
                                    for (f in 0 until frames) {
                                        var sum = 0f
                                        for (c in 0 until channels) sum += sb.get(f * channels + c) / 32768f
                                        mono[f] = sum / channels
                                    }
                                }
                                resampler.process(mono, frames, writer)
                                if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                            }
                            dec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = dec.outputFormat
                            sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                            if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                pcmEncoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            }
                            resampler = LinearResampler(sampleRate)
                        }
                    }
                }
                writer.flush()
                return writer.count
            }
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            extractor.release()
        }
    }
}

/** Scrive campioni float come PCM 16 bit little-endian. */
class Pcm16Writer(private val os: OutputStream) {
    private val buf = ByteArray(8192)
    private var pos = 0
    var count = 0L
        private set

    fun write(sample: Float) {
        val v = (sample.coerceIn(-1f, 1f) * 32767f).toInt()
        buf[pos++] = (v and 0xff).toByte()
        buf[pos++] = ((v shr 8) and 0xff).toByte()
        count++
        if (pos == buf.size) flush()
    }

    fun flush() {
        if (pos > 0) os.write(buf, 0, pos)
        pos = 0
    }
}

/**
 * Ricampionamento lineare verso 16 kHz, a blocchi (mantiene lo stato tra un blocco e l'altro).
 * Prima di scendere di frequenza applica una media mobile come filtro passa-basso semplice.
 */
class LinearResampler(inRate: Int) {
    private val step = inRate.toDouble() / SAMPLE_RATE
    private val taps = if (step >= 2.0) floor(step).toInt() else 1
    private val history = FloatArray(taps)
    private var histPos = 0
    private var histSum = 0f

    private var pos = 0.0          // posizione (in campioni di ingresso) del prossimo campione di uscita
    private var consumed = 0L      // campioni di ingresso già elaborati prima del blocco corrente
    private var last = 0f          // ultimo campione (filtrato) del blocco precedente
    private var filtered = FloatArray(0)

    fun process(input: FloatArray, n: Int, out: Pcm16Writer) {
        if (n <= 0) return
        if (filtered.size < n) filtered = FloatArray(n)
        for (i in 0 until n) {
            histSum += input[i] - history[histPos]
            history[histPos] = input[i]
            histPos = (histPos + 1) % taps
            filtered[i] = histSum / taps
        }
        while (true) {
            val i = floor(pos).toLong()
            if (i + 1 >= consumed + n) break
            val frac = (pos - i).toFloat()
            val a = if (i < consumed) last else filtered[(i - consumed).toInt()]
            val b = filtered[(i + 1 - consumed).toInt()]
            out.write(a + (b - a) * frac)
            pos += step
        }
        last = filtered[n - 1]
        consumed += n
    }
}
