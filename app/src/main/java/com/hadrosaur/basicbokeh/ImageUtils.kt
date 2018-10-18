package com.hadrosaur.basicbokeh

import android.app.Activity
import android.content.res.Resources
import android.graphics.*
import android.media.Image
import android.media.ImageReader
import android.os.Environment
import android.util.Log
import android.widget.ImageView
import com.hadrosaur.basicbokeh.MainActivity.Companion.GAUSSIAN_BLUR_RADIUS
import com.hadrosaur.basicbokeh.MainActivity.Companion.LOG_TAG
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import android.provider.MediaStore
import android.opengl.ETC1.getWidth
import android.opengl.ETC1.getHeight
import androidx.renderscript.Allocation
import androidx.renderscript.Element
import androidx.renderscript.RenderScript
import androidx.renderscript.ScriptIntrinsicBlur
import com.hadrosaur.basicbokeh.MainActivity.Companion.Logd
import android.R.attr.top
import android.R.attr.left
import android.graphics.Bitmap
import android.R.attr.bottom
import android.R.attr.right
import android.content.Context
import android.content.Intent
import com.hadrosaur.basicbokeh.MainActivity.Companion.BLUR_SCALE_FACTOR
import android.graphics.Shader
import android.graphics.LinearGradient
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.widget.Toast
import com.hadrosaur.basicbokeh.MainActivity.Companion.twoLens
import org.opencv.android.BaseLoaderCallback
import org.opencv.android.LoaderCallbackInterface
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat


class ImageAvailableListener(private val activity: MainActivity, internal var params: CameraParams) : ImageReader.OnImageAvailableListener {

    override fun onImageAvailable(reader: ImageReader) {
        // Orientation
        val rotation = activity.getWindowManager().getDefaultDisplay().getRotation()
        val capturedImageRotation = getOrientation(params, rotation)

        Log.d(MainActivity.LOG_TAG, "ImageReader. Image is available, about to post.")
        val image: Image = reader.acquireNextImage()

        //It might be that we received the image first and we're still waiting for the face calculations
        if (MainActivity.twoLens.isTwoLensShot) {
            Logd("Image Received, dual lens shot.")

            if (MainActivity.wideAngleId == params.id) {
                twoLens.wideImage = image

            } else if (MainActivity.normalLensId == params.id) {
                twoLens.normalImage = image
            }

            if (twoLens.wideShotDone && twoLens.normalShotDone
                    && null != twoLens.wideImage
                    && null != twoLens.normalImage) {

                val finalBitmap: Bitmap = DoBokeh(activity, twoLens)
                setCapturedPhoto(activity, params.capturedPhoto, finalBitmap)

                twoLens.normalImage?.close()
                twoLens.wideImage?.close()
            }

        } else {
            Logd("Image Received, NOT a dual lens shot.")
            //Only process wideAngle for now
            if (MainActivity.wideAngleId == params.id) {
                params.backgroundHandler?.post(ImageSaver(activity, image, params.capturedPhoto, capturedImageRotation, params.isFront, params))
            } else {
                image.close()
            }
        }

//        Log.d(MainActivity.LOG_TAG, "ImageReader. Post has been set.")
    }
}

class ImageSaver internal constructor(private val activity: MainActivity, private val image: Image?, private val imageView: ImageView?, private val rotation: Int, private val flip: Boolean, private val cameraParams: CameraParams) : Runnable {

    override fun run() {
        Logd( "ImageSaver. ImageSaver is running.")

        if (null == image)
            return

        Logd( "ImageSaver. Image is not null.")

        val file = File(Environment.getExternalStorageDirectory(), MainActivity.SAVE_FILE)

        Logd( "ImageSaver. Got file handle.")


        val buffer = image.planes[0].buffer

        Logd( "ImageSaver. Got image buffer planes")

        val bytes = ByteArray(buffer.remaining())

        Logd( "ImageSaver. image got the buffer remaining")

        buffer.get(bytes)

        Logd( "Got the photo, converted to bytes. About to set image view.")

        val wasFaceDetected: Boolean =
            CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF != cameraParams.bestFaceDetectionMode
                && cameraParams.hasFace


        //2 ideas
        //1. Single lens: cut out head, paste it on blurred/sepia'd background
        //2. Dual lens: generate depth map (but focal lenghts are different...)
        //See what happens if we try to combine things.

        //1. Single lens: cut out head, paste it on blurred/sepia'd background
        val backgroundImageBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, null)
        val foregroundImageBitmap = backgroundImageBitmap.copy(backgroundImageBitmap.config, true)

