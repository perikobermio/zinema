package com.zinema.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static final int BG = Color.rgb(9, 10, 13);
    static final int PANEL = Color.rgb(24, 26, 32);
    static final int MUTED = Color.rgb(168, 172, 182);
    static final int RED = Color.rgb(229, 9, 20);
    static final String PREFS = "zinema_client";
    static final String DEFAULT_SERVER = "https://zinema.ebu.freemyip.com";
    final ExecutorService io = Executors.newFixedThreadPool(4);
    final LruCache<String, Bitmap> imageCache = new LruCache<>(24);
    SharedPreferences prefs;
    FrameLayout frame;
    LinearLayout page;
    LinearLayout content;
    TextView backButton;
    String server;
    String currentLibrary = "movies";
    String detailNodeId = "";
    boolean libraryMode;
    boolean showingDetail;
    List<JSONObject> movies = new ArrayList<>();
    List<JSONObject> series = new ArrayList<>();
    final List<JSONObject> breadcrumbs = new ArrayList<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        server = trimBase(prefs.getString("server", DEFAULT_SERVER));
        buildShell("Inicio");
        loadHome();
    }

    int d(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    String trimBase(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    void buildShell(String title) {
        frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        frame.addView(page, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(d(20), d(12), d(16), d(10));
        bar.setBackgroundColor(BG);
        backButton = navAction("‹", "Atrás", v -> onBackPressed());
        backButton.setTextSize(30);
        backButton.setVisibility(title.equals("Inicio") ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(d(36), d(42));
        backParams.rightMargin = d(8);
        bar.addView(backButton, backParams);
        TextView brand = label("ZINEMA", 22, Color.WHITE, true);
        brand.setLetterSpacing(0.12f);
        brand.setOnClickListener(v -> loadHome());
        bar.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        TextView search = navAction("⌕", "Buscar", v -> showSearch());
        bar.addView(search);
        TextView settings = navAction("⚙", "Servidor", v -> showSettings());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.leftMargin = d(20);
        bar.addView(settings, sp);
        page.addView(bar, new LinearLayout.LayoutParams(-1, d(58)));

        HorizontalScrollView navScroll = new HorizontalScrollView(this);
        navScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(d(16), 0, d(16), d(8));
        nav.addView(navChip("Inicio", () -> loadHome(), true));
        nav.addView(navChip("Películas", () -> openLibrary("movies"), false));
        nav.addView(navChip("Series", () -> openLibrary("series"), false));
        navScroll.addView(nav);
        page.addView(navScroll, new LinearLayout.LayoutParams(-1, d(42)));

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, d(28));
        scroller.addView(content);
        page.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(frame);
    }

    TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    TextView navAction(String glyph, String hint, View.OnClickListener onClick) {
        TextView view = label(glyph, 23, Color.WHITE, true);
        view.setContentDescription(hint);
        view.setGravity(Gravity.CENTER);
        view.setMinWidth(d(40));
        view.setFocusable(true);
        view.setClickable(true);
        view.setOnClickListener(onClick);
        return view;
    }

    View navChip(String title, Runnable action, boolean active) {
        TextView chip = label(title, 14, active ? Color.WHITE : MUTED, active);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(d(14), 0, d(14), 0);
        chip.setFocusable(true);
        chip.setClickable(true);
        chip.setOnClickListener(v -> action.run());
        return chip;
    }

    void showLoading(String message) {
        content.removeAllViews();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(d(24), d(48), d(24), d(48));
        ProgressBar progress = new ProgressBar(this);
        box.addView(progress);
        TextView text = label(message, 15, MUTED, false);
        text.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = d(16);
        box.addView(text, p);
        content.addView(box, new LinearLayout.LayoutParams(-1, d(320)));
    }

    JSONObject getJson(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(server + path).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/json");
        int status = connection.getResponseCode();
        InputStream input = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String body = readText(input);
        connection.disconnect();
        if (status < 200 || status >= 300) throw new Exception("HTTP " + status + (body.isEmpty() ? "" : ": " + body));
        return new JSONObject(body);
    }

    JSONArray getArray(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(server + path).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/json");
        int status = connection.getResponseCode();
        InputStream input = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String body = readText(input);
        connection.disconnect();
        if (status < 200 || status >= 300) throw new Exception("HTTP " + status + (body.isEmpty() ? "" : ": " + body));
        return new JSONArray(body);
    }

    static String readText(InputStream input) throws Exception {
        if (input == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
        input.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    void loadHome() {
        libraryMode = false;
        showingDetail = false;
        breadcrumbs.clear();
        buildShell("Inicio");
        showLoading("Conectando con tu biblioteca…");
        io.execute(() -> {
            try {
                JSONArray m = getArray("/api/v1/catalog?library=movies");
                JSONArray s = getArray("/api/v1/catalog?library=series");
                List<JSONObject> movieList = toList(m);
                List<JSONObject> seriesList = toList(s);
                runOnUiThread(() -> {
                    movies = movieList;
                    series = seriesList;
                    renderHome();
                });
            } catch (Exception e) {
                runOnUiThread(() -> renderError(e.getMessage()));
            }
        });
    }

    List<JSONObject> toList(JSONArray array) {
        List<JSONObject> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject value = array.optJSONObject(i);
            if (value != null) result.add(value);
        }
        return result;
    }

    void renderHome() {
        content.removeAllViews();
        JSONObject hero = firstPlayableOrPoster(movies, series);
        if (hero != null) addHero(hero);
        List<JSONObject> favorites = readFavorites();
        if (!favorites.isEmpty()) addRow("Mi lista", favorites);
        List<JSONObject> resume = readContinueWatching();
        if (!resume.isEmpty()) addResumeRow("Seguir viendo", resume);
        addRow("Películas", movies);
        addRow("Series", series);
        if (movies.isEmpty() && series.isEmpty()) {
            TextView empty = label("Tu biblioteca está vacía. Sincroniza el catálogo en el servidor.", 16, MUTED, false);
            empty.setPadding(d(24), d(36), d(24), d(32));
            content.addView(empty);
        }
    }

    JSONObject firstPlayableOrPoster(List<JSONObject> a, List<JSONObject> b) {
        for (JSONObject item : a) if (item.optString("backdrop_url", "").length() > 0) return item;
        for (JSONObject item : b) if (item.optString("backdrop_url", "").length() > 0) return item;
        if (!a.isEmpty()) return a.get(0);
        return b.isEmpty() ? null : b.get(0);
    }

    void addHero(JSONObject node) {
        FrameLayout hero = new FrameLayout(this);
        hero.setBackground(round(PANEL, 0));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.addView(image, new FrameLayout.LayoutParams(-1, -1));
        loadImage(node.optString("backdrop_url", node.optString("poster_url", "")), image, true);
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Color.rgb(9, 10, 13), 0x99090A0D, 0x22090A0D}));
        hero.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.BOTTOM);
        info.setPadding(d(24), d(20), d(24), d(22));
        TextView eyebrow = label("RECOMENDADO PARA TI", 11, Color.WHITE, true);
        eyebrow.setLetterSpacing(0.12f);
        info.addView(eyebrow);
        TextView title = label(displayTitle(node), 30, Color.WHITE, true);
        title.setMaxLines(2);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = d(6);
        info.addView(title, tp);
        TextView desc = label(node.optString("overview", ""), 13, 0xFFE2E3E8, false);
        desc.setMaxLines(2);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, -2);
        dp.topMargin = d(6);
        info.addView(desc, dp);
        Button button = actionButton("Ver ahora", true);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, d(42));
        bp.topMargin = d(14);
        info.addView(button, bp);
        button.setOnClickListener(v -> openNode(node));
        hero.addView(info, new FrameLayout.LayoutParams(-1, -1));
        TextView heroActions = label("⋮", 23, Color.WHITE, true);
        heroActions.setGravity(Gravity.CENTER);
        heroActions.setFocusable(true);
        heroActions.setBackground(round(0xCC111318, d(18)));
        heroActions.setElevation(d(5));
        FrameLayout.LayoutParams heroActionParams = new FrameLayout.LayoutParams(d(42), d(42), Gravity.TOP | Gravity.RIGHT);
        heroActionParams.setMargins(0, d(14), d(14), 0);
        hero.addView(heroActions, heroActionParams);
        heroActions.setOnClickListener(v -> showActions(node));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, d(350));
        hp.setMargins(d(16), d(8), d(16), d(14));
        content.addView(hero, hp);
    }

    String displayTitle(JSONObject node) {
        String title = node.optString("display_title", node.optString("title", "Zinema"));
        if (node.optString("kind").equals("season")) return "Temporada " + String.format("%02d", node.optInt("season_number"));
        return title;
    }

    void addRow(String title, List<JSONObject> nodes) {
        if (nodes == null || nodes.isEmpty()) return;
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        TextView heading = label(title, 19, Color.WHITE, true);
        heading.setPadding(d(20), d(8), d(20), d(10));
        section.addView(heading);
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        LinearLayout cards = new LinearLayout(this);
        cards.setPadding(d(16), 0, d(16), d(8));
        for (JSONObject node : nodes) cards.addView(posterCard(node));
        horizontal.addView(cards);
        section.addView(horizontal);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = d(8);
        content.addView(section, p);
    }

    View posterCard(JSONObject node) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        card.setClickable(true);
        int width = d(142);
        FrameLayout art = new FrameLayout(this);
        art.setBackground(round(PANEL, d(10)));
        JSONArray mosaic = node.optJSONArray("mosaic_images");
        if (node.optString("kind").equals("collection") && mosaic != null && mosaic.length() > 0) {
            LinearLayout mosaicBox = new LinearLayout(this);
            mosaicBox.setOrientation(LinearLayout.VERTICAL);
            for (int r = 0; r < 2; r++) {
                LinearLayout mosaicRow = new LinearLayout(this);
                for (int c = 0; c < 2; c++) {
                    int index = r * 2 + c;
                    ImageView tile = new ImageView(this);
                    tile.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    tile.setBackgroundColor(PANEL);
                    LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, -1, 1);
                    tileParams.setMargins(d(1), d(1), d(1), d(1));
                    mosaicRow.addView(tile, tileParams);
                    if (index < mosaic.length()) loadImage(mosaic.optString(index), tile, false);
                }
                mosaicBox.addView(mosaicRow, new LinearLayout.LayoutParams(-1, 0, 1));
            }
            art.addView(mosaicBox, new FrameLayout.LayoutParams(-1, -1));
        } else {
            ImageView poster = new ImageView(this);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.addView(poster, new FrameLayout.LayoutParams(-1, -1));
            loadImage(node.optString("poster_url", ""), poster, false);
        }
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(width, d(204));
        card.addView(art, ap);
        TextView name = label(displayTitle(node), 13, Color.WHITE, true);
        name.setMaxLines(2);
        name.setPadding(d(2), d(7), d(2), 0);
        card.addView(name, new LinearLayout.LayoutParams(width, -2));
        TextView meta = label(cardSubtitle(node), 11, MUTED, false);
        meta.setPadding(d(2), d(3), d(2), 0);
        card.addView(meta);
        TextView actions = label("⋮", 22, Color.WHITE, true);
        actions.setGravity(Gravity.CENTER);
        actions.setFocusable(true);
        actions.setBackground(round(0xCC111318, d(18)));
        actions.setElevation(d(4));
        FrameLayout.LayoutParams actionParams = new FrameLayout.LayoutParams(d(36), d(36), Gravity.TOP | Gravity.RIGHT);
        actionParams.setMargins(0, d(6), d(6), 0);
        art.addView(actions, actionParams);
        actions.setOnClickListener(v -> showActions(node));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(width, -2);
        cp.setMargins(0, 0, d(12), 0);
        card.setOnClickListener(v -> openNode(node));
        return wrap(card, cp);
    }

    View wrap(View view, LinearLayout.LayoutParams params) {
        view.setLayoutParams(params);
        return view;
    }

    String cardSubtitle(JSONObject node) {
        String kind = node.optString("kind");
        if (kind.equals("series")) {
            JSONArray children = node.optJSONArray("children");
            int seasons = 0;
            if (children != null) {
                for (int i = 0; i < children.length(); i++) {
                    JSONObject child = children.optJSONObject(i);
                    if (child != null && "season".equals(child.optString("kind"))) seasons++;
                }
            }
            return seasons + (seasons == 1 ? " temporada" : " temporadas");
        }
        if (kind.equals("season")) return "Temporada " + String.format("%02d", node.optInt("season_number"));
        JSONArray genres = node.optJSONArray("genres");
        if (genres != null && genres.length() > 0) {
            List<String> shown = new ArrayList<>();
            for (int i = 0; i < Math.min(2, genres.length()); i++) shown.add(genres.optString(i));
            return join(new JSONArray(shown));
        }
        return kind.equals("collection") || kind.equals("movie") ? "Sin género disponible" : "";
    }

    GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    void loadImage(String path, ImageView target, boolean backdrop) {
        if (path == null || path.trim().isEmpty()) {
            target.setBackground(round(PANEL, d(8)));
            return;
        }
        String url = path.startsWith("http") ? path : server + (path.startsWith("/") ? path : "/" + path);
        target.setTag(url);
        Bitmap cached = imageCache.get(url);
        if (cached != null) { target.setImageBitmap(cached); return; }
        io.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.setInstanceFollowRedirects(true);
                Bitmap bitmap = BitmapFactory.decodeStream(connection.getInputStream());
                if (bitmap != null) {
                    imageCache.put(url, bitmap);
                    runOnUiThread(() -> { if (url.equals(target.getTag())) target.setImageBitmap(bitmap); });
                }
            } catch (Exception ignored) {
            } finally { if (connection != null) connection.disconnect(); }
        });
    }

    void openLibrary(String library) {
        libraryMode = true;
        showingDetail = false;
        currentLibrary = library;
        breadcrumbs.clear();
        buildShell(library.equals("movies") ? "Películas" : "Series");
        showLoading("Cargando catálogo…");
        io.execute(() -> {
            try {
                JSONArray rows = getArray("/api/v1/catalog?library=" + library);
                runOnUiThread(() -> renderListing(library.equals("movies") ? "Películas" : "Series", toList(rows)));
            } catch (Exception e) { runOnUiThread(() -> renderError(e.getMessage())); }
        });
    }

    void renderListing(String title, List<JSONObject> nodes) {
        content.removeAllViews();
        addSectionHeader(title, "Explora tu biblioteca");
        addGrid(nodes);
        if (nodes.isEmpty()) addEmpty("No hay títulos en esta sección.");
    }

    void addSectionHeader(String title, String subtitle) {
        TextView h = label(title, 27, Color.WHITE, true);
        h.setPadding(d(20), d(16), d(20), d(4));
        content.addView(h);
        TextView sub = label(subtitle, 14, MUTED, false);
        sub.setPadding(d(20), 0, d(20), d(18));
        content.addView(sub);
    }

    void addGrid(List<JSONObject> nodes) {
        int columns = getResources().getConfiguration().orientation == 2 ? 5 : 2;
        LinearLayout row = null;
        int count = 0;
        for (JSONObject node : nodes) {
            if (count % columns == 0) {
                row = new LinearLayout(this);
                row.setGravity(Gravity.TOP);
                row.setPadding(d(16), 0, d(16), d(14));
                content.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            View card = posterCard(node);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
            p.setMargins(d(4), 0, d(4), 0);
            row.addView(card, p);
            count++;
        }
    }

    void openNode(JSONObject node) {
        String kind = node.optString("kind");
        if (kind.equals("collection")) {
            showingDetail = false;
            if (backButton != null) backButton.setVisibility(View.VISIBLE);
            breadcrumbs.add(node);
            currentLibrary = node.optString("library", currentLibrary);
            showCollectionContents(node);
        } else {
            showingDetail = true;
            if (backButton != null) backButton.setVisibility(View.VISIBLE);
            io.execute(() -> {
                try {
                    JSONObject full = getJson("/api/v1/catalog/" + enc(node.optString("id")));
                    runOnUiThread(() -> renderDetail(full));
                } catch (Exception e) { runOnUiThread(() -> renderError(e.getMessage())); }
            });
            showLoading("Cargando ficha…");
        }
    }

    void showCollectionContents(JSONObject collection) {
        showLoading("Abriendo " + displayTitle(collection) + "…");
        io.execute(() -> {
            try {
                JSONArray children = getArray("/api/v1/catalog?library=" + currentLibrary +
                        "&parent_id=" + enc(collection.optString("id")));
                runOnUiThread(() -> renderListing(displayTitle(collection), toList(children)));
            } catch (Exception e) { runOnUiThread(() -> renderError(e.getMessage())); }
        });
    }

    JSONObject postJson(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(server + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.getOutputStream().close();
        int status = connection.getResponseCode();
        InputStream input = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String body = readText(input);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            String detail = "";
            try { detail = new JSONObject(body).optString("detail", ""); }
            catch (JSONException ignored) { }
            throw new Exception(detail.isEmpty() ? "HTTP " + status : detail);
        }
        return new JSONObject(body);
    }

    void showActions(JSONObject node) {
        new AlertDialog.Builder(this)
                .setTitle("Acciones")
                .setItems(new String[]{"Reintentar obtención de metadata"}, (dialog, which) -> retryMetadata(node))
                .show();
    }

    void retryMetadata(JSONObject node) {
        String nodeId = node.optString("id");
        if (nodeId.isEmpty()) return;
        io.execute(() -> {
            try {
                JSONObject result = postJson("/api/v1/catalog/" + enc(nodeId) + "/metadata/retry");
                int items = result.optInt("items", 1);
                String jobId = result.optString("id");
                runOnUiThread(() -> Toast.makeText(this,
                        "Reintento iniciado para " + items + (items == 1 ? " elemento" : " elementos") +
                                ". Te avisaremos al terminar.", Toast.LENGTH_LONG).show());
                for (int attempt = 0; attempt < 300; attempt++) {
                    JSONObject status = getJson("/api/v1/catalog/metadata/retry/" + enc(jobId));
                    if ("completed".equals(status.optString("status"))) {
                        imageCache.evictAll();
                        runOnUiThread(() -> finishMetadataRetry(nodeId));
                        return;
                    }
                    if ("failed".equals(status.optString("status"))) {
                        String error = status.optString("error", "Fallo al consultar TMDb");
                        runOnUiThread(() -> Toast.makeText(this, "Falló el reintento: " + error,
                                Toast.LENGTH_LONG).show());
                        return;
                    }
                    Thread.sleep(2000);
                }
                runOnUiThread(() -> Toast.makeText(this,
                        "El reintento sigue en curso. Consulta el servidor más tarde.", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "No se pudo reintentar: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    void finishMetadataRetry(String requestedNodeId) {
        Toast.makeText(this, "Metadata actualizada.", Toast.LENGTH_SHORT).show();
        if (showingDetail && requestedNodeId.equals(detailNodeId)) {
            io.execute(() -> {
                try {
                    JSONObject updated = getJson("/api/v1/catalog/" + enc(detailNodeId));
                    runOnUiThread(() -> renderDetail(updated));
                } catch (Exception e) { runOnUiThread(() -> Toast.makeText(this,
                        "Metadata guardada; no se pudo recargar la ficha.", Toast.LENGTH_LONG).show()); }
            });
        } else if (!showingDetail && !breadcrumbs.isEmpty()) {
            showCollectionContents(breadcrumbs.get(breadcrumbs.size() - 1));
        } else if (!showingDetail && libraryMode) {
            openLibrary(currentLibrary);
        } else if (!showingDetail) {
            loadHome();
        }
    }

    void renderDetail(JSONObject node) {
        showingDetail = true;
        detailNodeId = node.optString("id");
        if (backButton != null) backButton.setVisibility(View.VISIBLE);
        content.removeAllViews();
        FrameLayout banner = new FrameLayout(this);
        ImageView bgImage = new ImageView(this);
        bgImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        banner.addView(bgImage, new FrameLayout.LayoutParams(-1, -1));
        loadImage(node.optString("backdrop_url", node.optString("poster_url", "")), bgImage, true);
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{BG, 0xCC090A0D, 0x22090A0D}));
        banner.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.BOTTOM);
        info.setPadding(d(24), d(26), d(24), d(24));
        TextView kind = label(node.optString("kind", "").toUpperCase(), 11, 0xFFFF646B, true);
        kind.setLetterSpacing(0.12f);
        info.addView(kind);
        info.addView(label(displayTitle(node), 30, Color.WHITE, true));
        String meta = node.optString("kind").equals("series")
                ? cardSubtitle(node)
                : node.optString("year", "") + (node.optJSONArray("genres") == null ? "" : "   •   " + join(node.optJSONArray("genres")));
        TextView metadata = label(meta, 13, 0xFFD7D9DF, false);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
        mp.topMargin = d(5);
        info.addView(metadata, mp);
        TextView overview = label(node.optString("overview", ""), 14, 0xFFE0E1E6, false);
        overview.setMaxLines(4);
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, -2);
        op.topMargin = d(10);
        info.addView(overview, op);
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        Button play = actionButton(node.optString("kind").equals("series") ? "Ver episodios" : "Reproducir", true);
        controls.addView(play, new LinearLayout.LayoutParams(-2, d(44)));
        Button save = actionButton(isFavorite(node) ? "✓ Mi lista" : "+ Mi lista", false);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-2, d(44));
        fp.leftMargin = d(10);
        controls.addView(save, fp);
        LinearLayout.LayoutParams controlsP = new LinearLayout.LayoutParams(-1, -2);
        controlsP.topMargin = d(14);
        info.addView(controls, controlsP);
        play.setOnClickListener(v -> playFirst(node));
        save.setOnClickListener(v -> { toggleFavorite(node); save.setText(isFavorite(node) ? "✓ Mi lista" : "+ Mi lista"); });
        banner.addView(info, new FrameLayout.LayoutParams(-1, -1));
        TextView actions = label("⋮", 23, Color.WHITE, true);
        actions.setGravity(Gravity.CENTER);
        actions.setFocusable(true);
        actions.setBackground(round(0xCC111318, d(18)));
        actions.setElevation(d(5));
        FrameLayout.LayoutParams actionsParams = new FrameLayout.LayoutParams(d(42), d(42), Gravity.TOP | Gravity.RIGHT);
        actionsParams.setMargins(0, d(14), d(14), 0);
        banner.addView(actions, actionsParams);
        actions.setOnClickListener(v -> showActions(node));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, d(390));
        bp.setMargins(d(16), d(10), d(16), d(10));
        content.addView(banner, bp);

        JSONArray children = node.optJSONArray("children");
        JSONArray files = node.optJSONArray("files");
        if (children != null && children.length() > 0) {
            addSectionHeader(node.optString("kind").equals("series") ? "Temporadas" : "Explorar", "Selecciona para continuar");
            addGrid(toList(children));
        }
        if (files != null && files.length() > 0) addEpisodeList(files);
        if ((files == null || files.length() == 0) && (children == null || children.length() == 0)
                && !node.optString("kind").equals("movie")) addEmpty("No se encontraron episodios.");
    }

    String join(JSONArray values) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < values.length(); i++) {
            if (i > 0) result.append(" · ");
            result.append(values.optString(i));
        }
        return result.toString();
    }

    void addEpisodeList(JSONArray files) {
        addSectionHeader("Episodios", files.length() + (files.length() == 1 ? " vídeo" : " vídeos"));
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) continue;
            LinearLayout item = new LinearLayout(this);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(d(18), d(13), d(18), d(13));
            item.setBackground(round(PANEL, d(10)));
            TextView play = label("▶", 20, Color.WHITE, true);
            play.setGravity(Gravity.CENTER);
            item.addView(play, new LinearLayout.LayoutParams(d(48), d(48)));
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = label(file.optString("title", "Vídeo"), 15, Color.WHITE, true);
            TextView sub = label(file.optString("extension", "").replace(".", "").toUpperCase() + "   ·   " + formatSize(file.optLong("size_bytes")), 12, MUTED, false);
            texts.addView(name);
            LinearLayout.LayoutParams subP = new LinearLayout.LayoutParams(-1, -2);
            subP.topMargin = d(4);
            texts.addView(sub, subP);
            item.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
            ip.setMargins(d(16), 0, d(16), d(8));
            content.addView(item, ip);
            item.setOnClickListener(v -> launchPlayer(file.optString("stream_url"), file.optString("title", "Zinema")));
        }
    }

    void playFirst(JSONObject node) {
        JSONArray files = node.optJSONArray("files");
        if (files != null && files.length() > 0) {
            JSONObject file = files.optJSONObject(0);
            if (file != null) launchPlayer(file.optString("stream_url"), file.optString("title", displayTitle(node)));
            return;
        }
        JSONArray children = node.optJSONArray("children");
        if (children != null && children.length() > 0) openNode(children.optJSONObject(0));
        else Toast.makeText(this, "No hay un vídeo disponible.", Toast.LENGTH_SHORT).show();
    }

    String formatSize(long bytes) {
        if (bytes < 1024L * 1024L) return Math.max(1, bytes / 1024L) + " KB";
        return String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    Button actionButton(String title, boolean primary) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(13);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setPadding(d(15), 0, d(15), 0);
        button.setBackground(round(primary ? RED : 0xCC22252C, d(8)));
        return button;
    }

    void showSearch() {
        showingDetail = false;
        if (backButton != null) backButton.setVisibility(View.VISIBLE);
        content.removeAllViews();
        addSectionHeader("Buscar", "Encuentra películas, series y episodios");
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(d(18), 0, d(18), d(14));
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint("Título…");
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(MUTED);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setBackground(round(PANEL, d(9)));
        field.setPadding(d(14), 0, d(14), 0);
        line.addView(field, new LinearLayout.LayoutParams(0, d(48), 1));
        Button search = actionButton("Buscar", true);
        LinearLayout.LayoutParams searchP = new LinearLayout.LayoutParams(-2, d(48));
        searchP.leftMargin = d(8);
        line.addView(search, searchP);
        content.addView(line);
        Runnable submit = () -> performSearch(field.getText().toString());
        search.setOnClickListener(v -> submit.run());
        field.setOnEditorActionListener((v, actionId, event) -> { if (actionId == EditorInfo.IME_ACTION_SEARCH) { submit.run(); return true; } return false; });
        field.requestFocus();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
    }

    void performSearch(String query) {
        if (query.trim().isEmpty()) return;
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(content.getWindowToken(), 0);
        showLoading("Buscando…");
        io.execute(() -> {
            try {
                JSONArray results = getArray("/api/v1/items?q=" + enc(query.trim()) + "&limit=100");
                runOnUiThread(() -> {
                    content.removeAllViews();
                    addSectionHeader("Resultados", results.length() + " encontrados");
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject result = results.optJSONObject(i);
                        if (result != null) addSearchResult(result);
                    }
                    if (results.length() == 0) addEmpty("No encontramos resultados para “" + query + "”.");
                });
            } catch (Exception e) { runOnUiThread(() -> renderError(e.getMessage())); }
        });
    }

    void addSearchResult(JSONObject result) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(d(14), d(10), d(14), d(10));
        row.setBackground(round(PANEL, d(10)));
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(round(BG, d(6)));
        row.addView(poster, new LinearLayout.LayoutParams(d(62), d(88)));
        loadImage(result.optString("poster_url", ""), poster, false);
        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        textBox.setPadding(d(12), 0, d(8), 0);
        textBox.addView(label(result.optString("metadata_title", result.optString("title", "Vídeo")), 15, Color.WHITE, true));
        TextView detail = label(searchSubtitle(result) + "   ·   " + result.optString("extension", "").replace(".", "").toUpperCase(), 12, MUTED, false);
        LinearLayout.LayoutParams detailP = new LinearLayout.LayoutParams(-1, -2);
        detailP.topMargin = d(5);
        textBox.addView(detail, detailP);
        row.addView(textBox, new LinearLayout.LayoutParams(0, -2, 1));
        TextView play = label("▶", 18, Color.WHITE, true);
        row.addView(play);
        TextView actions = label("⋮", 22, Color.WHITE, true);
        actions.setGravity(Gravity.CENTER);
        actions.setMinWidth(d(34));
        actions.setFocusable(true);
        row.addView(actions);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(d(16), 0, d(16), d(8));
        content.addView(row, p);
        row.setOnClickListener(v -> launchPlayer(result.optString("stream_url", "/api/v1/items/" + result.optString("id") + "/stream"), result.optString("title", "Zinema")));
        actions.setOnClickListener(v -> {
            JSONObject target = new JSONObject();
            try {
                String metadataNode = result.optString("series_id", "");
                if (metadataNode.isEmpty() || metadataNode.equals("null")) metadataNode = result.optString("node_id", "");
                target.put("id", metadataNode);
                showActions(target);
            } catch (JSONException ignored) { }
        });
    }

    String searchSubtitle(JSONObject result) {
        if (!result.optString("series_id").isEmpty()) {
            int seasons = result.optInt("season_count");
            return seasons + (seasons == 1 ? " temporada" : " temporadas");
        }
        JSONArray genres = result.optJSONArray("genres");
        if (genres != null && genres.length() > 0) return join(genres);
        return "Sin género disponible";
    }

    void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(d(22), d(8), d(22), 0);
        TextView explanation = label("Dirección pública o local de tu servidor Zinema.", 14, MUTED, false);
        box.addView(explanation);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType( android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(server);
        input.setSelectAllOnFocus(true);
        box.addView(input);
        TextView attribution = label(
                "This product uses the TMDB API but is not endorsed or certified by TMDB.",
                11, MUTED, false);
        attribution.setPadding(0, d(12), 0, d(4));
        box.addView(attribution);
        new AlertDialog.Builder(this).setTitle("Conectar con el servidor").setView(box)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Guardar y conectar", (dialog, which) -> {
                    String value = trimBase(input.getText().toString());
                    if (!value.startsWith("http://") && !value.startsWith("https://")) value = "https://" + value;
                    if (value.length() < 10) { Toast.makeText(this, "Escribe una URL válida.", Toast.LENGTH_LONG).show(); return; }
                    server = value;
                    prefs.edit().putString("server", server).apply();
                    imageCache.evictAll();
                    loadHome();
                }).show();
    }

    void renderError(String error) {
        content.removeAllViews();
        TextView title = label("No se pudo conectar", 23, Color.WHITE, true);
        title.setPadding(d(22), d(48), d(22), d(8));
        content.addView(title);
        TextView detail = label(error == null ? "Comprueba que el servidor esté encendido." : error, 14, MUTED, false);
        detail.setPadding(d(22), 0, d(22), d(20));
        content.addView(detail);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(d(22), 0, d(22), 0);
        Button retry = actionButton("Reintentar", true);
        buttons.addView(retry);
        Button settings = actionButton("Configurar servidor", false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.leftMargin = d(10);
        buttons.addView(settings, sp);
        content.addView(buttons);
        retry.setOnClickListener(v -> loadHome());
        settings.setOnClickListener(v -> showSettings());
    }

    void addEmpty(String message) {
        TextView empty = label(message, 14, MUTED, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(d(24), d(28), d(24), d(28));
        content.addView(empty, new LinearLayout.LayoutParams(-1, -2));
    }

    String enc(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (Exception e) { return value; }
    }

    boolean isFavorite(JSONObject node) {
        return prefs.contains("fav:" + node.optString("id"));
    }

    void toggleFavorite(JSONObject node) {
        String key = "fav:" + node.optString("id");
        if (prefs.contains(key)) prefs.edit().remove(key).apply();
        else prefs.edit().putString(key, node.toString()).apply();
        Toast.makeText(this, prefs.contains(key) ? "Añadido a Mi lista" : "Eliminado de Mi lista", Toast.LENGTH_SHORT).show();
    }

    List<JSONObject> readFavorites() {
        List<JSONObject> list = new ArrayList<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith("fav:")) continue;
            try { list.add(new JSONObject(String.valueOf(entry.getValue()))); }
            catch (JSONException ignored) { }
        }
        return list;
    }

    List<JSONObject> readContinueWatching() {
        List<JSONObject> list = new ArrayList<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith("watch:")) continue;
            try {
                JSONObject item = new JSONObject(String.valueOf(entry.getValue()));
                if (item.optInt("position") > 10000) list.add(item);
            } catch (JSONException ignored) { }
        }
        Collections.sort(list, (a, b) -> Long.compare(b.optLong("updated"), a.optLong("updated")));
        return list;
    }

    void addResumeRow(String title, List<JSONObject> items) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        TextView heading = label(title, 19, Color.WHITE, true);
        heading.setPadding(d(20), d(8), d(20), d(10));
        section.addView(heading);
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        LinearLayout cards = new LinearLayout(this);
        cards.setPadding(d(16), 0, d(16), d(8));
        for (JSONObject item : items) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(d(14), d(14), d(14), d(14));
            card.setBackground(round(PANEL, d(10)));
            card.addView(label("▶  Continuar", 14, Color.WHITE, true));
            TextView name = label(item.optString("title", "Vídeo"), 13, MUTED, false);
            name.setMaxLines(2);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(d(180), -2);
            np.topMargin = d(9);
            card.addView(name, np);
            TextView progress = label("Retomar desde " + formatTime(item.optInt("position")), 11, MUTED, false);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2);
            pp.topMargin = d(7);
            card.addView(progress, pp);
            card.setOnClickListener(v -> launchPlayer(item.optString("stream"), item.optString("title", "Zinema")));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
            cp.setMargins(0, 0, d(10), 0);
            cards.addView(card, cp);
        }
        horizontal.addView(cards);
        section.addView(horizontal);
        content.addView(section);
    }

    String formatTime(int millis) {
        int total = Math.max(0, millis / 1000);
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60);
    }

    void launchPlayer(String path, String title) {
        if (path == null || path.isEmpty()) return;
        String url = path.startsWith("http") ? path : server + (path.startsWith("/") ? path : "/" + path);
        String key = "watch:" + path;
        int position = 0;
        try { position = new JSONObject(prefs.getString(key, "{}")).optInt("position"); }
        catch (JSONException ignored) { }
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra("url", url);
        intent.putExtra("path", path);
        intent.putExtra("title", title);
        intent.putExtra("position", position);
        startActivity(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        if (prefs != null && page != null) {
            List<JSONObject> resume = readContinueWatching();
            if (!resume.isEmpty() && content != null && movies.size() + series.size() > 0) {
                libraryMode = false;
                showingDetail = false;
                breadcrumbs.clear();
                if (backButton != null) backButton.setVisibility(View.GONE);
                renderHome();
            }
        }
    }

    @Override public void onBackPressed() {
        if (showingDetail) {
            showingDetail = false;
            if (!breadcrumbs.isEmpty()) {
                showCollectionContents(breadcrumbs.get(breadcrumbs.size() - 1));
            } else if (libraryMode) {
                openLibrary(currentLibrary);
            } else {
                loadHome();
            }
            return;
        }
        if (!breadcrumbs.isEmpty()) {
            breadcrumbs.remove(breadcrumbs.size() - 1);
            if (breadcrumbs.isEmpty()) {
                if (libraryMode) openLibrary(currentLibrary);
                else loadHome();
            } else {
                JSONObject parent = breadcrumbs.get(breadcrumbs.size() - 1);
                showCollectionContents(parent);
            }
            return;
        }
        loadHome();
    }
}
