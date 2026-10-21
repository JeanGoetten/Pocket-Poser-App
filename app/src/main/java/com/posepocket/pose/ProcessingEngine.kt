package com.posepocket.pose

/** User-visible inference engines; HOG is retained only as a QA baseline. */
enum class ProcessingEngine {
    NVIDIA,
    ML_KIT,
    MEDIAPIPE;

    val offline: Boolean get() = this != NVIDIA
}
