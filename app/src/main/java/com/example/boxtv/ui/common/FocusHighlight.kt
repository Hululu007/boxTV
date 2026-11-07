package com.example.boxtv.ui.common

import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import com.example.boxtv.R

/**
 * TV 遥控器焦点光标效果（2026-11-07 用户需求）：
 * - 卡片获得焦点：周围出现白色线框（focus_stroke selector 随 focused 状态自动切换）
 *   + 内容轻微放大动效，明确提示遥控器当前指示的内容；
 * - 失去焦点：恢复原大小、隐藏线框；
 * - 聚焦时 Z 轴抬升，保证放大后的卡片不被相邻卡片遮挡。
 *
 * 实现说明：主页/浏览页均为自建 RecyclerView（非 Leanback 官方容器），
 * 无法使用 FocusHighlightHelper 等官方联动，故以 View 动画手写实现。
 * 使用方式：onCreateViewHolder 中对卡片根 view 调用 [bindFocusHighlight] 一次；
 * onBindViewHolder 中调用 [resetFocusHighlight] 清除复用残留状态。
 */

/** 焦点放大倍率（"轻微放大"，不过分抢夺视觉） */
private const val FOCUS_SCALE = 1.08f

/** 焦点动效时长（ms）——短促干脆，适应遥控器快速移动的操作节奏 */
private const val FOCUS_ANIM_DURATION = 150L

/** 焦点 Z 轴抬升高度（dp），让聚焦卡片绘制在相邻卡片之上 */
private const val FOCUS_Z_DP = 8f

/**
 * 绑定焦点光标效果：白色线框 + 轻微放大动效（每个卡片 view 只需调用一次）
 *
 * @param withForeground 是否叠加白色描边前景。默认 true；
 *   当视图的背景 selector 已自带聚焦描边（如主页网格图块 bg_content_tile）
 *   时传 false，避免双重描边叠加。
 * @param onFocusChange 可选的附加焦点回调（与内置缩放动效叠加执行）。
 *   用于主页网格卡片聚焦时联动头部状态栏（更新主机名称/连接状态）等场景；
 *   不传时行为与旧版完全一致（浏览器页等既有调用点无需改动）。
 */
fun View.bindFocusHighlight(
    withForeground: Boolean = true,
    onFocusChange: ((View, Boolean) -> Unit)? = null
) {
    // 白色线框：selector 按 view 的 focused 状态自动显隐，无需手动控制
    if (withForeground) {
        foreground = AppCompatResources.getDrawable(context, R.drawable.focus_stroke)
    }
    setOnFocusChangeListener { v, hasFocus ->
        val zPx = FOCUS_Z_DP * v.resources.displayMetrics.density
        v.animate()
            .scaleX(if (hasFocus) FOCUS_SCALE else 1f)
            .scaleY(if (hasFocus) FOCUS_SCALE else 1f)
            .translationZ(if (hasFocus) zPx else 0f)
            .setDuration(FOCUS_ANIM_DURATION)
            .start()
        // 附加回调（如：通知 Activity 更新头部「电脑主机名称/连接状态」）
        onFocusChange?.invoke(v, hasFocus)
    }
}

/** 复用重置：RecyclerView 把带焦点状态的卡片复用给新数据时，清除残留的放大/抬升 */
fun View.resetFocusHighlight() {
    scaleX = 1f
    scaleY = 1f
    translationZ = 0f
}

/**
 * 侧边栏菜单聚焦动效：轻微放大。
 * 聚焦光标胶囊（实心黑）与选中描边胶囊由 bg_sidebar_item selector 自动切换，
 * 这里只负责缩放反馈。主页与浏览页的侧边栏共用。
 */
fun View.bindSidebarFocusScale() {
    setOnFocusChangeListener { v, hasFocus ->
        v.animate()
            .scaleX(if (hasFocus) 1.05f else 1f)
            .scaleY(if (hasFocus) 1.05f else 1f)
            .setDuration(FOCUS_ANIM_DURATION)
            .start()
    }
}
