package com.example.whattodo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "tasks",
        indices = {@Index("categoryKey")}
)
public class TaskEntity {
    @PrimaryKey
    public long id;

    @NonNull
    public String title;

    @NonNull
    public String notes;

    @NonNull
    public String categoryKey;

    @Nullable
    public Long dueAtMillis;

    public int priority;

    public int repeatType;

    @NonNull
    public String repeatConfigJson;

    public boolean isCompleted;

    public long createdAtMillis;

    public long updatedAtMillis;

    @Nullable
    public Long completedAtMillis;

    public TaskEntity() {
    }

    @Ignore
    public TaskEntity(long id, @NonNull String title, @NonNull String notes, @NonNull String categoryKey,
                      @Nullable Long dueAtMillis, int priority, int repeatType, @NonNull String repeatConfigJson,
                      boolean isCompleted, long createdAtMillis, long updatedAtMillis,
                      @Nullable Long completedAtMillis) {
        this.id = id;
        this.title = title;
        this.notes = notes;
        this.categoryKey = categoryKey;
        this.dueAtMillis = dueAtMillis;
        this.priority = priority;
        this.repeatType = repeatType;
        this.repeatConfigJson = repeatConfigJson;
        this.isCompleted = isCompleted;
        this.createdAtMillis = createdAtMillis;
        this.updatedAtMillis = updatedAtMillis;
        this.completedAtMillis = completedAtMillis;
    }
}
