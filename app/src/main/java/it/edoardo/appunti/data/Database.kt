package it.edoardo.appunti.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

object Status {
    const val NEW = "new"
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val DONE = "done"
    const val ERROR = "error"
    const val CANCELLED = "cancelled"
    /** Nota creata da un testo (es. una risposta di Claude), senza audio. */
    const val TEXT = "text"
}

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val audioPath: String?,
    val durationMs: Long = 0,
    val status: String = Status.NEW,
    val progress: Int = 0,
    /** Segmenti della trascrizione, una riga per segmento: "inizioMs\tfineMs\ttesto". */
    val segments: String = "",
    /** Campioni (16 kHz) già trascritti: permette di riprendere dopo un'interruzione. */
    val processedSamples: Long = 0,
    val error: String? = null,
    /** File del modello usato per la trascrizione attuale. */
    val modelName: String? = null,
    /** Se non null, è in corso un "Migliora" con questo modello: il testo attuale resta finché non finisce. */
    val improveModel: String? = null,
)

@Entity(
    tableName = "responses",
    foreignKeys = [ForeignKey(
        entity = Note::class,
        parentColumns = ["id"],
        childColumns = ["noteId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("noteId")],
)
data class ClaudeResponse(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val createdAt: Long,
    val text: String,
    /** Tipo di richiesta che ha prodotto la risposta (chiave di PromptKind), se nota. */
    val kind: String? = null,
    /** Immagine o PDF abbinato (es. il PNG della mappa o dell'infografica creato da Claude). */
    val filePath: String? = null,
)

/** File (immagine o PDF) della risposta: quello abbinato o, per le vecchie risposte-immagine, il testo stesso. */
fun ClaudeResponse.file(): String? =
    filePath ?: text.takeIf { kind == "image" || kind == "pdf" }

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Note>>

    @Query(
        """SELECT * FROM notes
           WHERE title LIKE '%' || :q || '%'
              OR segments LIKE '%' || :q || '%'
              OR id IN (SELECT noteId FROM responses WHERE text LIKE '%' || :q || '%')
           ORDER BY createdAt DESC"""
    )
    fun search(q: String): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observe(id: Long): Flow<Note?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: Long): Note?

    @Query("SELECT * FROM notes ORDER BY createdAt DESC LIMIT 30")
    suspend fun recent(): List<Note>

    @Insert
    suspend fun insert(note: Note): Long

    @Query("UPDATE notes SET status = :status, progress = :progress, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, progress: Int, error: String?)

    @Query("UPDATE notes SET progress = :progress WHERE id = :id")
    suspend fun setProgress(id: Long, progress: Int)

    @Query("UPDATE notes SET segments = :segments, processedSamples = :processed WHERE id = :id")
    suspend fun setSegments(id: Long, segments: String, processed: Long)

    @Query("UPDATE notes SET improveModel = :model WHERE id = :id")
    suspend fun setImprove(id: Long, model: String?)

    @Query("UPDATE notes SET modelName = :model WHERE id = :id")
    suspend fun setModelName(id: Long, model: String?)

    /** Sostituisce in un colpo solo la trascrizione (usato alla fine di "Migliora"). */
    @Query(
        """UPDATE notes SET segments = :segments, processedSamples = :processed, modelName = :model,
           improveModel = NULL, status = 'done', progress = 100, error = NULL WHERE id = :id"""
    )
    suspend fun replaceTranscript(id: Long, segments: String, processed: Long, model: String?)

    @Query("SELECT audioPath FROM notes WHERE audioPath IS NOT NULL")
    suspend fun audioPaths(): List<String>

    @Query("UPDATE notes SET durationMs = :durationMs WHERE id = :id")
    suspend fun setDuration(id: Long, durationMs: Long)

    @Query("UPDATE notes SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM responses WHERE noteId = :noteId ORDER BY createdAt DESC")
    fun observeResponses(noteId: Long): Flow<List<ClaudeResponse>>

    @Insert
    suspend fun insertResponse(response: ClaudeResponse): Long

    @Query(
        """SELECT text FROM responses WHERE noteId = :noteId AND kind IN ('image', 'pdf')
           UNION ALL SELECT filePath FROM responses WHERE noteId = :noteId AND filePath IS NOT NULL"""
    )
    suspend fun imagePaths(noteId: Long): List<String>

    /** Ultima scheda di quel tipo ancora senza immagine (per abbinarle il PNG che arriva dopo il testo). */
    @Query(
        """SELECT * FROM responses WHERE noteId = :noteId AND kind = :kind AND filePath IS NULL
           AND createdAt > :since ORDER BY createdAt DESC LIMIT 1"""
    )
    suspend fun latestWithoutFile(noteId: Long, kind: String, since: Long): ClaudeResponse?

    /** Ultima scheda di quel tipo con l'immagine ma senza testo (per abbinarle il testo che arriva dopo). */
    @Query(
        """SELECT * FROM responses WHERE noteId = :noteId AND kind = :kind AND filePath IS NOT NULL
           AND text = '' AND createdAt > :since ORDER BY createdAt DESC LIMIT 1"""
    )
    suspend fun latestWithoutText(noteId: Long, kind: String, since: Long): ClaudeResponse?

    @Query("UPDATE responses SET filePath = :path WHERE id = :id")
    suspend fun setResponseFile(id: Long, path: String?)

    @Query("UPDATE responses SET text = :text WHERE id = :id")
    suspend fun setResponseText(id: Long, text: String)

    @Query("UPDATE responses SET kind = :kind WHERE id = :id")
    suspend fun setResponseKind(id: Long, kind: String?)

    @Query("SELECT * FROM responses WHERE id = :id")
    suspend fun getResponse(id: Long): ClaudeResponse?

    @Delete
    suspend fun deleteResponse(response: ClaudeResponse)
}

@Database(entities = [Note::class, ClaudeResponse::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao

    companion object {
        /** 0.1 → 0.2: modello usato e "Migliora" in corso. Le note esistenti restano. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN modelName TEXT")
                db.execSQL("ALTER TABLE notes ADD COLUMN improveModel TEXT")
            }
        }

        /** 0.3 → 0.4: tipo di richiesta salvato con ogni risposta di Claude. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE responses ADD COLUMN kind TEXT")
            }
        }

        /** 0.7 → 0.8: immagine abbinata al testo di mappe e infografiche. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE responses ADD COLUMN filePath TEXT")
            }
        }

        /**
         * 4 → 5: esistono due "versione 4" diverse (questa, con l'immagine abbinata, e quella del ramo
         * menoter-0.8, con la tabella delle attività). Qui si aggiunge solo ciò che manca, così il
         * database funziona qualunque delle due fosse installata prima.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val columns = mutableSetOf<String>()
                db.query("PRAGMA table_info(`responses`)").use { c ->
                    val name = c.getColumnIndex("name")
                    while (c.moveToNext()) columns += c.getString(name)
                }
                if ("filePath" !in columns) db.execSQL("ALTER TABLE responses ADD COLUMN filePath TEXT")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "appunti.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}
