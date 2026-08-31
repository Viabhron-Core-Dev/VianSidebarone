package com.example.feature.miniapps.reader


import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FileAdapter(
    var files: List<java.io.File>,
    private val onDirectorySelected: (java.io.File) -> Unit,
    private val onImportFile: (java.io.File) -> Unit,
    private val onShowContextMenu: (java.io.File) -> Unit,
    private val onLoadEpubCover: suspend (java.io.File) -> android.graphics.Bitmap?,
    private val coroutineScope: CoroutineScope
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {
    
    inner class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.iv_file_icon)
        val tvName: TextView = view.findViewById(R.id.tv_file_name)
        val tvSize: TextView = view.findViewById(R.id.tv_file_size)

        init {
            view.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val file = files[pos]
                    if (file.isDirectory) {
                        onDirectorySelected(file)
                    } else {
                        onImportFile(file)
                    }
                }
            }
            
            view.setOnLongClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onShowContextMenu(files[pos])
                }
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file_explorer, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val file = files[position]
        holder.tvName.text = file.name ?: "Unknown"
        
        holder.ivIcon.tag = null
        
        if (file.isDirectory) {
            holder.ivIcon.setImageResource(android.R.drawable.ic_menu_agenda)
            holder.ivIcon.setColorFilter(android.graphics.Color.parseColor("#FFD54F"))
            holder.tvSize.visibility = View.GONE
        } else {
            holder.ivIcon.setImageResource(android.R.drawable.ic_menu_sort_by_size)
            holder.ivIcon.setColorFilter(android.graphics.Color.parseColor("#7FE9F9"))
            holder.tvSize.visibility = View.VISIBLE
            
            val ext = file.extension.uppercase()
            val sizeBytes = file.length()
            val sizeText = when {
                sizeBytes > 1024 * 1024 -> String.format("%.2f MB", sizeBytes / (1024f * 1024f))
                sizeBytes > 1024 -> String.format("%.1f KB", sizeBytes / 1024f)
                else -> "$sizeBytes B"
            }
            holder.tvSize.text = if (ext.isNotEmpty()) "$ext • $sizeText" else sizeText
            
            if (file.name.endsWith(".epub", true)) {
                holder.ivIcon.tag = file.absolutePath
                coroutineScope.launch(Dispatchers.IO) {
                    val bitmap = onLoadEpubCover(file)
                    withContext(Dispatchers.Main) {
                        if (holder.ivIcon.tag == file.absolutePath && bitmap != null) {
                            holder.ivIcon.clearColorFilter()
                            holder.ivIcon.setImageBitmap(bitmap)
                        }
                    }
                }
            }
        }
    }

    override fun getItemCount() = files.size
}


class LibraryAdapter(
    var books: List<com.example.data.EpubBook>,
    private val onBookSelected: (com.example.data.EpubBook) -> Unit,
    private val onBookDeleted: (com.example.data.EpubBook) -> Unit
) : RecyclerView.Adapter<LibraryAdapter.LibraryViewHolder>() {
    
    inner class LibraryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tv_title)
        val tvSub: TextView = view.findViewById(R.id.tv_subtitle)
        val btnMore: ImageView = view.findViewById(R.id.btn_more)
        init {
            view.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onBookSelected(books[pos])
                }
            }
            btnMore.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onBookDeleted(books[pos])
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LibraryViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_library_book, parent, false)
        return LibraryViewHolder(view)
    }

    override fun onBindViewHolder(holder: LibraryViewHolder, position: Int) {
        val book = books[position]
        holder.tvTitle.text = book.title
        val status = if (book.isParsed) "Parsed • Ch ${book.lastReadChapter + 1}/${book.totalChapters}" else "Parsing/Pending..."
        holder.tvSub.text = status
    }

    override fun getItemCount() = books.size
}


