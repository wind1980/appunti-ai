package it.edoardo.appunti.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import it.edoardo.appunti.app
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Attività: lettura dalla risposta "Azioni" di Claude e promemoria nel calendario. */
object Tasks {

    /** Riga "- [ ] testo" (anche con * o •, e [x] per le attività già fatte). */
    private val CHECK = Regex("^\\s*[-*•]\\s+\\[([ xX])]\\s+(.+)$")
    private val OWNER = Regex("(?i)\\b(?:responsabile|chi|assegnat[oa] a)\\s*:\\s*([^—–|;\\[]+)")
    private val DUE = Regex("(?i)\\bscadenza\\s*:\\s*([^—–|;\\[]+)")
    private val DATE = Regex("(\\d{1,2})[/.-](\\d{1,2})(?:[/.-](\\d{2,4}))?")

    data class Parsed(val text: String, val owner: String?, val dueAt: Long?, val done: Boolean)

    /** Estrae le attività da un testo in Markdown. Le righe senza casella vengono ignorate. */
    fun parse(markdown: String): List<Parsed> {
        // Se Claude raggruppa per persona ("## Mario"), il titolo fa da responsabile.
        var heading: String? = null
        val out = mutableListOf<Parsed>()
        for (line in markdown.lines()) {
            val t = line.trim()
            if (t.startsWith("#")) {
                heading = t.trimStart('#', ' ').trim('*', ' ', ':').takeIf { it.isNotEmpty() && it.length <= 40 }
                continue
            }
            val m = CHECK.find(line) ?: continue
            val done = m.groupValues[1].isNotBlank()
            var body = m.groupValues[2].replace("**", "").trim()
            val owner = OWNER.find(body)?.groupValues?.get(1)?.trim()?.trimEnd(',', '.')?.takeIf { it.isNotEmpty() }
                ?: heading?.takeIf { !it.contains("azion", ignoreCase = true) && !it.contains("attivit", ignoreCase = true) }
            val dueText = DUE.find(body)?.groupValues?.get(1)?.trim()
            val dueAt = dueText?.let(::parseDate)
            // Il testo dell'attività è la prima parte, prima dei campi "Responsabile:" e "Scadenza:".
            body = body.split(Regex("\\s+[—–|]\\s+")).firstOrNull()?.trim() ?: body
            body = OWNER.replace(body, "").let { DUE.replace(it, "") }.trim().trimEnd('—', '–', '-', ',', ' ')
            if (body.isNotEmpty()) out += Parsed(body, owner, dueAt, done)
        }
        return out
    }

    /** "15/12/2026", "15/12" o "15.12.26" → ore 9:00 di quel giorno. Le date senza anno vanno nel futuro. */
    fun parseDate(text: String): Long? {
        val m = DATE.find(text) ?: return null
        val day = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        if (day !in 1..31 || month !in 1..12) return null
        val cal = Calendar.getInstance()
        val yearText = m.groupValues[3]
        val year = when {
            yearText.isEmpty() -> cal.get(Calendar.YEAR)
            yearText.length == 2 -> 2000 + yearText.toInt()
            else -> yearText.toInt()
        }
        val now = cal.timeInMillis
        cal.set(year, month - 1, day, 9, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (yearText.isEmpty() && cal.timeInMillis < now - 86_400_000L) cal.add(Calendar.YEAR, 1)
        return cal.timeInMillis
    }

    /** Aggiunge alla nota le attività di una risposta "Azioni", senza duplicare quelle già presenti. */
    suspend fun importFrom(context: Context, noteId: Long, markdown: String): Int {
        val dao = context.app.db.notes()
        val existing = dao.taskTexts(noteId).map { it.lowercase() }.toMutableSet()
        var added = 0
        for (p in parse(markdown)) {
            if (!existing.add(p.text.lowercase())) continue
            dao.insertTask(
                TaskItem(
                    noteId = noteId, text = p.text, owner = p.owner, dueAt = p.dueAt, done = p.done,
                    createdAt = System.currentTimeMillis() + added,
                )
            )
            added++
        }
        return added
    }

    fun formatDate(millis: Long): String = SimpleDateFormat("EEE d MMM yyyy", Locale.ITALIAN).format(Date(millis))

    /** Apre il calendario con un evento già compilato: il promemoria lo gestisce l'app Calendario. */
    fun addReminder(context: Context, task: TaskItem, noteTitle: String?) {
        val begin = task.dueAt ?: Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
        }.timeInMillis
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 30 * 60_000L)
            putExtra(CalendarContract.Events.TITLE, task.text)
            putExtra(
                CalendarContract.Events.DESCRIPTION,
                buildString {
                    task.owner?.let { append("Responsabile: ").append(it).append('\n') }
                    noteTitle?.let { append("Dalla nota MeNoter: ").append(it) }
                },
            )
            putExtra(CalendarContract.Events.HAS_ALARM, true)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Nessuna app Calendario trovata sul telefono", Toast.LENGTH_LONG).show()
        }
    }
}
