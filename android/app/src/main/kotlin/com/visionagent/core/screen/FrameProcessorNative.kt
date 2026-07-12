package com.visionagent.core.screen

/**
 * FrameProcessorNative — JNI bridge to native FrameProcessor
 * Provides queue depth metrics to adaptive FPS controller.
 */
object FrameProcessorNative {
    init {
        try { System.loadLibrary("vision_agent_native") } catch (_: UnsatisfiedLinkError) {}
    }

    /** Get current frame queue depth from native FrameProcessor */
    @JvmStatic
    external fun getQueueSize(): Int

    /** Fallback if native lib not loaded */
    fun getQueueSizeSafe(): Int = try { getQueueSize() } catch (_: Exception) { 0 }
}
