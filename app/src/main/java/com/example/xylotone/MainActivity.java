package com.example.xylotone;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.example.xylotone.engine.MelodyExtractor;
import com.example.xylotone.engine.RingtoneMaker;
import com.example.xylotone.engine.WavWriter;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final double MAX_SONG_SECONDS = 480; // дольше 8 минут не читаем

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private float[] song;          // песня: моно, 22050 Гц
    private String songName = "";
    private File resultFile;       // готовый рингтон (во временной папке приложения)
    private Uri savedUri;          // ссылка на него после сохранения в «Рингтоны»
    private MediaPlayer player;

    private Button btnPick, btnMake, btnPlay, btnSave, btnSet;
    private TextView tvFile, tvStart, tvStatus;
    private RadioGroup groupLength;
    private CheckBox cbAuto;
    private SeekBar seekStart;
    private ProgressBar progress;
    private View layoutResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnPick = findViewById(R.id.btnPick);
        btnMake = findViewById(R.id.btnMake);
        btnPlay = findViewById(R.id.btnPlay);
        btnSave = findViewById(R.id.btnSave);
        btnSet = findViewById(R.id.btnSet);
        tvFile = findViewById(R.id.tvFile);
        tvStart = findViewById(R.id.tvStart);
        tvStatus = findViewById(R.id.tvStatus);
        groupLength = findViewById(R.id.groupLength);
        cbAuto = findViewById(R.id.cbAuto);
        seekStart = findViewById(R.id.seekStart);
        progress = findViewById(R.id.progress);
        layoutResult = findViewById(R.id.layoutResult);

        btnPick.setOnClickListener(v -> pickFile());
        btnMake.setOnClickListener(v -> makeRingtone());
        btnPlay.setOnClickListener(v -> togglePlay());
        btnSave.setOnClickListener(v -> saveOnly());
        btnSet.setOnClickListener(v -> setAsDefault());
        cbAuto.setOnCheckedChangeListener((b, checked) -> updateStartUi());
        groupLength.setOnCheckedChangeListener((g, id) -> updateSeekRange());
        seekStart.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                updateStartUi();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @SuppressWarnings("deprecation")
    private void handleIntent(Intent intent) {
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri uri = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
                    : (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) loadSong(uri);
        }
    }

    // ---------- выбор и чтение песни ----------

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            loadSong(data.getData());
        }
    }

    private void loadSong(Uri uri) {
        stopPlayback();
        song = null;
        resultFile = null;
        savedUri = null;
        layoutResult.setVisibility(View.GONE);
        btnMake.setEnabled(false);
        setBusy(true, "Читаю песню…");
        final String name = queryName(uri);
        worker.execute(() -> {
            try {
                float[] pcm = AudioDecoder.decodeToMono(this, uri, MelodyExtractor.RATE, MAX_SONG_SECONDS,
                        f -> runOnUiThread(() -> progress.setProgress((int) (f * 100))));
                runOnUiThread(() -> onSongLoaded(pcm, name));
            } catch (Exception e) {
                runOnUiThread(() -> onError("Не удалось прочитать файл: " + e.getMessage()));
            } catch (OutOfMemoryError e) {
                runOnUiThread(() -> onError("Файл слишком большой для этого телефона"));
            }
        });
    }

    private void onSongLoaded(float[] pcm, String name) {
        song = pcm;
        songName = name;
        tvFile.setText(String.format(Locale.US, "%s  ·  %s", name, formatTime(durationSec())));
        updateSeekRange();
        setBusy(false, "Песня загружена. Нажмите «Сделать рингтон».");
        btnMake.setEnabled(true);
    }

    // ---------- настройки фрагмента ----------

    private int lengthSec() {
        int id = groupLength.getCheckedRadioButtonId();
        if (id == R.id.rb15) return 15;
        if (id == R.id.rb30) return 30;
        return 20;
    }

    private double durationSec() {
        return song == null ? 0 : song.length / (double) MelodyExtractor.RATE;
    }

    private void updateSeekRange() {
        int max = (int) Math.max(0, Math.floor(durationSec() - lengthSec()));
        seekStart.setMax(max);
        if (seekStart.getProgress() > max) seekStart.setProgress(max);
        updateStartUi();
    }

    private void updateStartUi() {
        boolean manual = !cbAuto.isChecked() && song != null;
        seekStart.setVisibility(manual ? View.VISIBLE : View.GONE);
        tvStart.setVisibility(manual ? View.VISIBLE : View.GONE);
        tvStart.setText("Начало фрагмента: " + formatTime(seekStart.getProgress()));
    }

    // ---------- создание рингтона ----------

    private void makeRingtone() {
        if (song == null) return;
        stopPlayback();
        savedUri = null;
        layoutResult.setVisibility(View.GONE);
        btnMake.setEnabled(false);
        setBusy(true, "Начинаю…");
        final double start = cbAuto.isChecked() ? -1 : seekStart.getProgress();
        final int len = lengthSec();
        worker.execute(() -> {
            try {
                RingtoneMaker.Output out = RingtoneMaker.make(song, start, len, (text, fr) ->
                        runOnUiThread(() -> {
                            tvStatus.setText(text);
                            progress.setProgress((int) (fr * 100));
                        }));
                File f = new File(getCacheDir(), "xylophone_ringtone.wav");
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    WavWriter.write(fos, out.pcm, out.rate);
                }
                runOnUiThread(() -> onMade(f, out));
            } catch (Exception e) {
                runOnUiThread(() -> onError(e.getMessage() == null ? "Что-то пошло не так" : e.getMessage()));
            }
        });
    }

    private void onMade(File f, RingtoneMaker.Output out) {
        resultFile = f;
        setBusy(false, String.format(Locale.US, "Готово: %d нот, фрагмент песни с %s.",
                out.notes.size(), formatTime(out.startSec)));
        btnMake.setEnabled(true);
        layoutResult.setVisibility(View.VISIBLE);
        play();
    }

    // ---------- прослушивание ----------

    private void togglePlay() {
        if (player != null) stopPlayback();
        else play();
    }

    private void play() {
        if (resultFile == null) return;
        stopPlayback();
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setDataSource(resultFile.getAbsolutePath());
            player.setOnCompletionListener(mp -> stopPlayback());
            player.prepare();
            player.start();
            btnPlay.setText(R.string.stop);
        } catch (Exception e) {
            stopPlayback();
            Toast.makeText(this, "Не удалось проиграть: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopPlayback() {
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
                // ничего страшного
            }
            player = null;
        }
        btnPlay.setText(R.string.play);
    }

    // ---------- сохранение и установка ----------

    private String ringtoneTitle() {
        String base = songName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = base.replaceAll("[\\\\/:*?\"<>|]", " ").trim();
        if (base.length() > 40) base = base.substring(0, 40).trim();
        return "Ксилофон - " + (base.isEmpty() ? "рингтон" : base);
    }

    private synchronized Uri ensureSaved() throws Exception {
        if (savedUri == null) savedUri = RingtoneStore.save(this, resultFile, ringtoneTitle());
        return savedUri;
    }

    private void saveOnly() {
        if (resultFile == null) return;
        btnSave.setEnabled(false);
        worker.execute(() -> {
            try {
                ensureSaved();
                runOnUiThread(() -> {
                    btnSave.setEnabled(true);
                    tvStatus.setText("Сохранено в «Рингтоны». Выберите его в Настройки → Звук → Мелодия звонка.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnSave.setEnabled(true);
                    onError("Не удалось сохранить: " + e.getMessage());
                });
            }
        });
    }

    private void setAsDefault() {
        if (resultFile == null) return;
        if (!Settings.System.canWrite(this)) {
            Toast.makeText(this, "Разрешите приложению менять настройки, затем нажмите кнопку ещё раз",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        btnSet.setEnabled(false);
        worker.execute(() -> {
            try {
                Uri uri = ensureSaved();
                RingtoneManager.setActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE, uri);
                runOnUiThread(() -> {
                    btnSet.setEnabled(true);
                    tvStatus.setText("Готово! Этот рингтон теперь стоит на звонке.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnSet.setEnabled(true);
                    onError("Не удалось поставить на звонок: " + e.getMessage()
                            + "\nМелодия сохранена в «Рингтоны» - выберите её вручную в настройках звука.");
                });
            }
        });
    }

    // ---------- общее ----------

    private void setBusy(boolean busy, String status) {
        progress.setVisibility(busy ? View.VISIBLE : View.INVISIBLE);
        progress.setProgress(0);
        btnPick.setEnabled(!busy);
        tvStatus.setText(status);
    }

    private void onError(String message) {
        setBusy(false, message);
        btnMake.setEnabled(song != null);
    }

    private String queryName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n;
            }
        } catch (Exception ignored) {
            // имя необязательно
        }
        return "песня";
    }

    private static String formatTime(double sec) {
        int s = (int) Math.round(sec);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopPlayback();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayback();
        worker.shutdownNow();
    }
}
