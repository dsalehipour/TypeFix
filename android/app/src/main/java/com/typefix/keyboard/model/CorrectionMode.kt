package com.typefix.keyboard.model

/** Manual = tap the Fix key. Auto = fix automatically when typing pauses. */
enum class CorrectionMode(val id: String, val displayName: String) {
    AUTO("auto", "Auto (fix when I pause typing)"),
    MANUAL("manual", "Manual (tap the Fix key)");

    companion object {
        fun fromId(id: String?): CorrectionMode = entries.firstOrNull { it.id == id } ?: MANUAL
    }
}
