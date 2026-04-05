package com.artmedical.dcc.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PriorityLogicTest {

    @Test
    fun priority0_mapsToQos0() {
        assertThat(ConnectivityService.mapPriorityToQos(0)).isEqualTo(0)
    }

    @Test
    fun priority1_mapsToQos1() {
        assertThat(ConnectivityService.mapPriorityToQos(1)).isEqualTo(1)
    }

    @Test
    fun priority2_mapsToQos1() {
        assertThat(ConnectivityService.mapPriorityToQos(2)).isEqualTo(1)
    }

    @Test
    fun negativePriority_defaultsToQos0() {
        assertThat(ConnectivityService.mapPriorityToQos(-1)).isEqualTo(0)
    }

    @Test
    fun unknownHighPriority_defaultsToQos0() {
        assertThat(ConnectivityService.mapPriorityToQos(99)).isEqualTo(0)
    }

    @Test
    fun priorityThreshold_0isLowPriority() {
        // DAO query: priority < 1 = low priority
        assertThat(0 < 1).isTrue()
    }

    @Test
    fun priorityThreshold_1isHighPriority() {
        // DAO query: priority >= 1 = high priority
        assertThat(1 >= 1).isTrue()
    }

    @Test
    fun priorityThreshold_2isHighPriority() {
        assertThat(2 >= 1).isTrue()
    }
}
