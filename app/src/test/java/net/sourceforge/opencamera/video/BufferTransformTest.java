package net.sourceforge.opencamera.video;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** SurfaceTexture transforms are built as FlipV * Crop * X (GLConsumer::computeTransformMatrix). */
public class BufferTransformTest {
    private static final float[] FLIP_H = {-1,0,0,0, 0,1,0,0, 0,0,1,0, 1,0,0,1};
    private static final float[] FLIP_V = {1,0,0,0, 0,-1,0,0, 0,0,1,0, 0,1,0,1};
    private static final float[] ROT_90 = {0,1,0,0, -1,0,0,0, 0,0,1,0, 1,0,0,1};
    private static final float[] IDENTITY = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};

    private static float[] multiply(float[] l, float[] r) { // column-major, like android.opengl.Matrix
        float[] out = new float[16];
        for (int c = 0; c < 4; c++) for (int row = 0; row < 4; row++)
            for (int k = 0; k < 4; k++) out[c * 4 + row] += l[k * 4 + row] * r[c * 4 + k];
        return out;
    }

    @Test public void everyRotationAndMirrorIsRemovedAndCropKept() {
        float[] crop = {0.98f,0,0,0, 0,0.95f,0,0, 0,0,1,0, 0.01f,0.03f,0,1};
        float[] expected = multiply(FLIP_V, crop);
        for (int flags = 0; flags < 8; flags++) {
            float[] x = IDENTITY;
            if ((flags & 1) != 0) x = multiply(x, FLIP_H);
            if ((flags & 2) != 0) x = multiply(x, FLIP_V);
            if ((flags & 4) != 0) x = multiply(x, ROT_90);
            float[] transform = multiply(FLIP_V, multiply(crop, x));
            CreativeFilters.stripBufferTransform(transform);
            for (int i : new int[] {0, 1, 4, 5, 12, 13})
                assertEquals("transform flags " + flags + " element " + i, expected[i], transform[i], 1e-5f);
        }
    }
}
