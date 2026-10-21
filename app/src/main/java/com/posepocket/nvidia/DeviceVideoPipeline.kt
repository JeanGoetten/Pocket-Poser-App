package com.posepocket.nvidia

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.posepocket.media.VideoGeometry
import com.posepocket.media.TrimmedTimeline
import com.posepocket.media.TrimRange
import android.media.*
import com.posepocket.media.PreparedVideo
import com.posepocket.pose.fileHash
import com.posepocket.privacy.ConsentGate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.HOGDescriptor
import java.io.File
import java.util.UUID
import kotlin.math.*

data class PersonBox(val frame:Int,val x:Float,val y:Float,val width:Float,val height:Float)
data class NormalizedVideo(val file:File,val hash:String,val frames:Int,val width:Int,val height:Int)

data class DeviceVideo(val file:File,val hash:String,val frames:Int,val width:Int,val height:Int,val boxes:List<PersonBox>,val detectorFrames:Int=boxes.size)

/** Conservative full-body baseline: ambiguity is an error, never a made-up box. */
class SinglePersonTrack {
    private var previous:PersonBox?=null
    private var gap=0
    fun accept(boxes:List<PersonBox>,prediction:PersonBox?=null):PersonBox? {
        require(boxes.size<=1) { "Mais de uma pessoa detectada. Use um vídeo com apenas uma pessoa." }
        val box=boxes.firstOrNull()
        if(box==null) { if(previous!=null)gap++;return null }
        require(listOf(box.x,box.y,box.width,box.height).all { it.isFinite() }&&box.width>0&&box.height>0)
        previous?.let {
            val confirmedMotion=prediction?.let { predicted -> predicted.frame==box.frame&&overlap(predicted,box)>=.5f }==true
            require(gap<=15&&(overlap(it,box)>=.1f||confirmedMotion)) { "Rastreamento ambíguo após movimento ou oclusão. Tente outro vídeo." }
        }
        previous=box;gap=0;return box
    }
    companion object {
        fun overlap(a:PersonBox,b:PersonBox):Float {
            val intersection=max(0f,min(a.x+a.width,b.x+b.width)-max(a.x,b.x))*max(0f,min(a.y+a.height,b.y+b.height)-max(a.y,b.y))
            return intersection/max(1f,a.width*a.height+b.width*b.height-intersection)
        }
    }
}

enum class DetectorBackend { HOG, ML_KIT }

