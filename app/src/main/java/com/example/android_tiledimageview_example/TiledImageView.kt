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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    var debuggingCallback: ((topTileLevel: Int, curTileLevel: Int, topTileSampleSize: Int, curTilesSampleSize: Int, activeTilesSize: Int, bitmapAllocatedMemorySizeKb: Long) -> Unit)? = null
    var sourceImageWidth: Int = 0
        private set
    var sourceImageHeight: Int = 0
        private set
    val imageMatrix: Matrix = Matrix()
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
    var imageMinScale: Float? = null
    var imageMaxScale: Float? = null
    var imageRotation: Float
        get() = imageMatrix.values().let { v -> -(atan2(v[Matrix.MSKEW_X], v[Matrix.MSCALE_X]) * (180.0 / PI)).toFloat() }
        set(value) {
            imageMatrix.postRotate(value - imageRotation, imageTranslationX, imageTranslationY)
            invalidate()
        }
    var scaleType: ScaleType = ScaleType.FIT_INSIDE
    var viewportRectView: ViewportRectView = ViewportRectView.ITSELF
    var touchBehavior: TouchBehavior? = DefaultTouchBehavior()
    private var imageUri: Uri? = null
    private var tilingHelper: TilingHelper = TilingHelper()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentWidth: Int = MeasureSpec.getSize(widthMeasureSpec)
        val parentHeight: Int = MeasureSpec.getSize(heightMeasureSpec)
        val widthMeasureMode: Int = MeasureSpec.getMode(widthMeasureSpec)
        val heightMeasureMode: Int = MeasureSpec.getMode(heightMeasureSpec)
        var measuredWidth: Int = if (widthMeasureMode == MeasureSpec.EXACTLY) getDefaultSize(suggestedMinimumWidth, widthMeasureSpec) else 0
        var measuredHeight: Int = if (heightMeasureMode == MeasureSpec.EXACTLY) getDefaultSize(suggestedMinimumHeight, heightMeasureSpec) else 0
        when (scaleType) {
            ScaleType.FIT_INSIDE -> {
                if (widthMeasureMode != MeasureSpec.EXACTLY && heightMeasureMode == MeasureSpec.EXACTLY) {
                    measuredWidth = (sourceImageWidth.toFloat() / sourceImageHeight * measuredHeight).toInt()
                } else if (widthMeasureMode == MeasureSpec.EXACTLY && heightMeasureMode != MeasureSpec.EXACTLY) {
                    measuredHeight = (sourceImageHeight.toFloat() / sourceImageWidth * measuredWidth).toInt()
                } else if (widthMeasureMode != MeasureSpec.EXACTLY && heightMeasureMode != MeasureSpec.EXACTLY) {
                    (parentWidth.toFloat() / sourceImageWidth).let {
                        if (sourceImageHeight * it > parentHeight) {
                            measuredHeight = parentHeight
                            measuredWidth = (sourceImageWidth.toFloat() / sourceImageHeight * measuredHeight).toInt()
                        } else {
                            measuredWidth = parentWidth
                            measuredHeight = (sourceImageHeight.toFloat() / sourceImageWidth * measuredWidth).toInt()
                        }
                    }
                }
            }
            ScaleType.FIT_HORIZONTAL -> {
                if (widthMeasureMode != MeasureSpec.EXACTLY) {
                    measuredWidth = min(parentWidth, sourceImageWidth)
                }
                if (heightMeasureMode != MeasureSpec.EXACTLY) {
                    measuredHeight = (sourceImageHeight.toFloat() / sourceImageWidth * measuredWidth).toInt()
                }
            }
            ScaleType.FIT_VERTICAL -> {
                if (heightMeasureMode != MeasureSpec.EXACTLY) {
                    measuredHeight = min(parentHeight, sourceImageHeight)
                }
                if (widthMeasureMode != MeasureSpec.EXACTLY) {
                    measuredWidth = (sourceImageWidth.toFloat() / sourceImageHeight * measuredHeight).toInt()
                }
            }
        }

        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val initImageScale = when (scaleType) {
            ScaleType.FIT_INSIDE -> {
                (width.toFloat() / sourceImageWidth).let {
                    if (sourceImageHeight * it > height) height.toFloat() / sourceImageHeight
                    else it
                }
            }
            ScaleType.FIT_HORIZONTAL -> {
                width.toFloat() / sourceImageWidth
            }
            ScaleType.FIT_VERTICAL -> {
                height.toFloat() / sourceImageHeight
            }
        }
        imageMatrix.run {
            reset()
            postScale(initImageScale, initImageScale)
            postTranslate((width - (sourceImageWidth * initImageScale)) / 2f, (height - (sourceImageHeight * initImageScale)) / 2f)
        }
        if (imageMinScale == null) imageMinScale = initImageScale / 2f
        if (imageMaxScale == null) imageMaxScale = 2f
    }

    override fun onDraw(canvas: Canvas) {
        tilingHelper.drawTiles(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return touchBehavior?.invoke(event) ?: return false
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
        tilingHelper.init()
        requestLayout()
    }

    fun removeImage() {
        imageUri = null
        sourceImageWidth = 0
        sourceImageHeight = 0
        imageMatrix.reset()
        tilingHelper.init()
        requestLayout()
        invalidate()
    }

    enum class ScaleType {
        FIT_INSIDE,
        FIT_HORIZONTAL,
        FIT_VERTICAL
    }

    enum class ViewportRectView {
        ITSELF,
        PARENT_VIEW
    }

    /** The behavior of touches on [TiledImageView]. */
    abstract class TouchBehavior {
        var isPanningEnabled: Boolean = true
        var isScalingEnabled: Boolean = true
        var isRotatingEnabled: Boolean = true

        abstract fun invoke(event: MotionEvent): Boolean
    }

    /** Default behavior of touches on [TiledImageView]. */
    private inner class DefaultTouchBehavior : TouchBehavior() {
        private val touchCenter: PointF = PointF()
        private var touchDistance: Float = 0f
        private var touchAngle: Float = 0f

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
                    val newTouchCenter = getTouchCenter(event)
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
                                val center = if (isPanningEnabled) touchCenter else PointF(width / 2f, height / 2f)
                                val imageScale = this@TiledImageView.imageScale
                                if ((it < 1f && imageScale > imageMinScale!!) || (it > 1f && imageScale < imageMaxScale!!)) {
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
                                    imageMatrix.postRotate(it, width / 2f, height / 2f)
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

    private inner class TilingHelper {
        private var topTileLevel: Int = 0
        private val curTileLevel: Int
            get() = max(0, min(log(imageScale, 0.5f).toInt(), topTileLevel))
        private val curTilesSampleSize: Int
            get() = 2f.pow(max(0, log(imageScale, 0.5f).toInt())).toInt()
        private var tiles: Array<Array<Tile>>? = null
        private val topTile: Tile?
            get() = tiles?.get(topTileLevel)?.get(0)
        private val activeTiles: MutableSet<Tile> = mutableSetOf()
        private val tilePaint: Paint = Paint().apply { isAntiAlias = true }
        private val debuggingTilePaints: Array<Paint> = arrayOf(
            Paint().apply { isAntiAlias = true; colorFilter = PorterDuffColorFilter(Color.valueOf(1f, 0f, 0f, 0.5f).toArgb(), PorterDuff.Mode.LIGHTEN) },
            Paint().apply { isAntiAlias = true; colorFilter = PorterDuffColorFilter(Color.valueOf(0f, 1f, 0f, 0.5f).toArgb(), PorterDuff.Mode.LIGHTEN) },
            Paint().apply { isAntiAlias = true; colorFilter = PorterDuffColorFilter(Color.valueOf(0f, 0f, 1f, 0.5f).toArgb(), PorterDuff.Mode.LIGHTEN) }
        )

        fun init() {
            topTileLevel = 0
            tiles = null
            activeTiles.run {
                forEach { it.freeBitmap() }
                clear()
            }

            // Init topTileLevel (뷰의 크기는 가변적이고, 뷰의 크기가 변경될 때마다 모든 타일들을 초기화하는 건 비효율적이므로 고정 값인 기기 사이즈를 기준으로 topTileLevel 를 정한다.)
            val (displayWidth: Int, displayHeight: Int) = context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
            var (resizedImageWidth: Int, resizedImageHeight: Int) = sourceImageWidth to sourceImageHeight
            while (resizedImageWidth > displayWidth || resizedImageHeight > displayHeight) {
                resizedImageWidth /= 2
                resizedImageHeight /= 2
                topTileLevel++
            }
            topTileLevel--
            topTileLevel = max(0, min(2, topTileLevel)) // 모든 타일의 개수는 (4^topTileLevel - 1) / 3. 타일이 너무 많아지는 걸 방지하기 위해 topTileLevel 의 최댓값을 2로 준다.

            // Init tiles
            tiles = Array(topTileLevel + 1) { tileLevel ->
                val tileSideLength = 2f.pow(topTileLevel - tileLevel).toInt()
                val (tileWidth: Int, tileHeight: Int) = sourceImageWidth / tileSideLength to sourceImageHeight / tileSideLength

                Array(tileSideLength * tileSideLength) { tileIdx ->
                    val xIdx = tileIdx % tileSideLength
                    val yIdx = tileIdx / tileSideLength

                    Tile(
                        level = tileLevel,
                        index = tileIdx,
                        rect = Rect(
                            tileWidth * xIdx,
                            tileHeight * yIdx,
                            if (xIdx < tileSideLength - 1) tileWidth * (xIdx + 1) else sourceImageWidth, // 픽셀 유실 방지.
                            if (yIdx < tileSideLength - 1) tileHeight * (yIdx + 1) else sourceImageHeight // 픽셀 유실 방지.
                        ),
                        isTopTile = tileLevel == topTileLevel
                    )
                }
            }
        }

        fun drawTiles(canvas: Canvas) {
            val viewportRect: RectF = getViewportRect()
            val curTileLevel: Int = this.curTileLevel
            val tilesOverlappedWithViewport: List<Tile> = getTilesOverlappedWithViewport(viewportRect)
            val curTilesSampleSize: Int = this.curTilesSampleSize

            // activeTiles 중 tile.level == curTileLevel 인데 뷰포트와 겹치지 않는 타일들의 비트맵 해제
            activeTiles.filter { tile: Tile ->
                tile.level == curTileLevel && !isTileOverlappedWithViewport(tile, viewportRect)
            }.forEach { tile: Tile ->
                tile.freeBitmap()
                activeTiles.remove(tile)
            }

            // activeTiles 중 tile.level != curTileLevel 인데 디코딩 중인 타일들의 비트맵 해제
            activeTiles.filter { tile: Tile ->
                tile.level != curTileLevel && tile.state == TileState.DECODING
            }.forEach { tile: Tile ->
                tile.freeBitmap()
                activeTiles.remove(tile)
            }

            // 뷰포트와 겹치는 모든 타일들이 디코딩 되었다면, activeTiles 중 뷰포트와 겹치지 않는 타일들의 비트맵 해제
            tilesOverlappedWithViewport.all {
                it.state == TileState.DECODED
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

            // topTile 디코딩이 필요한 경우 디코딩 시작
            topTile?.let { tile: Tile ->
                if (tile.state == TileState.FREE || (curTileLevel == topTileLevel && tile.sampleSize != curTilesSampleSize)) {
                    tile.decodeBitmap()
                }
            }

            // 현재 뷰포트와 겹치고 Tile.State.FREE 상태인 타일들은 디코딩 시작
            tilesOverlappedWithViewport.filter { tile: Tile ->
                tile.state == TileState.FREE
            }.forEach { tile: Tile ->
                tile.decodeBitmap()
                activeTiles.add(tile)
            }

            // Draw top tile
            topTile?.let { tile: Tile ->
                tile.drawBitmap(
                    canvas,
                    tilePaint
                )
            }

            // Draw active tiles
            activeTiles.sortedByDescending {
                it.level
            }.forEach { tile: Tile ->
                tile.drawBitmap(
                    canvas,
                    if (debuggingCallback == null) tilePaint else debuggingTilePaints[tile.index % 3]
                )
            }

            // For debugging
            debuggingCallback?.invoke(
                topTileLevel,
                curTileLevel,
                topTile?.sampleSize ?: 0,
                curTilesSampleSize,
                1 + activeTiles.size,
                ((topTile?.getBitmapAllocationByteCount()?.toLong() ?: 0L) + activeTiles.sumOf { it.getBitmapAllocationByteCount().toLong() }) / 1024L
            )
        }

        /**
         * Get viewport rect.
         * Note that the viewport rect coordinate system is based on the bitmap coordinate system.
         *
         * For instance, there is a bitmap with width=100 and height=100. This bitmap rect will be left=0, top=0, right=100, bottom=100.
         * If this bitmap is centered on viewport rect with width=200 and height=200, the viewport rect will be left=-50, top=-50, right=150, bottom=150.
         */
        private fun getViewportRect(): RectF {
            val (translationX: Float, translationY: Float) = when (viewportRectView) {
                ViewportRectView.ITSELF -> {
                    this@TiledImageView.imageMatrix.values().let { it[Matrix.MTRANS_X] to it[Matrix.MTRANS_Y] }
                }
                ViewportRectView.PARENT_VIEW -> {
                    PointF(x, y).let {
                        it.rotate(PointF(it.x + (width / 2f), it.y + (height / 2f)), rotation)
                        it.x to it.y
                    }
                }
            }
            val scale: Float = this@TiledImageView.imageScale
            val rotation: Float = when (viewportRectView) {
                ViewportRectView.ITSELF -> imageRotation
                ViewportRectView.PARENT_VIEW -> rotation
            }
            val (viewportRectWidth: Int, viewportRectHeight: Int) = when (viewportRectView) {
                ViewportRectView.ITSELF -> width to height
                ViewportRectView.PARENT_VIEW -> (parent as View).width to (parent as View).height
            }

            val p1 = PointF(0f, 0f)
            val p2 = PointF(viewportRectWidth / scale, p1.y)
            val p3 = PointF(p1.x, viewportRectHeight / scale)
            val p4 = PointF(p2.x, p3.y)

            val axis = PointF(0f, 0f)
            val angle: Float = -rotation
            p1.rotate(axis, angle)
            p2.rotate(axis, angle)
            p3.rotate(axis, angle)
            p4.rotate(axis, angle)

            val (dx: Float, dy: Float) = PointF(-(translationX / scale), -(translationY / scale)).let {
                it.rotate(PointF(0f, 0f), -rotation)
                it.x to it.y
            }
            p1.offset(dx, dy)
            p2.offset(dx, dy)
            p3.offset(dx, dy)
            p4.offset(dx, dy)

            return RectF(
                minOf(p1.x, p2.x, p3.x, p4.x),
                minOf(p1.y, p2.y, p3.y, p4.y),
                maxOf(p1.x, p2.x, p3.x, p4.x),
                maxOf(p1.y, p2.y, p3.y, p4.y)
            )
        }

        /** Set coordinate rotated by [angle] with [axis]. */
        private fun PointF.rotate(axis: PointF, angle: Float) {
            val r = (angle * PI / 180.0).toFloat()
            val sinR = sin(r)
            val cosR = cos(r)
            set(((x - axis.x) * cosR) - ((y - axis.y) * sinR) + axis.x, ((x - axis.x) * sinR) + ((y - axis.y) * cosR) + axis.y)
        }

        /** @return True if [tile] is overlapped with [viewportRect]. Else false. */
        private fun isTileOverlappedWithViewport(tile: Tile, viewportRect: RectF): Boolean {
            return if (tile.level != curTileLevel) {
                false
            } else {
                !(tile.rect.left > viewportRect.right || tile.rect.right < viewportRect.left || tile.rect.top > viewportRect.bottom || tile.rect.bottom < viewportRect.top)
            }
        }

        /** @return Tiles overlapped with [viewportRect]. */
        private fun getTilesOverlappedWithViewport(viewportRect: RectF): List<Tile> {
            return if (curTileLevel == topTileLevel) {
                emptyList()
            } else {
                tiles?.get(curTileLevel)?.filter { tile: Tile ->
                    isTileOverlappedWithViewport(tile, viewportRect)
                } ?: emptyList()
            }
        }

        /**
         * It has [bitmap] corresponding to [rect] based on the source image size.
         * [bitmap] can be set or free or drawn as needed.
         *
         * @property index Index of tiles in same tile level.
         * @property rect The area to decode based on the source image size.
         * @property sampleSize It need to do subsampling based on viewport size.
         * @property bitmap A bitmap decoded by the required area based on the source image.
         */
        private inner class Tile(
            val level: Int,
            val index: Int,
            val rect: Rect,
            private val isTopTile: Boolean
        ) {
            var state: TileState = TileState.FREE
            var sampleSize: Int = 2f.pow(level).toInt()
            private var bitmap: Bitmap? = null
            private var bitmapRegionDecoder: BitmapRegionDecoder? = null

            fun freeBitmap() {
                bitmap?.recycle()
                bitmap = null

                state = TileState.FREE
            }

            fun decodeBitmap() {
                if (isTopTile) {
                    sampleSize = if (state == TileState.FREE) max(sampleSize, curTilesSampleSize) else curTilesSampleSize
                }

                state = TileState.DECODING

                CoroutineScope(Dispatchers.Default).launch {
                    if (!isTopTile) {
                        delay(200)
                    }

                    val viewportRect: RectF = getViewportRect()
                    if (!isTopTile && !isTileOverlappedWithViewport(this@Tile, viewportRect)) return@launch

                    if (bitmapRegionDecoder == null) {
                        val imageUri = this@TiledImageView.imageUri ?: return@launch
                        bitmapRegionDecoder = context.contentResolver.openInputStream(imageUri).use {
                            it?.let {
                                BitmapRegionDecoder.newInstance(it, false)
                            }
                        }
                    }

                    bitmapRegionDecoder?.let { decoder: BitmapRegionDecoder ->
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                        }
                        val decodedBitmap = decoder.decodeRegion(rect, options)

                        if (state == TileState.DECODING && (!isTopTile || (isTopTile && options.inSampleSize == sampleSize))) {
                            bitmap = decodedBitmap
                            state = TileState.DECODED
                            invalidate()
                        }
                    } ?: run {
                        freeBitmap()
                    }
                }
            }

            fun getBitmapAllocationByteCount(): Int {
                return bitmap?.allocationByteCount ?: 0
            }

            fun drawBitmap(canvas: Canvas, paint: Paint? = null) {
                val tileBitmap: Bitmap = this.bitmap ?: return
                val tileMatrix: Matrix = Matrix(imageMatrix).also { matrix: Matrix ->
                    val imageMatrixValues: FloatArray = imageMatrix.values()
                    val imageScale: Float = sqrt(imageMatrixValues[Matrix.MSCALE_X] * imageMatrixValues[Matrix.MSCALE_X] + imageMatrixValues[Matrix.MSKEW_Y] * imageMatrixValues[Matrix.MSKEW_Y])
                    val imageRotation: Float = -(atan2(imageMatrixValues[Matrix.MSKEW_X], imageMatrixValues[Matrix.MSCALE_X]) * (180.0 / PI)).toFloat()

                    if (isTopTile) {
                        matrix.postScale(sourceImageWidth.toFloat() / tileBitmap.width, sourceImageHeight.toFloat() / tileBitmap.height)
                    } else {
                        val sampleSize: Float = sampleSize.toFloat()
                        matrix.postScale(sampleSize, sampleSize)
                    }

                    val matrixValues: FloatArray = matrix.values().also {
                        it[Matrix.MTRANS_X] = imageMatrixValues[Matrix.MTRANS_X]
                        it[Matrix.MTRANS_Y] = imageMatrixValues[Matrix.MTRANS_Y]
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
        }
    }

    private enum class TileState {
        FREE, DECODING, DECODED
    }
}
