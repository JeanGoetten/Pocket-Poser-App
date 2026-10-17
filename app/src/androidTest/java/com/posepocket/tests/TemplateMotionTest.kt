package com.posepocket.tests

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posepocket.nvidia.*
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TemplateMotionTest {
    @Test fun tracksMeasuredTranslationAndRejectsBlankSceneCut() {
        check(OpenCVLoader.initLocal())
        val previous=Mat.zeros(300,400,CvType.CV_8UC1);val current=Mat();val blank=Mat.zeros(300,400,CvType.CV_8UC1)
        val affine=Mat(2,3,CvType.CV_64F)
        try {
            val patch=previous.submat(Rect(70,50,90,180))
            try{Core.setRNGSeed(17);Core.randu(patch,20.0,230.0)}finally{patch.release()}
            affine.put(0,0,1.0,0.0,3.0,0.0,1.0,2.0)
            Imgproc.warpAffine(previous,current,affine,previous.size())
            val box=PersonBox(0,70f,50f,90f,180f)
            val tracked=PersonTemplateMotion.predict(previous,current,box,1)!!
            assertEquals(73f,tracked.x,1f);assertEquals(52f,tracked.y,1f)
            assertNull(PersonTemplateMotion.predict(previous,blank,box,1))
            assertNull(PersonTemplateMotion.predict(blank,blank,box,1))
        }finally{previous.release();current.release();blank.release();affine.release()}
    }
    @Test fun rejectsTwoEquallyGoodMatches() {
        check(OpenCVLoader.initLocal())
        val previous=Mat.zeros(300,400,CvType.CV_8UC1);val current=Mat.zeros(300,400,CvType.CV_8UC1)
        val template=previous.submat(Rect(110,77,20,90))
        val first=current.submat(Rect(95,77,20,90));val second=current.submat(Rect(125,77,20,90))
        try {
            Core.setRNGSeed(17);Core.randu(template,20.0,230.0)
            template.copyTo(first);template.copyTo(second)
            assertNull(PersonTemplateMotion.predict(previous,current,PersonBox(0,100f,50f,40f,180f),1))
        }finally{template.release();first.release();second.release();previous.release();current.release()}
    }
}
