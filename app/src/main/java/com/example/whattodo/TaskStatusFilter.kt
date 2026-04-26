package com.example.whattodo

import androidx.annotation.StringRes

enum class TaskStatusFilter(@param:StringRes val labelRes: Int) {
    ALL(R.string.status_all),
    ACTIVE(R.string.status_active),
    COMPLETED(R.string.status_completed),
}
