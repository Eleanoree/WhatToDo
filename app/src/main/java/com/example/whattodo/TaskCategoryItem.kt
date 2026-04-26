package com.example.whattodo

data class TaskCategoryItem(
    val key: String,
    val title: String,
    val isDefault: Boolean,
    val sortOrder: Int,
)
