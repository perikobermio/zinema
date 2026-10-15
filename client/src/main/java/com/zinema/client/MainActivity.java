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
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.util.LruCache;
import android.util.Base64;
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
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.KeyStore;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static final int BG = Color.rgb(9, 10, 13);
    static final int PANEL = Color.rgb(24, 26, 32);
    static final int MUTED = Color.rgb(168, 172, 182);
    static final int RED = Color.rgb(229, 9, 20);
    static final String PREFS = "zinema_client";
    static final String DEFAULT_SERVER = ServerConfig.DEFAULT_SERVER;
    static final String CLIENT_TOKEN_ALIAS = "zinema_client_device_token";
    static final long CATALOG_CACHE_TTL_MS = 30000L;
    static final long IMAGE_DISK_CACHE_MAX_BYTES = 160L * 1024L * 1024L;
    final ExecutorService io = Executors.newFixedThreadPool(4);
    final Handler adminSyncHandler = new Handler(Looper.getMainLooper());
    Runnable adminSyncPollTask;
    final LruCache<String, Bitmap> imageCache = new LruCache<String, Bitmap>(bitmapCacheBytes()) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getAllocationByteCount());
        }
    };
    final Map<String, List<ImageView>> pendingImageTargets = new HashMap<>();
    final Map<String, List<JSONObject>> catalogCache = new HashMap<>();
    final Map<String, Long> catalogCacheUpdatedAt = new HashMap<>();
    SharedPreferences prefs;
    FrameLayout frame;
    LinearLayout page;
    LinearLayout content;
    ScrollView contentScroller;
    TextView backButton;
    View brandButton;
    TextView searchButton;
    View profileButton;
    HorizontalScrollView navScroll;
    String server;
    String serverName;
    String currentLibrary = "movies";
    String selectedSection = "home";
    String lastSearchQuery;
    String detailNodeId = "";
    String catalogVersion = "";
    int detailReturnScrollY;
    int pendingListingRestoreY = -1;
    long lastCatalogVersionCheckMs;
    long lastProfileSyncMs;
    boolean catalogVersionCheckRunning;
    boolean profileSyncRunning;
    boolean profileStateSyncRunning;
    boolean profileStateSyncPending;
    boolean hasResumedOnce;
    boolean libraryMode;
    boolean showingDetail;
    boolean showingAdmin;
    boolean showingProfiles;
    boolean profileEditorVisible;
    boolean introShowing;
    WebView introWebView;
    boolean startupPrefetchStarted;
    boolean startupPrefetchPending = true;
    boolean startupPrefetchReady;
    boolean startupHomeAwaiting;
    List<JSONObject> startupMovies = new ArrayList<>();
    List<JSONObject> startupSeries = new ArrayList<>();
    JSONArray startupItems;
    JSONArray startupRemoteProfiles;
    JSONObject startupRemoteProfileState;
    String startupRemoteStateProfileId = "";
    String startupPrefetchError;
    String startupCatalogVersion = "";
    String adminReturnDetailId = "";
    String editingProfileId = "";
    String selectedProfileAvatar = "";
    boolean metadataEditorMode;
    String metadataEditorReturnScreen = "";
    String metadataEditorReturnDetailId = "";
    int metadataEditorReturnScrollY;
    boolean metadataEditorReturnPrepared;
    boolean metadataEditorPreviousBackVisible;
    List<JSONObject> movies = new ArrayList<>();
    List<JSONObject> series = new ArrayList<>();
    final Map<String, String> posterByStream = new HashMap<>();
    final Map<String, Set<String>> selectedGenresByLibrary = new HashMap<>();
    final Map<ImageView, String> requestedImages = new WeakHashMap<>();
    final List<JSONObject> breadcrumbs = new ArrayList<>();
    final List<Integer> breadcrumbScrollPositions = new ArrayList<>();

    static int bitmapCacheBytes() {
        return (int) Math.min(32L * 1024L * 1024L,
                Math.max(8L * 1024L * 1024L, Runtime.getRuntime().maxMemory() / 10L));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        hideSystemBars();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit().remove("profile_sync_token").apply();
        server = trimBase(DEFAULT_SERVER);
        serverName = "Zinema";
        if (!clientApiToken().isEmpty()) {
            verifySavedAccountBeforeStartup();
        } else {
            showLoginScreen();
        }
    }

    void verifySavedAccountBeforeStartup() {
        io.execute(() -> {
            try {
                JSONObject account = getJson("/api/v1/auth/me");
                if (!account.optBoolean("is_admin")
                        && (!account.has("approved") || !account.optBoolean("approved"))) {
                    runOnUiThread(() -> invalidateSavedAccount("Esta cuenta todavía necesita aprobación de un administrador."));
                    return;
                }
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    startStartupPrefetch();
                    showIntro();
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? "" : e.getMessage();
                if (message.contains("HTTP 401") || message.contains("HTTP 403")) {
                    runOnUiThread(() -> invalidateSavedAccount("La sesión no está aprobada o ya no es válida. Inicia sesión con una cuenta aprobada."));
                } else {
                    runOnUiThread(() -> invalidateSavedAccount(
                            "No se pudo verificar la cuenta. Inicia sesión cuando haya conexión."));
                }
            }
        });
    }

    void invalidateSavedAccount(String message) {
        prefs.edit().remove("client_api_token_enc").remove("client_api_token")
                .remove("client_device_id").remove("account_email").apply();
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(CLIENT_TOKEN_ALIAS)) keyStore.deleteEntry(CLIENT_TOKEN_ALIAS);
        } catch (Exception ignored) { }
        clearLocalProfilesForAccountSwitch();
        clearCatalogCache();
        movies.clear();
        series.clear();
        startupPrefetchStarted = false;
        startupPrefetchPending = false;
        startupPrefetchReady = false;
        showLoginScreen();
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    void hideSystemBars() {
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        int flags = View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    void showIntro() {
        introShowing = true;
        introWebView = new WebView(this);
        introWebView.setBackgroundColor(BG);
        introWebView.setVerticalScrollBarEnabled(false);
        introWebView.setHorizontalScrollBarEnabled(false);
        introWebView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings webSettings = introWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setAllowFileAccess(true);
        introWebView.addJavascriptInterface(new IntroBridge(), "ZinemaIntro");
        setContentView(introWebView);
        introWebView.loadUrl("file:///android_asset/zinema_intro.html?embed=1");
    }

    public final class IntroBridge {
        @JavascriptInterface public void onIntroEnded() {
            runOnUiThread(MainActivity.this::finishIntro);
        }
    }

    void finishIntro() {
        if (!introShowing || isFinishing()) return;
        introShowing = false;
        if (introWebView != null) {
            introWebView.stopLoading();
            introWebView.removeJavascriptInterface("ZinemaIntro");
            introWebView.removeAllViews();
            introWebView.destroy();
            introWebView = null;
        }
        if (clientApiToken().isEmpty()) showLoginScreen();
        else {
            buildShell("Inicio");
            loadHome();
        }
    }

    void startStartupPrefetch() {
        if (clientApiToken().isEmpty()) return;
        synchronized (this) {
            if (startupPrefetchStarted) return;
            startupPrefetchStarted = true;
        }
        io.execute(() -> {
            List<JSONObject> movieList = null;
            List<JSONObject> seriesList = null;
            JSONArray items = null;
            JSONArray remoteProfiles = null;
            JSONObject remoteState = null;
            String remoteStateProfileId = "";
            String error = null;
            String version = "";
            try {
                JSONArray movieRows = getArray("/api/v1/catalog?library=movies");
                JSONArray seriesRows = getArray("/api/v1/catalog?library=series");
                items = getArray("/api/v1/items?limit=500");
                try { version = getJson("/api/v1/catalog/version").optString("version", ""); }
                catch (Exception ignored) { }
                if (!clientApiToken().isEmpty()) {
                    try {
                        remoteProfiles = getAuthorizedProfileArray("/api/v1/profiles");
                        remoteStateProfileId = prefs.getString("active_profile", "");
                        if (remoteStateProfileId.isEmpty() && remoteProfiles.length() > 0) {
                            remoteStateProfileId = remoteProfiles.optJSONObject(0).optString("id", "");
                        }
                        if (!remoteStateProfileId.isEmpty()) {
                            remoteState = getAuthorizedProfileJson("/api/v1/profiles/" +
                                    enc(remoteStateProfileId) + "/state");
                        }
                    } catch (Exception ignored) { }
                }
                movieList = toList(movieRows);
                seriesList = toList(seriesRows);
                sortNewestFirst(movieList);
                sortNewestFirst(seriesList);
            } catch (Exception e) {
                error = e.getMessage();
            }
            synchronized (MainActivity.this) {
                startupMovies = movieList;
                startupSeries = seriesList;
                startupItems = items;
                startupRemoteProfiles = remoteProfiles;
                startupRemoteProfileState = remoteState;
                startupRemoteStateProfileId = remoteStateProfileId;
                startupCatalogVersion = version;
                startupPrefetchError = error;
                startupPrefetchReady = true;
            }
            runOnUiThread(this::consumeStartupPrefetch);
        });
    }

    void consumeStartupPrefetch() {
        List<JSONObject> movieList;
        List<JSONObject> seriesList;
        JSONArray items;
        JSONArray remoteProfiles;
        JSONObject remoteState;
        String remoteStateProfileId;
        String error;
        String version;
        synchronized (this) {
            if (!startupPrefetchPending || !startupPrefetchReady || !startupHomeAwaiting) return;
            startupPrefetchPending = false;
            startupHomeAwaiting = false;
            movieList = startupMovies;
            seriesList = startupSeries;
            items = startupItems;
            remoteProfiles = startupRemoteProfiles;
            remoteState = startupRemoteProfileState;
            remoteStateProfileId = startupRemoteStateProfileId;
            version = startupCatalogVersion;
            error = startupPrefetchError;
        }
        boolean hasRemoteProfiles = false;
        if (remoteProfiles != null) {
            for (int i = 0; i < remoteProfiles.length(); i++) {
                JSONObject profile = remoteProfiles.optJSONObject(i);
                if (profile != null && !profile.optBoolean("deleted", false)) hasRemoteProfiles = true;
            }
        }
        boolean needsProfileChoice = hasRemoteProfiles && activeProfileId().isEmpty();
        if (error != null) {
            if (!readProfiles().isEmpty()) {
                if (showingProfiles) {
                    if (!profileEditorVisible) renderProfileManager();
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                } else renderError(error);
                return;
            }
            movieList = new ArrayList<>();
            seriesList = new ArrayList<>();
        }
        movies = movieList == null ? new ArrayList<>() : movieList;
        series = seriesList == null ? new ArrayList<>() : seriesList;
        if (remoteProfiles != null) mergeRemoteProfiles(remoteProfiles);
        putCatalogList("movies", null, movies);
        putCatalogList("series", null, series);
        catalogVersion = version == null ? "" : version;
        lastCatalogVersionCheckMs = System.currentTimeMillis();
        if (items != null) cachePosterByStream(items);
        if (remoteProfiles != null) lastProfileSyncMs = System.currentTimeMillis();
        if (remoteState != null && remoteStateProfileId.equals(activeProfileId())) {
            JSONObject state = remoteState.optJSONObject("state");
            boolean localDirty = prefs.getBoolean(profileStateDirtyKey(remoteStateProfileId), false);
            boolean localHasState = collectProfileState(remoteStateProfileId).keys().hasNext();
            if (localDirty || (remoteState.optInt("revision") == 0 && localHasState)) {
                if (localHasState) scheduleProfileStateSync();
                else if (state != null) applyProfileState(remoteStateProfileId, state);
            } else if (state != null) applyProfileState(remoteStateProfileId, state);
        }
        if (readProfiles().isEmpty()) {
            if (!profileEditorVisible) openProfileManager();
            return;
        }
        if (needsProfileChoice) {
            showingProfiles = true;
            buildShell("Perfiles");
            renderProfileManager();
            return;
        }
        renderHome();
    }

    void leaveStartupHomeWait() {
        startupHomeAwaiting = false;
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
        bar.setPadding(d(20), d(20), d(16), d(8));
        bar.setBackgroundColor(BG);
        backButton = navAction("‹", "Atrás", v -> onBackPressed());
        backButton.setTextSize(30);
        backButton.setVisibility(title.equals("Inicio") ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(d(36), d(42));
        backParams.rightMargin = d(8);
        bar.addView(backButton, backParams);
        if (showingAdmin) {
            brandButton = label(serverName, 22, Color.WHITE, true);
        } else {
            ImageView logo = new ImageView(this);
            logo.setImageResource(R.drawable.zinema_wordmark);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logo.setAdjustViewBounds(true);
            logo.setContentDescription("Zinema");
            brandButton = logo;
        }
        brandButton.setOnClickListener(v -> { showingAdmin = false; loadHome(); });
        applyTvFocusStyle(brandButton, false);
        LinearLayout.LayoutParams brandParams = showingAdmin
                ? new LinearLayout.LayoutParams(0, -2, 1)
                : new LinearLayout.LayoutParams(d(132), d(42));
        bar.addView(brandButton, brandParams);
        if (!showingAdmin) bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        searchButton = navAction("⌕", "Buscar", v -> showSearch());
        searchButton.setBackground(round(PANEL, d(22)));
        searchButton.setElevation(d(4));
        bar.addView(searchButton, new LinearLayout.LayoutParams(d(44), d(44)));
        profileButton = buildProfileChip();
        LinearLayout.LayoutParams profileParams = new LinearLayout.LayoutParams(-2, d(42));
        profileParams.leftMargin = d(10);
        bar.addView(profileButton, profileParams);
        if (showingAdmin) {
            searchButton.setVisibility(View.GONE);
            profileButton.setVisibility(View.GONE);
        }
        page.addView(bar, new LinearLayout.LayoutParams(-1, d(68)));

        navScroll = new HorizontalScrollView(this);
        navScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(d(16), 0, d(16), d(8));
        nav.addView(navChip("Inicio", () -> loadHome(), selectedSection.equals("home")));
        nav.addView(navChip("Películas", () -> openLibrary("movies"), selectedSection.equals("movies")));
        nav.addView(navChip("Series", () -> openLibrary("series"), selectedSection.equals("series")));
        navScroll.addView(nav);
        page.addView(navScroll, new LinearLayout.LayoutParams(-1, d(48)));
        if (showingAdmin || showingProfiles) navScroll.setVisibility(View.GONE);

        contentScroller = new ScrollView(this);
        contentScroller.setFillViewport(true);
        contentScroller.setVerticalScrollBarEnabled(false);
        contentScroller.setBackgroundColor(BG);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, d(28));
        contentScroller.addView(content);
        contentScroller.setOnScrollChangeListener((View view, int x, int y, int oldX, int oldY) ->
                loadVisibleLazyRows());
        page.addView(contentScroller, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(frame);
        frame.post(() -> {
            if (showingAdmin) {
                backButton.requestFocus();
                return;
            }
            if (showingProfiles) {
                View first = firstFocusable(content);
                if (first != null) first.requestFocus();
                return;
            }
            int tabIndex = "movies".equals(selectedSection) ? 1
                    : "series".equals(selectedSection) ? 2 : 0;
            View navView = navScroll.getChildAt(0);
            if (navView instanceof ViewGroup && ((ViewGroup) navView).getChildAt(tabIndex) != null) {
                ((ViewGroup) navView).getChildAt(tabIndex).requestFocus();
            }
        });
    }

    View firstFocusable(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return null;
        if (view.isFocusable()) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = firstFocusable(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(t(text));
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    TextView labelRaw(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    TextView navAction(String glyph, String hint, View.OnClickListener onClick) {
        TextView view = label(glyph, 23, Color.WHITE, true);
        view.setContentDescription(t(hint));
        view.setGravity(Gravity.CENTER);
        view.setMinWidth(d(40));
        view.setFocusable(true);
        view.setClickable(true);
        view.setOnClickListener(onClick);
        applyTvFocusStyle(view, false);
        return view;
    }

    void applyTvFocusStyle(View view, boolean scaleOnFocus) {
        view.setFocusable(true);
        view.setFocusableInTouchMode(false);
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(Color.TRANSPARENT);
        outline.setCornerRadius(d(9));
        outline.setStroke(d(1), Color.TRANSPARENT);
        view.setForeground(outline);
        view.setOnFocusChangeListener((focusedView, hasFocus) -> {
            outline.setStroke(d(hasFocus ? 2 : 1), hasFocus ? RED : Color.TRANSPARENT);
            focusedView.setForeground(outline);
            focusedView.animate().cancel();
            float scale = hasFocus && scaleOnFocus ? 1.045f : 1f;
            focusedView.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
            focusedView.setElevation(hasFocus ? d(7) : 0);
        });
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
        applyTvFocusStyle(tab, false);
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
        String token = clientApiToken();
        if (token.isEmpty()) throw new Exception("Configura la clave de acceso a la API en Administración.");
        HttpURLConnection connection = (HttpURLConnection) new URL(server + path).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        int status = connection.getResponseCode();
        InputStream input = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String body = readText(input);
        connection.disconnect();
        if (status < 200 || status >= 300) throw new Exception("HTTP " + status + (body.isEmpty() ? "" : ": " + body));
        return new JSONObject(body);
    }

    JSONObject getAccountInfoForToken(String token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(server + "/api/v1/auth/me").openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        int status = connection.getResponseCode();
        InputStream input = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String body = readText(input);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            String detail = body;
            try { detail = new JSONObject(body).optString("detail", body); }
            catch (JSONException ignored) { }
            throw new Exception("HTTP " + status + (detail.isEmpty() ? "" : ": " + detail));
        }
        return new JSONObject(body);
    }

    JSONArray getArray(String path) throws Exception {
        String token = clientApiToken();
        if (token.isEmpty()) throw new Exception("Configura la clave de acceso a la API en Administración.");
        HttpURLConnection connection = (HttpURLConnection) new URL(server + path).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        int status = connection.getResponseCode();
        InputStream input = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String body = readText(input);
        connection.disconnect();
        if (status < 200 || status >= 300) throw new Exception("HTTP " + status + (body.isEmpty() ? "" : ": " + body));
        return new JSONArray(body);
    }

    String clientApiToken() {
        if (prefs == null) return "";
        String encrypted = prefs.getString("client_api_token_enc", "");
        if (encrypted.isEmpty()) return "";
        try {
            byte[] data = Base64.decode(encrypted, Base64.NO_WRAP);
            byte[] iv = java.util.Arrays.copyOfRange(data, 0, 12);
            byte[] ciphertext = java.util.Arrays.copyOfRange(data, 12, data.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, clientTokenKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) { return ""; }
    }

    void storeClientApiToken(String token) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, clientTokenKey());
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, encrypted, 0, iv.length);
        System.arraycopy(ciphertext, 0, encrypted, iv.length, ciphertext.length);
        prefs.edit().putString("client_api_token_enc", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .remove("client_api_token").apply();
    }

    SecretKey clientTokenKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(CLIENT_TOKEN_ALIAS)) {
            return (SecretKey) keyStore.getKey(CLIENT_TOKEN_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(CLIENT_TOKEN_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    String authorizedProfileRequest(String method, String path, JSONObject payload) throws Exception {
        String token = clientApiToken();
        if (token.isEmpty()) throw new Exception("Vincula este dispositivo desde Administración.");
        HttpURLConnection connection = (HttpURLConnection) new URL(server + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        if (payload != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.getOutputStream().write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        InputStream input = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String body = readText(input);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            String message = body;
            try { message = new JSONObject(body).optString("detail", body); }
            catch (JSONException ignored) { }
            throw new Exception("HTTP " + status + (message.isEmpty() ? "" : ": " + message));
        }
        return body;
    }

    JSONArray getAuthorizedProfileArray(String path) throws Exception {
        return new JSONArray(authorizedProfileRequest("GET", path, null));
    }

    JSONObject getAuthorizedProfileJson(String path) throws Exception {
        return new JSONObject(authorizedProfileRequest("GET", path, null));
    }

    JSONObject putAuthorizedProfileJson(String path, JSONObject payload) throws Exception {
        return new JSONObject(authorizedProfileRequest("PUT", path, payload));
    }

    JSONObject postAuthorizedProfileJson(String path, JSONObject payload) throws Exception {
        return new JSONObject(authorizedProfileRequest("POST", path, payload));
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
        if (clientApiToken().isEmpty()) {
            showingProfiles = false;
            profileEditorVisible = false;
            showSettings();
            return;
        }
        List<JSONObject> savedProfiles = readProfiles();
        if (savedProfiles.isEmpty()) {
            startupHomeAwaiting = true;
            openProfileManager();
            consumeStartupPrefetch();
            return;
        }
        boolean activeFound = false;
        for (JSONObject profile : savedProfiles) {
            if (activeProfileId().equals(profile.optString("id"))) activeFound = true;
        }
        if (!activeFound) prefs.edit().putString("active_profile", savedProfiles.get(0).optString("id")).apply();
        showingProfiles = false;
        libraryMode = false;
        showingDetail = false;
        lastSearchQuery = null;
        selectedSection = "home";
        breadcrumbs.clear();
        breadcrumbScrollPositions.clear();
        buildShell("Inicio");
        List<JSONObject> cachedMovies = getCachedCatalogList("movies", null);
        List<JSONObject> cachedSeries = getCachedCatalogList("series", null);
        if (cachedMovies != null && cachedSeries != null) {
            movies = cachedMovies;
            series = cachedSeries;
            renderHome();
            maybeRefreshHomeCatalog();
            return;
        }
        showLoading("Conectando con tu biblioteca…");
        if (startupPrefetchPending) {
            startupHomeAwaiting = true;
            consumeStartupPrefetch();
            return;
        }
        fetchHomeFromServer();
    }

    List<JSONObject> readProfiles() {
        List<JSONObject> result = new ArrayList<>();
        String raw = prefs.getString("profiles_json", "[]");
        try {
            JSONArray profiles = new JSONArray(raw);
            for (int i = 0; i < profiles.length(); i++) {
                JSONObject profile = profiles.optJSONObject(i);
                if (profile != null && !profile.optString("id", "").isEmpty()) result.add(profile);
            }
        } catch (JSONException ignored) { }
        return result;
    }

    void saveProfiles(List<JSONObject> profiles) {
        JSONArray array = new JSONArray();
        for (JSONObject profile : profiles) array.put(profile);
        prefs.edit().putString("profiles_json", array.toString()).apply();
    }

    String profileStateDirtyKey(String id) { return "profile_sync_dirty:" + id; }
    String profileMetadataDirtyKey(String id) { return "profile_metadata_dirty:" + id; }

    Set<String> deletedProfileIds() {
        return new HashSet<>(prefs.getStringSet("profile_deleted_ids", new HashSet<>()));
    }

    void forgetDeletedProfile(String id) {
        Set<String> deleted = deletedProfileIds();
        if (!deleted.remove(id)) return;
        prefs.edit().putStringSet("profile_deleted_ids", deleted).apply();
    }

    void cleanupProfileLocalData(String id) {
        String prefix = "p:" + id + ":";
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(prefix) || key.equals(profileStateDirtyKey(id))
                    || key.equals(profileMetadataDirtyKey(id)) || key.equals("profile_sync_revision:" + id)) {
                editor.remove(key);
            }
        }
        if (activeProfileId().equals(id)) editor.remove("active_profile");
        editor.apply();
    }

    boolean isProfileStateKey(String key) {
        return key.startsWith("watch:") || key.startsWith("seen:") || key.startsWith("fav:");
    }

    JSONObject collectProfileState(String profileId) {
        JSONObject state = new JSONObject();
        String prefix = "p:" + profileId + ":";
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(prefix)) continue;
            String shortKey = key.substring(prefix.length());
            if (!isProfileStateKey(shortKey)) continue;
            try { state.put(shortKey, entry.getValue()); }
            catch (JSONException ignored) { }
        }
        return state;
    }

    void applyProfileState(String profileId, JSONObject state) {
        String prefix = "p:" + profileId + ":";
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (!key.startsWith(prefix)) continue;
            if (isProfileStateKey(key.substring(prefix.length()))) editor.remove(key);
        }
        if (state != null) {
            java.util.Iterator<String> keys = state.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!isProfileStateKey(key)) continue;
                Object value = state.opt(key);
                if (value instanceof Boolean) editor.putBoolean(prefix + key, (Boolean) value);
                else if (value != null && value != JSONObject.NULL) editor.putString(prefix + key, String.valueOf(value));
            }
        }
        editor.putBoolean(profileStateDirtyKey(profileId), false).apply();
    }

    JSONObject profilePayload(JSONObject profile) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("id", profile.optString("id"));
            payload.put("name", profile.optString("name", "Profil"));
            payload.put("avatar", profile.optString("avatar", "generic:0"));
        } catch (JSONException ignored) { }
        return payload;
    }

    void mergeRemoteProfiles(JSONArray remoteRows) {
        List<JSONObject> local = readProfiles();
        Set<String> remoteIds = new HashSet<>();
        Set<String> deleted = deletedProfileIds();
        boolean deletionSetChanged = false;
        for (int i = 0; i < remoteRows.length(); i++) {
            JSONObject remote = remoteRows.optJSONObject(i);
            if (remote == null) continue;
            String id = remote.optString("id", "");
            if (id.isEmpty()) continue;
            if (remote.optBoolean("deleted", false)) {
                for (int j = local.size() - 1; j >= 0; j--) {
                    if (id.equals(local.get(j).optString("id"))) local.remove(j);
                }
                if (deleted.add(id)) deletionSetChanged = true;
                cleanupProfileLocalData(id);
                continue;
            }
            if (deleted.contains(id)) {
                deleteProfileFromServer(id);
                continue;
            }
            remoteIds.add(id);
            boolean replaced = false;
            for (int j = 0; j < local.size(); j++) {
                if (id.equals(local.get(j).optString("id"))) {
                    if (!prefs.getBoolean(profileMetadataDirtyKey(id), false)) local.set(j, remote);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) local.add(remote);
        }
        if (deletionSetChanged) prefs.edit().putStringSet("profile_deleted_ids", deleted).apply();
        saveProfiles(local);
        String active = activeProfileId();
        boolean activeExists = false;
        for (JSONObject profile : local) if (active.equals(profile.optString("id"))) activeExists = true;
        if (!activeExists && !local.isEmpty()) {
            active = local.get(0).optString("id");
            prefs.edit().putString("active_profile", active).apply();
        }
        for (JSONObject profile : local) {
            String id = profile.optString("id");
            boolean metadataDirty = prefs.getBoolean(profileMetadataDirtyKey(id), false);
            boolean stateDirty = prefs.getBoolean(profileStateDirtyKey(id), false);
            if (!remoteIds.contains(id) || metadataDirty) {
                if (collectProfileState(id).keys().hasNext()) {
                    prefs.edit().putBoolean(profileStateDirtyKey(id), true).apply();
                }
                uploadProfileToServer(profile);
            } else if (stateDirty) {
                uploadProfileState(id);
            }
        }
    }

    void uploadProfileToServer(JSONObject profile) {
        if (clientApiToken().isEmpty()) return;
        JSONObject payload = profilePayload(profile);
        io.execute(() -> {
            try {
                postAuthorizedProfileJson("/api/v1/profiles", payload);
                String id = profile.optString("id");
                prefs.edit().putBoolean(profileMetadataDirtyKey(id), false).apply();
                JSONObject state = collectProfileState(profile.optString("id"));
                if (prefs.getBoolean(profileStateDirtyKey(id), false) && state.keys().hasNext()) {
                    JSONObject result = putAuthorizedProfileJson("/api/v1/profiles/" +
                            enc(id) + "/state", state);
                    prefs.edit().putInt("profile_sync_revision:" + id, result.optInt("revision"))
                            .putBoolean(profileStateDirtyKey(id), false).apply();
                }
            }
            catch (Exception ignored) { }
        });
    }

    void deleteProfileFromServer(String profileId) {
        if (clientApiToken().isEmpty()) return;
        io.execute(() -> {
            try {
                authorizedProfileRequest("DELETE", "/api/v1/profiles/" + enc(profileId), null);
                forgetDeletedProfile(profileId);
            }
            catch (Exception ignored) { }
        });
    }

    void uploadProfileState(String profileId) {
        if (clientApiToken().isEmpty()) return;
        JSONObject state = collectProfileState(profileId);
        JSONObject payload = new JSONObject();
        try {
            java.util.Iterator<String> keys = state.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                payload.put(key, state.opt(key));
            }
        } catch (JSONException ignored) { }
        io.execute(() -> {
            try {
                JSONObject result = putAuthorizedProfileJson("/api/v1/profiles/" + enc(profileId) + "/state", payload);
                prefs.edit().putInt("profile_sync_revision:" + profileId, result.optInt("revision"))
                        .putBoolean(profileStateDirtyKey(profileId), false).apply();
            } catch (Exception ignored) { }
        });
    }

    synchronized void scheduleProfileStateSync() {
        String id = activeProfileId();
        if (id.isEmpty()) return;
        prefs.edit().putBoolean(profileStateDirtyKey(id), true).apply();
        if (clientApiToken().isEmpty()) return;
        if (profileStateSyncRunning) {
            profileStateSyncPending = true;
            return;
        }
        profileStateSyncRunning = true;
        io.execute(() -> {
            while (true) {
                String profileId = activeProfileId();
                JSONObject state = collectProfileState(profileId);
                JSONObject payload = new JSONObject();
                try {
                    java.util.Iterator<String> keys = state.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        payload.put(key, state.opt(key));
                    }
                    JSONObject result = putAuthorizedProfileJson(
                            "/api/v1/profiles/" + enc(profileId) + "/state", payload);
                    prefs.edit().putInt("profile_sync_revision:" + profileId, result.optInt("revision"))
                            .putBoolean(profileStateDirtyKey(profileId), false).apply();
                } catch (Exception ignored) { }
                synchronized (MainActivity.this) {
                    if (profileStateSyncPending) {
                        profileStateSyncPending = false;
                    } else {
                        profileStateSyncRunning = false;
                        return;
                    }
                }
            }
        });
    }

    void synchronizeProfilesFromServer(boolean notifyError) {
        String token = clientApiToken();
        long now = System.currentTimeMillis();
        if (token.isEmpty() || profileSyncRunning || now - lastProfileSyncMs < 60000L) return;
        profileSyncRunning = true;
        lastProfileSyncMs = now;
        io.execute(() -> {
            try {
                JSONArray remote = getAuthorizedProfileArray("/api/v1/profiles");
                String currentId = activeProfileId();
                JSONObject remoteState = null;
                boolean localStateDirty = !currentId.isEmpty()
                        && prefs.getBoolean(profileStateDirtyKey(currentId), false);
                if (!currentId.isEmpty() && !localStateDirty) {
                    boolean found = false;
                    for (int i = 0; i < remote.length(); i++) {
                        JSONObject profile = remote.optJSONObject(i);
                        if (profile != null && currentId.equals(profile.optString("id"))) found = true;
                    }
                    if (found) remoteState = getAuthorizedProfileJson(
                            "/api/v1/profiles/" + enc(currentId) + "/state");
                }
                JSONObject stateResult = remoteState;
                runOnUiThread(() -> {
                    profileSyncRunning = false;
                    mergeRemoteProfiles(remote);
                    String activeId = activeProfileId();
                    if (stateResult != null && activeId.equals(currentId)) {
                        boolean localHasState = collectProfileState(activeId).keys().hasNext();
                        if (prefs.getBoolean(profileStateDirtyKey(activeId), false)
                                || (stateResult.optInt("revision") == 0 && localHasState)) {
                            scheduleProfileStateSync();
                        }
                        else applyProfileState(activeId, stateResult.optJSONObject("state"));
                    }
                    if (readProfiles().isEmpty()) openProfileManager();
                    else if (showingProfiles && !profileEditorVisible) renderProfileManager();
                    else if (!showingDetail && !showingAdmin && !showingProfiles && "home".equals(selectedSection)) {
                        renderHome();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    profileSyncRunning = false;
                    if (notifyError) Toast.makeText(MainActivity.this,
                            "No se pudieron sincronizar los perfiles: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    String activeProfileId() { return prefs.getString("active_profile", ""); }

    JSONObject activeProfile() {
        String id = activeProfileId();
        for (JSONObject profile : readProfiles()) {
            if (id.equals(profile.optString("id"))) return profile;
        }
        List<JSONObject> profiles = readProfiles();
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    String activeProfileAvatar() {
        JSONObject profile = activeProfile();
        return profile == null ? "generic:0" : profile.optString("avatar", "generic:0");
    }

    String progressPrefix(String type) {
        String id = activeProfileId();
        return id.isEmpty() ? type + ":" : "p:" + id + ":" + type + ":";
    }

    List<JSONObject> profileAvatarOptions() {
        List<JSONObject> options = new ArrayList<>();
        Set<String> used = new HashSet<>();
        ArrayList<JSONObject> catalog = new ArrayList<>();
        catalog.addAll(movies);
        catalog.addAll(series);
        for (JSONObject node : catalog) {
            String poster = node.optString("poster_url", "");
            String avatar = "catalog:" + node.optString("id", "");
            if (poster.isEmpty()) {
                JSONArray mosaic = node.optJSONArray("mosaic_images");
                if (mosaic != null && mosaic.length() > 0) {
                    poster = mosaic.optString(0, "");
                    avatar = "url:" + poster;
                }
            }
            if (poster.isEmpty() || !used.add(poster)) continue;
            JSONObject option = new JSONObject();
            try {
                option.put("avatar", avatar);
                option.put("title", displayTitle(node));
            } catch (JSONException ignored) { }
            options.add(option);
            if (options.size() == 20) break;
        }
        for (int i = 0; options.size() < 20; i++) {
            JSONObject option = new JSONObject();
            try {
                option.put("avatar", "generic:" + i);
                option.put("title", t("Avatar") + " " + (options.size() + 1));
            } catch (JSONException ignored) { }
            options.add(option);
        }
        return options;
    }

    String genericAvatarGlyph(String avatar) {
        String[] glyphs = {"🎬", "🧙", "🚀", "🕵️", "🦸", "👽", "🐉", "🤖", "🧛", "🏴‍☠️",
                "🦊", "🐼", "🐺", "🦉", "🐱", "🦁", "👑", "⚔️", "🌟", "🎭"};
        int index = 0;
        try { index = Integer.parseInt(avatar.substring("generic:".length())); }
        catch (Exception ignored) { }
        return glyphs[(index % glyphs.length + glyphs.length) % glyphs.length];
    }

    String profileAvatarImagePath(String avatar) {
        if (avatar == null) return "";
        if (avatar.startsWith("url:")) return avatar.substring("url:".length());
        if (!avatar.startsWith("catalog:")) return "";
        String id = avatar.substring("catalog:".length());
        for (JSONObject node : movies) if (id.equals(node.optString("id"))) return node.optString("poster_url", "");
        for (JSONObject node : series) if (id.equals(node.optString("id"))) return node.optString("poster_url", "");
        return "";
    }

    FrameLayout profileAvatarView(String avatar, int size, String description, boolean clickable) {
        FrameLayout holder = new FrameLayout(this);
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(79, 34, 48), Color.rgb(34, 55, 79)});
        base.setShape(GradientDrawable.OVAL);
        holder.setBackground(base);
        holder.setClipToOutline(true);
        holder.setFocusable(clickable);
        holder.setClickable(clickable);
        holder.setContentDescription(description);
        TextView fallback = label(genericAvatarGlyph(avatar), Math.max(20, size / d(2)), Color.WHITE, false);
        fallback.setGravity(Gravity.CENTER);
        holder.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
        String imagePath = profileAvatarImagePath(avatar);
        if (!imagePath.isEmpty()) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable clear = new GradientDrawable();
            clear.setColor(Color.TRANSPARENT);
            clear.setShape(GradientDrawable.OVAL);
            image.setBackground(clear);
            image.setClipToOutline(true);
            holder.addView(image, new FrameLayout.LayoutParams(-1, -1));
            loadImage(imagePath, image, false);
        }
        if (clickable) {
            applyTvFocusStyle(holder, false);
            holder.setOnClickListener(v -> openProfileManager());
        }
        return holder;
    }

    View buildProfileChip() {
        LinearLayout chip = new LinearLayout(this);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(d(5), d(3), d(10), d(3));
        chip.setBackground(round(PANEL, d(24)));
        JSONObject profile = activeProfile();
        String name = profile == null ? t("Perfil") : profile.optString("name", t("Perfil"));
        FrameLayout avatar = profileAvatarView(activeProfileAvatar(), d(34), name, false);
        chip.addView(avatar, new LinearLayout.LayoutParams(d(34), d(34)));
        TextView label = label(name, 12, Color.WHITE, true);
        label.setMaxWidth(d(100));
        label.setSingleLine(true);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(-2, -2);
        textParams.leftMargin = d(7);
        chip.addView(label, textParams);
        TextView dropdown = label("▾", 15, MUTED, true);
        LinearLayout.LayoutParams dropdownParams = new LinearLayout.LayoutParams(-2, -2);
        dropdownParams.leftMargin = d(5);
        chip.addView(dropdown, dropdownParams);
        chip.setFocusable(true);
        chip.setClickable(true);
        chip.setContentDescription(t("Cambiar perfil") + ": " + name);
        applyTvFocusStyle(chip, false);
        chip.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(MainActivity.this, chip);
            menu.getMenu().add(android.view.Menu.NONE, 1, 1, "👤   " + t("Cambiar perfil"));
            menu.getMenu().add(android.view.Menu.NONE, 2, 2, "⚙   " + t("Administración"));
            menu.getMenu().add(android.view.Menu.NONE, 5, 3, "👥   Gestión de cuentas");
            menu.getMenu().add(android.view.Menu.NONE, 3, 4, "⏻   " + t("Cerrar sesión"));
            menu.getMenu().add(android.view.Menu.NONE, 4, 5, "✕   " + t("Salir"));
            menu.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) openProfileManager();
                else if (item.getItemId() == 2) showSettings();
                else if (item.getItemId() == 5) showAccountManagement();
                else if (item.getItemId() == 3) logoutAccount();
                else if (item.getItemId() == 4) finishAndRemoveTask();
                return true;
            });
            menu.show();
        });
        return chip;
    }

    void logoutAccount() {
        String token = clientApiToken();
        String base = server;
        if (!token.isEmpty()) {
            io.execute(() -> {
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(base + "/api/v1/auth/logout").openConnection();
                    connection.setRequestMethod("POST");
                    connection.setConnectTimeout(7000);
                    connection.setReadTimeout(7000);
                    connection.setRequestProperty("Authorization", "Bearer " + token);
                    connection.getResponseCode();
                } catch (Exception ignored) { }
                finally { if (connection != null) connection.disconnect(); }
            });
        }
        prefs.edit().remove("client_api_token_enc").remove("client_api_token")
                .remove("client_device_id").remove("account_email").remove("profile_sync_token").apply();
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(CLIENT_TOKEN_ALIAS)) keyStore.deleteEntry(CLIENT_TOKEN_ALIAS);
        } catch (Exception ignored) { }
        clearLocalProfilesForAccountSwitch();
        clearCatalogCache();
        movies.clear();
        series.clear();
        catalogVersion = "";
        showingDetail = false;
        showingProfiles = false;
        profileEditorVisible = false;
        metadataEditorMode = false;
        libraryMode = false;
        startupPrefetchPending = false;
        startupPrefetchReady = false;
        startupPrefetchStarted = false;
        startupHomeAwaiting = false;
        showingAdmin = false;
        showLoginScreen();
        Toast.makeText(this, t("Has cerrado sesión."), Toast.LENGTH_LONG).show();
    }

    void refreshProfileChip() {
        if (profileButton == null || !(profileButton.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) profileButton.getParent();
        int index = parent.indexOfChild(profileButton);
        ViewGroup.LayoutParams params = profileButton.getLayoutParams();
        parent.removeView(profileButton);
        profileButton = buildProfileChip();
        parent.addView(profileButton, index, params);
    }

    void openProfileManager() {
        if (clientApiToken().isEmpty()) {
            Toast.makeText(this, t("Inicia sesión con una cuenta aprobada para crear un perfil."), Toast.LENGTH_LONG).show();
            showSettings();
            return;
        }
        showingProfiles = true;
        profileEditorVisible = false;
        buildShell("Perfiles");
        renderProfileManager();
    }

    void renderProfileManager() {
        if (content == null) return;
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(d(28), d(18), d(28), d(30));
        scroll.addView(body);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        body.addView(label(t("¿Quién está viendo?"), 26, Color.WHITE, true));
        TextView hint = label(t("Cada perfil conserva su lista y su progreso de reproducción."), 14, MUTED, false);
        hint.setPadding(0, d(6), 0, d(18));
        body.addView(hint);
        List<JSONObject> profiles = readProfiles();
        for (JSONObject profile : profiles) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(d(12), d(10), d(12), d(10));
            row.setBackground(round(PANEL, d(12)));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.bottomMargin = d(10);
            body.addView(row, rowParams);
            String id = profile.optString("id");
            String name = profile.optString("name", "Perfil");
            FrameLayout avatar = profileAvatarView(profile.optString("avatar", "generic:0"), d(58), name, false);
            row.addView(avatar, new LinearLayout.LayoutParams(d(58), d(58)));
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setGravity(Gravity.CENTER_VERTICAL);
            info.setPadding(d(12), 0, d(10), 0);
            row.addView(info, new LinearLayout.LayoutParams(0, -1, 1));
            info.addView(label(name, 17, Color.WHITE, true));
            if (id.equals(activeProfileId())) info.addView(label(t("Perfil actual"), 12, 0xFFFF777D, false));
            Button use = actionButton(t("Usar"), true);
            row.addView(use, new LinearLayout.LayoutParams(-2, d(42)));
            use.setOnClickListener(v -> selectProfile(id));
            Button edit = actionButton("✎", false);
            LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(d(44), d(42));
            editParams.leftMargin = d(8);
            row.addView(edit, editParams);
            edit.setContentDescription(t("Editar perfil") + " " + name);
            edit.setOnClickListener(v -> showProfileEditor(profile));
            row.setFocusable(true);
            applyTvFocusStyle(row, true);
            row.setOnClickListener(v -> selectProfile(id));
        }
        Button add = actionButton(t("Añadir perfil"), profiles.isEmpty());
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-2, d(48));
        addParams.topMargin = d(8);
        body.addView(add, addParams);
        add.setOnClickListener(v -> showProfileEditor(null));
    }

    void selectProfile(String id) {
        prefs.edit().putString("active_profile", id).apply();
        lastProfileSyncMs = 0L;
        showingProfiles = false;
        profileEditorVisible = false;
        loadHome();
        synchronizeProfilesFromServer(false);
    }

    void showProfileEditor(JSONObject existing) {
        profileEditorVisible = true;
        editingProfileId = existing == null ? "" : existing.optString("id", "");
        selectedProfileAvatar = existing == null ? "generic:0" : existing.optString("avatar", "generic:0");
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(d(28), d(16), d(28), d(28));
        scroll.addView(body);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        body.addView(label(existing == null ? t("Crear perfil") : t("Editar perfil"), 25, Color.WHITE, true));
        TextView nameLabel = label(t("Nombre"), 14, MUTED, true);
        nameLabel.setPadding(0, d(18), 0, d(7));
        body.addView(nameLabel);
        EditText name = metadataField(existing == null ? "" : existing.optString("name", ""), t("Nombre del perfil"), false);
        name.setSingleLine(true);
        body.addView(name, new LinearLayout.LayoutParams(-1, d(50)));
        TextView avatarLabel = label(t("Elige un avatar"), 14, MUTED, true);
        avatarLabel.setPadding(0, d(20), 0, d(10));
        body.addView(avatarLabel);
        List<JSONObject> options = profileAvatarOptions();
        int columns = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density > 850 ? 5 : 4;
        List<View> cells = new ArrayList<>();
        int rows = (options.size() + columns - 1) / columns;
        for (int r = 0; r < rows; r++) {
            LinearLayout line = new LinearLayout(this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            body.addView(line, new LinearLayout.LayoutParams(-1, d(112)));
            for (int c = 0; c < columns; c++) {
                int index = r * columns + c;
                if (index >= options.size()) {
                    line.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
                    continue;
                }
                JSONObject option = options.get(index);
                String avatar = option.optString("avatar", "generic:0");
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(d(4), d(4), d(4), d(4));
                cell.setBackground(round(avatar.equals(selectedProfileAvatar) ? 0x664C2028 : Color.TRANSPARENT, d(10)));
                cell.setFocusable(true);
                cell.setClickable(true);
                cell.setTag(avatar);
                applyTvFocusStyle(cell, false);
                FrameLayout picture = profileAvatarView(avatar, d(58), option.optString("title", "Avatar"), false);
                cell.addView(picture, new LinearLayout.LayoutParams(d(58), d(58)));
                TextView title = label(option.optString("title", "Avatar"), 10, Color.WHITE, false);
                title.setGravity(Gravity.CENTER);
                title.setMaxLines(2);
                cell.addView(title, new LinearLayout.LayoutParams(-1, d(34)));
                cells.add(cell);
                line.addView(cell, new LinearLayout.LayoutParams(0, -1, 1));
                cell.setOnClickListener(v -> {
                    selectedProfileAvatar = avatar;
                    for (View entry : cells) {
                        String value = String.valueOf(entry.getTag());
                        entry.setBackground(round(value.equals(selectedProfileAvatar) ? 0x664C2028 : Color.TRANSPARENT, d(10)));
                    }
                });
            }
        }
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(-1, -2);
        buttonsParams.topMargin = d(20);
        body.addView(buttons, buttonsParams);
        Button save = actionButton(t("Guardar perfil"), true);
        buttons.addView(save, new LinearLayout.LayoutParams(-2, d(48)));
        Button cancel = actionButton(t("Cancelar"), false);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(-2, d(48));
        cancelParams.leftMargin = d(10);
        buttons.addView(cancel, cancelParams);
        cancel.setOnClickListener(v -> backFromProfileEditor());
        if (existing != null) {
            Button delete = actionButton(t("Eliminar"), false);
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(-2, d(48));
            deleteParams.leftMargin = d(10);
            buttons.addView(delete, deleteParams);
            delete.setOnClickListener(v -> confirmDeleteProfile(existing));
        }
        save.setOnClickListener(v -> saveProfile(existing, name.getText().toString()));
    }

    void saveProfile(JSONObject existing, String rawName) {
        if (clientApiToken().isEmpty()) {
            Toast.makeText(this, t("Inicia sesión con una cuenta aprobada para crear un perfil."), Toast.LENGTH_LONG).show();
            showSettings();
            return;
        }
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            Toast.makeText(this, t("Escribe un nombre para el perfil."), Toast.LENGTH_SHORT).show();
            return;
        }
        boolean first = readProfiles().isEmpty();
        String id = existing == null ? UUID.randomUUID().toString() : existing.optString("id");
        JSONObject profile = new JSONObject();
        try {
            profile.put("id", id);
            profile.put("name", name);
            profile.put("avatar", selectedProfileAvatar);
        } catch (JSONException ignored) { }
        List<JSONObject> profiles = readProfiles();
        boolean replaced = false;
        for (int i = 0; i < profiles.size(); i++) {
            if (id.equals(profiles.get(i).optString("id"))) {
                profiles.set(i, profile);
                replaced = true;
                break;
            }
        }
        if (!replaced) profiles.add(profile);
        saveProfiles(profiles);
        prefs.edit().putBoolean(profileMetadataDirtyKey(id), true).apply();
        if (first) {
            migrateLegacyProfileData(id);
            prefs.edit().putString("active_profile", id).apply();
            if (collectProfileState(id).keys().hasNext()) {
                prefs.edit().putBoolean(profileStateDirtyKey(id), true).apply();
            }
        }
        uploadProfileToServer(profile);
        profileEditorVisible = false;
        if (first) {
            showingProfiles = false;
            loadHome();
        } else renderProfileManager();
    }

    void migrateLegacyProfileData(String profileId) {
        SharedPreferences.Editor editor = prefs.edit();
        Map<String, ?> all = prefs.getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            String target = null;
            for (String type : new String[]{"watch", "seen", "fav"}) {
                String prefix = type + ":";
                if (key.startsWith(prefix)) target = "p:" + profileId + ":" + key;
            }
            if (target != null) {
                Object value = entry.getValue();
                if (value instanceof String) editor.putString(target, (String) value);
                else if (value instanceof Boolean) editor.putBoolean(target, (Boolean) value);
                else if (value instanceof Integer) editor.putInt(target, (Integer) value);
                editor.remove(key);
            }
        }
        editor.apply();
    }

    void backFromProfileEditor() {
        profileEditorVisible = false;
        if (readProfiles().isEmpty()) {
            Toast.makeText(this, t("Crea un perfil para continuar."), Toast.LENGTH_SHORT).show();
            renderProfileManager();
        } else renderProfileManager();
    }

    void confirmDeleteProfile(JSONObject profile) {
        String id = profile.optString("id");
        new AlertDialog.Builder(this).setTitle(t("Eliminar perfil"))
                .setMessage(t("Se eliminará el perfil de este dispositivo."))
                .setNegativeButton(t("Cancelar"), null)
                .setPositiveButton(t("Eliminar"), (dialog, which) -> {
                    List<JSONObject> profiles = readProfiles();
                    List<JSONObject> kept = new ArrayList<>();
                    for (JSONObject value : profiles) if (!id.equals(value.optString("id"))) kept.add(value);
                    saveProfiles(kept);
                    boolean wasActive = id.equals(activeProfileId());
                    Set<String> deleted = deletedProfileIds();
                    deleted.add(id);
                    prefs.edit().putStringSet("profile_deleted_ids", deleted).apply();
                    cleanupProfileLocalData(id);
                    deleteProfileFromServer(id);
                    if (wasActive) {
                        if (kept.isEmpty()) prefs.edit().remove("active_profile").apply();
                        else prefs.edit().putString("active_profile", kept.get(0).optString("id")).apply();
                    }
                    profileEditorVisible = false;
                    renderProfileManager();
                }).show();
    }

    void fetchHomeFromServer() {
        io.execute(() -> {
            try {
                JSONArray m = getArray("/api/v1/catalog?library=movies");
                JSONArray s = getArray("/api/v1/catalog?library=series");
                JSONArray allItems = getArray("/api/v1/items?limit=500");
                String version = fetchCatalogVersionSafely();
                List<JSONObject> movieList = toList(m);
                List<JSONObject> seriesList = toList(s);
                sortNewestFirst(movieList);
                sortNewestFirst(seriesList);
                cachePosterByStream(allItems);
                runOnUiThread(() -> {
                    movies = movieList;
                    series = seriesList;
                    putCatalogList("movies", null, movieList);
                    putCatalogList("series", null, seriesList);
                    catalogVersion = version;
                    lastCatalogVersionCheckMs = System.currentTimeMillis();
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

    String catalogCacheKey(String library, String parentId) {
        return library + "|" + (parentId == null ? "root" : parentId);
    }

    List<JSONObject> getCachedCatalogList(String library, String parentId) {
        List<JSONObject> cached = catalogCache.get(catalogCacheKey(library, parentId));
        return cached == null ? null : new ArrayList<>(cached);
    }

    void putCatalogList(String library, String parentId, List<JSONObject> rows) {
        String key = catalogCacheKey(library, parentId);
        catalogCache.put(key, new ArrayList<>(rows));
        catalogCacheUpdatedAt.put(key, System.currentTimeMillis());
    }

    void clearCatalogCache() {
        catalogCache.clear();
        catalogCacheUpdatedAt.clear();
    }

    String fetchCatalogVersionSafely() {
        try { return getJson("/api/v1/catalog/version").optString("version", ""); }
        catch (Exception ignored) { return ""; }
    }

    String catalogListingPath(String library, String parentId) {
        String path = "/api/v1/catalog?library=" + enc(library);
        if (parentId != null) path += "&parent_id=" + enc(parentId);
        return path;
    }

    boolean isListingVisible(String library, String parentId) {
        if (!libraryMode || !library.equals(currentLibrary)) return false;
        if (parentId == null) return breadcrumbs.isEmpty();
        return !breadcrumbs.isEmpty() && parentId.equals(
                breadcrumbs.get(breadcrumbs.size() - 1).optString("id"));
    }

    void maybeRefreshCatalogListing(String library, String parentId) {
        long now = System.currentTimeMillis();
        if (catalogVersionCheckRunning || now - lastCatalogVersionCheckMs < CATALOG_CACHE_TTL_MS) return;
        catalogVersionCheckRunning = true;
        lastCatalogVersionCheckMs = now;
        String knownVersion = catalogVersion;
        io.execute(() -> {
            String remoteVersion = fetchCatalogVersionSafely();
            if (!remoteVersion.isEmpty() && remoteVersion.equals(knownVersion)) {
                runOnUiThread(() -> catalogVersionCheckRunning = false);
                return;
            }
            try {
                List<JSONObject> rows = toList(getArray(catalogListingPath(library, parentId)));
                if (parentId == null) sortNewestFirst(rows);
                runOnUiThread(() -> {
                    catalogVersionCheckRunning = false;
                    if (!remoteVersion.isEmpty() && !remoteVersion.equals(catalogVersion)) clearCatalogCache();
                    if (!remoteVersion.isEmpty()) catalogVersion = remoteVersion;
                    putCatalogList(library, parentId, rows);
                    if (parentId == null) {
                        if ("movies".equals(library)) movies = new ArrayList<>(rows);
                        else series = new ArrayList<>(rows);
                    }
                    if (isListingVisible(library, parentId)) {
                        int previousY = contentScroller == null ? 0 : contentScroller.getScrollY();
                        pendingListingRestoreY = previousY;
                        if (parentId == null) {
                            renderListing(library.equals("movies") ? "Películas" : "Series", rows);
                        } else {
                            JSONObject collection = breadcrumbs.get(breadcrumbs.size() - 1);
                            renderListing(displayTitle(collection), collectionSubtitle(rows), rows);
                        }
                    }
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> catalogVersionCheckRunning = false);
            }
        });
    }

    void maybeRefreshHomeCatalog() {
        long now = System.currentTimeMillis();
        if (catalogVersionCheckRunning || now - lastCatalogVersionCheckMs < CATALOG_CACHE_TTL_MS) return;
        catalogVersionCheckRunning = true;
        lastCatalogVersionCheckMs = now;
        String knownVersion = catalogVersion;
        io.execute(() -> {
            String remoteVersion = fetchCatalogVersionSafely();
            if (!remoteVersion.isEmpty() && remoteVersion.equals(knownVersion)) {
                runOnUiThread(() -> catalogVersionCheckRunning = false);
                return;
            }
            try {
                List<JSONObject> movieList = toList(getArray(catalogListingPath("movies", null)));
                List<JSONObject> seriesList = toList(getArray(catalogListingPath("series", null)));
                sortNewestFirst(movieList);
                sortNewestFirst(seriesList);
                JSONArray items = getArray("/api/v1/items?limit=500");
                runOnUiThread(() -> {
                    catalogVersionCheckRunning = false;
                    if (!remoteVersion.isEmpty() && !remoteVersion.equals(catalogVersion)) clearCatalogCache();
                    if (!remoteVersion.isEmpty()) catalogVersion = remoteVersion;
                    putCatalogList("movies", null, movieList);
                    putCatalogList("series", null, seriesList);
                    movies = movieList;
                    series = seriesList;
                    cachePosterByStream(items);
                    if (!libraryMode && !showingDetail && !showingAdmin && !metadataEditorMode) {
                        int previousY = contentScroller == null ? 0 : contentScroller.getScrollY();
                        pendingListingRestoreY = previousY;
                        renderHome();
                    }
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> catalogVersionCheckRunning = false);
            }
        });
    }

    String collectionSubtitle(List<JSONObject> items) {
        int movieCount = 0;
        int collectionCount = 0;
        for (JSONObject item : items) {
            if ("movie".equals(item.optString("kind"))) movieCount++;
            else if ("collection".equals(item.optString("kind"))) collectionCount++;
        }
        String subtitle = movieCount + (movieCount == 1 ? " película" : " películas");
        if (collectionCount > 0) subtitle += " · " + collectionCount +
                (collectionCount == 1 ? " colección" : " colecciones");
        return subtitle;
    }

    void renderHome() {
        refreshProfileChip();
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
        restoreListingScrollIfPending();
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
        if (node.optString("kind").equals("season")) return t("Temporada") + " " + String.format("%02d", node.optInt("season_number"));
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
        applyTvFocusStyle(card, true);
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
        TextView name = labelRaw(displayTitle(node), 13, Color.WHITE, true);
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
        applyTvFocusStyle(actions, false);
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
        View wrapped = wrap(card, cp);
        wrapped.setTag("catalog-node:" + node.optString("id", ""));
        return wrapped;
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
            return seasons + (seasons == 1 ? " " + t("temporada") : " " + t("temporadas"));
        }
        if (kind.equals("season")) return t("Temporada") + " " + String.format("%02d", node.optInt("season_number"));
        JSONArray genres = node.optJSONArray("genres");
        if (kind.equals("collection")) {
            int count = node.optInt("item_count");
            String countLabel = count + (count == 1 ? " " + t("título") : " " + t("títulos"));
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

    File imageDiskFile(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (byte value : digest) name.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            File directory = new File(getCacheDir(), "zinema-images");
            if (!directory.exists()) directory.mkdirs();
            return new File(directory, name + ".img");
        } catch (Exception ignored) { return null; }
    }

    Bitmap readDiskImage(String url) {
        File file = imageDiskFile(url);
        if (file == null || !file.isFile()) return null;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bitmap == null) file.delete();
        else file.setLastModified(System.currentTimeMillis());
        return bitmap;
    }

    byte[] downloadImage(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(true);
        if (url.startsWith(server + "/") && !clientApiToken().isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + clientApiToken());
        }
        try {
            InputStream input = connection.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
            input.close();
            return out.toByteArray();
        } finally { connection.disconnect(); }
    }

    void writeDiskImage(String url, byte[] bytes) {
        File file = imageDiskFile(url);
        if (file == null || bytes == null || bytes.length == 0) return;
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            FileOutputStream output = new FileOutputStream(temporary);
            output.write(bytes);
            output.close();
            if (!temporary.renameTo(file)) {
                FileOutputStream fallback = new FileOutputStream(file);
                fallback.write(bytes);
                fallback.close();
                temporary.delete();
            }
            file.setLastModified(System.currentTimeMillis());
            trimDiskImageCache(file.getParentFile());
        } catch (Exception ignored) { temporary.delete(); }
    }

    void trimDiskImageCache(File directory) {
        if (directory == null) return;
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".img"));
        if (files == null) return;
        long total = 0;
        for (File file : files) total += file.length();
        if (total <= IMAGE_DISK_CACHE_MAX_BYTES) return;
        java.util.Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File file : files) {
            if (total <= IMAGE_DISK_CACHE_MAX_BYTES) break;
            long size = file.length();
            if (file.delete()) total -= size;
        }
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
            Bitmap bitmap = null;
            try {
                bitmap = readDiskImage(url);
                if (bitmap == null) {
                    byte[] bytes = downloadImage(url);
                    bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bitmap != null) writeDiskImage(url, bytes);
                }
            } catch (Exception ignored) {
            }
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
        if (view instanceof HorizontalScrollView &&
                ("zinema-lazy-posters".equals(view.getTag()) || "zinema-lazy-cast".equals(view.getTag()))) {
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
        leaveStartupHomeWait();
        libraryMode = true;
        showingDetail = false;
        lastSearchQuery = null;
        selectedSection = library;
        currentLibrary = library;
        breadcrumbs.clear();
        breadcrumbScrollPositions.clear();
        buildShell(library.equals("movies") ? "Películas" : "Series");
        List<JSONObject> cached = getCachedCatalogList(library, null);
        if (cached != null) {
            renderListing(library.equals("movies") ? "Películas" : "Series", cached);
            maybeRefreshCatalogListing(library, null);
            return;
        }
        showLoading("Cargando catálogo…");
        io.execute(() -> {
            try {
                JSONArray rows = getArray("/api/v1/catalog?library=" + library);
                List<JSONObject> nodes = toList(rows);
                sortNewestFirst(nodes);
                runOnUiThread(() -> {
                    putCatalogList(library, null, nodes);
                    renderListing(library.equals("movies") ? "Películas" : "Series", nodes);
                });
            } catch (Exception e) { runOnUiThread(() -> renderError(e.getMessage())); }
        });
    }

    void renderListing(String title, List<JSONObject> nodes) {
        renderListing(title, "Explora tu biblioteca", nodes);
    }

    void renderListing(String title, String subtitle, List<JSONObject> nodes) {
        content.removeAllViews();
        boolean showGenreFilter = breadcrumbs.isEmpty() && libraryMode
                && ("movies".equals(selectedSection) || "series".equals(selectedSection));
        List<JSONObject> visibleNodes = nodes;
        Set<String> selected = null;
        if (showGenreFilter) {
            String library = selectedSection;
            selected = selectedGenresByLibrary.get(library);
            if (selected == null) {
                selected = new HashSet<>();
                selectedGenresByLibrary.put(library, selected);
            }
            visibleNodes = filterByGenres(nodes, selected);
            addLibraryListingHeader(title, subtitle, library, nodes, selected);
        } else {
            addSectionHeader(title, subtitle);
        }
        addGrid(visibleNodes);
        if (visibleNodes.isEmpty()) {
            addEmpty(showGenreFilter && selected != null && !selected.isEmpty()
                    ? "No hay títulos que coincidan con los géneros seleccionados."
                    : "No hay títulos en esta sección.");
        }
        restoreListingScrollIfPending();
    }

    void restoreListingScrollIfPending() {
        if (pendingListingRestoreY < 0 || contentScroller == null) return;
        int restoreY = pendingListingRestoreY;
        pendingListingRestoreY = -1;
        contentScroller.post(() -> contentScroller.scrollTo(0, restoreY));
    }

    void addLibraryListingHeader(String title, String subtitle, String library,
                                 List<JSONObject> allNodes, Set<String> selected) {
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(d(20), d(12), d(20), d(4));
        TextView heading = label(title, 27, Color.WHITE, true);
        headerRow.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        Button genreButton = actionButton(genreFilterButtonText(selected.size()), false);
        genreButton.setTextSize(12);
        genreButton.setMinHeight(d(42));
        headerRow.addView(genreButton, new LinearLayout.LayoutParams(-2, d(42)));
        genreButton.setOnClickListener(v -> showLibraryGenrePicker(
                library, title, subtitle, allNodes, genreButton));
        content.addView(headerRow);
        TextView sub = label(subtitle, 14, MUTED, false);
        sub.setPadding(d(20), 0, d(20), d(18));
        content.addView(sub);
    }

    String genreFilterButtonText(int selectedCount) {
        return selectedCount == 0 ? t("Todos los géneros")
                : t("Géneros") + " · " + selectedCount;
    }

    List<String> availableGenres(List<JSONObject> nodes) {
        Map<String, String> unique = new HashMap<>();
        for (JSONObject node : nodes) {
            JSONArray genres = node.optJSONArray("genres");
            if (genres == null) continue;
            for (int i = 0; i < genres.length(); i++) {
                String genre = genres.optString(i, "").trim();
                if (!genre.isEmpty() && !unique.containsKey(normalizeGenre(genre)))
                    unique.put(normalizeGenre(genre), genre);
            }
        }
        List<String> result = new ArrayList<>(unique.values());
        Collections.sort(result, String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    List<JSONObject> filterByGenres(List<JSONObject> nodes, Set<String> selected) {
        if (selected.isEmpty()) return nodes;
        List<JSONObject> filtered = new ArrayList<>();
        for (JSONObject node : nodes) {
            JSONArray genres = node.optJSONArray("genres");
            if (genres == null) continue;
            boolean matches = false;
            for (int i = 0; i < genres.length(); i++) {
                if (selected.contains(normalizeGenre(genres.optString(i, "")))) {
                    matches = true;
                    break;
                }
            }
            if (matches) filtered.add(node);
        }
        return filtered;
    }

    void showLibraryGenrePicker(String library, String title, String subtitle,
                                List<JSONObject> nodes, Button button) {
        List<String> options = availableGenres(nodes);
        Set<String> selected = selectedGenresByLibrary.get(library);
        if (selected == null) selected = new HashSet<>();
        boolean[] checked = new boolean[options.size()];
        String[] displayLabels = new String[options.size()];
        for (int i = 0; i < options.size(); i++) {
            checked[i] = selected.contains(normalizeGenre(options.get(i)));
            displayLabels[i] = genreLabel(options.get(i));
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(t("Filtrar por género"))
                .setMultiChoiceItems(displayLabels, checked, (d, which, isChecked) ->
                        checked[which] = isChecked)
                .setPositiveButton(t("Aplicar"), (d, which) -> {
                    Set<String> updated = new HashSet<>();
                    for (int i = 0; i < options.size(); i++) {
                        if (checked[i]) updated.add(normalizeGenre(options.get(i)));
                    }
                    selectedGenresByLibrary.put(library, updated);
                    button.setText(genreFilterButtonText(updated.size()));
                    renderListing(title, subtitle, nodes);
                })
                .setNegativeButton(t("Cancelar"), null)
                .setNeutralButton(t("Limpiar"), (d, which) -> {
                    selectedGenresByLibrary.put(library, new HashSet<>());
                    button.setText(genreFilterButtonText(0));
                    renderListing(title, subtitle, nodes);
                });
        dialog.show();
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
        int gridWidth = getResources().getDisplayMetrics().widthPixels - d(32);
        int cellWidth = Math.max(1, (gridWidth - d(columns * 8)) / columns);
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
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(cellWidth, -2);
            p.setMargins(d(4), 0, d(4), 0);
            row.addView(card, p);
            count++;
        }
    }

    void openNode(JSONObject node) {
        leaveStartupHomeWait();
        String kind = node.optString("kind");
        if (kind.equals("collection")) {
            showingDetail = false;
            if (backButton != null) backButton.setVisibility(View.VISIBLE);
            breadcrumbScrollPositions.add(contentScroller == null ? 0 : contentScroller.getScrollY());
            breadcrumbs.add(node);
            currentLibrary = node.optString("library", currentLibrary);
            showCollectionContents(node);
        } else {
            detailReturnScrollY = contentScroller == null ? 0 : contentScroller.getScrollY();
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
        String listingLibrary = currentLibrary;
        String collectionId = collection.optString("id");
        List<JSONObject> cached = getCachedCatalogList(listingLibrary, collectionId);
        if (cached != null) {
            renderListing(displayTitle(collection), collectionSubtitle(cached), cached);
            maybeRefreshCatalogListing(listingLibrary, collectionId);
            return;
        }
        showLoading("Abriendo " + displayTitle(collection) + "…");
        io.execute(() -> {
            try {
                JSONArray children = getArray("/api/v1/catalog?library=" + listingLibrary +
                        "&parent_id=" + enc(collectionId));
                List<JSONObject> items = toList(children);
                runOnUiThread(() -> {
                    putCatalogList(listingLibrary, collectionId, items);
                    renderListing(displayTitle(collection), collectionSubtitle(items), items);
                });
            } catch (Exception e) { runOnUiThread(() -> renderError(e.getMessage())); }
        });
    }

    JSONObject postJson(String path) throws Exception {
        String token = clientApiToken();
        if (token.isEmpty()) throw new Exception("Configura la clave de acceso a la API en Administración.");
        HttpURLConnection connection = (HttpURLConnection) new URL(server + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + token);
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
        String token = clientApiToken();
        if (token.isEmpty()) throw new Exception("Configura la clave de acceso a la API en Administración.");
        HttpURLConnection connection = (HttpURLConnection) new URL(server + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(45000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + token);
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
                refreshPosterCard(node);
            });
        }
        actions.add("Metadata manual");
        handlers.add(() -> showMetadataEditor(node, new JSONArray()));
        new AlertDialog.Builder(this)
                .setTitle(t("Acciones"))
                .setItems(localizedOptions(actions), (dialog, which) -> handlers.get(which).run())
                .show();
    }

    String[] localizedOptions(List<String> options) {
        String[] result = new String[options.size()];
        for (int i = 0; i < options.size(); i++) result[i] = t(options.get(i));
        return result;
    }

    String collectionMosaicKey(JSONObject node) {
        return "collection:mosaic:" + node.optString("id", "");
    }

    void refreshPosterCard(JSONObject node) {
        if (content == null) return;
        String tag = "catalog-node:" + node.optString("id", "");
        View existing = findTaggedView(content, tag);
        if (existing == null) return;
        ViewGroup parent = (ViewGroup) existing.getParent();
        int index = parent.indexOfChild(existing);
        View replacement = posterCard(node);
        replacement.setLayoutParams(existing.getLayoutParams());
        parent.removeViewAt(index);
        parent.addView(replacement, index);
        loadVisibleLazyRows();
    }

    View findTaggedView(View view, String tag) {
        if (tag.equals(view.getTag())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findTaggedView(group.getChildAt(i), tag);
                if (match != null) return match;
            }
        }
        return null;
    }

    String watchKey(String stream) { return progressPrefix("watch") + stream; }
    String favoriteKey(String nodeId) { return progressPrefix("fav") + nodeId; }
    String nodeSeenKey(String nodeId) { return progressPrefix("seen") + "node:" + nodeId; }
    String mediaSeenKey(JSONObject media) {
        String id = media.optString("id", "");
        if (id.isEmpty() || id.equals("null")) id = media.optString("stream_url", media.optString("stream", ""));
        return progressPrefix("seen") + "file:" + id;
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
        refreshPosterCard(node);
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
            } catch (Exception ignored) { }
        });
        Toast.makeText(this, seen ? "Marcado como visto" : "Marcado como no visto", Toast.LENGTH_SHORT).show();
    }

    void setNodeSeen(String id, boolean seen) {
        SharedPreferences.Editor editor = prefs.edit();
        if (seen) editor.putBoolean(nodeSeenKey(id), true);
        else editor.remove(nodeSeenKey(id));
        editor.apply();
        scheduleProfileStateSync();
    }

    void removeContinueForNode(String nodeId) {
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(progressPrefix("watch"))) continue;
            try {
                JSONObject item = new JSONObject(String.valueOf(entry.getValue()));
                if (nodeId.equals(item.optString("owner_node_id")) || nodeId.equals(item.optString("node_id"))) {
                    editor.remove(entry.getKey());
                }
            } catch (JSONException ignored) { }
        }
        editor.apply();
        scheduleProfileStateSync();
    }

    void removeContinueForFiles(JSONArray files) {
        if (files == null) return;
        SharedPreferences.Editor editor = prefs.edit();
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file != null) editor.remove(watchKey(file.optString("stream_url", "")));
        }
        editor.apply();
        scheduleProfileStateSync();
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
        new AlertDialog.Builder(this).setTitle(t("Acciones"))
                .setItems(localizedOptions(actions), (dialog, which) -> handlers.get(which).run())
                .show();
    }

    void setMediaWatched(JSONObject media, JSONObject owner, boolean seen) {
        String stream = media.optString("stream_url", media.optString("stream", ""));
        String key = mediaSeenKey(media);
        if (seen) prefs.edit().putBoolean(key, true).remove(watchKey(stream)).apply();
        else prefs.edit().remove(key).remove(watchKey(stream)).apply();
        scheduleProfileStateSync();
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
        refreshPosterCard(owner);
        Toast.makeText(this, seen ? "Marcado como visto" : "Marcado como no visto", Toast.LENGTH_SHORT).show();
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
            List<JSONObject> changedParents = new ArrayList<>();
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
                    changedParents.add(parent);
                    nextParent = parent.optString("parent_id", "");
                } catch (Exception e) { break; }
            }
            runOnUiThread(() -> {
                for (JSONObject parent : changedParents) refreshPosterCard(parent);
            });
        });
    }

    void showResumeActions(JSONObject item) {
        String[] actions = {"Marcar como visto", "Marcar como no visto", "Quitar de Seguir viendo"};
        new AlertDialog.Builder(this).setTitle(t("Acciones"))
                .setItems(localizedOptions(java.util.Arrays.asList(actions)), (dialog, which) -> {
                    if (which == 0) setResumeSeen(item, true);
                    else if (which == 1) setResumeSeen(item, false);
                    else {
                        prefs.edit().remove(watchKey(item.optString("path", item.optString("stream")))).apply();
                        scheduleProfileStateSync();
                    }
                    removeResumeCard(item);
                }).show();
    }

    void removeResumeCard(JSONObject item) {
        if (content == null) return;
        String key = "resume-item:" + item.optString("path", item.optString("stream", ""));
        View card = findTaggedView(content, key);
        if (card == null || !(card.getParent() instanceof ViewGroup)) return;
        ViewGroup cards = (ViewGroup) card.getParent();
        cards.removeView(card);
        if (cards.getChildCount() == 0 && cards.getParent() instanceof View) {
            View horizontal = (View) cards.getParent();
            if (horizontal.getParent() instanceof View) content.removeView((View) horizontal.getParent());
        }
    }

    void setResumeSeen(JSONObject item, boolean seen) {
        String nodeId = item.optString("owner_node_id", item.optString("node_id", ""));
        String kind = item.optString("owner_kind", "");
        JSONObject media = mediaFromProgress(item);
        String mediaKey = mediaSeenKey(media);
        SharedPreferences.Editor editor = prefs.edit().remove(watchKey(item.optString("path", item.optString("stream"))));
        if (seen) editor.putBoolean(mediaKey, true); else editor.remove(mediaKey);
        editor.apply();
        scheduleProfileStateSync();
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
        captureMetadataEditorReturnState();
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
        if (!metadataEditorReturnPrepared) captureMetadataEditorReturnState();
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
        TextView subtitle = labelRaw(displayTitle(node) + " · " + kindLabel(node.optString("kind")), 13, MUTED, false);
        subtitle.setPadding(0, d(4), 0, d(14));
        form.addView(subtitle);

        EditText title = metadataField(node.optString("display_title", node.optString("title", "")), "Título", false);
        EditText year = metadataField(node.isNull("year") ? "" : node.optString("year"), "Año", false);
        EditText overview = metadataField(node.optString("overview", ""), "Sinopsis", true);
        ArrayList<String> genreOptions = new ArrayList<>();
        ArrayList<String> selectedGenres = new ArrayList<>();
        addGenreValues(genreOptions, node.optJSONArray("genres"));
        addGenreValues(selectedGenres, node.optJSONArray("genres"));
        Button genres = actionButton(genrePickerText(selectedGenres.size()), false);
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
                selectedTmdbId[0], selectedImagePath[0], selectedImageType[0], save));

        io.execute(() -> {
            try {
                JSONArray importedGenres = getArray("/api/v1/genres");
                runOnUiThread(() -> {
                    addGenreValues(genreOptions, importedGenres);
                    addGenreValues(genreOptions, new JSONArray(selectedGenres));
                    genres.setText(genrePickerText(selectedGenres.size()));
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
            applyTvFocusStyle(card, true);
            ImageView poster = new ImageView(this);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            poster.setBackground(round(PANEL, d(7)));
            card.addView(poster, new LinearLayout.LayoutParams(d(102), d(142)));
            String path = candidate.optString("poster_path", "");
            if (!path.isEmpty()) loadImage("https://image.tmdb.org/t/p/w342" + path, poster, false);
            String candidateLabel = candidate.optString("title", "Título") +
                    (candidate.isNull("year") ? "" : " (" + candidate.optString("year") + ")");
            TextView candidateName = labelRaw(candidateLabel, 11, Color.WHITE, false);
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
        String[] displayLabels = new String[labels.length];
        boolean[] checked = new boolean[labels.length];
        for (int i = 0; i < labels.length; i++) {
            displayLabels[i] = genreLabel(labels[i]);
            for (String genre : selected) {
                if (genre.equalsIgnoreCase(labels[i])) { checked[i] = true; break; }
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(t("Selecciona los géneros"))
                .setMultiChoiceItems(displayLabels, checked, (dialog, which, isChecked) -> {
                    String genre = labels[which];
                    selected.removeIf(item -> item.equalsIgnoreCase(genre));
                    if (isChecked) selected.add(genre);
                    button.setText(genrePickerText(selected.size()));
                })
                .setPositiveButton(t("Listo"), (dialog, which) ->
                        button.setText(genrePickerText(selected.size())))
                .setNegativeButton(t("Cancelar"), null)
                .setNeutralButton(t("Añadir nuevo"), (dialog, which) ->
                        promptNewGenre(button, options, selected))
                .show();
    }

    String genrePickerText(int count) {
        return t("Seleccionar géneros") + " (" + count + ")";
    }

    void promptNewGenre(Button button, List<String> options, List<String> selected) {
        EditText input = metadataField("", "Nombre del género", false);
        new AlertDialog.Builder(this)
                .setTitle(t("Añadir género"))
                .setView(input)
                .setNegativeButton(t("Cancelar"), null)
                .setPositiveButton(t("Añadir"), (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) return;
                    JSONArray added = new JSONArray();
                    added.put(value);
                    addGenreValues(options, added);
                    addGenreValues(selected, added);
                    button.setText(genrePickerText(selected.size()));
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
                    genres.setText(genrePickerText(selectedGenres.size()));
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
                        applyTvFocusStyle(card, true);
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
                      EditText overview, long tmdbId, String imagePath, String imageType,
                      Button saveButton) {
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
        saveButton.setEnabled(false);
        saveButton.setText(t("Guardando metadata…"));
        io.execute(() -> {
            try {
                postJson("/api/v1/catalog/" + enc(nodeId) + "/metadata/manual", payload);
                imageCache.evictAll();
                runOnUiThread(() -> finishMetadataRetry("Metadata guardada y cliente actualizado."));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    saveButton.setEnabled(true);
                    saveButton.setText(t("Guardar metadata"));
                    Toast.makeText(this, "No se pudo guardar: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    void finishMetadataRetry(String summary) {
        Toast.makeText(this, summary, Toast.LENGTH_LONG).show();
        clearCatalogCache();
        catalogVersion = "";
        lastCatalogVersionCheckMs = 0L;
        returnFromMetadataEditor();
    }

    void captureMetadataEditorReturnState() {
        if (metadataEditorReturnPrepared) return;
        metadataEditorReturnScrollY = contentScroller == null ? 0 : contentScroller.getScrollY();
        metadataEditorReturnDetailId = showingDetail ? detailNodeId : "";
        if (showingDetail) metadataEditorReturnScreen = "detail";
        else if (!breadcrumbs.isEmpty()) metadataEditorReturnScreen = "collection";
        else if (lastSearchQuery != null) metadataEditorReturnScreen = "search";
        else if (libraryMode) metadataEditorReturnScreen = "library";
        else metadataEditorReturnScreen = "home";
        metadataEditorReturnPrepared = true;
    }

    void returnFromMetadataEditor() {
        setMetadataEditorChrome(false);
        showingDetail = false;
        String returnScreen = metadataEditorReturnScreen;
        String returnDetailId = metadataEditorReturnDetailId;
        int returnScrollY = metadataEditorReturnScrollY;
        metadataEditorReturnPrepared = false;
        metadataEditorReturnScreen = "";
        metadataEditorReturnDetailId = "";

        if ("detail".equals(returnScreen) && !returnDetailId.isEmpty()) {
            io.execute(() -> {
                try {
                    JSONObject node = getJson("/api/v1/catalog/" + enc(returnDetailId));
                    runOnUiThread(() -> {
                        renderDetail(node);
                        if (contentScroller != null) contentScroller.post(
                                () -> contentScroller.scrollTo(0, returnScrollY));
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> renderError("No se pudo volver a la ficha: " + e.getMessage()));
                }
            });
            return;
        }

        pendingListingRestoreY = returnScrollY;
        if ("collection".equals(returnScreen) && !breadcrumbs.isEmpty()) {
            showCollectionContents(breadcrumbs.get(breadcrumbs.size() - 1));
        } else if ("search".equals(returnScreen) && lastSearchQuery != null) {
            performSearch(lastSearchQuery);
        } else if ("library".equals(returnScreen)) {
            openLibrary(currentLibrary);
        } else {
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
        String detailBackdrop = node.optString("backdrop_url", "");
        if (detailBackdrop.isEmpty() || "null".equalsIgnoreCase(detailBackdrop))
            detailBackdrop = node.optString("poster_url", "");
        if ("null".equalsIgnoreCase(detailBackdrop)) detailBackdrop = "";
        loadImage(detailBackdrop, bgImage, true);
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{BG, 0xCC090A0D, 0x22090A0D}));
        banner.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.BOTTOM);
        info.setPadding(d(24), d(26), d(24), d(24));
        TextView kind = label(kindLabel(node.optString("kind")), 11, 0xFFFF646B, true);
        kind.setLetterSpacing(0.12f);
        info.addView(kind);
        if (isNodeSeen(node)) {
            TextView watched = label("✓  " + t("VISTO"), 11, 0xFF79E3B7, true);
            watched.setPadding(0, d(5), 0, 0);
            info.addView(watched);
        }
        info.addView(labelRaw(displayTitle(node), 30, Color.WHITE, true));
        String meta = node.optString("kind").equals("series")
                ? cardSubtitle(node)
                : node.optString("year", "") + (node.optJSONArray("genres") == null ? "" : "   •   " + join(node.optJSONArray("genres")));
        TextView metadata = label(meta, 13, 0xFFD7D9DF, false);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
        mp.topMargin = d(5);
        info.addView(metadata, mp);
        TextView overview = labelRaw(node.optString("overview", ""), 14, 0xFFE0E1E6, false);
        overview.setMaxLines(4);
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, -2);
        op.topMargin = d(10);
        info.addView(overview, op);
        JSONArray files = node.optJSONArray("files");
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        Button play = actionButton(node.optString("kind").equals("series") ? "Ver episodios" : "Reproducir", true);
        controls.addView(play, new LinearLayout.LayoutParams(-2, d(44)));
        if ("movie".equals(node.optString("kind")) && files != null && files.length() > 0) {
            Button details = actionButton("ⓘ  " + t("Details"), false);
            LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(-2, d(44));
            detailsParams.leftMargin = d(8);
            controls.addView(details, detailsParams);
            details.setOnClickListener(v -> showMovieFileDetails(node));
        }
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
        applyTvFocusStyle(actions, false);
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
        if (children != null && children.length() > 0) {
            addSectionHeader(node.optString("kind").equals("series") ? "Temporadas" : "Explorar", "Selecciona para continuar");
            addGrid(toList(children));
        }
        if (files != null && files.length() > 0) {
            if (!"movie".equals(node.optString("kind"))) addEpisodeList(files, node.optString("poster_url", ""), node);
        }
        if ((files == null || files.length() == 0) && (children == null || children.length() == 0)
                && !node.optString("kind").equals("movie")) addEmpty("No se encontraron episodios.");
    }

    String join(JSONArray values) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < values.length(); i++) {
            if (i > 0) result.append(" · ");
            result.append(genreLabel(values.optString(i)));
        }
        return result.toString();
    }

    String genreLabel(String genre) {
        if (prefs == null || !"eu".equals(prefs.getString("language", "es"))) return genre;
        switch (genre) {
            case "Acción": return "Ekintza";
            case "Aventura": return "Abentura";
            case "Animación": return "Animazioa";
            case "Comedia": return "Komedia";
            case "Crimen": return "Krimena";
            case "Documental": return "Dokumentala";
            case "Drama": return "Drama";
            case "Familia": return "Familia";
            case "Fantasía": return "Fantasia";
            case "Historia": return "Historia";
            case "Terror": return "Beldurrezkoa";
            case "Música": return "Musika";
            case "Misterio": return "Misterioa";
            case "Romance": return "Erromantzea";
            case "Ciencia ficción": return "Zientzia-fikzioa";
            case "Suspense": return "Suspense";
            case "Bélica": return "Gerra";
            case "Reality": return "Reality";
            case "Kids": return "Haurrak";
            case "Soap": return "Telenobela";
            case "Talk": return "Eztabaida";
            case "News": return "Albisteak";
            case "War & Politics": return "Gerra eta politika";
            case "Sci-Fi & Fantasy": return "Zientzia-fikzioa eta fantasia";
            case "Action & Adventure": return "Ekintza eta abentura";
            case "TV Movie": return "Telebistarako filma";
            default: return genre;
        }
    }

    String kindLabel(String kind) {
        if ("eu".equals(prefs.getString("language", "es"))) {
            if ("movie".equals(kind)) return "FILMA";
            if ("series".equals(kind)) return "TELESAILA";
            if ("season".equals(kind)) return "DENBORALDIA";
            if ("collection".equals(kind)) return "BILDUMA";
            if ("episode".equals(kind)) return "ATALA";
        }
        if ("movie".equals(kind)) return "PELÍCULA";
        if ("series".equals(kind)) return "SERIE";
        if ("season".equals(kind)) return "TEMPORADA";
        if ("collection".equals(kind)) return "COLECCIÓN";
        if ("episode".equals(kind)) return "CAPÍTULO";
        return kind == null ? "" : kind.toUpperCase(java.util.Locale.ROOT);
    }

    String t(String text) {
        if (text == null || prefs == null || !"eu".equals(prefs.getString("language", "es"))) return text;
        switch (text) {
            case "Inicio": return "Hasiera";
            case "Perfiles": return "Profilak";
            case "Perfil": return "Profila";
            case "Cambiar perfil": return "Profila aldatu";
            case "Salir": return "Irten";
            case "Cerrar sesión": return "Saioa itxi";
            case "¿Quién está viendo?": return "Nor ari da ikusten?";
            case "Cada perfil conserva su lista y su progreso de reproducción.":
                return "Profil bakoitzak bere zerrenda eta erreprodukzio-aurrerapena gordetzen ditu.";
            case "Perfil actual": return "Uneko profila";
            case "Vincular este dispositivo": return "Gailu hau lotu";
            case "Introduce la clave del NAS; se guardará una credencial exclusiva para este dispositivo.":
                return "Sartu NASeko gakoa; gailu honetarako kredentzial esklusibo bat gordeko da.";
            case "Introduce la clave de vinculación del NAS para este dispositivo.":
                return "Sartu NASeko lotura-gakoa gailu honetarako.";
            case "Vinculando este dispositivo…": return "Gailu hau lotzen…";
            case "Usar": return "Erabili";
            case "Editar perfil": return "Profila editatu";
            case "Añadir perfil": return "Gehitu profila";
            case "Crear perfil": return "Sortu profila";
            case "Nombre": return "Izena";
            case "Nombre del perfil": return "Profilaren izena";
            case "Elige un avatar": return "Aukeratu avatar bat";
            case "Avatar": return "Avatarra";
            case "Guardar perfil": return "Gorde profila";
            case "Eliminar": return "Ezabatu";
            case "Eliminar perfil": return "Profila ezabatu";
            case "Se eliminará el perfil de este dispositivo.":
                return "Profila gailu honetatik ezabatuko da.";
            case "Escribe un nombre para el perfil.": return "Idatzi profilaren izena.";
            case "Crea un perfil para continuar.": return "Sortu profil bat jarraitzeko.";
            case "Atrás": return "Atzera";
            case "Buscar": return "Bilatu";
            case "Servidor": return "Zerbitzaria";
            case "Películas": return "Filmak";
            case "Series": return "Telesailak";
            case "Seguir viendo": return "Ikusten jarraitu";
            case "Mi lista": return "Nire zerrenda";
            case "Colección": return "Bilduma";
            case "Temporada": return "Denboraldia";
            case "Temporadas": return "Denboraldiak";
            case "Explorar": return "Arakatu";
            case "Acciones": return "Ekintzak";
            case "Marcar como visto": return "Ikusitzat markatu";
            case "Marcar como no visto": return "Ikusi gabe markatu";
            case "Reintentar metadata (TMDb)": return "Metadatuak berriro bilatu (TMDb)";
            case "Usar póster manual": return "Eskuzko posterra erabili";
            case "Usar mosaico de pósters": return "Posterren mosaikoa erabili";
            case "Metadata manual": return "Eskuzko metadatuak";
            case "Quitar de Seguir viendo": return "Ikusten jarraitutik kendu";
            case "Título": return "Izenburua";
            case "Título…": return "Izenburua…";
            case "Año": return "Urtea";
            case "Sinopsis": return "Laburpena";
            case "Seleccionar géneros": return "Generoak hautatu";
            case "Buscar imágenes": return "Irudiak bilatu";
            case "Guardar metadata": return "Metadatuak gorde";
            case "Cancelar": return "Utzi";
            case "Listo": return "Eginda";
            case "Añadir": return "Gehitu";
            case "Añadir nuevo": return "Berria gehitu";
            case "Añadir género": return "Generoa gehitu";
            case "Nombre del género": return "Generoaren izena";
            case "Selecciona los géneros": return "Hautatu generoak";
            case "Reproducir": return "Erreproduzitu";
            case "Ver episodios": return "Atalak ikusi";
            case "Reparto": return "Antzezleak";
            case "Reparto principal": return "Antzezle nagusiak";
            case "Episodios": return "Atalak";
            case "Detalles del archivo": return "Fitxategiaren xehetasunak";
            case "Selecciona un archivo": return "Hautatu fitxategi bat";
            case "Cerrar": return "Itxi";
            case "Selecciona para continuar": return "Hautatu jarraitzeko";
            case "No se encontraron episodios.": return "Ez da atalik aurkitu.";
            case "temporada": return "denboraldi";
            case "temporadas": return "denboraldi";
            case "título": return "izenburu";
            case "títulos": return "izenburu";
            case "VISTO": return "IKUSITA";
            case "CONTINUAR": return "JARRAITU";
            case "Tu biblioteca está vacía. Sincroniza el catálogo en el servidor.":
                return "Zure liburutegia hutsik dago. Sinkronizatu katalogoa zerbitzarian.";
            case "Explora tu biblioteca": return "Arakatu zure liburutegia";
            case "No hay títulos en esta sección.": return "Atal honetan ez dago izenbururik.";
            case "Todos los géneros": return "Genero guztiak";
            case "Géneros": return "Generoak";
            case "Filtrar por género": return "Iragazi generoaren arabera";
            case "Aplicar": return "Aplikatu";
            case "Limpiar": return "Garbitu";
            case "No hay títulos que coincidan con los géneros seleccionados.":
                return "Ez dago hautatutako generoekin bat datorren izenbururik.";
            case "Cargando catálogo…": return "Katalogoa kargatzen…";
            case "Cargando ficha…": return "Fitxa kargatzen…";
            case "Conectando con tu biblioteca…": return "Zure liburutegira konektatzen…";
            case "Buscar coincidencias en TMDb…": return "TMDb-n bat-etortzeak bilatzen…";
            case "Coincidencias TMDb · selecciona para validar": return "TMDb-ko bat-etortzeak · hautatu egiaztatzeko";
            case "Imágenes online · selecciona una miniatura": return "Sareko irudiak · hautatu miniatura bat";
            case "No hay imágenes disponibles para esta búsqueda.": return "Ez dago irudirik bilaketa honetarako.";
            case "Horizontal": return "Horizontala";
            case "Póster": return "Posterra";
            case "Miniatura seleccionada.": return "Miniatura hautatuta.";
            case "El título no puede estar vacío.": return "Izenburua ezin da hutsik egon.";
            case "Revisa el año introducido.": return "Egiaztatu sartutako urtea.";
            case "Guardando metadata…": return "Metadatuak gordetzen…";
            case "Detalles": return "Xehetasunak";
            case "Details": return "Xehetasunak";
            case "Nombre:": return "Izena:";
            case "Extensión:": return "Luzapena:";
            case "Tamaño:": return "Tamaina:";
            case "Ruta:": return "Bidea:";
            case "Biblioteca:": return "Liburutegia:";
            case "Modificado:": return "Aldatuta:";
            case "Sin género disponible": return "Generorik ez dago";
            case "Encuentra películas, series y episodios": return "Bilatu filmak, telesailak eta atalak";
            case "Resultados": return "Emaitzak";
            case "Buscando…": return "Bilatzen…";
            case "No se pudo conectar": return "Ezin izan da konektatu";
            case "Comprueba que el servidor esté encendido.": return "Egiaztatu zerbitzaria piztuta dagoela.";
            case "Reintentar": return "Berriro saiatu";
            case "Configurar servidor": return "Zerbitzaria konfiguratu";
            case "Administración": return "Administrazioa";
            case "Iniciar sesión o crear cuenta": return "Hasi saioa edo sortu kontua";
            case "Iniciar sesión": return "Hasi saioa";
            case "Películas y series, para toda la familia": return "Filmak eta telesailak, familia osoarentzat";
            case "Entra para acceder a tus perfiles y a todo el catálogo.":
                return "Sartu zure profilak eta katalogo osoa ikusteko.";
            case "Las cuentas nuevas necesitan la aprobación de un administrador.":
                return "Kontu berriek administratzaile baten onespena behar dute.";
            case "Introduce tu usuario y contraseña.": return "Sartu erabiltzailea eta pasahitza.";
            case "El usuario debe tener al menos 3 caracteres y la contraseña 8.":
                return "Erabiltzaileak gutxienez 3 karaktere eta pasahitzak 8 izan behar dituzte.";
            case "Ajustes de Zinema": return "Zinemaren ezarpenak";
            case "Configuración del servidor": return "Zerbitzariaren ezarpenak";
            case "Nombre del servidor": return "Zerbitzariaren izena";
            case "Dirección del servidor": return "Zerbitzariaren helbidea";
            case "Cuenta Zinema": return "Zinema kontua";
            case "Usuario": return "Erabiltzailea";
            case "Email": return "Posta elektronikoa";
            case "Contraseña": return "Pasahitza";
            case "Inicia sesión para vincular esta instalación a tu cuenta. Cada dispositivo recibe su propio token.":
                return "Hasi saioa instalazio hau zure kontuarekin lotzeko. Gailu bakoitzak bere tokena jasoko du.";
            case "Crear cuenta": return "Kontua sortu";
            case "Solicitud enviada. Espera a que un administrador apruebe tu cuenta; después inicia sesión para recuperar tus perfiles y el catálogo.":
                return "Eskaera bidalita. Itxaron administratzaileak kontua onartu arte; ondoren hasi saioa profilak eta katalogoa berreskuratzeko.";
            case "Iniciando sesión…": return "Saioa hasten…";
            case "Creando cuenta…": return "Kontua sortzen…";
            case "Introduce usuario y contraseña para iniciar sesión.":
                return "Sartu erabiltzailea eta pasahitza saioa hasteko.";
            case "Indica un usuario (3 caracteres o más) y una contraseña de al menos 8 caracteres.":
                return "Sartu erabiltzaile bat (gutxienez 3 karaktere) eta gutxienez 8 karaktereko pasahitza.";
            case "Revisa la dirección y el nombre del servidor.":
                return "Egiaztatu zerbitzariaren helbidea eta izena.";
            case "Idioma de la aplicación": return "Aplikazioaren hizkuntza";
            case "Sincronización del catálogo": return "Katalogoa sinkronizatzea";
            case "Escanea las carpetas del NAS y vuelve a obtener metadata.":
                return "NASeko karpetak eskaneatu eta metadatuak berriro lortzen ditu.";
            case "Sincronizar ahora": return "Sinkronizatu orain";
            case "Sincronización nunca ejecutada": return "Sinkronizazioa ez da inoiz egin";
            case "Iniciando sincronización…": return "Sinkronizazioa hasten…";
            case "No se pudo iniciar la sincronización": return "Ezin izan da sinkronizazioa hasi";
            case "No se pudo consultar el estado": return "Ezin izan da egoera kontsultatu";
            case "Sincronización en cola…": return "Sinkronizazioa ilaran…";
            case "Escaneando carpetas…": return "Karpetak eskaneatzen…";
            case "Obteniendo metadata…": return "Metadatuak eskuratzen…";
            case "Sincronización en curso…": return "Sinkronizazioa egiten…";
            case "Sincronización completada": return "Sinkronizazioa osatu da";
            case "archivos revisados": return "fitxategi aztertu dira";
            case "Sincronización fallida": return "Sinkronizazioak huts egin du";
            case "El servidor no devolvió el ID de sincronización.":
                return "Zerbitzariak ez du sinkronizazio IDrik itzuli.";
            case "Probar conexión": return "Konexioa probatu";
            case "Guardar y conectar": return "Gorde eta konektatu";
            case "Dirección pública o local de tu servidor Zinema.": return "Zure Zinema zerbitzariaren helbide publikoa edo lokala.";
            case "Español": return "Español";
            case "Euskara": return "Euskara";
            case "Conectando…": return "Konektatzen…";
            case "Conexión correcta": return "Konexioa ondo dago";
            case "No se pudo conectar con esa dirección.": return "Ezin izan da helbide horretara konektatu.";
            case "Escribe una URL válida.": return "Idatzi baliozko URL bat.";
            case "El nombre del servidor no puede estar vacío.": return "Zerbitzariaren izena ezin da hutsik egon.";
            case "Marcado como visto": return "Ikusitzat markatuta";
            case "Marcado como no visto": return "Ikusi gabe markatuta";
            case "No hay un vídeo disponible.": return "Ez dago bideorik.";
            case "No se encontraron coincidencias. Puedes completar los campos manualmente.":
                return "Ez da bat-etortzerik aurkitu. Eremuak eskuz bete ditzakezu.";
            case "Buscando coincidencias en TMDb…": return "TMDb-n bat-etortzeak bilatzen…";
            case "No encontramos resultados para": return "Ez dugu emaitzarik aurkitu honentzat";
            case "No se encontraron coincidencias.": return "Ez da bat-etortzerik aurkitu.";
            case "No pudimos cargar la lista de géneros.": return "Ezin izan da generoen zerrenda kargatu.";
            case "Cargando coincidencia…": return "Bat-etortzea kargatzen…";
            case "Buscando imágenes…": return "Irudiak bilatzen…";
            case "No se pudo cargar la coincidencia": return "Ezin izan da bat-etortzea kargatu";
            case "No se pudieron buscar imágenes": return "Ezin izan dira irudiak bilatu";
            case "El elemento ya no existe en el catálogo. Resincroniza el catálogo e inténtalo de nuevo.":
                return "Elementua ez dago katalogoan. Sinkronizatu katalogoa berriro eta saiatu.";
            case "No se pudo guardar": return "Ezin izan da gorde";
            case "Metadata guardada y cliente actualizado.": return "Metadatuak gorde dira eta bezeroa eguneratu da.";
            case "Añadido a Mi lista": return "Nire zerrendara gehituta";
            case "Eliminado de Mi lista": return "Nire zerrendatik kenduta";
            case "Servidor actual": return "Uneko zerbitzaria";
            default: return text;
        }
    }

    void addCastSection(JSONArray cast) {
        if (cast == null || cast.length() == 0) return;
        addSectionHeader("Reparto", "Reparto principal");
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        horizontal.setTag("zinema-lazy-cast");
        LinearLayout cards = new LinearLayout(this);
        cards.setPadding(d(16), 0, d(16), d(16));
        for (int i = 0; i < cast.length(); i++) {
            JSONObject member = cast.optJSONObject(i);
            if (member == null) continue;
            FrameLayout card = new FrameLayout(this);
            card.setBackground(round(PANEL, d(10)));
            card.setClipToOutline(true);
            ImageView portrait = new ImageView(this);
            portrait.setScaleType(ImageView.ScaleType.CENTER_CROP);
            portrait.setImageResource(R.drawable.ic_actor_placeholder);
            card.addView(portrait, new FrameLayout.LayoutParams(-1, -1));
            String profilePath = member.optString("profile_path", "");
            if (!profilePath.isEmpty() && !profilePath.equals("null")) {
                portrait.setTag("https://image.tmdb.org/t/p/w342" + profilePath);
            }
            View shade = new View(this);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{0xEE090A0D, 0x66090A0D, 0x11090A0D}));
            card.addView(shade, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setGravity(Gravity.BOTTOM);
            text.setPadding(d(12), d(10), d(12), d(10));
            TextView name = labelRaw(member.optString("name"), 14, Color.WHITE, true);
            name.setMaxLines(2);
            text.addView(name);
            String character = member.optString("character", "").trim();
            if (!character.isEmpty()) {
                TextView role = labelRaw(character, 12, MUTED, false);
                role.setMaxLines(2);
                LinearLayout.LayoutParams roleParams = new LinearLayout.LayoutParams(-1, -2);
                roleParams.topMargin = d(5);
                text.addView(role, roleParams);
            }
            card.addView(text, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(d(152), d(228));
            cardParams.setMargins(0, 0, d(8), 0);
            cards.addView(card, cardParams);
        }
        horizontal.addView(cards);
        content.addView(horizontal, new LinearLayout.LayoutParams(-1, -2));
        installLazyRowScroll(horizontal);
    }

    void showMovieFileDetails(JSONObject node) {
        JSONArray files = node.optJSONArray("files");
        if (files == null || files.length() == 0) return;
        if (files.length() == 1) {
            showVideoFileDetails(files.optJSONObject(0), node.optString("library", ""));
            return;
        }
        String[] names = new String[files.length()];
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            names[i] = file == null ? "Vídeo " + (i + 1)
                    : file.optString("filename", file.optString("title", "Vídeo " + (i + 1)));
        }
        new AlertDialog.Builder(this).setTitle(t("Selecciona un archivo"))
                .setItems(names, (dialog, which) ->
                        showVideoFileDetails(files.optJSONObject(which), node.optString("library", "")))
                .show();
    }

    void showVideoFileDetails(JSONObject file, String library) {
        if (file == null) return;
        String filename = file.optString("filename", file.optString("title", "Vídeo"));
        String extension = file.optString("extension", "");
        long bytes = file.optLong("size_bytes", 0);
        String modified = file.optString("modified_at", "");
        StringBuilder message = new StringBuilder();
        message.append(t("Nombre:")).append(" ").append(filename)
                .append("\n").append(t("Título")).append(": ").append(file.optString("title", ""))
                .append("\n").append(t("Extensión:")).append(" ").append(extension.isEmpty() ? "—" : extension)
                .append("\n").append(t("Tamaño:")).append(" ").append(formatDetailedSize(bytes))
                .append("\n").append(t("Ruta:")).append(" ").append(file.optString("relative_path", "—"))
                .append("\n").append(t("Biblioteca:")).append(" ").append(library.isEmpty() ? "—" : library);
        if (!modified.isEmpty()) message.append("\n").append(t("Modificado:")).append(" ").append(modified);
        new AlertDialog.Builder(this).setTitle(t("Detalles del archivo"))
                .setMessage(message.toString()).setPositiveButton(t("Cerrar"), null).show();
    }

    String formatDetailedSize(long bytes) {
        if (bytes <= 0) return "—";
        if (bytes >= 1024L * 1024 * 1024)
            return String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024L * 1024)
            return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0);
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
            item.setFocusable(true);
            item.setClickable(true);
            applyTvFocusStyle(item, true);
            TextView play = label("▶", 20, Color.WHITE, true);
            play.setGravity(Gravity.CENTER);
            item.addView(play, new LinearLayout.LayoutParams(d(48), d(48)));
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = labelRaw(file.optString("title", "Vídeo"), 15, Color.WHITE, true);
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
            applyTvFocusStyle(actions, false);
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
        button.setText(t(title));
        button.setTextSize(13);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setPadding(d(15), 0, d(15), 0);
        button.setBackground(round(primary ? RED : 0xCC22252C, d(8)));
        applyTvFocusStyle(button, false);
        return button;
    }

    void showSearch() {
        leaveStartupHomeWait();
        showingDetail = false;
        libraryMode = false;
        breadcrumbs.clear();
        breadcrumbScrollPositions.clear();
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
                JSONArray results = getArray("/api/v1/search?q=" + enc(query.trim()) + "&limit=100");
                runOnUiThread(() -> {
                    content.removeAllViews();
                    addSectionHeader("Resultados", results.length() + " encontrados");
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject result = results.optJSONObject(i);
                        if (result != null) addSearchResult(result);
                    }
                    if (results.length() == 0) addEmpty("No encontramos resultados para “" + query + "”.");
                    restoreListingScrollIfPending();
                });
            } catch (Exception e) { runOnUiThread(() -> renderError(e.getMessage())); }
        });
    }

    void addSearchResult(JSONObject result) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(d(14), d(11), d(14), d(11));
        row.setBackground(round(PANEL, d(10)));
        row.setFocusable(true);
        row.setClickable(true);
        applyTvFocusStyle(row, true);
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(round(BG, d(6)));
        row.addView(poster, new LinearLayout.LayoutParams(d(62), d(88)));
        loadImage(result.optString("poster_url", ""), poster, false);
        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        textBox.setPadding(d(12), 0, d(8), 0);
        TextView type = label(kindLabel(result.optString("kind")), 10, 0xFFFF646B, true);
        type.setLetterSpacing(0.08f);
        textBox.addView(type);
        TextView title = labelRaw(result.optString("display_title", result.optString("title", "Vídeo")),
                15, Color.WHITE, true);
        title.setMaxLines(2);
        LinearLayout.LayoutParams titleP = new LinearLayout.LayoutParams(-1, -2);
        titleP.topMargin = d(3);
        textBox.addView(title, titleP);
        TextView detail = label(searchSubtitle(result), 12, MUTED, false);
        detail.setMaxLines(2);
        LinearLayout.LayoutParams detailP = new LinearLayout.LayoutParams(-1, -2);
        detailP.topMargin = d(4);
        textBox.addView(detail, detailP);
        if ("episode".equals(result.optString("kind")) && isMediaSeen(result)) {
            TextView watched = label("✓ VISTO", 10, 0xFF79E3B7, true);
            LinearLayout.LayoutParams watchedP = new LinearLayout.LayoutParams(-1, -2);
            watchedP.topMargin = d(3);
            textBox.addView(watched, watchedP);
        }
        row.addView(textBox, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = label("›", 27, MUTED, true);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(d(28), d(44)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(d(16), 0, d(16), d(8));
        content.addView(row, p);
        row.setOnClickListener(v -> openSearchResult(result));
    }

    void openSearchResult(JSONObject result) {
        if ("episode".equals(result.optString("kind"))) openSearchEpisodeDetail(result);
        else openNode(result);
    }

    void openSearchEpisodeDetail(JSONObject episode) {
        leaveStartupHomeWait();
        detailReturnScrollY = contentScroller == null ? 0 : contentScroller.getScrollY();
        showingDetail = true;
        detailNodeId = "";
        if (backButton != null) backButton.setVisibility(View.VISIBLE);
        content.removeAllViews();

        FrameLayout banner = new FrameLayout(this);
        ImageView background = new ImageView(this);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        banner.addView(background, new FrameLayout.LayoutParams(-1, -1));
        loadImage(episode.optString("poster_url", ""), background, true);
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{BG, 0xCC090A0D, 0x22090A0D}));
        banner.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.BOTTOM);
        info.setPadding(d(24), d(26), d(24), d(24));
        TextView type = label(kindLabel("episode"), 11, 0xFFFF646B, true);
        type.setLetterSpacing(0.12f);
        info.addView(type);
        TextView title = labelRaw(episode.optString("display_title", episode.optString("title", "Capítulo")),
                28, Color.WHITE, true);
        title.setMaxLines(2);
        LinearLayout.LayoutParams titleP = new LinearLayout.LayoutParams(-1, -2);
        titleP.topMargin = d(6);
        info.addView(title, titleP);
        String episodeMeta = episode.optString("series_title", "") + "  ·  "
                + String.format(java.util.Locale.ROOT, "T%02d E%02d",
                episode.optInt("season_number"), episode.optInt("episode_number"));
        TextView meta = label(episodeMeta, 13, 0xFFD7D9DF, false);
        LinearLayout.LayoutParams metaP = new LinearLayout.LayoutParams(-1, -2);
        metaP.topMargin = d(7);
        info.addView(meta, metaP);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        Button play = actionButton("Reproducir", true);
        controls.addView(play, new LinearLayout.LayoutParams(-2, d(44)));
        Button details = actionButton("ⓘ  Details", false);
        LinearLayout.LayoutParams detailsP = new LinearLayout.LayoutParams(-2, d(44));
        detailsP.leftMargin = d(8);
        controls.addView(details, detailsP);
        LinearLayout.LayoutParams controlsP = new LinearLayout.LayoutParams(-1, -2);
        controlsP.topMargin = d(14);
        info.addView(controls, controlsP);
        banner.addView(info, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams bannerP = new LinearLayout.LayoutParams(-1, d(390));
        bannerP.setMargins(d(16), d(10), d(16), d(10));
        content.addView(banner, bannerP);
        play.setOnClickListener(v -> launchPlayer(episode.optString("stream_url"),
                episode.optString("title", "Zinema"), episode.optString("poster_url", ""),
                episode.optString("node_id", ""), episode.optString("id", ""), "season"));
        details.setOnClickListener(v -> showVideoFileDetails(episode, episode.optString("library", "")));
    }

    String searchSubtitle(JSONObject result) {
        String kind = result.optString("kind");
        if ("collection".equals(kind)) {
            int count = result.optInt("item_count");
            return count + (count == 1 ? " elemento" : " elementos");
        }
        if ("series".equals(kind)) {
            int seasons = result.optInt("season_count");
            return seasons + (seasons == 1 ? " " + t("temporada") : " " + t("temporadas"));
        }
        if ("season".equals(kind)) {
            JSONArray files = result.optJSONArray("files");
            int episodes = files == null ? 0 : files.length();
            return t("Temporada") + " " + String.format(java.util.Locale.ROOT, "%02d", result.optInt("season_number"))
                    + "  ·  " + episodes + (episodes == 1 ? " capítulo" : " capítulos");
        }
        if ("episode".equals(kind)) {
            return result.optString("series_title", "") + "  ·  "
                    + String.format(java.util.Locale.ROOT, "T%02d E%02d",
                    result.optInt("season_number"), result.optInt("episode_number"));
        }
        String year = result.isNull("year") ? "" : result.optString("year", "");
        JSONArray genres = result.optJSONArray("genres");
        String genreText = genres == null || genres.length() == 0 ? "" : join(genres);
        if (!year.isEmpty() && !genreText.isEmpty()) return year + "  ·  " + genreText;
        if (!year.isEmpty()) return year;
        return genreText.isEmpty() ? "" : genreText;
    }

    void loadPendingAccountControls(LinearLayout slot) {
        if (clientApiToken().isEmpty()) return;
        io.execute(() -> {
            try {
                JSONObject account = getJson("/api/v1/auth/me");
                if (!account.optBoolean("is_admin")) return;
                JSONArray pending = getArray("/api/v1/admin/accounts/pending");
                runOnUiThread(() -> {
                    if (!showingAdmin || slot.getParent() == null) return;
                    slot.removeAllViews();
                    if (pending.length() == 0) {
                        slot.setVisibility(View.GONE);
                        return;
                    }
                    slot.setVisibility(View.VISIBLE);
                    LinearLayout card = settingsCard();
                    slot.addView(card);
                    card.addView(label("Cuentas pendientes de aprobación (" + pending.length() + ")",
                            16, Color.WHITE, true));
                    TextView hint = label("Estas cuentas todavía no pueden acceder a la biblioteca.",
                            12, MUTED, false);
                    LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
                    hintParams.topMargin = d(5);
                    card.addView(hint, hintParams);
                    for (int i = 0; i < pending.length(); i++) {
                        JSONObject request = pending.optJSONObject(i);
                        if (request == null) continue;
                        String accountId = request.optString("id", "");
                        String username = request.optString("username", request.optString("email", ""));
                        LinearLayout row = new LinearLayout(this);
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
                        rowParams.topMargin = d(10);
                        card.addView(row, rowParams);
                        TextView usernameLabel = label(username, 14, Color.WHITE, false);
                        usernameLabel.setSingleLine(true);
                        usernameLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
                        row.addView(usernameLabel, new LinearLayout.LayoutParams(0, d(46), 1));
                        LinearLayout actions = new LinearLayout(this);
                        actions.setGravity(Gravity.CENTER_VERTICAL);
                        row.addView(actions, new LinearLayout.LayoutParams(-2, -2));
                        Button approve = actionButton("Aprobar", true);
                        actions.addView(approve, new LinearLayout.LayoutParams(-2, d(44)));
                        approve.setOnClickListener(v -> new AlertDialog.Builder(this)
                                .setTitle("Aprobar cuenta")
                                .setMessage("¿Permitir que " + username + " acceda a Zinema y cree sus perfiles?")
                                .setNegativeButton("Cancelar", null)
                                .setPositiveButton("Aprobar", (dialog, which) -> {
                                    approve.setEnabled(false);
                                    io.execute(() -> {
                                        try {
                                            postJson("/api/v1/admin/accounts/" + enc(accountId) + "/approve");
                                            runOnUiThread(() -> {
                                                Toast.makeText(this, "Cuenta aprobada: " + username,
                                                        Toast.LENGTH_LONG).show();
                                                loadPendingAccountControls(slot);
                                            });
                                        } catch (Exception e) {
                                            runOnUiThread(() -> {
                                                approve.setEnabled(true);
                                                Toast.makeText(this, "No se pudo aprobar la cuenta: " + e.getMessage(),
                                                        Toast.LENGTH_LONG).show();
                                            });
                                        }
                                    });
                                }).show());
                        Button reject = actionButton("Rechazar", false);
                        LinearLayout.LayoutParams rejectParams = new LinearLayout.LayoutParams(-2, d(44));
                        rejectParams.leftMargin = d(7);
                        actions.addView(reject, rejectParams);
                        reject.setOnClickListener(v -> new AlertDialog.Builder(this)
                                .setTitle("Rechazar cuenta")
                                .setMessage("¿Rechazar y eliminar la cuenta de " + username + "?")
                                .setNegativeButton("Cancelar", null)
                                .setPositiveButton("Rechazar", (dialog, which) -> {
                                    reject.setEnabled(false);
                                    approve.setEnabled(false);
                                    io.execute(() -> {
                                        try {
                                            postJson("/api/v1/admin/accounts/" + enc(accountId) + "/reject");
                                            runOnUiThread(() -> {
                                                Toast.makeText(this, "Cuenta rechazada y eliminada: " + username,
                                                        Toast.LENGTH_LONG).show();
                                                loadPendingAccountControls(slot);
                                            });
                                        } catch (Exception e) {
                                            runOnUiThread(() -> {
                                                reject.setEnabled(true);
                                                approve.setEnabled(true);
                                                Toast.makeText(this, "No se pudo rechazar la cuenta: " + e.getMessage(),
                                                        Toast.LENGTH_LONG).show();
                                            });
                                        }
                                    });
                                }).show());
                    }
                });
            } catch (Exception ignored) {
                // Hide account approval controls when the device is not an administrator.
            }
        });
    }

    void loadAdminSyncControls(LinearLayout slot) {
        io.execute(() -> {
            try {
                JSONObject account = getJson("/api/v1/auth/me");
                if (!account.optBoolean("is_admin")) return;
                JSONObject latest = getJson("/api/v1/admin/sync");
                runOnUiThread(() -> {
                    if (!showingAdmin || slot.getParent() == null) return;
                    if (adminSyncPollTask != null) adminSyncHandler.removeCallbacks(adminSyncPollTask);
                    slot.removeAllViews();
                    slot.setVisibility(View.VISIBLE);
                    LinearLayout card = settingsCard();
                    slot.addView(card);
                    card.addView(label(t("Sincronización del catálogo"), 16, Color.WHITE, true));
                    TextView hint = label(t("Escanea las carpetas del NAS y vuelve a obtener metadata."),
                            12, MUTED, false);
                    LinearLayout.LayoutParams hintP = new LinearLayout.LayoutParams(-1, -2);
                    hintP.topMargin = d(6);
                    card.addView(hint, hintP);
                    ProgressBar progress = new ProgressBar(this);
                    progress.setIndeterminate(true);
                    LinearLayout.LayoutParams progressP = new LinearLayout.LayoutParams(-1, d(4));
                    progressP.topMargin = d(14);
                    card.addView(progress, progressP);
                    progress.setVisibility(View.GONE);
                    TextView status = label(t("Sincronización nunca ejecutada"), 12, MUTED, false);
                    LinearLayout.LayoutParams statusP = new LinearLayout.LayoutParams(-1, -2);
                    statusP.topMargin = d(10);
                    card.addView(status, statusP);
                    Button sync = actionButton("Sincronizar ahora", true);
                    LinearLayout.LayoutParams syncP = new LinearLayout.LayoutParams(-1, d(46));
                    syncP.topMargin = d(12);
                    card.addView(sync, syncP);

                    boolean active = applyAdminSyncState(latest, status, progress, sync, false);
                    if (active && !latest.optString("id", "").isEmpty())
                        pollAdminSync(latest.optString("id"), status, progress, sync, false);
                    sync.setOnClickListener(v -> startCatalogSyncFromAdmin(status, progress, sync));
                });
            } catch (Exception ignored) {
                // Hide admin-only controls if the account cannot be verified.
            }
        });
    }

    void startCatalogSyncFromAdmin(TextView status, ProgressBar progress, Button button) {
        status.setText(t("Iniciando sincronización…"));
        progress.setVisibility(View.VISIBLE);
        button.setEnabled(false);
        io.execute(() -> {
            try {
                JSONObject started;
                try {
                    started = postJson("/api/v1/admin/sync");
                } catch (Exception startError) {
                    if (startError.getMessage() == null || !startError.getMessage().contains("409"))
                        throw startError;
                    started = getJson("/api/v1/admin/sync");
                }
                String jobId = started.optString("id", "");
                if (jobId.isEmpty()) throw new Exception("El servidor no devolvió el ID de sincronización.");
                runOnUiThread(() -> pollAdminSync(jobId, status, progress, button, true));
            } catch (Exception e) {
                String message = e.getMessage() == null ? "Error desconocido" : e.getMessage();
                runOnUiThread(() -> {
                    if (!showingAdmin || status.getParent() == null) return;
                    progress.setVisibility(View.GONE);
                    button.setEnabled(true);
                    status.setText(t("No se pudo iniciar la sincronización") + ": " + message);
                    Toast.makeText(this, t("No se pudo iniciar la sincronización") + ": " + message,
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    void pollAdminSync(String jobId, TextView status, ProgressBar progress,
                       Button button, boolean notifyCompletion) {
        if (!showingAdmin || status.getParent() == null) return;
        io.execute(() -> {
            try {
                JSONObject job = getJson("/api/v1/admin/sync/" + enc(jobId));
                runOnUiThread(() -> {
                    if (!showingAdmin || status.getParent() == null) return;
                    boolean active = applyAdminSyncState(job, status, progress, button, notifyCompletion);
                    if (active) scheduleAdminSyncPoll(jobId, status, progress, button, notifyCompletion);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!showingAdmin || status.getParent() == null) return;
                    status.setText(t("No se pudo consultar el estado") + ": " + e.getMessage());
                    scheduleAdminSyncPoll(jobId, status, progress, button, notifyCompletion);
                });
            }
        });
    }

    void scheduleAdminSyncPoll(String jobId, TextView status, ProgressBar progress,
                               Button button, boolean notifyCompletion) {
        if (adminSyncPollTask != null) adminSyncHandler.removeCallbacks(adminSyncPollTask);
        adminSyncPollTask = () -> {
            adminSyncPollTask = null;
            pollAdminSync(jobId, status, progress, button, notifyCompletion);
        };
        adminSyncHandler.postDelayed(adminSyncPollTask, 2000L);
    }

    boolean applyAdminSyncState(JSONObject job, TextView status, ProgressBar progress,
                                Button button, boolean notifyCompletion) {
        String state = job.optString("status", "");
        String stage = job.optString("stage", "");
        boolean active = "queued".equals(state) || "running".equals(state);
        progress.setVisibility(active ? View.VISIBLE : View.GONE);
        button.setEnabled(!active);
        if ("queued".equals(state)) status.setText(t("Sincronización en cola…"));
        else if ("running".equals(state)) {
            if ("scan".equals(stage)) status.setText(t("Escaneando carpetas…"));
            else if (stage.startsWith("metadata:"))
                status.setText(t("Obteniendo metadata…") + " (" + stage.substring("metadata:".length()) + ")");
            else status.setText(t("Sincronización en curso…"));
        } else if ("completed".equals(state)) {
            JSONObject result = job.optJSONObject("result");
            JSONObject catalog = result == null ? null : result.optJSONObject("catalog");
            int scanned = catalog == null ? 0 : catalog.optInt("scanned");
            status.setText(scanned > 0
                    ? t("Sincronización completada") + " · " + scanned + " " + t("archivos revisados")
                    : t("Sincronización completada"));
            clearCatalogCache();
            catalogVersion = "";
            lastCatalogVersionCheckMs = 0L;
            if (notifyCompletion) Toast.makeText(this, t("Sincronización completada"), Toast.LENGTH_LONG).show();
        } else if ("failed".equals(state)) {
            String error = job.optString("error", "");
            status.setText(t("Sincronización fallida") + (error.isEmpty() ? "" : ": " + error));
            clearCatalogCache();
            catalogVersion = "";
            lastCatalogVersionCheckMs = 0L;
            if (notifyCompletion) Toast.makeText(this, t("Sincronización fallida"), Toast.LENGTH_LONG).show();
        } else if ("never_run".equals(state)) {
            status.setText(t("Sincronización nunca ejecutada"));
        } else {
            status.setText(t("Estado") + ": " + state);
        }
        return active;
    }

    void showSettings() {
        if (clientApiToken().isEmpty()) {
            showLoginScreen();
            return;
        }
        leaveStartupHomeWait();
        if (!showingAdmin) adminReturnDetailId = showingDetail ? detailNodeId : "";
        showingAdmin = true;
        showingDetail = false;
        buildShell("Administración");
        renderAdminWindow();
    }

    void showLoginScreen() {
        leaveStartupHomeWait();
        showingAdmin = false;
        showingProfiles = false;
        profileEditorVisible = false;
        FrameLayout root = new FrameLayout(this);
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF27090D, 0xFF111014, BG, 0xFF060709});
        root.setBackground(background);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setGravity(Gravity.CENTER_HORIZONTAL);
        screen.setPadding(d(24), d(34), d(24), d(34));
        scroll.addView(screen);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        View topSpacer = new View(this);
        screen.addView(topSpacer, new LinearLayout.LayoutParams(1, 0, 1));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.zinema_wordmark);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setContentDescription("Zinema");
        screen.addView(logo, new LinearLayout.LayoutParams(d(220), d(74)));
        TextView tagline = label(t("Películas y series, para toda la familia"), 15, 0xFFD7D7DB, false);
        LinearLayout.LayoutParams taglineParams = new LinearLayout.LayoutParams(-2, -2);
        taglineParams.topMargin = d(8);
        taglineParams.bottomMargin = d(28);
        screen.addView(tagline, taglineParams);

        int width = Math.max(d(250), Math.min(d(430),
                getResources().getDisplayMetrics().widthPixels - d(48)));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(d(26), d(26), d(26), d(24));
        card.setBackground(round(0xF01A1B20, d(18)));
        card.setElevation(d(12));
        screen.addView(card, new LinearLayout.LayoutParams(width, -2));

        TextView heading = label(t("Iniciar sesión"), 27, Color.WHITE, true);
        card.addView(heading);
        TextView intro = label(t("Entra para acceder a tus perfiles y a todo el catálogo."),
                14, MUTED, false);
        LinearLayout.LayoutParams introParams = new LinearLayout.LayoutParams(-1, -2);
        introParams.topMargin = d(6);
        introParams.bottomMargin = d(22);
        card.addView(intro, introParams);

        EditText usernameInput = settingsField("", t("Usuario"), false);
        usernameInput.setSingleLine(true);
        usernameInput.setTextSize(16);
        usernameInput.setPadding(d(15), 0, d(15), 0);
        usernameInput.setBackground(round(0xFF303139, d(9)));
        usernameInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        card.addView(usernameInput, new LinearLayout.LayoutParams(-1, d(52)));

        EditText passwordInput = settingsField("", t("Contraseña"), false);
        passwordInput.setSingleLine(true);
        passwordInput.setTextSize(16);
        passwordInput.setPadding(d(15), 0, d(15), 0);
        passwordInput.setBackground(round(0xFF303139, d(9)));
        passwordInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
        LinearLayout.LayoutParams passwordParams = new LinearLayout.LayoutParams(-1, d(52));
        passwordParams.topMargin = d(12);
        card.addView(passwordInput, passwordParams);

        TextView accountStatus = label("", 13, 0xFFFFB1B5, false);
        accountStatus.setVisibility(View.GONE);
        LinearLayout.LayoutParams accountStatusParams = new LinearLayout.LayoutParams(-1, -2);
        accountStatusParams.topMargin = d(14);
        card.addView(accountStatus, accountStatusParams);

        Button login = actionButton(t("Iniciar sesión"), true);
        LinearLayout.LayoutParams loginParams = new LinearLayout.LayoutParams(-1, d(50));
        loginParams.topMargin = d(20);
        card.addView(login, loginParams);
        Button register = actionButton(t("Crear cuenta"), false);
        LinearLayout.LayoutParams registerParams = new LinearLayout.LayoutParams(-1, d(48));
        registerParams.topMargin = d(10);
        card.addView(register, registerParams);
        TextView approvalHint = label(t("Las cuentas nuevas necesitan la aprobación de un administrador."),
                12, MUTED, false);
        approvalHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams approvalParams = new LinearLayout.LayoutParams(-1, -2);
        approvalParams.topMargin = d(16);
        card.addView(approvalHint, approvalParams);

        TextView brand = label("ZINEMA", 12, 0xFF85858D, true);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(-2, -2);
        brandParams.topMargin = d(24);
        screen.addView(brand, brandParams);
        View bottomSpacer = new View(this);
        screen.addView(bottomSpacer, new LinearLayout.LayoutParams(1, 0, 1));

        login.setOnClickListener(v -> {
            String username = usernameInput.getText().toString().trim();
            String password = passwordInput.getText().toString();
            if (username.length() < 3 || password.isEmpty()) {
                accountStatus.setText(t("Introduce tu usuario y contraseña."));
                accountStatus.setVisibility(View.VISIBLE);
                return;
            }
            authenticateAccount(username, password, false, "", passwordInput, accountStatus);
        });
        register.setOnClickListener(v -> {
            String username = usernameInput.getText().toString().trim();
            String password = passwordInput.getText().toString();
            if (username.length() < 3 || password.length() < 8) {
                accountStatus.setText(t("El usuario debe tener al menos 3 caracteres y la contraseña 8."));
                accountStatus.setVisibility(View.VISIBLE);
                return;
            }
            authenticateAccount(username, password, true, "", passwordInput, accountStatus);
        });
        setContentView(root);
        login.requestFocus();
    }

    EditText settingsField(String value, String hint, boolean uri) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(MUTED);
        input.setHint(t(hint));
        if (uri) input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(value);
        input.setSelectAllOnFocus(true);
        input.setPadding(d(14), 0, d(14), 0);
        input.setBackground(round(0xFF17191F, d(9)));
        return input;
    }

    void renderAdminWindow() {
        content.removeAllViews();
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(d(20), d(14), d(20), d(24));
        TextView heading = label("Administración", 27, Color.WHITE, true);
        screen.addView(heading);
        TextView intro = label(t("Ajustes de Zinema"), 14, MUTED, false);
        LinearLayout.LayoutParams introP = new LinearLayout.LayoutParams(-1, -2);
        introP.topMargin = d(5);
        introP.bottomMargin = d(20);
        screen.addView(intro, introP);

        LinearLayout playerCard = settingsCard();
        LinearLayout.LayoutParams playerCardParams = new LinearLayout.LayoutParams(-1, -2);
        playerCardParams.bottomMargin = d(14);
        screen.addView(playerCard, playerCardParams);
        playerCard.addView(label("Reproductor", 14, Color.WHITE, true));
        TextView playerHint = label("VLC devuelve posición y duración al terminar. El reproductor de Zinema incluye controles y guarda el progreso mientras ves el vídeo.",
                12, MUTED, false);
        LinearLayout.LayoutParams playerHintParams = new LinearLayout.LayoutParams(-1, -2);
        playerHintParams.topMargin = d(5);
        playerCard.addView(playerHint, playerHintParams);
        RadioGroup playerGroup = new RadioGroup(this);
        playerGroup.setOrientation(RadioGroup.VERTICAL);
        LinearLayout.LayoutParams playerGroupParams = new LinearLayout.LayoutParams(-1, -2);
        playerGroupParams.topMargin = d(8);
        playerCard.addView(playerGroup, playerGroupParams);
        RadioButton zinemaPlayer = settingsRadio("Reproductor de Zinema", "builtin".equals(prefs.getString("player", "vlc")));
        zinemaPlayer.setId(View.generateViewId());
        playerGroup.addView(zinemaPlayer);
        RadioButton vlcPlayer = settingsRadio("VLC", "vlc".equals(prefs.getString("player", "vlc")));
        vlcPlayer.setId(View.generateViewId());
        playerGroup.addView(vlcPlayer);
        playerGroup.setOnCheckedChangeListener((group, checkedId) -> {
            String choice = checkedId == vlcPlayer.getId() ? "vlc" : "builtin";
            prefs.edit().putString("player", choice).apply();
            Toast.makeText(this, choice.equals("vlc")
                    ? "Se usará VLC. Si no está instalado, se abrirá el reproductor de Zinema."
                    : "Se usará el reproductor de Zinema.", Toast.LENGTH_SHORT).show();
        });

        LinearLayout syncSlot = new LinearLayout(this);
        syncSlot.setOrientation(LinearLayout.VERTICAL);
        syncSlot.setVisibility(View.GONE);
        LinearLayout.LayoutParams syncSlotParams = new LinearLayout.LayoutParams(-1, -2);
        syncSlotParams.topMargin = d(14);
        screen.addView(syncSlot, syncSlotParams);

        LinearLayout languageCard = settingsCard();
        LinearLayout.LayoutParams languageP = new LinearLayout.LayoutParams(-1, -2);
        languageP.topMargin = d(14);
        screen.addView(languageCard, languageP);
        languageCard.addView(label("Idioma de la aplicación", 14, Color.WHITE, true));
        LinearLayout languageButtons = new LinearLayout(this);
        LinearLayout.LayoutParams langButtonsP = new LinearLayout.LayoutParams(-1, -2);
        langButtonsP.topMargin = d(12);
        languageCard.addView(languageButtons, langButtonsP);
        String activeLanguage = prefs.getString("language", "es");
        Button spanish = actionButton("Español", "es".equals(activeLanguage));
        languageButtons.addView(spanish, new LinearLayout.LayoutParams(0, d(46), 1));
        Button basque = actionButton("Euskara", "eu".equals(activeLanguage));
        LinearLayout.LayoutParams basqueP = new LinearLayout.LayoutParams(0, d(46), 1);
        basqueP.leftMargin = d(9);
        languageButtons.addView(basque, basqueP);
        spanish.setOnClickListener(v -> changeLanguage("es"));
        basque.setOnClickListener(v -> changeLanguage("eu"));

        TextView attribution = label("This product uses the TMDB API but is not endorsed or certified by TMDB.",
                11, MUTED, false);
        attribution.setPadding(d(2), d(16), d(2), 0);
        screen.addView(attribution);
        content.addView(screen, new LinearLayout.LayoutParams(-1, -2));
        loadAdminSyncControls(syncSlot);
    }

    void showAccountManagement() {
        if (clientApiToken().isEmpty()) {
            showLoginScreen();
            return;
        }
        leaveStartupHomeWait();
        showingAdmin = true;
        showingDetail = false;
        buildShell("Gestión de cuentas");
        renderAccountManagement();
    }

    void renderAccountManagement() {
        content.removeAllViews();
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(d(20), d(14), d(20), d(24));
        screen.addView(label("Gestión de cuentas", 27, Color.WHITE, true));
        TextView description = label("Permite, deniega o elimina el acceso a Zinema.", 14, MUTED, false);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
        descriptionParams.topMargin = d(5);
        descriptionParams.bottomMargin = d(16);
        screen.addView(description, descriptionParams);
        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        screen.addView(progress, new LinearLayout.LayoutParams(-2, d(44)));
        content.addView(screen, new LinearLayout.LayoutParams(-1, -2));
        io.execute(() -> {
            try {
                JSONObject current = getJson("/api/v1/auth/me");
                if (!current.optBoolean("is_admin")) throw new Exception("Solo los administradores pueden gestionar cuentas.");
                String currentAccountId = current.optString("id", "");
                JSONArray accounts = getArray("/api/v1/admin/accounts");
                runOnUiThread(() -> {
                    if (!showingAdmin || screen.getParent() == null) return;
                    screen.removeView(progress);
                    if (accounts.length() == 0) {
                        screen.addView(label("Todavía no hay cuentas registradas.", 14, MUTED, false));
                        return;
                    }
                    for (int i = 0; i < accounts.length(); i++) {
                        JSONObject account = accounts.optJSONObject(i);
                        if (account == null) continue;
                        String accountId = account.optString("id", "");
                        String username = account.optString("username", "Cuenta");
                        boolean isCurrentAccount = accountId.equals(currentAccountId);
                        boolean approved = account.optBoolean("approved");
                        boolean disabled = account.optBoolean("disabled");
                        boolean isAdmin = account.optBoolean("is_admin");
                        boolean approvalSatisfied = approved || isAdmin;
                        String state = isCurrentAccount ? "Sesión actual"
                                : (disabled ? "Acceso denegado"
                                : (!approvalSatisfied ? "Pendiente de aprobación" : "Acceso permitido"));
                        LinearLayout card = settingsCard();
                        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
                        cardParams.bottomMargin = d(10);
                        screen.addView(card, cardParams);
                        TextView userLabel = label(username + (isAdmin ? "  ·  Admin" : ""), 16, Color.WHITE, true);
                        card.addView(userLabel);
                        TextView stateLabel = label(state, 12,
                                !approvalSatisfied || disabled ? 0xFFFFB1B5 : 0xFF79E3B7, false);
                        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(-1, -2);
                        stateParams.topMargin = d(4);
                        card.addView(stateLabel, stateParams);

                        LinearLayout actions = new LinearLayout(this);
                        actions.setGravity(Gravity.CENTER_VERTICAL);
                        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
                        actionsParams.topMargin = d(10);
                        card.addView(actions, actionsParams);
                        if (isCurrentAccount) continue;
                        if (!approvalSatisfied && !disabled) {
                            Button allow = actionButton("Permitir", true);
                            actions.addView(allow, new LinearLayout.LayoutParams(0, d(44), 1));
                            allow.setOnClickListener(v -> confirmAccountChange(username, "Permitir acceso",
                                    "¿Permitir que " + username + " acceda al catálogo?", () -> {
                                        try { postJson("/api/v1/admin/accounts/" + enc(accountId) + "/approve"); }
                                        catch (Exception e) { throw new RuntimeException(e); }
                                    }));
                            Button deny = actionButton("Denegar", false);
                            LinearLayout.LayoutParams denyParams = new LinearLayout.LayoutParams(0, d(44), 1);
                            denyParams.leftMargin = d(6);
                            actions.addView(deny, denyParams);
                            deny.setOnClickListener(v -> confirmAccountChange(username, "Denegar acceso",
                                    "¿Denegar temporalmente a " + username + "? Podrás permitirlo después.", () -> {
                                        try { adminAccountRequest("POST", "/api/v1/admin/accounts/" + enc(accountId) + "/disable"); }
                                        catch (Exception e) { throw new RuntimeException(e); }
                                    }));
                        } else if (disabled) {
                            Button allow = actionButton("Permitir", true);
                            actions.addView(allow, new LinearLayout.LayoutParams(0, d(44), 1));
                            allow.setOnClickListener(v -> confirmAccountChange(username, "Permitir acceso",
                                    "¿Permitir que " + username + " acceda al catálogo?", () -> {
                                        try { postJson("/api/v1/admin/accounts/" + enc(accountId) + "/approve"); }
                                        catch (Exception e) { throw new RuntimeException(e); }
                                    }));
                        } else {
                            Button deny = actionButton("Denegar", false);
                            actions.addView(deny, new LinearLayout.LayoutParams(0, d(44), 1));
                            deny.setOnClickListener(v -> confirmAccountChange(username, "Denegar acceso",
                                    "¿Bloquear a " + username + "? Podrás volver a permitirlo después.", () -> {
                                        try { adminAccountRequest("POST", "/api/v1/admin/accounts/" + enc(accountId) + "/disable"); }
                                        catch (Exception e) { throw new RuntimeException(e); }
                                    }));
                        }
                        Button delete = actionButton("Borrar", false);
                        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, d(44), 1);
                        deleteParams.leftMargin = d(8);
                        actions.addView(delete, deleteParams);
                        delete.setOnClickListener(v -> confirmAccountChange(username, "Borrar cuenta",
                                "¿Borrar definitivamente la cuenta " + username + " y sus perfiles?", () -> {
                                    try { adminAccountRequest("DELETE", "/api/v1/admin/accounts/" + enc(accountId)); }
                                    catch (Exception e) { throw new RuntimeException(e); }
                                }));
                    }
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? "No se pudieron cargar las cuentas." : e.getMessage();
                runOnUiThread(() -> {
                    if (!showingAdmin || screen.getParent() == null) return;
                    screen.removeView(progress);
                    screen.addView(label(message, 14, 0xFFFFB1B5, false));
                    Button retry = actionButton("Reintentar", true);
                    LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(-2, d(44));
                    retryParams.topMargin = d(12);
                    screen.addView(retry, retryParams);
                    retry.setOnClickListener(v -> renderAccountManagement());
                });
            }
        });
    }

    interface AccountChange { void run() throws Exception; }

    void confirmAccountChange(String username, String title, String message, AccountChange change) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Confirmar", (dialog, which) -> io.execute(() -> {
                    try {
                        change.run();
                        runOnUiThread(() -> {
                            Toast.makeText(this, "Cuenta actualizada: " + username, Toast.LENGTH_SHORT).show();
                            renderAccountManagement();
                        });
                    } catch (Exception e) {
                        Throwable cause = e instanceof RuntimeException && e.getCause() != null ? e.getCause() : e;
                        String messageText = cause.getMessage() == null ? "No se pudo actualizar la cuenta." : cause.getMessage();
                        runOnUiThread(() -> Toast.makeText(this, messageText, Toast.LENGTH_LONG).show());
                    }
                })).show();
    }

    JSONObject adminAccountRequest(String method, String path) throws Exception {
        String token = clientApiToken();
        if (token.isEmpty()) throw new Exception("Inicia sesión con una cuenta administradora.");
        HttpURLConnection connection = (HttpURLConnection) new URL(server + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        if ("POST".equals(method)) {
            connection.setDoOutput(true);
            connection.getOutputStream().close();
        }
        int status = connection.getResponseCode();
        InputStream input = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String body = readText(input);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            String detail = body;
            try { detail = new JSONObject(body).optString("detail", body); }
            catch (JSONException ignored) { }
            throw new Exception(detail.isEmpty() ? "HTTP " + status : detail);
        }
        return body.isEmpty() ? new JSONObject() : new JSONObject(body);
    }

    void authenticateAccount(String username, String password, boolean register,
                             String currentDeviceId, EditText passwordInput,
                             TextView accountStatus) {
        Toast.makeText(this, register ? t("Creando cuenta…") : t("Iniciando sesión…"), Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            HttpURLConnection connection = null;
            try {
                JSONObject payload = new JSONObject();
                String deviceName = (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).trim();
                payload.put("device_name", deviceName.isEmpty() ? "Android" : deviceName);
                payload.put("username", username);
                payload.put("email", username);
                payload.put("password", password);
                if (currentDeviceId != null && !currentDeviceId.isEmpty()) {
                    payload.put("device_id", currentDeviceId);
                }
                String authPath = register ? "/api/v1/auth/register" : "/api/v1/auth/login";
                connection = (HttpURLConnection) new URL(server + authPath).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(20000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.getOutputStream().write(payload.toString().getBytes(StandardCharsets.UTF_8));
                int status = connection.getResponseCode();
                InputStream input = status < 400 ? connection.getInputStream() : connection.getErrorStream();
                String body = readText(input);
                if (status < 200 || status >= 300) {
                    String detail = body;
                    try { detail = new JSONObject(body).optString("detail", body); }
                    catch (JSONException ignored) { }
                    throw new Exception("HTTP " + status + (detail.isEmpty() ? "" : ": " + detail));
                }
                JSONObject result = new JSONObject(body);
                if (register && "pending_approval".equals(result.optString("status"))) {
                    runOnUiThread(() -> {
                        passwordInput.setText("");
                        accountStatus.setText(t("Solicitud enviada. Espera a que un administrador apruebe tu cuenta; después inicia sesión para recuperar tus perfiles y el catálogo."));
                        accountStatus.setVisibility(View.VISIBLE);
                    });
                    return;
                }
                String deviceToken = result.optString("token", "");
                String deviceId = result.optString("device_id", "");
                if (deviceToken.isEmpty() || deviceId.isEmpty()) {
                    throw new Exception("El servidor no devolvió las credenciales del dispositivo.");
                }
                JSONObject verifiedAccount = getAccountInfoForToken(deviceToken);
                if (!verifiedAccount.optBoolean("is_admin")
                        && (!verifiedAccount.has("approved") || !verifiedAccount.optBoolean("approved"))) {
                    throw new Exception("El servidor no confirma la aprobación de esta cuenta. Actualiza el servidor antes de continuar.");
                }
                String previousUsername = prefs.getString("account_email", "");
                if (!previousUsername.equalsIgnoreCase(username)) {
                    clearLocalProfilesForAccountSwitch();
                }
                storeClientApiToken(deviceToken);
                prefs.edit().putString("client_device_id", deviceId)
                        .putString("account_email", username).remove("profile_sync_token").apply();
                runOnUiThread(() -> {
                    passwordInput.setText("");
                    accountStatus.setVisibility(View.GONE);
                    showingAdmin = false;
                    lastProfileSyncMs = 0L;
                    synchronized (MainActivity.this) {
                        startupPrefetchPending = true;
                        startupPrefetchReady = false;
                        startupPrefetchStarted = false;
                        startupHomeAwaiting = false;
                    }
                    startupPrefetchError = null;
                    startStartupPrefetch();
                    loadHome();
                });
            } catch (Exception e) {
                String message = e.getMessage();
                runOnUiThread(() -> {
                    String detail = message == null ? "No se pudo completar la solicitud." : message;
                    detail = detail.replaceFirst("^HTTP \\d+: ", "");
                    if (detail.startsWith("Invalid email or password")) {
                        detail = "Usuario o contraseña incorrectos.";
                    }
                    accountStatus.setText(detail);
                    accountStatus.setVisibility(View.VISIBLE);
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    void clearLocalProfilesForAccountSwitch() {
        SharedPreferences.Editor editor = prefs.edit().remove("profiles_json")
                .remove("active_profile").remove("profile_deleted_ids");
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("p:") || key.startsWith("profile_sync_dirty:")
                    || key.startsWith("profile_metadata_dirty:")
                    || key.startsWith("profile_sync_revision:")
                    || key.startsWith("watch:") || key.startsWith("seen:")
                    || key.startsWith("fav:")) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    LinearLayout settingsCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(d(16), d(16), d(16), d(16));
        card.setBackground(round(PANEL, d(13)));
        return card;
    }

    RadioButton settingsRadio(String text, boolean checked) {
        RadioButton button = new RadioButton(this);
        button.setText(t(text));
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setButtonTintList(android.content.res.ColorStateList.valueOf(RED));
        button.setChecked(checked);
        button.setMinHeight(d(44));
        button.setFocusable(true);
        button.setFocusableInTouchMode(false);
        applyTvFocusStyle(button, false);
        return button;
    }

    void changeLanguage(String language) {
        prefs.edit().putString("language", language).apply();
        renderAdminWindow();
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
        return prefs.contains(favoriteKey(node.optString("id")));
    }

    void toggleFavorite(JSONObject node) {
        String key = favoriteKey(node.optString("id"));
        if (prefs.contains(key)) prefs.edit().remove(key).apply();
        else prefs.edit().putString(key, node.toString()).apply();
        scheduleProfileStateSync();
        Toast.makeText(this, prefs.contains(key) ? "Añadido a Mi lista" : "Eliminado de Mi lista", Toast.LENGTH_SHORT).show();
    }

    List<JSONObject> readFavorites() {
        List<JSONObject> list = new ArrayList<>();
        String prefix = progressPrefix("fav");
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            try { list.add(new JSONObject(String.valueOf(entry.getValue()))); }
            catch (JSONException ignored) { }
        }
        return list;
    }

    List<JSONObject> readContinueWatching() {
        List<JSONObject> list = new ArrayList<>();
        String prefix = progressPrefix("watch");
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
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
            applyTvFocusStyle(card, true);
            card.setTag("resume-item:" + item.optString("path", item.optString("stream", "")));
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
            details.addView(label("▶  " + t("CONTINUAR") + " · " + formatTime(item.optInt("position")),
                    10, 0xFFFF858A, true));
            TextView name = labelRaw(item.optString("title", "Vídeo"), 14, Color.WHITE, true);
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
            applyTvFocusStyle(actions, false);
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
        String itemId = mediaId == null ? "" : mediaId;
        if (itemId.isEmpty()) {
            String relative = path.startsWith(server) ? path.substring(server.length()) : path;
            String prefix = "/api/v1/items/";
            String suffix = "/stream";
            if (relative.startsWith(prefix) && relative.endsWith(suffix)) {
                itemId = relative.substring(prefix.length(), relative.length() - suffix.length());
            }
        }
        if (itemId.isEmpty()) {
            Toast.makeText(this, "No se pudo identificar el vídeo para reproducirlo.", Toast.LENGTH_LONG).show();
            return;
        }
        final String resolvedItemId = itemId;
        String key = watchKey(path);
        long position = 0L;
        try { position = new JSONObject(prefs.getString(key, "{}")).optLong("position"); }
        catch (JSONException ignored) { }
        final long resumePosition = position;
        Toast.makeText(this, "Preparando reproducción…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try {
                JSONObject ticket = postJson("/api/v1/items/" + enc(resolvedItemId) + "/playback-ticket");
                String ticketPath = ticket.optString("url", "");
                if (ticketPath.isEmpty()) throw new Exception("El servidor no devolvió el acceso temporal al vídeo.");
                String url = ticketPath.startsWith("http") ? ticketPath
                        : server + (ticketPath.startsWith("/") ? ticketPath : "/" + ticketPath);
                runOnUiThread(() -> startPlayerActivity(url, path, title, posterUrl, ownerNodeId,
                        resolvedItemId, ownerKind, key, resumePosition));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "No se pudo preparar el vídeo: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    void startPlayerActivity(String url, String path, String title, String posterUrl,
                             String ownerNodeId, String mediaId, String ownerKind,
                             String key, long position) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra("url", url);
        intent.putExtra("path", path);
        intent.putExtra("title", title);
        intent.putExtra("poster_url", posterUrl);
        intent.putExtra("position", position);
        intent.putExtra("owner_node_id", ownerNodeId);
        intent.putExtra("media_id", mediaId);
        intent.putExtra("owner_kind", ownerKind);
        intent.putExtra("progress_key", key);
        intent.putExtra("profile_id", activeProfileId());
        startActivity(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        if (hasResumedOnce) {
            String activeId = activeProfileId();
            if (!activeId.isEmpty() && prefs.getBoolean(profileStateDirtyKey(activeId), false)) {
                scheduleProfileStateSync();
            }
            if (!startupPrefetchPending) synchronizeProfilesFromServer(false);
        } else hasResumedOnce = true;
        if (prefs != null && page != null && "home".equals(selectedSection)
                && !showingDetail && !showingAdmin && content != null
                && movies.size() + series.size() > 0) {
            int scrollY = contentScroller == null ? 0 : contentScroller.getScrollY();
            renderHome();
            if (contentScroller != null) contentScroller.post(() -> contentScroller.scrollTo(0, scrollY));
            maybeRefreshHomeCatalog();
        } else if (prefs != null && libraryMode && !showingDetail && !showingAdmin && !metadataEditorMode) {
            if (breadcrumbs.isEmpty()) maybeRefreshCatalogListing(currentLibrary, null);
            else maybeRefreshCatalogListing(currentLibrary,
                    breadcrumbs.get(breadcrumbs.size() - 1).optString("id"));
        }
    }

    @Override public void onBackPressed() {
        if (introShowing) return;
        if (clientApiToken().isEmpty()) {
            finishAndRemoveTask();
            return;
        }
        if (showingProfiles) {
            if (profileEditorVisible) backFromProfileEditor();
            else if (readProfiles().isEmpty()) showProfileEditor(null);
            else {
                showingProfiles = false;
                loadHome();
            }
            return;
        }
        if (showingAdmin) {
            showingAdmin = false;
            String returnDetailId = adminReturnDetailId;
            adminReturnDetailId = "";
            if (!returnDetailId.isEmpty()) {
                buildShell("Inicio");
                io.execute(() -> {
                    try {
                        JSONObject node = getJson("/api/v1/catalog/" + enc(returnDetailId));
                        runOnUiThread(() -> renderDetail(node));
                    } catch (Exception e) { runOnUiThread(this::loadHome); }
                });
            } else if (!breadcrumbs.isEmpty()) {
                JSONObject parent = breadcrumbs.get(breadcrumbs.size() - 1);
                buildShell("Inicio");
                showCollectionContents(parent);
            } else if (libraryMode) {
                openLibrary(currentLibrary);
            } else if (lastSearchQuery != null) {
                buildShell("Inicio");
                performSearch(lastSearchQuery);
            } else {
                loadHome();
            }
            return;
        }
        if (metadataEditorMode) {
            returnFromMetadataEditor();
            return;
        }
        if (showingDetail) {
            showingDetail = false;
            if (!breadcrumbs.isEmpty()) {
                pendingListingRestoreY = detailReturnScrollY;
                showCollectionContents(breadcrumbs.get(breadcrumbs.size() - 1));
            } else if (libraryMode) {
                pendingListingRestoreY = detailReturnScrollY;
                openLibrary(currentLibrary);
            } else if (lastSearchQuery != null) {
                pendingListingRestoreY = detailReturnScrollY;
                performSearch(lastSearchQuery);
            } else {
                loadHome();
            }
            return;
        }
        if (!breadcrumbs.isEmpty()) {
            breadcrumbs.remove(breadcrumbs.size() - 1);
            int returnScrollY = breadcrumbScrollPositions.isEmpty()
                    ? 0 : breadcrumbScrollPositions.remove(breadcrumbScrollPositions.size() - 1);
            pendingListingRestoreY = returnScrollY;
            if (breadcrumbs.isEmpty()) {
                if (libraryMode) openLibrary(currentLibrary);
                else if (lastSearchQuery != null) {
                    pendingListingRestoreY = returnScrollY;
                    performSearch(lastSearchQuery);
                }
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
