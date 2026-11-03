package com.spektrafilm.app

import android.graphics.Bitmap

/** JNI surface implemented in rust/spektrafilm-jni/src/lib.rs. */
object NativeBridge {
    init {
        System.loadLibrary("spektrafilm_jni")
    }

    /** Initialise the engine once. Returns the active backend name. */
    external fun init(dataDir: String, tryGpu: Boolean): String

    /** JSON array of {id,name,stage,film_type,support,channel_model,target_print}. */
    external fun listProfiles(): String

    /** Copy an sRGB ARGB_8888 bitmap in as the source image. */
    external fun setSourceBitmap(bitmap: Bitmap)

    /** Decode a RAW/DNG file (fd ownership is transferred and closed) as the source. */
    external fun setSourceRaw(fd: Int, maxEdge: Int)

    /** "srgb8", "raw" or "". */
    external fun sourceKind(): String

    /** [width, height] of a render at [maxEdge] (0 = source size). */
    external fun outputDims(maxEdge: Int): IntArray

    /**
     * Render the film/print/scan simulation into [out] (ARGB_8888, sized by
     * [outputDims]). Returns JSON {backend, millis, gpu_fallback, width, height}.
     */
    external fun render(
        film: String,
        paper: String,
        paramsJson: String,
        maxEdge: Int,
        preferGpu: Boolean,
        cachePipeline: Boolean,
        out: Bitmap,
    ): String

    /** Unprocessed source (sRGB-encoded) into [out], for before/after. */
    external fun renderOriginal(maxEdge: Int, out: Bitmap)
}