        //Foreground
        val croppedForeground = cropBitmap(activity, foregroundImageBitmap, cameraParams.faceBounds)
        val scaledForeground = scaleBitmap(activity, croppedForeground, MainActivity.BLUR_SCALE_FACTOR)
        val featheredForeground = featherBitmap(activity, scaledForeground, 0.15f)

        val scaledBackground = scaleBitmap(activity, backgroundImageBitmap, MainActivity.BLUR_SCALE_FACTOR)
        val sepiaBackground = sepiaFilter(activity, scaledBackground)
        val blurredBackground = gaussianBlur(activity, sepiaBackground, MainActivity.GAUSSIAN_BLUR_RADIUS)

        if (wasFaceDetected) {
            val combinedBitmap = pasteBitmap(activity, blurredBackground, featheredForeground, cameraParams.faceBounds)
            val rotatedImageBitmap = rotateBitmap(combinedBitmap, rotation.toFloat())

            var finalBitmap = rotatedImageBitmap

            //If front facing camera, flip the bitmap
            if (cameraParams.isFront)
                finalBitmap = horizontalFlip(finalBitmap)

            //Set the image view to be the final
            setCapturedPhoto(activity, imageView, finalBitmap)

        } else {
            Logd("No face detected.")
            val rotatedImageBitmap = rotateBitmap(blurredBackground, rotation.toFloat())
            var finalBitmap = rotatedImageBitmap

            //If front facing camera, flip the bitmap
            if (cameraParams.isFront)
                finalBitmap = horizontalFlip(finalBitmap)

            //Set the image view to be the final
            setCapturedPhoto(activity, imageView, finalBitmap)
        }



        //2. Dual lens: generate depth map (but focal lenghts are different...)
        //See what happens if we try to combine things.


        Logd( "Now saving photo to disk.")

        var output: FileOutputStream? = null
        try {
            output = FileOutputStream(file)
            output.write(bytes)
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            image.close()
            if (null != output) {
                try {
                    output.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }

            }
        }

        //Reset the image reader
//        setupImageReader(activity, cameraParams)
    }

}

fun rotateBitmap(original: Bitmap, degrees: Float): Bitmap {
    /*        int width = original.getWidth();
    int height = original.getHeight();

    Matrix matrix = new Matrix();
    matrix.preRotate(degrees);

    Bitmap rotatedBitmap = Bitmap.createBitmap(original, 0, 0, width, height, matrix, true);
    Canvas canvas = new Canvas(rotatedBitmap);
    canvas.drawBitmap(original, 5.0f, 0.0f, null);

    return rotatedBitmap;
    */
    val matrix = Matrix()
    matrix.postRotate(degrees)
    return Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
}

fun setCapturedPhoto(activity: Activity, imageView: ImageView?, bitmap: Bitmap) {
    activity.runOnUiThread { imageView?.setImageBitmap(bitmap) }
}

fun scaleBitmap(activity: Activity, bitmap: Bitmap, scaleFactor: Float): Bitmap {
    val scaledWidth = Math.round(bitmap.width * scaleFactor)
    val scaledHeight = Math.round(bitmap.height * scaleFactor)

    return Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
}

fun gaussianBlur(activity: Activity, bitmap: Bitmap, blurRadius: Float): Bitmap {
    val rs = RenderScript.create(activity)
    val allocation = Allocation.createFromBitmap(rs, bitmap)
    val allocationType = allocation.type
    val blurredAllocation = Allocation.createTyped(rs, allocationType)
    val blurredBitmap = bitmap

    val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
    script.setRadius(blurRadius)
    script.setInput(allocation)

    //Do the blur
    script.forEach(blurredAllocation)
    blurredAllocation.copyTo(blurredBitmap)

    allocation.destroy()
    blurredAllocation.destroy()
    script.destroy()
    allocationType.destroy()
    rs.destroy()
    return blurredBitmap
}

