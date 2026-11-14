package com.poolguide.tool

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.poolguide.tool.ui.PracticeActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Offline practice mode (v1.0.4, Cheto lead). */
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
}
