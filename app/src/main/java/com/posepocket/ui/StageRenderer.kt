package com.posepocket.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import com.posepocket.pose.Nova77
import com.posepocket.pose.Pose33
import com.posepocket.pose.Vec3
import com.posepocket.pose.ViewCamera
import com.posepocket.ui.theme.PocketExtras
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class StageMode { SKELETON, MANNEQUIN }

private fun perspective(depth: Float) = 3f / (3f + depth).coerceAtLeast(.2f)
private fun isFinger(child: Int) = child in 11..66 && (child - 11) % 28 >= 4
// Immutable skeleton topology; do not rebuild/filter it on each render pass.
private val drawableBoneIds = Nova77.parents.indices.filter { Nova77.parents[it] >= 0 }

/**
 * Approximate bone thickness in metres by Nova-77 region:
 * spine 0-6, head 7-10, two arms 11-66 (28 joints each), two legs 67-76 (5 joints each).
 * Used only by the mannequin view; the data itself is never changed.
 */
internal fun boneThickness(child: Int): Float = when (child) {
    in 1..2 -> .19f
    in 3..5 -> .21f
    6 -> .07f
    in 7..10 -> .01f
    in 11..66 -> when ((child - 11) % 28) { 0 -> .06f; 1 -> .07f; 2 -> .065f; 3 -> .05f; else -> .018f }
    in 67..76 -> when ((child - 67) % 5) { 0 -> .09f; 1 -> .095f; 2 -> .07f; 3 -> .045f; else -> .04f }
    else -> .02f
}

/** Draws the stage: floor, shadow and the pose in the chosen mode. Pure drawing, no state. */
fun DrawScope.drawStage(camera: ViewCamera, joints: List<Vec3>?, mode: StageMode,
    colors: PocketExtras, visibility:List<Boolean>?=null,
    confidence:List<Float?>?=null) {
    drawRect(colors.stage)
    val unit = min(size.width, size.height) / 2.1f
    val px = max(size.minDimension / 300f, 1f)
    fun screen(p: Vec3) = Offset(size.width / 2 + p.x * unit, size.height * .48f - p.y * unit)

    // Floor grid fades toward the edges so it frames the figure instead of competing with it.
    for (line in -6..6) {
        val v = line * .35f
        val fade = (1f - abs(line) / 7f).coerceIn(.15f, 1f)
        val color = colors.stageFloor.copy(alpha = colors.stageFloor.alpha * fade)
        drawLine(color, screen(camera.project(Vec3(v, -.98f, -2.1f))), screen(camera.project(Vec3(v, -.98f, 2.1f))), 1f)
        drawLine(color, screen(camera.project(Vec3(-2.1f, -.98f, v))), screen(camera.project(Vec3(2.1f, -.98f, v))), 1f)
    }
    val shadow = screen(camera.project(Vec3(0f, -.95f, 0f)))
    drawOval(Color.Black.copy(alpha = .22f), Offset(shadow.x - unit * .28f * camera.zoom, shadow.y - unit * .08f * camera.zoom), Size(unit * .56f * camera.zoom, unit * .16f * camera.zoom))

    if (joints == null) return
    if(joints.size==33) {
        drawPose33(camera,joints,visibility,confidence,mode,colors,unit,px,{ screen(it) })
        return
    }
    if(joints.size!=77)return
    val points = joints.map { camera.project(it) }
    // Far to near, so closer bones overlap farther ones.
    val order = drawableBoneIds.sortedByDescending { points[it].z }

    if (mode == StageMode.SKELETON) {
        order.forEach { child ->
            val a = points[Nova77.parents[child]]
            val b = points[child]
            val alpha = (1f - b.z * .25f).coerceIn(.5f, 1f)
            val width = (if (isFinger(child)) 1f else 2.2f) * px * perspective(b.z)
            drawLine(colors.stageBone.copy(alpha = alpha), screen(a), screen(b), width.coerceAtLeast(1f), StrokeCap.Round)
        }
        points.forEachIndexed { index, p ->
            val alpha = (1f - p.z * .25f).coerceIn(.5f, 1f)
            val finger = isFinger(index)
            drawCircle(colors.stageBone.copy(alpha = alpha), (if (finger) 1.1f else 2.6f) * px * perspective(p.z), screen(p))
        }
        return
    }

    val body = Color(0xFFC3CCD1)
    val shade = Color(0xFF7F8B92)
    if(points.size==77) {
        val torso=Path().apply {
            val a=screen(points[12]);val b=screen(points[40]);val c=screen(points[72]);val d=screen(points[67])
            moveTo(a.x,a.y);lineTo(b.x,b.y);lineTo(c.x,c.y);lineTo(d.x,d.y);close()
        }
        drawPath(torso,Brush.horizontalGradient(listOf(shade,body,Color(0xFFE1E6EA))))
    }
    order.forEach { child ->
        if(child in 1..3||child in 7..10) return@forEach
        val a = points[Nova77.parents[child]]
        val b = points[child]
        val mid = (a.z + b.z) / 2f
        val width = ((if(child in 4..6).07f else boneThickness(child)) * unit * perspective(mid) * camera.zoom).coerceAtLeast(1.5f * px)
        val light = lerp(shade, body, (1f - mid * .3f).coerceIn(.35f, 1f))
        drawLine(shade, screen(a), screen(b), width + px * .9f, StrokeCap.Round)
        drawLine(light, screen(a), screen(b), width, StrokeCap.Round)
    }
    if (points.size >= 11) {
        val head = points.subList(7, 11)
        val center = Offset(head.map { screen(it).x }.average().toFloat(), head.map { screen(it).y }.average().toFloat())
        val depth = head.map { it.z }.average().toFloat()
        val radius = .105f * unit * perspective(depth) * camera.zoom
        drawCircle(shade, radius + px * .9f, center)
        drawCircle(Brush.radialGradient(listOf(Color(0xFFE6EBED), body), center, radius), radius, center)
    }
    // Accent joints keep the technical read of the figure.
    points.forEachIndexed { index, p ->
        if (!isFinger(index) && index !in 7..10) drawCircle(colors.stageBone, 1.7f * px * perspective(p.z), screen(p))
    }
}


