package com.sleeper.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "upgrades")
data class UpgradeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val cost: Int,
    val multiplier: Double,
    val isPurchased: Boolean = false,
    val type: UpgradeType
)

enum class UpgradeType {
    SPEED,      // Turbo x4, Super x10
    STORAGE,    // Storage увеличение
    AUTO        // Auto-check
}
