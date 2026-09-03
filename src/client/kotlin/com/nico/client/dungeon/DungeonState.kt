package com.nico.client.dungeon

import com.nico.client.utils.LocationUtils
import net.minecraft.client.Minecraft

object DungeonState {

    private val floorRegex =
        Regex("""The Catacombs \(([FM])(\d+)\)""")

    var floorNumber: Int? = null
        private set

    var masterMode: Boolean = false
        private set

    val inDungeons: Boolean
        get() = LocationUtils.isInDungeon()

    val inBoss: Boolean
        get() {
            if (!inDungeons) return false

            val player = Minecraft.getInstance().player ?: return false
            val floor = floorNumber ?: return false

            return when (floor) {
                1 ->
                    player.x > -71 && player.z > -39

                in 2..4 ->
                    player.x > -39 && player.z > -39

                in 5..6 ->
                    player.x > -39 && player.z > -7

                7 ->
                    player.x > -7 && player.z > -7

                else -> false
            }
        }

    val inClear: Boolean
        get() = inDungeons && !inBoss

    @JvmStatic
    fun tick() {
        if (!inDungeons) {
            floorNumber = null
            masterMode = false
            return
        }

        val connection = Minecraft.getInstance().connection ?: return

        var detectedFloor: Int? = null
        var detectedMasterMode = false

        for (playerInfo in connection.onlinePlayers) {
            val displayName =
                playerInfo.tabListDisplayName ?: continue

            val line =
                displayName.string.stripControlCodes()

            val match = floorRegex.find(line) ?: continue

            detectedMasterMode =
                match.groupValues[1]
                    .equals("M", ignoreCase = true)

            detectedFloor =
                match.groupValues[2].toIntOrNull()

            break
        }

        floorNumber = detectedFloor
        masterMode = detectedMasterMode
    }

    @JvmStatic
    fun reset() {
        floorNumber = null
        masterMode = false
    }

    private fun String.stripControlCodes(): String =
        replace(
            Regex(
                "§[0-9A-FK-OR]",
                RegexOption.IGNORE_CASE
            ),
            ""
        )
}