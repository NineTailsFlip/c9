package life.furrer.c9.core

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyRouterTest {

    @Test
    fun `dpad up and down`() {
        assertEquals(Action.Up, KeyRouter.map(KeyEvent.KEYCODE_DPAD_UP, ' '))
        assertEquals(Action.Down, KeyRouter.map(KeyEvent.KEYCODE_DPAD_DOWN, ' '))
    }

    @Test
    fun `dpad center and enter both mean select`() {
        assertEquals(Action.Select, KeyRouter.map(KeyEvent.KEYCODE_DPAD_CENTER, ' '))
        assertEquals(Action.Select, KeyRouter.map(KeyEvent.KEYCODE_ENTER, ' '))
    }

    @Test
    fun `unmapped keycodes yield no action`() {
        assertNull(KeyRouter.map(KeyEvent.KEYCODE_BACK, ' '))
        assertNull(KeyRouter.map(KeyEvent.KEYCODE_STAR, ' '))
        assertNull(KeyRouter.map(KeyEvent.KEYCODE_POUND, ' '))
        assertNull(KeyRouter.map(KeyEvent.KEYCODE_1, '1'))
        assertNull(KeyRouter.map(KeyEvent.KEYCODE_VOLUME_UP, ' '))
    }
}
