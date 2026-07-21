package com.xssh.core.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpecialKeyTest {
    @Test fun `escape is single 0x1b`() {
        assertThat(SpecialKey.ESC.toBytes().toList()).containsExactly(0x1b.toByte())
    }

    @Test fun `arrow keys emit CSI sequences`() {
        assertThat(SpecialKey.UP.toBytes()).isEqualTo(byteArrayOf(0x1b, 0x5b, 0x41))
        assertThat(SpecialKey.DOWN.toBytes()).isEqualTo(byteArrayOf(0x1b, 0x5b, 0x42))
        assertThat(SpecialKey.RIGHT.toBytes()).isEqualTo(byteArrayOf(0x1b, 0x5b, 0x43))
        assertThat(SpecialKey.LEFT.toBytes()).isEqualTo(byteArrayOf(0x1b, 0x5b, 0x44))
    }

    @Test fun `page up and page down`() {
        assertThat(SpecialKey.PG_UP.toBytes()).isEqualTo(byteArrayOf(0x1b, 0x5b, 0x35, 0x7e))
        assertThat(SpecialKey.PG_DN.toBytes()).isEqualTo(byteArrayOf(0x1b, 0x5b, 0x36, 0x7e))
    }

    @Test fun `modifier toggles emit zero bytes`() {
        assertThat(SpecialKey.CTRL_TOGGLE.toBytes()).isEmpty()
        assertThat(SpecialKey.ALT_TOGGLE.toBytes()).isEmpty()
    }
}
