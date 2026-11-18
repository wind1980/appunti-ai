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
)

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

    @Query("SELECT text FROM responses WHERE noteId = :noteId AND kind = 'image'")
    suspend fun imagePaths(noteId: Long): List<String>

    @Query("UPDATE responses SET kind = :kind WHERE id = :id")
    suspend fun setResponseKind(id: Long, kind: String?)

    @Query("SELECT * FROM responses WHERE id = :id")
    suspend fun getResponse(id: Long): ClaudeResponse?

    @Delete
    suspend fun deleteResponse(response: ClaudeResponse)
}

@Database(entities = [Note::class, ClaudeResponse::class], version = 3, exportSchema = false)
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

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "appunti.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
