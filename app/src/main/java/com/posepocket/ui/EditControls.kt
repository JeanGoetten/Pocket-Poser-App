package com.posepocket.ui

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.posepocket.R
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.posepocket.PocketViewModel
import com.posepocket.pose.Vec3

@Composable
fun EditControls(model:PocketViewModel,enabled:Boolean) {
    val context=LocalContext.current
    var joint by remember { mutableStateOf("13") }
    var start by remember { mutableStateOf("0") }
    var end by remember { mutableStateOf("0") }
    var angle by remember { mutableFloatStateOf(0f) }
    var interpolate by remember { mutableStateOf(false) }
    var endAngle by remember { mutableStateOf("0") }
    var chain by remember { mutableStateOf("11,12,13") }
    var target by remember { mutableStateOf("-0.3,0.5,0.1") }
    var pole by remember { mutableStateOf("0,1,1") }
    fun vector(text:String):Vec3 {
        val values=text.split(',').map{it.trim().toFloat()};require(values.size==3&&values.all{it.isFinite()})
        return Vec3(values[0],values[1],values[2])
    }
    HorizontalDivider()
    Text(stringResource(R.string.edit_title),style=MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.edit_hint))
    OutlinedTextField(joint,{joint=it},label={Text(stringResource(R.string.edit_joint))})
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(start,{start=it},label={Text(stringResource(R.string.edit_start))},modifier=Modifier.weight(1f))
        OutlinedTextField(end,{end=it},label={Text(stringResource(R.string.edit_end))},modifier=Modifier.weight(1f))
    }
    Text(stringResource(R.string.edit_rotation_z,angle.toInt()))
    Slider(angle,{angle=it},valueRange=-180f..180f,enabled=enabled)
    Row {Checkbox(interpolate,{interpolate=it},enabled=enabled);Text(stringResource(R.string.edit_interpolate))}
    if(interpolate)OutlinedTextField(endAngle,{endAngle=it},label={Text(stringResource(R.string.edit_end_rotation))})
    Button(enabled=enabled,onClick={
        try {model.rotateJoint(joint.toInt(),start.toInt(),end.toInt(),angle,if(interpolate)endAngle.toFloat()else null)}
        catch(_:Exception){model.report(context.getString(R.string.edit_error_values))}
    }){Text(stringResource(R.string.edit_apply))}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled=enabled&&model.canUndo,onClick=model::undoEdit){Text(stringResource(R.string.edit_undo))}
        OutlinedButton(enabled=enabled&&model.canRedo,onClick=model::redoEdit){Text(stringResource(R.string.edit_redo))}
    }
    Text(stringResource(R.string.ik_title))
    OutlinedTextField(chain,{chain=it},label={Text(stringResource(R.string.ik_chain))})
    OutlinedTextField(target,{target=it},label={Text(stringResource(R.string.ik_target))})
    OutlinedTextField(pole,{pole=it},label={Text(stringResource(R.string.ik_pole))})
    Button(enabled=enabled,onClick={
        try {val ids=chain.split(',').map{it.trim().toInt()};require(ids.size==3)
            model.solveIk(start.toInt(),ids[0],ids[1],ids[2],vector(target),vector(pole))}
        catch(_:Exception){model.report(context.getString(R.string.ik_error))}
    }){Text(stringResource(R.string.ik_apply))}
}
