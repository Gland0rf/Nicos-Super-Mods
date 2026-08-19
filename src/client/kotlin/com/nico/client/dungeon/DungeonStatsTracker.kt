package com.nico.client.dungeon

import net.minecraft.network.chat.Component
import kotlin.math.floor

object DungeonStatsTracker {
    private val secretCountRegex =
        Regex("""^\s*Secrets Found: (\d+)$""")

    private val secretPercentRegex =
        Regex("""^\s*Secrets Found: ([\d.]+)%$""")

    @get:JvmStatic
    var secretCount: Int = 0
        private set

    @get:JvmStatic
    var secretPercentage: Float = 0f
        private set

    @get:JvmStatic
    val totalSecrets: Int
        get() {
            if (secretCount == 0 || secretPercentage == 0f) {
                return 0
            }

            return floor(
                100.0 / secretPercentage * secretCount + 0.5
            ).toInt()
        }

    @JvmStatic
    fun onTabDisplayName(component: Component?) {
        val text = component?.string ?: return

        secretCountRegex.find(text)?.let {
            secretCount = it.groupValues[1].toIntOrNull() ?: secretCount
        }

        secretPercentRegex.find(text)?.let {
            secretPercentage = it.groupValues[1].toFloatOrNull() ?: secretPercentage
        }
    }

    @JvmStatic
    fun reset() {
        secretCount = 0
        secretPercentage = 0f
    }
}