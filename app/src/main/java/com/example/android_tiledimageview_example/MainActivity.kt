package com.example.android_tiledimageview_example

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.android_tiledimageview_example.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tiledImage.run {
            touchBehavior.isRotatingEnabled = false
            imageMinScale = 0f
            imageMaxScale = Float.MAX_VALUE
            debuggingCallback = { topTileLevel: Int, curTileLevel: Int, curSampleSize: Int, activeTilesSize: Int, bitmapAllocatedMemorySizeKb: Long ->
                binding.debuggingText.text = "topTileLevel=${topTileLevel}, curTileLevel=${curTileLevel}, curSampleSize=${curSampleSize}, activeTilesSize=${activeTilesSize}, bitmapAllocatedMemorySizeKb=${bitmapAllocatedMemorySizeKb}"
            }
            setImage(R.drawable.mountain_11785x7741)
        }
    }
}
