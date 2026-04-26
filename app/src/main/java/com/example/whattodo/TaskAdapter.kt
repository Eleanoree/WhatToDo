package com.example.whattodo

import android.graphics.Paint
import android.view.animation.OvershootInterpolator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class TaskAdapter(
    private val onTaskClicked: (TaskItem) -> Unit,
    private val onTaskCompletedChanged: (TaskItem, Boolean) -> Unit,
) : ListAdapter<TaskItem, TaskAdapter.TaskViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_task, parent, false)
        return TaskViewHolder(view, onTaskClicked, onTaskCompletedChanged)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class TaskViewHolder(
        itemView: View,
        private val onTaskClicked: (TaskItem) -> Unit,
        private val onTaskCompletedChanged: (TaskItem, Boolean) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {
        private val taskCard: View = itemView.findViewById(R.id.taskCard)
        private val doneCheckBox: MaterialCheckBox = itemView.findViewById(R.id.taskDoneCheckBox)
        private val titleText: TextView = itemView.findViewById(R.id.taskTitleText)
        private val noteText: TextView = itemView.findViewById(R.id.taskNoteText)
        private val dueDateText: TextView = itemView.findViewById(R.id.taskDueDateText)
        private val priorityText: TextView = itemView.findViewById(R.id.taskPriorityText)
        private val categoryText: TextView = itemView.findViewById(R.id.taskCategoryText)
        private val tagsText: TextView = itemView.findViewById(R.id.taskTagsText)
        private val repeatText: TextView = itemView.findViewById(R.id.taskRepeatText)
        private val completedBadgeText: TextView = itemView.findViewById(R.id.taskCompletedBadgeText)
        private val sparkleContainer: View = itemView.findViewById(R.id.taskSparkleContainer)
        private val sparkleLeft: TextView = itemView.findViewById(R.id.taskSparkleLeft)
        private val sparkleCenter: TextView = itemView.findViewById(R.id.taskSparkleCenter)
        private val sparkleRight: TextView = itemView.findViewById(R.id.taskSparkleRight)

        fun bind(task: TaskItem) {
            titleText.text = task.title
            noteText.text = task.notes
            noteText.isVisible = task.notes.isNotBlank()

            dueDateText.isVisible = task.dueAtMillis != null
            dueDateText.text = task.dueAtMillis?.let {
                itemView.context.getString(R.string.due_date_prefix, formatDate(it))
            }

            bindPriority(task.priority)
            bindCategory(task.categoryTitle.ifBlank { task.categoryKey })
            bindTags(task.tags)
            bindRepeat(task.repeatRule)
            bindCompletion(task.isCompleted)

            taskCard.setOnClickListener { onTaskClicked(task) }
            doneCheckBox.setOnCheckedChangeListener(null)
            doneCheckBox.isChecked = task.isCompleted
            doneCheckBox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked != task.isCompleted) {
                    playCompletionAnimation(isChecked)
                    onTaskCompletedChanged(task, isChecked)
                }
            }
        }

        private fun bindCompletion(isCompleted: Boolean) {
            val strikeFlags = if (isCompleted) {
                titleText.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                titleText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
            titleText.paintFlags = strikeFlags
            noteText.paintFlags = if (isCompleted) {
                noteText.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                noteText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
            dueDateText.paintFlags = if (isCompleted) {
                dueDateText.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                dueDateText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
            priorityText.paintFlags = if (isCompleted) {
                priorityText.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                priorityText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
            categoryText.paintFlags = if (isCompleted) {
                categoryText.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                categoryText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
            tagsText.paintFlags = if (isCompleted) {
                tagsText.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                tagsText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
            repeatText.paintFlags = if (isCompleted) {
                repeatText.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                repeatText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }

            val alpha = if (isCompleted) 0.7f else 1f
            titleText.alpha = alpha
            noteText.alpha = alpha
            dueDateText.alpha = alpha
            priorityText.alpha = alpha
            categoryText.alpha = alpha
            tagsText.alpha = alpha
            repeatText.alpha = alpha
            completedBadgeText.isVisible = isCompleted
            completedBadgeText.alpha = if (isCompleted) 1f else 0f
            if (!isCompleted) {
                sparkleContainer.isVisible = false
                resetSparkleView(sparkleLeft)
                resetSparkleView(sparkleCenter)
                resetSparkleView(sparkleRight)
            }
        }

        private fun playCompletionAnimation(isCompleted: Boolean) {
            val scale = if (isCompleted) 1.04f else 1f
            taskCard.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(120)
                .setInterpolator(OvershootInterpolator())
                .withEndAction {
                    taskCard.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(160)
                        .start()
                }
                .start()

            if (isCompleted) {
                sparkleContainer.isVisible = true
                animateSparkle(sparkleLeft, 0L, -18f)
                animateSparkle(sparkleCenter, 90L, -24f)
                animateSparkle(sparkleRight, 180L, -20f)
                sparkleContainer.postDelayed({
                    sparkleContainer.isVisible = false
                    resetSparkleView(sparkleLeft)
                    resetSparkleView(sparkleCenter)
                    resetSparkleView(sparkleRight)
                }, 900L)

                completedBadgeText.apply {
                    scaleX = 0.8f
                    scaleY = 0.8f
                    alpha = 0f
                    isVisible = true
                    animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(180)
                        .setInterpolator(OvershootInterpolator())
                        .start()
                }
            }
        }

        private fun animateSparkle(view: TextView, delayMs: Long, translationY: Float) {
            view.alpha = 0f
            view.scaleX = 0.7f
            view.scaleY = 0.7f
            view.translationY = 10f
            view.animate()
                .alpha(1f)
                .translationY(translationY)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(delayMs)
                .setDuration(240)
                .setInterpolator(OvershootInterpolator())
                .start()
        }

        private fun resetSparkleView(view: TextView) {
            view.alpha = 0f
            view.translationY = 10f
            view.scaleX = 0.7f
            view.scaleY = 0.7f
        }

        private fun bindPriority(priority: Int) {
            val (labelRes, backgroundRes, textColorRes) = when (priority) {
                TaskItem.PRIORITY_LOW -> Triple(R.string.priority_low, R.drawable.bg_priority_low, R.color.priority_low_text)
                TaskItem.PRIORITY_HIGH -> Triple(R.string.priority_high, R.drawable.bg_priority_high, R.color.priority_high_text)
                else -> Triple(R.string.priority_medium, R.drawable.bg_priority_medium, R.color.priority_medium_text)
            }
            priorityText.setBackgroundResource(backgroundRes)
            priorityText.setTextColor(itemView.context.getColor(textColorRes))
            priorityText.text = itemView.context.getString(labelRes)
        }

        private fun bindCategory(categoryTitle: String) {
            categoryText.setBackgroundResource(R.drawable.bg_category_pill)
            categoryText.setTextColor(itemView.context.getColor(R.color.category_chip_text))
            categoryText.text = itemView.context.getString(R.string.category_label_prefix, categoryTitle)
        }

        private fun bindTags(tags: List<String>) {
            if (tags.isEmpty()) {
                tagsText.isVisible = false
                return
            }

            tagsText.isVisible = true
            tagsText.text = itemView.context.getString(
                R.string.tags_label_prefix,
                tags.joinToString("  ") { "#$it" },
            )
            tagsText.setTextColor(itemView.context.getColor(R.color.tag_chip_text))
        }

        private fun bindRepeat(repeatRule: TaskRepeatRule) {
            if (repeatRule is TaskRepeatRule.None) {
                repeatText.isVisible = false
                return
            }

            repeatText.isVisible = true
            repeatText.text = itemView.context.getString(R.string.repeat_label_prefix, repeatRule.summary(itemView.context))
        }

        private fun formatDate(dueAtMillis: Long): String {
            val dateFormatter = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return if (hasExplicitTime(dueAtMillis)) {
                val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
                "${dateFormatter.format(Date(dueAtMillis))} ${timeFormatter.format(Date(dueAtMillis))}"
            } else {
                dateFormatter.format(Date(dueAtMillis))
            }
        }

        private fun hasExplicitTime(dueAtMillis: Long): Boolean = dueAtMillis % MILLIS_PER_DAY != 0L
    }

    private object DiffCallback : DiffUtil.ItemCallback<TaskItem>() {
        override fun areItemsTheSame(oldItem: TaskItem, newItem: TaskItem): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: TaskItem, newItem: TaskItem): Boolean = oldItem == newItem
    }
}
