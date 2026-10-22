package com.simplegym.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EngineInfoTest {
    @Test
    fun engineVersionIsOne() {
        assertThat(ENGINE_VERSION).isEqualTo(1)
    }
}
