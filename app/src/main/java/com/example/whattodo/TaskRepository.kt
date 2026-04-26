package com.example.whattodo

import android.content.Context
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.LinkedHashMap
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

data class DashboardData(
    val tasks: List<TaskItem>,
    val categories: List<TaskCategoryItem>,
)

data class FocusSessionItem(
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

data class DayCompletionStat(
    val label: String,
    val completedCount: Int,
    val secondaryLabel: String = "",
)

data class AnalyticsSnapshot(
    val weeklyCompleted: Int,
    val weeklyActivityCount: Int,
    val weeklyCompletionRate: Int,
    val monthlyCompleted: Int,
    val monthlyActivityCount: Int,
    val monthlyCompletionRate: Int,
    val weeklyTrend: List<DayCompletionStat>,
    val monthlyTrend: List<DayCompletionStat>,
    val focusMinutesThisWeek: Int,
    val focusMinutesThisMonth: Int,
)

class TaskRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = TaskDatabase.create(appContext)
    private val dao = database.taskDao()
    private val legacyPreferences = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
    private val cloudSyncManager = FirebaseCloudSyncManager(appContext)
    private val cloudSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cloudSyncJob: Job? = null

    val dashboardFlow: Flow<DashboardData> = combine(
        dao.observeTasks(),
        dao.observeCategories(),
    ) { taskEntities, categoryEntities ->
        val categories = categoryEntities.map { it.toDomainCategory() }
        val categoryMap = categories.associateBy { it.key }
        val tasks = taskEntities.map { it.toDomainTask(categoryMap) }
        DashboardData(tasks = tasks, categories = categories)
    }

    val categoriesFlow: Flow<List<TaskCategoryItem>> = dao.observeCategories().map { entities ->
        entities.map { it.toDomainCategory() }
    }

    val tagSuggestionsFlow: Flow<List<String>> = dao.observeTagDefinitions().map { tags ->
        normalizeTags(tags.map { it.name })
    }

    val focusSessionsFlow: Flow<List<FocusSessionItem>> = dao.observeFocusSessions().map { sessions ->
        sessions.map { it.toDomainFocusSession() }
    }

    val analyticsFlow: Flow<AnalyticsSnapshot> = combine(
        dashboardFlow,
        focusSessionsFlow,
    ) { dashboard, focusSessions ->
        dashboard.toAnalyticsSnapshot(focusSessions)
    }

    init {
        runBlocking(Dispatchers.IO) {
            seedCategoriesIfNeeded()
            migrateLegacyTasksIfNeeded()
            seedDemoTasksIfNeeded()
            seedDemoFocusSessionsIfNeeded()
            seedDemoPreviewDataIfNeeded()
        }
    }

    suspend fun upsertTask(task: TaskItem) = withContext(Dispatchers.IO) {
        val normalized = task.sanitized()
        database.runInTransaction {
            if (normalized.tags.isNotEmpty()) {
                dao.upsertTagDefinitions(normalized.tags.map { TaskTagDefinitionEntity(it) })
            }
            dao.deleteTagsForTask(normalized.id)
            dao.upsertTask(normalized.toEntity())
            dao.upsertTags(normalized.tags.map { TaskTagEntity(normalized.id, it) })
        }
        scheduleCloudPush()
    }

    suspend fun deleteTask(taskId: Long) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            dao.deleteTagsForTask(taskId)
            dao.deleteTaskById(taskId)
        }
        scheduleCloudPush()
    }

    suspend fun completeTask(task: TaskItem) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val nextDue = TaskRepeatRule.nextDueAfter(
            rule = task.repeatRule,
            anchorMillis = task.dueAtMillis ?: now,
            timeZone = TimeZone.getTimeZone("UTC"),
        )

        val updatedTask = if (task.repeatRule is TaskRepeatRule.None || nextDue == null) {
            task.copy(
                isCompleted = true,
                completedAtMillis = now,
                updatedAtMillis = now,
            )
        } else {
            task.copy(
                dueAtMillis = nextDue,
                isCompleted = false,
                completedAtMillis = now,
                updatedAtMillis = now,
            )
        }

        upsertTask(updatedTask)
    }

    suspend fun recordFocusSession(session: FocusSessionItem) = withContext(Dispatchers.IO) {
        dao.upsertFocusSession(session.toEntity())
        scheduleCloudPush()
    }

    suspend fun upsertCategory(category: TaskCategoryItem) = withContext(Dispatchers.IO) {
        dao.upsertCategory(category.toEntity())
        scheduleCloudPush()
    }

    suspend fun ensureTagExists(tagName: String) = withContext(Dispatchers.IO) {
        val normalized = normalizeTags(listOf(tagName)).firstOrNull() ?: return@withContext
        dao.upsertTagDefinitions(listOf(TaskTagDefinitionEntity(normalized)))
        scheduleCloudPush()
    }

    suspend fun renameTag(oldName: String, newName: String) = withContext(Dispatchers.IO) {
        val oldNormalized = normalizeTags(listOf(oldName)).firstOrNull() ?: return@withContext
        val newNormalized = normalizeTags(listOf(newName)).firstOrNull() ?: return@withContext
        if (oldNormalized == newNormalized) return@withContext

        database.runInTransaction {
            dao.upsertTagDefinitions(listOf(TaskTagDefinitionEntity(newNormalized)))
            dao.deleteConflictingTagAssignments(oldNormalized, newNormalized)
            dao.renameTagAssignments(oldNormalized, newNormalized)
            dao.deleteTagDefinition(oldNormalized)
        }
        scheduleCloudPush()
    }

    suspend fun deleteTag(tagName: String) = withContext(Dispatchers.IO) {
        val normalized = normalizeTags(listOf(tagName)).firstOrNull() ?: return@withContext
        database.runInTransaction {
            dao.deleteTagEverywhere(normalized)
            dao.deleteTagDefinition(normalized)
        }
        scheduleCloudPush()
    }

    suspend fun countTagUsage(tagName: String): Int = withContext(Dispatchers.IO) {
        val normalized = normalizeTags(listOf(tagName)).firstOrNull() ?: return@withContext 0
        dao.countTagUsage(normalized)
    }

    fun cloudConnectionState(): FirebaseConnectionState {
        return cloudSyncManager.connectionState()
    }

    suspend fun syncWithCloud() = withContext(Dispatchers.IO) {
        if (!cloudSyncManager.connectionState().isSignedIn) return@withContext
        val localSnapshot = captureCloudSnapshot()
        val remoteSnapshot = cloudSyncManager.loadSnapshot()
        val mergedSnapshot = mergeCloudSnapshots(localSnapshot, remoteSnapshot)
        applyCloudSnapshot(mergedSnapshot)
        cloudSyncManager.saveSnapshot(mergedSnapshot)
    }

    suspend fun deleteCategory(categoryKey: String) = withContext(Dispatchers.IO) {
        if (TaskCategories.isDefaultKey(categoryKey)) return@withContext
        database.runInTransaction {
            dao.moveTasksToCategory(categoryKey, TaskCategories.OTHER_KEY)
            dao.deleteCategoryByKey(categoryKey)
        }
        scheduleCloudPush()
    }

    suspend fun ensureCustomCategory(title: String): TaskCategoryItem = withContext(Dispatchers.IO) {
        val normalizedTitle = title.trim()
        val key = buildCategoryKey(normalizedTitle)
        val category = TaskCategoryItem(
            key = key,
            title = normalizedTitle,
            isDefault = false,
            sortOrder = 100,
        )
        dao.upsertCategory(category.toEntity())
        scheduleCloudPush()
        category
    }

    private fun scheduleCloudPush() {
        if (!cloudSyncManager.connectionState().isSignedIn) return
        cloudSyncJob?.cancel()
        cloudSyncJob = cloudSyncScope.launch {
            delay(600L)
            runCatching {
                val localSnapshot = captureCloudSnapshot()
                cloudSyncManager.saveSnapshot(localSnapshot)
            }
        }
    }

    private suspend fun captureCloudSnapshot(): CloudSnapshot = withContext(Dispatchers.IO) {
        val categories = dao.loadCategoriesOnce().map { entity ->
            CloudCategoryRecord(
                key = entity.key,
                title = entity.title,
                isDefault = entity.isDefault,
                sortOrder = entity.sortOrder,
            )
        }
        val categoryMap = categories.associateBy { it.key }
        val tasks = dao.loadTasksOnce().map { taskWithTags ->
            taskWithTags.toCloudTaskRecord(categoryMap)
        }
        val tagDefinitions = dao.loadTagDefinitionsOnce().map { it.name }.filter { it.isNotBlank() }.distinct()
        val focusSessions = dao.loadFocusSessionsOnce().map { it.toCloudFocusSessionRecord() }
        CloudSnapshot(
            tasks = tasks,
            categories = categories,
            tagDefinitions = tagDefinitions,
            focusSessions = focusSessions,
        )
    }

    private suspend fun applyCloudSnapshot(snapshot: CloudSnapshot) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            dao.deleteTagsForAllTasks()
            dao.deleteAllTasks()
            dao.deleteAllCategories()
            dao.deleteAllTagDefinitions()
            dao.deleteAllFocusSessions()

            if (snapshot.categories.isEmpty()) {
                dao.upsertCategories(defaultCategories().map { it.toEntity() })
            } else {
                dao.upsertCategories(snapshot.categories.map { it.toEntity() })
            }

            val mergedTagDefinitions = snapshot.tagDefinitions
                .toMutableSet()
                .apply {
                    snapshot.tasks.forEach { addAll(it.tags) }
                }
                .filter { it.isNotBlank() }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)

            if (mergedTagDefinitions.isNotEmpty()) {
                dao.upsertTagDefinitions(mergedTagDefinitions.map { TaskTagDefinitionEntity(it) })
            }

            snapshot.tasks.forEach { record ->
                dao.upsertTask(record.toEntity())
                if (record.tags.isNotEmpty()) {
                    dao.upsertTags(record.tags.map { TaskTagEntity(record.id, it) })
                }
            }

            snapshot.focusSessions.forEach { record ->
                dao.upsertFocusSession(record.toEntity())
            }
        }
    }

    private fun mergeCloudSnapshots(local: CloudSnapshot, remote: CloudSnapshot?): CloudSnapshot {
        if (remote == null || remote.isEmpty()) return local
        if (local.isEmpty()) return remote

        val localTasks = local.tasks.associateBy { it.id }
        val remoteTasks = remote.tasks.associateBy { it.id }
        val mergedTasks = buildList {
            addAll(localTasks.keys)
            addAll(remoteTasks.keys)
        }.distinct().mapNotNull { id ->
            val localTask = localTasks[id]
            val remoteTask = remoteTasks[id]
            when {
                localTask == null -> remoteTask
                remoteTask == null -> localTask
                localTask.updatedAtMillis >= remoteTask.updatedAtMillis -> localTask
                else -> remoteTask
            }
        }

        val localCategories = local.categories.associateBy { it.key }
        val remoteCategories = remote.categories.associateBy { it.key }
        val mergedCategories = buildList {
            addAll(remote.categories)
            addAll(local.categories)
        }.distinctBy { it.key }.mapNotNull { category ->
            localCategories[category.key] ?: remoteCategories[category.key] ?: category
        }

        val mergedTags = buildSet {
            addAll(remote.tagDefinitions)
            addAll(local.tagDefinitions)
            mergedTasks.forEach { addAll(it.tags) }
        }.filter { it.isNotBlank() }.sortedWith(String.CASE_INSENSITIVE_ORDER)

        val localFocusSessions = local.focusSessions.associateBy { it.id }
        val remoteFocusSessions = remote.focusSessions.associateBy { it.id }
        val mergedFocusSessions = buildList {
            addAll(localFocusSessions.keys)
            addAll(remoteFocusSessions.keys)
        }.distinct().mapNotNull { id ->
            val localSession = localFocusSessions[id]
            val remoteSession = remoteFocusSessions[id]
            when {
                localSession == null -> remoteSession
                remoteSession == null -> localSession
                localSession.completedAtMillis >= remoteSession.completedAtMillis -> localSession
                else -> remoteSession
            }
        }

        return CloudSnapshot(
            tasks = mergedTasks,
            categories = mergedCategories,
            tagDefinitions = mergedTags,
            focusSessions = mergedFocusSessions,
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    private suspend fun seedCategoriesIfNeeded() = withContext(Dispatchers.IO) {
        if (dao.countCategories() > 0) return@withContext
        dao.upsertCategories(defaultCategories().map { it.toEntity() })
    }

    private suspend fun migrateLegacyTasksIfNeeded() = withContext(Dispatchers.IO) {
        if (dao.countTasks() > 0) return@withContext

        val legacyTasks = loadLegacyTasks()
        if (legacyTasks.isEmpty()) return@withContext

        database.runInTransaction {
            legacyTasks.forEach { task ->
                dao.upsertTask(task.toEntity())
                dao.upsertTags(task.tags.map { TaskTagEntity(task.id, it) })
            }
        }

        legacyPreferences.edit().remove(LEGACY_TASKS_KEY).apply()
    }

    private suspend fun seedDemoTasksIfNeeded() = withContext(Dispatchers.IO) {
        if (dao.countTasks() > 0) return@withContext

        val now = System.currentTimeMillis()
        val demoTasks = buildDemoTasks(now)
        database.runInTransaction {
            demoTasks.forEach { task ->
                val normalized = task.sanitized()
                if (normalized.tags.isNotEmpty()) {
                    dao.upsertTagDefinitions(normalized.tags.map { TaskTagDefinitionEntity(it) })
                }
                dao.upsertTask(normalized.toEntity())
                dao.upsertTags(normalized.tags.map { TaskTagEntity(normalized.id, it) })
            }
        }
    }

    private suspend fun seedDemoFocusSessionsIfNeeded() = withContext(Dispatchers.IO) {
        if (dao.loadFocusSessionsOnce().isNotEmpty()) return@withContext

        val now = System.currentTimeMillis()
        val day = 24L * 60L * 60L * 1000L
        val samples = listOf(
            FocusSessionItem(30001L, 10003L, "Demo 03 - 每週六倒垃圾", 120, 25, 5, 4, now - day - 120 * 60_000L, now - day, true),
            FocusSessionItem(30002L, 10004L, "Demo 04 - 每週一規劃工作", 50, 25, 5, 2, now - day * 2 - 55 * 60_000L, now - day * 2, true),
            FocusSessionItem(30003L, 10018L, "Demo 18 - 讀完 1 章書", 25, 25, 5, 1, now - day * 3 - 28 * 60_000L, now - day * 3, true),
            FocusSessionItem(30004L, null, "無關聯任務", 25, 25, 5, 1, now - day * 4 - 12 * 60_000L, now - day * 4, false),
        )

        database.runInTransaction {
            samples.forEach { session ->
                dao.upsertFocusSession(session.toEntity())
            }
        }
    }

    private suspend fun seedDemoPreviewDataIfNeeded() = withContext(Dispatchers.IO) {
        if ((appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return@withContext
        if (dao.countCompletedTasks() >= 12 && dao.countCompletedFocusSessions() >= 10) return@withContext

        val now = System.currentTimeMillis()
        val previewTasks = buildPreviewTasks(now)
        val previewSessions = buildPreviewFocusSessions(now)

        database.runInTransaction {
            previewTasks.forEach { task ->
                val normalized = task.sanitized()
                if (normalized.tags.isNotEmpty()) {
                    dao.upsertTagDefinitions(normalized.tags.map { TaskTagDefinitionEntity(it) })
                }
                dao.upsertTask(normalized.toEntity())
                dao.upsertTags(normalized.tags.map { TaskTagEntity(normalized.id, it) })
            }
            previewSessions.forEach { session ->
                dao.upsertFocusSession(session.toEntity())
            }
        }
    }

    private fun loadLegacyTasks(): List<TaskItem> {
        val raw = legacyPreferences.getString(LEGACY_TASKS_KEY, "[]") ?: "[]"
        val jsonArray = JSONArray(raw)
        val result = mutableListOf<TaskItem>()

        for (index in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(index)
            result += TaskItem(
                id = item.optLong("id"),
                title = item.optString("title"),
                notes = item.optString("notes"),
                categoryKey = TaskCategories.OTHER_KEY,
                categoryTitle = "其他",
                tags = emptyList(),
                dueAtMillis = item.optLong("dueAtMillis", -1L).takeIf { it >= 0L },
                priority = item.optInt("priority", TaskItem.PRIORITY_MEDIUM),
                repeatRule = TaskRepeatRule.None,
                isCompleted = item.optBoolean("isCompleted", false),
                createdAtMillis = item.optLong("createdAtMillis", System.currentTimeMillis()),
                updatedAtMillis = item.optLong("updatedAtMillis", System.currentTimeMillis()),
                completedAtMillis = item.optLong("completedAtMillis", -1L).takeIf { it >= 0L }
                    ?: if (item.optBoolean("isCompleted", false)) item.optLong("updatedAtMillis", System.currentTimeMillis()) else null,
            )
        }

        return result
    }

    private fun TaskItem.toEntity(): TaskEntity = TaskEntity(
        id,
        title.trim(),
        notes.trim(),
        TaskCategories.normalizeKey(categoryKey),
        dueAtMillis,
        priority,
        repeatRule.toStorage().type,
        repeatRule.toStorage().json,
        isCompleted,
        createdAtMillis,
        updatedAtMillis,
        completedAtMillis,
    )

    private fun TaskEntity.toDomainTask(categoryMap: Map<String, TaskCategoryItem>): TaskItem {
        val repeatRule = TaskRepeatRule.fromStorage(repeatType, repeatConfigJson)
        val category = categoryMap[TaskCategories.normalizeKey(categoryKey)]
        return TaskItem(
            id = id,
            title = title,
            notes = notes,
            categoryKey = TaskCategories.normalizeKey(categoryKey),
            categoryTitle = category?.title ?: categoryKey,
            tags = emptyList(),
            dueAtMillis = dueAtMillis,
            priority = priority,
            repeatRule = repeatRule,
            isCompleted = isCompleted,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis,
            completedAtMillis = completedAtMillis,
        )
    }

    private fun TaskWithTags.toDomainTask(categoryMap: Map<String, TaskCategoryItem>): TaskItem {
        val taskEntity = task
        val repeatRule = TaskRepeatRule.fromStorage(taskEntity.repeatType, taskEntity.repeatConfigJson)
        val category = categoryMap[TaskCategories.normalizeKey(taskEntity.categoryKey)]
        return TaskItem(
            id = taskEntity.id,
            title = taskEntity.title,
            notes = taskEntity.notes,
            categoryKey = TaskCategories.normalizeKey(taskEntity.categoryKey),
            categoryTitle = category?.title ?: taskEntity.categoryKey,
            tags = normalizeTags(tags.orEmpty().map { it.name }),
            dueAtMillis = taskEntity.dueAtMillis,
            priority = taskEntity.priority,
            repeatRule = repeatRule,
            isCompleted = taskEntity.isCompleted,
            createdAtMillis = taskEntity.createdAtMillis,
            updatedAtMillis = taskEntity.updatedAtMillis,
            completedAtMillis = taskEntity.completedAtMillis,
        )
    }

    private fun FocusSessionItem.toEntity(): FocusSessionEntity {
        return FocusSessionEntity(
            id,
            taskId,
            taskTitle,
            plannedMinutes,
            workMinutes,
            breakMinutes,
            cyclesCompleted,
            startedAtMillis,
            completedAtMillis,
            isCompleted,
        )
    }

    private fun FocusSessionEntity.toDomainFocusSession(): FocusSessionItem {
        return FocusSessionItem(
            id = id,
            taskId = taskId,
            taskTitle = taskTitle.orEmpty(),
            plannedMinutes = plannedMinutes,
            workMinutes = workMinutes,
            breakMinutes = breakMinutes,
            cyclesCompleted = cyclesCompleted,
            startedAtMillis = startedAtMillis,
            completedAtMillis = completedAtMillis,
            isCompleted = isCompleted,
        )
    }

    private fun FocusSessionEntity.toCloudFocusSessionRecord(): CloudFocusSessionRecord {
        return CloudFocusSessionRecord(
            id = id,
            taskId = taskId,
            taskTitle = taskTitle.orEmpty(),
            plannedMinutes = plannedMinutes,
            workMinutes = workMinutes,
            breakMinutes = breakMinutes,
            cyclesCompleted = cyclesCompleted,
            startedAtMillis = startedAtMillis,
            completedAtMillis = completedAtMillis,
            isCompleted = isCompleted,
        )
    }

    private fun DashboardData.toAnalyticsSnapshot(focusSessions: List<FocusSessionItem>): AnalyticsSnapshot {
        val now = System.currentTimeMillis()
        val weekStart = startOfWeek(now)
        val monthStart = startOfMonth(now)

        val completedTasks = tasks.filter { it.completedAtMillis != null }
        val weeklyCompletedTasks = completedTasks.filter { (it.completedAtMillis ?: 0L) >= weekStart }
        val monthlyCompletedTasks = completedTasks.filter { (it.completedAtMillis ?: 0L) >= monthStart }

        val weeklyTouchedTasks = tasks.filter {
            (it.completedAtMillis ?: 0L) >= weekStart || it.createdAtMillis >= weekStart
        }
        val monthlyTouchedTasks = tasks.filter {
            (it.completedAtMillis ?: 0L) >= monthStart || it.createdAtMillis >= monthStart
        }

        val weeklyRate = percentage(weeklyCompletedTasks.size, weeklyTouchedTasks.size)
        val monthlyRate = percentage(monthlyCompletedTasks.size, monthlyTouchedTasks.size)

        val weeklyTrend = (0..6).map { offset ->
            val dayStart = weekStart + offset * DAY_MILLIS
            val dayEnd = dayStart + DAY_MILLIS
            val count = completedTasks.count { completed ->
                val completedAt = completed.completedAtMillis ?: 0L
                completedAt in dayStart until dayEnd
            }
            DayCompletionStat(
                label = dayLabelForTime(dayStart),
                secondaryLabel = weekdayShortLabel(dayStart),
                completedCount = count,
            )
        }

        val currentCalendar = Calendar.getInstance().apply { timeInMillis = now }
        val currentYear = currentCalendar.get(Calendar.YEAR)
        val currentMonth = currentCalendar.get(Calendar.MONTH)
        val monthlyTrend = (1..6).map { weekIndex ->
            val count = completedTasks.count { completed ->
                val completedCalendar = Calendar.getInstance().apply { timeInMillis = completed.completedAtMillis ?: 0L }
                completedCalendar.get(Calendar.YEAR) == currentYear &&
                    completedCalendar.get(Calendar.MONTH) == currentMonth &&
                    completedCalendar.get(Calendar.WEEK_OF_MONTH) == weekIndex
            }
            DayCompletionStat(
                label = "第$weekIndex 週",
                completedCount = count,
            )
        }

        val focusMinutesThisWeek = focusSessions
            .filter { it.isCompleted && it.completedAtMillis >= weekStart }
            .sumOf { focusSessionMinutes(it) }
        val focusMinutesThisMonth = focusSessions
            .filter { it.isCompleted && it.completedAtMillis >= monthStart }
            .sumOf { focusSessionMinutes(it) }

        return AnalyticsSnapshot(
            weeklyCompleted = weeklyCompletedTasks.size,
            weeklyActivityCount = weeklyTouchedTasks.size,
            weeklyCompletionRate = weeklyRate,
            monthlyCompleted = monthlyCompletedTasks.size,
            monthlyActivityCount = monthlyTouchedTasks.size,
            monthlyCompletionRate = monthlyRate,
            weeklyTrend = weeklyTrend,
            monthlyTrend = monthlyTrend,
            focusMinutesThisWeek = focusMinutesThisWeek,
            focusMinutesThisMonth = focusMinutesThisMonth,
        )
    }

    private fun percentage(completed: Int, total: Int): Int {
        if (total <= 0) return 0
        return ((completed * 100f) / total).toInt().coerceIn(0, 100)
    }

    private fun startOfWeek(millis: Long): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        while (calendar.timeInMillis > millis) {
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
        return calendar.timeInMillis
    }

    private fun startOfMonth(millis: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun dayLabelForTime(millis: Long): String {
        val pattern = java.text.SimpleDateFormat("E", Locale.getDefault())
        return pattern.format(java.util.Date(millis))
    }

    private fun weekdayShortLabel(millis: Long): String {
        return when (Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "一"
            Calendar.TUESDAY -> "二"
            Calendar.WEDNESDAY -> "三"
            Calendar.THURSDAY -> "四"
            Calendar.FRIDAY -> "五"
            Calendar.SATURDAY -> "六"
            else -> "日"
        }
    }

    private fun TaskCategoryEntity.toDomainCategory(): TaskCategoryItem {
        return TaskCategoryItem(
            key = key,
            title = title,
            isDefault = isDefault,
            sortOrder = sortOrder,
        )
    }

    private fun TaskCategoryItem.toEntity(): TaskCategoryEntity {
        return TaskCategoryEntity(key, title, isDefault, sortOrder)
    }

    private fun CloudCategoryRecord.toEntity(): TaskCategoryEntity {
        return TaskCategoryEntity(key, title, isDefault, sortOrder)
    }

    private fun CloudTaskRecord.toEntity(): TaskEntity {
        return TaskEntity(
            id,
            title.trim(),
            notes.trim(),
            TaskCategories.normalizeKey(categoryKey),
            dueAtMillis,
            priority,
            repeatType,
            repeatConfigJson,
            isCompleted,
            createdAtMillis,
            updatedAtMillis,
            completedAtMillis,
        )
    }

    private fun CloudFocusSessionRecord.toEntity(): FocusSessionEntity {
        return FocusSessionEntity(
            id,
            taskId,
            taskTitle,
            plannedMinutes,
            workMinutes,
            breakMinutes,
            cyclesCompleted,
            startedAtMillis,
            completedAtMillis,
            isCompleted,
        )
    }

    private fun TaskWithTags.toCloudTaskRecord(categoryMap: Map<String, CloudCategoryRecord>): CloudTaskRecord {
        val taskEntity = task
        val category = categoryMap[TaskCategories.normalizeKey(taskEntity.categoryKey)]
        return CloudTaskRecord(
            id = taskEntity.id,
            title = taskEntity.title,
            notes = taskEntity.notes,
            categoryKey = TaskCategories.normalizeKey(taskEntity.categoryKey),
            categoryTitle = category?.title ?: taskEntity.categoryKey,
            tags = normalizeTags(tags.orEmpty().map { it.name }),
            dueAtMillis = taskEntity.dueAtMillis,
            priority = taskEntity.priority,
            repeatType = taskEntity.repeatType,
            repeatConfigJson = taskEntity.repeatConfigJson,
            isCompleted = taskEntity.isCompleted,
            createdAtMillis = taskEntity.createdAtMillis,
            updatedAtMillis = taskEntity.updatedAtMillis,
            completedAtMillis = taskEntity.completedAtMillis,
        )
    }

    private fun TaskItem.sanitized(): TaskItem {
        return copy(
            title = title.trim(),
            notes = notes.trim(),
            categoryKey = TaskCategories.normalizeKey(categoryKey),
            tags = normalizeTags(tags),
        )
    }

    private fun normalizeTags(tags: List<String>): List<String> {
        val seen = LinkedHashMap<String, String>()
        tags.forEach { rawTag ->
            val cleaned = rawTag.trim().trimStart('#').replace(Regex("\\s+"), " ")
            if (cleaned.isEmpty()) return@forEach
            val key = cleaned.lowercase(Locale.getDefault())
            seen.putIfAbsent(key, cleaned)
        }
        return seen.values.toList()
    }

    private fun defaultCategories(): List<TaskCategoryItem> {
        return listOf(
            TaskCategoryItem(TaskCategories.WORK_KEY, "工作", true, 10),
            TaskCategoryItem(TaskCategories.PERSONAL_KEY, "個人", true, 20),
            TaskCategoryItem(TaskCategories.STUDY_KEY, "學習", true, 30),
            TaskCategoryItem(TaskCategories.HEALTH_KEY, "健康", true, 40),
            TaskCategoryItem(TaskCategories.HOME_KEY, "居家", true, 50),
            TaskCategoryItem(TaskCategories.SHOPPING_KEY, "購物", true, 60),
            TaskCategoryItem(TaskCategories.OTHER_KEY, "其他", true, 99),
        )
    }

    private fun buildDemoTasks(now: Long): List<TaskItem> {
        val day = 24L * 60L * 60L * 1000L
        fun due(days: Int): Long = now + days * day

        return listOf(
            TaskItem(10001L, "Demo 01 - 每天檢查信箱", "早上先清掉重要郵件", TaskCategories.WORK_KEY, "工作", listOf("email", "daily"), due(0), TaskItem.PRIORITY_MEDIUM, TaskRepeatRule.Daily, false, now, now),
            TaskItem(10002L, "Demo 02 - 每天喝水", "提醒自己補水", TaskCategories.HEALTH_KEY, "健康", listOf("health", "habit"), due(0), TaskItem.PRIORITY_LOW, TaskRepeatRule.Daily, false, now, now),
            TaskItem(10003L, "Demo 03 - 每週六倒垃圾", "晚間出門前整理垃圾", TaskCategories.HOME_KEY, "居家", listOf("home", "cleanup"), due(2), TaskItem.PRIORITY_HIGH, TaskRepeatRule.Weekly(setOf(java.util.Calendar.SATURDAY)), false, now, now),
            TaskItem(10004L, "Demo 04 - 每週一規劃工作", "排定本週最重要的三件事", TaskCategories.WORK_KEY, "工作", listOf("planning", "work"), due(1), TaskItem.PRIORITY_HIGH, TaskRepeatRule.Weekly(setOf(java.util.Calendar.MONDAY)), false, now, now),
            TaskItem(10005L, "Demo 05 - 每週三運動", "30 分鐘快走或重訓", TaskCategories.HEALTH_KEY, "健康", listOf("sport", "health"), due(3), TaskItem.PRIORITY_MEDIUM, TaskRepeatRule.Weekly(setOf(java.util.Calendar.WEDNESDAY)), false, now, now),
            TaskItem(10006L, "Demo 06 - 每週五備份相簿", "把手機照片同步到雲端", TaskCategories.PERSONAL_KEY, "個人", listOf("backup", "tech"), due(5), TaskItem.PRIORITY_MEDIUM, TaskRepeatRule.Weekly(setOf(java.util.Calendar.FRIDAY)), false, now, now),
            TaskItem(10007L, "Demo 07 - 每週二、四回覆社群訊息", "集中處理留言", TaskCategories.WORK_KEY, "工作", listOf("social", "work"), due(2), TaskItem.PRIORITY_MEDIUM, TaskRepeatRule.Weekly(setOf(java.util.Calendar.TUESDAY, java.util.Calendar.THURSDAY)), false, now, now),
            TaskItem(10008L, "Demo 08 - 每月第一個週五繳電信帳單", "先確認帳單金額", TaskCategories.PERSONAL_KEY, "個人", listOf("bill", "finance"), due(7), TaskItem.PRIORITY_HIGH, TaskRepeatRule.MonthlyWeekday(1, java.util.Calendar.FRIDAY), false, now, now),
            TaskItem(10009L, "Demo 09 - 每月第二個週一檢查預算", "更新本月支出表", TaskCategories.PERSONAL_KEY, "個人", listOf("budget", "finance"), due(10), TaskItem.PRIORITY_MEDIUM, TaskRepeatRule.MonthlyWeekday(2, java.util.Calendar.MONDAY), false, now, now),
            TaskItem(10010L, "Demo 10 - 每月最後一個週四交報告", "整理月末進度", TaskCategories.WORK_KEY, "工作", listOf("report", "work"), due(28), TaskItem.PRIORITY_HIGH, TaskRepeatRule.MonthlyWeekday(-1, java.util.Calendar.THURSDAY), false, now, now),
            TaskItem(10011L, "Demo 11 - 每月 1 號整理發票", "檢查收據與雲端備份", TaskCategories.PERSONAL_KEY, "個人", listOf("invoice", "finance"), due(1), TaskItem.PRIORITY_LOW, TaskRepeatRule.MonthlyDate(1), false, now, now),
            TaskItem(10012L, "Demo 12 - 每月 5 號繳房租", "轉帳後保留明細", TaskCategories.PERSONAL_KEY, "個人", listOf("rent", "finance"), due(5), TaskItem.PRIORITY_HIGH, TaskRepeatRule.MonthlyDate(5), false, now, now),
            TaskItem(10013L, "Demo 13 - 每月 10 號發薪確認", "看薪資單與入帳", TaskCategories.PERSONAL_KEY, "個人", listOf("payroll", "finance"), due(10), TaskItem.PRIORITY_HIGH, TaskRepeatRule.MonthlyDate(10), false, now, now),
            TaskItem(10014L, "Demo 14 - 每月 15 號清理桌面", "把桌面資料歸檔", TaskCategories.WORK_KEY, "工作", listOf("cleanup", "desk"), due(15), TaskItem.PRIORITY_LOW, TaskRepeatRule.MonthlyDate(15), false, now, now),
            TaskItem(10015L, "Demo 15 - 每月 20 號檢查保險", "確認續保與付款", TaskCategories.PERSONAL_KEY, "個人", listOf("insurance", "finance"), due(20), TaskItem.PRIORITY_MEDIUM, TaskRepeatRule.MonthlyDate(20), false, now, now),
            TaskItem(10016L, "Demo 16 - 每月最後一個工作日關帳", "確認所有款項都已對帳", TaskCategories.WORK_KEY, "工作", listOf("close", "finance"), due(29), TaskItem.PRIORITY_HIGH, TaskRepeatRule.MonthlyLastWorkday, false, now, now),
            TaskItem(10017L, "Demo 17 - 每月最後一個工作日整理票據", "收齊報帳資料", TaskCategories.WORK_KEY, "工作", listOf("receipt", "finance"), due(30), TaskItem.PRIORITY_MEDIUM, TaskRepeatRule.MonthlyLastWorkday, false, now, now),
            TaskItem(10018L, "Demo 18 - 讀完 1 章書", "固定學習時間", TaskCategories.STUDY_KEY, "學習", listOf("reading", "study"), due(2), TaskItem.PRIORITY_LOW, TaskRepeatRule.None, false, now, now),
            TaskItem(10019L, "Demo 19 - 完成 Kotlin 練習", "每天練習 30 分鐘", TaskCategories.STUDY_KEY, "學習", listOf("kotlin", "coding"), due(1), TaskItem.PRIORITY_HIGH, TaskRepeatRule.Daily, false, now, now),
            TaskItem(10020L, "Demo 20 - 處理待辦信件", "把信箱清到零", TaskCategories.WORK_KEY, "工作", listOf("email", "work"), due(-1), TaskItem.PRIORITY_HIGH, TaskRepeatRule.None, true, now, now, now),
            TaskItem(10021L, "Demo 21 - 檢查冰箱食材", "每週採買前確認庫存", TaskCategories.HOME_KEY, "居家", listOf("home", "shopping"), due(4), TaskItem.PRIORITY_LOW, TaskRepeatRule.Weekly(setOf(java.util.Calendar.SUNDAY)), false, now, now),
            TaskItem(10022L, "Demo 22 - 每週四與團隊同步", "更新進度與阻塞", TaskCategories.WORK_KEY, "工作", listOf("meeting", "work"), due(4), TaskItem.PRIORITY_HIGH, TaskRepeatRule.Weekly(setOf(java.util.Calendar.THURSDAY)), false, now, now),
            TaskItem(10023L, "Demo 23 - 每週二買菜", "補充蔬菜水果", TaskCategories.SHOPPING_KEY, "購物", listOf("home", "food"), due(2), TaskItem.PRIORITY_MEDIUM, TaskRepeatRule.Weekly(setOf(java.util.Calendar.TUESDAY)), false, now, now),
            TaskItem(10024L, "Demo 24 - 每月第三個週三整理報表", "下載與歸檔資料", TaskCategories.WORK_KEY, "工作", listOf("report", "data"), due(17), TaskItem.PRIORITY_HIGH, TaskRepeatRule.MonthlyWeekday(3, java.util.Calendar.WEDNESDAY), false, now, now),
            TaskItem(10025L, "Demo 25 - 每月 28 號整理帳務", "對帳與發票歸檔", TaskCategories.PERSONAL_KEY, "個人", listOf("finance", "invoice"), due(28), TaskItem.PRIORITY_HIGH, TaskRepeatRule.MonthlyDate(28), false, now, now),
            TaskItem(10026L, "Demo 26 - 每月最後一個工作日備份電腦", "外接硬碟與雲端同步", TaskCategories.PERSONAL_KEY, "個人", listOf("backup", "tech"), due(29), TaskItem.PRIORITY_HIGH, TaskRepeatRule.MonthlyLastWorkday, false, now, now),
            TaskItem(10027L, "Demo 27 - 每天晚間整理明日行程", "把明天要做的事情列出來", TaskCategories.PERSONAL_KEY, "個人", listOf("plan", "daily"), due(0), TaskItem.PRIORITY_MEDIUM, TaskRepeatRule.Daily, false, now, now),
            TaskItem(10028L, "Demo 28 - 每月第一個週一回顧目標", "檢查月初方向", TaskCategories.STUDY_KEY, "學習", listOf("goal", "review"), due(7), TaskItem.PRIORITY_MEDIUM, TaskRepeatRule.MonthlyWeekday(1, java.util.Calendar.MONDAY), false, now, now),
            TaskItem(10029L, "Demo 29 - 每月 25 號預約下月行程", "先把重要日子排起來", TaskCategories.PERSONAL_KEY, "個人", listOf("calendar", "plan"), due(25), TaskItem.PRIORITY_LOW, TaskRepeatRule.MonthlyDate(25), false, now, now),
            TaskItem(10030L, "Demo 30 - 每週六整理桌面", "重整工作區與文件", TaskCategories.HOME_KEY, "居家", listOf("home", "cleanup"), due(6), TaskItem.PRIORITY_LOW, TaskRepeatRule.Weekly(setOf(java.util.Calendar.SATURDAY)), false, now, now),
        )
    }

    private fun buildPreviewTasks(now: Long): List<TaskItem> {
        val day = DAY_MILLIS
        val hour = 60L * 60L * 1000L
        fun completedAt(daysAgo: Int, offsetHours: Int = 0): Long = now - daysAgo * day - offsetHours * hour
        fun futureDue(daysAhead: Int, offsetHours: Int = 0): Long = now + daysAhead * day + offsetHours * hour

        val templates = listOf(
            Triple("整理信箱與通知", TaskCategories.WORK_KEY, listOf("email", "daily")),
            Triple("番茄鐘專注 25 分鐘", TaskCategories.STUDY_KEY, listOf("focus", "study")),
            Triple("每週例行回報", TaskCategories.WORK_KEY, listOf("report", "weekly")),
            Triple("喝水與伸展", TaskCategories.HEALTH_KEY, listOf("health", "habit")),
            Triple("補充冰箱食材", TaskCategories.HOME_KEY, listOf("home", "shopping")),
            Triple("閱讀一章書", TaskCategories.STUDY_KEY, listOf("reading", "study")),
            Triple("整理桌面與文件", TaskCategories.WORK_KEY, listOf("cleanup", "desk")),
            Triple("回覆重要訊息", TaskCategories.WORK_KEY, listOf("reply", "urgent")),
            Triple("支付月費帳單", TaskCategories.PERSONAL_KEY, listOf("bill", "finance")),
            Triple("慢跑 30 分鐘", TaskCategories.HEALTH_KEY, listOf("sport", "health")),
            Triple("寫下明日計畫", TaskCategories.PERSONAL_KEY, listOf("plan", "daily")),
            Triple("備份相簿到雲端", TaskCategories.PERSONAL_KEY, listOf("backup", "tech")),
        )

        val completedRules = listOf(
            TaskRepeatRule.Daily,
            TaskRepeatRule.Weekly(setOf(java.util.Calendar.MONDAY)),
            TaskRepeatRule.Weekly(setOf(java.util.Calendar.WEDNESDAY)),
            TaskRepeatRule.MonthlyDate(5),
            TaskRepeatRule.MonthlyWeekday(1, java.util.Calendar.FRIDAY),
            TaskRepeatRule.MonthlyLastWorkday,
        )

        val activeRules = listOf(
            TaskRepeatRule.None,
            TaskRepeatRule.Daily,
            TaskRepeatRule.Weekly(setOf(java.util.Calendar.TUESDAY, java.util.Calendar.THURSDAY)),
            TaskRepeatRule.MonthlyWeekday(2, java.util.Calendar.MONDAY),
            TaskRepeatRule.MonthlyDate(18),
            TaskRepeatRule.MonthlyWeekday(3, java.util.Calendar.WEDNESDAY),
        )

        val completedTasks = (0 until 18).map { index ->
            val template = templates[index % templates.size]
            val daysAgo = when (index) {
                0, 1, 2 -> index
                3, 4, 5 -> 4 + (index - 3)
                6, 7, 8 -> 8 + (index - 6)
                else -> 11 + (index - 9)
            }
            TaskItem(
                id = 40001L + index,
                title = "示意完成 ${index + 1} - ${template.first}",
                notes = "完成版面用的示意任務，讓數據頁有更完整的完成率。",
                categoryKey = template.second,
                categoryTitle = defaultCategories().firstOrNull { it.key == template.second }?.title ?: "其他",
                tags = template.third,
                dueAtMillis = completedAt(daysAgo, index % 3),
                priority = when (index % 3) {
                    0 -> TaskItem.PRIORITY_HIGH
                    1 -> TaskItem.PRIORITY_MEDIUM
                    else -> TaskItem.PRIORITY_LOW
                },
                repeatRule = completedRules[index % completedRules.size],
                isCompleted = true,
                createdAtMillis = completedAt(daysAgo + 5, 0),
                updatedAtMillis = completedAt(daysAgo, 0),
                completedAtMillis = completedAt(daysAgo, 0),
            )
        }

        val activeTasks = (0 until 12).map { index ->
            val template = templates[(index + 5) % templates.size]
            val dueOffset = index + 1
            TaskItem(
                id = 41001L + index,
                title = "示意進行中 ${index + 1} - ${template.first}",
                notes = "用來讓日程與任務清單有更完整的分布。",
                categoryKey = template.second,
                categoryTitle = defaultCategories().firstOrNull { it.key == template.second }?.title ?: "其他",
                tags = template.third + listOf("demo"),
                dueAtMillis = futureDue(dueOffset, index % 4),
                priority = when (index % 3) {
                    0 -> TaskItem.PRIORITY_HIGH
                    1 -> TaskItem.PRIORITY_MEDIUM
                    else -> TaskItem.PRIORITY_LOW
                },
                repeatRule = activeRules[index % activeRules.size],
                isCompleted = false,
                createdAtMillis = futureDue(-2, 0),
                updatedAtMillis = now,
            )
        }

        return completedTasks + activeTasks
    }

    private fun buildPreviewFocusSessions(now: Long): List<FocusSessionItem> {
        val day = DAY_MILLIS
        fun sessionStart(daysAgo: Int, hoursAgo: Int = 0): Long = now - daysAgo * day - hoursAgo * 60L * 60L * 1000L

        val specs = listOf(
            FocusSessionItem(50001L, 40001L, "示意完成 1 - 整理信箱與通知", 25, 25, 0, 1, sessionStart(0, 3), sessionStart(0, 3) + 25 * 60_000L, true),
            FocusSessionItem(50002L, 40002L, "示意完成 2 - 番茄鐘專注 25 分鐘", 50, 25, 5, 2, sessionStart(0, 5), sessionStart(0, 5) + 55 * 60_000L, true),
            FocusSessionItem(50003L, 40003L, "示意完成 3 - 每週例行回報", 75, 25, 5, 3, sessionStart(1, 2), sessionStart(1, 2) + 85 * 60_000L, true),
            FocusSessionItem(50004L, 40004L, "示意完成 4 - 喝水與伸展", 25, 25, 0, 1, sessionStart(1, 5), sessionStart(1, 5) + 25 * 60_000L, true),
            FocusSessionItem(50005L, 40005L, "示意完成 5 - 補充冰箱食材", 100, 30, 5, 3, sessionStart(2, 1), sessionStart(2, 1) + 100 * 60_000L, true),
            FocusSessionItem(50006L, 40006L, "示意完成 6 - 閱讀一章書", 25, 25, 0, 1, sessionStart(2, 4), sessionStart(2, 4) + 25 * 60_000L, true),
            FocusSessionItem(50007L, 40007L, "示意完成 7 - 整理桌面與文件", 50, 25, 5, 2, sessionStart(3, 3), sessionStart(3, 3) + 50 * 60_000L, true),
            FocusSessionItem(50008L, 40008L, "示意完成 8 - 回覆重要訊息", 65, 30, 5, 2, sessionStart(4, 2), sessionStart(4, 2) + 65 * 60_000L, true),
            FocusSessionItem(50009L, 40009L, "示意完成 9 - 支付月費帳單", 90, 25, 5, 3, sessionStart(5, 1), sessionStart(5, 1) + 90 * 60_000L, true),
            FocusSessionItem(50010L, 40010L, "示意完成 10 - 慢跑 30 分鐘", 30, 30, 0, 1, sessionStart(6, 2), sessionStart(6, 2) + 30 * 60_000L, true),
            FocusSessionItem(50011L, 40011L, "示意中斷 - 寫下明日計畫", 50, 25, 5, 1, sessionStart(0, 1), sessionStart(0, 1) + 18 * 60_000L, false),
            FocusSessionItem(50012L, 40012L, "示意完成 11 - 備份相簿到雲端", 120, 25, 5, 4, sessionStart(7, 3), sessionStart(7, 3) + 118 * 60_000L, true),
            FocusSessionItem(50013L, 40013L, "示意完成 12 - 每月 1 號整理發票", 25, 25, 0, 1, sessionStart(0, 8), sessionStart(0, 8) + 25 * 60_000L, true),
            FocusSessionItem(50014L, 40014L, "示意完成 13 - 每月 5 號繳房租", 55, 25, 5, 2, sessionStart(1, 1), sessionStart(1, 1) + 55 * 60_000L, true),
            FocusSessionItem(50015L, 40015L, "示意完成 14 - 每月 10 號發薪確認", 30, 30, 0, 1, sessionStart(1, 6), sessionStart(1, 6) + 30 * 60_000L, true),
            FocusSessionItem(50016L, 40016L, "示意完成 15 - 每月最後一個工作日關帳", 90, 30, 5, 3, sessionStart(2, 2), sessionStart(2, 2) + 90 * 60_000L, true),
            FocusSessionItem(50017L, 40017L, "示意完成 16 - 每天晚間整理明日行程", 25, 25, 0, 1, sessionStart(2, 7), sessionStart(2, 7) + 25 * 60_000L, true),
            FocusSessionItem(50018L, 40018L, "示意完成 17 - 讀完 1 章書", 65, 30, 5, 2, sessionStart(3, 4), sessionStart(3, 4) + 65 * 60_000L, true),
            FocusSessionItem(50019L, 40019L, "示意完成 18 - 完成 Kotlin 練習", 50, 25, 5, 2, sessionStart(4, 2), sessionStart(4, 2) + 50 * 60_000L, true),
            FocusSessionItem(50020L, null, "示意放棄 - 先選一件代辦", 25, 25, 0, 1, sessionStart(5, 1), sessionStart(5, 1) + 12 * 60_000L, false),
        )
        return specs
    }

    private fun focusSessionMinutes(session: FocusSessionItem): Int {
        val elapsed = ((session.completedAtMillis - session.startedAtMillis).coerceAtLeast(0L) / 60_000L).toInt()
        return elapsed.takeIf { it > 0 } ?: session.plannedMinutes
    }

    private fun buildCategoryKey(title: String): String {
        val slug = title.lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), "-")
            .trim('-')
            .ifBlank { "category" }

        var candidate = slug
        var suffix = 2
        val existingKeys = dao.loadCategoriesOnce().map { it.key }.toHashSet()
        while (candidate in existingKeys || TaskCategories.isDefaultKey(candidate)) {
            candidate = "$slug-$suffix"
            suffix += 1
        }
        return candidate
    }

    companion object {
        private const val LEGACY_PREFS_NAME = "whattodo.tasks"
        private const val LEGACY_TASKS_KEY = "tasks_json"
        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}
