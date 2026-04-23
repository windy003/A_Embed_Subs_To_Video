package com.example.subtitleapp.adapter

import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.content.Context
import androidx.core.view.GestureDetectorCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.subtitleapp.databinding.ItemSubtitleBinding
import com.example.subtitleapp.model.SubtitleEntry

class SubtitleAdapter(
    private val onTextChanged: (index: Int, newText: String) -> Unit,
    private val onClick: (position: Int, entry: SubtitleEntry) -> Unit,
    private val onLongClick: (position: Int) -> Unit
) : ListAdapter<SubtitleEntry, SubtitleAdapter.ViewHolder>(DiffCallback()) {

    var highlightedPosition: Int = -1
        set(value) {
            val old = field
            field = value
            if (old >= 0 && old < itemCount) notifyItemChanged(old, "highlight")
            if (value >= 0 && value < itemCount) notifyItemChanged(value, "highlight")
        }

    /** 当前正在编辑的位置，-1 表示无 */
    private var editingPosition: Int = -1

    /** 退出所有编辑状态（由外部调用，如点击其他区域时） */
    fun clearEditing(recyclerView: RecyclerView?) {
        if (editingPosition >= 0 && recyclerView != null) {
            val old = editingPosition
            editingPosition = -1
            val vh = recyclerView.findViewHolderForAdapterPosition(old) as? ViewHolder
            vh?.disableEditing()
        }
    }

    inner class ViewHolder(
        private val binding: ItemSubtitleBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var textWatcher: TextWatcher? = null
        private var boundPosition = -1
        private var boundEntry: SubtitleEntry? = null

        fun bind(entry: SubtitleEntry, position: Int) {
            boundPosition = position
            boundEntry = entry

            textWatcher?.let { binding.etSubtitleText.removeTextChangedListener(it) }

            binding.etSubtitleText.setText(entry.text)
            updateHighlight(position)

            if (position == editingPosition) {
                enableEditing()
            } else {
                disableEditing()
            }

            textWatcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val newText = s?.toString() ?: ""
                    if (newText != boundEntry?.text && boundPosition >= 0) {
                        onTextChanged(boundPosition, newText)
                    }
                }
            }
            binding.etSubtitleText.addTextChangedListener(textWatcher)

            val gestureDetector = GestureDetectorCompat(binding.root.context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        val e = boundEntry ?: return false
                        onClick(boundPosition, e)
                        return true
                    }

                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        if (boundPosition < 0) return false
                        startEditing(boundPosition)
                        return true
                    }

                    override fun onLongPress(e: MotionEvent) {
                        if (boundPosition >= 0 && boundPosition != editingPosition) onLongClick(boundPosition)
                    }
                })

            binding.etSubtitleText.setOnTouchListener { _, event ->
                if (boundPosition == editingPosition) {
                    false
                } else {
                    gestureDetector.onTouchEvent(event)
                    true
                }
            }

            binding.layoutRoot.setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                true
            }
        }

        private fun startEditing(position: Int) {
            // 先退出之前的编辑
            val rv = binding.root.parent as? RecyclerView
            if (editingPosition >= 0 && editingPosition != position && rv != null) {
                val oldVh = rv.findViewHolderForAdapterPosition(editingPosition) as? ViewHolder
                oldVh?.disableEditing()
            }
            editingPosition = position
            enableEditing()
        }

        fun enableEditing() {
            binding.etSubtitleText.isFocusable = true
            binding.etSubtitleText.isFocusableInTouchMode = true
            binding.etSubtitleText.isCursorVisible = true
            binding.etSubtitleText.requestFocus()
            // 弹出键盘
            val imm = binding.root.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.etSubtitleText, InputMethodManager.SHOW_IMPLICIT)
        }

        fun disableEditing() {
            binding.etSubtitleText.isFocusable = false
            binding.etSubtitleText.isFocusableInTouchMode = false
            binding.etSubtitleText.isCursorVisible = false
            binding.etSubtitleText.clearFocus()
        }

        fun updateHighlight(position: Int) {
            if (position == highlightedPosition) {
                binding.layoutRoot.setBackgroundColor(Color.parseColor("#1A2196F3"))
                binding.etSubtitleText.setTextColor(Color.parseColor("#1565C0"))
            } else {
                binding.layoutRoot.setBackgroundColor(Color.TRANSPARENT)
                binding.etSubtitleText.setTextColor(Color.parseColor("#333333"))
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSubtitleBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains("highlight")) {
            holder.updateHighlight(position)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<SubtitleEntry>() {
        override fun areItemsTheSame(old: SubtitleEntry, new: SubtitleEntry): Boolean {
            return old.index == new.index && old.startMs == new.startMs
        }
        override fun areContentsTheSame(old: SubtitleEntry, new: SubtitleEntry): Boolean {
            return old == new
        }
    }
}
