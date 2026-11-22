package com.aurviz.agent

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Placeholder launcher. The agent UI (chat + timeline) lands in T3+. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = "AURVIZ agent scaffold (T1). UI arrives in T3+."
                textSize = 18f
                setPadding(48, 48, 48, 48)
            },
        )
    }
}
