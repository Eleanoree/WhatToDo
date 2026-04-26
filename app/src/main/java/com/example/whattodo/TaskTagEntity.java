package com.example.whattodo;

import androidx.annotation.NonNull;
import androidx.room.Ignore;
import androidx.room.Entity;
import androidx.room.Index;

@Entity(
        tableName = "task_tags",
        primaryKeys = {"taskId", "name"},
        indices = {@Index("taskId")}
)
public class TaskTagEntity {
    public long taskId;

    @NonNull
    public String name;

    public TaskTagEntity() {
    }

    @Ignore
    public TaskTagEntity(long taskId, @NonNull String name) {
        this.taskId = taskId;
        this.name = name;
    }
}