class NotesAdapter(
    var notesList: List<com.example.data.QuickNote>,
    private val selectedNotes: MutableSet<com.example.data.QuickNote>,
    private val onUpdateNotesUi: () -> Unit,
    private val onShowNoteDialog: (com.example.data.QuickNote) -> Unit
) : RecyclerView.Adapter<NotesAdapter.NoteViewHolder>() {
    val unfoldedNoteIds = mutableSetOf<Long>()

    inner class NoteViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tv_note_title)
        val tvText: TextView = view.findViewById(R.id.tv_note_text)
        val btnUnfold: android.widget.ImageButton = view.findViewById(R.id.btn_note_unfold)
        val container: View = view.findViewById(R.id.ll_note_container)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_quick_note, parent, false)
        return NoteViewHolder(view)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        val note = notesList[position]
        val isUnfolded = unfoldedNoteIds.contains(note.id.toLong())

        if (note.title.isNotEmpty()) {
            holder.tvTitle.text = note.title
        } else {
            val lines = note.text.lines()
            holder.tvTitle.text = if (lines.isNotEmpty()) lines[0] else "Untitled Note"
        }
        holder.tvTitle.visibility = View.VISIBLE

        holder.tvText.text = note.text
        
        if (isUnfolded) {
            holder.tvText.visibility = View.VISIBLE
            holder.btnUnfold.setImageResource(android.R.drawable.arrow_up_float)
        } else {
            holder.tvText.visibility = View.GONE
            holder.btnUnfold.setImageResource(android.R.drawable.arrow_down_float)
        }
        
        holder.btnUnfold.setOnClickListener {
            if (isUnfolded) unfoldedNoteIds.remove(note.id.toLong()) else unfoldedNoteIds.add(note.id.toLong())
            notifyItemChanged(position)
        }
        
        if (selectedNotes.contains(note)) {
            holder.container.setBackgroundColor(android.graphics.Color.parseColor("#3A5A7A"))
        } else {
            holder.container.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        
        holder.itemView.setOnClickListener {
            if (selectedNotes.isNotEmpty()) {
                if (selectedNotes.contains(note)) selectedNotes.remove(note) else selectedNotes.add(note)
                onUpdateNotesUi()
            } else {
                onShowNoteDialog(note)
            }
        }
        
        holder.itemView.setOnLongClickListener {
            if (selectedNotes.contains(note)) selectedNotes.remove(note) else selectedNotes.add(note)
            onUpdateNotesUi()
            true
        }
    }

    override fun getItemCount() = notesList.size
}


data class BookmarkItem(val words: String, val chapter: Int, val percentage: Int)

class BookmarkAdapter(
    private val bookmarksList: MutableList<BookmarkItem>,
    private val onLoadAndJump: (Int, Int) -> Unit,
    private val onHideOverlays: () -> Unit
) : RecyclerView.Adapter<BookmarkAdapter.BookmarkViewHolder>() {
    inner class BookmarkViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvWords: TextView = view.findViewById(R.id.tv_bookmark_words)
        val tvChapter: TextView = view.findViewById(R.id.tv_bookmark_chapter)
        val tvPercent: TextView = view.findViewById(R.id.tv_bookmark_percent)
        val btnDelete: ImageView = view.findViewById(R.id.btn_delete_bookmark)
        
        init {
            view.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val bm = bookmarksList[pos]
                    onLoadAndJump(bm.chapter, 0)
                    onHideOverlays()
                }
            }
            btnDelete.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    bookmarksList.removeAt(pos)
                    notifyItemRemoved(pos)
                    notifyItemRangeChanged(pos, bookmarksList.size)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookmarkViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_bookmark, parent, false)
        return BookmarkViewHolder(view)
    }

    override fun onBindViewHolder(holder: BookmarkViewHolder, position: Int) {
        val bm = bookmarksList[position]
        holder.tvWords.text = bm.words
        holder.tvChapter.text = "Chapter ${bm.chapter + 1}"
        holder.tvPercent.text = "${bm.percentage}%"
    }

    override fun getItemCount() = bookmarksList.size
}


class ChapterAdapter(
    private val totalChapters: Int,
    private val currentChapterIndex: Int,
    private val onChapterSelected: (Int) -> Unit
) : RecyclerView.Adapter<ChapterAdapter.ChapterViewHolder>() {
    inner class ChapterViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tv_chapter_title)
        init {
            view.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onChapterSelected(pos)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChapterViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chapter, parent, false)
        return ChapterViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChapterViewHolder, position: Int) {
        holder.tvTitle.text = "Chapter ${position + 1}"
        if (position == currentChapterIndex) {
            holder.tvTitle.setTextColor(android.graphics.Color.parseColor("#7FE9F9"))
        } else {
            holder.tvTitle.setTextColor(android.graphics.Color.WHITE)
        }
    }

    override fun getItemCount() = totalChapters
}
