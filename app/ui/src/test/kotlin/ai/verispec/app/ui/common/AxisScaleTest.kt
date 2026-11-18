package ai.verispec.app.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AxisScaleTest {

    @Test
    fun `throughput from zero ticks on round numbers covering the data`() {
        val s = AxisScale.of(18.5, 25.3, fromZero = true)
        assertThat(s.min).isEqualTo(0.0)
        assertThat(s.max).isAtLeast(25.3)
        assertThat(s.ticks.map(s::label)).containsExactly("0", "10", "20", "30").inOrder()
    }

    @Test
    fun `temperature range stays tight instead of starting at zero`() {
        val s = AxisScale.of(29.9, 30.8, fromZero = false)
        assertThat(s.min).isAtMost(29.9)
        assertThat(s.min).isGreaterThan(29.0)
        assertThat(s.max).isAtLeast(30.8)
        assertThat(s.ticks.map(s::label)).contains("30")
        assertThat(s.ticks.size).isAtMost(7)
    }

    @Test
    fun `iteration axis never ticks between whole iterations`() {
        val s = AxisScale.of(0.0, 2.0, fromZero = true, targetTicks = 5, minStep = 1.0)
        assertThat(s.ticks).containsExactly(0.0, 1.0, 2.0).inOrder()
    }

    @Test
    fun `long run gets few labelled ticks`() {
        val s = AxisScale.of(0.0, 2_999.0, fromZero = true, targetTicks = 5)
        assertThat(s.ticks.size).isAtMost(7)
        assertThat(s.ticks.map(s::label)).contains("1000")
    }

    @Test
    fun `flat series still gets a visible band`() {
        val s = AxisScale.of(251.0, 251.0, fromZero = false)
        assertThat(s.span).isGreaterThan(0.0)
        assertThat(s.fraction(251.0)).isIn(com.google.common.collect.Range.open(0.0, 1.0))
    }

    @Test
    fun `labels use only the decimals the step needs`() {
        val s = AxisScale.of(0.0, 1.0, fromZero = true)
        assertThat(s.ticks.map(s::label)).containsExactly("0", "0.25", "0.5", "0.75", "1").inOrder()
    }
}
