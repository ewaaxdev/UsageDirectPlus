package godau.fynn.usagedirectplus.view.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import godau.fynn.usagedirectplus.databinding.RowNoteBinding
import godau.fynn.usagedirectplus.persistence.Note

class NoteAdapter(
    private val metaFormatter: (Note) -> String,
    private val onClick: (Note) -> Unit,
    private val onLongClick: (Note) -> Unit
) : RecyclerView.Adapter<NoteAdapter.ViewHolder>() {

    private val notes = mutableListOf<Note>()

    init {
        setHasStableIds(true)
    }

    fun submit(newNotes: List<Note>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = notes.size

            override fun getNewListSize(): Int = newNotes.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                notes[oldItemPosition].id == newNotes[newItemPosition].id

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                notes[oldItemPosition] == newNotes[newItemPosition]
        })
        notes.clear()
        notes.addAll(newNotes)
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemId(position: Int): Long = notes[position].id

    override fun getItemCount(): Int = notes.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = RowNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val note = notes[position]
        holder.binding.noteBody.text = note.body
        holder.binding.noteMeta.text = metaFormatter(note)
        holder.binding.noteCard.setOnClickListener { onClick(note) }
        holder.binding.noteCard.setOnLongClickListener {
            onLongClick(note)
            true
        }
    }

    class ViewHolder(val binding: RowNoteBinding) : RecyclerView.ViewHolder(binding.root)
}
