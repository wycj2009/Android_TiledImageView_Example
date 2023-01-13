package com.example.android_tiledimageview_example

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.minus
import androidx.core.graphics.values
import androidx.core.view.doOnLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class TiledImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : View(context, attrs, defStyleAttr, defStyleRes) {
    var debuggingCallback: ((maxResolutionLv: Int, curResolutionLv: Int, activeTilesSize: Int, bitmapAllocatedMemorySizeMb: Long) -> Unit)? = null
    var sourceImageWidth: Int = 0
        private set
    var sourceImageHeight: Int = 0
        private set
    var imageTranslationX: Float
        get() = imageMatrix.values()[Matrix.MTRANS_X]
        set(value) {
            imageMatrix.postTranslate(value - imageTranslationX, 0f)
            invalidate()
        }
    var imageTranslationY: Float
        get() = imageMatrix.values()[Matrix.MTRANS_Y]
        set(value) {
            imageMatrix.postTranslate(0f, value - imageTranslationY)
            invalidate()
        }
    var imageScale: Float
        get() = imageMatrix.values().let { v -> sqrt(v[Matrix.MSCALE_X] * v[Matrix.MSCALE_X] + v[Matrix.MSKEW_Y] * v[Matrix.MSKEW_Y]) }
        set(value) {
            val ds = value / imageScale
            imageMatrix.postScale(ds, ds, imageTranslationX, imageTranslationY)
            invalidate()
        }
    var imageMinScale: Float = 1f
    var imageMaxScale: Float = 1f
    var imageRotation: Float
        get() = imageMatrix.values().let { v -> -(atan2(v[Matrix.MSKEW_X], v[Matrix.MSCALE_X]) * (180.0 / PI)).toFloat() }
        set(value) {
            imageMatrix.postRotate(value - imageRotation, imageTranslationX, imageTranslationY)
            invalidate()
        }
    var scaleType: ScaleType = ScaleType.FIT_INSIDE
    val imageMatrix: Matrix = Matrix()
    var touchBehavior: TouchBehavior = DefaultTouchBehavior()
    private var imageUri: Uri? = null
    private var tilingHelper: TilingHelper = TilingHelper()

    override fun onDraw(canvas: Canvas) {
        tilingHelper.drawTiles(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return touchBehavior.invoke(event)
    }

    fun setImage(resId: Int) {
        val uri = Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${resId}")
        setImage(uri)
    }

    fun setImage(uri: Uri) {
        val bitmapRegionDecoder = context.contentResolver.openInputStream(uri).use {
            if (it == null) return
            BitmapRegionDecoder.newInstance(it, false) ?: return
        }
        imageUri = uri
        sourceImageWidth = bitmapRegionDecoder.width
        sourceImageHeight = bitmapRegionDecoder.height
        imageMatrix.reset()

        doOnLayout { view: View ->
            // TODO : ScaleType 추가
            val initImageScale = when (scaleType) {
                ScaleType.FIT_INSIDE -> {
                    (view.width.toFloat() / sourceImageWidth).let {
                        if (sourceImageHeight * it > view.height) view.height.toFloat() / sourceImageHeight
                        else it
                    }
                }
                ScaleType.FIT_HORIZONTAL -> {
                    view.width.toFloat() / sourceImageWidth
                }
                ScaleType.FIT_VERTICAL -> {
                    view.height.toFloat() / sourceImageHeight
                }
            }
            imageMatrix.postScale(initImageScale, initImageScale)
            imageMinScale = initImageScale / 2f
            imageMaxScale = 2f
            touchBehavior.let {
                if (it is DefaultTouchBehavior) {
                    it.setInitImageCenter(PointF(sourceImageWidth * initImageScale / 2f, sourceImageHeight * initImageScale / 2f))
                }
            }
            tilingHelper.init()
        }
    }

    enum class ScaleType {
        FIT_INSIDE,
        FIT_HORIZONTAL,
        FIT_VERTICAL
    }

    // TODO : 플링 모션 추가
    /** Default behavior of touches on [TiledImageView]. */
    private inner class DefaultTouchBehavior : TouchBehavior() {
        private val touchCenter: PointF = PointF()
        private var touchDistance: Float = 0f
        private var touchAngle: Float = 0f
        private val initImageCenter: PointF = PointF()

        fun setInitImageCenter(center: PointF) {
            initImageCenter.set(center)
        }

        override fun invoke(event: MotionEvent): Boolean {
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    touchCenter.set(getTouchCenter(event))
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    touchCenter.set(getTouchCenter(event))
                    touchDistance = getTouchDistance(event)
                    touchAngle = getTouchAngle(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    // For panning
                    val newTouchCenter = PointF(getTouchCenter(event))
                    if (isPanningEnabled) {
                        (newTouchCenter - touchCenter).let {
                            imageMatrix.postTranslate(it.x, it.y)
                        }
                    }
                    touchCenter.set(newTouchCenter)

                    // For scaling
                    if (event.pointerCount >= 2) {
                        val newTouchDistance = getTouchDistance(event)
                        if (isScalingEnabled) {
                            (newTouchDistance / touchDistance).let {
                                val imageScale = this@TiledImageView.imageScale
                                val center = if (isPanningEnabled) touchCenter else initImageCenter
                                if ((it < 1f && imageScale > imageMinScale) || (it > 1f && imageScale < imageMaxScale)) {
                                    imageMatrix.postScale(it, it, center.x, center.y)
                                }
                            }
                        }
                        touchDistance = newTouchDistance
                    }

                    // For rotating
                    if (event.pointerCount >= 2) {
                        val newTouchAngle = getTouchAngle(event)
                        if (isRotatingEnabled) {
                            (newTouchAngle - touchAngle).let {
                                if (isPanningEnabled) {
                                    imageMatrix.postRotate(it, touchCenter.x, touchCenter.y)
                                } else {
                                    imageMatrix.postRotate(it, initImageCenter.x, initImageCenter.y)
                                }
                            }
                        }
                        touchAngle = newTouchAngle
                    }

                    invalidate()
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    touchCenter.set(getTouchCenter(event))
                    touchDistance = getTouchDistance(event)
                    touchAngle = getTouchAngle(event)
                }
                MotionEvent.ACTION_UP -> {
                    touchCenter.set(0f, 0f)
                }
            }
            return true
        }

        /** @return Center coordinate between touch coordinates of first and second index. */
        private fun getTouchCenter(e: MotionEvent): PointF {
            var size = 0
            var dx = 0f
            var dy = 0f
            for (i in 0 until e.pointerCount) {
                if (size >= 2) break
                if ((e.action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_UP && e.actionIndex == i) continue

                size++
                dx += e.getX(i)
                dy += e.getY(i)
            }
            return PointF(dx / size, dy / size)
        }

        /** @return Distance between touch coordinates of first and second index. */
        private fun getTouchDistance(e: MotionEvent): Float {
            return sqrt(
                (e.getX(0) - e.getX(1)).pow(2)
                    + (e.getY(0) - e.getY(1)).pow(2)
            )
        }

        /** @return Angle of the line connecting coordinates of first and second index. */
        private fun getTouchAngle(e: MotionEvent): Float {
            return (atan2(e.getY(0) - e.getY(1), e.getX(0) - e.getX(1)) * 180.0 / PI).toFloat()
        }
    }

    // TODO : isFlingEnabled 추가
    /** The behavior of touches on [TiledImageView]. */
    abstract class TouchBehavior {
        var isPanningEnabled: Boolean = true
        var isScalingEnabled: Boolean = true
        var isRotatingEnabled: Boolean = true

        abstract fun invoke(event: MotionEvent): Boolean
    }

    private inner class TilingHelper {
        private var maxResolutionLv: Int = 0
        private val curResolutionLv: Int
            get() = max(0, min(log(imageScale, 0.5f).toInt(), maxResolutionLv))
        private var tiles: Array<Array<Tile>>? = null
        private val previewTile: Tile?
            get() = tiles?.get(maxResolutionLv)?.get(0)
        private val activeTiles: MutableSet<Tile> = mutableSetOf() // TODO : 다른 자료구조로 성능 개선
        private val tilePaint: Paint = Paint().apply { isAntiAlias = true }
        private val debuggingTilePaints: Array<Paint> = arrayOf(
            Paint().apply { isAntiAlias = true; colorFilter = PorterDuffColorFilter(Color.valueOf(1f, 0f, 0f, 0.5f).toArgb(), PorterDuff.Mode.LIGHTEN) },
            Paint().apply { isAntiAlias = true; colorFilter = PorterDuffColorFilter(Color.valueOf(0f, 1f, 0f, 0.5f).toArgb(), PorterDuff.Mode.LIGHTEN) },
            Paint().apply { isAntiAlias = true; colorFilter = PorterDuffColorFilter(Color.valueOf(0f, 0f, 1f, 0.5f).toArgb(), PorterDuff.Mode.LIGHTEN) }
        )

        fun init() {
            maxResolutionLv = 0
            tiles = null
            activeTiles.run {
                forEach { it.freeBitmap() }
                clear()
            }

            // Init maxResolutionLv
            var (resizedImageWidth: Int, resizedImageHeight: Int) = sourceImageWidth to sourceImageHeight
            while (resizedImageWidth > this@TiledImageView.width || resizedImageHeight > this@TiledImageView.height) {
                resizedImageWidth /= 2
                resizedImageHeight /= 2
                maxResolutionLv++
            }
            maxResolutionLv--

            // Init tiles
            tiles = Array(maxResolutionLv + 1) { resolutionLv ->
                val tileSideLength = 2f.pow(maxResolutionLv - resolutionLv).toInt()
                val (tileWidth: Int, tileHeight: Int) = sourceImageWidth / tileSideLength to sourceImageHeight / tileSideLength

                Array(tileSideLength * tileSideLength) { tileIdx ->
                    val xIdx = tileIdx % tileSideLength
                    val yIdx = tileIdx / tileSideLength

                    Tile(
                        resolutionLv = resolutionLv,
                        index = tileIdx,
                        rect = Rect(
                            tileWidth * xIdx,
                            tileHeight * yIdx,
                            if (xIdx < tileSideLength - 1) tileWidth * (xIdx + 1) else sourceImageWidth, // 픽셀 유실 방지.
                            if (yIdx < tileSideLength - 1) tileHeight * (yIdx + 1) else sourceImageHeight // 픽셀 유실 방지.
                        )
                    )
                }
            }

            // Decode bitmap of preview tile
            previewTile?.decodeBitmap(this@TiledImageView, imageUri!!)
        }

        fun drawTiles(canvas: Canvas) {
            val viewportRect: RectF = getViewportRect()

            // activeTiles 중 tile.resolutionLv == curResolutionLv 인데 뷰포트와 겹치지 않는 타일들의 비트맵 해제
            activeTiles.filter { tile: Tile ->
                tile.resolutionLv == curResolutionLv && !isTileOverlappedWithViewport(tile, viewportRect)
            }.forEach { tile: Tile ->
                tile.freeBitmap()
                activeTiles.remove(tile)
            }

            // activeTiles 중 tile.resolutionLv != curResolutionLv 인데 디코딩 중인 타일들의 비트맵 해제
            activeTiles.filter { tile: Tile ->
                tile.resolutionLv != curResolutionLv && tile.state == Tile.State.DECODING
            }.forEach { tile: Tile ->
                tile.freeBitmap()
                activeTiles.remove(tile)
            }

            // 현재 뷰포트와 겹치고 Tile.State.FREE 상태인 타일들은 디코딩 시작
            getTilesOverlappedWithViewport(viewportRect).filter { tile: Tile ->
                tile.state == Tile.State.FREE
            }.forEach { tile: Tile ->
                tile.decodeBitmap(this@TiledImageView, imageUri!!)
                activeTiles.add(tile)
            }

            // 뷰포트와 겹치는 모든 타일들이 디코딩 되었다면, activeTiles 중 뷰포트와 겹치지 않는 타일들의 비트맵 해제
            getTilesOverlappedWithViewport(viewportRect).all {
                it.state == Tile.State.DECODED
            }.let { allTilesOverappedWithViewportDecoded: Boolean ->
                if (allTilesOverappedWithViewportDecoded) {
                    activeTiles.filter {
                        !isTileOverlappedWithViewport(it, viewportRect)
                    }.forEach {
                        it.freeBitmap()
                        activeTiles.remove(it)
                    }
                }
            }

            // Draw preview tile
            previewTile?.let { tile: Tile ->
                tile.drawBitmap(
                    imageMatrix,
                    canvas,
                    tilePaint
                )
            }

            // Draw active tiles
            activeTiles.sortedByDescending {
                it.resolutionLv
            }.forEach { tile: Tile ->
                tile.drawBitmap(
                    imageMatrix,
                    canvas,
                    if (debuggingCallback == null) tilePaint else debuggingTilePaints[tile.index % 3]
                )
            }

            // For debugging
            debuggingCallback?.invoke(
                maxResolutionLv,
                curResolutionLv,
                1 + activeTiles.size,
                ((previewTile?.getBitmapAllocationByteCount()?.toLong() ?: 0L) + activeTiles.sumOf { it.getBitmapAllocationByteCount().toLong() }) / 1024L / 1024L
            )
        }

        private fun getViewportRect(): RectF {
            val imageScale = this@TiledImageView.imageScale
            val imageRotation = this@TiledImageView.imageRotation
            val imageMatrixValues = this@TiledImageView.imageMatrix.values()

            val p1 = PointF(0f, 0f)
            val p2 = PointF(width / imageScale, 0f)
            val p3 = PointF(0f, height / imageScale)
            val p4 = PointF(p2.x, p3.y)

            val (axis: PointF, angle: Float) = PointF(0f, 0f) to -imageRotation
            p1.rotate(axis, angle)
            p2.rotate(axis, angle)
            p3.rotate(axis, angle)
            p4.rotate(axis, angle)

            // TODO : 개선
            val (dx: Float, dy: Float) = PointF(-(imageMatrixValues[Matrix.MTRANS_X] / imageScale), -(imageMatrixValues[Matrix.MTRANS_Y] / imageScale)).let {
                it.rotate(PointF(0f, 0f), -imageRotation)
                it.x to it.y
            }
            p1.offset(dx, dy)
            p2.offset(dx, dy)
            p3.offset(dx, dy)
            p4.offset(dx, dy)

            // TODO : 개선
            return RectF(
                minOf(p1.x, p2.x, p3.x, p4.x),
                minOf(p1.y, p2.y, p3.y, p4.y),
                maxOf(p1.x, p2.x, p3.x, p4.x),
                maxOf(p1.y, p2.y, p3.y, p4.y)
            )
        }

        private fun isTileOverlappedWithViewport(tile: Tile, viewportRect: RectF): Boolean {
            return if (tile.resolutionLv != curResolutionLv) {
                false
            } else {
                !(tile.rect.left > viewportRect.right || tile.rect.right < viewportRect.left || tile.rect.top > viewportRect.bottom || tile.rect.bottom < viewportRect.top)
            }
        }

        private fun getTilesOverlappedWithViewport(viewportRect: RectF): List<Tile> {
            return if (curResolutionLv == maxResolutionLv) {
                emptyList()
            } else {
                tiles?.get(curResolutionLv)?.filter { tile: Tile ->
                    isTileOverlappedWithViewport(tile, viewportRect)
                } ?: emptyList()
            }
        }
    }

    /**
     * It has [bitmap] corresponding to [rect] based on the source image size.
     * [bitmap] can be set or free or drawn as needed.
     *
     * @property index Index of tiles in same resolution level.
     * @property rect The area to decode based on the source image size.
     * @property sampleSize It need to do subsampling based on viewport size.
     * @property bitmap A bitmap decoded by the required area based on the source image.
     */
    private class Tile(
        val resolutionLv: Int,
        val index: Int,
        val rect: Rect
    ) {
        var state: State = State.FREE
        private val sampleSize: Int = 2f.pow(resolutionLv).toInt()
        private var bitmap: Bitmap? = null
        private var decodingJob: Job? = null
        private var bitmapRegionDecoder: BitmapRegionDecoder? = null

        fun freeBitmap() {
            decodingJob?.cancel()
            decodingJob = null
            bitmap?.recycle()
            bitmap = null

            state = State.FREE
        }

        fun decodeBitmap(tiledImageView: TiledImageView, imageUri: Uri) {
            if (decodingJob?.isActive == true) return

            // TODO : test
            decodingJob = CoroutineScope(Dispatchers.Default).launch {
                state = State.DECODING

                if (bitmapRegionDecoder == null) {
                    bitmapRegionDecoder = tiledImageView.context.contentResolver.openInputStream(imageUri).use {
                        it?.let {
                            BitmapRegionDecoder.newInstance(it, false)
                        }
                    }
                }

                bitmapRegionDecoder?.let { decoder: BitmapRegionDecoder ->
                    val decodedBitmap = decoder.decodeRegion(
                        rect,
                        BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                        }
                    )

                    if (state == State.DECODING) {
                        bitmap = decodedBitmap
                        state = State.DECODED
                        tiledImageView.invalidate()
                    }
                } ?: run {
                    freeBitmap()
                }
            }
        }

        fun getBitmapAllocationByteCount(): Int {
            return bitmap?.allocationByteCount ?: 0
        }

        fun drawBitmap(imageMatrix: Matrix, canvas: Canvas, paint: Paint? = null) {
            val tileBitmap: Bitmap = this.bitmap ?: return
            val tileMatrix: Matrix = Matrix(imageMatrix).also { matrix: Matrix ->
                val imageMatrixValues: FloatArray = imageMatrix.values()
                val imageScale: Float = sqrt(imageMatrixValues[Matrix.MSCALE_X] * imageMatrixValues[Matrix.MSCALE_X] + imageMatrixValues[Matrix.MSKEW_Y] * imageMatrixValues[Matrix.MSKEW_Y])
                val imageRotation: Float = -(atan2(imageMatrixValues[Matrix.MSKEW_X], imageMatrixValues[Matrix.MSCALE_X]) * (180.0 / PI)).toFloat()

                val sampleSize: Float = sampleSize.toFloat()
                matrix.postScale(sampleSize, sampleSize)

                val matrixValues: FloatArray = matrix.values().also {
                    it[2] = imageMatrixValues[2]
                    it[5] = imageMatrixValues[5]
                }
                matrix.setValues(matrixValues)

                val (dx: Float, dy: Float) = PointF(rect.left * imageScale, rect.top * imageScale).apply {
                    rotate(PointF(0f, 0f), imageRotation)
                }.let {
                    it.x to it.y
                }
                matrix.postTranslate(dx, dy)
            }

            canvas.drawBitmap(tileBitmap, tileMatrix, paint)
        }

        enum class State {
            FREE, DECODING, DECODED
        }
    }

    private companion object {
        /** Set coordinate rotated by [angle] with [axis]. */
        private fun PointF.rotate(axis: PointF, angle: Float) {
            val r = (angle * PI / 180.0).toFloat()
            val sinR = sin(r)
            val cosR = cos(r)
            set(((x - axis.x) * cosR) - ((y - axis.y) * sinR) + axis.x, ((x - axis.x) * sinR) + ((y - axis.y) * cosR) + axis.y)
        }
    }
}
