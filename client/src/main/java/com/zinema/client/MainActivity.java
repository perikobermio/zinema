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
import android.graphics.Rect;
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
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
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
    final Map<String, List<ImageView>> pendingImageTargets = new HashMap<>();
    SharedPreferences prefs;
    FrameLayout frame;
    LinearLayout page;
    LinearLayout content;
    ScrollView contentScroller;
    TextView backButton;
    TextView brandButton;
    TextView searchButton;
    TextView settingsButton;
    HorizontalScrollView navScroll;
    String server;
    String currentLibrary = "movies";
    String selectedSection = "home";
    String lastSearchQuery;
    String detailNodeId = "";
    boolean libraryMode;
    boolean showingDetail;
    boolean metadataEditorFromDetail;
    boolean metadataEditorMode;
    boolean metadataEditorPreviousBackVisible;
    List<JSONObject> movies = new ArrayList<>();
    List<JSONObject> series = new ArrayList<>();
    final Map<String, String> posterByStream = new HashMap<>();
    final Map<ImageView, String> requestedImages = new WeakHashMap<>();
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
        brandButton = label("ZINEMA", 22, Color.WHITE, true);
        brandButton.setLetterSpacing(0.12f);
        brandButton.setOnClickListener(v -> loadHome());
        bar.addView(brandButton, new LinearLayout.LayoutParams(0, -2, 1));
        searchButton = navAction("⌕", "Buscar", v -> showSearch());
        bar.addView(searchButton);
        settingsButton = navAction("⚙", "Servidor", v -> showSettings());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.leftMargin = d(20);
        bar.addView(settingsButton, sp);
        page.addView(bar, new LinearLayout.LayoutParams(-1, d(58)));

        navScroll = new HorizontalScrollView(this);
        navScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(d(16), 0, d(16), d(8));
        nav.addView(navChip("Inicio", () -> loadHome(), selectedSection.equals("home")));
        nav.addView(navChip("Películas", () -> openLibrary("movies"), selectedSection.equals("movies")));
        nav.addView(navChip("Series", () -> openLibrary("series"), selectedSection.equals("series")));
        navScroll.addView(nav);
        page.addView(navScroll, new LinearLayout.LayoutParams(-1, d(48)));

        contentScroller = new ScrollView(this);
        contentScroller.setFillViewport(true);
        contentScroller.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, d(28));
        contentScroller.addView(content);
        contentScroller.setOnScrollChangeListener((View view, int x, int y, int oldX, int oldY) ->
                loadVisibleLazyRows());
        page.addView(contentScroller, new LinearLayout.LayoutParams(-1, 0, 1));
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
        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(d(14), d(2), d(14), d(2));
        TextView text = label(title, 14, active ? Color.WHITE : MUTED, active);
        text.setGravity(Gravity.CENTER);
        tab.addView(text, new LinearLayout.LayoutParams(-2, -2));
        View indicator = new View(this);
        indicator.setBackground(round(active ? RED : Color.TRANSPARENT, d(2)));
        LinearLayout.LayoutParams indicatorParams = new LinearLayout.LayoutParams(d(24), d(3));
        indicatorParams.topMargin = d(3);
        tab.addView(indicator, indicatorParams);
        tab.setFocusable(true);
        tab.setClickable(true);
        tab.setOnClickListener(v -> action.run());
        return tab;
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
        lastSearchQuery = null;
        selectedSection = "home";
        breadcrumbs.clear();
        buildShell("Inicio");
        showLoading("Conectando con tu biblioteca…");
        io.execute(() -> {
            try {
                JSONArray m = getArray("/api/v1/catalog?library=movies");
                JSONArray s = getArray("/api/v1/catalog?library=series");
                JSONArray allItems = getArray("/api/v1/items?limit=500");
                List<JSONObject> movieList = toList(m);
                List<JSONObject> seriesList = toList(s);
                sortNewestFirst(movieList);
                sortNewestFirst(seriesList);
                cachePosterByStream(allItems);
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

    void sortNewestFirst(List<JSONObject> nodes) {
        Collections.sort(nodes, (left, right) ->
                right.optString("added_at", "").compareTo(left.optString("added_at", "")));
    }

    void renderHome() {
        content.removeAllViews();
        List<JSONObject> resume = readContinueWatching();
        if (!resume.isEmpty()) addResumeRow("Seguir viendo", resume);
        List<JSONObject> favorites = readFavorites();
        if (!favorites.isEmpty()) addRow("Mi lista", favorites);
        addRow("Películas", movies);
        addRow("Series", series);
        if (movies.isEmpty() && series.isEmpty()) {
            TextView empty = label("Tu biblioteca está vacía. Sincroniza el catálogo en el servidor.", 16, MUTED, false);
            empty.setPadding(d(24), d(36), d(24), d(32));
            content.addView(empty);
        }
    }

    void cachePosterByStream(JSONArray items) {
        posterByStream.clear();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String stream = item.optString("stream_url", "");
            String poster = item.optString("poster_url", "");
            if (!stream.isEmpty() && !poster.isEmpty()) posterByStream.put(stream, poster);
        }
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
        TextView heading = label(title, 22, Color.WHITE, true);
        heading.setPadding(d(20), d(8), d(20), d(12));
        section.addView(heading);
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        horizontal.setTag("zinema-lazy-posters");
        LinearLayout cards = new LinearLayout(this);
        cards.setPadding(d(16), 0, d(16), d(8));
        for (JSONObject node : nodes) cards.addView(posterCard(node, true));
        horizontal.addView(cards);
        installLazyRowScroll(horizontal);
        section.addView(horizontal);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = d(8);
        content.addView(section, p);
    }

    View posterCard(JSONObject node) {
        return posterCard(node, false);
    }

    View posterCard(JSONObject node, boolean lazyPosters) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        card.setClickable(true);
        int width = d(142);
        FrameLayout art = new FrameLayout(this);
        art.setBackground(round(PANEL, d(10)));
        JSONArray mosaic = node.optJSONArray("mosaic_images");
        boolean isCollection = node.optString("kind").equals("collection");
        boolean hasMosaic = mosaic != null && mosaic.length() > 0;
        boolean showMosaic = isCollection && hasMosaic &&
                (node.optBoolean("poster_is_assigned")
                        ? prefs.getBoolean(collectionMosaicKey(node), false)
                        : node.optInt("item_count") > 1);
        if (showMosaic) {
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
                    if (index < mosaic.length()) showPoster(mosaic.optString(index), tile, lazyPosters);
                }
                mosaicBox.addView(mosaicRow, new LinearLayout.LayoutParams(-1, 0, 1));
            }
            art.addView(mosaicBox, new FrameLayout.LayoutParams(-1, -1));
        } else {
            ImageView poster = new ImageView(this);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.addView(poster, new FrameLayout.LayoutParams(-1, -1));
            showPoster(node.optString("poster_url", ""), poster, lazyPosters);
        }
        if (isCollection) {
            TextView collectionIcon = label("▦", 17, Color.WHITE, true);
            collectionIcon.setGravity(Gravity.CENTER);
            collectionIcon.setContentDescription("Colección");
            FrameLayout.LayoutParams collectionParams = new FrameLayout.LayoutParams(d(34), d(34), Gravity.TOP | Gravity.LEFT);
            collectionParams.setMargins(d(6), d(6), 0, 0);
            art.addView(collectionIcon, collectionParams);
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
        if (isNodeSeen(node)) {
            TextView seen = label("✓ VISTO", 10, Color.WHITE, true);
            seen.setGravity(Gravity.CENTER);
            seen.setPadding(d(8), d(5), d(8), d(5));
            seen.setBackground(round(0xDD22936F, d(10)));
            FrameLayout.LayoutParams seenParams = new FrameLayout.LayoutParams(-2, -2, Gravity.LEFT | Gravity.BOTTOM);
            seenParams.setMargins(d(7), 0, 0, d(7));
            art.addView(seen, seenParams);
        }
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
        if (kind.equals("collection")) {
            int count = node.optInt("item_count");
            String countLabel = count + (count == 1 ? " título" : " títulos");
            if (genres == null || genres.length() == 0) return countLabel;
            List<String> shown = new ArrayList<>();
            for (int i = 0; i < Math.min(2, genres.length()); i++) shown.add(genres.optString(i));
            return countLabel + " · " + join(new JSONArray(shown));
        }
        if (genres != null && genres.length() > 0) {
            List<String> shown = new ArrayList<>();
            for (int i = 0; i < Math.min(2, genres.length()); i++) shown.add(genres.optString(i));
            return join(new JSONArray(shown));
        }
        return kind.equals("movie") ? "Sin género disponible" : "";
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
        if (url.equals(requestedImages.get(target))) return;
        target.setTag(url);
        requestedImages.put(target, url);
        Bitmap cached = imageCache.get(url);
        if (cached != null) { target.setImageBitmap(cached); return; }
        List<ImageView> pending = pendingImageTargets.get(url);
        if (pending != null) {
            pending.add(target);
            return;
        }
        pending = new ArrayList<>();
        pending.add(target);
        pendingImageTargets.put(url, pending);
        io.execute(() -> {
            HttpURLConnection connection = null;
            Bitmap bitmap = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.setInstanceFollowRedirects(true);
                bitmap = BitmapFactory.decodeStream(connection.getInputStream());
            } catch (Exception ignored) {
            } finally { if (connection != null) connection.disconnect(); }
            Bitmap result = bitmap;
            runOnUiThread(() -> {
                List<ImageView> targets = pendingImageTargets.remove(url);
                if (result != null) imageCache.put(url, result);
                if (targets == null || result == null) return;
                for (ImageView image : targets) {
                    if (url.equals(image.getTag())) image.setImageBitmap(result);
                }
            });
        });
    }

    void showPoster(String path, ImageView target, boolean lazy) {
        if (!lazy) {
            loadImage(path, target, false);
            return;
        }
        if (path == null || path.trim().isEmpty()) {
            target.setBackground(round(PANEL, d(8)));
            return;
        }
        String url = path.startsWith("http") ? path : server + (path.startsWith("/") ? path : "/" + path);
        target.setTag(url);
        Bitmap cached = imageCache.get(url);
        if (cached != null) {
            target.setImageBitmap(cached);
            requestedImages.put(target, url);
        }
    }

    void installLazyRowScroll(HorizontalScrollView horizontal) {
        horizontal.setOnScrollChangeListener((View view, int x, int y, int oldX, int oldY) ->
                loadLazyRow(horizontal));
        horizontal.post(() -> loadLazyRow(horizontal));
    }

    void loadVisibleLazyRows() {
        if (content == null) return;
        loadVisibleLazyRowsIn(content);
    }

    void loadVisibleLazyRowsIn(View view) {
        if (view instanceof HorizontalScrollView && "zinema-lazy-posters".equals(view.getTag())) {
            loadLazyRow((HorizontalScrollView) view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) loadVisibleLazyRowsIn(group.getChildAt(i));
        }
    }

    void loadLazyRow(HorizontalScrollView horizontal) {
        Rect visible = new Rect();
        if (!horizontal.getGlobalVisibleRect(visible) || horizontal.getChildCount() == 0) return;
        ViewGroup cards = (ViewGroup) horizontal.getChildAt(0);
        int cardStep = cards.getChildCount() == 0 ? d(154) : cards.getChildAt(0).getWidth() + d(10);
        int end = horizontal.getScrollX() + horizontal.getWidth() + cardStep * 3;
        for (int i = 0; i < cards.getChildCount(); i++) {
            View card = cards.getChildAt(i);
            if (card.getRight() >= horizontal.getScrollX() && card.getLeft() <= end) {
                loadDeferredPosters(card);
            }
        }
    }

    void loadDeferredPosters(View view) {
        if (view instanceof ImageView) {
            Object value = view.getTag();
            if (value instanceof String && ((String) value).startsWith("http")) {
                loadImage((String) value, (ImageView) view, false);
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) loadDeferredPosters(group.getChildAt(i));
        }
    }

    void openLibrary(String library) {
        libraryMode = true;
        showingDetail = false;
        lastSearchQuery = null;
        selectedSection = library;
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
        renderListing(title, "Explora tu biblioteca", nodes);
    }

    void renderListing(String title, String subtitle, List<JSONObject> nodes) {
        content.removeAllViews();
        addSectionHeader(title, subtitle);
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
                List<JSONObject> items = toList(children);
                int movieCount = 0;
                int collectionCount = 0;
                for (JSONObject item : items) {
                    if ("movie".equals(item.optString("kind"))) movieCount++;
                    else if ("collection".equals(item.optString("kind"))) collectionCount++;
                }
                String subtitle = movieCount + (movieCount == 1 ? " película" : " películas");
                if (collectionCount > 0) subtitle += " · " + collectionCount +
                        (collectionCount == 1 ? " colección" : " colecciones");
                String collectionSubtitle = subtitle;
                runOnUiThread(() -> renderListing(displayTitle(collection), collectionSubtitle, items));
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

    JSONObject postJson(String path, JSONObject payload) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(server + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(45000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.getOutputStream().write(payload.toString().getBytes(StandardCharsets.UTF_8));
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
        List<String> actions = new ArrayList<>();
        List<Runnable> handlers = new ArrayList<>();
        String kind = node.optString("kind");
        if (kind.equals("movie") || kind.equals("series")) {
            actions.add("Marcar como visto");
            handlers.add(() -> setNodeWatched(node, true));
            actions.add("Marcar como no visto");
            handlers.add(() -> setNodeWatched(node, false));
            actions.add("Reintentar metadata (TMDb)");
            handlers.add(() -> retryMetadata(node));
        }
        if (kind.equals("collection") && node.optBoolean("poster_is_assigned")) {
            boolean showingMosaic = prefs.getBoolean(collectionMosaicKey(node), false);
            actions.add(showingMosaic ? "Usar póster manual" : "Usar mosaico de pósters");
            handlers.add(() -> {
                prefs.edit().putBoolean(collectionMosaicKey(node), !showingMosaic).apply();
                refreshCurrentScreen();
            });
        }
        actions.add("Metadata manual");
        handlers.add(() -> showMetadataEditor(node, new JSONArray()));
        new AlertDialog.Builder(this)
                .setTitle("Acciones")
                .setItems(actions.toArray(new String[0]), (dialog, which) -> handlers.get(which).run())
                .show();
    }

    String collectionMosaicKey(JSONObject node) {
        return "collection:mosaic:" + node.optString("id", "");
    }

    String nodeSeenKey(String nodeId) { return "seen:node:" + nodeId; }
    String mediaSeenKey(JSONObject media) {
        String id = media.optString("id", "");
        if (id.isEmpty() || id.equals("null")) id = media.optString("stream_url", media.optString("stream", ""));
        return "seen:file:" + id;
    }
    boolean isNodeSeen(JSONObject node) {
        String id = node.optString("id", "");
        return !id.isEmpty() && prefs.getBoolean(nodeSeenKey(id), false);
    }
    boolean isMediaSeen(JSONObject media) { return prefs.getBoolean(mediaSeenKey(media), false); }

    void setNodeWatched(JSONObject node, boolean seen) {
        String id = node.optString("id", "");
        if (id.isEmpty()) return;
        setNodeSeen(id, seen);
        removeContinueForNode(id);
        io.execute(() -> {
            try {
                JSONObject full = getJson("/api/v1/catalog/" + enc(id));
                removeContinueForFiles(full.optJSONArray("files"));
                if ("series".equals(node.optString("kind"))) {
                    JSONArray seasons = full.optJSONArray("children");
                    if (seasons != null) {
                        for (int i = 0; i < seasons.length(); i++) {
                            JSONObject season = seasons.optJSONObject(i);
                            if (season != null) {
                                removeContinueForNode(season.optString("id"));
                                JSONObject fullSeason = getJson("/api/v1/catalog/" + enc(season.optString("id")));
                                removeContinueForFiles(fullSeason.optJSONArray("files"));
                            }
                        }
                    }
                }
                refreshWatchedAncestors(full.optString("parent_id", ""));
            } catch (Exception ignored) { runOnUiThread(this::refreshCurrentScreen); }
        });
        Toast.makeText(this, seen ? "Marcado como visto" : "Marcado como no visto", Toast.LENGTH_SHORT).show();
        refreshCurrentScreen();
    }

    void setNodeSeen(String id, boolean seen) {
        SharedPreferences.Editor editor = prefs.edit();
        if (seen) editor.putBoolean(nodeSeenKey(id), true);
        else editor.remove(nodeSeenKey(id));
        editor.apply();
    }

    void removeContinueForNode(String nodeId) {
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith("watch:")) continue;
            try {
                JSONObject item = new JSONObject(String.valueOf(entry.getValue()));
                if (nodeId.equals(item.optString("owner_node_id")) || nodeId.equals(item.optString("node_id"))) {
                    editor.remove(entry.getKey());
                }
            } catch (JSONException ignored) { }
        }
        editor.apply();
    }

    void removeContinueForFiles(JSONArray files) {
        if (files == null) return;
        SharedPreferences.Editor editor = prefs.edit();
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file != null) editor.remove("watch:" + file.optString("stream_url", ""));
        }
        editor.apply();
    }

    void showMediaActions(JSONObject media, JSONObject owner) {
        showMediaActions(media, owner, owner);
    }

    void showMediaActions(JSONObject media, JSONObject owner, JSONObject metadataNode) {
        List<String> actions = new ArrayList<>();
        List<Runnable> handlers = new ArrayList<>();
        String metadataKind = metadataNode.optString("kind", "");
        if (metadataKind.equals("movie") || metadataKind.equals("series")) {
            actions.add("Reintentar metadata (TMDb)");
            handlers.add(() -> retryMetadata(metadataNode));
            actions.add("Metadata manual");
            handlers.add(() -> showMetadataEditor(metadataNode, new JSONArray()));
        }
        actions.add("Marcar como visto");
        handlers.add(() -> setMediaWatched(media, owner, true));
        actions.add("Marcar como no visto");
        handlers.add(() -> setMediaWatched(media, owner, false));
        new AlertDialog.Builder(this).setTitle("Acciones")
                .setItems(actions.toArray(new String[0]), (dialog, which) -> handlers.get(which).run())
                .show();
    }

    void setMediaWatched(JSONObject media, JSONObject owner, boolean seen) {
        String stream = media.optString("stream_url", media.optString("stream", ""));
        String key = mediaSeenKey(media);
        if (seen) prefs.edit().putBoolean(key, true).remove("watch:" + stream).apply();
        else prefs.edit().remove(key).remove("watch:" + stream).apply();
        if ("movie".equals(owner.optString("kind"))) setNodeSeen(owner.optString("id"), seen);
        if ("season".equals(owner.optString("kind"))) {
            if (owner.optJSONArray("files") != null) refreshSeasonSeen(owner);
            else io.execute(() -> {
                try {
                    JSONObject fullSeason = getJson("/api/v1/catalog/" + enc(owner.optString("id")));
                    runOnUiThread(() -> refreshSeasonSeen(fullSeason));
                } catch (Exception ignored) { }
            });
        }
        else refreshParentOfNode(owner.optString("id", ""));
        Toast.makeText(this, seen ? "Marcado como visto" : "Marcado como no visto", Toast.LENGTH_SHORT).show();
        refreshCurrentScreen();
    }

    void refreshSeasonSeen(JSONObject season) {
        JSONArray files = season.optJSONArray("files");
        boolean allSeen = files != null && files.length() > 0;
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.optJSONObject(i);
                if (file == null || !isMediaSeen(file)) { allSeen = false; break; }
            }
        }
        setNodeSeen(season.optString("id"), allSeen);
        refreshWatchedAncestors(season.optString("parent_id", ""));
    }

    void refreshParentOfNode(String nodeId) {
        if (nodeId == null || nodeId.isEmpty() || nodeId.equals("null")) return;
        io.execute(() -> {
            try {
                JSONObject node = getJson("/api/v1/catalog/" + enc(nodeId));
                refreshWatchedAncestors(node.optString("parent_id", ""));
            } catch (Exception ignored) { }
        });
    }

    void refreshWatchedAncestors(String parentId) {
        if (parentId == null || parentId.isEmpty() || parentId.equals("null")) return;
        io.execute(() -> {
            String nextParent = parentId;
            while (!nextParent.isEmpty() && !nextParent.equals("null")) {
                try {
                    JSONObject parent = getJson("/api/v1/catalog/" + enc(nextParent));
                    JSONArray children = parent.optJSONArray("children");
                    boolean allSeen = children != null && children.length() > 0;
                    if (children != null) {
                        for (int i = 0; i < children.length(); i++) {
                            JSONObject child = children.optJSONObject(i);
                            if (child == null || !isNodeSeen(child)) { allSeen = false; break; }
                        }
                    }
                    setNodeSeen(parent.optString("id"), allSeen);
                    nextParent = parent.optString("parent_id", "");
                } catch (Exception e) { break; }
            }
            runOnUiThread(this::refreshCurrentScreen);
        });
    }

    void showResumeActions(JSONObject item) {
        String[] actions = {"Marcar como visto", "Marcar como no visto", "Quitar de Seguir viendo"};
        new AlertDialog.Builder(this).setTitle("Acciones")
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) setResumeSeen(item, true);
                    else if (which == 1) setResumeSeen(item, false);
                    else prefs.edit().remove("watch:" + item.optString("path", item.optString("stream"))).apply();
                    refreshCurrentScreen();
                }).show();
    }

    void setResumeSeen(JSONObject item, boolean seen) {
        String nodeId = item.optString("owner_node_id", item.optString("node_id", ""));
        String kind = item.optString("owner_kind", "");
        JSONObject media = mediaFromProgress(item);
        String mediaKey = mediaSeenKey(media);
        SharedPreferences.Editor editor = prefs.edit().remove("watch:" + item.optString("path", item.optString("stream")));
        if (seen) editor.putBoolean(mediaKey, true); else editor.remove(mediaKey);
        editor.apply();
        if (kind.equals("movie") || kind.equals("series")) {
            setNodeSeen(nodeId, seen);
            removeContinueForNode(nodeId);
        }
        if (kind.equals("season") && !nodeId.isEmpty()) {
            io.execute(() -> {
                try {
                    JSONObject season = getJson("/api/v1/catalog/" + enc(nodeId));
                    runOnUiThread(() -> refreshSeasonSeen(season));
                } catch (Exception ignored) { }
            });
        } else if (!nodeId.isEmpty()) refreshParentOfNode(nodeId);
    }

    JSONObject mediaFromProgress(JSONObject item) {
        JSONObject media = new JSONObject();
        try {
            media.put("id", item.optString("media_id", ""));
            media.put("stream", item.optString("stream", ""));
        } catch (JSONException ignored) { }
        return media;
    }

    void refreshCurrentScreen() {
        if (content == null || movies.size() + series.size() == 0) return;
        if (showingDetail && !detailNodeId.isEmpty()) {
            String id = detailNodeId;
            io.execute(() -> {
                try {
                    JSONObject updated = getJson("/api/v1/catalog/" + enc(id));
                    runOnUiThread(() -> renderDetail(updated));
                } catch (Exception ignored) { }
            });
        } else if (!breadcrumbs.isEmpty()) showCollectionContents(breadcrumbs.get(breadcrumbs.size() - 1));
        else if (libraryMode) openLibrary(currentLibrary);
        else if (lastSearchQuery != null) performSearch(lastSearchQuery);
        else renderHome();
    }

    void retryMetadata(JSONObject node) {
        String nodeId = node.optString("id");
        if (nodeId.isEmpty()) return;
        showLoading("Buscando coincidencias en TMDb…");
        io.execute(() -> {
            try {
                JSONObject result = postJson("/api/v1/catalog/" + enc(nodeId) + "/metadata/candidates");
                JSONArray candidates = result.optJSONArray("candidates");
                runOnUiThread(() -> showMetadataEditor(node,
                        candidates == null ? new JSONArray() : candidates));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    showMetadataEditor(node, new JSONArray());
                    String message = e.getMessage() == null ? "Error desconocido" : e.getMessage();
                    if (message.contains("Metadata was recently requested")) {
                        message = "Ya se buscó hace poco. Espera un minuto antes de volver a intentarlo.";
                    }
                    Toast.makeText(this, "No se pudieron buscar coincidencias: " + message,
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    EditText metadataField(String value, String hint, boolean multiline) {
        EditText field = new EditText(this);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(MUTED);
        field.setHint(hint);
        field.setText(value);
        field.setSingleLine(!multiline);
        if (multiline) {
            field.setMinLines(3);
            field.setGravity(Gravity.TOP | Gravity.LEFT);
        }
        field.setPadding(d(12), d(10), d(12), d(10));
        field.setBackground(round(PANEL, d(8)));
        return field;
    }

    void showMetadataEditor(JSONObject node, JSONArray candidates) {
        metadataEditorFromDetail = showingDetail;
        showingDetail = false;
        setMetadataEditorChrome(true);
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(d(18), d(8), d(18), d(30));
        scroll.addView(form);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        form.addView(label("Metadata manual", 23, Color.WHITE, true));
        TextView subtitle = label(displayTitle(node) + " · " + node.optString("kind"), 13, MUTED, false);
        subtitle.setPadding(0, d(4), 0, d(14));
        form.addView(subtitle);

        EditText title = metadataField(node.optString("display_title", node.optString("title", "")), "Título", false);
        EditText year = metadataField(node.isNull("year") ? "" : node.optString("year"), "Año", false);
        EditText overview = metadataField(node.optString("overview", ""), "Sinopsis", true);
        ArrayList<String> genreOptions = new ArrayList<>();
        ArrayList<String> selectedGenres = new ArrayList<>();
        addGenreValues(genreOptions, node.optJSONArray("genres"));
        addGenreValues(selectedGenres, node.optJSONArray("genres"));
        Button genres = actionButton("Seleccionar géneros (" + selectedGenres.size() + ")", false);
        genres.setOnClickListener(v -> showGenrePicker(genres, genreOptions, selectedGenres));
        form.addView(title, fieldMargins());
        form.addView(year, fieldMargins());
        form.addView(genres, fieldMargins());
        form.addView(overview, fieldMargins());

        long[] selectedTmdbId = {node.optLong("tmdb_id", 0)};
        String[] selectedImagePath = {null};
        String[] selectedImageType = {"poster"};
        LinearLayout candidateGallery = new LinearLayout(this);
        HorizontalScrollView candidateScroller = new HorizontalScrollView(this);
        candidateScroller.setHorizontalScrollBarEnabled(false);
        candidateScroller.addView(candidateGallery);
        LinearLayout imageGallery = new LinearLayout(this);
        HorizontalScrollView imageScroller = new HorizontalScrollView(this);
        imageScroller.setHorizontalScrollBarEnabled(false);
        imageScroller.addView(imageGallery);
        form.addView(label("Coincidencias TMDb · selecciona para validar", 16, Color.WHITE, true), fieldMargins());
        form.addView(candidateScroller, new LinearLayout.LayoutParams(-1, d(195)));
        form.addView(label("Imágenes online · selecciona una miniatura", 16, Color.WHITE, true), fieldMargins());
        form.addView(imageScroller, new LinearLayout.LayoutParams(-1, d(174)));

        Button searchImages = actionButton("Buscar imágenes", false);
        form.addView(searchImages, fieldMargins());
        searchImages.setOnClickListener(v -> searchMetadataImages(node, title.getText().toString(),
                selectedTmdbId[0], imageGallery, selectedImagePath, selectedImageType));
        Button save = actionButton("Guardar metadata", true);
        LinearLayout.LayoutParams saveParams = fieldMargins();
        saveParams.topMargin = d(14);
        form.addView(save, saveParams);
        save.setOnClickListener(v -> saveMetadata(node, title, year, selectedGenres, overview,
                selectedTmdbId[0], selectedImagePath[0], selectedImageType[0]));

        io.execute(() -> {
            try {
                JSONArray importedGenres = getArray("/api/v1/genres");
                runOnUiThread(() -> {
                    addGenreValues(genreOptions, importedGenres);
                    addGenreValues(genreOptions, new JSONArray(selectedGenres));
                    genres.setText("Seleccionar géneros (" + selectedGenres.size() + ")");
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "No se pudo cargar la lista de géneros: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });

        candidateGallery.removeAllViews();
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject candidate = candidates.optJSONObject(i);
            if (candidate == null) continue;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(d(4), d(4), d(10), d(4));
            ImageView poster = new ImageView(this);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            poster.setBackground(round(PANEL, d(7)));
            card.addView(poster, new LinearLayout.LayoutParams(d(102), d(142)));
            String path = candidate.optString("poster_path", "");
            if (!path.isEmpty()) loadImage("https://image.tmdb.org/t/p/w342" + path, poster, false);
            String candidateLabel = candidate.optString("title", "Título") +
                    (candidate.isNull("year") ? "" : " (" + candidate.optString("year") + ")");
            TextView candidateName = label(candidateLabel, 11, Color.WHITE, false);
            candidateName.setMaxLines(2);
            card.addView(candidateName, new LinearLayout.LayoutParams(d(102), -2));
            card.setOnClickListener(v -> selectMetadataCandidate(node, candidate, title, year,
                    genres, genreOptions, selectedGenres, overview, selectedTmdbId,
                    selectedImagePath, selectedImageType, imageGallery));
            candidateGallery.addView(card);
        }
        if (candidates.length() == 0) {
            candidateGallery.addView(label("No se encontraron coincidencias. Puedes completar los campos manualmente.",
                    13, MUTED, false));
        }
        searchMetadataImages(node, title.getText().toString(), selectedTmdbId[0],
                imageGallery, selectedImagePath, selectedImageType);
    }

    void addGenreValues(List<String> destination, JSONArray values) {
        if (values == null) return;
        for (int i = 0; i < values.length(); i++) {
            String value = values.optString(i, "").trim();
            if (value.isEmpty()) continue;
            String normalized = normalizeGenre(value);
            if (normalized.equals("animacion")) value = "Animación";
            else if (normalized.equals("fantasia")) value = "Fantasía";
            boolean exists = false;
            for (String current : destination) {
                if (normalizeGenre(current).equals(normalizeGenre(value))) { exists = true; break; }
            }
            if (!exists) destination.add(value);
        }
    }

    String normalizeGenre(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.replaceAll("\\s+", " ");
    }

    void showGenrePicker(Button button, List<String> options, List<String> selected) {
        String[] labels = options.toArray(new String[0]);
        boolean[] checked = new boolean[labels.length];
        for (int i = 0; i < labels.length; i++) {
            for (String genre : selected) {
                if (genre.equalsIgnoreCase(labels[i])) { checked[i] = true; break; }
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Selecciona los géneros")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                    String genre = labels[which];
                    selected.removeIf(item -> item.equalsIgnoreCase(genre));
                    if (isChecked) selected.add(genre);
                    button.setText("Seleccionar géneros (" + selected.size() + ")");
                })
                .setPositiveButton("Listo", (dialog, which) ->
                        button.setText("Seleccionar géneros (" + selected.size() + ")"))
                .setNegativeButton("Cancelar", null)
                .setNeutralButton("Añadir nuevo", (dialog, which) ->
                        promptNewGenre(button, options, selected))
                .show();
    }

    void promptNewGenre(Button button, List<String> options, List<String> selected) {
        EditText input = metadataField("", "Nombre del género", false);
        new AlertDialog.Builder(this)
                .setTitle("Añadir género")
                .setView(input)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Añadir", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) return;
                    JSONArray added = new JSONArray();
                    added.put(value);
                    addGenreValues(options, added);
                    addGenreValues(selected, added);
                    button.setText("Seleccionar géneros (" + selected.size() + ")");
                    button.post(() -> showGenrePicker(button, options, selected));
                })
                .show();
    }

    void setMetadataEditorChrome(boolean visible) {
        if (backButton != null) {
            if (visible && !metadataEditorMode) {
                metadataEditorPreviousBackVisible = backButton.getVisibility() == View.VISIBLE;
            }
            backButton.setVisibility(visible || metadataEditorPreviousBackVisible ? View.VISIBLE : View.GONE);
        }
        int menuVisibility = visible ? View.GONE : View.VISIBLE;
        if (brandButton != null) brandButton.setVisibility(menuVisibility);
        if (searchButton != null) searchButton.setVisibility(menuVisibility);
        if (settingsButton != null) settingsButton.setVisibility(menuVisibility);
        if (navScroll != null) navScroll.setVisibility(menuVisibility);
        metadataEditorMode = visible;
    }

    LinearLayout.LayoutParams fieldMargins() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = d(9);
        return params;
    }

    void selectMetadataCandidate(JSONObject node, JSONObject candidate, EditText title,
                                 EditText year, Button genres, List<String> genreOptions,
                                 List<String> selectedGenres, EditText overview,
                                 long[] selectedTmdbId, String[] selectedImagePath,
                                 String[] selectedImageType, LinearLayout imageGallery) {
        long tmdbId = candidate.optLong("tmdb_id", 0);
        if (tmdbId <= 0) return;
        Toast.makeText(this, "Cargando coincidencia…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try {
                JSONObject detail = getJson("/api/v1/catalog/" + enc(node.optString("id")) +
                        "/metadata/candidate/" + tmdbId);
                runOnUiThread(() -> {
                    selectedTmdbId[0] = tmdbId;
                    selectedImagePath[0] = detail.optString("poster_path", null);
                    selectedImageType[0] = "poster";
                    title.setText(detail.optString("metadata_title", ""));
                    year.setText(detail.isNull("year") ? "" : detail.optString("year"));
                    JSONArray values = detail.optJSONArray("genres");
                    selectedGenres.clear();
                    addGenreValues(selectedGenres, values);
                    addGenreValues(genreOptions, values);
                    genres.setText("Seleccionar géneros (" + selectedGenres.size() + ")");
                    overview.setText(detail.optString("overview", ""));
                    showMetadataEditorWithSelection(node, selectedTmdbId[0], detail, title,
                            year, genres, genreOptions, selectedGenres, overview,
                            selectedImagePath, selectedImageType, imageGallery);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "No se pudo cargar la coincidencia: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    void showMetadataEditorWithSelection(JSONObject node, long tmdbId, JSONObject detail,
                                         EditText title, EditText year, Button genres,
                                         List<String> genreOptions, List<String> selectedGenres,
                                         EditText overview, String[] selectedImagePath,
                                         String[] selectedImageType, LinearLayout imageGallery) {
        // The editor stays visible while the selected match supplies its image choices.
        imageCache.evictAll();
        searchMetadataImages(node, title.getText().toString(), tmdbId,
                imageGallery, selectedImagePath, selectedImageType);
    }

    void searchMetadataImages(JSONObject node, String query, long tmdbId,
                              LinearLayout gallery, String[] selectedPath, String[] selectedType) {
        String nodeId = node.optString("id");
        if (nodeId.isEmpty() || query.trim().isEmpty()) return;
        gallery.removeAllViews();
        gallery.addView(label("Buscando imágenes…", 13, MUTED, false));
        io.execute(() -> {
            try {
                String path = "/api/v1/catalog/" + enc(nodeId) + "/metadata/images?q=" + enc(query.trim());
                if (tmdbId > 0) path += "&tmdb_id=" + tmdbId;
                JSONArray images = getJson(path).optJSONArray("images");
                runOnUiThread(() -> {
                    gallery.removeAllViews();
                    if (images == null || images.length() == 0) {
                        gallery.addView(label("No hay imágenes disponibles para esta búsqueda.", 13, MUTED, false));
                        return;
                    }
                    for (int i = 0; i < images.length(); i++) {
                        JSONObject option = images.optJSONObject(i);
                        if (option == null) continue;
                        LinearLayout card = new LinearLayout(this);
                        card.setOrientation(LinearLayout.VERTICAL);
                        card.setPadding(d(4), d(4), d(8), d(4));
                        ImageView image = new ImageView(this);
                        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        image.setBackground(round(PANEL, d(7)));
                        card.addView(image, new LinearLayout.LayoutParams(d(104), d(138)));
                        loadImage(option.optString("url", ""), image, false);
                        card.setTag(new String[]{option.optString("path", ""),
                                option.optString("type", "poster")});
                        TextView kind = label(option.optString("type", "poster").equals("backdrop")
                                ? "Horizontal" : "Póster", 10, MUTED, false);
                        card.addView(kind);
                        card.setOnClickListener(v -> {
                            selectedPath[0] = option.optString("path", null);
                            selectedType[0] = option.optString("type", "poster");
                            highlightImageSelection(gallery, selectedPath[0], selectedType[0]);
                            Toast.makeText(this, "Miniatura seleccionada.", Toast.LENGTH_SHORT).show();
                        });
                        gallery.addView(card);
                    }
                    highlightImageSelection(gallery, selectedPath[0], selectedType[0]);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    gallery.removeAllViews();
                    String error = e.getMessage() == null ? "Error desconocido" : e.getMessage();
                    if (error.contains("HTTP 404") && error.contains("Not Found")) {
                        error = "El servidor del NAS aún no tiene la ruta de búsqueda de imágenes. " +
                                "Actualiza el contenedor con: docker compose up --build -d";
                    } else if (error.contains("Catalog node not found")) {
                        error = "El elemento ya no existe en el catálogo. Resincroniza el catálogo e inténtalo de nuevo.";
                    }
                    gallery.addView(label("No se pudieron buscar imágenes: " + error, 12, MUTED, false));
                });
            }
        });
    }

    void highlightImageSelection(LinearLayout gallery, String selectedPath, String selectedType) {
        for (int i = 0; i < gallery.getChildCount(); i++) {
            View child = gallery.getChildAt(i);
            Object tag = child.getTag();
            boolean selected = false;
            if (tag instanceof String[] && selectedPath != null) {
                String[] image = (String[]) tag;
                selected = selectedPath.equals(image[0]) && selectedType.equals(image[1]);
            }
            GradientDrawable border = new GradientDrawable();
            border.setColor(Color.TRANSPARENT);
            border.setCornerRadius(d(8));
            border.setStroke(d(selected ? 3 : 1), selected ? RED : 0x66585B66);
            child.setBackground(border);
            child.setElevation(selected ? d(5) : 0);
        }
    }

    void saveMetadata(JSONObject node, EditText title, EditText year, List<String> selectedGenres,
                      EditText overview, long tmdbId, String imagePath, String imageType) {
        String itemTitle = title.getText().toString().trim();
        if (itemTitle.isEmpty()) {
            Toast.makeText(this, "El título no puede estar vacío.", Toast.LENGTH_SHORT).show();
            return;
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("metadata_title", itemTitle);
            payload.put("overview", overview.getText().toString());
            payload.put("year", year.getText().toString().trim().isEmpty()
                    ? JSONObject.NULL : Integer.parseInt(year.getText().toString().trim()));
            JSONArray genreList = new JSONArray();
            for (String genre : selectedGenres) genreList.put(genre);
            payload.put("genres", genreList);
            if (imagePath != null && !imagePath.isEmpty()) payload.put("image_path", imagePath);
            payload.put("image_type", imageType == null ? "poster" : imageType);
            if (tmdbId > 0) payload.put("tmdb_id", tmdbId);
        } catch (JSONException | NumberFormatException e) {
            Toast.makeText(this, "Revisa el año introducido.", Toast.LENGTH_SHORT).show();
            return;
        }
        String nodeId = node.optString("id");
        showLoading("Guardando metadata…");
        io.execute(() -> {
            try {
                postJson("/api/v1/catalog/" + enc(nodeId) + "/metadata/manual", payload);
                imageCache.evictAll();
                runOnUiThread(() -> finishMetadataRetry(nodeId, "Metadata guardada y cliente actualizado."));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "No se pudo guardar: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    void finishMetadataRetry(String requestedNodeId, String summary) {
        Toast.makeText(this, summary, Toast.LENGTH_LONG).show();
        setMetadataEditorChrome(false);
        if ((showingDetail || metadataEditorFromDetail) && requestedNodeId.equals(detailNodeId)) {
            metadataEditorFromDetail = false;
            io.execute(() -> {
                try {
                    JSONObject updated = getJson("/api/v1/catalog/" + enc(detailNodeId));
                    runOnUiThread(() -> renderDetail(updated));
                } catch (Exception e) { runOnUiThread(() -> Toast.makeText(this,
                        "Metadata guardada; no se pudo recargar la ficha.", Toast.LENGTH_LONG).show()); }
            });
        } else if (!showingDetail && !breadcrumbs.isEmpty()) {
            showCollectionContents(breadcrumbs.get(breadcrumbs.size() - 1));
        } else if (!showingDetail && lastSearchQuery != null) {
            performSearch(lastSearchQuery);
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
        if (isNodeSeen(node)) {
            TextView watched = label("✓  VISTO", 11, 0xFF79E3B7, true);
            watched.setPadding(0, d(5), 0, 0);
            info.addView(watched);
        }
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
        LinearLayout.LayoutParams controlsP = new LinearLayout.LayoutParams(-1, -2);
        controlsP.topMargin = d(14);
        info.addView(controls, controlsP);
        play.setOnClickListener(v -> playFirst(node));
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

        if ("movie".equals(node.optString("kind")) || "series".equals(node.optString("kind"))) {
            addCastSection(node.optJSONArray("cast"));
        }
        JSONArray children = node.optJSONArray("children");
        JSONArray files = node.optJSONArray("files");
        if (children != null && children.length() > 0) {
            addSectionHeader(node.optString("kind").equals("series") ? "Temporadas" : "Explorar", "Selecciona para continuar");
            addGrid(toList(children));
        }
        if (files != null && files.length() > 0) {
            if ("movie".equals(node.optString("kind"))) addMovieFileCard(files, node.optString("poster_url", ""), node);
            else addEpisodeList(files, node.optString("poster_url", ""), node);
        }
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

    void addCastSection(JSONArray cast) {
        if (cast == null || cast.length() == 0) return;
        addSectionHeader("Reparto", "Reparto principal");
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        LinearLayout cards = new LinearLayout(this);
        cards.setPadding(d(16), 0, d(16), d(16));
        for (int i = 0; i < cast.length(); i++) {
            JSONObject member = cast.optJSONObject(i);
            if (member == null) continue;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(d(12), d(12), d(12), d(12));
            card.setBackground(round(PANEL, d(10)));
            TextView name = label(member.optString("name"), 14, Color.WHITE, true);
            name.setMaxLines(2);
            card.addView(name);
            String character = member.optString("character", "").trim();
            if (!character.isEmpty()) {
                TextView role = label(character, 12, MUTED, false);
                role.setMaxLines(2);
                LinearLayout.LayoutParams roleParams = new LinearLayout.LayoutParams(-1, -2);
                roleParams.topMargin = d(5);
                card.addView(role, roleParams);
            }
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(d(164), d(88));
            cardParams.setMargins(0, 0, d(8), 0);
            cards.addView(card, cardParams);
        }
        horizontal.addView(cards);
        content.addView(horizontal, new LinearLayout.LayoutParams(-1, -2));
    }

    void addMovieFileCard(JSONArray files, String fallbackPoster, JSONObject owner) {
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) continue;
            LinearLayout item = new LinearLayout(this);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(d(12), d(12), d(16), d(12));
            item.setBackground(round(PANEL, d(12)));
            ImageView poster = new ImageView(this);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            poster.setBackground(round(BG, d(8)));
            String cover = file.optString("poster_url", fallbackPoster);
            loadImage(cover, poster, false);
            item.addView(poster, new LinearLayout.LayoutParams(d(72), d(100)));

            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            details.setPadding(d(14), 0, d(8), 0);
            TextView name = label(file.optString("filename", file.optString("title", "Película")),
                    15, Color.WHITE, true);
            name.setMaxLines(2);
            details.addView(name);
            String extension = file.optString("extension", "").replace(".", "").toUpperCase();
            TextView technical = label(extension + "   ·   " + formatSize(file.optLong("size_bytes")),
                    12, MUTED, false);
            LinearLayout.LayoutParams technicalParams = new LinearLayout.LayoutParams(-1, -2);
            technicalParams.topMargin = d(6);
            details.addView(technical, technicalParams);
            TextView play = label("▶  Reproducir", 13, 0xFFFF858A, true);
            LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(-1, -2);
            playParams.topMargin = d(12);
            details.addView(play, playParams);
            item.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(-1, -2);
            itemParams.setMargins(d(16), 0, d(16), d(9));
            content.addView(item, itemParams);
            item.setOnClickListener(v -> launchPlayer(file.optString("stream_url"),
                    file.optString("title", "Película"), cover, owner.optString("id", ""),
                    file.optString("id", ""), owner.optString("kind", "")));
        }
    }

    void addEpisodeList(JSONArray files, String fallbackPoster, JSONObject owner) {
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
            String watched = isMediaSeen(file) ? "   ·   ✓ VISTO" : "";
            TextView sub = label(file.optString("extension", "").replace(".", "").toUpperCase() + "   ·   " + formatSize(file.optLong("size_bytes")) + watched,
                    12, isMediaSeen(file) ? 0xFF79E3B7 : MUTED, false);
            texts.addView(name);
            LinearLayout.LayoutParams subP = new LinearLayout.LayoutParams(-1, -2);
            subP.topMargin = d(4);
            texts.addView(sub, subP);
            item.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
            TextView actions = label("⋮", 22, Color.WHITE, true);
            actions.setGravity(Gravity.CENTER);
            actions.setFocusable(true);
            actions.setMinWidth(d(36));
            item.addView(actions);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
            ip.setMargins(d(16), 0, d(16), d(8));
            content.addView(item, ip);
            item.setOnClickListener(v -> launchPlayer(file.optString("stream_url"),
                    file.optString("title", "Zinema"), file.optString("poster_url", fallbackPoster),
                    owner.optString("id", ""), file.optString("id", ""), owner.optString("kind", "")));
            actions.setOnClickListener(v -> showMediaActions(file, owner));
        }
    }

    void playFirst(JSONObject node) {
        JSONArray files = node.optJSONArray("files");
        if (files != null && files.length() > 0) {
            JSONObject file = files.optJSONObject(0);
            if (file != null) launchPlayer(file.optString("stream_url"),
                    file.optString("title", displayTitle(node)), file.optString("poster_url", node.optString("poster_url", "")),
                    node.optString("id", ""), file.optString("id", ""), node.optString("kind", ""));
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
        libraryMode = false;
        breadcrumbs.clear();
        lastSearchQuery = null;
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
        lastSearchQuery = query;
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
        if (isMediaSeen(result)) detail.setText(detail.getText() + "   ·   ✓ VISTO");
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
        String seriesId = result.optString("series_id", "");
        boolean isEpisode = !seriesId.isEmpty() && !seriesId.equals("null");
        String metadataId = isEpisode ? seriesId : result.optString("node_id", "");
        String ownerId = isEpisode ? result.optString("node_id", "") : metadataId;
        JSONObject metadataNode = new JSONObject();
        JSONObject owner = new JSONObject();
        try {
            metadataNode.put("id", metadataId);
            metadataNode.put("kind", isEpisode ? "series" : "movie");
            owner.put("id", ownerId);
            owner.put("kind", isEpisode ? "season" : "movie");
        } catch (JSONException ignored) { }
        row.setOnClickListener(v -> launchPlayer(
                result.optString("stream_url", "/api/v1/items/" + result.optString("id") + "/stream"),
                result.optString("title", "Zinema"), result.optString("poster_url", ""),
                ownerId, result.optString("id", ""), owner.optString("kind", "")));
        actions.setOnClickListener(v -> showMediaActions(result, owner, metadataNode));
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
                    requestedImages.clear();
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
                String ownerId = item.optString("owner_node_id", item.optString("node_id", ""));
                if (item.optInt("position") > 10000 && !isMediaSeen(mediaFromProgress(item))
                        && (ownerId.isEmpty() || !prefs.getBoolean(nodeSeenKey(ownerId), false))) list.add(item);
            } catch (JSONException ignored) { }
        }
        Collections.sort(list, (a, b) -> Long.compare(b.optLong("updated"), a.optLong("updated")));
        return list;
    }

    void addResumeRow(String title, List<JSONObject> items) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        TextView heading = label(title, 22, Color.WHITE, true);
        heading.setPadding(d(20), d(8), d(20), d(12));
        section.addView(heading);
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        horizontal.setClipToPadding(false);
        horizontal.setTag("zinema-lazy-posters");
        LinearLayout cards = new LinearLayout(this);
        cards.setPadding(d(16), 0, d(16), d(8));
        for (JSONObject item : items) {
            FrameLayout card = new FrameLayout(this);
            card.setBackground(round(PANEL, d(12)));
            card.setClipToOutline(true);
            card.setFocusable(true);
            card.setClickable(true);
            ImageView poster = new ImageView(this);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(poster, new FrameLayout.LayoutParams(-1, -1));
            View shade = new View(this);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{0xFF090A0D, 0xAA090A0D, 0x11090A0D}));
            card.addView(shade, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            details.setGravity(Gravity.BOTTOM);
            details.setPadding(d(14), d(12), d(14), d(12));
            details.addView(label("▶  CONTINUAR · " + formatTime(item.optInt("position")),
                    10, 0xFFFF858A, true));
            TextView name = label(item.optString("title", "Vídeo"), 14, Color.WHITE, true);
            name.setMaxLines(2);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, -2);
            nameParams.topMargin = d(5);
            details.addView(name, nameParams);
            card.addView(details, new FrameLayout.LayoutParams(-1, -1));
            String cover = item.optString("poster_url", "");
            if (cover.isEmpty()) cover = posterByStream.get(item.optString("stream", ""));
            String resumePoster = cover == null ? "" : cover;
            showPoster(resumePoster, poster, true);
            card.setOnClickListener(v -> launchPlayer(item.optString("stream"),
                    item.optString("title", "Zinema"), resumePoster,
                    item.optString("owner_node_id", item.optString("node_id", "")),
                    item.optString("media_id", ""), item.optString("owner_kind", "")));
            TextView actions = label("⋮", 20, Color.WHITE, true);
            actions.setGravity(Gravity.CENTER);
            actions.setFocusable(true);
            actions.setBackground(round(0xCC111318, d(18)));
            FrameLayout.LayoutParams actionParams = new FrameLayout.LayoutParams(d(34), d(34), Gravity.TOP | Gravity.RIGHT);
            actionParams.setMargins(0, d(6), d(6), 0);
            card.addView(actions, actionParams);
            actions.setOnClickListener(v -> showResumeActions(item));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(d(224), d(148));
            cardParams.setMargins(0, 0, d(10), 0);
            cards.addView(card, cardParams);
        }
        horizontal.addView(cards);
        installLazyRowScroll(horizontal);
        section.addView(horizontal);
        content.addView(section);
    }

    String formatTime(int millis) {
        int total = Math.max(0, millis / 1000);
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60);
    }

    void launchPlayer(String path, String title) {
        launchPlayer(path, title, "");
    }

    void launchPlayer(String path, String title, String posterUrl) {
        launchPlayer(path, title, posterUrl, "", "", "");
    }

    void launchPlayer(String path, String title, String posterUrl,
                      String ownerNodeId, String mediaId, String ownerKind) {
        if (path == null || path.isEmpty()) return;
        String url = path.startsWith("http") ? path : server + (path.startsWith("/") ? path : "/" + path);
        String key = "watch:" + path;
        long position = 0L;
        try { position = new JSONObject(prefs.getString(key, "{}")).optLong("position"); }
        catch (JSONException ignored) { }
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra("url", url);
        intent.putExtra("path", path);
        intent.putExtra("title", title);
        intent.putExtra("poster_url", posterUrl);
        intent.putExtra("position", position);
        intent.putExtra("owner_node_id", ownerNodeId);
        intent.putExtra("media_id", mediaId);
        intent.putExtra("owner_kind", ownerKind);
        startActivity(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        if (prefs != null && page != null) {
            List<JSONObject> resume = readContinueWatching();
            if (!resume.isEmpty() && content != null && movies.size() + series.size() > 0) {
                libraryMode = false;
                showingDetail = false;
                selectedSection = "home";
                breadcrumbs.clear();
                buildShell("Inicio");
                renderHome();
            }
        }
    }

    @Override public void onBackPressed() {
        if (metadataEditorMode) {
            setMetadataEditorChrome(false);
        }
        if (metadataEditorFromDetail) {
            metadataEditorFromDetail = false;
            io.execute(() -> {
                try {
                    JSONObject node = getJson("/api/v1/catalog/" + enc(detailNodeId));
                    runOnUiThread(() -> renderDetail(node));
                } catch (Exception e) { runOnUiThread(this::loadHome); }
            });
            return;
        }
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
