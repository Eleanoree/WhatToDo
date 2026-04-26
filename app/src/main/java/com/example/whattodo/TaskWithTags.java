package com.example.whattodo;

import androidx.room.Embedded;
import androidx.room.Relation;

import java.util.List;

public class TaskWithTags {
    @Embedded
    public TaskEntity task;

    @Relation(parentColumn = "id", entityColumn = "taskId")
    public List<TaskTagEntity> tags;

    public TaskWithTags() {
    }
}
