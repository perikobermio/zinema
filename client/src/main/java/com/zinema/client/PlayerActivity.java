package com.zinema.client;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import org.json.JSONObject;

public class PlayerActivity extends Activity {
    private VideoView video;
    private Handler handler;
    private String path;
    private String title;
    private String posterUrl;
    private boolean prepared;
    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (prepared && video != null && video.isPlaying()) saveProgress(false);
            if (handler != null) handler.postDelayed(this, 10000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        String url = getIntent().getStringExtra("url");
        path = getIntent().getStringExtra("path");
        title = getIntent().getStringExtra("title");
        posterUrl = getIntent().getStringExtra("poster_url");
        int resumeAt = getIntent().getIntExtra("position", 0);
        video = new VideoView(this);
        video.setBackgroundColor(0xFF000000);
        setContentView(video);
        MediaController controls = new MediaController(this);
        controls.setAnchorView(video);
        video.setMediaController(controls);
        video.setVideoURI(Uri.parse(url));
        video.setOnPreparedListener(player -> {
            prepared = true;
            player.setOnVideoSizeChangedListener((mp, width, height) -> controls.setAnchorView(video));
            if (resumeAt > 15000) video.seekTo(resumeAt);
            video.start();
            handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(progressTick, 10000);
        });
        video.setOnCompletionListener(player -> {
            getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit().remove("watch:" + path).apply();
            finish();
        });
        video.setOnErrorListener((player, what, extra) -> {
            android.widget.Toast.makeText(this, "No se pudo reproducir este formato de vídeo.", android.widget.Toast.LENGTH_LONG).show();
            finish();
            return true;
        });
    }

    private void saveProgress(boolean force) {
        if (video == null || !prepared) return;
        int position = video.getCurrentPosition();
        if (position < 10000) return;
        try {
            JSONObject item = new JSONObject();
            item.put("title", title);
            item.put("path", path);
            item.put("stream", path);
            item.put("poster_url", posterUrl == null ? "" : posterUrl);
            item.put("position", position);
            item.put("updated", System.currentTimeMillis());
            getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                    .putString("watch:" + path, item.toString()).apply();
        } catch (Exception ignored) { }
    }

    @Override protected void onPause() {
        saveProgress(true);
        if (video != null && video.isPlaying()) video.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (handler != null) handler.removeCallbacks(progressTick);
        if (video != null) video.stopPlayback();
        super.onDestroy();
    }
}
