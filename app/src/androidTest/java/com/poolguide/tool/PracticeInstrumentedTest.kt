package com.poolguide.tool

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.poolguide.tool.ui.PracticeActivity
import com.poolguide.tool.ui.PracticeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Offline practice mode (v1.0.4, Cheto lead; combo/kiss modes v1.0.6; bank mode v1.0.7). */
@RunWith(AndroidJUnit4::class)
class PracticeInstrumentedTest {

    @Test
    fun practice_launchesSolvesAndNudges() {
        ActivityScenario.launch(PracticeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val state = activity.practiceView.state
                assertNotNull("practice state must exist", state)
                assertNotNull("default layout must solve a shot", state!!.solve())

                // 1-px nudge moves the selected ball exactly 1 px.
                val cueBefore = state.cue.center
                state.nudge(1.0, 0.0)
                assertEquals(cueBefore.x + 1.0, state.cue.center.x, 0.0)
                assertEquals(cueBefore.y, state.cue.center.y, 0.0)

                // Selecting the target moves the target instead.
                state.select(1)
                val targetBefore = state.target.center
                state.nudge(0.0, -1.0)
                assertEquals(targetBefore.y - 1.0, state.target.center.y, 0.0)
                assertEquals(cueBefore.x + 1.0, state.cue.center.x, 0.0)

                // Reset restores the default layout.
                state.reset()
                assertEquals(200.0, state.cue.center.x, 0.0)
                assertEquals(424.0, state.target.center.x, 0.0)
                assertEquals(0, state.selectedId)
            }
        }
    }

    @Test
    fun practice_modesSolveBankComboAndKiss() {
        ActivityScenario.launch(PracticeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val state = activity.practiceView.state
                assertNotNull("practice state must exist", state)

                // Bank via the mode button: two balls, bank solves off
                // the bottom rail to the top-right pocket.
                activity.findViewById<android.widget.Button>(R.id.btn_mode_bank)
                    .performClick()
                assertEquals(PracticeState.PracticeMode.BANK, state!!.mode)
                assertEquals(2, state.balls().size)
                val bank = state.solve()
                assertTrue(
                    "bank default must solve",
                    bank is PracticeState.PracticeSolution.Bank
                )
                assertEquals(
                    "bottom",
                    (bank as PracticeState.PracticeSolution.Bank).railName
                )
                activity.practiceView.invalidate()

                // Combo via the mode button: three balls, combo solves.
                activity.findViewById<android.widget.Button>(R.id.btn_mode_combo)
                    .performClick()
                assertEquals(PracticeState.PracticeMode.COMBO, state!!.mode)
                assertEquals(3, state.balls().size)
                assertTrue(
                    "combo default must solve",
                    state.solve() is PracticeState.PracticeSolution.Combo
                )
                activity.practiceView.invalidate()

                // Kiss via the mode button: kiss solves.
                activity.findViewById<android.widget.Button>(R.id.btn_mode_kiss)
                    .performClick()
                assertEquals(PracticeState.PracticeMode.KISS, state.mode)
                assertTrue(
                    "kiss default must solve",
                    state.solve() is PracticeState.PracticeSolution.Kiss
                )
                activity.practiceView.invalidate()

                // Back to direct: two balls, direct solves.
                activity.findViewById<android.widget.Button>(R.id.btn_mode_direct)
                    .performClick()
                assertEquals(PracticeState.PracticeMode.DIRECT, state.mode)
                assertEquals(2, state.balls().size)
                assertTrue(
                    "direct default must solve",
                    state.solve() is PracticeState.PracticeSolution.Direct
                )
            }
        }
    }
}
