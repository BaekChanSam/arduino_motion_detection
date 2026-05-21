package com.example.entity

data class SensorStatus(
    val isFall: Boolean,
    val angle: Int,
    val isFireDetected: Boolean,
    val mq3Value: Int,
    val hitHead: Boolean,
    val hitLeftArm: Boolean,
    val hitRightArm: Boolean,
    val hitLeftLeg: Boolean,
    val hitRightLeg: Boolean,
) {
    val anyHit: Boolean
        get() = hitHead || hitLeftArm || hitRightArm || hitLeftLeg || hitRightLeg

    val isAlarm: Boolean
        get() = isFall || isFireDetected || anyHit
}