fun sepiaFilter(activity: Activity, bitmap: Bitmap): Bitmap {
    val rs = RenderScript.create(activity)
    val allocation = Allocation.createFromBitmap(rs, bitmap, Allocation.MipmapControl.MIPMAP_NONE,
            Allocation.USAGE_SCRIPT)
    val allocationType = allocation.type
    val sepiaAllocation = Allocation.createTyped(rs, allocationType)
    val sepiaBitmap = bitmap
    val script = ScriptC_sepia(rs)

    script.set_allocationIn(allocation)
    script.set_allocationOut(sepiaAllocation);
    script.set_script(script);
    script.invoke_filter();
    sepiaAllocation.copyTo(sepiaBitmap);

    return sepiaBitmap
}

fun drawBox(activity: Activity, cameraParams: CameraParams, bitmap: Bitmap): Bitmap {
    val bitmapBoxed = bitmap.copy(Bitmap.Config.ARGB_8888, true);
    val canvas = Canvas(bitmapBoxed)
    val paint = Paint()
    paint.setColor(Color.GREEN)
    canvas.drawBitmap(bitmap, 0f, 0f, null)
    canvas.drawRect(cameraParams.faceBounds, paint)
    return bitmapBoxed
}

fun horizontalFlip(bitmap: Bitmap) : Bitmap {
    val matrix = Matrix()
    matrix.preScale(-1.0f, 1.0f)
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

fun cropMat(mat: Mat, cropFactor: Float) : Mat {
    val cropRect: Rect = Rect(0, 0, mat.cols(), mat.rows())
    Logd("In cropMat.  Left: " + cropRect.left + " Right: " + cropRect.right + " Top: " + cropRect.top + " Bottom: " + cropRect.bottom)
    cropRect.inset(mat.cols() - (cropFactor * mat.cols()).toInt(), mat.rows() - (cropFactor * mat.rows()).toInt())

    Logd("In cropMat after inset.  Left: " + cropRect.left + " Right: " + cropRect.right + " Top: " + cropRect.top + " Bottom: " + cropRect.bottom)

    val cvRect: org.opencv.core.Rect = org.opencv.core.Rect(cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
    val croppedMat: Mat = Mat(mat, cvRect);

    return croppedMat
}

fun cropBitmap(activity: Activity, bitmap: Bitmap, cropFactor: Float) : Bitmap {
    val cropRect: Rect = Rect(0, 0, bitmap.width, bitmap.height)
    cropRect.inset((cropFactor * bitmap.width).toInt(), (cropFactor * bitmap.height).toInt())
    return cropBitmap(activity, bitmap, cropRect)
}

fun cropBitmap(activity: Activity, bitmap: Bitmap, rect: Rect) : Bitmap {
    if (!(rect.left < rect.right && rect.top < rect.bottom)) {
        Logd("In cropBitmap. Rect bounds incorrect, skipping crop. Left: " + rect.left + " Right: " + rect.right + " Top: " + rect.top + " Bottom: " + rect.bottom)
        return bitmap
    }

    val croppedBitmap = Bitmap.createBitmap(rect.right - rect.left, rect.bottom - rect.top, Bitmap.Config.ARGB_8888)
    Canvas(croppedBitmap).drawBitmap(bitmap, 0f - rect.left, 0f -rect.top, null)

    return croppedBitmap
}

fun pasteBitmap(activity: Activity, background: Bitmap, foreground: Bitmap, rect: Rect) : Bitmap {
    val combinedBitmap = Bitmap.createBitmap(background.width, background.height, background.config)
    val canvas = Canvas(combinedBitmap)
    canvas.drawBitmap(background, Matrix(), null)
    canvas.drawBitmap(foreground, rect.left.toFloat() * BLUR_SCALE_FACTOR, rect.top.toFloat() * BLUR_SCALE_FACTOR, null)
    return combinedBitmap
}

fun featherBitmap(activity: Activity, bitmap: Bitmap, borderSize: Float = 0.1f) : Bitmap {
    val canvas = Canvas(bitmap)
    val framePaint = Paint()
    for (i in 0..3) {
        setFramePaint(framePaint, i, bitmap.width.toFloat(), bitmap.height.toFloat(), borderSize)
        canvas.drawPaint(framePaint)
    }

    return bitmap
}


//From https://stackoverflow.com/questions/14172085/draw-transparent-gradient-with-alpha-transparency-from-0-to-1
private fun setFramePaint(p: Paint, side: Int, width: Float, height: Float, borderSize: Float = 0.1f) {

    p.shader = null
    p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)

    //use the smaller image size to calculate the actual border size
    val bSize = if (width > height) height * borderSize else width * borderSize

    var g1x = 0f
    var g1y = 0f
    var g2x = 0f
    var g2y = 0f
    var c1 = 0
    var c2 = 0

    if (side == 0) {
        //left
        g1x = 0f
        g1y = width / 2
        g2x = bSize
        g2y = height / 2
        c1 = Color.TRANSPARENT
        c2 = Color.BLACK

    } else if (side == 1) {
        //top
        g1x = width / 2
        g1y = 0f
        g2x = height / 2
        g2y = bSize
        c1 = Color.TRANSPARENT
        c2 = Color.BLACK


    } else if (side == 2) {
        //right
        g1x = width
        g1y = height / 2
        g2x = width - bSize
        g2y = height / 2
        c1 = Color.TRANSPARENT
        c2 = Color.BLACK


    } else if (side == 3) {
        //bottom
        g1x = width / 2
        g1y = height
        g2x = width / 2
        g2y = height - bSize
        c1 = Color.TRANSPARENT
        c2 = Color.BLACK
    }

    p.shader = LinearGradient(g1x, g1y, g2x, g2y, c1, c2, Shader.TileMode.CLAMP)
}

class OpenCVLoaderCallback(val context: Context) : BaseLoaderCallback(context) {
    override fun onManagerConnected(status: Int) {
        when (status) {
            LoaderCallbackInterface.SUCCESS -> {
                Logd("OpenCV loaded successfully")
            }
            else -> {
                super.onManagerConnected(status);
            }
        }
    }
}

fun WriteFile(activity: MainActivity, bitmap: Bitmap, name: String) {
    val PHOTOS_DIR: String = "BasicBokeh"

    val jpgFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            File.separatorChar + PHOTOS_DIR + File.separatorChar +
                    name + ".jpg")

    val photosDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), PHOTOS_DIR)

    if (!photosDir.exists()) {
        val createSuccess = photosDir.mkdir()
        if (!createSuccess) {
            Toast.makeText(activity, "DCIM/" + PHOTOS_DIR + " creation failed.", Toast.LENGTH_SHORT).show()
            Logd("Photo storage directory DCIM/" + PHOTOS_DIR + " creation failed!!")
        } else {
            Logd("Photo storage directory DCIM/" + PHOTOS_DIR + " did not exist. Created.")
        }
    }

    var output: FileOutputStream? = null
    try {
        output = FileOutputStream(jpgFile)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
    } catch (e: IOException) {
        e.printStackTrace()
    } finally {
        if (null != output) {
            try {
                output.close()

                //File is written, let media scanner know
                val scannerIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                scannerIntent.data = Uri.fromFile(jpgFile)
                activity.sendBroadcast(scannerIntent)

            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    Logd("WriteFile: Completed.")
}

fun WriteFile(activity: MainActivity, bytes: ByteArray, name: String) {
    val PHOTOS_DIR: String = "BasicBokeh"

    val jpgFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            File.separatorChar + PHOTOS_DIR + File.separatorChar +
                    name + ".jpg")

    val photosDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), PHOTOS_DIR)

    if (!photosDir.exists()) {
        val createSuccess = photosDir.mkdir()
        if (!createSuccess) {
            Toast.makeText(activity, "DCIM/" + PHOTOS_DIR + " creation failed.", Toast.LENGTH_SHORT).show()
            Logd("Photo storage directory DCIM/" + PHOTOS_DIR + " creation failed!!")
        } else {
            Logd("Photo storage directory DCIM/" + PHOTOS_DIR + " did not exist. Created.")
        }
    }

    var output: FileOutputStream? = null
    try {
        output = FileOutputStream(jpgFile)
        output.write(bytes)
    } catch (e: IOException) {
        e.printStackTrace()
    } finally {
        if (null != output) {
            try {
                output.close()

                    //File is written, let media scanner know
                    val scannerIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                    scannerIntent.data = Uri.fromFile(jpgFile)
                    activity.sendBroadcast(scannerIntent)

            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    Logd("WriteFile: Completed.")
}
