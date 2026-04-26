package com.example.whattodo

data class TaskItem(
    val id: Long,
    val title: String,
    val notes: String = "",
    val categoryKey: String = TaskCategories.OTHER_KEY,
    val categoryTitle: String = "",
    val tags: List<String> = emptyList(),
    val dueAtMillis: Long? = null,
    val priority: Int = PRIORITY_MEDIUM,
    val repeatRule: TaskRepeatRule = TaskRepeatRule.None,
    val isCompleted: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis,
    val completedAtMillis: Long? = null,
) {
    companion object {
        const val PRIORITY_LOW = 0
        const val PRIORITY_MEDIUM = 1
        const val PRIORITY_HIGH = 2
    }
}
