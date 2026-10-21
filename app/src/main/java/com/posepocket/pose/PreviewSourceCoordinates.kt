package com.posepocket.pose

/** Studio preview is Y-up. Offline estimators are Y-up on ingest.
 * Only NVIDIA's raw source convention needs a vertical/depth flip. */
internal fun previewSourceCoordinates(point:Vec3,generator:String):Vec3=
    if(generator=="NVIDIA")Vec3(point.x,-point.y,-point.z)else point
