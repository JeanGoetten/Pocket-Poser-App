package com.posepocket.pose

/** Native common 33-landmark topology of ML Kit Pose / MediaPipe Pose.
 * Names are model-defined landmarks, NOT Nova-77 bone names or rotations.
 * Edges use 0-based landmark indices and never imply inferred missing bones.
 */
object Pose33 {
    val names=listOf(
        "nose", "left_eye_inner", "left_eye", "left_eye_outer",
        "right_eye_inner", "right_eye", "right_eye_outer",
        "left_ear", "right_ear", "mouth_left", "mouth_right",
        "left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
        "left_wrist", "right_wrist", "left_pinky", "right_pinky",
        "left_index", "right_index", "left_thumb", "right_thumb",
        "left_hip", "right_hip", "left_knee", "right_knee",
        "left_ankle", "right_ankle", "left_heel", "right_heel",
        "left_foot_index", "right_foot_index"
    )
    val edges=listOf(
        0 to 1,1 to 2,2 to 3,3 to 7,
        0 to 4,4 to 5,5 to 6,6 to 8,
        9 to 10,
        11 to 12,11 to 23,12 to 24,23 to 24,
        11 to 13,13 to 15,15 to 17,15 to 19,15 to 21,17 to 19,
        12 to 14,14 to 16,16 to 18,16 to 20,16 to 22,18 to 20,
        23 to 25,25 to 27,27 to 29,29 to 31,27 to 31,
        24 to 26,26 to 28,28 to 30,30 to 32,28 to 32
    )
    init {
        check(names.size==33)
        check(names.toSet().size==33)
        check(edges.all{(a,b)->a in 0..32&&b in 0..32&&a!=b})
    }

    /** Visibility is a probability of being seen, NOT validity of a predicted 3D location. */
    fun isEstimated(i:Int,scores:List<Float?>?,threshold:Float=.30f):Boolean =
        scores!=null && (scores.getOrNull(i)?.let { it<threshold }?:true)
    fun isEstimated(a:Int,b:Int,scores:List<Float?>?,threshold:Float=.30f):Boolean =
        isEstimated(a,scores,threshold)||isEstimated(b,scores,threshold)

    fun hasBody(mask:List<Boolean>):Boolean=
        mask.size==33&&mask[11]&&mask[12]&&mask[23]&&mask[24]&&
            listOf(25,26,27,28).any { mask[it] }
}
