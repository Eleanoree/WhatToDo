package com.example.whattodo;

import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

@Entity(tableName = "focus_sessions")
public class FocusSessionEntity {
    @PrimaryKey
    public long id;

    @Nullable
    public Long taskId;

    @Nullable
    public String taskTitle;
    public int plannedMinutes;
    public int workMinutes;
    public int breakMinutes;
    public int cyclesCompleted;
    public long startedAtMillis;
    public long completedAtMillis;
    public boolean isCompleted;

    @Ignore
    public FocusSessionEntity() {
    }

    public FocusSessionEntity(long id, @Nullable Long taskId, @Nullable String taskTitle, int plannedMinutes,
                              int workMinutes, int breakMinutes, int cyclesCompleted,
                              long startedAtMillis, long completedAtMillis, boolean isCompleted) {
        this.id = id;
        this.taskId = taskId;
        this.taskTitle = taskTitle;
        this.plannedMinutes = plannedMinutes;
        this.workMinutes = workMinutes;
        this.breakMinutes = breakMinutes;
        this.cyclesCompleted = cyclesCompleted;
        this.startedAtMillis = startedAtMillis;
        this.completedAtMillis = completedAtMillis;
        this.isCompleted = isCompleted;
    }
}
