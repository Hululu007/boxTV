package com.example.boxtv.ui.browser

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import com.example.boxtv.BoxTvApp
import com.example.boxtv.R
import com.example.boxtv.data.remote.ApiResult
import com.example.boxtv.model.Folder
import com.example.boxtv.model.PairedHost
import com.example.boxtv.model.VideoEntry
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.example.boxtv.ui.common.NetworkName
import com.example.boxtv.ui.common.bindFocusHighlight
import com.example.boxtv.ui.common.bindSidebarFocusScale
import com.example.boxtv.ui.common.resetFocusHighlight
import com.example.boxtv.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 电脑端文件浏览页（ui/browser 层）—— 2026-11-07 参考 UI 设计稿改版
 *
 * 与主页统一框架：左侧边栏（主页/设置）+ 头部状态栏（网络/主机/连接状态 + 搜索/刷新）
 * + 内容网格。对应设计稿「boxTV主机-内容列表」。
 *
 * 结构与导航：
 * - 根目录：网格显示电脑端授权的文件夹图块；
 * - 进入文件夹：显示子文件夹与视频（图块卡片，视频用 Glide 加载关键帧缩略图）；
 * - 面包屑：主机名-文件夹1-文件夹2-…（进文件夹逐级追加，方便用户定位当前位置）；
 * - 返回键：搜索结果 → 退出搜索；子目录 → 逐级回退；根目录 → 退出浏览页；
 * - 搜索按钮：在当前文件夹内递归搜索视频；根目录下搜索则合并所有授权文件夹的结果；
 * - 连接状态：由授权文件夹列表加载结果推导（成功=正常，网络错误=已断开）；
 * - 错误兜底：401（授权失效）→ 提示重新配对；网络错误 → 面包屑提示。
 */
class HostBrowserActivity : FragmentActivity() {

    companion object {
        /** Intent Extra：目标主机唯一标识 */
        const val EXTRA_HOST_DEVICE_ID = "hostDeviceId"

        /** 网格列数（参照设计稿，与主页一致） */
        private const val GRID_SPAN = 5
    }

    private lateinit var host: PairedHost
    private lateinit var repository: com.example.boxtv.data.repository.HostRepository

    // ---- 视图 ----
    private lateinit var sidebarHome: TextView
    private lateinit var sidebarSettings: TextView
    private lateinit var tvNetwork: TextView
    private lateinit var tvConnection: TextView
    private lateinit var breadcrumb: TextView
    private lateinit var contentGrid: RecyclerView

    /** 顶栏容器（状态栏 + 面包屑）：随网格滚动联动收起/恢复（2026-11-07 用户需求） */
    private lateinit var headerContainer: View

    private lateinit var contentAdapter: ContentAdapter

    /** 授权文件夹列表 */
    private var folders: List<Folder> = emptyList()

    /** 当前所在授权文件夹 id（null = 根目录：网格显示授权文件夹列表） */
    private var selectedFolderId: String? = null

    /** 目录导航栈（当前授权文件夹内的相对路径逐层进入，返回键逐级回退） */
    private val pathStack = ArrayDeque<String>()

    /** 搜索关键字（非空 = 搜索结果展示中，返回键先退出搜索） */
    private var searchKeyword: String? = null

    // ---- 排序（2026-11-07 用户需求：头部「排序」按钮 + 下拉框选择排序方式） ----

    /**
     * 排序方式列表（下标与 sortComparator 分支一一对应，勿乱序）：
     * 0 名称排序（自然排序：数字段按数值、中英文按拼音）
     * 1 创建时间排序（修改时间从新到旧）
     * 2 文件大小排序（从大到小）
     * 3 数字从大到小（名称中第一个数字，无数字排末尾）
     * 4 数字从小到大
     * 5 字母（A-Z）正序（中英文混合按拼音序）
     * 6 字母（Z-A）倒序
     */
    private val sortModes = listOf(
        "名称排序", "创建时间排序", "文件大小排序",
        "数字从大到小", "数字从小到大",
        "字母（A-Z）正序", "字母（Z-A）倒序"
    )

    /** 当前排序方式下标（默认名称排序；Activity 存活期内有效，不持久化） */
    private var currentSortIndex = 0

