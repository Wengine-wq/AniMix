package app.animix

import app.animix.model.SkipRange
import app.animix.ui.player.skipTarget
import kotlin.test.*

class PlayerSkipTest {
    @Test fun skipOnlyAppliesInsideTheDeclaredRange() {
        val opening = SkipRange(10, 90)
        assertNull(skipTarget(9999, opening))
        assertEquals(100_000, skipTarget(10_000, opening))
        assertEquals(100_000, skipTarget(99_999, opening))
        assertNull(skipTarget(100_000, opening))
        assertNull(skipTarget(20_000, null))
        assertNull(skipTarget(20_000, SkipRange(10, 0)))
        assertNull(skipTarget(0, SkipRange(-1, 90)))
    }
}
