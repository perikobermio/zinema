package com.zinema.client;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import org.json.JSONObject;

public class PlayerActivity extends Activity {
    private static final int VLC_REQUEST = 41;
    private String path;
    private String title;
    private String posterUrl;
    private String ownerNodeId;
    private String mediaId;
    private String ownerKind;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String url = getIntent().getStringExtra("url");
        path = getIntent().getStringExtra("path");
        title = getIntent().getStringExtra("title");
        posterUrl = getIntent().getStringExtra("poster_url");
        ownerNodeId = getIntent().getStringExtra("owner_node_id");
        mediaId = getIntent().getStringExtra("media_id");
        ownerKind = getIntent().getStringExtra("owner_kind");
        long resumeAt = getIntent().getLongExtra("position", 0L);
        if (url == null || url.isEmpty()) {
            finish();
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
            Toast.makeText(this, "Instala VLC para reproducir vídeos.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != VLC_REQUEST) return;
        if (resultCode == RESULT_OK && data != null) {
            long position = data.getLongExtra("extra_position", -1L);
            long duration = data.getLongExtra("extra_duration", -1L);
            if (position >= 0L && duration > 0L && position >= duration - 5000L) {
                getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                        .remove("watch:" + path).apply();
            } else if (position > 10000L) {
                saveProgress(position);
            }
        }
        finish();
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
                    .putString("watch:" + path, item.toString()).apply();
        } catch (Exception ignored) { }
    }
}
