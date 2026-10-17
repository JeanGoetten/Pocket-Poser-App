package com.posepocket.nvidia

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/** The only conversion boundary: HOG, templates and gap recovery all use detector pixels. */
internal fun PersonBox.inVideoCoordinates(detectorWidth:Int,detectorHeight:Int,videoWidth:Int,videoHeight:Int):PersonBox {
    require(detectorWidth>0&&detectorHeight>0&&videoWidth>0&&videoHeight>0)
    val scaleX=videoWidth.toFloat()/detectorWidth
    val scaleY=videoHeight.toFloat()/detectorHeight
    return copy(x=x*scaleX,y=y*scaleY,width=width*scaleX,height=height*scaleY)
}

/** A prediction is publishable only after another detector observation confirms
 * the same track. No unbounded carry-forward or invented bounding boxes. */
class ConfirmedTrackGaps(private val maxGap:Int=12) {
    var last:PersonBox?=null;private set
    private val pending=mutableListOf<PersonBox>()
    val canPredict get()=last!=null&&pending.size<=maxGap
    fun accept(detection:PersonBox?,prediction:PersonBox?):List<PersonBox> {
        if(detection!=null) {
            val recovered=if(pending.isNotEmpty()&&prediction!=null&&SinglePersonTrack.overlap(prediction,detection)>=.5f)pending.toList() else emptyList()
            pending.clear();last=detection
            return recovered+detection
        }
        if(prediction!=null&&last!=null&&pending.size<maxGap) {
            pending+=prediction;last=prediction
        }else{pending.clear();last=null}
        return emptyList()
    }
}

/** Short-range, image-backed translation of the torso. Reject flat patches,
 * weak/ambiguous matches, out-of-frame results and abrupt appearance changes. */
object PersonTemplateMotion {
    fun padded(box:PersonBox,width:Int,height:Int):PersonBox {
        val left=max(0f,box.x-box.width*.15f);val top=max(0f,box.y-box.height*.15f)
        return box.copy(x=left,y=top,width=min(width.toFloat(),box.x+box.width*1.15f)-left,
            height=min(height.toFloat(),box.y+box.height*1.15f)-top)
    }
    fun predict(previous:Mat,current:Mat,box:PersonBox,frame:Int):PersonBox? {
        if(previous.empty()||previous.size()!=current.size())return null
        val px=(box.x+box.width*.25).toInt();val py=(box.y+box.height*.15).toInt()
        val pw=(box.width*.5).toInt();val ph=(box.height*.5).toInt()
        if(pw<8||ph<16||px<0||py<0||px+pw>previous.cols()||py+ph>previous.rows())return null
        val radius=min(32.0,box.height*.12).toInt()
        val sx=max(0,px-radius);val sy=max(0,py-radius)
        val right=min(current.cols(),px+pw+radius);val bottom=min(current.rows(),py+ph+radius)
        val template=previous.submat(Rect(px,py,pw,ph));val search=current.submat(Rect(sx,sy,right-sx,bottom-sy))
        val scores=Mat();val mean=MatOfDouble();val deviation=MatOfDouble()
        try {
            Core.meanStdDev(template,mean,deviation)
            if(deviation.toArray()[0]<=8)return null
            Imgproc.matchTemplate(search,template,scores,Imgproc.TM_CCOEFF_NORMED)
            val match=Core.minMaxLoc(scores)
            if(!match.maxVal.isFinite()||match.maxVal<.85)return null
            val cx=match.maxLoc.x.toInt();val cy=match.maxLoc.y.toInt()
            val exclusion=scores.submat(Rect(max(0,cx-8),max(0,cy-8),
                min(scores.cols(),cx+9)-max(0,cx-8),min(scores.rows(),cy+9)-max(0,cy-8)))
            try{exclusion.setTo(Scalar(-1.0))}finally{exclusion.release()}
            if(match.maxVal-Core.minMaxLoc(scores).maxVal<.015)return null
            val x=box.x+sx+cx-px;val y=box.y+sy+cy-py
            if(x<0||y<0||x+box.width>current.cols()||y+box.height>current.rows())return null
            return box.copy(frame=frame,x=x,y=y)
        }finally{template.release();search.release();scores.release();mean.release();deviation.release()}
    }
}
