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
            setImage(R.drawable.mountain_11785x7741)
            debuggingCallback = { maxResolutionLv: Int, curResolutionLv: Int, activeTilesSize: Int, bitmapAllocatedMemorySizeMb: Long ->
                binding.debuggingText.text = "maxResolutionLv=${maxResolutionLv}, curResolutionLv=${curResolutionLv}, activeTilesSize=${activeTilesSize}, bitmapAllocatedMemorySizeMb=${bitmapAllocatedMemorySizeMb}"
            }
        }
    }
}
