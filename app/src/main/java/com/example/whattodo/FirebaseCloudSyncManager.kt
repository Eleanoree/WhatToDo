package com.example.whattodo

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

data class FirebaseConnectionState(
    val isConfigured: Boolean,
    val isSignedIn: Boolean,
    val displayName: String? = null,
    val email: String? = null,
    val googleAppId: String = "",
    val projectId: String = "",
    val webClientId: String = "",
)

data class CloudTaskRecord(
    val id: Long,
    val title: String,
    val notes: String,
    val categoryKey: String,
    val categoryTitle: String,
    val tags: List<String>,
    val dueAtMillis: Long?,
    val priority: Int,
    val repeatType: Int,
    val repeatConfigJson: String,
    val isCompleted: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val completedAtMillis: Long?,
)

data class CloudCategoryRecord(
    val key: String,
    val title: String,
    val isDefault: Boolean,
    val sortOrder: Int,
)

data class CloudFocusSessionRecord(
    val id: Long,
    val taskId: Long? = null,
    val taskTitle: String,
    val plannedMinutes: Int,
    val workMinutes: Int,
    val breakMinutes: Int,
    val cyclesCompleted: Int,
    val startedAtMillis: Long,
    val completedAtMillis: Long,
    val isCompleted: Boolean,
)

