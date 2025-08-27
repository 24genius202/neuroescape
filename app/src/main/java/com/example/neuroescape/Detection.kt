package com.example.neuroescape

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