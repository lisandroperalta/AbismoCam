package ar.com.biopus.abismocam;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

final class PhotoStore {
    private PhotoStore() {}

    /** Ejecutar fuera del hilo UI y del hilo que entrega video a NDI. */
    static Uri save(Context context, Bitmap photo) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String name = "Abismo_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date())
            + "_" + UUID.randomUUID().toString().substring(0, 8) + ".jpg";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AbismoCam");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        } else {
            File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AbismoCam");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("No se pudo crear Pictures/AbismoCam.");
            values.put(MediaStore.Images.Media.DATA, new File(directory, name).getAbsolutePath());
        }
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("No se pudo crear la foto en la galería.");
        try {
            try (OutputStream output = resolver.openOutputStream(uri)) {
                if (output == null || !photo.compress(Bitmap.CompressFormat.JPEG, 95, output))
                    throw new IOException("No se pudo escribir la foto.");
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                if (resolver.update(uri, ready, null, null) != 1) throw new IOException("No se pudo publicar la foto en la galería.");
            }
            return uri;
        } catch (IOException | RuntimeException error) {
            try { resolver.delete(uri, null, null); } catch (RuntimeException ignored) { /* conservar error original */ }
            throw error;
        }
    }
}
