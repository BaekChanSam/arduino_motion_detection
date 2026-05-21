package com.example.data.ble

import android.util.Log
import com.example.entity.SensorStatus

object SensorStatusParser {

    private const val TAG = "BLE_PARSER"

    fun parse(line: String): SensorStatus? {
        // Expected: STATUS:NORMAL,ANGLE:30,FIRE:0,MQ3:200,HEAD:0,LARM:0,RARM:0,LLEG:0,RLEG:0
        Log.v(TAG, "input ▶ \"$line\"")
        val map = HashMap<String, String>(16)
        line.split(',').forEach { token ->
            val idx = token.indexOf(':')
            if (idx > 0) {
                map[token.substring(0, idx).trim()] = token.substring(idx + 1).trim()
            }
        }
        if (!map.containsKey("STATUS")) {
            Log.w(TAG, "missing STATUS key, raw=\"$line\"")
            return null
        }
        Log.v(TAG, "map ▶ $map")
        return runCatching {
            SensorStatus(
                isFall = map["STATUS"] == "FALL",
                angle = map["ANGLE"]?.toIntOrNull() ?: 0,
                isFireDetected = map["FIRE"] == "1",
                mq3Value = map["MQ3"]?.toIntOrNull() ?: 0,
                hitHead = map["HEAD"] == "1",
                // ⚠ 배선 보정: LARM↔RLEG, RARM↔LLEG 스왑
                hitLeftArm = map["RLEG"] == "1",   // 원본 우측다리(RLEG) → 왼팔로 표시
                hitRightArm = map["LLEG"] == "1",  // 원본 좌측다리(LLEG) → 오른팔로 표시
                hitLeftLeg = map["RARM"] == "1",   // 원본 우측팔(RARM) → 왼다리로 표시
                hitRightLeg = map["LARM"] == "1",  // 원본 좌측팔(LARM) → 오른다리로 표시
            )
        }.onFailure { Log.e(TAG, "parse exception: ${it.message}", it) }.getOrNull()
    }
}
