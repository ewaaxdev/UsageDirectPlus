package godau.fynn.usagedirectplus.activity

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import godau.fynn.usagedirectplus.R
import godau.fynn.usagedirectplus.databinding.ActivityNotesBinding
import godau.fynn.usagedirectplus.databinding.DialogNoteBinding
import godau.fynn.usagedirectplus.persistence.HistoryDatabase
import godau.fynn.usagedirectplus.persistence.Note
import godau.fynn.usagedirectplus.thread.icon.IconThread
import godau.fynn.usagedirectplus.view.adapter.NoteAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class NotesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotesBinding
    private lateinit var adapter: NoteAdapter

    private val dao by lazy { HistoryDatabase.get(this).getNotesDao() }

    private var filterApplicationId: String? = null
    private var filterDay: Long? = null
    private var query: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityNotesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        filterApplicationId = intent.getStringExtra(EXTRA_APPLICATION_ID)
        if (intent.hasExtra(EXTRA_DAY)) {
            filterDay = intent.getLongExtra(EXTRA_DAY, 0L)
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (supportActionBar?.title.isNullOrEmpty()) {
            supportActionBar?.title = getString(R.string.notes_title)
        }
        updateFilterLabel()

        adapter = NoteAdapter(
            metaFormatter = ::formatMeta,
            onClick = { note -> showEditor(note) },
            onLongClick = { note -> confirmDelete(note) }
        )
        binding.notesList.layoutManager = LinearLayoutManager(this)
        binding.notesList.adapter = adapter

        binding.notesAdd.setOnClickListener { showEditor(null) }

        binding.notesSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                reload()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun reload() {
        val requestedQuery = query
        lifecycleScope.launch {
            val notes = withContext(Dispatchers.IO) { loadNotes(requestedQuery) }
            if (requestedQuery != query) return@launch

            adapter.submit(notes)

            val empty = notes.isEmpty()
            binding.notesList.visibility = if (empty) View.GONE else View.VISIBLE
            binding.notesEmpty.visibility = if (empty) View.VISIBLE else View.GONE
            binding.notesEmpty.setText(
                if (requestedQuery.isNotEmpty()) R.string.notes_no_search_results else R.string.notes_empty
            )
        }
    }

    private fun loadNotes(requestedQuery: String): List<Note> {
        val loaded = when {
            requestedQuery.isNotEmpty() -> dao.search(requestedQuery)
            filterApplicationId != null && filterDay != null ->
                dao.getByAppAndDay(filterApplicationId!!, filterDay!!)
            filterApplicationId != null -> dao.getByApp(filterApplicationId!!)
            filterDay != null -> dao.getByDay(filterDay!!)
            else -> dao.getAll()
        }

        if (requestedQuery.isEmpty()) return loaded

        return loaded.filter { note ->
            (filterApplicationId == null || note.applicationId == filterApplicationId) &&
                (filterDay == null || note.day == filterDay)
        }
    }

    private fun showEditor(existing: Note?) {
        val dialogBinding = DialogNoteBinding.inflate(LayoutInflater.from(this))
        dialogBinding.noteTagInput.setText(existing?.tag.orEmpty())
        dialogBinding.noteBodyInput.setText(existing?.body.orEmpty())

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.notes_add else R.string.notes_edit)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.notes_save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            if (existing != null) {
                dialog.setButton(AlertDialog.BUTTON_NEUTRAL, getString(R.string.notes_delete)) { _, _ ->
                    confirmDelete(existing) { dialog.dismiss() }
                }
            }

            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.isEnabled = !dialogBinding.noteBodyInput.text.isNullOrBlank()

            dialogBinding.noteBodyInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                override fun afterTextChanged(s: Editable?) {
                    saveButton.isEnabled = !s.isNullOrBlank()
                }
            })

            saveButton.setOnClickListener {
                val body = dialogBinding.noteBodyInput.text?.toString()?.trim().orEmpty()
                if (body.isEmpty()) return@setOnClickListener

                val tag = dialogBinding.noteTagInput.text?.toString()?.trim().orEmpty()
                val note = Note(
                    id = existing?.id ?: 0L,
                    applicationId = existing?.applicationId ?: filterApplicationId,
                    day = existing?.day ?: filterDay,
                    createdAt = existing?.createdAt ?: 0L,
                    updatedAt = existing?.updatedAt ?: 0L,
                    tag = tag.ifEmpty { null },
                    body = body
                )

                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { dao.upsert(note) }
                    dialog.dismiss()
                    reload()
                }
            }
        }

        dialog.show()
    }

    private fun confirmDelete(note: Note, onDeleted: (() -> Unit)? = null) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.notes_delete)
            .setMessage(R.string.notes_delete_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { dao.deleteById(note.id) }
                    onDeleted?.invoke()
                    reload()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateFilterLabel() {
        val parts = mutableListOf<String>()
        filterApplicationId?.let { id ->
            parts.add(getString(R.string.notes_filter_app, IconThread.nameMap[id] ?: id))
        }
        filterDay?.let { day ->
            parts.add(getString(R.string.notes_filter_day, LocalDate.ofEpochDay(day).toString()))
        }

        if (parts.isEmpty()) {
            binding.notesFilter.visibility = View.GONE
        } else {
            binding.notesFilter.text = getString(R.string.notes_filter, parts.joinToString(" · "))
            binding.notesFilter.visibility = View.VISIBLE
        }
    }

    private fun formatMeta(note: Note): String {
        val parts = mutableListOf<String>()
        note.tag?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        note.applicationId?.let { id -> parts.add(IconThread.nameMap[id] ?: id) }
        note.day?.let { day -> parts.add(LocalDate.ofEpochDay(day).toString()) }
        if (note.day == null && note.updatedAt > 0) parts.add(formatTimestamp(note.updatedAt))
        return parts.joinToString(" · ")
    }

    private fun formatTimestamp(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    companion object {
        const val EXTRA_APPLICATION_ID = "applicationId"
        const val EXTRA_DAY = "day"
    }
}
