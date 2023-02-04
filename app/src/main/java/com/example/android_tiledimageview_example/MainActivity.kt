package com.example.android_tiledimageview_example

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.android_tiledimageview_example.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import java.text.DecimalFormat

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tiledImage.run {
            scaleType = TiledImageView.ScaleType.FIT_INSIDE
            viewportRectView = TiledImageView.ViewportRectView.ITSELF
            imageMinScale = 0f
            imageMaxScale = Float.MAX_VALUE
            touchBehavior?.isPanningEnabled = true
            touchBehavior?.isScalingEnabled = true
            touchBehavior?.isRotatingEnabled = true
            setImage(R.drawable.mountain_11785x7741)
        }

        binding.debuggingSwitch.run {
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    binding.tiledImage.debuggingCallback = { topTileLevel: Int, curTileLevel: Int, topTileSampleSize: Int, curTilesSampleSize: Int, activeTilesSize: Int, bitmapAllocatedMemorySizeKb: Long ->
                        binding.debuggingText.text = buildString {
                            append("topTileLevel=${topTileLevel}")
                            append(", curTileLevel=${curTileLevel}")
                            append(", topTileSampleSize=${topTileSampleSize}")
                            append(", curTilesSampleSize=${curTilesSampleSize}")
                            append(", activeTilesSize=${activeTilesSize}")
                            append(", bitmapAllocatedMemorySizeKb=${DecimalFormat("#,###").format(bitmapAllocatedMemorySizeKb)}KB")
                        }
                    }
                    Snackbar.make(binding.root, "Debugging On", Snackbar.LENGTH_SHORT).show()
                } else {
                    binding.tiledImage.debuggingCallback = null
                    binding.debuggingText.text = ""
                    Snackbar.make(binding.root, "Debugging Off", Snackbar.LENGTH_SHORT).show()
                }
            }
            isChecked = true
        }
    }
}
