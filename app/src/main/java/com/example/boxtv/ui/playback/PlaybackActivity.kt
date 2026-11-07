package com.example.boxtv.ui.playback

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.ForwardingPlayer
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.example.boxtv.BoxTvApp
import com.example.boxtv.R
import com.example.boxtv.data.remote.ApiResult
import com.example.boxtv.data.remote.VideoIdCodec
import com.example.boxtv.model.PlaybackProgress
import com.example.boxtv.model.VideoEntry
import com.example.boxtv.model.VideoMeta
import com.example.boxtv.ui.common.bindFocusHighlight
import com.example.boxtv.ui.common.resetFocusHighlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 视频播放页（ui/playback 层）—— 2026-11-07 播放器升级
 *
 * 播放链路简化：浏览页点击缩略图直接进入本页（详情页已移除），
 * 断点续播位置由本页自动判断（进度 5%~95% 之间续播，细则 5.3 规则）。
 *
 * 播放器菜单面板（遥控器「菜单」键呼出/关闭）：
 * - 播放信息：视频名称 / 播放进度 / 分辨率·清晰度·播放状态（1 秒刷新）；
 * - 清晰度：1080P（原画直连，默认）/ 720P / 480P（电脑端 ffmpeg 实时转码）；
 * - 选集：列出当前文件夹内的视频，点击直接切换播放。
 *
 * 清晰度与电脑端协议（对应 HostServer /api/video/stream）：
 * - 1080P：源片高度 ≤ 1080 → 原文件直连（Range 秒开拖动，零转码）；
 *   源片 > 1080（如 4K）→ 电脑端转码压到 1080p；
 * - 720P/480P：转码流（fMP4 管道推流，无 Range）。转码流无法按字节拖动，
 *   采用「重新起播」模型：拖动进度条/切清晰度时以新起点重新请求，
 *   电脑端 -ss 精确寻址重启 ffmpeg。
 *
 * 转码模式实现要点：
 * - 电脑端转码流不携带总时长 → 用 /api/video/meta 的时长做
 *   ClippingMediaSource 窗口裁剪，进度条时长与原片一致；
 * - [seekProxy]（ForwardingPlayer）接管进度条：对内把「窗口内位置」补偿为
 *   「原片时间线位置」（加回流起点），对外把 seek 拦截为「换起点重新起播」，
 *   控制条、进度保存、菜单信息展示均感知不到分段流的细节；
 * - 上次选择的清晰度持久化（默认 1080P）。
 *
 * M3U8 播放分支（2026-11-07 M3U8 播放支持）：
 * - 电脑端聚合的 M3U8 虚拟条目经 EXTRA_IS_M3U8 标记进入 HLS 原生直连：
 *   电脑端已把播放列表内相对 URI 改写为 /api/video/part 绝对地址，
 *   ExoPlayer 原生 HLS 播放器直接消费（秒开 + 原生精准 seek，电视端零解析负载）；
 * - 选集 / 断点续播 / 播放暂停 / 快进快退与 MP4 完全一致；
 * - 清晰度面板 720P/480P 置灰（HLS 原生流不支持电脑端转码档）。
 *
 * 播放体验专项（2026-11-07）：
 * - 遥控器左/右方向键：短按步进 10 秒，长按持续步进快进/快退
 *   （转码模式长按只累计偏移，松开按键一次性换起点重播）；
 * - 音量增益 0-200%：PCM 层增益处理器（GainAudioProcessor），菜单面板
 *   「− / 增益值 / ＋」三件套可调（2026-11-08 交互改版：−/＋ 每次步进 10%，
 *   替代原单项循环档位）、持久化存储、斜坡过渡平滑无爆音，适配不同机型音频输出差异。
 */
class PlaybackActivity : FragmentActivity() {

    companion object {
        /** Intent Extra 约定 */
        const val EXTRA_HOST_DEVICE_ID = "hostDeviceId"
        const val EXTRA_VIDEO_ID = "videoId"
        const val EXTRA_NAME = "name"
        const val EXTRA_RESUME_POSITION_MS = "resumePositionMs"
        const val EXTRA_IS_M3U8 = "isM3u8"   // 是否为电脑端聚合的 M3U8 虚拟条目（2026-11-07）

        /** 起播位置哨兵：未显式传入 → 自动按本地断点进度决定是否续播 */
        const val RESUME_AUTO = Long.MIN_VALUE

        /** 清晰度档位常量（与电脑端 quality 参数协议一致） */
        const val QUALITY_1080 = "1080p"   // 默认：原画直连（源片超高清时电脑端转 1080）
        const val QUALITY_720 = "720p"     // 电脑端实时转码 720p
        const val QUALITY_480 = "480p"     // 电脑端实时转码 480p

        /** 进度节流保存间隔（细则 5.3：每 5 秒） */
        private const val SAVE_INTERVAL_MS = 5_000L

        /** 清晰度偏好持久化 */
        private const val PREFS_PLAYER = "boxtv_player"
        private const val KEY_QUALITY = "quality"

        /** 音量增益档位持久化（2026-11-07 播放体验专项） */
        private const val KEY_GAIN = "volume_gain"

        /** 增益步长（百分比）：−/＋ 按钮每次调整 10%（0-200%，2026-11-08 交互改版） */
        private const val GAIN_STEP = 10

        /** 快进/快退固定步长：10 秒/次 */
        private const val SEEK_STEP_MS = 10_000L

        /** 长按持续步进的最小间隔（毫秒）：滤除过快的按键重复事件，步进节奏均匀 */
        private const val SEEK_REPEAT_INTERVAL_MS = 220L

        /** 快进/快退反馈气泡显示时长 */
        private const val SEEK_FEEDBACK_MS = 900L
    }

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var statusText: TextView
    private lateinit var repository: com.example.boxtv.data.repository.HostRepository

