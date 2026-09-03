package com.nico.client.dungeon

import com.nico.client.utils.LocationUtils
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

object DungeonState {

    private val floorRegex = Regex(
        """(?:The\s+)?Catacombs\s*\(\s*([FM])\s*(\d+)\s*\)""",
        RegexOption.IGNORE_CASE
    )

    private var scoreboardFloorNumber: Int? = null;
    private var scoreboardMasterMode: Boolean = false;

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
            reset()
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

            val floor = parseFloor(line) ?: continue
            detectedMasterMode = floor.masterMode
            detectedFloor = floor.number

            break
        }

        floorNumber = detectedFloor ?: scoreboardFloorNumber
        masterMode =
            if (detectedFloor != null) detectedMasterMode
            else scoreboardMasterMode
    }

    /**
    * Receives the text components used by sidebar scoreboard rows. Hypixel puts
    * "The Catacombs (F7)" there on clients where the same text is not present
    * in the tab list, so this acts as the fallback for the normal tab scan.
    */
    @JvmStatic
    fun onScoreboardText(prefix: Component?, suffix: Component?) {
        val line = buildString {
            prefix?.let { append(it.string) }
            suffix?.let { append(it.string) }
        }.stripControlCodes()

        val floor = parseFloor(line) ?: return
        scoreboardFloorNumber = floor.number
        scoreboardMasterMode = floor.masterMode

        // Publish it immediately; tick() will keep preferring the tab-list value
        // whenever that number is available.
        floorNumber = floor.number
        masterMode = floor.masterMode
    }

    @JvmStatic
    fun reset() {
        floorNumber = null
        masterMode = false
        scoreboardFloorNumber = null
        scoreboardMasterMode = false
    }

    private fun parseFloor(line: String): DetectedFloor? {
        val match = floorRegex.find(line) ?: return null
        val number = match.groupValues[2].toIntOrNull() ?: return null

        return DetectedFloor(
            number = number,
            masterMode = match.groupValues[1].equals("M", ignoreCase = true)
        )
    }

    private fun String.stripControlCodes(): String =
        replace(
            Regex(
                "§[0-9A-FK-OR]",
                RegexOption.IGNORE_CASE
            ),
            ""
        )

    private data class DetectedFloor(
        val number: Int,
        val masterMode: Boolean
    )
}