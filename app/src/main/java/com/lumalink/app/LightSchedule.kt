package com.lumalink.app

internal object LightSchedule {
    /** First systematic pass, then alternate source repeats and fresh repair equations. */
    fun sequence(blocks: Int, emission: Long): Int {
        require(blocks > 0 && emission >= 0)
        if (emission < blocks) return emission.toInt()
        val later = emission - blocks
        return if (later % 2 == 0L) ((later / 2) % blocks).toInt()
        else (blocks + later / 2).also { require(it <= Int.MAX_VALUE) }.toInt()
    }
}
