/*
 * xSSH — Modifier bar.
 *
 * The user's system keyboard covers letters/digits/punctuation. This slim row
 * sits between the terminal and the IME to supply the keys phone keyboards
 * don't have: Esc, Tab, arrows, common shell symbols, plus Ctrl/Alt "arm once"
 * toggles.
 *
 * The `Ctrl` and `Alt` chips are stateful ("armed"): tapping them arms the
 * modifier so the very next keystroke is transformed once, then the chip
 * disarms itself. That gives users a phone-native Ctrl-C / Ctrl-D / Alt-.
 * flow without a hardware modifier key.
 *
 * A previous version of this file defined a private
 * `LazyListScope.items(count, block)` helper that recursively invoked itself
 * with the same signature — an infinite loop at first render. This file now
 * uses `LazyRow`'s built-in overload directly, which is what was intended.
 */
package com.xssh.core.terminal

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession

enum class SpecialKey {
    ESC,
    TAB,
    CTRL_TOGGLE,
    ALT_TOGGLE,
    PASTE,
    UP,
    DOWN,
    LEFT,
    RIGHT,
    HOME,
    END,
    PG_UP,
    PG_DN,
    PIPE,
    SLASH,
    DASH,
    TILDE,
}

fun SpecialKey.toBytes(): ByteArray =
    when (this) {
        SpecialKey.ESC -> byteArrayOf(0x1b)
        SpecialKey.TAB -> byteArrayOf(0x09)
        SpecialKey.UP -> byteArrayOf(0x1b, '['.code.toByte(), 'A'.code.toByte())
        SpecialKey.DOWN -> byteArrayOf(0x1b, '['.code.toByte(), 'B'.code.toByte())
        SpecialKey.RIGHT -> byteArrayOf(0x1b, '['.code.toByte(), 'C'.code.toByte())
        SpecialKey.LEFT -> byteArrayOf(0x1b, '['.code.toByte(), 'D'.code.toByte())
        SpecialKey.HOME -> byteArrayOf(0x1b, '['.code.toByte(), 'H'.code.toByte())
        SpecialKey.END -> byteArrayOf(0x1b, '['.code.toByte(), 'F'.code.toByte())
        SpecialKey.PG_UP -> byteArrayOf(0x1b, '['.code.toByte(), '5'.code.toByte(), '~'.code.toByte())
        SpecialKey.PG_DN -> byteArrayOf(0x1b, '['.code.toByte(), '6'.code.toByte(), '~'.code.toByte())
        SpecialKey.PIPE -> "|".toByteArray()
        SpecialKey.SLASH -> "/".toByteArray()
        SpecialKey.DASH -> "-".toByteArray()
        SpecialKey.TILDE -> "~".toByteArray()
        SpecialKey.CTRL_TOGGLE, SpecialKey.ALT_TOGGLE, SpecialKey.PASTE -> ByteArray(0)
    }

fun SpecialKey.toBytes(
    session: TerminalSession?,
    ctrl: Boolean,
    alt: Boolean,
): ByteArray {
    val keyCode =
        when (this) {
            SpecialKey.ESC -> KeyEvent.KEYCODE_ESCAPE
            SpecialKey.TAB -> KeyEvent.KEYCODE_TAB
            SpecialKey.UP -> KeyEvent.KEYCODE_DPAD_UP
            SpecialKey.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            SpecialKey.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
            SpecialKey.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
            SpecialKey.HOME -> KeyEvent.KEYCODE_MOVE_HOME
            SpecialKey.END -> KeyEvent.KEYCODE_MOVE_END
            SpecialKey.PG_UP -> KeyEvent.KEYCODE_PAGE_UP
            SpecialKey.PG_DN -> KeyEvent.KEYCODE_PAGE_DOWN
            else -> null
        }
    if (keyCode != null) {
        var modifiers = 0
        if (ctrl) modifiers = modifiers or KeyHandler.KEYMOD_CTRL
        if (alt) modifiers = modifiers or KeyHandler.KEYMOD_ALT
        val emulator = session?.emulator
        val sequence =
            KeyHandler.getCode(
                keyCode,
                modifiers,
                emulator?.isCursorKeysApplicationMode ?: false,
                emulator?.isKeypadApplicationMode ?: false,
            )
        if (sequence != null) return sequence.toByteArray(Charsets.UTF_8)
    }
    val raw = toBytes()
    if (raw.isEmpty()) return raw
    val controlled =
        if (ctrl) {
            val first = raw[0].toInt() and 0xff
            if (first in 0x20..0x7e) byteArrayOf(first.and(0x1f).toByte()) + raw.copyOfRange(1, raw.size) else raw
        } else {
            raw
        }
    return if (alt) byteArrayOf(0x1b) + controlled else controlled
}

/**
 * Ordered chip list. Declared at file scope so a fresh list is not allocated
 * on every recomposition — the bar re-renders every time the arm/disarm
 * state flips, which is often.
 */
private val BAR_KEYS: List<Pair<String, SpecialKey>> =
    listOf(
        "Esc" to SpecialKey.ESC,
        "Tab" to SpecialKey.TAB,
        "Ctrl" to SpecialKey.CTRL_TOGGLE,
        "Alt" to SpecialKey.ALT_TOGGLE,
        "Paste" to SpecialKey.PASTE,
        "↑" to SpecialKey.UP,
        "↓" to SpecialKey.DOWN,
        "←" to SpecialKey.LEFT,
        "→" to SpecialKey.RIGHT,
        "Home" to SpecialKey.HOME,
        "End" to SpecialKey.END,
        "PgUp" to SpecialKey.PG_UP,
        "PgDn" to SpecialKey.PG_DN,
        "|" to SpecialKey.PIPE,
        "/" to SpecialKey.SLASH,
        "-" to SpecialKey.DASH,
        "~" to SpecialKey.TILDE,
    )

@Composable
fun ModifierBar(
    ctrlArmed: Boolean,
    altArmed: Boolean,
    onKey: (SpecialKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(count = BAR_KEYS.size) { i ->
            val (label, code) = BAR_KEYS[i]
            val selected =
                (code == SpecialKey.CTRL_TOGGLE && ctrlArmed) ||
                    (code == SpecialKey.ALT_TOGGLE && altArmed)
            FilterChip(
                selected = selected,
                onClick = { onKey(code) },
                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}
