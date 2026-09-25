package ar.com.biopus.abismocam;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import java.nio.ByteBuffer;

/** Buffers reutilizados, exclusivos del executor de cámara. No modifica el original. */
final class FrameScaler {
    private Bitmap input, output;
    private ByteBuffer packed, pixels;
    private Canvas canvas;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

    ByteBuffer scale(ByteBuffer source, int width, int height, int stride, int outWidth, int outHeight) {
        if (input == null || input.getWidth() != width || input.getHeight() != height) {
            if (input != null) input.recycle();
            input = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            packed = ByteBuffer.allocateDirect(width * height * 4);
        }
        if (output == null || output.getWidth() != outWidth || output.getHeight() != outHeight) {
            if (output != null) output.recycle();
            output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
            canvas = new Canvas(output);
            pixels = ByteBuffer.allocateDirect(outWidth * outHeight * 4);
        }
        packed.clear();
        ByteBuffer row = source.duplicate();
        int start = source.position();
        for (int y = 0; y < height; y++) {
            row.limit(source.limit()); row.position(start + y * stride); row.limit(row.position() + width * 4);
            packed.put(row);
        }
        packed.flip(); input.copyPixelsFromBuffer(packed);
        int cropWidth = width, cropHeight = height;
        if ((long)width * outHeight > (long)height * outWidth) cropWidth = height * outWidth / outHeight;
        else cropHeight = width * outHeight / outWidth;
        int left = (width - cropWidth) / 2, top = (height - cropHeight) / 2;
        canvas.drawBitmap(input, new Rect(left, top, left + cropWidth, top + cropHeight), new Rect(0, 0, outWidth, outHeight), paint);
        pixels.clear(); output.copyPixelsToBuffer(pixels); pixels.flip();
        return pixels;
    }
}
