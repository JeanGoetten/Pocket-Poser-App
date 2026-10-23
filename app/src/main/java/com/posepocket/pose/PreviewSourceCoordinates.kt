package com.posepocket.pose

/** NVIDIA source coordinates require a vertical/depth flip for the Y-up studio. */
internal fun previewSourceCoordinates(point:Vec3,generator:String):Vec3 {
    require(generator=="NVIDIA")
    return Vec3(point.x,-point.y,-point.z)
}
