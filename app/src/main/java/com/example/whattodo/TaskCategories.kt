package com.example.whattodo

object TaskCategories {
    const val ALL_KEY = "all"
    const val WORK_KEY = "work"
    const val PERSONAL_KEY = "personal"
    const val STUDY_KEY = "study"
    const val HEALTH_KEY = "health"
    const val HOME_KEY = "home"
    const val SHOPPING_KEY = "shopping"
    const val OTHER_KEY = "other"

    val defaultKeys: List<String> = listOf(
        WORK_KEY,
        PERSONAL_KEY,
        STUDY_KEY,
        HEALTH_KEY,
        HOME_KEY,
        SHOPPING_KEY,
        OTHER_KEY,
    )

    fun normalizeKey(key: String?): String {
        val normalized = key?.trim().orEmpty()
        return when (normalized) {
            "", ALL_KEY -> OTHER_KEY
            else -> normalized
        }
    }

    fun isDefaultKey(key: String): Boolean = defaultKeys.contains(key)
}
