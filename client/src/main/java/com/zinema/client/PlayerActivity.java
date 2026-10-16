package com.zinema.client;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private String seriesId;
    private int seasonNumber;
    private String progressKey;
    private String profileId;
    private VideoView videoView;
    private boolean internalPlayer;
    private boolean prepared;
    private boolean playbackFinishing;
    private MediaController mediaController;
    private TextView nextEpisodeButton;
    private JSONObject nextEpisode;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Runnable nextEpisodeTicker = new Runnable() {
        @Override public void run() {
            if (!internalPlayer || !prepared || videoView == null || nextEpisode == null) return;
            int duration = videoView.getDuration();
            int position = videoView.getCurrentPosition();
            if (duration > 0 && duration - position <= Math.min(30000, duration / 20)) {
                if (nextEpisodeButton != null) nextEpisodeButton.setVisibility(TextView.VISIBLE);
            } else if (nextEpisodeButton != null) nextEpisodeButton.setVisibility(TextView.GONE);
            progressHandler.postDelayed(this, 1000L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String url = getIntent().getStringExtra("url");
        path = getIntent().getStringExtra("path");
        title = getIntent().getStringExtra("title");
        posterUrl = getIntent().getStringExtra("poster_url");
        ownerNodeId = getIntent().getStringExtra("owner_node_id");
        mediaId = getIntent().getStringExtra("media_id");
        ownerKind = getIntent().getStringExtra("owner_kind");
        seriesId = getIntent().getStringExtra("series_id");
        seasonNumber = getIntent().getIntExtra("season_number", 0);
        progressKey = getIntent().getStringExtra("progress_key");
        profileId = getIntent().getStringExtra("profile_id");
        String nextRaw = getIntent().getStringExtra("next_episode_json");
        try { if (nextRaw != null && !nextRaw.isEmpty()) nextEpisode = new JSONObject(nextRaw); }
        catch (Exception ignored) { }
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
        if (nextEpisode != null) {
            nextEpisodeButton = new TextView(this);
            nextEpisodeButton.setText("Ver siguiente capítulo  ▶");
            nextEpisodeButton.setTextColor(Color.WHITE);
            nextEpisodeButton.setTextSize(16);
            nextEpisodeButton.setTypeface(null, android.graphics.Typeface.BOLD);
            nextEpisodeButton.setGravity(Gravity.CENTER);
            nextEpisodeButton.setPadding(24, 14, 24, 14);
            nextEpisodeButton.setBackgroundColor(0xEEB20710);
            nextEpisodeButton.setFocusable(true);
            nextEpisodeButton.setClickable(true);
            nextEpisodeButton.setVisibility(TextView.GONE);
            FrameLayout.LayoutParams nextParams = new FrameLayout.LayoutParams(-2, -2,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            nextParams.bottomMargin = 90;
            root.addView(nextEpisodeButton, nextParams);
            nextEpisodeButton.setOnClickListener(v -> {
                getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                        .putString("auto_play_episode:" + profileId, nextEpisode.toString()).apply();
                finishWithProgress(true);
            });
        }
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
            progressHandler.removeCallbacks(nextEpisodeTicker);
            progressHandler.postDelayed(nextEpisodeTicker, 1000L);
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
            if (isComplete(position, duration)) {
                markCompleted();
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
        if (internalPlayer && prepared && !playbackFinishing) saveCurrentProgress(false);
        super.onPause();
    }

    private void finishWithProgress(boolean completed) {
        playbackFinishing = true;
        if (videoView == null) {
            finish();
            return;
        }
        long position = Math.max(0, videoView.getCurrentPosition());
        long duration = Math.max(0, videoView.getDuration());
        if (completed || isComplete(position, duration)) {
            markCompleted();
        } else if (position > 10000L) {
            saveProgress(position);
            markProfileStateDirty();
        }
        Intent result = new Intent();
        result.putExtra("extra_position", position);
        result.putExtra("extra_duration", duration);
        setResult(RESULT_OK, result);
        progressHandler.removeCallbacks(nextEpisodeTicker);
        if (videoView.isPlaying()) videoView.pause();
        finish();
    }

    private void saveCurrentProgress(boolean completed) {
        if (videoView == null || !prepared) return;
        long position = Math.max(0, videoView.getCurrentPosition());
        long duration = Math.max(0, videoView.getDuration());
        if (completed || isComplete(position, duration)) {
            markCompleted();
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
            item.put("series_id", seriesId == null ? "" : seriesId);
            item.put("season_number", seasonNumber);
            item.put("position", (int) Math.min(Integer.MAX_VALUE, position));
            item.put("updated", System.currentTimeMillis());
            getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                    .putString(progressKey == null || progressKey.isEmpty() ? "watch:" + path : progressKey,
                            item.toString()).apply();
        } catch (Exception ignored) { }
    }

    private boolean isComplete(long position, long duration) {
        return duration > 0L && position >= duration * 0.90d;
    }

    private void markCompleted() {
        android.content.SharedPreferences.Editor editor = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .edit().remove(progressKey);
        if (profileId != null && !profileId.isEmpty() && mediaId != null && !mediaId.isEmpty()
                && !"null".equals(mediaId)) {
            editor.putBoolean("p:" + profileId + ":seen:file:" + mediaId, true)
                    .putString("completed_media:" + profileId, mediaId)
                    .putString("completed_owner:" + profileId, ownerNodeId == null ? "" : ownerNodeId)
                    .putString("completed_kind:" + profileId, ownerKind == null ? "" : ownerKind);
            if ("movie".equals(ownerKind) && ownerNodeId != null && !ownerNodeId.isEmpty())
                editor.putBoolean("p:" + profileId + ":seen:node:" + ownerNodeId, true);
            if ("season".equals(ownerKind) && seriesId != null && !seriesId.isEmpty()) {
                String seriesSuggestionKey = "series_next_episode:" + profileId + ":" + seriesId;
                if (nextEpisode != null) {
                    try {
                        JSONObject suggestion = new JSONObject(nextEpisode.toString());
                        suggestion.put("series_id", seriesId);
                        suggestion.put("suggestion_type", ownerNodeId != null
                                && ownerNodeId.equals(suggestion.optString("owner_node_id"))
                                ? "next_episode" : "next_season");
                        suggestion.put("updated", System.currentTimeMillis());
                        editor.putString(seriesSuggestionKey, suggestion.toString())
                                .putString("next_episode:" + profileId, suggestion.toString());
                    } catch (Exception ignored) { }
                } else {
                    editor.remove(seriesSuggestionKey);
                    String oldSuggestion = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                            .getString("next_episode:" + profileId, "");
                    try {
                        if (!oldSuggestion.isEmpty()
                                && seriesId.equals(new JSONObject(oldSuggestion).optString("series_id")))
                            editor.remove("next_episode:" + profileId);
                    } catch (Exception ignored) { }
                }
            } else if (nextEpisode != null) {
                editor.putString("next_episode:" + profileId, nextEpisode.toString());
            }
        }
        editor.apply();
        markProfileStateDirty();
    }

    private void markProfileStateDirty() {
        if (profileId == null || profileId.isEmpty()) return;
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                .putBoolean("profile_sync_dirty:" + profileId, true).apply();
    }
}
