package com.example.boxtv.ui.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * 音量增益音频处理器（2026-11-07 播放体验专项优化）
 *
 * 背景：部分电视机型在系统音量拉满后视频播放音量仍然偏低，
 * 而 ExoPlayer 的 player.volume 上限为 1.0（100%）无法放大，
 * 因此在音频渲染管线的 PCM 层直接对解码后样本做增益处理，支持 0-200%。
 *
 * 挂载方式：PlaybackActivity 自定义 RenderersFactory 覆写 buildAudioSink，
 * 把本处理器注入 DefaultAudioSink 的音频处理链
 * （MP4 直连 / HLS / 电脑端转码流全模式统一生效）。
 *
 * 平滑无爆音：增益变化时在当前音频缓冲区内做逐样本线性斜坡
 * （appliedGain → 目标增益），波形无跳变即无「咔哒」爆音；
 * 增益超 100% 时样本钳位到 [-1, 1]（16bit 钳位到 short 范围），防止溢出破音。
 *
 * Media3 1.11 API 约定：queueInput 返回 void，处理结果经 replaceOutputBuffer
 * 写入后由基类 getOutput() 交给音频 sink（写入后需 flip 使缓冲可读）。
 */
class GainAudioProcessor : BaseAudioProcessor() {

    /** 目标增益系数（1.0 = 100%）；volatile：UI 线程写入、音频渲染线程读取可见 */
    @Volatile
    private var targetGain = 1.0f

    /** 当前已应用增益（斜坡起点）：每个缓冲区从该值线性过渡到目标增益 */
    private var appliedGain = 1.0f

    /** 输入是否为 Float PCM（否则按 16bit 整型 PCM 处理） */
    private var pcmFloat = false

    /** 设置增益档位（百分比 0-200）；播放中调整即时生效（斜坡平滑过渡） */
    fun setGainPercent(percent: Int) {
        targetGain = percent.coerceIn(0, 200) / 100f
    }

    /** 读取当前增益档位（百分比） */
    fun gainPercent(): Int = (targetGain * 100).roundToInt()

    /**
     * 协商输入格式：仅处理 PCM（16bit 整型 / Float），
     * 非 PCM 格式声明不支持，由 ExoPlayer 决定回退路径。
     */
    @Throws(UnhandledAudioFormatException::class)
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        pcmFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        // 新流起播：斜坡起点直接对齐目标增益，避免上一首残留的过渡状态串音
        appliedGain = targetGain
        return inputAudioFormat
    }

    /** 核心处理：输入 PCM 逐样本乘以增益（缓冲区内线性斜坡），写入输出缓冲 */
    override fun queueInput(input: ByteBuffer) {
        val inPosition = input.position()
        val inLimit = input.limit()
        val output = replaceOutputBuffer(input.remaining())
        if (output === input) {
            // 同一缓冲防护：Media3 AudioProcessingPipeline 在特定时序下会把上一次输出的
            // 同一缓冲回喂给处理器（数据已经过本处理器处理）。此时恢复被 replaceOutputBuffer
            // 内部 clear() 重置的读写区间、原样透传——既避免 put(self) 抛
            // IllegalArgumentException 导致播放失败（ERROR_CODE_FAILED_RUNTIME_CHECK），
            // 也避免逐样本分支对回喂缓冲错位重写产生杂音、样本被二次增益
            output.position(inPosition).limit(inLimit)
            appliedGain = targetGain
            return
        }
        val start = appliedGain
        val end = targetGain
        if (start == 1f && end == 1f) {
            // 100%（默认档）：位精确旁路——纯拷贝不经过任何算术运算，
            // 保证默认音量下与未挂处理器时逐字节一致（彻底规避算术引入的失真）
            output.put(input)
        } else if (input.hasRemaining()) {
            if (pcmFloat) {
                // Float PCM（-1.0 ~ 1.0）：逐样本乘增益，超界钳位防溢出失真
                val total = (input.remaining() / java.lang.Float.BYTES).coerceAtLeast(1)
                var i = 0
                while (input.hasRemaining()) {
                    val g = start + (end - start) * (i.toFloat() / total)
                    val sample = input.float * g
                    output.putFloat(sample.coerceIn(-1f, 1f))
                    i++
                }
            } else {
                // 16bit 整型 PCM：乘增益后钳位到 short 可表示范围
                val total = (input.remaining() / 2).coerceAtLeast(1)
                var i = 0
                while (input.hasRemaining()) {
                    val g = start + (end - start) * (i.toFloat() / total)
                    val amplified = (input.short * g).roundToInt()
                    output.putShort(
                        amplified.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    )
                    i++
                }
            }
        }
        appliedGain = end // 本缓冲区处理完：增益已完全过渡到目标值
        // 无论输入是否为空都必须 flip：上游处理器（静音跳过/变速）可能传来空缓冲，
        // 若不 flip，输出缓冲会带着 limit=capacity 的陈旧数据被下游当作 PCM 消费（杂音根因）
        output.flip()
    }

    /** 流结束：无需额外处理（基类负责向下游传递 EOS） */
    override fun onQueueEndOfStream() {
    }

    /** seek / 清空缓冲：斜坡起点直接对齐目标增益 */
    override fun onFlush() {
        appliedGain = targetGain
    }

    /** 复位：同 flush，清掉过渡状态 */
    override fun onReset() {
        appliedGain = targetGain
    }
}
