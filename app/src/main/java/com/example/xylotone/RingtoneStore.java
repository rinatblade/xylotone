package com.example.xylotone;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Кладёт готовый файл в папку «Рингтоны» телефона, чтобы он появился в списке мелодий звонка. */
final class RingtoneStore {

    private RingtoneStore() {}

    static Uri save(Context ctx, File wav, String title) throws IOException {
        ContentResolver resolver = ctx.getContentResolver();

        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, title + ".wav");
        v.put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-wav");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_RINGTONES);
        v.put(MediaStore.Audio.Media.TITLE, title);
        v.put(MediaStore.Audio.Media.IS_RINGTONE, 1);
        v.put(MediaStore.Audio.Media.IS_NOTIFICATION, 0);
        v.put(MediaStore.Audio.Media.IS_ALARM, 0);
        v.put(MediaStore.Audio.Media.IS_MUSIC, 0);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri item = resolver.insert(collection, v);
        if (item == null) throw new IOException("Не удалось создать файл в папке «Рингтоны»");

        try (InputStream in = new FileInputStream(wav);
             OutputStream out = resolver.openOutputStream(item)) {
            if (out == null) throw new IOException("Не удалось открыть файл для записи");
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        } catch (IOException e) {
            resolver.delete(item, null, null);
            throw e;
        }

        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(item, done, null, null);
        return item;
    }
}