/** Render the 33 detected nodes directly; never use Nova-77 bone indices. */
private fun DrawScope.drawPose33(
    camera:ViewCamera, joints:List<Vec3>, validity:List<Boolean>?,
    confidence:List<Float?>?,
    mode:StageMode, colors:PocketExtras, unit:Float, px:Float,
    screen:(Vec3)->Offset,
) {
    require(joints.size==Pose33.names.size)
    val valid=validity?:List(33){true}
    require(valid.size==33)
    require(confidence==null||confidence.size==33)
    val nodes=joints.map(camera::project)
    val edges=Pose33.edges.filter{ (a,b)->valid[a]&&valid[b] }
        .sortedByDescending{(a,b)->(nodes[a].z+nodes[b].z)/2f}
    if(mode==StageMode.MANNEQUIN && listOf(11,12,23,24).all{valid[it]}) {
        val contour=Path().apply {
            val indices=listOf(11,12,24,23)
            val first=screen(nodes[indices.first()])
            moveTo(first.x,first.y)
            indices.drop(1).forEach { i ->
                val at=screen(nodes[i])
                lineTo(at.x,at.y)
            }
            close()
        }
        drawPath(contour,Brush.horizontalGradient(
            listOf(Color(0xFF7F8B92),Color(0xFFC3CCD1),Color(0xFFE1E6EA))))
    }
    edges.forEach{(a,b)->
        val mid=(nodes[a].z+nodes[b].z)/2f
        // Occluded points are PREDICTIONS, not reliable measured joints.
        // Draw them faint/dashed instead of silently removing the far arm.
        val uncertain=Pose33.isEstimated(a,b,confidence)
        val alpha=(1f-mid*.25f).coerceIn(.5f,1f)*(if(uncertain).40f else 1f)
        val color=if(mode==StageMode.SKELETON)colors.stageBone.copy(alpha=alpha)
            else Color(0xFFC3CCD1).copy(alpha=alpha)
        val width=if(mode==StageMode.SKELETON)2.2f*px*perspective(mid)
            else {
                val region=when {
                    a in 23..32&&b in 23..32 -> .095f
                    a in 11..22&&b in 11..22 -> .065f
                    a in 0..10||b in 0..10 -> .022f
                    else -> .08f
                }
                (region*unit*perspective(mid)*camera.zoom).coerceAtLeast(1.5f*px)
            }
        drawLine(color,screen(nodes[a]),screen(nodes[b]),width.coerceAtLeast(1f),
            StrokeCap.Round,
            pathEffect=if(uncertain)PathEffect.dashPathEffect(floatArrayOf(9f*px,6f*px))
                else null)
    }
    // The face uses observed detector landmarks; no phantom neck.
    if(mode==StageMode.MANNEQUIN) {
        val headIndices=(0..10).filter{valid[it]}
        if(headIndices.size>=3) {
            val center=Offset(
                headIndices.map{screen(nodes[it]).x}.average().toFloat(),
                headIndices.map{screen(nodes[it]).y}.average().toFloat())
            val headWidth=if(valid[7]&&valid[8])
                (screen(nodes[7])-screen(nodes[8])).getDistance()
                else (unit*.13f*camera.zoom)
            drawCircle(Color(0xFFC3CCD1),headWidth.coerceIn(5f*px,.28f*unit)*.65f,center)
        }
    }
    nodes.forEachIndexed { i,node ->
        if(valid[i])drawCircle(colors.stageBone.copy(
            alpha=if(Pose33.isEstimated(i,confidence)).40f else 1f),
            (if(i in 0..10)1.35f else 2.5f)*px*perspective(node.z),screen(node))
    }
}