    // ---- 菜单面板视图 ----
    private lateinit var menuPanel: View
    private lateinit var menuTitle: TextView
    private lateinit var menuInfoProgress: TextView
    private lateinit var menuInfoDetail: TextView
    private lateinit var episodeList: RecyclerView
    private lateinit var episodeEmpty: TextView
    private lateinit var qualityButtons: Map<String, TextView>

    private var host: com.example.boxtv.model.PairedHost? = null
    private var videoId: String = ""
    private var videoPath: String = ""      // 断点进度存储键（相对路径，细则 5.3）
    private var videoName: String = ""
    private var lastSavedPosition = -1L     // 节流：上次已保存的位置
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- 音量增益（0-200%，PCM 增益处理器，2026-11-07 播放体验专项）----
    private lateinit var gainProcessor: GainAudioProcessor
    private lateinit var gainButton: TextView
    private var gainPercent = 100

    // ---- 遥控器长按快进/快退状态 ----
    private lateinit var seekFeedback: TextView
    private var lastSeekStepMs = 0L           // 上次步进时刻（重复事件节流用）
    private var pendingTranscodeOffsetMs = 0L // 转码模式长按累计偏移（松开按键时一次性提交）
    private val hideSeekFeedback = Runnable { seekFeedback.isVisible = false }

    /** HTTP 数据源工厂（Token 头 + 超时，直连/转码共用） */
    private var dataSourceFactory: DefaultHttpDataSource.Factory? = null

    /** MediaSource 工厂（ClippingMediaSource 组装基座） */
    private var mediaSourceFactory: DefaultMediaSourceFactory? = null

    // ---- 清晰度/转码状态 ----
    private var prefs: SharedPreferences? = null
    private var quality: String = QUALITY_1080
    private var isM3u8 = false           // 当前视频是否为 M3U8 聚合条目（HLS 原生直连，2026-11-07）
    private var transcoded = false       // 当前播放的流是否为电脑端转码流
    private var streamStartMs = 0L       // 当前转码流的起点（-ss，直连模式恒为 0）
    private var mediaDurationMs = 0L     // 原片时长（meta 补偿；转码流本身无时长）
    private var meta: VideoMeta? = null  // 当前视频元数据（时长/源片分辨率）

    // ---- 选集状态 ----
    private var episodes: List<VideoEntry> = emptyList()
    private var episodesKey: String? = null   // 已加载列表的「folderId|父路径」标记（换视频失效）
    private lateinit var episodeAdapter: EpisodeAdapter

    /** 菜单面板是否展开 */
    private var menuVisible = false