class DeviceVideoPipeline(
    private val gate:ConsentGate,
    private val backend:DetectorBackend =
        if (com.posepocket.BuildConfig.POSE_DETECTOR_MLKIT) DetectorBackend.ML_KIT else DetectorBackend.HOG,
) {
    private suspend fun checkActive() { currentCoroutineContext().ensureActive();gate.withConsent { } }

    suspend fun prepare(input:PreparedVideo,onPhase:(String)->Unit):DeviceVideo {
        val video=normalizeVideo(input,onPhase)
        return try { trackVideo(video,onPhase) }catch(error:Throwable){video.file.delete();throw error}
    }

    suspend fun normalizeVideo(input:PreparedVideo,onPhase:(String)->Unit):NormalizedVideo {
        checkActive()
        // writeYuv uses OpenCV too; normalization can run before the tracking checkpoint.
        check(OpenCVLoader.initLocal()) { "Não foi possível iniciar o processamento de vídeo neste aparelho." }
        val target=File(input.file.parentFile,"${UUID.randomUUID()}-cfr.mp4")
        try {
            require(input.file.parentFile!!.usableSpace>100_000_000) { "Libere pelo menos 100 MB para preparar o vídeo." }
            onPhase("Normalizando vídeo no celular")
            val frames=normalize(input.file,target,onPhase,input.focus,input.trim)
            checkActive()
            onPhase("Verificando vídeo normalizado")
            val extractor=MediaExtractor()
            val dimensions=try {
                extractor.setDataSource(target.absolutePath)
                require(extractor.trackCount==1)
                val f=extractor.getTrackFormat(0)
                require(f.getString(MediaFormat.KEY_MIME)=="video/avc")
                extractor.selectTrack(0)
                var count=0
                while(extractor.sampleTime>=0) {
                    checkActive()
                    require(abs(extractor.sampleTime-count*1_000_000L/30)<=2) { "O aparelho não produziu vídeo CFR compatível." }
                    count++;extractor.advance()
                    if(count%30==0||count==frames)onPhase("Verificando vídeo normalizado: ${count*100/frames}%")
                }
                require(count==frames) { "Contagem de quadros do vídeo normalizado inconsistente." }
                f.getInteger(MediaFormat.KEY_WIDTH) to f.getInteger(MediaFormat.KEY_HEIGHT)
            }finally{extractor.release()}
            return NormalizedVideo(target,fileHash(target),frames,dimensions.first,dimensions.second)
        }catch(error:Throwable){target.delete();throw error}
    }

    suspend fun trackVideo(video:NormalizedVideo,onPhase:(String)->Unit):DeviceVideo {
        checkActive()
        check(OpenCVLoader.initLocal()) { "Não foi possível iniciar o processamento de vídeo neste aparelho." }
        onPhase("Localizando uma pessoa no celular")
        val (boxes,detectorFrames)=track(video.file,video.frames,video.width,video.height,onPhase)
        checkActive()
        return DeviceVideo(video.file,video.hash,video.frames,video.width,video.height,boxes,detectorFrames)
    }

    private suspend fun normalize(input:File,output:File,onPhase:(String)->Unit,focus:com.posepocket.media.FocusWindow?,trim:TrimRange?):Int {
        if(focus==null&&trim==null)compatibleFrameCount(input)?.let { frames ->
            onPhase("Vídeo H.264 SDR 30 CFR compatível; preservando quadros")
            input.inputStream().use { source -> output.outputStream().use { target ->
                val buffer=ByteArray(65536);var copied=0L
                while(true) {
                    checkActive()
                    val count=source.read(buffer)
                    if(count<0)break
                    target.write(buffer,0,count);copied+=count
                    onPhase("Normalizando vídeo no celular: ${copied*100/input.length()}%")
                }
            } }
            return frames
        }
        val retriever=MediaMetadataRetriever()
        var encoder:MediaCodec?=null
        var muxer:MediaMuxer?=null
        var started=false
        var codecStarted=false
        try {
            retriever.setDataSource(input.absolutePath)
            val duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0
            require(duration in 34..600_000) { "Duração de vídeo inválida; limite de 10 minutos." }
            val first=retriever.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST)?:error("Vídeo não decodificável neste aparelho.")
            val firstWidth=first.width;val firstHeight=first.height
            val region=focus?.pixelBounds(first.width,first.height)
            val originalWidth=region?.get(2)?:first.width
            val originalHeight=region?.get(3)?:first.height
            first.recycle()
            val geometry=VideoGeometry.fit(originalWidth,originalHeight)
            val width=geometry.width;val height=geometry.height
            val timeline=trim?.let { range ->
                val extractor=MediaExtractor()
                val timestamps=ArrayList<Long>()
                try {
                    extractor.setDataSource(input.absolutePath);extractor.selectTrack(0)
                    while(extractor.sampleTime>=0) {
                        checkActive();timestamps.add(extractor.sampleTime)
                        require(timestamps.size<=144000);extractor.advance()
                    }
                }finally{extractor.release()}
                TrimmedTimeline(timestamps,range)
            }
            // Indexed decoding returns source-sized bitmaps. Budget for ARGB even
            // when RGB_565 is requested: devices may ignore the preference.
            val batchSize=(16_000_000L/(firstWidth.toLong()*firstHeight*4)).coerceIn(1,8).toInt()
            val fitsBatchBudget=firstWidth.toLong()*firstHeight*4<=16_000_000
            val frames=timeline?.frames?:ceil(duration*30.0/1000).toInt().coerceAtMost(18000)
            val indexedFrames=if(timeline==null&&android.os.Build.VERSION.SDK_INT>=28&&fitsBatchBudget) nearCfrFrameCount(input,frames) else null
            val format=MediaFormat.createVideoFormat("video/avc",width,height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE,2_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE,30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
                setInteger(MediaFormat.KEY_COLOR_STANDARD,MediaFormat.COLOR_STANDARD_BT601_NTSC)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER,MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE,MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                if(android.os.Build.VERSION.SDK_INT>=29)setInteger(MediaFormat.KEY_MAX_B_FRAMES,0)
            }
            val codec=MediaCodec.createEncoderByType("video/avc");encoder=codec
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start();codecStarted=true
            val writer=MediaMuxer(output.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);muxer=writer
            var track=-1
            var end=false
            val info=MediaCodec.BufferInfo()
            suspend fun drain(wait:Boolean) {
                val deadline=System.nanoTime()+30_000_000_000L
                do {
                    checkActive()
                    val index=codec.dequeueOutputBuffer(info,if(wait)10_000 else 0)
                    when {
                        index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { check(!started);track=writer.addTrack(codec.outputFormat);writer.start();started=true }
                        index>=0 -> {
                            try {
                                if(info.size>0&&info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) {
                                    check(started)
                                    val bytes=requireNotNull(codec.getOutputBuffer(index));bytes.position(info.offset);bytes.limit(info.offset+info.size)
                                    writer.writeSampleData(track,bytes,info)
                                    require(output.length()<=50_000_000) { "Vídeo normalizado excede 50 MB. Use um vídeo mais curto." }
                                }
                                if(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0)end=true
                            }finally{codec.releaseOutputBuffer(index,false)}
                        }
                        index==MediaCodec.INFO_TRY_AGAIN_LATER&&!wait -> return
                    }
                    check(System.nanoTime()<deadline) { "O codificador do aparelho parou de responder." }
                }while(!end&&(wait||index>=0||index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED))
            }
            suspend fun encodeFrame(frame:Int,original:Bitmap?) {
                checkActive()
                var slot=codec.dequeueInputBuffer(10_000)
                val deadline=System.nanoTime()+30_000_000_000L
                while(slot<0) {
                    drain(false);checkActive();check(System.nanoTime()<deadline) { "Tempo limite do codificador." };slot=codec.dequeueInputBuffer(10_000)
                }
                if(frame==frames)codec.queueInputBuffer(slot,0,0,frame*1_000_000L/30,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                else {
                    requireNotNull(original) { "Falha ao decodificar quadro do vídeo." }
                    val region=focus?.pixelBounds(original.width,original.height)
                    val cropped=if(region==null)original else Bitmap.createBitmap(original,region[0],region[1],region[2],region[3])
                    val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).apply {
                        drawColor(Color.BLACK)
                        drawBitmap(cropped,null,Rect(geometry.left,geometry.top,
                            geometry.left+geometry.contentWidth,geometry.top+geometry.contentHeight),Paint(Paint.FILTER_BITMAP_FLAG))
                    }
                    try {
                        val image=codec.getInputImage(slot)?:error("Codificador sem entrada YUV compatível.")
                        try { writeYuv(bitmap,image) }finally{image.close()}
                    }finally{
                        if(bitmap!==cropped)bitmap.recycle()
                        if(cropped!==original)cropped.recycle()
                    }
                    codec.queueInputBuffer(slot,0,width*height*3/2,frame*1_000_000L/30,0)
                    if(frame%5==0||frame==frames-1)onPhase("Normalizando vídeo no celular: ${(frame+1)*100/frames}%")
                }
                drain(false)
            }
            if(indexedFrames!=null) {
                onPhase("Normalizando vídeo no celular: leitura em lotes")
                VideoFrames.forEach(retriever,indexedFrames,::checkActive,batchSize) { frame,bitmap ->
                    encodeFrame(frame,bitmap)
                    // Millisecond metadata may request one final CFR frame beyond
                    // the last source timestamp; time-based retrieval repeats it too.
                    if(frame==indexedFrames-1&&frames>indexedFrames)encodeFrame(frame+1,bitmap)
                }
            }else for(frame in 0 until frames) {
                checkActive()
                val time=timeline?.timestamp(frame)?:frame*1_000_000L/30
                val bitmap=if(android.os.Build.VERSION.SDK_INT>=27)
                    retriever.getScaledFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST,
                        if(focus==null)geometry.contentWidth else firstWidth,
                        if(focus==null)geometry.contentHeight else firstHeight)
                else retriever.getFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST)
                try{encodeFrame(frame,bitmap)}finally{bitmap?.recycle()}
            }
            encodeFrame(frames,null)
            drain(true)
            require(started&&output.length() in 1..50_000_000)
            return frames
        }finally {
            try { if(started)muxer?.stop() }finally {
                muxer?.release()
                try { if(codecStarted)encoder?.stop() }finally{encoder?.release();retriever.release()}
            }
        }
    }

    /** Every output timestamp must select the corresponding source frame. A
     * 1 ms drift is far below the 16.6 ms nearest-frame boundary. Reject reordered
     * samples, VFR, different rates and incompatible duration/count metadata. */
    private suspend fun nearCfrFrameCount(input:File,outputFrames:Int):Int? {
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            if(extractor.trackCount!=1)return null
            extractor.selectTrack(0)
            var count=0
            while(extractor.sampleTime>=0) {
                checkActive()
                if(count>=outputFrames||abs(extractor.sampleTime-count*1_000_000L/30)>1000)return null
                count++;extractor.advance()
            }
            return count.takeIf{it>=2&&outputFrames-it in 0..1}
        }finally{extractor.release()}
    }

    /** Input is already a video-only MP4 from MediaPreparer. Inspect actual timestamps,
     * not the nominal frame-rate field, before avoiding an expensive re-encode. */
    private suspend fun compatibleFrameCount(input:File):Int? {
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            if(extractor.trackCount!=1||input.length() !in 1..50_000_000)return null
            val format=extractor.getTrackFormat(0)
            if(format.getString(MediaFormat.KEY_MIME)!="video/avc")return null
            val width=format.getInteger(MediaFormat.KEY_WIDTH)
            val height=format.getInteger(MediaFormat.KEY_HEIGHT)
            if(width<64||height<128||max(width,height)>720||!VideoGeometry.detectorCompatible(width,height))return null
            if(format.containsKey(MediaFormat.KEY_ROTATION)&&format.getInteger(MediaFormat.KEY_ROTATION)!=0)return null
            // Some extractors omit KEY_ROTATION even when the MP4 display matrix
            // is rotated. Copying that file would send upright boxes against a
            // rotated bitstream, so also check the retriever's container metadata.
            val metadata=MediaMetadataRetriever()
            try {
                metadata.setDataSource(input.absolutePath)
                if(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()!=0)return null
            }finally{metadata.release()}
            if(!format.containsKey(MediaFormat.KEY_PROFILE)||format.getInteger(MediaFormat.KEY_PROFILE) !in listOf(
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline,
                MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh))return null
            if(format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)&&format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) in listOf(
                    MediaFormat.COLOR_TRANSFER_ST2084,MediaFormat.COLOR_TRANSFER_HLG))return null
            extractor.selectTrack(0)
            var count=0
            while(extractor.sampleTime>=0) {
                checkActive()
                if(count>=18000||abs(extractor.sampleTime-count*1_000_000L/30)>2)return null
                count++;extractor.advance()
            }
            return count.takeIf { it>=2 }
        }finally{extractor.release()}
    }

    private fun writeYuv(bitmap:Bitmap,image:Image) {
        val rgba=Mat();val rgb=Mat();val yuv=Mat()
        try {
            Utils.bitmapToMat(bitmap,rgba);Imgproc.cvtColor(rgba,rgb,Imgproc.COLOR_RGBA2RGB);Imgproc.cvtColor(rgb,yuv,Imgproc.COLOR_RGB2YUV_I420)
            val data=ByteArray(bitmap.width*bitmap.height*3/2);yuv.get(0,0,data)
            var offset=0
            image.planes.forEachIndexed { index,plane ->
                val w=if(index==0)bitmap.width else bitmap.width/2
                val h=if(index==0)bitmap.height else bitmap.height/2
                val buffer=plane.buffer.duplicate()
                val base=buffer.position()
                val span=(w-1)*plane.pixelStride+1
                val rowBytes=ByteArray(span)
                for(row in 0 until h) {
                    buffer.position(base+row*plane.rowStride)
                    if(plane.pixelStride==1) {
                        buffer.put(data,offset,w);offset+=w
                    }else {
                        // Chroma planes may share interleaved storage. Preserve the other
                        // component and row padding instead of overwriting either of them.
                        buffer.get(rowBytes)
                        for(col in 0 until w)rowBytes[col*plane.pixelStride]=data[offset++]
                        buffer.position(base+row*plane.rowStride);buffer.put(rowBytes)
                    }
                }
            }
        }finally{rgba.release();rgb.release();yuv.release()}
    }

    private suspend fun track(file:File,frames:Int,width:Int,height:Int,onPhase:(String)->Unit):Pair<List<PersonBox>,Int> {
        val retriever=MediaMetadataRetriever()
        val hog=HOGDescriptor().apply { setSVMDetector(HOGDescriptor.getDefaultPeopleDetector()) }
        val tracker=SinglePersonTrack();val output=mutableListOf<PersonBox>()
        val mlkit=if(backend==DetectorBackend.ML_KIT)MlKitPosePersonDetector()else null
        val startedAt=android.os.SystemClock.elapsedRealtime()
        val gaps=ConfirmedTrackGaps();val previousGray=Mat()
        var detected=0
        try {
            retriever.setDataSource(file.absolutePath)
            VideoFrames.forEach(retriever,frames,::checkActive) { frame,bitmap ->
                val rgba=Mat();val rgb=Mat();val small=Mat();val gray=Mat();val rects=MatOfRect();val weights=MatOfDouble()
                try {
                    Utils.bitmapToMat(bitmap,rgba);Imgproc.cvtColor(rgba,rgb,Imgproc.COLOR_RGBA2RGB)
                    val factor=min(1.0,640.0/max(width,height))
                    Imgproc.resize(rgb,small,Size(width*factor,height*factor))
                    require(small.rows()>=128&&small.cols()>=64) { "Vídeo pequeno demais para localizar o corpo inteiro." }
                    val candidates=if(mlkit!=null) {
                        // Convert ML Kit coordinates to HOG/temporal detector pixels.
                        mlkit.detect(bitmap,frame)?.let { box ->
                            listOf(box.inVideoCoordinates(bitmap.width,bitmap.height,small.cols(),small.rows()))
                        } ?: emptyList()
                    } else {
                        hog.detectMultiScale(small,rects,weights,.5,Size(8.0,8.0),Size(8.0,8.0),1.05,2.0,false)
                        if(rects.empty()||weights.empty()) emptyList() else
                            rects.toArray().zip(weights.toArray().toList()).sortedByDescending { it.second }.map { (r,_) ->
                                PersonBox(frame,r.x.toFloat(),r.y.toFloat(),r.width.toFloat(),r.height.toFloat())
                            }
                    }
                    val kept=mutableListOf<PersonBox>()
                    candidates.forEach { box->if(kept.none { SinglePersonTrack.overlap(it,box)>.4f })kept+=box }
                    Imgproc.cvtColor(small,gray,Imgproc.COLOR_RGB2GRAY)
                    val prediction=if(gaps.canPredict)gaps.last?.let{PersonTemplateMotion.predict(previousGray,gray,it,frame)}else null
                    val detection=tracker.accept(kept.map{PersonTemplateMotion.padded(it,small.cols(),small.rows())},prediction)
                    if(detection!=null)detected++
                    gaps.accept(detection,prediction).forEach { box ->
                        output+=box.inVideoCoordinates(small.cols(),small.rows(),width,height)
                    }
                    gray.copyTo(previousGray)
                    if(frame%5==0||frame==frames-1)onPhase("Localizando uma pessoa no celular: ${(frame+1)*100/frames}%")
                }finally{rgba.release();rgb.release();small.release();gray.release();rects.release();weights.release()}
            }
            android.util.Log.i("PocketPoseDetector",
                "backend="+backend+" frames="+frames+" detectorFrames="+detected+
                    " boxes="+output.size+" coveragePercent="+(output.size*100/frames)+
                    " elapsedMs="+(android.os.SystemClock.elapsedRealtime()-startedAt))
            require(output.isNotEmpty()) { "Nenhuma pessoa detectada. Use um vídeo com o corpo inteiro visível e bem iluminado." }
            onPhase("Verificando rastreamento: 100%")
            return output to detected
        }finally{mlkit?.close();retriever.release();previousGray.release()}
    }
}
