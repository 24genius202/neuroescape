package com.example.neuroescape

import android.graphics.Bitmap

data class Detection(
    val classId: Int,
    val confidence: Float,
    val box: Box
)

data class Box(
    val x: Float,
    val y: Float,
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

data class BitmapSize(
    val X: Int,
    val Y: Int,
    val beforewidth: Int,
    val beforeheight: Int,
    val scale: Float
)

data class DebugInfo(
    val originalbitmapwidth: Int,
    val originalbitmapheight: Int,
    val croppedbitmapwidth: Int,
    val croppedbitmapheight: Int,
    val finalW: Int,
    val finalH: Int,
    val cropscale: Float,
    val bboxabsx: Int,
    val absolutex: Float
)