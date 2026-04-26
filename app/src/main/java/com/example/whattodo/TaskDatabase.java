package com.example.whattodo;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
        entities = {
                TaskEntity.class,
                TaskTagEntity.class,
                TaskTagDefinitionEntity.class,
                TaskCategoryEntity.class,
                FocusSessionEntity.class
        },
        version = 5,
        exportSchema = false
)
public abstract class TaskDatabase extends RoomDatabase {
    public abstract TaskDao taskDao();

    public static TaskDatabase create(Context context) {
        return Room.databaseBuilder(context.getApplicationContext(), TaskDatabase.class, "whattodo.db")
                .addMigrations(MIGRATION_1_2)
                .addMigrations(MIGRATION_2_3)
                .addMigrations(MIGRATION_3_4)
                .addMigrations(MIGRATION_4_5)
                .build();
    }

    private static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE tasks ADD COLUMN repeatType INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE tasks ADD COLUMN repeatConfigJson TEXT NOT NULL DEFAULT '{}'");
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS task_categories (" +
                            "`key` TEXT NOT NULL PRIMARY KEY, " +
                            "title TEXT NOT NULL, " +
                            "isDefault INTEGER NOT NULL, " +
                            "sortOrder INTEGER NOT NULL)"
            );
            database.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_categoryKey ON tasks(categoryKey)");
            database.execSQL("INSERT OR REPLACE INTO task_categories (`key`, title, isDefault, sortOrder) VALUES " +
                    "('work', '工作', 1, 10), " +
                    "('personal', '個人', 1, 20), " +
                    "('study', '學習', 1, 30), " +
                    "('health', '健康', 1, 40), " +
                    "('home', '居家', 1, 50), " +
                    "('shopping', '購物', 1, 60), " +
                    "('other', '其他', 1, 99)");
        }
    };

    private static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS task_tag_definitions (" +
                            "name TEXT NOT NULL PRIMARY KEY)"
            );
            database.execSQL("INSERT OR IGNORE INTO task_tag_definitions (name) SELECT DISTINCT name FROM task_tags");
        }
    };

    private static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE tasks ADD COLUMN completedAtMillis INTEGER");
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS focus_sessions (" +
                            "id INTEGER NOT NULL PRIMARY KEY, " +
                            "taskId INTEGER, " +
                            "taskTitle TEXT, " +
                            "plannedMinutes INTEGER NOT NULL, " +
                            "workMinutes INTEGER NOT NULL, " +
                            "breakMinutes INTEGER NOT NULL, " +
                            "cyclesCompleted INTEGER NOT NULL, " +
                            "completedAtMillis INTEGER NOT NULL)"
            );
        }
    };

    private static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE focus_sessions ADD COLUMN startedAtMillis INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE focus_sessions ADD COLUMN isCompleted INTEGER NOT NULL DEFAULT 1");
            database.execSQL(
                    "UPDATE focus_sessions " +
                            "SET startedAtMillis = CASE " +
                            "WHEN startedAtMillis = 0 AND completedAtMillis > 0 THEN completedAtMillis - (plannedMinutes * 60000) " +
                            "ELSE startedAtMillis END"
            );
        }
    };
}
