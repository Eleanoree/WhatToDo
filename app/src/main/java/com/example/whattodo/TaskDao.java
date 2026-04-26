package com.example.whattodo;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;

import kotlinx.coroutines.flow.Flow;

@Dao
public interface TaskDao {
    @Transaction
    @Query("SELECT * FROM tasks ORDER BY isCompleted ASC, dueAtMillis IS NULL ASC, dueAtMillis ASC, updatedAtMillis DESC")
    Flow<List<TaskWithTags>> observeTasks();

    @Transaction
    @Query("SELECT * FROM tasks ORDER BY isCompleted ASC, dueAtMillis IS NULL ASC, dueAtMillis ASC, updatedAtMillis DESC")
    List<TaskWithTags> loadTasksOnce();

    @Query("SELECT COUNT(*) FROM tasks")
    int countTasks();

    @Query("SELECT COUNT(*) FROM tasks WHERE completedAtMillis IS NOT NULL")
    int countCompletedTasks();

    @Query("SELECT * FROM task_categories ORDER BY sortOrder ASC, title COLLATE NOCASE ASC")
    Flow<List<TaskCategoryEntity>> observeCategories();

    @Query("SELECT * FROM task_categories ORDER BY sortOrder ASC, title COLLATE NOCASE ASC")
    List<TaskCategoryEntity> loadCategoriesOnce();

    @Query("SELECT COUNT(*) FROM task_categories")
    int countCategories();

    @Query("SELECT DISTINCT name FROM task_tags ORDER BY name COLLATE NOCASE")
    Flow<List<String>> observeDistinctTags();

    @Query("SELECT DISTINCT name FROM task_tags ORDER BY name COLLATE NOCASE")
    List<String> loadDistinctTagsOnce();

    @Query("SELECT * FROM task_tag_definitions ORDER BY name COLLATE NOCASE")
    Flow<List<TaskTagDefinitionEntity>> observeTagDefinitions();

    @Query("SELECT * FROM task_tag_definitions ORDER BY name COLLATE NOCASE")
    List<TaskTagDefinitionEntity> loadTagDefinitionsOnce();

    @Query("SELECT * FROM focus_sessions ORDER BY completedAtMillis DESC")
    Flow<List<FocusSessionEntity>> observeFocusSessions();

    @Query("SELECT * FROM focus_sessions ORDER BY completedAtMillis DESC")
    List<FocusSessionEntity> loadFocusSessionsOnce();

    @Query("SELECT COUNT(*) FROM focus_sessions")
    int countFocusSessions();

    @Query("SELECT COUNT(*) FROM focus_sessions WHERE isCompleted = 1")
    int countCompletedFocusSessions();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertFocusSession(FocusSessionEntity session);

    @Query("DELETE FROM focus_sessions")
    void deleteAllFocusSessions();

    @Query("SELECT COUNT(*) FROM task_tag_definitions")
    int countTagDefinitions();

    @Query("SELECT COUNT(*) FROM task_tags WHERE name = :tagName")
    int countTagUsage(String tagName);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertTask(TaskEntity task);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertTasks(List<TaskEntity> tasks);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertTags(List<TaskTagEntity> tags);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertTagDefinitions(List<TaskTagDefinitionEntity> tags);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertCategory(TaskCategoryEntity category);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertCategories(List<TaskCategoryEntity> categories);

    @Query("DELETE FROM task_tags WHERE taskId = :taskId")
    void deleteTagsForTask(long taskId);

    @Query("DELETE FROM task_tags")
    void deleteTagsForAllTasks();

    @Query("DELETE FROM tasks WHERE id = :taskId")
    void deleteTaskById(long taskId);

    @Query("DELETE FROM tasks")
    void deleteAllTasks();

    @Query("DELETE FROM task_categories WHERE `key` = :categoryKey")
    void deleteCategoryByKey(String categoryKey);

    @Query("DELETE FROM task_categories")
    void deleteAllCategories();

    @Query("UPDATE tasks SET categoryKey = :fallbackKey WHERE categoryKey = :categoryKey")
    void moveTasksToCategory(String categoryKey, String fallbackKey);

    @Query("DELETE FROM task_tags WHERE name = :tagName")
    void deleteTagEverywhere(String tagName);

    @Query("DELETE FROM task_tags WHERE name = :oldName AND taskId IN (SELECT taskId FROM task_tags WHERE name = :newName)")
    void deleteConflictingTagAssignments(String oldName, String newName);

    @Query("UPDATE task_tags SET name = :newName WHERE name = :oldName")
    void renameTagAssignments(String oldName, String newName);

    @Query("UPDATE task_tag_definitions SET name = :newName WHERE name = :oldName")
    void renameTagDefinition(String oldName, String newName);

    @Query("DELETE FROM task_tag_definitions WHERE name = :tagName")
    void deleteTagDefinition(String tagName);

    @Query("DELETE FROM task_tag_definitions")
    void deleteAllTagDefinitions();
}
