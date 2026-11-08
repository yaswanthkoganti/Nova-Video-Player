package com.nova.player.engine

import android.content.Context
import android.opengl.GLES20
import android.util.Size
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/** Live-tweakable parameters shared with the shader (no pipeline rebuild while dragging sliders). */
class EnhanceParams {
    @Volatile var sharpen = 0f
    @Volatile var contrast = 1f
    @Volatile var saturation = 1f
    @Volatile var brightness = 0f
    fun set(e: Enhance) { sharpen = e.sharpen; contrast = e.contrast; saturation = e.saturation; brightness = e.brightness }
}

@UnstableApi
class EnhanceEffect(private val params: EnhanceParams) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        EnhanceShader(useHdr, params)
}

@UnstableApi
private class EnhanceShader(useHdr: Boolean, private val p: EnhanceParams) : BaseGlShaderProgram(useHdr, 1) {
    private val program: GlProgram
    private var w = 1
    private var h = 1

    init {
        try {
            program = GlProgram(VERTEX, FRAGMENT)
            program.setBufferAttribute(
                "aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        w = inputWidth; h = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatsUniform("uTexel", floatArrayOf(1f / w, 1f / h))
            program.setFloatUniform("uSharpen", p.sharpen)
            program.setFloatUniform("uContrast", p.contrast)
            program.setFloatUniform("uSaturation", p.saturation)
            program.setFloatUniform("uBrightness", p.brightness)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try { program.delete() } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
    }

    companion object {
        private const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexCoord;
void main() {
  gl_Position = aFramePosition;
  vTexCoord = aFramePosition.xy * 0.5 + 0.5;
}"""
        private const val FRAGMENT = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform vec2 uTexel;
uniform float uSharpen;
uniform float uContrast;
uniform float uSaturation;
uniform float uBrightness;
varying vec2 vTexCoord;
void main() {
  vec3 c = texture2D(uTexSampler, vTexCoord).rgb;
  vec3 n = texture2D(uTexSampler, vTexCoord + vec2(0.0, -uTexel.y)).rgb;
  vec3 s = texture2D(uTexSampler, vTexCoord + vec2(0.0,  uTexel.y)).rgb;
  vec3 e = texture2D(uTexSampler, vTexCoord + vec2( uTexel.x, 0.0)).rgb;
  vec3 w = texture2D(uTexSampler, vTexCoord + vec2(-uTexel.x, 0.0)).rgb;
  vec3 blur = (n + s + e + w) * 0.25;
  c = c + (c - blur) * uSharpen * 2.0;
  float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
  c = mix(vec3(l), c, uSaturation);
  c = (c - 0.5) * uContrast + 0.5 + uBrightness;
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}"""
    }
}
