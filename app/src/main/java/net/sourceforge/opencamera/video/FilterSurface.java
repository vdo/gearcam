package net.sourceforge.opencamera.video;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.*;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A camera output Surface that filters on the GPU and forwards sensor timestamps unchanged.
 *  Each instance owns its GL thread and input, but never owns the destination Surface. */
public final class FilterSurface implements AutoCloseable {
    private final Context context;
    private final HandlerThread thread = new HandlerThread("GearCam filter");
    private final Handler handler;
    private final Runnable onFailure;
    private final boolean sensorOrientation;
    private volatile boolean closed;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture texture;
    private Surface input;
    private int program, width, height, transformLocation, lookLocation;
    private final FloatBuffer[] vertices = new FloatBuffer[2];
    private int attributeIndex;
    private static EGLDisplay sharedDisplay = EGL14.EGL_NO_DISPLAY;
    private static int displayUsers;
    private final float[] transform = new float[16];
    private long lastTimestamp;

    /** @param sensorOrientation true for an encoder: output raw sensor-oriented frames, so the MP4
     *                          orientation hint rotates them exactly once. False keeps the upright preview. */
    public FilterSurface(Context context, Surface destination, int width, int height, boolean sensorOrientation, Runnable onFailure) {
        this.context = context.getApplicationContext(); this.onFailure = onFailure; this.sensorOrientation = sensorOrientation;
        thread.start(); handler = new Handler(thread.getLooper());
        CountDownLatch ready = new CountDownLatch(1); RuntimeException[] failure = new RuntimeException[1];
        handler.post(() -> {
            try { if (!closed) initialize(destination, width, height); }
            catch (RuntimeException e) { failure[0] = e; destroy(); }
            finally { ready.countDown(); }
        });
        try {
            if (!ready.await(5, TimeUnit.SECONDS)) { close(); throw new IllegalStateException("Filter renderer did not start"); }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); close(); throw new IllegalStateException(e); }
        if (failure[0] != null) { close(); throw failure[0]; }
    }
    public Surface input() { return input; }

    private void initialize(Surface destination, int requestedWidth, int requestedHeight) {
        display = acquireDisplay();
        int[] attributes = {EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,
                EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,
                0x3142,1, EGL14.EGL_NONE}; // EGL_RECORDABLE_ANDROID
        EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
        require(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0, "Recordable EGL config");
        eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE}, 0);
        require(eglContext != EGL14.EGL_NO_CONTEXT, "EGL context");
        window = EGL14.eglCreateWindowSurface(display, configs[0], destination, new int[] {EGL14.EGL_NONE}, 0);
        require(window != EGL14.EGL_NO_SURFACE, "EGL output surface");
        require(EGL14.eglMakeCurrent(display, window, window, eglContext), "EGL make current");
        int[] dimension = new int[1];
        EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, dimension, 0); width = requestedWidth > 0 ? requestedWidth : dimension[0];
        EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, dimension, 0); height = requestedHeight > 0 ? requestedHeight : dimension[0];
        require(width > 0 && height > 0, "Filter dimensions");
        String vertex = "attribute vec2 point; attribute vec2 coord; uniform mat4 transform; varying vec2 uv; varying vec2 position;"
                + "void main(){ gl_Position=vec4(point,0.0,1.0); uv=(transform*vec4(coord,0.0,1.0)).xy; position=coord; }";
        int vs = shader(GLES20.GL_VERTEX_SHADER, vertex), fs = shader(GLES20.GL_FRAGMENT_SHADER, CreativeFilters.FRAGMENT);
        program = GLES20.glCreateProgram(); GLES20.glAttachShader(program, vs); GLES20.glAttachShader(program, fs); GLES20.glLinkProgram(program);
        int[] linked = new int[1]; GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs);
        require(linked[0] != 0, "Filter shader link: " + GLES20.glGetProgramInfoLog(program));
        GLES20.glUseProgram(program);
        attribute("point", new float[] {-1,-1, 1,-1, -1,1, 1,1});
        attribute("coord", new float[] {0,0, 1,0, 0,1, 1,1});
        transformLocation = GLES20.glGetUniformLocation(program, "transform"); lookLocation = GLES20.glGetUniformLocation(program, "look");
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0);
        int[] names = new int[1]; GLES20.glGenTextures(1, names, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, names[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        texture = new SurfaceTexture(names[0]); texture.setDefaultBufferSize(width, height);
        input = new Surface(texture);
        texture.setOnFrameAvailableListener(ignored -> render(), handler);
    }
    private void attribute(String name, float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(values).position(0); vertices[attributeIndex++] = buffer;
        int location = GLES20.glGetAttribLocation(program, name);
        GLES20.glVertexAttribPointer(location, 2, GLES20.GL_FLOAT, false, 0, buffer); GLES20.glEnableVertexAttribArray(location);
    }
    private static int shader(int type, String source) {
        int shader = GLES20.glCreateShader(type); GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader);
        int[] compiled = new int[1]; GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) { String log = GLES20.glGetShaderInfoLog(shader); GLES20.glDeleteShader(shader); throw new IllegalStateException(log); }
        return shader;
    }
    private void render() {
        if (closed) return;
        try {
            texture.updateTexImage(); long timestamp = texture.getTimestamp();
            if (timestamp <= lastTimestamp) return;
            lastTimestamp = timestamp; texture.getTransformMatrix(transform);
            if (sensorOrientation) CreativeFilters.stripBufferTransform(transform);
            GLES20.glViewport(0, 0, width, height);
            GLES20.glUniformMatrix4fv(transformLocation, 1, false, transform, 0);
            GLES20.glUniform1i(lookLocation, CreativeFilters.selected(context));
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            require(GLES20.glGetError() == GLES20.GL_NO_ERROR, "Filter draw");
            EGLExt.eglPresentationTimeANDROID(display, window, timestamp);
            require(EGL14.eglSwapBuffers(display, window), "Filter output");
        } catch (RuntimeException e) {
            closed = true; android.util.Log.e("GearCamFilter", "Renderer failed", e);
            destroy(); thread.quitSafely(); new Handler(android.os.Looper.getMainLooper()).post(onFailure);
        }
    }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
    private void destroy() {
        if (texture != null) { texture.setOnFrameAvailableListener(null); texture.release(); texture = null; }
        if (input != null) { input.release(); input = null; }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window);
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext);
            EGL14.eglReleaseThread(); releaseDisplay();
            display = EGL14.EGL_NO_DISPLAY; window = EGL14.EGL_NO_SURFACE; eglContext = EGL14.EGL_NO_CONTEXT;
        }
    }
    private static synchronized EGLDisplay acquireDisplay() {
        if (displayUsers == 0) {
            EGLDisplay created = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            require(EGL14.eglInitialize(created, version, 0, version, 1), "EGL initialize");
            sharedDisplay = created;
        }
        displayUsers++; return sharedDisplay;
    }
    private static synchronized void releaseDisplay() {
        if (--displayUsers == 0) { EGL14.eglTerminate(sharedDisplay); sharedDisplay = EGL14.EGL_NO_DISPLAY; }
    }
    @Override public void close() {
        if (closed) return;
        closed = true; CountDownLatch stopped = new CountDownLatch(1);
        handler.post(() -> { try { destroy(); } finally { stopped.countDown(); thread.quitSafely(); } });
        try { stopped.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
