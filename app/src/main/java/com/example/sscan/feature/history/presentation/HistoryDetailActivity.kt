package com.example.sscan.feature.history.presentation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.sscan.R
import com.example.sscan.databinding.ActivityHistoryDetailBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class HistoryDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 启用边缘到边缘（Edge-to-Edge）
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityHistoryDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 沉浸式状态栏
        setupImmersiveMode()

        val result = intent.getStringExtra(EXTRA_RESULT).orEmpty()
        val imageUri = intent.getStringExtra(EXTRA_IMAGE_URI).orEmpty()
        val timeLabel = intent.getStringExtra(EXTRA_TIME_LABEL).orEmpty()

        binding.backButton.setOnClickListener { finish() }
        binding.resultContentValue.text = result
        binding.timeText.text = getString(R.string.history_time_prefix, timeLabel)
        binding.noteText.text = if (imageUri.isBlank()) {
            getString(R.string.history_image_saved_no)
        } else {
            getString(R.string.history_watermark_applied)
        }

        renderImage(imageUri)

        binding.copyButton.setOnClickListener {
            copyResult(result)
        }
        binding.shareButton.setOnClickListener {
            shareRecord(result, imageUri)
        }
        binding.resultImageView.setOnClickListener {
            openImagePreview(imageUri)
        }
        binding.viewImageButton.setOnClickListener {
            openImagePreview(imageUri)
        }
    }

    private fun renderImage(imageUri: String) {
        val hasImage = imageUri.isNotBlank()
        val avatarSize = resources.getDimensionPixelSize(R.dimen.size_avatar)
        val previewSize = if (hasImage) avatarSize * 2 else avatarSize

        binding.viewImageButton.isEnabled = hasImage
        binding.viewImageButton.alpha = if (hasImage) 1f else 0.5f
        binding.resultImageView.layoutParams = binding.resultImageView.layoutParams.apply {
            width = previewSize
            height = previewSize
        }
        binding.resultImageView.scaleType =
            if (hasImage) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.CENTER_INSIDE

        if (hasImage) {
            binding.resultImageView.setImageURI(imageUri.toUri())
            binding.imageStatusText.setText(R.string.history_click_view_image)
        } else {
            binding.resultImageView.setImageResource(android.R.drawable.ic_menu_report_image)
            binding.imageStatusText.setText(R.string.history_no_image)
        }
    }

    private fun copyResult(result: String) {
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(ClipData.newPlainText("scan_result", result))
        Toast.makeText(this, getString(R.string.history_copy_success), Toast.LENGTH_SHORT).show()
    }

    private fun openImagePreview(imageUri: String) {
        if (imageUri.isBlank()) {
            Toast.makeText(this, getString(R.string.history_no_image), Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, ImagePreviewActivity::class.java)
                .putExtra(ImagePreviewActivity.EXTRA_IMAGE_URI, imageUri)
        )
    }

    private fun shareRecord(result: String, imageUri: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            if (imageUri.isBlank()) {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, result)
            } else {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, imageUri.toUri())
                putExtra(Intent.EXTRA_TEXT, result)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.history_share_chooser)))
    }

    private fun setupImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 使用 WindowInsetsController
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // Android 10 及以下使用 View 系统
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }

        // 处理 WindowInsets，让内容不被系统栏遮挡
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, insets.top, 0, insets.bottom)
            windowInsets
        }
    }

    companion object {
        private const val EXTRA_RESULT = "extra_result"
        private const val EXTRA_IMAGE_URI = "extra_image_uri"
        private const val EXTRA_TIME_LABEL = "extra_time_label"

        fun createIntent(
            context: Context,
            result: String,
            imageUri: String,
            timeLabel: String
        ): Intent {
            return Intent(context, HistoryDetailActivity::class.java)
                .putExtra(EXTRA_RESULT, result)
                .putExtra(EXTRA_IMAGE_URI, imageUri)
                .putExtra(EXTRA_TIME_LABEL, timeLabel)
        }
    }
}
