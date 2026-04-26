package com.example.whattodo;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

@Entity(tableName = "task_categories")
public class TaskCategoryEntity {
    @PrimaryKey
    @NonNull
    public String key;

    @NonNull
    public String title;

    public boolean isDefault;

    public int sortOrder;

    public TaskCategoryEntity() {
    }

    @Ignore
    public TaskCategoryEntity(@NonNull String key, @NonNull String title, boolean isDefault, int sortOrder) {
        this.key = key;
        this.title = title;
        this.isDefault = isDefault;
        this.sortOrder = sortOrder;
    }
}