data class CloudSnapshot(
    val tasks: List<CloudTaskRecord>,
    val categories: List<CloudCategoryRecord>,
    val tagDefinitions: List<String>,
    val focusSessions: List<CloudFocusSessionRecord> = emptyList(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
) {
    fun isEmpty(): Boolean {
        return tasks.isEmpty() && categories.isEmpty() && tagDefinitions.isEmpty() && focusSessions.isEmpty()
    }

    fun toMap(): Map<String, Any?> {
        return mapOf(
            "updatedAtMillis" to updatedAtMillis,
            "tasks" to tasks.map { it.toMap() },
            "categories" to categories.map { it.toMap() },
            "tagDefinitions" to tagDefinitions,
            "focusSessions" to focusSessions.map { it.toMap() },
        )
    }

    companion object {
        fun fromMap(data: Map<String, Any?>): CloudSnapshot {
            val tasks = data.list("tasks").mapNotNull { it.asMapOrNull() }.mapNotNull { cloudTaskRecordFromMap(it) }
            val categories = data.list("categories").mapNotNull { it.asMapOrNull() }.mapNotNull { cloudCategoryRecordFromMap(it) }
            val tagDefinitions = data.list("tagDefinitions").mapNotNull { it as? String }.filter { it.isNotBlank() }
            val focusSessions = data.list("focusSessions").mapNotNull { it.asMapOrNull() }.mapNotNull { cloudFocusSessionRecordFromMap(it) }
            return CloudSnapshot(
                tasks = tasks,
                categories = categories,
                tagDefinitions = tagDefinitions.distinct(),
                focusSessions = focusSessions,
                updatedAtMillis = data.long("updatedAtMillis") ?: System.currentTimeMillis(),
            )
        }
    }
}

class FirebaseCloudSyncManager(context: Context) {
    private val appContext = context.applicationContext
    private val authManager = FirebaseAccountManager(appContext)
    private val firestore = FirebaseFirestore.getInstance()

    fun connectionState(): FirebaseConnectionState {
        val binding = authManager.bindingState()
        return FirebaseConnectionState(
            isConfigured = binding.isConfigured && binding.webClientId.isNotBlank(),
            isSignedIn = binding.isSignedIn,
            displayName = binding.displayName,
            email = binding.email,
            googleAppId = binding.applicationId,
            projectId = binding.projectId,
            webClientId = binding.webClientId,
        )
    }

    suspend fun loadSnapshot(): CloudSnapshot? {
        val uid = authManager.currentUser()?.uid ?: return null
        val doc = firestore.userSyncDocument(uid).get().awaitResult()
        val data = doc.data ?: return null
        if (data.isEmpty()) return null
        return CloudSnapshot.fromMap(data)
    }

    suspend fun saveSnapshot(snapshot: CloudSnapshot) {
        val uid = authManager.currentUser()?.uid ?: return
        firestore.userSyncDocument(uid).set(snapshot.toMap()).awaitResult()
    }

    private fun FirebaseFirestore.userSyncDocument(uid: String) =
        collection(USERS_COLLECTION).document(uid).collection(SYNC_COLLECTION).document(SYNC_DOCUMENT_ID)

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCoroutine { continuation ->
        addOnCompleteListener { task ->
            when {
                task.isSuccessful -> continuation.resume(task.result)
                task.isCanceled -> continuation.resumeWithException(IllegalStateException("Firestore 操作已取消"))
                else -> continuation.resumeWithException(task.exception ?: IllegalStateException("Firestore 操作失敗"))
            }
        }
    }

    companion object {
        private const val USERS_COLLECTION = "users"
        private const val SYNC_COLLECTION = "sync"
        private const val SYNC_DOCUMENT_ID = "main"
    }
}

private fun CloudTaskRecord.toMap(): Map<String, Any?> {
    return mapOf(
        "id" to id,
        "title" to title,
        "notes" to notes,
        "categoryKey" to categoryKey,
        "categoryTitle" to categoryTitle,
        "tags" to tags,
        "dueAtMillis" to dueAtMillis,
        "priority" to priority,
        "repeatType" to repeatType,
        "repeatConfigJson" to repeatConfigJson,
        "isCompleted" to isCompleted,
        "createdAtMillis" to createdAtMillis,
        "updatedAtMillis" to updatedAtMillis,
        "completedAtMillis" to completedAtMillis,
    )
}

private fun CloudCategoryRecord.toMap(): Map<String, Any?> {
    return mapOf(
        "key" to key,
        "title" to title,
        "isDefault" to isDefault,
        "sortOrder" to sortOrder,
    )
}

private fun CloudFocusSessionRecord.toMap(): Map<String, Any?> {
    return mapOf(
        "id" to id,
        "taskId" to taskId,
        "taskTitle" to taskTitle,
        "plannedMinutes" to plannedMinutes,
        "workMinutes" to workMinutes,
        "breakMinutes" to breakMinutes,
        "cyclesCompleted" to cyclesCompleted,
        "startedAtMillis" to startedAtMillis,
        "completedAtMillis" to completedAtMillis,
        "isCompleted" to isCompleted,
    )
}

private fun cloudTaskRecordFromMap(data: Map<String, Any?>): CloudTaskRecord? {
    val id = data.long("id") ?: return null
    val title = data.string("title").orEmpty()
    if (title.isBlank()) return null
    return CloudTaskRecord(
        id = id,
        title = title,
        notes = data.string("notes").orEmpty(),
        categoryKey = data.string("categoryKey").orEmpty(),
        categoryTitle = data.string("categoryTitle").orEmpty(),
        tags = data.list("tags").mapNotNull { it as? String }.filter { it.isNotBlank() },
        dueAtMillis = data.long("dueAtMillis"),
        priority = data.int("priority") ?: TaskItem.PRIORITY_MEDIUM,
        repeatType = data.int("repeatType") ?: TaskRepeatRule.TYPE_NONE,
        repeatConfigJson = data.string("repeatConfigJson").orEmpty().ifBlank { "{}" },
        isCompleted = data.bool("isCompleted") ?: false,
        createdAtMillis = data.long("createdAtMillis") ?: System.currentTimeMillis(),
        updatedAtMillis = data.long("updatedAtMillis") ?: System.currentTimeMillis(),
        completedAtMillis = data.long("completedAtMillis"),
    )
}

private fun cloudCategoryRecordFromMap(data: Map<String, Any?>): CloudCategoryRecord? {
    val key = data.string("key").orEmpty()
    val title = data.string("title").orEmpty()
    if (key.isBlank() || title.isBlank()) return null
    return CloudCategoryRecord(
        key = key,
        title = title,
        isDefault = data.bool("isDefault") ?: false,
        sortOrder = data.int("sortOrder") ?: 0,
    )
}

private fun cloudFocusSessionRecordFromMap(data: Map<String, Any?>): CloudFocusSessionRecord? {
    val id = data.long("id") ?: return null
    val taskTitle = data.string("taskTitle").orEmpty()
    if (taskTitle.isBlank()) return null
    return CloudFocusSessionRecord(
        id = id,
        taskId = data.long("taskId"),
        taskTitle = taskTitle,
        plannedMinutes = data.int("plannedMinutes") ?: 0,
        workMinutes = data.int("workMinutes") ?: 0,
        breakMinutes = data.int("breakMinutes") ?: 0,
        cyclesCompleted = data.int("cyclesCompleted") ?: 0,
        startedAtMillis = data.long("startedAtMillis") ?: data.long("completedAtMillis") ?: System.currentTimeMillis(),
        completedAtMillis = data.long("completedAtMillis") ?: System.currentTimeMillis(),
        isCompleted = data.bool("isCompleted") ?: false,
    )
}

private fun Map<String, Any?>.string(key: String): String? = this[key] as? String
private fun Map<String, Any?>.long(key: String): Long? = when (val value = this[key]) {
    is Long -> value
    is Int -> value.toLong()
    is Double -> value.toLong()
    is String -> value.toLongOrNull()
    else -> null
}
private fun Map<String, Any?>.int(key: String): Int? = when (val value = this[key]) {
    is Int -> value
    is Long -> value.toInt()
    is Double -> value.toInt()
    is String -> value.toIntOrNull()
    else -> null
}
private fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean
private fun Map<String, Any?>.list(key: String): List<Any?> = this[key] as? List<Any?> ?: emptyList()
private fun Any?.asMapOrNull(): Map<String, Any?>? {
    val raw = this as? Map<*, *> ?: return null
    val result = linkedMapOf<String, Any?>()
    raw.forEach { (key, value) ->
        if (key is String) {
            result[key] = value
        }
    }
    return result
}
