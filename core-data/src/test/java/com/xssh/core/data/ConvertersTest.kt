package com.xssh.core.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConvertersTest {
    private val converters = Converters()

    @Test fun `tag lists round trip delimiters and unicode without corruption`() {
        val source = listOf("prod\u0001blue", "日本語", "comma,space and quote \"")
        assertThat(converters.toStringList(converters.fromStringList(source)))
            .containsExactlyElementsIn(source)
            .inOrder()
    }

    @Test fun `legacy delimiter rows remain readable`() {
        assertThat(converters.toStringList("prod\u0001gateway"))
            .containsExactly("prod", "gateway")
            .inOrder()
    }
}
