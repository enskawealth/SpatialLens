package com.spatiallens.app

import android.opengl.*
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class RectificationRenderer(private val calibration: CalibrationData) {

    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var program = 0
    private var mainTextureId = 0
    private var ultraTextureId = 0
    private var homographyLoc = 0

    private val quadVertices = floatArrayOf(
        -1f, -1f, 0f, 0f,
         1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
         1f, 1f, 1f, 1f
    )
    private val quadBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(quadVertices.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(quadVertices)
        .apply { position(0) }

    private val vertexShaderCode = """
        #version 300 es
        in vec4 aPosition;
        in vec2 aTexCoord;
        out vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #version 300 es
        precision mediump float;
        in vec2 vTexCoord;
        uniform sampler2D uMainTexture;
        uniform sampler2D uUltraTexture;
        uniform mat3 uHomography;
        uniform int uPass;
        out vec4 fragColor;

        vec2 applyHomography(mat3 H, vec2 uv) {
            vec3 p = H * vec3(uv, 1.0);
            return p.xy / p.z;
        }

        void main() {
            vec2 uv = vTexCoord;
            if (uPass == 0) {
                vec2 sampledUV = vec2(uv.x * 2.0, uv.y);
                fragColor = texture(uMainTexture, sampledUV);
            } else {
                vec2 sampledUV = vec2((uv.x - 0.5) * 2.0, uv.y);
                vec2 warpedUV = applyHomography(uHomography, sampledUV);
                if (warpedUV.x < 0.0 || warpedUV.x > 1.0 || warpedUV.y < 0.0 || warpedUV.y > 1.0) {
                    fragColor = vec4(0.0, 0.0, 0.0, 1.0);
                } else {
                    fragColor = texture(uUltraTexture, warpedUV);
                }
            }
        }
    """.trimIndent()

    fun setup(encoderSurface: Surface, width: Int, height: Int) {
        setupEGL(encoderSurface, width, height)
        compileShaders()
        createTextures(width, height)
    }

    private fun setupEGL(surface: Surface, width: Int, height: Int) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val attribList = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES3_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0
        )

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, configs[0], surface, surfaceAttribs, 0
        )

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        GLES20.glViewport(0, 0, width, height)
    }

    private fun compileShaders() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        homographyLoc = GLES20.glGetUniformLocation(program, "uHomography")
    }

    private fun createTextures(width: Int, height: Int) {
        val textures = IntArray(1)

        GLES20.glGenTextures(1, textures, 0)
        mainTextureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mainTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
            width, height, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null
        )

        GLES20.glGenTextures(1, textures, 0)
        ultraTextureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ultraTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
            width, height, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null
        )
    }

    fun renderFrame(mainYData: ByteBuffer, ultraYData: ByteBuffer) {
        if (eglDisplay == null || eglSurface == null) return

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        mainYData.rewind()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mainTextureId)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0,
            1920, 1080,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE,
            mainYData
        )

        ultraYData.rewind()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ultraTextureId)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0,
            1920, 1080,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE,
            ultraYData
        )

        GLES20.glUseProgram(program)

        GLES20.glUniformMatrix3fv(
            homographyLoc, 1, false,
            calibration.rectificationHomography, 0
        )

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mainTextureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uMainTexture"), 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ultraTextureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uUltraTexture"), 1)

        val posLoc = GLES20.glGetAttribLocation(program, "aPosition")
        val texLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        val passLoc = GLES20.glGetUniformLocation(program, "uPass")

        GLES20.glUniform1i(passLoc, 0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glEnableVertexAttribArray(texLoc)
        quadBuffer.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
        quadBuffer.position(2)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
        GLES20.glViewport(0, 0, 1920, 1080)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glUniform1i(passLoc, 1)
        GLES20.glViewport(1920, 0, 1920, 1080)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun release() {
        GLES20.glDeleteTextures(1, intArrayOf(mainTextureId), 0)
        GLES20.glDeleteTextures(1, intArrayOf(ultraTextureId), 0)
        GLES20.glDeleteProgram(program)
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }
}
