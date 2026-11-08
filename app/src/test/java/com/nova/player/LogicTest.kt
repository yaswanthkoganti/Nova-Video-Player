package com.nova.player

import com.nova.player.data.*
import com.nova.player.engine.Enhance
import com.nova.player.engine.EnhancePresets
import com.nova.player.engine.fmtTime
import com.nova.player.engine.subtitleBottomFraction
import org.junit.Assert.*
import org.junit.Test

class LogicTest {
    @Test fun srtRoundTrip() {
        val cues = listOf(Cue(1000, 2500, "Hello"), Cue(3_600_000, 3_601_000, "A\nB"))
        assertEquals(cues, parseSrt(toSrt(cues)))
    }

    @Test fun srtIgnoresGarbage() {
        assertTrue(parseSrt("not a subtitle").isEmpty())
    }

    @Test fun naturalOrder() {
        val sorted = listOf("Ep 10", "Ep 2", "ep 1").sortedWith(Natural)
        assertEquals(listOf("ep 1", "Ep 2", "Ep 10"), sorted)
    }

    @Test fun timeFormat() {
        assertEquals("01:05", fmtTime(65_000))
        assertEquals("1:01:05", fmtTime(3_665_000))
        assertEquals("00:00", fmtTime(-5))
    }

    @Test fun enhanceIdentity() {
        assertTrue(Enhance().isIdentity)
        assertTrue(EnhancePresets.drop(1).none { it.second.isIdentity })
    }

    @Test fun subtitlePositionFixedIgnoresVideo() {
        assertEquals(0.08f, subtitleBottomFraction(false, 0.08f, true, 2390, 1000, 1920, 1080), 1e-6f)
    }

    @Test fun subtitlePositionMovesIntoLetterboxBar() {
        val v = subtitleBottomFraction(true, 0.08f, true, 2390, 1000, 1920, 1080)   // 2.39:1 film on 16:9 screen
        assertTrue("was $v", v in 0.02f..0.04f)
    }

    @Test fun subtitlePositionRelativeToVideoEdgeWhenBarIsThin() {
        val v = subtitleBottomFraction(true, 0.08f, true, 2000, 1000, 1920, 1080)   // 2:1 -> thin bar
        assertEquals(0.1356f, v, 0.001f)
    }

    @Test fun subtitlePositionNoBarCases() {
        assertEquals(0.08f, subtitleBottomFraction(true, 0.08f, true, 1920, 1080, 1920, 1080), 1e-6f) // exact fit
        assertEquals(0.08f, subtitleBottomFraction(true, 0.08f, true, 1440, 1080, 1920, 1080), 1e-6f) // 4:3 pillarbox
        assertEquals(0.08f, subtitleBottomFraction(true, 0.08f, false, 2390, 1000, 1920, 1080), 1e-6f) // crop/stretch
        assertEquals(0.08f, subtitleBottomFraction(true, 0.08f, true, 0, 0, 1920, 1080), 1e-6f)        // unknown size
    }
}
