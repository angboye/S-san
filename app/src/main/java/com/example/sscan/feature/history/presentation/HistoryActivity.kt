package com.example.sscan.feature.history.presentation

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.sscan.R
import com.example.sscan.core.storage.MediaStoreDocumentSaver
import com.example.sscan.data.local.entity.ScanHistoryEntity
import com.example.sscan.databinding.ActivityHistoryBinding
import com.example.sscan.databinding.DialogCreateFolderBinding
import com.example.sscan.databinding.ItemHistoryFolderBinding
import com.example.sscan.databinding.ItemHistoryRecordBinding
import com.example.sscan.feature.scan.presentation.ScanActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@AndroidEntryPoint
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val viewModel: HistoryViewModel by viewModels()
    private var lastShownMessage: String? = null

    // 编辑模式状态
    private var isEditMode: Boolean = false
    private val editSelectedRecordIds = mutableSetOf<Long>()
    private val editSelectedFolderNames = mutableSetOf<String>()
    private var editCurrentTab: Int = TAB_SCAN
    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private var allRecordsJob: Job? = null
    private val knownGroupNames = mutableSetOf<String>()
    private var activeFolderName: String? = null

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra(ScanActivity.EXTRA_SCAN_RESULT)?.let { resultText ->
                binding.searchInput.setText(resultText)
                viewModel.updateSearchQuery(resultText)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 启用边缘到边缘
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 设置沉浸式状态栏
        setupImmersiveMode()

        setupUiListeners()
        observeUiState()
        refreshKnownGroupNames()
    }

    private fun setupImmersiveMode() {
        // 设置状态栏和导航栏颜色为透明
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 使用 WindowInsetsController
            window.insetsController?.let { controller ->
                // 设置系统栏为浅色内容（状态栏图标为深色）
                controller.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            }
        } else {
            // Android 10 及以下使用 View 系统
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    )
        }

        // 处理WindowInsets，让内容不被系统栏遮挡
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, insets.top, 0, insets.bottom)
            windowInsets
        }
    }

    private fun setupUiListeners() {
        binding.backButton.setOnClickListener { finish() }
        binding.searchScanButton.setOnClickListener {
            val intent = Intent(this, ScanActivity::class.java).apply {
                putExtra(ScanActivity.EXTRA_RETURN_RESULT_ONLY, true)
            }
            scanLauncher.launch(intent)
        }
        binding.moreButton.setOnClickListener {
            showMoreOptionsDialog()
        }

        setupSearchListener()
        setupTabListeners()
    }

    private fun setupEditModeListeners() {
        binding.editCloseBtn.setOnClickListener { exitEditMode() }
        binding.editSelectAll.setOnCheckedChangeListener(null)
        binding.editSelectAll.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                editSelectedRecordIds.clear()
                editSelectedFolderNames.clear()
                viewModel.allRecords.value.forEach { record ->
                    if (matchesCurrentTab(record)) {
                        editSelectedRecordIds.add(record.id)
                        val group = record.groupName
                        if (group.isNotBlank()) editSelectedFolderNames.add(group)
                    }
                }
            } else {
                editSelectedRecordIds.clear()
                editSelectedFolderNames.clear()
            }
            renderEditModeList()
            updateEditSelectionUI()
        }
        binding.actionMove.setOnClickListener { onEditMoveClicked() }
        binding.actionExport.setOnClickListener { onEditExportClicked() }
        binding.actionCompare.setOnClickListener { onEditCompareClicked() }
        binding.actionDelete.setOnClickListener { onEditDeleteClicked() }
    }

    private fun setupTabListeners() {
        binding.tabScan.setOnClickListener { selectScanTab() }
        binding.tabConvert.setOnClickListener { selectConvertTab() }
    }

    private fun selectScanTab() {
        binding.tabScan.setTextColor(getColor(com.example.sscan.R.color.brand_success))
        binding.tabScan.paint.isFakeBoldText = true
        binding.tabConvert.setTextColor(getColor(com.example.sscan.R.color.text_secondary))
        binding.tabConvert.paint.isFakeBoldText = false
        binding.tabIndicator.translationX = 0f
        if (isEditMode) {
            editCurrentTab = TAB_SCAN
            editSelectedRecordIds.clear()
            editSelectedFolderNames.clear()
            renderEditModeList()
            updateEditSelectionUI()
        }
    }

    private fun selectConvertTab() {
        binding.tabConvert.setTextColor(getColor(com.example.sscan.R.color.brand_success))
        binding.tabConvert.paint.isFakeBoldText = true
        binding.tabScan.setTextColor(getColor(com.example.sscan.R.color.text_secondary))
        binding.tabScan.paint.isFakeBoldText = false
        binding.tabIndicator.translationX = binding.tabIndicator.width.toFloat()
        if (isEditMode) {
            editCurrentTab = TAB_CONVERT
            editSelectedRecordIds.clear()
            editSelectedFolderNames.clear()
            renderEditModeList()
            updateEditSelectionUI()
        } else {
            showToast(getString(R.string.feature_coming_soon, getString(R.string.history_tab_convert)))
        }
    }

    private fun setupSearchListener() {
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) =
                Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.updateSearchQuery(s?.toString().orEmpty())
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (!isEditMode) {
                        renderList(state)
                    }
                    showMessageIfNeeded(state.message)
                }
            }
        }
    }

    private fun observeAllRecords() {
        allRecordsJob?.cancel()
        allRecordsJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.allRecords.collect {
                    if (isEditMode) {
                        renderEditModeList()
                    }
                }
            }
        }
    }

    private fun renderList(state: HistoryUiState) {
        binding.historyListContainer.removeAllViews()
        val density = resources.displayMetrics.density
        val sideMarginPx = (20 * density).toInt()
        val topMarginPx = (12 * density).toInt()

        val grouped = state.items.groupBy { it.groupName.ifBlank { "__NO_GROUP__" } }
        val folderNames = (
                knownGroupNames + grouped.keys.filter { it != "__NO_GROUP__" }
                ).filter { it.isNotBlank() }.sortedWith(
                compareByDescending<String> {
                    grouped[it]?.maxOfOrNull { item -> item.timeMillis } ?: 0L
                }
                    .thenBy { it }
            )
        val selectedFolder = activeFolderName
        val visibleRecords = if (selectedFolder.isNullOrBlank()) {
            grouped["__NO_GROUP__"].orEmpty()
        } else {
            grouped[selectedFolder].orEmpty()
        }

        if (!selectedFolder.isNullOrBlank()) {
            addFolderReturnRow(selectedFolder, sideMarginPx, topMarginPx)
        } else {
            folderNames.forEach { groupName ->
                val records = grouped[groupName].orEmpty()
                val folderBinding = createHistoryFolderView(groupName, records)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.topMargin = topMarginPx
                params.marginStart = sideMarginPx
                params.marginEnd = sideMarginPx
                binding.historyListContainer.addView(folderBinding.root, params)
            }
        }

        visibleRecords.forEach { item ->
            val itemBinding = createHistoryItemView(item)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = topMarginPx
            params.marginStart = sideMarginPx
            params.marginEnd = sideMarginPx
            binding.historyListContainer.addView(itemBinding.root, params)
        }

        val hasVisibleContent =
            (!selectedFolder.isNullOrBlank() || folderNames.isNotEmpty() || visibleRecords.isNotEmpty())
        binding.emptyView.isVisible = !hasVisibleContent
        binding.historyTotalCount.text =
            getString(R.string.history_total_count, visibleRecords.size)
    }

    private fun addFolderReturnRow(folderName: String, sideMarginPx: Int, topMarginPx: Int) {
        val row = TextView(this).apply {
            text = getString(R.string.history_folder_return_all, folderName)
            setTextColor(getColor(com.example.sscan.R.color.brand_success))
            textSize = 15f
            paint.isFakeBoldText = true
            setPadding(4.dp, 8.dp, 4.dp, 4.dp)
            setOnClickListener {
                activeFolderName = null
                renderList(viewModel.uiState.value)
            }
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = topMarginPx
        params.marginStart = sideMarginPx
        params.marginEnd = sideMarginPx
        binding.historyListContainer.addView(row, params)
    }

    private fun showMessageIfNeeded(message: String?) {
        if (!message.isNullOrBlank() && message != lastShownMessage) {
            lastShownMessage = message
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    /** 有扫码原图就显示缩略图，没有则退回条码占位图。 */
    private fun bindRecordThumbnail(
        itemBinding: ItemHistoryRecordBinding,
        imageUri: String
    ) {
        if (imageUri.isBlank()) return
        runCatching {
            itemBinding.thumbnailImage.setPadding(0, 0, 0, 0)
            itemBinding.thumbnailImage.imageTintList = null
            itemBinding.thumbnailImage.setImageURI(android.net.Uri.parse(imageUri))
        }
    }

    private fun createHistoryItemView(
        item: HistoryItemUi
    ): ItemHistoryRecordBinding {
        val itemBinding = ItemHistoryRecordBinding.inflate(layoutInflater)
        itemBinding.typeText.text = getBarcodeTypeLabel(item.result)
        itemBinding.resultText.text = item.result
        itemBinding.noteText.text = getString(
            R.string.history_edit_note,
            item.note.ifBlank { getString(R.string.history_note_empty) }
        )
        itemBinding.timeText.text = item.timeLabel
        bindRecordThumbnail(itemBinding, item.imageUri)

        // 点击 = 打开详情
        itemBinding.root.setOnClickListener {
            startActivity(
                HistoryDetailActivity.createIntent(
                    context = this,
                    result = item.result,
                    imageUri = item.imageUri,
                    timeLabel = item.timeLabel
                )
            )
        }

        // 长按 = 进入编辑模式并选中该项
        itemBinding.root.setOnLongClickListener {
            enterEditModeWithSelection(item.id)
            true
        }

        itemBinding.exportButton.setOnClickListener {
            // 此前这里调的是 shareSingleItem（分享文本、不产出文件），与编辑模式下
            // 同名按钮行为不一致。统一走主链路的四选一导出。
            val entity = viewModel.allRecords.value.firstOrNull { it.id == item.id }
            if (entity == null) {
                showToast(com.example.sscan.R.string.history_export_failed)
            } else {
                showExportModeDialog(listOf(entity))
            }
        }

        itemBinding.deleteButton.setOnClickListener {
            viewModel.moveToTrash(item.id)
        }

        return itemBinding
    }

    private fun createHistoryFolderView(
        groupName: String,
        records: List<HistoryItemUi>
    ): ItemHistoryFolderBinding {
        val itemBinding = ItemHistoryFolderBinding.inflate(layoutInflater)
        val recordIds = records.map { it.id }
        val latestTime = records.maxByOrNull { it.timeMillis }?.timeLabel

        itemBinding.folderTitle.text = getString(R.string.history_edit_folder_name, groupName)
        itemBinding.folderTime.text =
            latestTime ?: getString(com.example.sscan.R.string.history_empty_desc)

        itemBinding.folderCard.setOnClickListener {
            activeFolderName = groupName
            renderList(viewModel.uiState.value)
        }

        itemBinding.folderRename.setOnClickListener {
            showRenameFolderDialog(groupName, recordIds)
        }

        itemBinding.folderExport.setOnClickListener {
            val entities = viewModel.allRecords.value.filter { it.id in recordIds }
            if (entities.isEmpty()) {
                showToast(getString(com.example.sscan.R.string.history_edit_please_select))
            } else {
                showExportModeDialog(entities)
            }
        }

        itemBinding.folderDelete.setOnClickListener {
            showConfirmDeleteFolderDialog(groupName, recordIds)
        }

        return itemBinding
    }

    // ========== 分享 / 导出 ==========

    private fun showMoreOptionsDialog() {
        val items = arrayOf<CharSequence>(
            // 扫码页不再每扫一次问一次文件夹，改成扫完来这里批量归类，
            // 所以这个入口必须能被找到——此前叫「编辑」，没人看得出它能归类。
            getString(com.example.sscan.R.string.history_more_batch_move),
            getString(com.example.sscan.R.string.history_more_create_folder)
        )
        AlertDialog.Builder(this)
            .setTitle(com.example.sscan.R.string.history_more_title)
            .setItems(items) { _, which ->
                when (which) {
                    // 必须走 showEditRecordDialog：它负责 isEditMode = true、重置 tab、
                    // 清空上一次的勾选。直接调内层的 enterEditMode() 只会把编辑栏显示出来，
                    // 状态标志仍是 false——行点击会跳详情页而不是勾选，且会带着上次的残留选中项。
                    0 -> showEditRecordDialog()
                    1 -> showCreateFolderDialog()
                }
            }
            .show()
    }

    /** 弹出"新建文件夹"对话框。居中显示，输入名字后点击完成。 */
    private fun showCreateFolderDialog() {
        val dialogBinding = DialogCreateFolderBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        // 去掉 AlertDialog 默认背景，让自定义卡片显示
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogBinding.confirmButton.setOnClickListener {
            val name = dialogBinding.folderNameInput.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                showToast(com.example.sscan.R.string.dialog_create_folder_empty)
                return@setOnClickListener
            }
            viewModel.createGroup(name)
            knownGroupNames.add(name)
            showToast(getString(com.example.sscan.R.string.dialog_create_folder_created, name))
            dialog.dismiss()
            if (!isEditMode) {
                renderList(viewModel.uiState.value)
            } else {
                renderEditModeList()
            }
        }
        dialogBinding.cancelButton.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    /** 进入编辑模式页面 */
    private fun showEditRecordDialog() {
        isEditMode = true
        editCurrentTab = TAB_SCAN
        editSelectedRecordIds.clear()
        editSelectedFolderNames.clear()
        enterEditMode()
    }

    private fun enterEditMode() {
        // 隐藏普通顶部栏，显示编辑模式顶部栏
        binding.topBar.visibility = View.INVISIBLE
        binding.editModeBar.isVisible = true
        // 显示底部操作栏
        binding.editBottomBar.isVisible = true
        // 设置编辑模式监听器
        setupEditModeListeners()
        // 清空选择状态
        binding.editSelectAll.setOnCheckedChangeListener(null)
        binding.editSelectAll.isChecked = false
        // 清空并渲染编辑模式列表
        binding.historyListContainer.removeAllViews()
        // 开始观察 allRecords 变化
        observeAllRecords()
        refreshKnownGroupNames()
        renderEditModeList()
        updateEditSelectionUI()
    }

    /** 长按某条记录 → 进入编辑模式并预选该项，复用编辑模式的完整操作能力 */
    private fun enterEditModeWithSelection(recordId: Long) {
        isEditMode = true
        editCurrentTab = TAB_SCAN
        editSelectedRecordIds.clear()
        editSelectedRecordIds.add(recordId)
        editSelectedFolderNames.clear()
        enterEditMode()
    }

    private fun exitEditMode() {
        isEditMode = false
        binding.topBar.visibility = View.VISIBLE
        binding.editModeBar.isVisible = false
        binding.editBottomBar.isVisible = false
        binding.historyListContainer.removeAllViews()
        allRecordsJob?.cancel()
        allRecordsJob = null
        renderList(viewModel.uiState.value)
    }

    private fun matchesCurrentTab(record: ScanHistoryEntity): Boolean {
        val hasCustom = record.customTitle.isNotBlank()
        return if (editCurrentTab == TAB_SCAN) !hasCustom else hasCustom
    }

    private fun renderEditModeList() {
        val allRecords = viewModel.allRecords.value
        val filteredRecords = allRecords.filter { matchesCurrentTab(it) }

        // 按 groupName 分组
        val grouped = filteredRecords.groupBy { it.groupName.ifBlank { "__NO_GROUP__" } }

        binding.historyListContainer.removeAllViews()

        val density = resources.displayMetrics.density
        val sideMarginPx = (20 * density).toInt()
        val topMarginPx = (12 * density).toInt()

        val folderNames = (
                knownGroupNames + grouped.keys.filter { it != "__NO_GROUP__" }
                ).filter { it.isNotBlank() }.sortedWith(
                compareByDescending<String> {
                    grouped[it]?.maxOfOrNull { record -> record.time } ?: 0L
                }
                    .thenBy { it }
            )

        // 文件夹条目，包括尚未放入记录的空文件夹
        folderNames.forEach { groupName ->
            val records = grouped[groupName].orEmpty()
            val itemBinding = createEditFolderView(groupName, records)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = topMarginPx
            params.marginStart = sideMarginPx
            params.marginEnd = sideMarginPx
            binding.historyListContainer.addView(itemBinding.root, params)
        }

        // 独立记录条目
        grouped["__NO_GROUP__"]
            ?.sortedByDescending { it.time }
            ?.forEach { record ->
                val itemBinding = createEditRecordView(record)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.topMargin = topMarginPx
                params.marginStart = sideMarginPx
                params.marginEnd = sideMarginPx
                binding.historyListContainer.addView(itemBinding.root, params)
            }

        // 更新全选状态
        val visibleIds = grouped.values.flatten().map { it.id }.toSet()
        val allSelected = visibleIds.isNotEmpty() && visibleIds.all { it in editSelectedRecordIds }
        binding.editSelectAll.setOnCheckedChangeListener(null)
        binding.editSelectAll.isChecked = allSelected
        binding.editSelectAll.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                editSelectedRecordIds.clear()
                editSelectedFolderNames.clear()
                allRecords.forEach { record ->
                    if (matchesCurrentTab(record)) {
                        editSelectedRecordIds.add(record.id)
                        val group = record.groupName
                        if (group.isNotBlank()) editSelectedFolderNames.add(group)
                    }
                }
            } else {
                editSelectedRecordIds.clear()
                editSelectedFolderNames.clear()
            }
            renderEditModeList()
            updateEditSelectionUI()
        }
    }

    private fun createEditFolderView(
        groupName: String,
        records: List<ScanHistoryEntity>
    ): ItemHistoryFolderBinding {
        val itemBinding = ItemHistoryFolderBinding.inflate(layoutInflater)
        val latestTime = records.maxOfOrNull { it.time } ?: 0L

        itemBinding.folderTitle.text =
            getString(com.example.sscan.R.string.history_edit_folder_name, groupName)
        itemBinding.folderTime.text = if (records.isEmpty()) {
            getString(com.example.sscan.R.string.history_empty_desc)
        } else {
            dateTimeFormat.format(Date(latestTime))
        }

        val allSelected = records.all { it.id in editSelectedRecordIds } && records.isNotEmpty()
        val selected = allSelected || groupName in editSelectedFolderNames
        itemBinding.root.setCardBackgroundColor(if (selected) getColor(com.example.sscan.R.color.surface_selected) else getColor(com.example.sscan.R.color.surface_card))
        itemBinding.root.strokeColor = if (selected) {
            getColor(com.example.sscan.R.color.brand_success)
        } else {
            getColor(com.example.sscan.R.color.surface_divider)
        }

        itemBinding.folderCard.setOnClickListener {
            if (selected) {
                editSelectedFolderNames.remove(groupName)
                records.forEach { editSelectedRecordIds.remove(it.id) }
            } else {
                editSelectedFolderNames.add(groupName)
                editSelectedRecordIds.addAll(records.map { it.id })
            }
            renderEditModeList()
            updateEditSelectionUI()
        }

        itemBinding.folderRename.setOnClickListener {
            showRenameFolderDialog(groupName, records.map { it.id })
        }
        itemBinding.folderExport.setOnClickListener {
            if (records.isEmpty()) {
                showToast(getString(com.example.sscan.R.string.history_edit_please_select))
            } else {
                showExportModeDialog(records)
            }
        }
        itemBinding.folderDelete.setOnClickListener {
            showConfirmDeleteFolderDialog(groupName, records.map { it.id })
        }

        return itemBinding
    }

    private fun refreshKnownGroupNames() {
        lifecycleScope.launch {
            val historyViewModel = viewModel
            val folders = withContext(Dispatchers.IO) {
                historyViewModel.getAllGroupNames()
            }
            knownGroupNames.clear()
            knownGroupNames.addAll(folders)
            if (isEditMode) {
                renderEditModeList()
            } else {
                renderList(viewModel.uiState.value)
            }
        }
    }

    private fun createEditRecordView(record: ScanHistoryEntity): ItemHistoryRecordBinding {
        val itemBinding = ItemHistoryRecordBinding.inflate(layoutInflater)

        val selected = record.id in editSelectedRecordIds
        itemBinding.typeText.text = getBarcodeTypeLabel(record.result)
        itemBinding.resultText.text = record.result
        itemBinding.noteText.text = getString(
            com.example.sscan.R.string.history_edit_note,
            record.customTitle.ifBlank { getString(R.string.history_note_empty) }
        )
        itemBinding.timeText.text = dateTimeFormat.format(Date(record.time))
        itemBinding.selectIndicator.isVisible = true
        itemBinding.root.setCardBackgroundColor(if (selected) getColor(com.example.sscan.R.color.surface_selected) else getColor(com.example.sscan.R.color.surface_card))
        itemBinding.checkIcon.setBackgroundResource(
            if (selected) {
                com.example.sscan.R.drawable.bg_history_check
            } else {
                com.example.sscan.R.drawable.bg_history_check_off
            }
        )
        itemBinding.checkIcon.setImageResource(
            if (selected) com.example.sscan.R.drawable.ic_check else 0
        )

        itemBinding.root.setOnClickListener {
            if (record.id in editSelectedRecordIds) {
                editSelectedRecordIds.remove(record.id)
            } else {
                editSelectedRecordIds.add(record.id)
            }
            renderEditModeList()
            updateEditSelectionUI()
        }

        itemBinding.exportButton.setOnClickListener {
            showExportModeDialog(listOf(record))
        }
        itemBinding.deleteButton.setOnClickListener {
            showConfirmDeleteRecordDialog(record.id)
        }

        return itemBinding
    }

    private fun updateEditSelectionUI() {
        val count = editSelectedRecordIds.size
        binding.editSelectedCount.text =
            getString(com.example.sscan.R.string.history_edit_selected, count)
    }

    // ===== 编辑模式操作 =====

    private fun onEditMoveClicked() {
        if (editSelectedRecordIds.isEmpty()) {
            showToast(getString(com.example.sscan.R.string.history_edit_please_select))
            return
        }
        lifecycleScope.launch {
            val folders = viewModel.getAllGroupNames()
            withContext(Dispatchers.Main) {
                showMoveToFolderDialog(folders)
            }
        }
    }

    private fun showMoveToFolderDialog(existingFolders: List<String>) {
        val dialog = AlertDialog.Builder(this).create()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
            background = getDrawable(com.example.sscan.R.drawable.bg_dialog_card)
        }

        val title = TextView(this).apply {
            text = getString(com.example.sscan.R.string.history_edit_move_to_folder)
            setTextColor(getColor(com.example.sscan.R.color.text_primary))
            textSize = 18f
            paint.isFakeBoldText = true
        }
        content.addView(title)

        // 现有文件夹列表
        val scrollContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 30; it.bottomMargin = 10 }
        }
        if (existingFolders.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(com.example.sscan.R.string.history_edit_no_folder)
                setTextColor(getColor(com.example.sscan.R.color.text_tertiary))
                textSize = 13f
                setPadding(0, 8, 0, 8)
            }
            scrollContainer.addView(empty)
        } else {
            existingFolders.forEach { folder ->
                val row = TextView(this).apply {
                    text = folder
                    setPadding(30, 18, 30, 18)
                    setTextColor(getColor(com.example.sscan.R.color.text_primary))
                    textSize = 14f
                    background = getDrawable(com.example.sscan.R.drawable.bg_dialog_input)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.topMargin = 8
                    lp.bottomMargin = 8
                    layoutParams = lp
                    setOnClickListener {
                        performMove(folder)
                        dialog.dismiss()
                    }
                }
                scrollContainer.addView(row)
            }
        }
        content.addView(scrollContainer)

        // 或者新建文件夹输入框
        val hint = TextView(this).apply {
            text = getString(com.example.sscan.R.string.history_edit_or_new_folder)
            setTextColor(getColor(com.example.sscan.R.color.text_tertiary))
            textSize = 12f
            setPadding(0, 10, 0, 6)
        }
        content.addView(hint)

        val input = EditText(this).apply {
            setHint(com.example.sscan.R.string.history_edit_enter_folder_name)
            setBackgroundColor(getColor(com.example.sscan.R.color.surface_subtle))
            setPadding(40, 24, 40, 24)
            setTextColor(getColor(com.example.sscan.R.color.text_primary))
            textSize = 14f
        }
        content.addView(input)

        // 按钮栏
        val btnBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 40
            layoutParams = lp
        }
        val cancel = TextView(this).apply {
            text = getString(com.example.sscan.R.string.dialog_edit_record_cancel)
            setPadding(50, 24, 50, 24)
            setTextColor(getColor(com.example.sscan.R.color.text_secondary))
            textSize = 14f
            setOnClickListener { dialog.dismiss() }
        }
        val confirm = TextView(this).apply {
            text = getString(com.example.sscan.R.string.dialog_edit_record_confirm)
            setPadding(50, 24, 50, 24)
            setTextColor(getColor(com.example.sscan.R.color.brand_success))
            textSize = 14f
            paint.isFakeBoldText = true
            setOnClickListener {
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    showToast(getString(com.example.sscan.R.string.history_edit_enter_folder_name))
                    return@setOnClickListener
                }
                performMove(name)
                dialog.dismiss()
            }
        }
        btnBar.addView(cancel)
        btnBar.addView(confirm)
        content.addView(btnBar)

        dialog.setView(content)
        dialog.show()
    }

    private fun performMove(folderName: String) {
        val ids = editSelectedRecordIds.toList()
        if (ids.isEmpty()) return
        lifecycleScope.launch {
            viewModel.moveToGroup(folderName, ids)
            withContext(Dispatchers.Main) {
                showToast(getString(com.example.sscan.R.string.history_edit_moved, folderName))
                editSelectedRecordIds.clear()
                editSelectedFolderNames.clear()
                updateEditSelectionUI()
            }
        }
    }

    private fun onEditExportClicked() {
        if (editSelectedRecordIds.isEmpty()) {
            showToast(getString(com.example.sscan.R.string.history_edit_please_select))
            return
        }
        val records = viewModel.allRecords.value.filter { it.id in editSelectedRecordIds }
        if (records.isEmpty()) {
            showToast(getString(com.example.sscan.R.string.history_edit_please_select))
            return
        }
        showExportModeDialog(records)
    }

    private fun showExportModeDialog(records: List<ScanHistoryEntity>) {
        val dialog = AlertDialog.Builder(this).create()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28.dp, 24.dp, 28.dp, 8.dp)
            setBackgroundColor(getColor(com.example.sscan.R.color.surface_card))
        }

        val title = TextView(this).apply {
            text = getString(com.example.sscan.R.string.history_export_mode_title)
            setTextColor(getColor(com.example.sscan.R.color.text_primary))
            textSize = 20f
            setPadding(0, 0, 0, 26.dp)
        }
        content.addView(title)

        addExportModeRow(
            content = content,
            title = getString(com.example.sscan.R.string.history_export_mode_content_text),
            desc = getString(com.example.sscan.R.string.history_export_mode_content_text_desc)
        ) {
            dialog.dismiss()
            exportRecords(records, ExportMode.CONTENT_TEXT)
        }
        addDivider(content)
        addExportModeRow(
            content = content,
            title = getString(com.example.sscan.R.string.history_export_mode_content_table),
            desc = getString(com.example.sscan.R.string.history_export_mode_content_table_desc)
        ) {
            dialog.dismiss()
            exportRecords(records, ExportMode.CONTENT_TABLE)
        }
        addDivider(content)
        addExportModeRow(
            content = content,
            title = getString(com.example.sscan.R.string.history_export_mode_detail_table),
            desc = getString(com.example.sscan.R.string.history_export_mode_detail_table_desc)
        ) {
            dialog.dismiss()
            exportRecords(records, ExportMode.DETAIL_TABLE)
        }
        addDivider(content)
        addExportModeRow(
            content = content,
            title = getString(com.example.sscan.R.string.history_export_mode_detail_image_table),
            desc = getString(com.example.sscan.R.string.history_export_mode_detail_image_table_desc)
        ) {
            dialog.dismiss()
            exportRecords(records, ExportMode.DETAIL_TABLE_WITH_IMAGES)
        }

        dialog.setView(content)
        dialog.show()
    }

    private fun addExportModeRow(
        content: LinearLayout,
        title: String,
        desc: String,
        onClick: () -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 20.dp, 0, 20.dp)
            setOnClickListener { onClick() }
            background = android.util.TypedValue().let { outValue ->
                theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                getDrawable(outValue.resourceId)
            }
        }
        row.addView(TextView(this).apply {
            text = title
            setTextColor(getColor(com.example.sscan.R.color.text_primary))
            textSize = 18f
            paint.isFakeBoldText = true
        })
        row.addView(TextView(this).apply {
            text = desc
            setTextColor(getColor(com.example.sscan.R.color.text_secondary))
            textSize = 16f
            setPadding(0, 3.dp, 0, 0)
        })
        content.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun addDivider(content: LinearLayout) {
        content.addView(View(this).apply {
            setBackgroundColor(getColor(com.example.sscan.R.color.surface_subtle))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
    }

    private fun exportRecords(records: List<ScanHistoryEntity>, mode: ExportMode) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 先写缓存，再发布到系统「下载」；缓存文件由 publish 负责清理
                val dir = File(cacheDir, "export").apply { if (!exists()) mkdirs() }
                val timestamp =
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val displayName =
                    getString(R.string.history_export_file_name_single, timestamp, mode.extension)
                val file = File(dir, displayName)
                when (mode) {
                    ExportMode.CONTENT_TEXT -> writeContentText(file, records)
                    ExportMode.CONTENT_TABLE -> writeXlsx(
                        file,
                        records,
                        includeDetails = false,
                        includeImages = false
                    )

                    ExportMode.DETAIL_TABLE -> writeXlsx(
                        file,
                        records,
                        includeDetails = true,
                        includeImages = false
                    )

                    ExportMode.DETAIL_TABLE_WITH_IMAGES -> writeXlsx(
                        file,
                        records,
                        includeDetails = true,
                        includeImages = true
                    )
                }
                val saved = MediaStoreDocumentSaver.publish(
                    context = this@HistoryActivity,
                    source = file,
                    displayName = displayName,
                    mimeType = MediaStoreDocumentSaver.mimeTypeOf(displayName)
                )
                withContext(Dispatchers.Main) {
                    showShareFileDialog(saved)
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    showToast(
                        getString(
                            com.example.sscan.R.string.history_edit_export_failed,
                            t.message.orEmpty()
                        )
                    )
                }
            }
        }
    }

    private fun writeContentText(file: File, records: List<ScanHistoryEntity>) {
        FileWriter(file).use { writer ->
            records.forEach { record ->
                writer.write(record.result)
                writer.write("\n")
            }
        }
    }

    private fun writeXlsx(
        file: File,
        records: List<ScanHistoryEntity>,
        includeDetails: Boolean,
        includeImages: Boolean
    ) {
        val imageRefs = if (includeImages) {
            records.mapNotNull { record ->
                readExportImage(record)?.let { image -> record.id to image }
            }.toMap()
        } else {
            emptyMap()
        }
        val columns = if (includeDetails) {
            mutableListOf(
                getString(R.string.history_export_header_content),
                getString(R.string.history_export_header_note),
                getString(R.string.history_export_header_time),
                getString(R.string.history_export_header_type),
                getString(R.string.history_export_header_folder)
            ).also {
                if (includeImages) it += getString(R.string.history_export_header_image)
            }
        } else {
            mutableListOf(getString(R.string.history_export_header_content))
        }

        ZipOutputStream(file.outputStream()).use { zip ->
            zipText(
                zip,
                "[Content_Types].xml",
                contentTypesXml(imageRefs.values.toList(), includeImages)
            )
            zipText(zip, "_rels/.rels", rootRelsXml())
            zipText(zip, "xl/workbook.xml", workbookXml())
            zipText(zip, "xl/_rels/workbook.xml.rels", workbookRelsXml())
            zipText(zip, "xl/styles.xml", stylesXml())
            zipText(
                zip,
                "xl/worksheets/sheet1.xml",
                sheetXml(records, columns, includeDetails, includeImages, imageRefs)
            )
            if (includeImages && imageRefs.isNotEmpty()) {
                zipText(zip, "xl/worksheets/_rels/sheet1.xml.rels", sheetDrawingRelsXml())
                zipText(zip, "xl/drawings/drawing1.xml", drawingXml(records, imageRefs))
                zipText(
                    zip,
                    "xl/drawings/_rels/drawing1.xml.rels",
                    drawingRelsXml(records, imageRefs)
                )
                records.forEach { record ->
                    imageRefs[record.id]?.let { image ->
                        zipBytes(zip, "xl/media/image${record.id}.${image.extension}", image.bytes)
                    }
                }
            }
        }
    }

    private fun sheetXml(
        records: List<ScanHistoryEntity>,
        columns: List<String>,
        includeDetails: Boolean,
        includeImages: Boolean,
        imageRefs: Map<Long, ExportImage>
    ): String {
        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """)
            append("""xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
            append("<cols>")
            columns.forEachIndexed { index, _ ->
                val width = when {
                    includeImages && index == columns.lastIndex -> 28
                    includeDetails && index == 0 -> 42
                    else -> 20
                }
                append("""<col min="${index + 1}" max="${index + 1}" width="$width" customWidth="1"/>""")
            }
            append("</cols><sheetData>")
            append("""<row r="1">""")
            columns.forEachIndexed { index, title ->
                append(cell(index + 1, 1, title, style = 1))
            }
            append("</row>")
            records.forEachIndexed { rowIndex, record ->
                val rowNumber = rowIndex + 2
                val height = if (includeImages && imageRefs.containsKey(record.id)) 92 else 28
                append("""<row r="$rowNumber" ht="$height" customHeight="1">""")
                val row = if (includeDetails) {
                    mutableListOf(
                        record.result,
                        record.customTitle,
                        dateTimeFormat.format(Date(record.time)),
                        getBarcodeTypeLabel(record.result),
                        record.groupName
                    ).also {
                        if (includeImages) {
                            it += if (imageRefs.containsKey(record.id)) {
                                getString(R.string.history_export_seen_image)
                            } else {
                                ""
                            }
                        }
                    }
                } else {
                    mutableListOf(record.result)
                }
                row.forEachIndexed { colIndex, value ->
                    append(cell(colIndex + 1, rowNumber, value))
                }
                append("</row>")
            }
            append("</sheetData>")
            if (includeImages && imageRefs.isNotEmpty()) {
                append("""<drawing r:id="rId1"/>""")
            }
            append("</worksheet>")
        }
    }

    private fun drawingXml(
        records: List<ScanHistoryEntity>,
        imageRefs: Map<Long, ExportImage>
    ): String {
        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" """)
            append("""xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">""")
            var imageIndex = 1
            records.forEachIndexed { rowIndex, record ->
                if (imageRefs.containsKey(record.id)) {
                    val row = rowIndex + 1
                    append(
                        """
                        <xdr:twoCellAnchor editAs="oneCell">
                            <xdr:from><xdr:col>5</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>$row</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>
                            <xdr:to><xdr:col>6</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>${row + 1}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>
                            <xdr:pic>
                                <xdr:nvPicPr><xdr:cNvPr id="${imageIndex + 1}" name="${getString(R.string.history_export_image_name, imageIndex)}"/><xdr:cNvPicPr/></xdr:nvPicPr>
                                <xdr:blipFill><a:blip xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" r:embed="rId$imageIndex"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>
                                <xdr:spPr><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr>
                            </xdr:pic>
                            <xdr:clientData/>
                        </xdr:twoCellAnchor>
                        """.trimIndent()
                    )
                    imageIndex++
                }
            }
            append("</xdr:wsDr>")
        }
    }

    private fun contentTypesXml(images: List<ExportImage>, includeImages: Boolean): String {
        val defaults = buildString {
            if (includeImages) {
                if (images.any { it.extension == "png" }) append("""<Default Extension="png" ContentType="image/png"/>""")
                if (images.any { it.extension == "jpg" }) append("""<Default Extension="jpg" ContentType="image/jpeg"/>""")
                if (images.any { it.extension == "jpeg" }) append("""<Default Extension="jpeg" ContentType="image/jpeg"/>""")
            }
        }
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                $defaults
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                ${if (includeImages && images.isNotEmpty()) """<Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>""" else ""}
            </Types>
        """.trimIndent()
    }

    private fun rootRelsXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
        </Relationships>
    """.trimIndent()

    private fun workbookXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
            <sheets><sheet name="${getString(R.string.history_export_sheet_name)}" sheetId="1" r:id="rId1"/></sheets>
        </workbook>
    """.trimIndent()

    private fun workbookRelsXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
            <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
        </Relationships>
    """.trimIndent()

    private fun stylesXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
            <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
            <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
            <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
            <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
            <cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>
        </styleSheet>
    """.trimIndent()

    private fun sheetDrawingRelsXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/drawing1.xml"/>
        </Relationships>
    """.trimIndent()

    private fun drawingRelsXml(
        records: List<ScanHistoryEntity>,
        imageRefs: Map<Long, ExportImage>
    ): String {
        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            var index = 1
            records.forEach { record ->
                imageRefs[record.id]?.let { image ->
                    append("""<Relationship Id="rId$index" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/image${record.id}.${image.extension}"/>""")
                    index++
                }
            }
            append("</Relationships>")
        }
    }

    private fun cell(col: Int, row: Int, value: String, style: Int = 0): String {
        val ref = "${columnName(col)}$row"
        val styleAttr = if (style > 0) """ s="$style"""" else ""
        return """<c r="$ref" t="inlineStr"$styleAttr><is><t>${xml(value)}</t></is></c>"""
    }

    private fun columnName(index: Int): String {
        var value = index
        val result = StringBuilder()
        while (value > 0) {
            value--
            result.insert(0, ('A'.code + value % 26).toChar())
            value /= 26
        }
        return result.toString()
    }

    private fun zipText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun zipBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun readExportImage(record: ScanHistoryEntity): ExportImage? {
        val uri = record.imageUri.takeIf { it.isNotBlank() } ?: return null
        val bytes = runCatching {
            when {
                uri.startsWith("content://") || uri.startsWith("file://") -> {
                    contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytesCompat() }
                }

                else -> File(uri).takeIf { it.exists() }?.readBytes()
            }
        }.getOrNull() ?: return null
        val extension = when {
            uri.endsWith(".png", ignoreCase = true) -> "png"
            uri.endsWith(".jpeg", ignoreCase = true) -> "jpeg"
            else -> "jpg"
        }
        return ExportImage(bytes = bytes, extension = extension)
    }

    private fun java.io.InputStream.readBytesCompat(): ByteArray {
        val buffer = ByteArrayOutputStream()
        val data = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = read(data)
            if (count == -1) break
            buffer.write(data, 0, count)
        }
        return buffer.toByteArray()
    }

    private fun html(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun xml(value: String): String = html(value)

    private fun onEditCompareClicked() {
        val records = viewModel.allRecords.value.filter { it.id in editSelectedRecordIds }
        if (records.size < 2) {
            showToast(getString(com.example.sscan.R.string.history_edit_compare_need_two))
            return
        }
        showCompareDialog(records)
    }

    private fun showCompareDialog(records: List<ScanHistoryEntity>) {
        val dialog = AlertDialog.Builder(this).create()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 30)
            background = getDrawable(com.example.sscan.R.drawable.bg_dialog_card)
        }

        val title = TextView(this).apply {
            text = getString(com.example.sscan.R.string.history_edit_compare_title)
            setTextColor(getColor(com.example.sscan.R.color.text_primary))
            textSize = 18f
            paint.isFakeBoldText = true
        }
        content.addView(title)

        records.forEachIndexed { index, record ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(getColor(com.example.sscan.R.color.surface_subtle))
                setPadding(20, 16, 20, 16)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also {
                    it.topMargin = if (index == 0) 24 else 12
                }
            }
            val indexText = TextView(this).apply {
                text = getString(R.string.history_compare_record_label, index + 1)
                setTextColor(getColor(com.example.sscan.R.color.brand_success))
                textSize = 12f
                paint.isFakeBoldText = true
            }
            card.addView(indexText)
            val resultText = TextView(this).apply {
                text = record.result
                setTextColor(getColor(com.example.sscan.R.color.text_primary))
                textSize = 14f
                setPadding(0, 8, 0, 0)
            }
            card.addView(resultText)
            content.addView(card)
        }

        val closeBtn = TextView(this).apply {
            text = getString(com.example.sscan.R.string.dialog_edit_record_cancel)
            setPadding(50, 24, 50, 24)
            setTextColor(getColor(com.example.sscan.R.color.text_secondary))
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 24 }
            setOnClickListener { dialog.dismiss() }
        }
        content.addView(closeBtn)

        dialog.setView(content)
        dialog.show()
    }

    private fun onEditDeleteClicked() {
        if (editSelectedRecordIds.isEmpty()) {
            showToast(getString(com.example.sscan.R.string.history_edit_please_select))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(com.example.sscan.R.string.history_edit_confirm_delete))
            .setMessage(getString(com.example.sscan.R.string.history_edit_confirm_delete_desc))
            .setPositiveButton(getString(com.example.sscan.R.string.settings_dialog_confirm)) { _, _ ->
                val ids = editSelectedRecordIds.toList()
                lifecycleScope.launch {
                    viewModel.moveSelectedToTrash(ids)
                    withContext(Dispatchers.Main) {
                        showToast(getString(com.example.sscan.R.string.history_edit_deleted))
                        editSelectedRecordIds.clear()
                        editSelectedFolderNames.clear()
                        updateEditSelectionUI()
                    }
                }
            }
            .setNegativeButton(
                getString(com.example.sscan.R.string.dialog_edit_record_cancel),
                null
            )
            .show()
    }

    private fun showRenameFolderDialog(folderName: String, recordIds: List<Long>) {
        val dialog = AlertDialog.Builder(this).create()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
            background = getDrawable(com.example.sscan.R.drawable.bg_dialog_card)
        }
        val title = TextView(this).apply {
            text = getString(com.example.sscan.R.string.history_edit_rename_folder)
            setTextColor(getColor(com.example.sscan.R.color.text_primary))
            textSize = 18f
            paint.isFakeBoldText = true
        }
        content.addView(title)

        val input = EditText(this).apply {
            setText(folderName)
            setBackgroundColor(getColor(com.example.sscan.R.color.surface_subtle))
            setPadding(40, 24, 40, 24)
            setTextColor(getColor(com.example.sscan.R.color.text_primary))
            textSize = 14f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 30
            layoutParams = lp
        }
        content.addView(input)

        val btnBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 30
            layoutParams = lp
        }
        val cancel = TextView(this).apply {
            text = getString(com.example.sscan.R.string.dialog_edit_record_cancel)
            setPadding(50, 24, 50, 24)
            setTextColor(getColor(com.example.sscan.R.color.text_secondary))
            textSize = 14f
            setOnClickListener { dialog.dismiss() }
        }
        val confirm = TextView(this).apply {
            text = getString(com.example.sscan.R.string.dialog_edit_record_confirm)
            setPadding(50, 24, 50, 24)
            setTextColor(getColor(com.example.sscan.R.color.brand_success))
            textSize = 14f
            paint.isFakeBoldText = true
            setOnClickListener {
                val newName = input.text?.toString()?.trim().orEmpty()
                if (newName.isBlank()) {
                    showToast(getString(com.example.sscan.R.string.history_edit_enter_folder_name))
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    viewModel.renameGroup(folderName, newName)
                    withContext(Dispatchers.Main) {
                        showToast(getString(com.example.sscan.R.string.history_edit_renamed))
                        knownGroupNames.remove(folderName)
                        knownGroupNames.add(newName)
                        if (activeFolderName == folderName) activeFolderName = newName
                        editSelectedFolderNames.remove(folderName)
                        if (newName.isNotBlank()) editSelectedFolderNames.add(newName)
                        if (isEditMode) {
                            renderEditModeList()
                        } else {
                            renderList(viewModel.uiState.value)
                        }
                        dialog.dismiss()
                    }
                }
            }
        }
        btnBar.addView(cancel)
        btnBar.addView(confirm)
        content.addView(btnBar)

        dialog.setView(content)
        dialog.show()
    }

    private fun showConfirmDeleteFolderDialog(folderName: String, recordIds: List<Long>) {
        AlertDialog.Builder(this)
            .setTitle(getString(com.example.sscan.R.string.history_edit_confirm_delete_folder_title))
            .setMessage(
                getString(
                    com.example.sscan.R.string.history_edit_confirm_delete_folder,
                    folderName
                )
            )
            .setPositiveButton(getString(com.example.sscan.R.string.settings_dialog_confirm)) { _, _ ->
                lifecycleScope.launch {
                    viewModel.deleteGroup(folderName, recordIds)
                    withContext(Dispatchers.Main) {
                        showToast(getString(com.example.sscan.R.string.history_edit_folder_deleted))
                        knownGroupNames.remove(folderName)
                        if (activeFolderName == folderName) activeFolderName = null
                        editSelectedFolderNames.remove(folderName)
                        recordIds.forEach { editSelectedRecordIds.remove(it) }
                        if (isEditMode) {
                            renderEditModeList()
                        } else {
                            renderList(viewModel.uiState.value)
                        }
                        updateEditSelectionUI()
                    }
                }
            }
            .setNegativeButton(
                getString(com.example.sscan.R.string.dialog_edit_record_cancel),
                null
            )
            .show()
    }

    private fun showConfirmDeleteRecordDialog(recordId: Long) {
        AlertDialog.Builder(this)
            .setTitle(getString(com.example.sscan.R.string.history_edit_confirm_delete))
            .setMessage(getString(com.example.sscan.R.string.history_edit_confirm_delete_desc))
            .setPositiveButton(getString(com.example.sscan.R.string.dialog_edit_record_confirm)) { _, _ ->
                lifecycleScope.launch {
                    viewModel.moveSelectedToTrash(listOf(recordId))
                    withContext(Dispatchers.Main) {
                        showToast(getString(com.example.sscan.R.string.history_edit_record_deleted))
                        editSelectedRecordIds.remove(recordId)
                        updateEditSelectionUI()
                    }
                }
            }
            .setNegativeButton(
                getString(com.example.sscan.R.string.dialog_edit_record_cancel),
                null
            )
            .show()
    }


    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showToast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun showShareFileDialog(saved: MediaStoreDocumentSaver.SavedDocument) {
        val options = arrayOf(
            getString(com.example.sscan.R.string.history_open_with_wps),
            getString(com.example.sscan.R.string.history_share_file)
        )

        // API 29+ 落在系统「下载」，文件管理器可见、卸载不丢；老机器仍落私有目录，
        // 所以标题要如实说明落点，否则用户会去「下载」里找一个不存在的文件。
        val title = if (saved.savedToDownloads) {
            getString(com.example.sscan.R.string.history_export_saved_downloads, saved.displayName)
        } else {
            getString(com.example.sscan.R.string.history_export_success, saved.displayName)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openWithWps(saved)
                    1 -> shareFile(saved)
                }
            }
            .setPositiveButton(com.example.sscan.R.string.settings_dialog_confirm, null)
            .show()
    }

    private fun openWithWps(saved: MediaStoreDocumentSaver.SavedDocument) {
        val wpsPackages = listOf(
            "cn.wps.moffice_eng",
            "cn.wps.moffice_i18n",
            "cn.wps.wpsconnect.me",
            "com.kingsoft.writer.pad",
            "com.kingsoft.writer",
            "com.kingsoft.wps.moffice_eng",
            "com.kingsoft.wps.moffice_i18n",
            "com.kingsoft.wps.pad.english",
            "com.wps.caiji"
        )
        var launched = false
        val mimeType = saved.mimeType
        val fileUri = saved.uri
        for (pkg in wpsPackages) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, mimeType)
                setPackage(pkg)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                launched = true
                break
            }
        }
        if (!launched) {
            val fallback = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (fallback.resolveActivity(packageManager) != null) {
                startActivity(
                    Intent.createChooser(
                        fallback,
                        getString(com.example.sscan.R.string.history_open_file)
                    )
                )
            } else {
                showToast(com.example.sscan.R.string.history_wps_not_found)
            }
        }
    }

    private fun shareFile(saved: MediaStoreDocumentSaver.SavedDocument) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = saved.mimeType
            putExtra(Intent.EXTRA_STREAM, saved.uri)
            putExtra(
                Intent.EXTRA_TEXT,
                getString(com.example.sscan.R.string.history_share_file_body, saved.displayName)
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(
            sendIntent,
            getString(com.example.sscan.R.string.history_share_file)
        )
        if (sendIntent.resolveActivity(packageManager) != null) {
            startActivity(chooser)
        } else {
            showToast(com.example.sscan.R.string.history_share_failed)
        }
    }


    private fun getBarcodeTypeLabel(result: String): String {
        return if (result.startsWith("http", ignoreCase = true)) {
            getString(com.example.sscan.R.string.scan_result_badge)
        } else {
            getString(com.example.sscan.R.string.history_barcode_ean)
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private enum class ExportMode(val extension: String) {
        CONTENT_TEXT("txt"),
        CONTENT_TABLE("xlsx"),
        DETAIL_TABLE("xlsx"),
        DETAIL_TABLE_WITH_IMAGES("xlsx")
    }

    private data class ExportImage(
        val bytes: ByteArray,
        val extension: String
    )

    companion object {
        private const val TAB_SCAN = 0
        private const val TAB_CONVERT = 1
    }
}
