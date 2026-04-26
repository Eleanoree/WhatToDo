package com.example.whattodo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class FocusHistoryActivity : AppCompatActivity() {

    private lateinit var repository: TaskRepository
    private lateinit var rootView: View
    private lateinit var emptyView: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var sessionsValueView: TextView
    private lateinit var focusMinutesValueView: TextView
    private lateinit var linkedValueView: TextView
    private lateinit var adapter: FocusHistoryAdapter
    private val dateTimeFormatter = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_focus_history)

        repository = TaskRepository(this)
        bindViews()
        setupInsets()
        setupRecycler()
        observeSessions()
    }

    private fun bindViews() {
        rootView = findViewById(R.id.focusHistoryRoot)
        emptyView = findViewById(R.id.focusHistoryEmpty)
        recyclerView = findViewById(R.id.focusHistoryRecyclerView)
        sessionsValueView = findViewById(R.id.focusHistorySessionsValue)
        focusMinutesValueView = findViewById(R.id.focusHistoryFocusMinutesValue)
        linkedValueView = findViewById(R.id.focusHistoryLinkedValue)
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun setupRecycler() {
        adapter = FocusHistoryAdapter(dateTimeFormatter, ::formatMinutes, ::elapsedMinutes)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun observeSessions() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                repository.focusSessionsFlow.collect { sessions ->
                    adapter.submitList(sessions)
                    emptyView.isVisible = sessions.isEmpty()
                    renderStats(sessions)
                }
            }
        }
    }

    private fun renderStats(sessions: List<FocusSessionItem>) {
        val totalSessions = sessions.size
        val totalFocusMinutes = sessions.filter { it.isCompleted }.sumOf { elapsedMinutes(it) }
        val linkedTasks = sessions.mapNotNull { it.taskId }.distinct().size

        sessionsValueView.text = totalSessions.toString()
        focusMinutesValueView.text = formatMinutes(totalFocusMinutes)
        linkedValueView.text = linkedTasks.toString()
    }

    private class FocusHistoryAdapter(
        private val dateTimeFormatter: SimpleDateFormat,
        private val formatMinutes: (Int) -> String,
        private val elapsedMinutes: (FocusSessionItem) -> Int,
    ) : RecyclerView.Adapter<FocusHistoryAdapter.ViewHolder>() {

        private val items = mutableListOf<FocusSessionItem>()

        fun submitList(list: List<FocusSessionItem>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_focus_session, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position], dateTimeFormatter, formatMinutes, elapsedMinutes)
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val taskTitle: TextView = itemView.findViewById(R.id.focusHistoryTaskTitle)
            private val meta: TextView = itemView.findViewById(R.id.focusHistoryMeta)
            private val time: TextView = itemView.findViewById(R.id.focusHistoryTime)
            private val detail: TextView = itemView.findViewById(R.id.focusHistoryDetail)
            private val status: TextView = itemView.findViewById(R.id.focusHistoryStatus)

            fun bind(
                item: FocusSessionItem,
                formatter: SimpleDateFormat,
                formatMinutes: (Int) -> String,
                elapsedMinutes: (FocusSessionItem) -> Int,
            ) {
                taskTitle.text = item.taskTitle.ifBlank { itemView.context.getString(R.string.focus_task_none) }
                meta.text = "總時長 ${item.plannedMinutes} 分 · ${item.cyclesCompleted} 輪 · 專注 ${item.workMinutes} / 休息 ${item.breakMinutes}"
                val started = Date(item.startedAtMillis)
                val ended = Date(item.completedAtMillis)
                val elapsedMinutesValue = elapsedMinutes(item)
                time.text = "開始 ${formatter.format(started)} · 結束 ${formatter.format(ended)}"
                detail.text = "共耗時 ${formatMinutes(elapsedMinutesValue)}"
                val statusText = when {
                    !item.isCompleted -> "中途放棄"
                    elapsedMinutesValue > item.plannedMinutes -> "超時 ${elapsedMinutesValue - item.plannedMinutes} 分"
                    else -> "準時完成"
                }
                status.text = statusText
                status.background = ContextCompat.getDrawable(
                    itemView.context,
                    when {
                        !item.isCompleted -> R.drawable.bg_repeat_pill
                        elapsedMinutesValue > item.plannedMinutes -> R.drawable.bg_priority_medium
                        else -> R.drawable.bg_priority_low
                    }
                )
                status.setTextColor(
                    ContextCompat.getColor(
                        itemView.context,
                        when {
                            !item.isCompleted -> R.color.text_secondary
                            elapsedMinutesValue > item.plannedMinutes -> R.color.priority_medium_text
                            else -> R.color.priority_low_text
                        }
                    )
                )
            }
        }
    }

    private fun formatMinutes(minutes: Int): String {
        val hours = minutes / 60
        val remain = minutes % 60
        return when {
            hours > 0 && remain > 0 -> "${hours}h ${remain}m"
            hours > 0 -> "${hours}h"
            else -> "${remain}m"
        }
    }

    private fun elapsedMinutes(item: FocusSessionItem): Int {
        val elapsedMillis = max(0L, item.completedAtMillis - item.startedAtMillis)
        val elapsedMinutes = (elapsedMillis / 60_000L).toInt()
        return elapsedMinutes.coerceAtLeast(1)
    }
}
