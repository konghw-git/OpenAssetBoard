package com.assetboard.ui

import com.github.takahirom.roborazzi.RoborazziOptions

/**
 * How every screenshot is compared with its baseline. The demo data is dated from the clock, so a
 * time such as 19:19 on screen moves a few dozen pixels between the run that recorded the baseline
 * and the one that checks it; a layout or colour that changed moves far more than 0.5% of them.
 */
val SHOT_OPTIONS = RoborazziOptions(
    compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.005f),
)