    /** 周期保存任务：每 5 秒记录一次播放位置（细则 5.3） */
    private val periodicSave = object : Runnable {
        override fun run() {
            saveProgressNow()
            mainHandler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    /** 菜单面板信息刷新（展开期间每秒一次：进度/状态/分辨率） */
    private val menuInfoTicker = object : Runnable {
        override fun run() {
            if (!menuVisible) return
            refreshMenuInfo()
            mainHandler.postDelayed(this, 1_000)
        }
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)
        statusText = findViewById(R.id.playback_status)
        playerView = findViewById(R.id.player_view)
        seekFeedback = findViewById(R.id.seek_feedback)

        // 控制条「菜单」按钮（2026-11-08 控制栏交互优化，位于设置按钮左侧）：
        // 遥控器 OK 键点击与原生「菜单」键完全等效，呼出/关闭侧边菜单面板，
        // 为遥控器菜单键失效的用户提供替代入口；按钮聚焦样式见 bg_exo_menu_btn
        playerView.findViewById<ImageButton>(R.id.btn_menu_toggle)?.setOnClickListener {
            if (menuVisible) hideMenuPanel() else showMenuPanel()
        }

        repository = (application as BoxTvApp).repository
        val hostId = intent.getStringExtra(EXTRA_HOST_DEVICE_ID)
        val videoIdArg = intent.getStringExtra(EXTRA_VIDEO_ID) ?: ""
        val nameArg = intent.getStringExtra(EXTRA_NAME) ?: ""
        val resumeExtra = intent.getLongExtra(EXTRA_RESUME_POSITION_MS, RESUME_AUTO)
        val m3u8Arg = intent.getBooleanExtra(EXTRA_IS_M3U8, false) // M3U8 聚合条目标记
        android.util.Log.d(
            "boxtv-playback",
            "onCreate: hostId=$hostId videoId=$videoIdArg resume=$resumeExtra m3u8=$m3u8Arg"
        )

        val found = repository.pairedHosts.value.firstOrNull { it.hostDeviceId == hostId }
        if (hostId == null || found == null || videoIdArg.isBlank()) {
            android.util.Log.d("boxtv-playback", "finish: missing info (hostId=$hostId found=${found != null} videoIdBlank=${videoIdArg.isBlank()})")
            Toast.makeText(this, "播放信息缺失", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        host = found

        // ---- 菜单面板视图绑定 ----
        bindMenuPanel()

        // ---- 清晰度偏好：上次选择持久化，默认 1080P ----
        prefs = getSharedPreferences(PREFS_PLAYER, MODE_PRIVATE)
        quality = prefs?.getString(KEY_QUALITY, QUALITY_1080)?.takeIf {
            it == QUALITY_1080 || it == QUALITY_720 || it == QUALITY_480
        } ?: QUALITY_1080

        // ---- 音量增益：读取上次设置的档位（0-200%，默认 100% 不增益），下次启动自动生效 ----
        gainPercent = prefs?.getInt(KEY_GAIN, 100)?.coerceIn(0, 200) ?: 100

        // ---- 构建 ExoPlayer（HTTP 数据源携带 Token，细则 5.4）----
        dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("X-BoxTV-Token" to found.token))
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory!!)

        // ---- 音量增益处理器：注入音频渲染管线（PCM 样本放大，0-200%）----
        gainProcessor = GainAudioProcessor().apply { setGainPercent(gainPercent) }
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                // 与 DefaultRenderersFactory 官方默认实现完全对齐（float 输出 + 变速参数开关，
                // 避免 sink 配置漂移导致部分机型 float PCM 路径异常），仅额外注入增益处理器，
                // MP4 直连 / HLS / 转码流全模式统一生效
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(gainProcessor))
                    .build()
            }
        }
        player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory!!)
            .build()

        // 控制条与菜单面板都通过 seekProxy 操作播放器（转码模式下接管 seek 与位置补偿）
        playerView.player = buildSeekProxy()
        player.addListener(playerListener)

        // ---- 选集列表 ----
        episodeAdapter = EpisodeAdapter()
        episodeList.layoutManager = LinearLayoutManager(this)
        episodeList.adapter = episodeAdapter

        // ---- 开始播放（断点续播自动判断；M3U8 条目走 HLS 直连分支）----
        loadAndPlay(videoIdArg, nameArg, resumeExtra, m3u8Arg)

        // ---- 每 5 秒节流保存进度（细则 5.3）----
        mainHandler.postDelayed(periodicSave, SAVE_INTERVAL_MS)

        // ---- 返回键：先关菜单面板，再保存进度退出（Android 13+ 必须用 Dispatcher 注册）----
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (menuVisible) {
                    hideMenuPanel()
                    return
                }
                saveProgressNow()
                finish()
            }
        })
    }

    /** 遥控器按键：菜单键呼出/关闭播放器面板 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (menuVisible) hideMenuPanel() else showMenuPanel()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * 按键分发（2026-11-07 遥控器快进专项；2026-11-08 控制条焦点导航兼容）：
     * 左/右方向键在 Activity 层最外层拦截（优先于 PlayerView 控制条），统一为
     * 「快退/快进」语义，符合电视端常规操作习惯：
     * - 短按：立即步进 10 秒（首次按下即响应，零延迟）；
     * - 长按：系统按键重复事件按固定间隔节流，持续步进跳转（按住不放连续快进/快退）；
     * - 转码流：长按期间只累计偏移并预览目标位置，松开按键一次性换起点重播，
     *   避免反复重启电脑端 ffmpeg 造成卡顿；
     * - 菜单面板展开时不拦截，保留面板内的焦点导航；
     * - 焦点位于 PlayerView 内（控制条按钮/中央控制）时不拦截：
     *   此时左右键用于在控制条按钮间移动焦点（含 2026-11-08 新增的「菜单」按钮），
     *   避免焦点导航被快进/快退抢占。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!menuVisible && !isFocusInsidePlayerView()) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> return handleSeekKeyEvent(event, forward = false)
                KeyEvent.KEYCODE_DPAD_RIGHT -> return handleSeekKeyEvent(event, forward = true)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** 当前焦点 View 是否位于 PlayerView 内（控制条可见且按钮聚焦时为 true） */
    private fun isFocusInsidePlayerView(): Boolean {
        var p = currentFocus?.parent as ViewParent?
        while (p != null) {
            if (p === playerView) return true
            p = p.parent
        }
        return false
    }

    /** 左/右方向键处理：按下时节流步进，抬起时提交转码模式累计的偏移 */
    private fun handleSeekKeyEvent(event: KeyEvent, forward: Boolean): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val now = SystemClock.elapsedRealtime()
                // 首按（repeatCount==0）立即步进；长按重复事件按固定间隔节流，
                // 兼容不同遥控器的重复频率差异，避免跳转过猛
                if (event.repeatCount == 0 || now - lastSeekStepMs >= SEEK_REPEAT_INTERVAL_MS) {
                    lastSeekStepMs = now
                    stepSeek(forward)
                }
            }
            KeyEvent.ACTION_UP -> {
                // 松开按键：转码模式把长按累计的偏移一次性提交（换起点重新起播）
                if (transcoded && pendingTranscodeOffsetMs != 0L) {
                    val target = realPosition() + pendingTranscodeOffsetMs
                    pendingTranscodeOffsetMs = 0
                    restartTranscodeAt(target)
                }
            }
        }
        // 消费事件，避免 PlayerView 控制条再次响应产生双重跳转
        return true
    }

    /** 步进 10 秒：直连流直接 seek（Range 可拖动）；转码流只累计偏移并预览目标位置 */
    private fun stepSeek(forward: Boolean) {
        if (transcoded) {
            pendingTranscodeOffsetMs += if (forward) SEEK_STEP_MS else -SEEK_STEP_MS
            showSeekFeedback(realPosition() + pendingTranscodeOffsetMs, forward)
        } else {
            val delta = if (forward) SEEK_STEP_MS else -SEEK_STEP_MS
            val target = (player.currentPosition + delta).coerceAtLeast(0)
            player.seekTo(target)
            showSeekFeedback(target, forward)
        }
    }

    /** 快进/快退反馈气泡：显示目标位置，超时自动隐藏 */
    private fun showSeekFeedback(targetMs: Long, forward: Boolean) {
        seekFeedback.text =
            "${if (forward) "快进 ⏩" else "快退 ⏪"} ${formatMs(targetMs.coerceAtLeast(0))}"
        seekFeedback.isVisible = true
        mainHandler.removeCallbacks(hideSeekFeedback)
        mainHandler.postDelayed(hideSeekFeedback, SEEK_FEEDBACK_MS)
    }

    override fun onStop() {
        super.onStop()
        // 切后台：保存进度并暂停（细则 5.3 触发规则）
        saveProgressNow()
        if (::player.isInitialized) player.pause()
    }

    override fun onStart() {
        super.onStart()
        if (::player.isInitialized) player.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(periodicSave)
        mainHandler.removeCallbacks(menuInfoTicker)
        mainHandler.removeCallbacks(hideSeekFeedback)
        if (::player.isInitialized) {
            player.removeListener(playerListener)
            player.release()
        }
    }

    // ==================================================================
    // 播放控制（起播 / 断点续播 / 清晰度切换 / 转码 seek）
    // ==================================================================

    /**
     * 装载并播放视频（新视频入口：浏览页直入 / 选集切换共用）。
     * @param startMs RESUME_AUTO=自动断点续播；>0=指定起播位置；其余=从头播放
     * @param m3u8 是否为电脑端聚合的 M3U8 虚拟条目（HLS 原生直连播放，2026-11-07）
     */
    private fun loadAndPlay(vid: String, name: String, startMs: Long, m3u8: Boolean) {
        val h = host ?: return
        videoId = vid
        videoName = name
        videoPath = VideoIdCodec.decode(vid)?.second ?: ""
        lastSavedPosition = -1L
        isM3u8 = m3u8
        // 清晰度档位：M3U8 固定原画（HLS 直连不支持电脑端转码档）；
        // 普通视频维持持久化偏好，与原有行为完全一致
        quality = if (m3u8) QUALITY_1080 else prefs?.getString(KEY_QUALITY, QUALITY_1080)?.takeIf {
            it == QUALITY_1080 || it == QUALITY_720 || it == QUALITY_480
        } ?: QUALITY_1080
        applyQualityAvailability()
        statusText.isVisible = true
        statusText.text = "正在加载… $videoName"

        lifecycleScope.launch {
            if (m3u8) {
                // HLS 流自带总时长（原生进度条/精准 seek），跳过 meta 探测
                meta = null
                mediaDurationMs = 0L
            } else {
                // 元数据：时长（转码模式进度补偿）+ 源片分辨率（1080P 档转码判定）
                val m = repository.videoMeta(h, vid)
                meta = m
                mediaDurationMs = m?.durationMs ?: 0L
            }
            episodesKey = null // 换视频后选集列表需按新文件夹重新加载

            val startPos = when {
                startMs == RESUME_AUTO -> resolveResumePosition()
                startMs > 0 -> startMs
                else -> -1L
            }
            withContext(Dispatchers.Main) { playCurrent(startPos) }
        }
    }

    /**
     * 按当前清晰度档位起播（位置为原片时间线，毫秒）。
     * 直连/转码/M3U8 的分流决策集中在此处，切清晰度与转码 seek 复用。
     */
    private fun playCurrent(startPos: Long) {
        val h = host ?: return
        if (isFinishing || isDestroyed || mediaSourceFactory == null) return
        if (isM3u8) {
            // M3U8 聚合条目：HLS 原生直连——电脑端已把列表内相对 URI 改写为
            // /api/video/part 绝对地址，ExoPlayer 原生 HLS 播放器直接消费；
            // 显式声明 MIME 确保路由到 HLS MediaSource（不依赖扩展名嗅探）
            val item = MediaItem.Builder()
                .setUri(repository.streamUrl(h, videoId))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
            applySource(transcode = false, item, startPos)
            return
        }
        val wantTranscode = when (quality) {
            QUALITY_720, QUALITY_480 -> true                       // 低档位必转码
            else -> (meta?.height ?: 0) > 1080                     // 1080P：源片超高清才转码
        }
        val item = if (wantTranscode) {
            // 转码流：起点经 start 参数交给电脑端 -ss 定位（秒）
            val startSec = (if (startPos > 0) startPos else 0L) / 1000.0
            MediaItem.Builder()
                .setUri(repository.streamUrl(h, videoId, quality, startSec))
                .build()
        } else {
            // 原画直连：Range 可拖动，直接从断点位置开流
            MediaItem.fromUri(repository.streamUrl(h, videoId))
        }
        applySource(wantTranscode, item, startPos)
    }

    /** 应用媒体源：直连 = MediaItem + 断点位置；转码 = Clipping 裁剪窗口 + 0 点起播 */
    private fun applySource(transcode: Boolean, item: MediaItem, startPos: Long) {
        val factory = mediaSourceFactory ?: return
        transcoded = transcode
        streamStartMs = if (transcode && startPos > 0) startPos else 0L
        if (transcode) {
            val base = factory.createMediaSource(item)
            // 转码流不携带时长 → 用 meta 时长裁剪窗口 [0, duration]，
            // 进度条时长与原片一致（窗口内位置由 seekProxy 补偿为原片时间线）
            val source = if (mediaDurationMs > 0)
                ClippingMediaSource(base, 0L, mediaDurationMs * 1000) else base
            player.setMediaSource(source)
        } else {
            player.setMediaItem(item, if (startPos > 0) startPos else C.TIME_UNSET)
        }
        player.prepare()
        player.playWhenReady = true
        refreshMenuInfo()
    }

    /**
     * 转码模式 seek：以目标位置为新起点重新起播（电脑端 -ss 精确寻址）。
     * 位置为原片时间线毫秒；夹取到 [0, 时长-1s] 避免起点越界产生空流。
     */
    private fun restartTranscodeAt(targetMs: Long) {
        val dur = realDuration()
        val maxPos = if (dur != C.TIME_UNSET && dur > 0) (dur - 1_000).coerceAtLeast(0) else Long.MAX_VALUE
        val pos = targetMs.coerceIn(0L, maxPos)
        if (pos == streamStartMs) {
            // 目标即当前流起点（如重启后的重复确认）：无需重启，恢复播放
            player.play()
            return
        }
        statusText.isVisible = true
        statusText.text = "正在跳转… $videoName"
        playCurrent(pos)
    }

    /** 切换清晰度：从当前真实位置起播新档位（直连↔转码统一走此路径）。
     *  M3U8 条目不支持转码清晰度（HLS 原生直连），低档位点击直接提示。 */
    private fun onQualityClicked(newQuality: String) {
        if (isM3u8 && newQuality != QUALITY_1080) {
            Toast.makeText(this, "M3U8 片源不支持切换清晰度", Toast.LENGTH_SHORT).show()
            return
        }
        if (newQuality == quality) return
        quality = newQuality
        prefs?.edit()?.putString(KEY_QUALITY, quality)?.apply()
        refreshQualityButtons()
        statusText.isVisible = true
        statusText.text = "正在切换清晰度… $videoName"
        playCurrent(realPosition())
        Toast.makeText(this, "清晰度：${qualityLabel(quality)}", Toast.LENGTH_SHORT).show()
    }

    /** 自动断点续播位置（细则 5.3：进度 5%~95% 之间续播）；无有效进度返回 -1 */
    private fun resolveResumePosition(): Long {
        val h = host ?: return -1
        val saved = repository.getProgress(h, videoPath) ?: return -1
        if (saved.durationMs <= 0) return -1
        val ratio = saved.positionMs.toDouble() / saved.durationMs
        return if (ratio in 0.05..0.95) saved.positionMs else -1
    }

    // ------------------------------------------------------------------
    // 原片时间线换算（转码流窗口位置 ↔ 原片位置）
    // ------------------------------------------------------------------

    /** 原片时间线真实播放位置（直连 = 播放器位置；转码 = 流起点 + 窗口内位置） */
    private fun realPosition(): Long {
        if (!::player.isInitialized) return 0
        val pos = player.currentPosition
        if (!transcoded) return pos
        val dur = realDuration()
        return if (dur == C.TIME_UNSET) streamStartMs + pos else (streamStartMs + pos).coerceAtMost(dur)
    }

    /** 原片总时长（优先播放器报告值；转码流回退 meta 时长） */
    private fun realDuration(): Long {
        val playerDur = if (::player.isInitialized) player.duration else C.TIME_UNSET
        if (playerDur != C.TIME_UNSET && playerDur > 0) return playerDur
        return if (mediaDurationMs > 0) mediaDurationMs else C.TIME_UNSET
    }

    /**
     * 播放器代理（ForwardingPlayer）：转码模式下的位置补偿与 seek 拦截。
     * - 读侧：getCurrentPosition/getBufferedPosition 加回流起点、getDuration 换为
     *   原片时长 → 控制条进度条显示的就是原片时间线；
     * - 写侧：seekTo/seekBack/seekForward 拦截为「换起点重新起播」
     *   （转码流无法 Range 拖动，重新请求是唯一精确寻址方式）；
     * - 直连模式全部透传，行为与原生 ExoPlayer 完全一致。
     */
    private fun buildSeekProxy(): ForwardingPlayer = object : ForwardingPlayer(player) {
        override fun seekTo(positionMs: Long) {
            if (!transcoded) { super.seekTo(positionMs); return }
            restartTranscodeAt(positionMs)
        }

        override fun seekBack() {
            if (!transcoded) { super.seekBack(); return }
            restartTranscodeAt(realPosition() - seekBackIncrement)
        }

        override fun seekForward() {
            if (!transcoded) { super.seekForward(); return }
            restartTranscodeAt(realPosition() + seekForwardIncrement)
        }

        override fun seekToDefaultPosition() {
            if (!transcoded) { super.seekToDefaultPosition(); return }
            restartTranscodeAt(0)
        }

        override fun getCurrentPosition(): Long {
            val p = super.getCurrentPosition()
            if (!transcoded) return p
            val d = realDuration()
            return if (d == C.TIME_UNSET) streamStartMs + p else (streamStartMs + p).coerceAtMost(d)
        }

        override fun getBufferedPosition(): Long {
            val p = super.getBufferedPosition()
            if (!transcoded) return p
            val d = realDuration()
            return if (d == C.TIME_UNSET) streamStartMs + p else (streamStartMs + p).coerceAtMost(d)
        }

        override fun getDuration(): Long = realDuration()

        override fun getContentPosition(): Long = getCurrentPosition()

        override fun getContentBufferedPosition(): Long = getBufferedPosition()
    }

    // ==================================================================
    // 播放事件：错误兜底 / 缓冲提示 / 播放结束清理
    // ==================================================================

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.d("boxtv-playback", "player error: ${error.errorCodeName}", error)
            val hint = if (transcoded) "，可尝试切换 1080P（原画）" else ""
            Toast.makeText(
                this@PlaybackActivity,
                "播放失败：${error.errorCodeName}$hint",
                Toast.LENGTH_LONG
            ).show()
            saveProgressNow()
            finish()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    // 转码重启的「跳转/切换」提示优先（避免被缓冲文案覆盖造成困惑）
                    if (!statusText.text.startsWith("正在跳转") && !statusText.text.startsWith("正在切换")) {
                        statusText.isVisible = true
                        statusText.text = "正在缓冲… $videoName"
                    }
                }
                Player.STATE_READY, Player.STATE_IDLE -> statusText.isVisible = false
                Player.STATE_ENDED -> {
                    statusText.isVisible = false
                    // 播放自然结束：清理进度，下次不续播（细则 5.3 清理规则）
                    clearProgress()
                }
            }
            refreshMenuInfo()
        }
    }

    /** 立即保存当前播放进度（细则 5.3 playback_progress 结构；原片时间线位置） */
    private fun saveProgressNow() {
        val h = host ?: return
        if (!::player.isInitialized) return
        val position = realPosition()
        val duration = realDuration()
        if (duration == C.TIME_UNSET || duration <= 0) return // 媒体元数据未就绪，跳过本次
        if (position == lastSavedPosition) return // 无变化，节流跳过
        lastSavedPosition = position
        val progress = PlaybackProgress(
            hostDeviceId = h.hostDeviceId,
            videoPath = videoPath,
            positionMs = position,
            durationMs = duration,
            updatedAt = System.currentTimeMillis()
        )
        android.util.Log.d("boxtv-playback", "save progress: ${progress.positionMs}/${progress.durationMs}")
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            (application as BoxTvApp).repository.saveProgress(progress)
        }
    }

    /** 播放自然结束：写入 positionMs=0 的进度使续播判定失效（比例 < 5%） */
    private fun clearProgress() {
        val h = host ?: return
        val duration = (realDuration().takeIf { it != C.TIME_UNSET } ?: 1L).coerceAtLeast(1)
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            (application as BoxTvApp).repository.saveProgress(
                PlaybackProgress(h.hostDeviceId, videoPath, 0, duration, System.currentTimeMillis())
            )
        }
    }

    // ==================================================================
    // 播放器菜单面板（播放信息 + 清晰度 + 选集）
    // ==================================================================

    /** 绑定面板视图与清晰度按钮（onCreate 调用一次） */
    private fun bindMenuPanel() {
        menuPanel = findViewById(R.id.menu_panel)
        menuTitle = findViewById(R.id.menu_title)
        menuInfoProgress = findViewById(R.id.menu_info_progress)
        menuInfoDetail = findViewById(R.id.menu_info_detail)
        episodeList = findViewById(R.id.episode_list)
        episodeEmpty = findViewById(R.id.episode_empty)
        qualityButtons = mapOf(
            QUALITY_1080 to findViewById<TextView>(R.id.btn_quality_1080),
            QUALITY_720 to findViewById<TextView>(R.id.btn_quality_720),
            QUALITY_480 to findViewById<TextView>(R.id.btn_quality_480)
        )
        qualityButtons.forEach { (key, btn) ->
            btn.setOnClickListener { onQualityClicked(key) }
        }
        // 音量增益 −/＋ 按钮（2026-11-08 交互改版）：每次步进 10%，即时持久化生效；
        // 中间 btn_gain 胶囊仅展示当前值（不可聚焦），焦点在 −/＋ 间用 DPAD 左右切换
        gainButton = findViewById(R.id.btn_gain)
        findViewById<TextView>(R.id.btn_gain_minus).setOnClickListener { adjustGain(-GAIN_STEP) }
        findViewById<TextView>(R.id.btn_gain_plus).setOnClickListener { adjustGain(GAIN_STEP) }
    }

    /** 展开菜单面板：焦点落到当前清晰度档位，启动 1 秒信息刷新 */
    private fun showMenuPanel() {
        menuVisible = true
        menuPanel.isVisible = true
        playerView.hideController() // 隐藏控制条，避免两套 UI 抢焦点
        refreshQualityButtons()
        refreshGainButton()
        ensureEpisodesLoaded()
        refreshMenuInfo()
        qualityButtons[quality]?.post { qualityButtons[quality]?.requestFocus() }
        mainHandler.removeCallbacks(menuInfoTicker)
        mainHandler.post(menuInfoTicker)
    }

    /** 收起菜单面板并停止信息刷新 */
    private fun hideMenuPanel() {
        menuVisible = false
        menuPanel.isVisible = false
        mainHandler.removeCallbacks(menuInfoTicker)
    }

    /** 刷新清晰度按钮选中态 */
    private fun refreshQualityButtons() {
        qualityButtons.forEach { (key, btn) -> btn.isSelected = key == quality }
    }

    /**
     * 清晰度按钮可用性（2026-11-07 M3U8 播放支持）：
     * M3U8 条目为 HLS 原生直连流，电脑端转码档（720P/480P）不适用 →
     * 置灰（isEnabled=false + 半透明），1080P 档保持可用并高亮。
     */
    private fun applyQualityAvailability() {
        qualityButtons.forEach { (key, btn) ->
            val enabled = !isM3u8 || key == QUALITY_1080
            btn.isEnabled = enabled
            btn.alpha = if (enabled) 1f else 0.4f
        }
    }

    // ------------------------------------------------------------------
    // 音量增益（0-200%，2026-11-07 播放体验专项）
    // ------------------------------------------------------------------

    /**
     * 调整增益（2026-11-08 交互改版）：delta 正为加档、负为减档，步长 [GAIN_STEP]，
     * 范围钳制 0-200；持久化 + 即时生效（处理器内做斜坡过渡，无爆音）。
     * 中间值胶囊即时刷新，边界（0 静音 / 200 最大）再按同方向不动。
     */
    private fun adjustGain(delta: Int) {
        val next = (gainPercent + delta).coerceIn(0, 200)
        if (next == gainPercent) return // 已到边界：无变化不重复持久化
        gainPercent = next
        prefs?.edit()?.putInt(KEY_GAIN, next)?.apply()
        gainProcessor.setGainPercent(next)
        refreshGainButton()
    }

    /** 增益值展示文案（0/100/200 附加语义标注，其余显示纯百分比） */
    private fun gainLabel(percent: Int): String = when (percent) {
        0 -> "0%（静音）"
        100 -> "100%（标准）"
        200 -> "200%（最大）"
        else -> "$percent%"
    }

    /** 刷新增益按钮文案（面板展开时同步当前档位） */
    private fun refreshGainButton() {
        gainButton.text = gainLabel(gainPercent)
    }

    /** 刷新面板播放信息（展开期间由 menuInfoTicker 每秒调用） */
    private fun refreshMenuInfo() {
        if (!menuVisible) return
        menuTitle.text = videoName
        val pos = realPosition()
        val dur = realDuration()
        menuInfoProgress.text = if (dur != C.TIME_UNSET && dur > 0) {
            "播放进度：${formatMs(pos)} / ${formatMs(dur)}（${pos * 100 / dur}%）"
        } else {
            "播放进度：${formatMs(pos)} / 未知时长"
        }
        // 分辨率优先取播放器实际解码尺寸（转码时即输出分辨率），回退源片元数据
        val size = if (::player.isInitialized) player.videoSize else null
        val resText = when {
            size != null && size.width > 0 -> "${size.width}x${size.height}"
            meta != null && meta!!.width > 0 -> "${meta!!.width}x${meta!!.height}"
            else -> "—"
        }
        val stateText = when {
            !::player.isInitialized -> "初始化"
            player.playbackState == Player.STATE_BUFFERING -> "缓冲中…"
            player.playbackState == Player.STATE_ENDED -> "已播完"
            player.isPlaying -> "播放中"
            else -> "已暂停"
        }
        menuInfoDetail.text = "分辨率：$resText　清晰度：${qualityLabel(quality)}　状态：$stateText"
    }

    /** 清晰度档位展示名（M3U8 条目固定 HLS 原画直连） */
    private fun qualityLabel(q: String): String = when {
        isM3u8 && q == QUALITY_1080 -> "原画（HLS）"
        q == QUALITY_1080 -> "1080P（原画）"
        q == QUALITY_720 -> "720P"
        q == QUALITY_480 -> "480P"
        else -> q
    }

    /** 毫秒 → h:mm:ss / mm:ss */
    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    // ------------------------------------------------------------------
    // 选集（当前文件夹内的视频列表）
    // ------------------------------------------------------------------

    /**
     * 按需加载选集列表（面板展开时调用）。
     * 当前文件夹信息直接从 videoId 反解（Base64URL("folderId|相对路径")），
     * 无需浏览页额外传参；同一文件夹的结果在会话内缓存。
     */
    private fun ensureEpisodesLoaded() {
        val decoded = VideoIdCodec.decode(videoId) ?: return
        val key = "${decoded.first}|${decoded.second.substringBeforeLast('/', "")}"
        if (key == episodesKey) return
        episodesKey = key
        episodeEmpty.isVisible = false
        val h = host ?: return
        val (folderId, relPath) = decoded
        val parentPath = relPath.substringBeforeLast('/', "")
        lifecycleScope.launch(Dispatchers.IO) {
            val result = repository.browse(h, folderId, parentPath)
            // 只保留视频条目（排除子文件夹）；接口失败降级为空列表提示
            val list = (result as? ApiResult.Ok)?.data
                ?.filter { !it.isDir && it.videoId != null }
                ?: emptyList()
            if (isFinishing || isDestroyed) return@launch
            withContext(Dispatchers.Main) {
                episodes = list
                episodeAdapter.notifyDataSetChanged()
                episodeEmpty.isVisible = list.isEmpty()
            }
        }
    }

    /** 选集点击：保存当前视频进度 → 直接切换播放（新视频自动断点续播；
     *  M3U8 聚合条目与普通视频同列表混排，按 format 分别进入对应播放分支） */
    private fun onEpisodeClicked(entry: VideoEntry) {
        val vid = entry.videoId ?: return
        hideMenuPanel()
        if (vid == videoId) return
        saveProgressNow()
        loadAndPlay(vid, entry.name, RESUME_AUTO, entry.isM3u8)
    }

    /** 选集列表适配器：当前播放项加 ▶ 前缀并提亮 */
    private inner class EpisodeAdapter : RecyclerView.Adapter<EpisodeAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val root = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_episode_row, parent, false)
            // 遥控器焦点动效（缩放）；描边光标由 bg_episode_row 背景自带，不叠加前景
            root.bindFocusHighlight(withForeground = false)
            return VH(root)
        }

        override fun getItemCount(): Int = episodes.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = episodes[position]
            // 复用重置：清掉上一个数据残留的放大/抬升状态（与浏览页同款处理）
            holder.itemView.resetFocusHighlight()
            val current = entry.videoId == videoId
            holder.name.text = if (current) "▶ ${entry.name}" else entry.name
            holder.name.setTextColor(if (current) 0xFFFFFFFF.toInt() else 0xFFCCCCCC.toInt())
            holder.itemView.setOnClickListener { onEpisodeClicked(entry) }
        }

        inner class VH(root: View) : RecyclerView.ViewHolder(root) {
            val name: TextView = root.findViewById(R.id.episode_name)
        }
    }
}
