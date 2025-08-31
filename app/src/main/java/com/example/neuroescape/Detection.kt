package com.example.neuroescape

import android.graphics.Bitmap

//클래스 선언
data class Detection(
    val classId: Int,
    val confidence: Float,
    val box: Box
)

data class Box(
    val x: Float,     // top-left x (normalized 0~1)
    val y: Float,     // top-left y
    val width: Float,
    val height: Float
)

data class CroppedBitmapResult(
    val bitmap: Bitmap,
    val centerX: Int,
    val centerY: Int,
    val width: Int,
    val height: Int
)