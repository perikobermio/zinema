package com.zinema.client;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONObject;

public class PlayerActivity extends Activity {
    private static final int VLC_REQUEST = 41;
    private String path;
    private String title;
    private String posterUrl;
    private String ownerNodeId;
    private String mediaId;
    private String ownerKind;
    private String progressKey;
    private String profileId;
    private VideoView videoView;
    private boolean internalPlayer;
    private boolean prepared;
    private MediaController mediaController;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String url = getIntent().getStringExtra("url");
        path = getIntent().getStringExtra("path");
        title = getIntent().getStringExtra("title");
        posterUrl = getIntent().getStringExtra("poster_url");
        ownerNodeId = getIntent().getStringExtra("owner_node_id");
        mediaId = getIntent().getStringExtra("media_id");
        ownerKind = getIntent().getStringExtra("owner_kind");
        progressKey = getIntent().getStringExtra("progress_key");
        profileId = getIntent().getStringExtra("profile_id");
        if (progressKey == null || progressKey.isEmpty()) progressKey = "watch:" + path;
        long resumeAt = getIntent().getLongExtra("position", 0L);
        if (url == null || url.isEmpty()) {
            finish();
            return;
        }

        boolean preferVlc = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getString("player", "vlc").equals("vlc");
        if (!preferVlc) {
            startInternalPlayer(url, resumeAt);
            return;
        }

        Intent vlc = new Intent(Intent.ACTION_VIEW);
        vlc.setComponent(new ComponentName("org.videolan.vlc", "org.videolan.vlc.StartActivity"));
        vlc.setDataAndType(Uri.parse(url), "video/*");
        vlc.putExtra("title", title == null ? "Zinema" : title);
        vlc.putExtra("position", (int) Math.max(0L, resumeAt));
        vlc.putExtra("return_result", true);
        vlc.putExtra("secure_uri", true);
        try {
            startActivityForResult(vlc, VLC_REQUEST);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "VLC no está instalado. Se abrirá el reproductor de Zinema.",
                    Toast.LENGTH_LONG).show();
            startInternalPlayer(url, resumeAt);
        }
    }

    private void startInternalPlayer(String url, long resumeAt) {
        internalPlayer = true;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        videoView = new VideoView(this);
        videoView.setFocusable(true);
        videoView.setFocusableInTouchMode(true);
        root.addView(videoView, new FrameLayout.LayoutParams(-1, -1));
        TextView heading = new TextView(this);
        heading.setText(title == null || title.isEmpty() ? "Zinema" : title);
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(18);
        heading.setSingleLine(true);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.setPadding(24, 16, 24, 16);
        heading.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams headingParams = new FrameLayout.LayoutParams(-1, -2,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        root.addView(heading, headingParams);
        setContentView(root);
        mediaController = new MediaController(this);
        mediaController.setAnchorView(videoView);
        mediaController.setContentDescription("Controles de reproducción");
        videoView.setMediaController(mediaController);
        videoView.setOnPreparedListener(player -> {
            prepared = true;
            long duration = player.getDuration();
            if (resumeAt > 0L && duration > 0L && resumeAt < duration - 5000L) {
                videoView.seekTo((int) Math.min(Integer.MAX_VALUE, resumeAt));
            }
            videoView.start();
            videoView.requestFocus();
        });
        videoView.setOnCompletionListener(player -> finishWithProgress(true));
        videoView.setOnErrorListener((player, what, extra) -> {
            Toast.makeText(this, "No se pudo reproducir este formato en el dispositivo.",
                    Toast.LENGTH_LONG).show();
            finishWithProgress(false);
            return true;
        });
        videoView.setVideoURI(Uri.parse(url));
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != VLC_REQUEST) return;
        if (resultCode == RESULT_OK && data != null) {
            long position = data.getLongExtra("extra_position", -1L);
            long duration = data.getLongExtra("extra_duration", -1L);
            if (position >= 0L && duration > 0L && position >= duration - 5000L) {
                getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                        .remove(progressKey).apply();
                markProfileStateDirty();
            } else if (position > 10000L) {
                saveProgress(position);
                markProfileStateDirty();
            }
        }
        finish();
    }

    @Override public void onBackPressed() {
        if (internalPlayer) finishWithProgress(false);
        else super.onBackPressed();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (internalPlayer && prepared && videoView != null) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                if (videoView.isPlaying()) videoView.pause(); else videoView.start();
                if (mediaController != null) mediaController.show();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
                videoView.seekTo(Math.max(0, videoView.getCurrentPosition() - 10000));
                if (mediaController != null) mediaController.show();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                int duration = Math.max(0, videoView.getDuration());
                videoView.seekTo(Math.min(duration, videoView.getCurrentPosition() + 10000));
                if (mediaController != null) mediaController.show();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onPause() {
        if (internalPlayer && prepared) saveCurrentProgress(false);
        super.onPause();
    }

    private void finishWithProgress(boolean completed) {
        if (videoView == null) {
            finish();
            return;
        }
        long position = Math.max(0, videoView.getCurrentPosition());
        long duration = Math.max(0, videoView.getDuration());
        if (completed || (duration > 0 && position >= duration - 5000L)) {
            getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                    .remove(progressKey).apply();
            markProfileStateDirty();
        } else if (position > 10000L) {
            saveProgress(position);
            markProfileStateDirty();
        }
        Intent result = new Intent();
        result.putExtra("extra_position", position);
        result.putExtra("extra_duration", duration);
        setResult(RESULT_OK, result);
        if (videoView.isPlaying()) videoView.pause();
        finish();
    }

    private void saveCurrentProgress(boolean completed) {
        if (videoView == null || !prepared) return;
        long position = Math.max(0, videoView.getCurrentPosition());
        long duration = Math.max(0, videoView.getDuration());
        if (completed || (duration > 0 && position >= duration - 5000L)) {
            getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                    .remove(progressKey).apply();
        } else if (position > 10000L) {
            saveProgress(position);
        }
        markProfileStateDirty();
    }

    private void saveProgress(long position) {
        if (path == null || path.isEmpty()) return;
        try {
            JSONObject item = new JSONObject();
            item.put("title", title == null ? "Vídeo" : title);
            item.put("path", path);
            item.put("stream", path);
            item.put("poster_url", posterUrl == null ? "" : posterUrl);
            item.put("owner_node_id", ownerNodeId == null ? "" : ownerNodeId);
            item.put("node_id", ownerNodeId == null ? "" : ownerNodeId);
            item.put("media_id", mediaId == null ? "" : mediaId);
            item.put("owner_kind", ownerKind == null ? "" : ownerKind);
            item.put("position", (int) Math.min(Integer.MAX_VALUE, position));
            item.put("updated", System.currentTimeMillis());
            getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                    .putString(progressKey == null || progressKey.isEmpty() ? "watch:" + path : progressKey,
                            item.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void markProfileStateDirty() {
        if (profileId == null || profileId.isEmpty()) return;
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                .putBoolean("profile_sync_dirty:" + profileId, true).apply();
    }
}