    /** 最近一次加载成功的条目列表：切换排序时本地重排，避免重复请求网络 */
    private var currentEntries: List<VideoEntry> = emptyList()

    /** 头部「排序」按钮（下拉框锚点） */
    private lateinit var btnSort: TextView

    /** 首次成功加载后是否已完成初始聚焦（浏览页打开时光标落在第 0 卡） */
    private var initialFocusDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        val hostId = intent.getStringExtra(EXTRA_HOST_DEVICE_ID)
        val found = (application as BoxTvApp).repository.pairedHosts.value
            .firstOrNull { it.hostDeviceId == hostId }
        if (hostId == null || found == null) {
            Toast.makeText(this, "主机信息缺失，请从主页重新进入", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        host = found
        repository = (application as BoxTvApp).repository

        // ---- 视图绑定 ----
        sidebarHome = findViewById(R.id.sidebar_home)
        sidebarSettings = findViewById(R.id.sidebar_settings)
        tvNetwork = findViewById(R.id.tv_network)
        findViewById<TextView>(R.id.tv_host_name).text = "电脑主机名称：${host.name}"
        tvConnection = findViewById(R.id.tv_connection)
        breadcrumb = findViewById(R.id.breadcrumb)
        headerContainer = findViewById(R.id.header_container)
        contentGrid = findViewById(R.id.content_grid)

        // ---- 侧边栏：聚焦动效 + 页面跳转（浏览页属于「主页」分区） ----
        sidebarHome.bindSidebarFocusScale()
        sidebarSettings.bindSidebarFocusScale()
        sidebarHome.isSelected = true
        // 主页菜单 → 返回主页（浏览页本身在主页分区，OK 即返回）
        sidebarHome.setOnClickListener { finish() }
        // 设置菜单 → 返回主页并打开设置页（设置内容在主页的设置面板中）
        sidebarSettings.setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_SETTINGS)
            )
            finish()
        }

        // ---- 头部按钮：搜索（当前文件夹内容）/ 刷新 ----
        findViewById<TextView>(R.id.btn_search).apply {
            bindFocusHighlight()
            setOnClickListener { showSearchDialog() }
        }
        findViewById<TextView>(R.id.btn_refresh).apply {
            bindFocusHighlight()
            setOnClickListener { refreshAll() }
        }
        // 排序按钮：弹出排序方式下拉框（ListPopupWindow，遥控器 DPAD 可导航）
        btnSort = findViewById<TextView>(R.id.btn_sort).apply {
            bindFocusHighlight()
            setOnClickListener { showSortMenu() }
        }

        // ---- 当前网络名称（子线程解析，与主页同源） ----
        lifecycleScope.launch(Dispatchers.IO) {
            val name = NetworkName.resolve(this@HostBrowserActivity)
            withContext(Dispatchers.Main) { tvNetwork.text = "当前网络：$name" }
        }

        // ---- 内容网格（5 列，与主页一致） ----
        contentAdapter = ContentAdapter(
            onFolderClick = { entry -> enterFolder(entry) },
            onVideoClick = { entry -> openVideo(entry) }
        )
        contentGrid.layoutManager = GridLayoutManager(this, GRID_SPAN)
        contentGrid.adapter = contentAdapter

        // 顶栏随网格滚动联动收起/恢复（2026-11-07 用户需求：往下选片时整个界面一起滚动查看）：
        // - 网格向下滚动（遥控器下移/触摸上滑）→ 顶栏整体让出屏幕，网格占满全屏；
        // - 滚回顶部（GONE 的顶栏不参与焦点搜索，遥控器上移到第 0 行自然滚回顶部）→ 顶栏恢复；
        // - 内容不足一屏时 scrollOffset 恒为 0，顶栏常显。
        // 用 GONE/VISIBLE 而非逐帧平移：布局高度真实变化，网格收起后能占满顶栏让出的空间（无空洞），
        // 且保留 RecyclerView 虚拟化（ScrollView 全包会让大文件夹全量渲染，有卡顿/内存风险）
        contentGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                when {
                    // 回到/停在顶部：顶栏恢复
                    contentGrid.computeVerticalScrollOffset() <= 0 -> setHeaderVisible(true)
                    // 正在向下滚动：顶栏收起
                    dy > 0 -> setHeaderVisible(false)
                }
            }
        })

        // 进入页面即加载授权文件夹
        loadFolders()

        // ---- 返回键：逐级回退导航（见 handleBackNavigation 注释的根因说明） ----
        // Android 13+ 上返回键走 OnBackInvokedCallback 调度，必须用 Dispatcher 注册，
        // 旧式 onBackPressed() 覆盖会被绕过导致"按返回直接退出浏览页"
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 逐级回退命中则消费本次返回；否则放行（finish 退出浏览页）
                if (!handleBackNavigation()) {
                    finish()
                }
            }
        })
    }

    /** 顶栏显示/收起（避免每帧重复设置触发多余重布局） */
    private fun setHeaderVisible(visible: Boolean) {
        val target = if (visible) View.VISIBLE else View.GONE
        if (headerContainer.visibility != target) {
            headerContainer.visibility = target
        }
    }

    /**
     * 返回键导航（2026-11-07 修复：三级及以上目录按返回直接回主页）。
     *
     * 根因：targetSdk 36 在 Android 13+ 设备上，系统返回键走
     * OnBackInvokedCallback 调度路径（androidx OnBackPressedDispatcher 机制），
     * 旧式 onBackPressed() 覆盖不会被调用，系统直接 finish 页面 → 回到主页。
     *
     * 修复：改用 OnBackPressedDispatcher 注册 OnBackPressedCallback（新旧系统
     * 路径均可靠）。逐级回退顺序：搜索结果 → 子目录 → 根目录 → 退出浏览页。
     */
    private fun handleBackNavigation(): Boolean = when {
        searchKeyword != null -> {
            searchKeyword = null
            loadEntries()
            true
        }
        pathStack.isNotEmpty() -> {
            pathStack.removeLast()
            loadEntries()
            true
        }
        // 已在授权文件夹根层：退回浏览页根目录（授权文件夹列表）
        selectedFolderId != null -> {
            selectedFolderId = null
            pathStack.clear()
            loadEntries()
            true
        }
        // 浏览页根目录：放行默认行为（退出浏览页回主页）
        else -> false
    }

    // ------------------------------------------------------------------
    // 导航
    // ------------------------------------------------------------------

    /** 进入文件夹：根目录图块 → 进入对应授权文件夹；网格内文件夹 → 压入路径栈 */
    private fun enterFolder(entry: VideoEntry) {
        if (selectedFolderId == null) {
            // 根目录的授权文件夹图块：videoId 携带文件夹 id
            selectedFolderId = entry.videoId
            pathStack.clear()
        } else {
            pathStack.addLast(entry.name)
        }
        loadEntries()
    }

    /**
     * 视频卡片 → 直接进入播放页（2026-11-07 播放链路简化）。
     * 原流程「缩略图 → 详情页 → 点播放按钮」两级跳改为点击即播；
     * 断点续播位置由播放页自动判断（进度 5%~95% 之间自动续播）。
     * M3U8 聚合条目（电脑端隐藏原文件夹、聚合出的虚拟条目）携带
     * EXTRA_IS_M3U8 标记，播放页走 HLS 原生直连分支，点击即播完整序列。
     */
    private fun openVideo(entry: VideoEntry) {
        val vid = entry.videoId ?: return
        startActivity(
            Intent(this, com.example.boxtv.ui.playback.PlaybackActivity::class.java)
                .putExtra(com.example.boxtv.ui.playback.PlaybackActivity.EXTRA_HOST_DEVICE_ID, host.hostDeviceId)
                .putExtra(com.example.boxtv.ui.playback.PlaybackActivity.EXTRA_VIDEO_ID, vid)
                .putExtra(com.example.boxtv.ui.playback.PlaybackActivity.EXTRA_NAME, entry.name)
                .putExtra(com.example.boxtv.ui.playback.PlaybackActivity.EXTRA_IS_M3U8, entry.isM3u8)
        )
    }

    // ------------------------------------------------------------------
    // 数据加载
    // ------------------------------------------------------------------

    /** 加载授权文件夹列表（刷新按钮与页面进入共用；fromRefresh 仅用于成功提示） */
    private fun loadFolders(fromRefresh: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            when (val result = repository.folders(host)) {
                is ApiResult.Ok -> {
                    folders = result.data
                    // 当前授权文件夹已被电脑端移除：回到根目录并清空导航与搜索状态
                    if (folders.none { it.id == selectedFolderId }) {
                        selectedFolderId = null
                        pathStack.clear()
                        searchKeyword = null
                    }
                    withContext(Dispatchers.Main) {
                        // 连接状态由本次请求结果推导（无需额外 ping）
                        tvConnection.text = "连接状态：正常"
                        if (folders.isEmpty()) {
                            breadcrumb.text = "${host.name}（电脑端尚未授权任何文件夹）"
                            contentAdapter.submit(emptyList())
                            setHeaderVisible(true)
                        } else {
                            loadEntries()
                        }
                        if (fromRefresh) {
                            Toast.makeText(this@HostBrowserActivity, "已刷新，内容已同步最新", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                is ApiResult.ApiError -> withContext(Dispatchers.Main) { handleError(result) }
                is ApiResult.NetworkError -> withContext(Dispatchers.Main) {
                    tvConnection.text = "连接状态：已断开"
                    breadcrumb.text = "网络错误：${result.reason}"
                    contentAdapter.submit(emptyList())
                    setHeaderVisible(true)
                }
            }
        }
    }

    /**
     * 刷新（头部「刷新」按钮）：重新拉取授权文件夹列表 + 当前目录内容，
     * 连接状态随请求结果同步更新。
     */
    private fun refreshAll() {
        breadcrumb.text = "刷新中…"
        loadFolders(fromRefresh = true)
    }

    /** 面包屑文本：主机名-文件夹1-文件夹2-…（搜索时追加搜索词） */
    private fun buildBreadcrumb(): String {
        val sb = StringBuilder(host.name)
        selectedFolderId?.let { id ->
            folders.firstOrNull { it.id == id }?.name?.let { folderName ->
                sb.append("-").append(folderName)
            }
            pathStack.forEach { segment ->
                sb.append("-").append(segment)
            }
        }
        searchKeyword?.let { kw ->
            sb.append("-搜索「").append(kw).append("」")
        }
        return sb.toString()
    }

    /** 加载当前层级内容（根目录=授权文件夹列表 / 文件夹内容 / 搜索结果） */
    private fun loadEntries() {
        val folderId = selectedFolderId
        val keyword = searchKeyword
        breadcrumb.text = buildBreadcrumb()

        lifecycleScope.launch(Dispatchers.IO) {
            val result = when {
                // 搜索：在当前文件夹内递归；根目录下并行搜索所有授权文件夹后合并
                keyword != null -> {
                    if (folderId != null) {
                        repository.search(host, folderId, keyword)
                    } else {
                        val deferreds = folders.map { folder ->
                            async { repository.search(host, folder.id, keyword) }
                        }
                        ApiResult.Ok(
                            deferreds.awaitAll().flatMap { r ->
                                (r as? ApiResult.Ok)?.data ?: emptyList()
                            }
                        )
                    }
                }
                // 文件夹内容（相对路径逐层）
                folderId != null -> repository.browse(host, folderId, pathStack.joinToString("/"))
                // 根目录：授权文件夹列表 → 转为文件夹图块条目（videoId 携带文件夹 id）
                else -> ApiResult.Ok(
                    folders.map { VideoEntry(name = it.name, type = "dir", videoId = it.id) }
                )
            }
            if (isFinishing || isDestroyed) return@launch // 页面已销毁才中止（注意方向不能写反）
            when (result) {
                is ApiResult.Ok -> withContext(Dispatchers.Main) {
                    // 先记录提交前网格是否持有焦点（notifyDataSetChanged 的焦点清理
                    // 发生在随后的布局 pass，同步检查会误判为"网格仍持有焦点"）
                    val gridHadFocus = contentGrid.hasFocus()
                    // 缓存本次数据供「排序」切换时本地重排（不重复请求网络），
                    // 按当前排序方式排序后提交给网格
                    currentEntries = result.data
                    contentAdapter.submit(applySort(result.data))
                    // 内容刷新后网格回到顶部且 onScrolled 不会回调，手动恢复顶栏显示
                    // （换文件夹/进入子目录/搜索/返回上级均走此处）
                    setHeaderVisible(true)
                    // 数据集变化（notifyDataSetChanged）可能使原聚焦卡片被回收导致焦点丢失
                    // （实测落到侧边栏）：提交前网格持有焦点、布局完成后丢失时，
                    // 恢复到第 0 个卡片，保证遥控器导航连续；
                    // 提交前网格本就无焦点（如焦点在头部按钮上刷新）则不抢焦点。
                    // 另：页面刚打开时系统会把窗口焦点自动赋给第一个可聚焦控件
                    // （侧边栏「主页」）——首次成功加载后聚焦第 0 卡，
                    // 让浏览页一打开光标就落在内容上。
                    // 注意：post 回调可能早于 notifyDataSetChanged 的布局 pass
                    // （此时取不到 ViewHolder），用 postDelayed 确保布局完成，
                    // 且仅在成功聚焦时置位 initialFocusDone，失败不吞掉后续机会
                    if (result.data.isNotEmpty()) {
                        contentGrid.postDelayed({
                            val vh = contentGrid.findViewHolderForAdapterPosition(0)
                            val needFocus = !initialFocusDone ||
                                (gridHadFocus && !contentGrid.hasFocus())
                            if (vh != null && needFocus) {
                                initialFocusDone = true
                                vh.itemView.findViewById<FrameLayout>(R.id.tile).requestFocus()
                            }
                        }, 200)
                    }
                    if (result.data.isEmpty()) {
                        val hint = when {
                            keyword != null -> "（无匹配内容）"
                            folderId != null -> "（空文件夹）"
                            else -> "（电脑端尚未授权任何文件夹）"
                        }
                        breadcrumb.text = buildBreadcrumb() + "　" + hint
                    }
                }
                is ApiResult.ApiError -> withContext(Dispatchers.Main) { handleError(result) }
                is ApiResult.NetworkError -> withContext(Dispatchers.Main) {
                    contentAdapter.submit(emptyList())
                    breadcrumb.text = "网络错误：${result.reason}"
                }
            }
        }
    }

    /** API 错误兜底：401 → 引导重新配对；其余 → 面包屑提示 */
    private fun handleError(error: ApiResult.ApiError) {
        if (error.isUnauthorized) {
            tvConnection.text = "连接状态：已断开"
            Toast.makeText(this, "授权已失效，请在电脑端重新配对（设置页-添加主机）", Toast.LENGTH_LONG).show()
            finish()
        } else {
            breadcrumb.text = "错误 ${error.code}：${error.message}"
        }
    }

    /** 弹出搜索输入框（遥控器 IME）：搜索当前文件夹内的视频名称 */
    private fun showSearchDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "输入视频文件名关键字"
            setSingleLine(true)
        }
        val container = android.widget.FrameLayout(this).apply {
            val pad = (resources.displayMetrics.density * 24).toInt()
            setPadding(pad, pad, 0, 0)
            addView(input)
        }
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("搜索当前文件夹")
            .setView(container)
            .setPositiveButton("搜索") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) {
                    searchKeyword = q
                    loadEntries()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================================================================
    // 排序（2026-11-07 用户需求：排序下拉框 + 7 种排序方式）
    // ==================================================================

    /**
     * 弹出排序方式下拉框（锚定头部「排序」按钮）。
     * 用 ListPopupWindow + 自定义选项布局（深灰底/聚焦提亮）：
     * 弹窗是独立窗口，遥控器 DPAD 上下移动、OK 确认、BACK 关闭都由其自身处理，
     * 不会与本页返回键逐级回退逻辑冲突；当前排序项前加「✓」标记。
     */
    private fun showSortMenu() {
        val popup = androidx.appcompat.widget.ListPopupWindow(this)
        popup.anchorView = btnSort
        popup.setModal(true) // 模态：弹出时拦截按键/点击，BACK 仅关闭下拉框
        val density = resources.displayMetrics.density
        popup.width = (density * 230).toInt()
        // 右对齐锚点：排序按钮靠近屏幕右侧，默认左对齐会让下拉框溢出屏幕
        popup.horizontalOffset = -(popup.width - btnSort.width)
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF2E2E2E.toInt()))
        // 选项文本：当前选中项前加 ✓ 标记（其余原样显示）
        val labels = sortModes.mapIndexed { i, mode ->
            if (i == currentSortIndex) "✓ $mode" else mode
        }
        popup.setAdapter(
            android.widget.ArrayAdapter(
                this, R.layout.item_sort_option, R.id.tv_sort_option, labels
            )
        )
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            if (position != currentSortIndex) {
                currentSortIndex = position
                applySortToCurrent()
                Toast.makeText(this, "已按「${sortModes[position]}」排序", Toast.LENGTH_SHORT).show()
            }
        }
        popup.show()
    }

    /**
     * 切换排序方式后对已加载内容本地重排并刷新网格（不重新请求网络）。
     * 焦点保持策略与 loadEntries 相同：提交前网格持有焦点、布局完成后
     * 因数据集变化丢失时，恢复焦点到第 0 卡，保证遥控器导航连续。
     */
    private fun applySortToCurrent() {
        if (currentEntries.isEmpty()) return
        val gridHadFocus = contentGrid.hasFocus()
        contentAdapter.submit(applySort(currentEntries))
        setHeaderVisible(true)
        if (gridHadFocus) {
            // postDelayed 确保布局 pass 完成后再检查/恢复焦点（同 loadEntries 的坑）
            contentGrid.postDelayed({
                val vh = contentGrid.findViewHolderForAdapterPosition(0)
                if (vh != null && !contentGrid.hasFocus()) {
                    vh.itemView.findViewById<FrameLayout>(R.id.tile).requestFocus()
                }
            }, 200)
        }
    }

    /**
     * 按当前排序方式对条目列表排序。
     *
     * 分组规则：文件夹（dir）始终排在视频前面、两组内分别排序——
     * 文件夹没有大小/时间语义（size=0），混排会让「文件大小排序」出现
     * 一串 size=0 的文件夹干扰视频定位；分组后各组排序语义更清晰
     * （名称/字母排序下文件夹也集中在网格开头，便于定位）。
     */
    private fun applySort(list: List<VideoEntry>): List<VideoEntry> {
        val cmp = sortComparator() ?: return list
        return list.sortedWith(
            compareBy<VideoEntry> { !it.isDir } // false(文件夹) 排前
                .then(cmp)
        )
    }

    /**
     * 当前排序方式对应的比较器（null = 不排序）。
     * 下标含义见 sortModes 注释；中文一律走 Collator（拼音序），
     * 避免默认按 Unicode 码点比较导致中文乱序。
     */
    private fun sortComparator(): Comparator<VideoEntry>? = when (currentSortIndex) {
        // 名称排序：自然排序——数字段按数值比较（「第2集」<「第10集」），
        // 非数字段按 Collator 拼音序
        0 -> Comparator { a, b -> naturalCompare(a.name, b.name) }
        // 创建时间排序：修改时间从新到旧（最近添加的在前）
        1 -> compareByDescending<VideoEntry> { it.modifiedAt }
        // 文件大小排序：从大到小
        2 -> compareByDescending<VideoEntry> { it.size }
        // 数字从大到小 / 从小到大：按名称中第一个数字段数值比较
        3 -> numberComparator(descending = true)
        4 -> numberComparator(descending = false)
        // 字母（A-Z）正序 / （Z-A）倒序：Collator 拼音序
        5 -> compareBy(nameCollator) { it.name }
        6 -> compareByDescending(nameCollator) { it.name }
        else -> null
    }

    /** 名称比较用的中文 Collator（拼音序，中英文混合排序一致） */
    private val nameCollator by lazy {
        java.text.Collator.getInstance(java.util.Locale.CHINA)
    }

    /**
     * 数字排序比较器：提取名称中的第一个连续数字段作为排序键
     * （如「第02集.mp4」→ 2），无数字的条目固定排在末尾。
     */
    private fun numberComparator(descending: Boolean): Comparator<VideoEntry> =
        Comparator { a, b ->
            val na = firstNumber(a.name)
            val nb = firstNumber(b.name)
            when {
                na == null && nb == null -> 0
                na == null -> 1  // 无数字排末尾（两个方向一致）
                nb == null -> -1
                descending -> nb.compareTo(na)
                else -> na.compareTo(nb)
            }
        }

    /** 提取名称中的第一个连续数字段（无数字返回 null） */
    private fun firstNumber(name: String): Long? =
        Regex("\\d+").find(name)?.value?.toLongOrNull()

    /**
     * 自然排序比较（名称排序用）：把名称切成「数字段/非数字段」逐段比较——
     * 数字段与数字段按数值比较，其余按 Collator 拼音序比较；
     * 全部段相等时较短的在前。
     */
    private fun naturalCompare(a: String, b: String): Int {
        val chunksA = NUMBER_OR_TEXT.findAll(a).toList()
        val chunksB = NUMBER_OR_TEXT.findAll(b).toList()
        for (i in 0 until minOf(chunksA.size, chunksB.size)) {
            val xa = chunksA[i].value
            val xb = chunksB[i].value
            val cmp = if (xa[0].isDigit() && xb[0].isDigit()) {
                // 双方都是数字段：按数值比较（去掉前导零影响）
                xa.toLong().compareTo(xb.toLong())
            } else {
                nameCollator.compare(xa, xb)
            }
            if (cmp != 0) return cmp
        }
        return chunksA.size.compareTo(chunksB.size)
    }

    /** 自然排序分段正则：连续数字 或 连续非数字 */
    private val NUMBER_OR_TEXT = Regex("\\d+|\\D+")

    // ==================================================================
    // 内容网格适配器（根目录授权文件夹 / 子文件夹 / 视频卡片，样式与主页一致）
    // ==================================================================

    private inner class ContentAdapter(
        private val onFolderClick: (VideoEntry) -> Unit,
        private val onVideoClick: (VideoEntry) -> Unit
    ) : RecyclerView.Adapter<ContentAdapter.VH>() {

        private var entries: List<VideoEntry> = emptyList()

        fun submit(list: List<VideoEntry>) {
            entries = list
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val root = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_content_card, parent, false)
            val tile = root.findViewById<FrameLayout>(R.id.tile)
            // 遥控器焦点光标：白环（背景 selector）+ 轻微放大（FocusHighlight）；
            // clipToOutline 让视频缩略图跟随图块圆角裁切
            tile.clipToOutline = true
            tile.bindFocusHighlight(withForeground = false)
            return VH(root)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = entries[position]
            // 复用重置：清掉上一个数据残留的放大/抬升状态
            holder.tile.resetFocusHighlight()
            // 第 0 行卡片：上键显式直达头部「搜索」按钮（与主页同理，
            // 否则几何焦点搜索可能落回侧边栏，头部按钮不可达）
            holder.tile.nextFocusUpId =
                if (position < GRID_SPAN) R.id.btn_search else View.NO_ID
            holder.name.text = entry.name
            if (entry.isDir) {
                // 文件夹图块：固定文件夹图标
                holder.thumb.visibility = View.GONE
                holder.folderIcon.visibility = View.VISIBLE
            } else {
                // 视频图块：Glide 异步加载电脑端 ffmpeg 生成的关键帧
                // - 通过 GlideUrl 携带 X-BoxTV-Token 鉴权头（Token 不落 URL，避免泄露到日志）
                // - placeholder/error：生成失败或 404 时降级占位图（细则 4.4）
                // - RecyclerView 复用时 into() 自动取消上一次请求，无需手动管理
                holder.thumb.visibility = View.VISIBLE
                holder.folderIcon.visibility = View.GONE
                val vid = entry.videoId
                if (vid != null) {
                    val glideUrl = GlideUrl(
                        repository.thumbUrl(host, vid),
                        LazyHeaders.Builder()
                            .addHeader("X-BoxTV-Token", host.token)
                            .build()
                    )
                    Glide.with(holder.tile)
                        .load(glideUrl)
                        .placeholder(R.drawable.movie)
                        .error(R.drawable.movie)
                        .centerCrop()
                        .into(holder.thumb)
                } else {
                    holder.thumb.setImageResource(R.drawable.movie)
                }
            }
            holder.tile.setOnClickListener {
                if (entry.isDir) onFolderClick(entry) else onVideoClick(entry)
            }
        }

        inner class VH(root: View) : RecyclerView.ViewHolder(root) {
            val tile: FrameLayout = root.findViewById(R.id.tile)
            val thumb: ImageView = root.findViewById(R.id.iv_thumb)
            val folderIcon: ImageView = root.findViewById(R.id.iv_folder)
            val name: TextView = root.findViewById(R.id.tv_name)
        }
    }
}
