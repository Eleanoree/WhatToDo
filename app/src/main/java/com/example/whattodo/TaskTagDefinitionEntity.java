package com.example.whattodo;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

@Entity(tableName = "task_tag_definitions")
public class TaskTagDefinitionEntity {
    @PrimaryKey
    @NonNull
    public String name;

    public TaskTagDefinitionEntity() {
    }

    @Ignore
    public TaskTagDefinitionEntity(@NonNull String name) {
        this.name = name;
    }
}
