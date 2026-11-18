package it.edoardo.appunti.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Wav {
    const val HEADER_SIZE = 44

    data class Header(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataBytes: Long,
    )

    /** Scrive un'intestazione WAV PCM 16 bit mono. */
    fun writeHeader(raf: RandomAccessFile, dataBytes: Long, sampleRate: Int = SAMPLE_RATE) {
        val b = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt())
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        b.putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes.toInt())
        raf.seek(0)
        raf.write(b.array())
    }

    /**
     * Legge l'intestazione di un WAV PCM. Se la dimensione dei dati è sbagliata
     * (registrazione interrotta), la ricava dalla lunghezza del file.
     */
    fun readHeader(file: File): Header? {
        if (!file.exists() || file.length() < HEADER_SIZE) return null
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(12)
            raf.readFully(head)
            if (String(head, 0, 4) != "RIFF" || String(head, 8, 4) != "WAVE") return null
            var sampleRate = 0
            var channels = 0
            var bits = 0
            var format = 0
            var pos = 12L
            while (pos + 8 <= raf.length()) {
                raf.seek(pos)
                val ch = ByteArray(8)
                raf.readFully(ch)
                val id = String(ch, 0, 4)
                val size = ByteBuffer.wrap(ch, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
                if (id == "fmt ") {
                    val f = ByteArray(16)
                    raf.readFully(f)
                    val bb = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
                    format = bb.short.toInt()
                    channels = bb.short.toInt()
                    sampleRate = bb.int
                    bb.int
                    bb.short
                    bits = bb.short.toInt()
                } else if (id == "data") {
                    if (format != 1) return null
                    val offset = pos + 8
                    val available = raf.length() - offset
                    val bytes = if (size == 0L || size > available) available else size
                    return Header(sampleRate, channels, bits, offset, bytes - bytes % 2)
                }
                pos += 8 + size + (size and 1)
            }
            return null
        }
    }
}
