package com.poolguide.tool.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.poolguide.tool.R

/**
 * Offline practice mode (Cheto lead, implemented from scratch). A
 * self-contained table where the user drags the cue and target balls and
 * sees the solved shot live, with 1-px nudge buttons for fine adjustment.
 * No capture, no overlay, no game interaction — this screen never touches
 * 8 Ball Pool, so it carries no ban risk at all.
 */
class PracticeActivity : AppCompatActivity() {

    lateinit var practiceView: PracticeView
        private set
    private lateinit var statusText: TextView
    private lateinit var state: PracticeState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_practice)

        state = PracticeState(PracticeView.LOGICAL_TABLE, PracticeView.BALL_RADIUS_PX)
        practiceView = findViewById(R.id.practice_view)
        practiceView.state = state
        statusText = findViewById(R.id.practice_status)

        findViewById<Button>(R.id.btn_nudge_up).setOnClickListener { nudge(0.0, -1.0) }
        findViewById<Button>(R.id.btn_nudge_down).setOnClickListener { nudge(0.0, 1.0) }
        findViewById<Button>(R.id.btn_nudge_left).setOnClickListener { nudge(-1.0, 0.0) }
        findViewById<Button>(R.id.btn_nudge_right).setOnClickListener { nudge(1.0, 0.0) }
        findViewById<Button>(R.id.btn_mode_direct).setOnClickListener {
            setMode(PracticeState.PracticeMode.DIRECT)
        }
        findViewById<Button>(R.id.btn_mode_bank).setOnClickListener {
            setMode(PracticeState.PracticeMode.BANK)
        }
        findViewById<Button>(R.id.btn_mode_combo).setOnClickListener {
            setMode(PracticeState.PracticeMode.COMBO)
        }
        findViewById<Button>(R.id.btn_mode_kiss).setOnClickListener {
            setMode(PracticeState.PracticeMode.KISS)
        }
        findViewById<Button>(R.id.btn_practice_reset).setOnClickListener {
            state.reset()
            refresh()
        }
        updateStatus()
    }

    private fun setMode(mode: PracticeState.PracticeMode) {
        // Switching mode re-lays out the balls to that mode's default.
        state.setMode(mode)
        refresh()
    }

    private fun nudge(dx: Double, dy: Double) {
        state.nudge(dx, dy)
        refresh()
    }

    private fun refresh() {
        practiceView.invalidate()
        updateStatus()
    }

    private fun updateStatus() {
        statusText.text = when (val solution = state.solve()) {
            null -> getString(R.string.practice_no_shot)
            is PracticeState.PracticeSolution.Direct -> {
                val shot = solution.shot
                if (shot.isThinCut) {
                    getString(R.string.practice_cut_thin, shot.cutAngleDegrees)
                } else {
                    getString(R.string.practice_cut, shot.cutAngleDegrees)
                }
            }
            is PracticeState.PracticeSolution.Bank -> {
                val bank = solution.solution
                if (bank.direct.isThinCut) {
                    getString(
                        R.string.practice_bank_thin,
                        solution.railName,
                        bank.direct.cutAngleDegrees
                    )
                } else {
                    getString(
                        R.string.practice_bank,
                        solution.railName,
                        bank.direct.cutAngleDegrees
                    )
                }
            }
            is PracticeState.PracticeSolution.Combo -> {
                val combo = solution.solution
                if (combo.firstShot.isThinCut) {
                    getString(
                        R.string.practice_combo_thin,
                        combo.firstShot.cutAngleDegrees,
                        combo.secondShot.cutAngleDegrees
                    )
                } else {
                    getString(
                        R.string.practice_combo,
                        combo.firstShot.cutAngleDegrees,
                        combo.secondShot.cutAngleDegrees
                    )
                }
            }
            is PracticeState.PracticeSolution.Kiss -> {
                val kiss = solution.solution
                if (kiss.kissShot.isThinCut) {
                    getString(
                        R.string.practice_kiss_thin,
                        kiss.kissShot.cutAngleDegrees,
                        kiss.secondShot.cutAngleDegrees
                    )
                } else {
                    getString(
                        R.string.practice_kiss,
                        kiss.kissShot.cutAngleDegrees,
                        kiss.secondShot.cutAngleDegrees
                    )
                }
            }
        }
    }
}
