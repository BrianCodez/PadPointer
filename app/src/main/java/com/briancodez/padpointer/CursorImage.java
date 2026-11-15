package com.briancodez.padpointer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

final class CursorImage {
    private CursorImage() {}
    static File file(Context context) { return new File(context.getFilesDir(), "cursor.png"); }

    /** Keep only a normalized, bounded PNG in private storage; no long-term URI grant is needed. */
    static void importImage(Context context, Uri uri) throws IOException {
        Bitmap bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.getContentResolver(), uri), (decoder, info, source) -> {
            int width = info.getSize().getWidth(), height = info.getSize().getHeight();
            double scale = Math.min(1d, 512d / Math.max(width, height));
            decoder.setTargetSize(Math.max(1, (int) (width * scale)), Math.max(1, (int) (height * scale)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
        AtomicFile destination = new AtomicFile(file(context));
        FileOutputStream stream = null;
        try {
            stream = destination.startWrite();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("Image could not be saved");
            destination.finishWrite(stream);
        } catch (IOException | RuntimeException error) {
            if (stream != null) destination.failWrite(stream);
            throw error;
        } finally { bitmap.recycle(); }
    }
}
