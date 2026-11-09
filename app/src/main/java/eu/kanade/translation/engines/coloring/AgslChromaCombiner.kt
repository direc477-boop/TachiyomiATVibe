package eu.kanade.translation.engines.coloring

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Ink-aware AGSL combine: original L kept on ink (Rec.601 gray ≤0.12), L mixes toward the colorized
 *  lightness by [INK_SHADING_STRENGTH] elsewhere; work layer contributes a/b; API 33+ only. */
internal object AgslChromaCombiner {

    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Renders at [mergeWidth]x[mergeHeight]; throws on GPU failure so the caller falls back to CPU. */
    fun combine(source: Bitmap, coloredWork: Bitmap, mergeWidth: Int, mergeHeight: Int): Bitmap {
        val thread = HandlerThread("mcv2-agsl-combine")
        thread.start()
        val handler = Handler(thread.looper)
        val renderer = HardwareRenderer()
        val reader = ImageReader.newInstance(
            mergeWidth,
            mergeHeight,
            PixelFormat.RGBA_8888,
            1,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
        )
        val frameShown = CountDownLatch(1)
        reader.setOnImageAvailableListener({ frameShown.countDown() }, handler)
        try {
            renderer.setSurface(reader.surface)
            // uOrigSize is the render-target size, so the full-resolution source must be
            // scaled into that space by its shader local matrix — BitmapShader alone would
            // sample raw source pixels and crop the top-left merge region of large pages.
            val origShader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                val local = android.graphics.Matrix()
                local.setScale(mergeWidth.toFloat() / source.width, mergeHeight.toFloat() / source.height)
                setLocalMatrix(local)
            }
            val shader = RuntimeShader(COMBINE_PROGRAM).apply {
                setInputShader("uColor", BitmapShader(coloredWork, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
                setInputShader("uOrig", origShader)
                setFloatUniform("uOrigSize", floatArrayOf(mergeWidth.toFloat(), mergeHeight.toFloat()))
                setFloatUniform("uWorkSize", floatArrayOf(coloredWork.width.toFloat(), coloredWork.height.toFloat()))
                setFloatUniform("uShadingStrength", floatArrayOf(INK_SHADING_STRENGTH))
            }
            val node = RenderNode("mcv2-chroma-combine").apply { setPosition(0, 0, mergeWidth, mergeHeight) }
            node.beginRecording(mergeWidth, mergeHeight).drawPaint(Paint().also { it.shader = shader })
            node.endRecording()
            renderer.setContentRoot(node)
            renderer.createRenderRequest().syncAndDraw()
            check(frameShown.await(5, TimeUnit.SECONDS)) { "chroma combine frame never produced" }
            val image = reader.acquireLatestImage()
            try {
                val hardwareBuffer = checkNotNull(image.hardwareBuffer) { "chroma combine image has no buffer" }
                try {
                    val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, ColorSpace.get(ColorSpace.Named.SRGB))
                    checkNotNull(hardwareBitmap) { "chroma combine readback wrap failed" }
                    val result = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    checkNotNull(result) { "chroma combine readback copy failed" }
                    return result
                } finally {
                    hardwareBuffer.close()
                }
            } finally {
                image.close()
            }
        } finally {
            renderer.setSurface(null)
            reader.close()
            thread.quitSafely()
        }
    }

    private val COMBINE_PROGRAM = """
        uniform float2 uOrigSize;
        uniform float2 uWorkSize;
        uniform float uShadingStrength;
        uniform shader uColor;
        uniform shader uOrig;
        $AGSL_HELPERS
        half4 main(float2 coord) {
            float3 orig = uOrig.eval(coord).rgb;
            float3 col = uColor.eval(coord * (uWorkSize / uOrigSize)).rgb;
            float gray = dot(orig, float3(0.299, 0.587, 0.114));
            float inkProtection = 1.0 - smoothstep(0.12, 0.45, gray);
            float shadingWeight = uShadingStrength * (1.0 - inkProtection);
            float3 origLab = rgbToLab(orig);
            float3 colLab = rgbToLab(col);
            float finalL = mix(origLab.x, colLab.x, shadingWeight);
            return half4(clamp(labToRgb(float3(finalL, colLab.y, colLab.z)), 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    private const val AGSL_HELPERS = """
float3x3 srgbToXyz() {
    return float3x3(0.412453, 0.357580, 0.180423,
                    0.212671, 0.715160, 0.072169,
                    0.019334, 0.119193, 0.950227);
}
float3x3 xyzToSrgb() {
    return float3x3( 3.2404542, -1.5371385, -0.4985314,
                    -0.9692660,  1.8760108,  0.0415560,
                     0.0556434, -0.2040259,  1.0572252);
}
float3 srgbToLinear(float3 c) {
    float3 hi = pow((c + 0.055) / 1.055, float3(2.4));
    float3 lo = c / 12.92;
    return mix(lo, hi, step(float3(0.04045), c));
}
float3 linearToSrgb(float3 c) {
    float3 hi = 1.055 * pow(clamp(c, 0.0, 1.0), float3(1.0 / 2.4)) - 0.055;
    float3 lo = 12.92 * c;
    return mix(lo, hi, step(float3(0.0031308), c));
}
float3 labF(float3 v) {
    float3 cube = v * v * v;
    return mix((903.3 * v + 16.0) / 116.0, pow(clamp(v, 0.0, 1.0), float3(1.0 / 3.0)), step(float3(0.008856), v));
}
float3 labFinv(float3 v) {
    float3 cube = v * v * v;
    return mix((v * 116.0 - 16.0) / 903.3, cube, step(float3(0.008856), cube));
}
float3 rgbToLab(float3 rgb) {
    // SkSL mat constructors fill column-major, so vec*mat applies the
    // row-major matrix intended here.
    float3 xyz = srgbToLinear(clamp(rgb, 0.0, 1.0)) * srgbToXyz();
    xyz /= float3(0.95047, 1.0, 1.08883);
    float3 f = labF(xyz);
    return float3(116.0 * f.y - 16.0, 500.0 * (f.x - f.y), 200.0 * (f.y - f.z));
}
float3 labToRgb(float3 lab) {
    float fy = (lab.x + 16.0) / 116.0;
    float3 f = float3(fy + lab.y / 500.0, fy, fy - lab.z / 200.0);
    float3 xyz = labFinv(f) * float3(0.95047, 1.0, 1.08883);
    return linearToSrgb(xyz * xyzToSrgb());
}
"""
}
