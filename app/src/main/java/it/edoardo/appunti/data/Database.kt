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

    @Delete
    suspend fun deleteResponse(response: ClaudeResponse)
}

@Database(entities = [Note::class, ClaudeResponse::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "appunti.db").build()
    }
}
