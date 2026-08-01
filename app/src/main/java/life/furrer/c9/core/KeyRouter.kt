package life.furrer.c9.core

import android.view.KeyEvent

sealed interface Action {
    data object Up : Action
    data object Down : Action
    data object Select : Action
}

object KeyRouter {
    /** [number] is the key's mapped character (KeyEvent.getNumber()), passed in rather than a
     *  KeyEvent so this stays a pure function testable without any Android dependency. */
    fun map(keyCode: Int, number: Char): Action? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> Action.Up
        KeyEvent.KEYCODE_DPAD_DOWN -> Action.Down
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> Action.Select
        else -> null
    }
}
