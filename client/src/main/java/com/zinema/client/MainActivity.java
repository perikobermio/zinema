package com.zinema.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.ImageSpan;
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
import android.widget.CheckBox;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static final Pattern EPISODE_MARKER_PATTERN = Pattern.compile(
            "(?i)s(\\d{1,2})\\s*e(\\d{1,3})|(?:^|[^0-9])(\\d{1,2})x(\\d{1,3})(?:$|[^0-9])");
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
    final Handler contentRequestSearchHandler = new Handler(Looper.getMainLooper());
    Runnable contentRequestSearchTask;
    Runnable adminSyncPollTask;
    final LruCache<String, Bitmap> imageCache = new LruCache<String, Bitmap>(bitmapCacheBytes()) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getAllocationByteCount());
        }
    };
    final Map<String, List<ImageView>> pendingImageTargets = new HashMap<>();
    final Map<ImageView, String[]> deferredImageRequests = new WeakHashMap<>();
    final Set<ImageView> lazyImageScrollWatchers = Collections.newSetFromMap(new WeakHashMap<>());
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
    JSONObject currentDetailNode;
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
    boolean playerLaunched;
    boolean seriesSuggestionBackfillRunning;
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
    volatile JSONArray incomingFilesCache;
    String adminReturnDetailId = "";
    String editingProfileId = "";
    String selectedProfileAvatar = "";
    boolean metadataEditorMode;
    String metadataEditorReturnScreen = "";
    String metadataEditorReturnDetailId = "";
    int metadataEditorReturnScrollY;
    boolean metadataEditorReturnPrepared;
    boolean metadataEditorPreviousBackVisible;
    boolean updateCheckInProgress;
    boolean installAfterPermission;
    int lastPromptedUpdateCode;
    List<JSONObject> movies = new ArrayList<>();
    List<JSONObject> series = new ArrayList<>();
    final Map<String, String> posterByStream = new HashMap<>();
    final Map<String, Set<String>> selectedGenresByLibrary = new HashMap<>();
    final Set<String> kidAllowedSeasonIds = new HashSet<>();
    final Map<ImageView, String> requestedImages = new WeakHashMap<>();
    final List<JSONObject> breadcrumbs = new ArrayList<>();
    final List<Integer> breadcrumbScrollPositions = new ArrayList<>();
    final Map<Character, View> alphabetAnchors = new HashMap<>();
    View alphabetRail;
    View alphabetToggleButton;
    boolean alphabetVisible;
    List<JSONObject> currentListingNodes;
    String currentListingTitle = "";
    String currentListingSubtitle = "";
    boolean advancingCompletedEpisode;

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
        checkForClientUpdate(true);
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
                boolean isAdmin = jsonBoolean(account, "is_admin", false);
                prefs.edit().putBoolean("account_is_admin", isAdmin).apply();
                if (!isAdmin && (!account.has("approved") || !jsonBoolean(account, "approved", false))) {
                    runOnUiThread(() -> invalidateSavedAccount("Esta cuenta todavía necesita aprobación de un administrador."));
                    return;
                }
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    startStartupPrefetch();
                    showIntro();
                });
                if (isAdmin) prefetchIncomingVideosForAdmin();
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
                .remove("client_device_id").remove("account_email").remove("account_is_admin").apply();
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

    boolean isTelevision() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    String trimBase(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    void buildShell(String title) {
        frame = new FrameLayout(this);
        boolean kidTheme = isActiveKidProfile() && !showingAdmin;
        GradientDrawable appBackground = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                kidTheme
                        ? new int[]{0xFF172B55, 0xFF453078, 0xFF8B3C77, 0xFF176C83}
                        : new int[]{0xFF22243A, 0xFF141925, 0xFF191520, 0xFF29202D});
        frame.setBackground(appBackground);
        frame.setClipChildren(false);
        if (kidTheme) {
            View confetti = new View(this) {
                final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override protected void onDraw(Canvas canvas) {
                    super.onDraw(canvas);
                    int[][] dots = {{0x3038E7FF, 12, 15, 78}, {0x30FFE15A, 86, 30, 35},
                            {0x30FF80C8, 91, 78, 96}, {0x30FFFFFF, 18, 82, 43},
                            {0x30FFD166, 53, 8, 28}, {0x3047F5B8, 62, 52, 58}};
                    for (int[] dot : dots) {
                        paint.setColor(dot[0]);
                        canvas.drawCircle(getWidth() * dot[1] / 100f,
                                getHeight() * dot[2] / 100f, d(dot[3]), paint);
                    }
                }
            };
            confetti.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            frame.addView(confetti, new FrameLayout.LayoutParams(-1, -1));
        }
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        frame.addView(page, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(d(20), d(20), d(16), d(8));
        bar.setBackgroundColor(kidTheme ? 0xF12A3767 : 0xF0141721);
        bar.setElevation(d(24));
        bar.setTranslationZ(d(24));
        backButton = navAction("‹", "Atrás", v -> onBackPressed());
        backButton.setTextSize(30);
        boolean libraryRoot = libraryMode && !showingDetail && breadcrumbs.isEmpty()
                && ("movies".equals(selectedSection) || "series".equals(selectedSection));
        backButton.setVisibility(title.equals("Inicio") || libraryRoot ? View.GONE : View.VISIBLE);
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
        searchButton.setBackground(kidTheme
                ? gradientRound(new int[]{0xFF3779A5, 0xFF7851A2}, d(22))
                : round(PANEL, d(22)));
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
        navScroll.setElevation(d(22));
        navScroll.setTranslationZ(d(22));
        navScroll.setBackgroundColor(kidTheme ? 0xF1373978 : 0xF0141721);
        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(d(16), 0, d(16), d(8));
        nav.addView(navChip("Inicio", () -> loadHome(), selectedSection.equals("home")));
        nav.addView(navChip("Películas", () -> openLibrary("movies"), selectedSection.equals("movies")));
        nav.addView(navChip("Series", () -> openLibrary("series"), selectedSection.equals("series")));
        alphabetToggleButton = navChip("A–Z", this::toggleAlphabet, alphabetVisible);
        alphabetToggleButton.setContentDescription(t("Mostrar u ocultar el abecedario"));
        nav.addView(alphabetToggleButton);
        alphabetToggleButton.setVisibility(View.GONE);
        navScroll.addView(nav);
        page.addView(navScroll, new LinearLayout.LayoutParams(-1, d(48)));
        if (showingAdmin || showingProfiles) navScroll.setVisibility(View.GONE);

        contentScroller = new ScrollView(this);
        contentScroller.setFillViewport(true);
        contentScroller.setVerticalScrollBarEnabled(false);
        GradientDrawable sectionBackground = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                kidTheme
                        ? new int[]{0xD91B4670, 0xD943347A, 0xD92D6874}
                        : new int[]{0xFF19243A, 0xFF131925, 0xFF211923});
        contentScroller.setBackground(sectionBackground);
        contentScroller.setClipChildren(false);
        contentScroller.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(0, 0, 0, d(28));
        contentScroller.addView(content);
        contentScroller.setOnScrollChangeListener((View view, int x, int y, int oldX, int oldY) ->
                loadVisibleLazyRows());
        page.addView(contentScroller, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(frame);
        frame.post(() -> {
            loadVisibleLazyRows();
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
        view.setText(t(cleanDisplayText(text)));
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(activeProfileTypeface(bold));
        return view;
    }

    TextView labelRaw(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(cleanDisplayText(text));
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(activeProfileTypeface(bold));
        return view;
    }

    Typeface activeProfileTypeface(boolean bold) {
        int style = bold ? Typeface.BOLD : Typeface.NORMAL;
        return Typeface.create(isActiveKidProfile() ? "casual" : "sans-serif", style);
    }

    String cleanDisplayText(String value) {
        if (value == null) return "";
        return value.replaceAll("(?i)\\bnull\\b", "")
                .replaceAll("^[\\s·•|]+|[\\s·•|]+$", "")
                .replaceAll("\\s{2,}", " ").trim();
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
        boolean posterFocus = scaleOnFocus && isTelevision();
        if (!posterFocus) view.setForeground(outline);
        if (view instanceof ViewGroup) ((ViewGroup) view).setClipChildren(false);
        view.setOnFocusChangeListener((focusedView, hasFocus) -> {
            if (!(scaleOnFocus && isTelevision())) {
                int focusColor = isActiveKidProfile() ? 0xFFFFD166 : RED;
                outline.setStroke(d(hasFocus ? 2 : 1), hasFocus ? focusColor : Color.TRANSPARENT);
                focusedView.setForeground(outline);
            } else {
                setPosterBrightness(focusedView, hasFocus);
            }
            focusedView.animate().cancel();
            float scale = hasFocus && scaleOnFocus && isTelevision() ? 1.035f : 1f;
            focusedView.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
            focusedView.setElevation(hasFocus ? d(7) : 0);
        });
    }

    void setPosterBrightness(View root, boolean focused) {
        if (root instanceof ImageView) {
            ImageView image = (ImageView) root;
            if (focused) {
                ColorMatrix matrix = new ColorMatrix(new float[]{
                        1.12f, 0, 0, 0, 5,
                        0, 1.12f, 0, 0, 5,
                        0, 0, 1.12f, 0, 5,
                        0, 0, 0, 1, 0
                });
                image.setColorFilter(new ColorMatrixColorFilter(matrix));
            } else image.clearColorFilter();
        } else if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) setPosterBrightness(group.getChildAt(i), focused);
        }
    }

    View navChip(String title, Runnable action, boolean active) {
        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(d(14), d(2), d(14), d(2));
        TextView text = label(title, 14, active ? Color.WHITE
                : isActiveKidProfile() ? 0xFFE3DCFF : MUTED, active);
        text.setGravity(Gravity.CENTER);
        tab.addView(text, new LinearLayout.LayoutParams(-2, -2));
        View indicator = new View(this);
        indicator.setBackground(round(active ? (isActiveKidProfile() ? 0xFFFFD166 : RED)
                : Color.TRANSPARENT, d(2)));
        if (isActiveKidProfile()) tab.setBackground(round(active ? 0x554F61B1 : Color.TRANSPARENT, d(14)));
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
        syncActiveProfileToServer(activeProfileId());
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
            payload.put("is_kid", profile.optBoolean("is_kid", false));
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
                    if (!prefs.getBoolean(profileMetadataDirtyKey(id), false)) {
                        if (!remote.has("is_kid")) {
                            try { remote.put("is_kid", local.get(j).optBoolean("is_kid", false)); }
                            catch (JSONException ignored) { }
                        }
                        local.set(j, remote);
                    }
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

    void syncActiveProfileToServer(String profileId) {
        String deviceId = prefs.getString("client_device_id", "");
        if (profileId == null || profileId.isEmpty() || deviceId.isEmpty() || clientApiToken().isEmpty()) return;
        String syncKey = deviceId + ":" + profileId;
        if (syncKey.equals(prefs.getString("active_profile_synced", ""))) return;
        io.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("profile_id", profileId);
                putAuthorizedProfileJson("/api/v1/profiles/active", payload);
                if (profileId.equals(activeProfileId())
                        && deviceId.equals(prefs.getString("client_device_id", ""))) {
                    prefs.edit().putString("active_profile_synced", syncKey).apply();
                }
            } catch (Exception ignored) { }
        });
    }

    JSONObject activeProfile() {
        String id = activeProfileId();
        for (JSONObject profile : readProfiles()) {
            if (id.equals(profile.optString("id"))) return profile;
        }
        List<JSONObject> profiles = readProfiles();
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    boolean isActiveKidProfile() {
        if (prefs == null) return false;
        JSONObject profile = activeProfile();
        return profile != null && profile.optBoolean("is_kid", false);
    }

    boolean isAccountAdmin() {
        return prefs != null && prefs.getBoolean("account_is_admin", false);
    }

    boolean isKidAllowedNode(JSONObject node) {
        if (!isActiveKidProfile()) return true;
        if (node == null) return false;
        String kind = node.optString("kind", "");
        if ("collection".equals(kind)) {
            JSONArray genres = node.optJSONArray("genres");
            return genres == null || genres.length() == 0 || isKidAllowedByGenres(node);
        }
        if ("season".equals(kind)) return kidAllowedSeasonIds.contains(node.optString("id", ""));
        if ("episode".equals(kind)) {
            String seriesId = node.optString("series_id", "");
            JSONObject owner = seriesId.isEmpty() ? null : findCatalogNodeById(seriesId, series);
            return owner != null && isKidAllowedByGenres(owner);
        }
        return isKidAllowedByGenres(node);
    }

    boolean isKidAllowedByGenres(JSONObject node) {
        if (node == null) return false;
        JSONArray genres = node.optJSONArray("genres");
        if (genres == null || genres.length() == 0) return true;
        boolean hasFamily = false;
        boolean hasBlockedGenre = false;
        for (int i = 0; i < genres.length(); i++) {
            String genre = normalizeGenre(genres.optString(i, ""));
            if ("familia".equals(genre) || "family".equals(genre)) hasFamily = true;
            if (isKidBlockedGenre(genre)) hasBlockedGenre = true;
        }
        return !hasBlockedGenre || hasFamily;
    }

    boolean isKidBlockedGenre(String genre) {
        String[] terms = normalizeGenre(genre).split("[^a-z0-9]+");
        for (String term : terms) {
            if (term.equals("guerra") || term.equals("gerra") || term.equals("war")
                    || term.equals("belica") || term.equals("crimen") || term.equals("crime")
                    || term.equals("krimena") || term.equals("drama") || term.equals("terror")
                    || term.equals("horror") || term.equals("beldurrezkoa") || term.equals("western"))
                return true;
        }
        return false;
    }

    List<JSONObject> filterKidCatalog(List<JSONObject> nodes) {
        if (!isActiveKidProfile() || nodes == null) return nodes == null ? new ArrayList<>() : nodes;
        List<JSONObject> filtered = new ArrayList<>();
        for (JSONObject node : nodes) if (isKidAllowedNode(node)) filtered.add(node);
        return filtered;
    }

    boolean isKidAllowedProgress(JSONObject item) {
        if (!isActiveKidProfile()) return true;
        String seriesId = item.optString("series_id", "");
        if (!seriesId.isEmpty()) {
            JSONObject owner = findCatalogNodeById(seriesId, series);
            boolean allowed = owner != null && isKidAllowedByGenres(owner);
            String seasonId = item.optString("owner_node_id", "");
            if (allowed && !seasonId.isEmpty()) kidAllowedSeasonIds.add(seasonId);
            return allowed;
        }
        String ownerId = item.optString("owner_node_id", item.optString("node_id", ""));
        JSONObject owner = findCatalogNodeById(ownerId, movies);
        if (owner == null) owner = findCatalogNodeById(ownerId, series);
        if (owner != null) return isKidAllowedNode(owner);
        JSONObject media = findCatalogNodeById(item.optString("media_id", ""), movies);
        if (media == null) media = findCatalogNodeById(item.optString("media_id", ""), series);
        return media != null && isKidAllowedNode(media);
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
        boolean catalogAvatar = avatar != null && avatar.startsWith("catalog:");
        if (!imagePath.isEmpty() || catalogAvatar) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable clear = new GradientDrawable();
            clear.setColor(Color.TRANSPARENT);
            clear.setShape(GradientDrawable.OVAL);
            image.setBackground(clear);
            image.setClipToOutline(true);
            holder.addView(image, new FrameLayout.LayoutParams(-1, -1));
            if (!imagePath.isEmpty()) loadImage(imagePath, image, false);
            else loadCatalogProfileAvatar(avatar, image);
        }
        if (clickable) {
            applyTvFocusStyle(holder, false);
            holder.setOnClickListener(v -> openProfileManager());
        }
        return holder;
    }

    void loadCatalogProfileAvatar(String avatar, ImageView target) {
        if (avatar == null || !avatar.startsWith("catalog:") || clientApiToken().isEmpty()) return;
        String nodeId = avatar.substring("catalog:".length());
        target.setTag("profile-catalog:" + nodeId);
        io.execute(() -> {
            try {
                JSONObject node = getJson("/api/v1/catalog/" + enc(nodeId));
                String imagePath = node.optString("poster_url", "");
                if (imagePath.isEmpty()) {
                    JSONArray mosaic = node.optJSONArray("mosaic_images");
                    if (mosaic != null && mosaic.length() > 0) imagePath = mosaic.optString(0, "");
                }
                if (imagePath.isEmpty()) return;
                String resolvedImagePath = imagePath;
                runOnUiThread(() -> {
                    if (target.getParent() != null && ("profile-catalog:" + nodeId).equals(target.getTag())) {
                        loadImage(resolvedImagePath, target, false);
                    }
                });
            } catch (Exception ignored) { }
        });
    }

    View buildProfileChip() {
        LinearLayout chip = new LinearLayout(this);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(d(5), d(3), d(10), d(3));
        chip.setBackground(isActiveKidProfile()
                ? gradientRound(new int[]{0xFF4B3A83, 0xFF137487}, d(24))
                : round(PANEL, d(24)));
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
            if (isActiveKidProfile()) {
                List<String> choices = new ArrayList<>();
                List<Integer> actions = new ArrayList<>();
                choices.add(t("Cambiar perfil")); actions.add(1);
                choices.add(t("Pedir contenido")); actions.add(6);
                choices.add(t("Cerrar sesión")); actions.add(3);
                choices.add(t("Salir")); actions.add(4);
                showStyledActionDialog(t("Menú de perfil"), choices,
                        selected -> runAvatarMenuAction(actions.get(selected)));
                return;
            }
            PopupMenu menu = new PopupMenu(MainActivity.this, chip);
            addAvatarMenuItem(menu, 1, 1, t("Cambiar perfil"), MenuIconDrawable.PROFILE);
            addAvatarMenuItem(menu, 2, 2, t("Administración"), MenuIconDrawable.SETTINGS);
            addAvatarMenuItem(menu, 6, 3, t("Pedir contenido"), MenuIconDrawable.SEARCH);
            if (prefs.getBoolean("account_is_admin", false)) {
                addAvatarMenuItem(menu, 5, 4, t("Gestión de cuentas"), MenuIconDrawable.ACCOUNTS);
            }
            addAvatarMenuItem(menu, 3, 5, t("Cerrar sesión"), MenuIconDrawable.LOGOUT);
            addAvatarMenuItem(menu, 4, 6, t("Salir"), MenuIconDrawable.EXIT);
            menu.setOnMenuItemClickListener(item -> {
                runAvatarMenuAction(item.getItemId());
                return true;
            });
            menu.show();
        });
        return chip;
    }

    void runAvatarMenuAction(int id) {
        if (id == 1) openProfileManager();
        else if (id == 2) showSettings();
        else if (id == 5) showAccountManagement();
        else if (id == 6) showContentRequestScreen();
        else if (id == 3) logoutAccount();
        else if (id == 4) finishAndRemoveTask();
    }

    void showContentRequestScreen() {
        if (clientApiToken().isEmpty()) {
            showLoginScreen();
            return;
        }
        leaveStartupHomeWait();
        if (!showingAdmin) adminReturnDetailId = showingDetail ? detailNodeId : "";
        showingAdmin = true;
        showingDetail = false;
        buildShell(t("Pedir contenido"));
        content.removeAllViews();

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(d(20), d(14), d(20), d(28));
        screen.addView(label(t("Pedir contenido"), 26, Color.WHITE, true));
        TextView explanation = label(t("Toca un resultado para añadirlo a la petición. Puedes elegir la temporada de cada serie y enviar varios títulos juntos."),
                14, MUTED, false);
        LinearLayout.LayoutParams explanationParams = new LinearLayout.LayoutParams(-1, -2);
        explanationParams.topMargin = d(6);
        explanationParams.bottomMargin = d(14);
        screen.addView(explanation, explanationParams);

        EditText query = settingsField("", t("Título de película o serie"), false);
        screen.addView(query, new LinearLayout.LayoutParams(-1, d(48)));
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams resultsParams = new LinearLayout.LayoutParams(-1, -2);
        resultsParams.topMargin = d(8);
        screen.addView(results, resultsParams);

        TextView queueHeading = label(t("Elementos en la petición") + " · 0", 17, Color.WHITE, true);
        LinearLayout.LayoutParams queueHeadingParams = new LinearLayout.LayoutParams(-1, -2);
        queueHeadingParams.topMargin = d(18);
        screen.addView(queueHeading, queueHeadingParams);
        LinearLayout queue = new LinearLayout(this);
        queue.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams queueParams = new LinearLayout.LayoutParams(-1, -2);
        queueParams.topMargin = d(8);
        screen.addView(queue, queueParams);
        Button submit = actionButton(t("Enviar solicitudes") + " · 0", true);
        submit.setEnabled(false);
        LinearLayout.LayoutParams submitParams = new LinearLayout.LayoutParams(-1, d(46));
        submitParams.topMargin = d(12);
        screen.addView(submit, submitParams);
        content.addView(screen, new LinearLayout.LayoutParams(-1, -2));
        if (isTelevision()) query.requestFocus();

        ArrayList<JSONObject> requestItems = new ArrayList<>();
        JSONArray[] searchResults = {new JSONArray()};
        Runnable[] renderGrid = {null};
        Runnable[] renderQueue = {null};
        renderGrid[0] = () -> renderContentRequestGrid(results, searchResults[0],
                null, candidate -> {
                    String type = candidate.optString("content_type");
                    long tmdbId = candidate.optLong("tmdb_id");
                    for (JSONObject item : requestItems) {
                        if (item.optString("content_type").equals(type)
                                && item.optLong("tmdb_id") == tmdbId) {
                            Toast.makeText(this, t("Ese contenido ya está en la petición."), Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                    if (requestItems.size() >= 20) {
                        Toast.makeText(this, t("Puedes añadir hasta 20 elementos."), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        JSONObject queued = new JSONObject();
                        queued.put("content_type", type);
                        queued.put("tmdb_id", tmdbId);
                        queued.put("title", candidate.optString("title", ""));
                        queued.put("year", candidate.opt("year"));
                        queued.put("poster_path", candidate.optString("poster_path", ""));
                        queued.put("season_number", -1);
                        requestItems.add(queued);
                    } catch (JSONException ignored) { return; }
                    searchResults[0] = new JSONArray();
                    query.setText("");
                    renderGrid[0].run();
                    renderQueue[0].run();
                });
        renderQueue[0] = () -> {
            queue.removeAllViews();
            queueHeading.setText(t("Elementos en la petición") + " · " + requestItems.size());
            submit.setText(t("Enviar solicitudes") + " · " + requestItems.size());
            submit.setEnabled(!requestItems.isEmpty());
            for (int i = 0; i < requestItems.size(); i++) {
                JSONObject item = requestItems.get(i);
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(d(8), d(7), d(8), d(7));
                row.setBackground(round(PANEL, d(9)));
                ImageView poster = new ImageView(this);
                poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
                row.addView(poster, new LinearLayout.LayoutParams(d(48), d(68)));
                loadImage(tmdbPosterUrl(item.optString("poster_path", "")), poster, false);
                LinearLayout textBox = new LinearLayout(this);
                textBox.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams textBoxParams = new LinearLayout.LayoutParams(0, -2, 1);
                textBoxParams.leftMargin = d(10);
                row.addView(textBox, textBoxParams);
                textBox.addView(labelRaw(item.optString("title", ""), 14, Color.WHITE, true));
                String subtitle = "movie".equals(item.optString("content_type"))
                        ? t("Película") : t("Serie");
                textBox.addView(label(subtitle, 12, MUTED, false));
                if ("tv".equals(item.optString("content_type"))) {
                    int season = item.optInt("season_number", -1);
                    String seasonLabel = season > 0 ? t("Temporada") + " " + season : t("Serie completa");
                    Button seasonButton = actionButton(seasonLabel, false);
                    LinearLayout.LayoutParams seasonButtonParams = new LinearLayout.LayoutParams(-2, d(34));
                    seasonButtonParams.topMargin = d(4);
                    textBox.addView(seasonButton, seasonButtonParams);
                    seasonButton.setOnClickListener(v -> chooseContentRequestSeason(item, renderQueue[0]));
                }
                Button remove = actionButton(t("Quitar"), false);
                row.addView(remove, new LinearLayout.LayoutParams(-2, d(38)));
                remove.setOnClickListener(v -> {
                    requestItems.remove(item);
                    renderQueue[0].run();
                });
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
                rowParams.bottomMargin = d(6);
                queue.addView(row, rowParams);
            }
        };

        submit.setOnClickListener(v -> {
            if (requestItems.isEmpty()) return;
            JSONObject payload = new JSONObject();
            try {
                payload.put("request_type", "new_content");
                JSONArray items = new JSONArray();
                for (JSONObject item : requestItems) {
                    JSONObject entry = new JSONObject();
                    entry.put("content_type", item.optString("content_type"));
                    entry.put("tmdb_id", item.optLong("tmdb_id"));
                    int season = item.optInt("season_number", -1);
                    if (season > 0) entry.put("season_number", season);
                    items.put(entry);
                }
                payload.put("items", items);
            } catch (JSONException ignored) { return; }
            submit.setEnabled(false);
            io.execute(() -> {
                try {
                    postJson("/api/v1/content-requests", payload);
                    runOnUiThread(() -> {
                        Toast.makeText(this, t("Solicitud enviada a administración."), Toast.LENGTH_LONG).show();
                        requestItems.clear();
                        renderGrid[0].run();
                        renderQueue[0].run();
                        query.setText("");
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        submit.setEnabled(!requestItems.isEmpty());
                        Toast.makeText(this, t("No se pudo enviar la solicitud: ") + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    });
                }
            });
        });

        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable editable) {
                if (contentRequestSearchTask != null)
                    contentRequestSearchHandler.removeCallbacks(contentRequestSearchTask);
                String searchTerm = editable.toString().trim();
                searchResults[0] = new JSONArray();
                results.removeAllViews();
                if (searchTerm.length() < 2) return;
                contentRequestSearchTask = () -> io.execute(() -> {
                    try {
                        JSONArray found = getJson("/api/v1/content-requests/search?q=" + enc(searchTerm))
                                .optJSONArray("results");
                        runOnUiThread(() -> {
                            if (!showingAdmin || results.getParent() == null
                                    || !searchTerm.equals(query.getText().toString().trim())) return;
                            if (found == null || found.length() == 0) {
                                results.removeAllViews();
                                results.addView(label(t("No se encontraron resultados."), 13, MUTED, false));
                                return;
                            }
                            searchResults[0] = found;
                            renderGrid[0].run();
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            if (!showingAdmin || results.getParent() == null
                                    || !searchTerm.equals(query.getText().toString().trim())) return;
                            results.removeAllViews();
                            results.addView(label(t("No se pudo buscar en TMDb: ") + e.getMessage(),
                                    13, MUTED, false));
                        });
                    }
                });
                contentRequestSearchHandler.postDelayed(contentRequestSearchTask, 350L);
            }
        });
    }

    interface ContentRequestCandidateHandler { void onSelected(JSONObject candidate); }

    String tmdbPosterUrl(String posterPath) {
        if (posterPath == null || posterPath.trim().isEmpty() || "null".equalsIgnoreCase(posterPath)) return "";
        if (posterPath.startsWith("http")) return posterPath;
        return "https://image.tmdb.org/t/p/w342" + (posterPath.startsWith("/") ? posterPath : "/" + posterPath);
    }

    void renderContentRequestGrid(LinearLayout results, JSONArray candidates, JSONObject selected,
                                  ContentRequestCandidateHandler handler) {
        results.removeAllViews();
        int columns = Math.max(2, Math.min(5,
                (getResources().getDisplayMetrics().widthPixels - d(48)) / d(158)));
        LinearLayout gridRow = null;
        int inRow = 0;
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject candidate = candidates.optJSONObject(i);
            if (candidate == null) continue;
            if (gridRow == null || inRow == columns) {
                gridRow = new LinearLayout(this);
                gridRow.setGravity(Gravity.TOP);
                LinearLayout.LayoutParams gridRowParams = new LinearLayout.LayoutParams(-1, -2);
                gridRowParams.topMargin = d(8);
                results.addView(gridRow, gridRowParams);
                inRow = 0;
            }
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setPadding(d(5), d(5), d(5), d(7));
            tile.setFocusable(true);
            tile.setClickable(true);
            String tag = candidate.optString("content_type") + ":" + candidate.optLong("tmdb_id");
            String selectedTag = selected == null ? "" : selected.optString("content_type")
                    + ":" + selected.optLong("tmdb_id");
            GradientDrawable tileBackground = round(tag.equals(selectedTag) ? 0xFF393147 : PANEL, d(10));
            if (tag.equals(selectedTag)) tileBackground.setStroke(d(2), 0xFFFFC857);
            tile.setBackground(tileBackground);
            ImageView poster = new ImageView(this);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            poster.setBackground(round(PANEL, d(8)));
            tile.addView(poster, new LinearLayout.LayoutParams(-1, d(174)));
            loadImage(tmdbPosterUrl(candidate.optString("poster_path", "")), poster, false);
            TextView name = labelRaw(candidate.optString("title", ""), 13, Color.WHITE, true);
            name.setMaxLines(2);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, -2);
            nameParams.topMargin = d(6);
            tile.addView(name, nameParams);
            String type = "movie".equals(candidate.optString("content_type")) ? t("Película") : t("Serie");
            String year = candidate.isNull("year") ? "" : candidate.optString("year");
            tile.addView(label(type + (year.isEmpty() ? "" : " · " + year), 11, MUTED, false));
            tile.setOnClickListener(v -> handler.onSelected(candidate));
            applyTvFocusStyle(tile, false);
            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, -2, 1);
            tileParams.setMargins(d(3), 0, d(3), d(5));
            gridRow.addView(tile, tileParams);
            inRow++;
        }
        if (gridRow != null) {
            while (inRow++ < columns) gridRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        }
    }

    void chooseContentRequestSeason(JSONObject item, Runnable rerenderQueue) {
        String type = item.optString("content_type", "");
        long tmdbId = item.optLong("tmdb_id");
        if (!"tv".equals(type) || tmdbId <= 0) return;
        io.execute(() -> {
            try {
                JSONObject details = getJson("/api/v1/content-requests/tmdb/" + type + "/" + tmdbId);
                runOnUiThread(() -> {
                    JSONArray seasons = details.optJSONArray("seasons");
                    int count = seasons == null ? 0 : seasons.length();
                    String[] labels = new String[count + 1];
                    int[] numbers = new int[count + 1];
                    labels[0] = t("Serie completa");
                    numbers[0] = -1;
                    for (int i = 0; i < count; i++) {
                        JSONObject season = seasons.optJSONObject(i);
                        int number = season == null ? i + 1 : season.optInt("season_number", i + 1);
                        int episodes = season == null ? 0 : season.optInt("episode_count");
                        labels[i + 1] = t("Temporada") + " " + number
                                + (episodes > 0 ? " · " + episodes + " "
                                + t(episodes == 1 ? "capítulo" : "capítulos") : "");
                        numbers[i + 1] = number;
                    }
                    new AlertDialog.Builder(this).setTitle(t("Selecciona una temporada"))
                            .setItems(labels, (dialog, which) -> {
                                try {
                                    item.put("season_number", numbers[which]);
                                    rerenderQueue.run();
                                } catch (JSONException ignored) { }
                            }).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        t("No se pudo cargar la selección: ") + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    void addAvatarMenuItem(PopupMenu menu, int id, int order, String title, int iconKind) {
        int iconSize = d(20);
        MenuIconDrawable icon = new MenuIconDrawable(iconSize, iconKind);
        icon.setBounds(0, 0, iconSize, iconSize);
        SpannableString itemTitle = new SpannableString("\uFFFC  " + title);
        itemTitle.setSpan(new ImageSpan(icon, ImageSpan.ALIGN_BOTTOM), 0, 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        menu.getMenu().add(android.view.Menu.NONE, id, order, itemTitle);
    }

    static class MenuIconDrawable extends Drawable {
        static final int PROFILE = 1;
        static final int SETTINGS = 2;
        static final int SEARCH = 6;
        static final int ACCOUNTS = 3;
        static final int LOGOUT = 4;
        static final int EXIT = 5;
        final int kind;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        MenuIconDrawable(int size, int kind) {
            this.kind = kind;
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.5f, size * 0.085f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect bounds = getBounds();
            float left = bounds.left;
            float top = bounds.top;
            float size = Math.min(bounds.width(), bounds.height());
            float cx = left + size / 2f;
            float cy = top + size / 2f;
            float unit = size / 100f;
            canvas.save();
            canvas.translate(left, top);
            canvas.scale(unit, unit);
            if (kind == PROFILE) {
                canvas.drawCircle(50, 31, 14, paint);
                Path shoulders = new Path();
                shoulders.moveTo(19, 88);
                shoulders.cubicTo(22, 65, 37, 59, 50, 59);
                shoulders.cubicTo(64, 59, 78, 66, 81, 88);
                canvas.drawPath(shoulders, paint);
            } else if (kind == SETTINGS) {
                canvas.drawCircle(50, 50, 27, paint);
                canvas.drawCircle(50, 50, 10, paint);
                for (int i = 0; i < 8; i++) {
                    double angle = Math.PI * i / 4d;
                    float x1 = 50 + (float) Math.cos(angle) * 31;
                    float y1 = 50 + (float) Math.sin(angle) * 31;
                    float x2 = 50 + (float) Math.cos(angle) * 40;
                    float y2 = 50 + (float) Math.sin(angle) * 40;
                    canvas.drawLine(x1, y1, x2, y2, paint);
                }
            } else if (kind == SEARCH) {
                canvas.drawCircle(43, 43, 24, paint);
                canvas.drawLine(61, 61, 87, 87, paint);
            } else if (kind == ACCOUNTS) {
                canvas.drawCircle(36, 32, 11, paint);
                canvas.drawCircle(67, 35, 9, paint);
                Path group = new Path();
                group.moveTo(12, 84);
                group.cubicTo(15, 64, 26, 57, 38, 57);
                group.cubicTo(52, 57, 60, 67, 61, 84);
                group.moveTo(58, 62);
                group.cubicTo(75, 57, 88, 67, 89, 82);
                canvas.drawPath(group, paint);
            } else if (kind == LOGOUT) {
                canvas.drawLine(57, 13, 30, 13, paint);
                canvas.drawLine(30, 13, 30, 87, paint);
                canvas.drawLine(30, 87, 57, 87, paint);
                canvas.drawLine(44, 50, 91, 50, paint);
                canvas.drawLine(91, 50, 74, 33, paint);
                canvas.drawLine(91, 50, 74, 67, paint);
            } else {
                canvas.drawLine(23, 23, 77, 77, paint);
                canvas.drawLine(77, 23, 23, 77, paint);
            }
            canvas.restore();
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
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
                .remove("client_device_id").remove("account_email").remove("account_is_admin")
                .remove("profile_sync_token").apply();
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
        Button back = actionButton("‹  " + t("Atrás"), false);
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(-2, d(44));
        backParams.bottomMargin = d(12);
        body.addView(back, backParams);
        back.setOnClickListener(v -> onBackPressed());
        body.addView(label(t("¿Quién está viendo?"), 26, Color.WHITE, true));
        TextView hint = label(t("Cada perfil conserva su lista y su progreso de reproducción."), 14, MUTED, false);
        hint.setPadding(0, d(6), 0, d(18));
        body.addView(hint);
        List<JSONObject> profiles = readProfiles();
        for (JSONObject profile : profiles) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(d(12), d(10), d(12), d(10));
            boolean kid = profile.optBoolean("is_kid", false);
            row.setBackground(kid
                    ? gradientRound(new int[]{0xFF17496B, 0xFF593779}, d(16))
                    : round(PANEL, d(12)));
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
            if (kid) info.addView(label(t("Perfil infantil · filtro por género"), 11, 0xFF9EF4E2, true));
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
        syncActiveProfileToServer(id);
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
        Button back = actionButton("‹  " + t("Atrás"), false);
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(-2, d(42));
        backParams.bottomMargin = d(6);
        body.addView(back, backParams);
        back.setOnClickListener(v -> backFromProfileEditor());
        body.addView(label(existing == null ? t("Crear perfil") : t("Editar perfil"), 25, Color.WHITE, true));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(-1, -2);
        buttonsParams.topMargin = d(8);
        buttonsParams.bottomMargin = d(8);
        body.addView(buttons, buttonsParams);
        Button save = actionButton(t("Guardar perfil"), true);
        buttons.addView(save, new LinearLayout.LayoutParams(0, d(48), 1));
        Button cancel = actionButton(t("Cancelar"), false);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, d(48), 1);
        cancelParams.leftMargin = d(8);
        buttons.addView(cancel, cancelParams);
        cancel.setOnClickListener(v -> backFromProfileEditor());
        if (existing != null) {
            Button delete = actionButton(t("Eliminar"), false);
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, d(48), 1);
            deleteParams.leftMargin = d(8);
            buttons.addView(delete, deleteParams);
            delete.setOnClickListener(v -> confirmDeleteProfile(existing));
        }
        TextView nameLabel = label(t("Nombre"), 14, MUTED, true);
        nameLabel.setPadding(0, d(18), 0, d(7));
        body.addView(nameLabel);
        EditText name = metadataField(existing == null ? "" : existing.optString("name", ""), t("Nombre del perfil"), false);
        name.setSingleLine(true);
        body.addView(name, new LinearLayout.LayoutParams(-1, d(50)));
        LinearLayout kidPanel = new LinearLayout(this);
        kidPanel.setOrientation(LinearLayout.VERTICAL);
        kidPanel.setPadding(d(14), d(7), d(14), d(10));
        kidPanel.setBackground(gradientRound(new int[]{0xFF243F66, 0xFF56366F}, d(16)));
        LinearLayout.LayoutParams kidPanelParams = new LinearLayout.LayoutParams(-1, -2);
        kidPanelParams.topMargin = d(16);
        body.addView(kidPanel, kidPanelParams);
        CheckBox kidToggle = new CheckBox(this);
        kidToggle.setText(t("Perfil infantil"));
        kidToggle.setTextColor(Color.WHITE);
        kidToggle.setTextSize(16);
        kidToggle.setTypeface(activeProfileTypeface(true));
        if (Build.VERSION.SDK_INT >= 21) kidToggle.setButtonTintList(
                android.content.res.ColorStateList.valueOf(0xFFFFD166));
        kidToggle.setChecked(existing != null && existing.optBoolean("is_kid", false));
        save.setOnClickListener(v -> saveProfile(existing, name.getText().toString(), kidToggle.isChecked()));
        kidPanel.addView(kidToggle, new LinearLayout.LayoutParams(-1, d(48)));
        TextView kidDescription = label(t("Oculta títulos de Guerra, Crimen, Drama, Terror y Western, salvo los que también incluyan Familia."),
                12, 0xFFE5E7FF, false);
        kidDescription.setPadding(d(4), 0, d(4), d(4));
        kidPanel.addView(kidDescription);
        TextView avatarLabel = label(t("Elige un avatar"), 14, MUTED, true);
        avatarLabel.setPadding(0, d(20), 0, d(10));
        body.addView(avatarLabel);
        List<JSONObject> options = profileAvatarOptions();
        List<View> cells = new ArrayList<>();
        EditText avatarSearch = metadataField("", t("Buscar entre los pósteres"), false);
        avatarSearch.setSingleLine(true);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, d(48));
        searchParams.bottomMargin = d(12);
        body.addView(avatarSearch, searchParams);
        LinearLayout avatarGrid = new LinearLayout(this);
        avatarGrid.setOrientation(LinearLayout.VERTICAL);
        avatarGrid.setClipChildren(false);
        body.addView(avatarGrid, new LinearLayout.LayoutParams(-1, -2));
        renderAvatarGrid(avatarGrid, options, cells, "");
        avatarSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderAvatarGrid(avatarGrid, options, cells, s.toString());
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        io.execute(() -> {
            try {
                JSONArray remote = getArray("/api/v1/profile-avatars");
                runOnUiThread(() -> {
                    if (avatarGrid.getParent() == null || !profileEditorVisible) return;
                    Set<String> known = new HashSet<>();
                    for (JSONObject option : options) known.add(option.optString("avatar", ""));
                    for (int i = 0; i < remote.length(); i++) {
                        JSONObject option = remote.optJSONObject(i);
                        if (option != null && known.add(option.optString("avatar", ""))) options.add(option);
                    }
                    renderAvatarGrid(avatarGrid, options, cells, avatarSearch.getText().toString());
                });
            } catch (Exception ignored) { }
        });
    }

    void renderAvatarGrid(LinearLayout grid, List<JSONObject> options, List<View> cells, String query) {
        grid.removeAllViews();
        cells.clear();
        String needle = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        List<JSONObject> matches = new ArrayList<>();
        for (JSONObject option : options) {
            String title = option.optString("title", "").toLowerCase(java.util.Locale.ROOT);
            if (needle.isEmpty() || title.contains(needle)) matches.add(option);
        }
        int columns = isTelevision() ? Math.max(6, Math.min(10,
                getResources().getDisplayMetrics().widthPixels / d(122)))
                : (getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density > 850 ? 5 : 4);
        int cellHeight = d(isTelevision() ? 142 : 112);
        int imageSize = d(isTelevision() ? 94 : 62);
        for (int r = 0; r * columns < matches.size(); r++) {
            LinearLayout line = new LinearLayout(this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setClipChildren(false);
            grid.addView(line, new LinearLayout.LayoutParams(-1, cellHeight));
            for (int c = 0; c < columns; c++) {
                int index = r * columns + c;
                if (index >= matches.size()) break;
                JSONObject option = matches.get(index);
                String avatar = option.optString("avatar", "generic:0");
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(d(4), d(4), d(4), d(4));
                cell.setBackground(round(avatar.equals(selectedProfileAvatar) ? 0xB9472732 : 0x33242A38, d(12)));
                cell.setFocusable(true);
                cell.setClickable(true);
                cell.setTag(avatar);
                applyTvFocusStyle(cell, false);
                FrameLayout picture = profileAvatarView(avatar, imageSize,
                        option.optString("title", "Avatar"), false);
                LinearLayout.LayoutParams pictureParams = new LinearLayout.LayoutParams(imageSize, imageSize);
                pictureParams.bottomMargin = d(4);
                cell.addView(picture, pictureParams);
                TextView title = label(option.optString("title", "Avatar"), isTelevision() ? 11 : 10,
                        Color.WHITE, false);
                title.setGravity(Gravity.CENTER);
                title.setMaxLines(2);
                cell.addView(title, new LinearLayout.LayoutParams(-1, d(isTelevision() ? 34 : 30)));
                cells.add(cell);
                line.addView(cell, new LinearLayout.LayoutParams(0, -1, 1));
                cell.setOnClickListener(v -> {
                    selectedProfileAvatar = avatar;
                    for (View entry : cells) {
                        String value = String.valueOf(entry.getTag());
                        entry.setBackground(round(value.equals(selectedProfileAvatar)
                                ? 0xB9472732 : 0x33242A38, d(12)));
                    }
                });
            }
        }
        if (matches.isEmpty()) {
            TextView empty = label("No se encontraron pósteres.", 13, MUTED, false);
            empty.setPadding(d(8), d(16), d(8), d(16));
            grid.addView(empty);
        }
    }

    void saveProfile(JSONObject existing, String rawName, boolean isKid) {
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
            profile.put("is_kid", isKid);
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
        } else if (id.equals(activeProfileId())) {
            buildShell("Perfiles");
            renderProfileManager();
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
        if (array == null) return result;
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

    void sortAlphabetically(List<JSONObject> nodes) {
        java.text.Collator collator = java.text.Collator.getInstance(java.util.Locale.forLanguageTag("es"));
        collator.setStrength(java.text.Collator.PRIMARY);
        Collections.sort(nodes, (left, right) -> collator.compare(displayTitle(left), displayTitle(right)));
    }

    char alphabetInitial(String title) {
        String normalized = Normalizer.normalize(title == null ? "" : title.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toUpperCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) return '#';
        char first = normalized.charAt(0);
        return first >= 'A' && first <= 'Z' ? first : '#';
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
                if (parentId == null) sortAlphabetically(rows);
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
        List<JSONObject> continueItems = new ArrayList<>();
        Set<String> includedSeries = new HashSet<>();
        Set<String> includedStreams = new HashSet<>();
        for (JSONObject item : resume) {
            if (!isKidAllowedProgress(item)) continue;
            JSONObject card = decorateResumeItem(item, false);
            String stream = item.optString("stream", item.optString("path", ""));
            String seriesId = card.optString("series_id", "");
            if (!stream.isEmpty() && !includedStreams.add(stream)) continue;
            if (!seriesId.isEmpty() && !includedSeries.add(seriesId)) continue;
            continueItems.add(card);
        }
        List<JSONObject> suggestions = readSeriesNextEpisodes();
        JSONObject legacyNext = readNextEpisode();
        if (legacyNext != null) suggestions.add(legacyNext);
        for (JSONObject suggestion : suggestions) {
            if (!isKidAllowedProgress(suggestion)) continue;
            String stream = suggestion.optString("stream", suggestion.optString("stream_url", ""));
            String mediaId = suggestion.optString("media_id", suggestion.optString("id", ""));
            JSONObject card = decorateResumeItem(suggestion, true);
            String seriesId = card.optString("series_id", "");
            if ((!stream.isEmpty() && includedStreams.contains(stream))
                    || (!seriesId.isEmpty() && includedSeries.contains(seriesId))
                    || (!mediaId.isEmpty() && isMediaSeen(suggestion))) continue;
            continueItems.add(card);
            if (!stream.isEmpty()) includedStreams.add(stream);
            if (!seriesId.isEmpty()) includedSeries.add(seriesId);
        }
        Collections.sort(continueItems, (a, b) -> Long.compare(
                resumeItemUpdated(b), resumeItemUpdated(a)));
        if (!continueItems.isEmpty()) addResumeRow("Seguir viendo", continueItems);
        List<JSONObject> favorites = filterKidCatalog(readFavorites());
        List<JSONObject> visibleMovies = filterKidCatalog(movies);
        List<JSONObject> visibleSeries = filterKidCatalog(series);
        if (!favorites.isEmpty()) addRow("Mi lista", favorites);
        List<JSONObject> pendingItems = new ArrayList<>();
        Set<String> pendingIds = new HashSet<>();
        for (JSONObject node : visibleMovies)
            collectPendingHomeItems(node, "", pendingItems, pendingIds);
        for (JSONObject node : visibleSeries)
            collectPendingHomeItems(node, "", pendingItems, pendingIds);
        List<JSONObject> catalogItems = new ArrayList<>();
        for (JSONObject node : visibleMovies)
            if (!node.optBoolean("is_placeholder") && "movie".equals(node.optString("kind")))
                catalogItems.add(node);
        for (JSONObject node : visibleSeries)
            if (!node.optBoolean("is_placeholder") && "series".equals(node.optString("kind")))
                catalogItems.add(node);
        sortNewestFirst(catalogItems);
        addRow("Lo más nuevo", catalogItems);
        sortPendingHomeItems(pendingItems);
        addRow("Pendientes de añadir", pendingItems);
        for (String genre : homeGenres(catalogItems)) {
            List<JSONObject> genreItems = new ArrayList<>();
            for (JSONObject node : catalogItems) {
                JSONArray genres = node.optJSONArray("genres");
                if (genres == null) continue;
                for (int i = 0; i < genres.length(); i++) {
                    if (normalizeGenre(genre).equals(normalizeGenre(genres.optString(i, "")))) {
                        genreItems.add(node);
                        break;
                    }
                }
            }
            addRow(genreLabel(genre), genreItems);
        }
        if (visibleMovies.isEmpty() && visibleSeries.isEmpty()) {
            TextView empty = label("Tu biblioteca está vacía. Sincroniza el catálogo en el servidor.", 16, MUTED, false);
            empty.setPadding(d(24), d(36), d(24), d(32));
            content.addView(empty);
        }
        restoreListingScrollIfPending();
        backfillMissingSeriesSuggestions();
    }

    void collectPendingHomeItems(JSONObject node, String parentSeriesTitle,
                                 List<JSONObject> output, Set<String> includedIds) {
        if (node == null) return;
        if (node.optBoolean("is_placeholder")) {
            String id = node.optString("id", "");
            if (!id.isEmpty() && includedIds.add(id)) {
                JSONObject card;
                try { card = new JSONObject(node.toString()); }
                catch (JSONException ignored) { card = node; }
                if ("season".equals(card.optString("kind")) && !parentSeriesTitle.isEmpty()) {
                    try { card.put("series_title", parentSeriesTitle); }
                    catch (JSONException ignored) { }
                }
                output.add(card);
            }
            return;
        }
        String seriesTitle = "series".equals(node.optString("kind"))
                ? displayTitle(node) : parentSeriesTitle;
        JSONArray children = node.optJSONArray("children");
        if (children == null) return;
        for (int i = 0; i < children.length(); i++)
            collectPendingHomeItems(children.optJSONObject(i), seriesTitle, output, includedIds);
    }

    void sortPendingHomeItems(List<JSONObject> nodes) {
        java.text.Collator collator = java.text.Collator.getInstance(
                java.util.Locale.forLanguageTag("eu".equals(prefs.getString("language", "es")) ? "eu" : "es"));
        collator.setStrength(java.text.Collator.PRIMARY);
        Collections.sort(nodes, (left, right) -> {
            String leftTitle = left.optString("series_title", "") + " " + displayTitle(left);
            String rightTitle = right.optString("series_title", "") + " " + displayTitle(right);
            return collator.compare(leftTitle.trim(), rightTitle.trim());
        });
    }

    List<String> homeGenres(List<JSONObject> nodes) {
        Map<String, String> unique = new HashMap<>();
        for (JSONObject node : nodes) {
            JSONArray genres = node.optJSONArray("genres");
            if (genres == null) continue;
            for (int i = 0; i < genres.length(); i++) {
                String genre = genres.optString(i, "").trim();
                String normalized = normalizeGenre(genre);
                if (genre.isEmpty() || normalized.isEmpty()
                        || (isActiveKidProfile() && isKidBlockedGenre(normalized))) continue;
                if (!unique.containsKey(normalized)) unique.put(normalized, genre);
            }
        }
        List<String> result = new ArrayList<>(unique.values());
        java.text.Collator collator = java.text.Collator.getInstance(
                java.util.Locale.forLanguageTag("eu".equals(prefs.getString("language", "es")) ? "eu" : "es"));
        collator.setStrength(java.text.Collator.PRIMARY);
        Collections.sort(result, (left, right) -> collator.compare(genreLabel(left), genreLabel(right)));
        return result;
    }

    long resumeItemUpdated(JSONObject item) {
        return item.optLong("updated", item.optLong("suggested_at", 0L));
    }

    void backfillMissingSeriesSuggestions() {
        String profileId = activeProfileId();
        if (profileId.isEmpty() || seriesSuggestionBackfillRunning) return;
        String timestampKey = "series_suggestion_backfill_v2_ms:" + profileId;
        if (System.currentTimeMillis() - prefs.getLong(timestampKey, 0L) < 5 * 60 * 1000L) return;
        List<JSONObject> allSeries = new ArrayList<>();
        for (JSONObject root : series) collectSeriesNodes(root, allSeries);
        if (allSeries.isEmpty()) return;
        Set<String> seenFileIds = new HashSet<>();
        String seenPrefix = "p:" + profileId + ":seen:file:";
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (entry.getKey().startsWith(seenPrefix) && Boolean.TRUE.equals(entry.getValue()))
                seenFileIds.add(entry.getKey().substring(seenPrefix.length()));
        }
        if (seenFileIds.isEmpty()) return;
        seriesSuggestionBackfillRunning = true;
        io.execute(() -> {
            boolean succeeded = false;
            boolean checked = false;
            try {
                Set<String> startedSeriesIds = new HashSet<>();
                int offset = 0;
                JSONArray catalogItems;
                do {
                    catalogItems = getArray("/api/v1/items?limit=500&offset=" + offset);
                    for (int i = 0; i < catalogItems.length(); i++) {
                        JSONObject file = catalogItems.optJSONObject(i);
                        if (file != null && seenFileIds.contains(file.optString("id"))) {
                            String id = file.optString("series_id", "");
                            if (!id.isEmpty() && !"null".equals(id)) startedSeriesIds.add(id);
                        }
                    }
                    offset += catalogItems.length();
                    checked = true;
                } while (catalogItems.length() == 500);
                for (JSONObject summary : allSeries) {
                    String seriesId = summary.optString("id", "");
                    if (seriesId.isEmpty() || !startedSeriesIds.contains(seriesId)) continue;
                    String suggestionKey = "series_next_episode:" + profileId + ":" + seriesId;
                    JSONObject fullSeries = getJson("/api/v1/catalog/" + enc(seriesId));
                    checked = true;
                    List<JSONObject> seasons = toList(fullSeries.optJSONArray("children"));
                    Collections.sort(seasons, Comparator.comparingInt(value -> value.optInt("season_number")));
                    boolean hasActiveEpisode = false;
                    int lastSeenIndex = -1;
                    List<JSONObject> orderedFiles = new ArrayList<>();
                    List<JSONObject> orderedSeasons = new ArrayList<>();
                    for (JSONObject seasonSummary : seasons) {
                        String seasonId = seasonSummary.optString("id", "");
                        if (seasonId.isEmpty()) continue;
                        JSONObject fullSeason = getJson("/api/v1/catalog/" + enc(seasonId));
                        JSONArray files = fullSeason.optJSONArray("files");
                        if (files == null) continue;
                        for (int i = 0; i < files.length(); i++) {
                            JSONObject file = files.optJSONObject(i);
                            if (file == null) continue;
                            orderedFiles.add(file);
                            orderedSeasons.add(fullSeason);
                            int episodeIndex = orderedFiles.size() - 1;
                            if (isMediaSeen(file)) {
                                lastSeenIndex = episodeIndex;
                            } else {
                                long position = mediaProgressPosition(file);
                                if (position > 10000L) hasActiveEpisode = true;
                            }
                        }
                    }
                    if (hasActiveEpisode) continue;
                    int nextIndex = lastSeenIndex + 1;
                    if (lastSeenIndex < 0 || nextIndex >= orderedFiles.size()) {
                        SharedPreferences.Editor editor = prefs.edit().remove(suggestionKey);
                        String global = prefs.getString("next_episode:" + profileId, "");
                        try {
                            if (!global.isEmpty() && seriesId.equals(new JSONObject(global).optString("series_id")))
                                editor.remove("next_episode:" + profileId);
                        } catch (JSONException ignored) { }
                        editor.apply();
                        continue;
                    }
                    JSONObject nextFile = orderedFiles.get(nextIndex);
                    JSONObject nextSeason = orderedSeasons.get(nextIndex);
                    JSONObject lastSeenSeason = orderedSeasons.get(lastSeenIndex);
                    JSONObject suggestion = nextEpisodePayload(nextFile, nextSeason);
                    if (suggestion == null) continue;
                    suggestion.put("series_id", seriesId);
                    suggestion.put("suggestion_type", !lastSeenSeason.optString("id").equals(nextSeason.optString("id"))
                            ? "next_season" : "next_episode");
                    String existing = prefs.getString(suggestionKey, "");
                    long suggestionTime = System.currentTimeMillis();
                    try {
                        JSONObject previous = new JSONObject(existing);
                        if (nextFile.optString("id").equals(previous.optString("media_id")))
                            suggestionTime = previous.optLong("updated", suggestionTime);
                    } catch (JSONException ignored) { }
                    suggestion.put("updated", suggestionTime);
                    prefs.edit().putString(suggestionKey, suggestion.toString())
                            .putString("next_episode:" + profileId, suggestion.toString()).apply();
                    succeeded = true;
                }
            } catch (Exception ignored) { }
            if (checked) prefs.edit().putLong(timestampKey, System.currentTimeMillis()).apply();
            seriesSuggestionBackfillRunning = false;
            if (succeeded) runOnUiThread(() -> {
                if ("home".equals(selectedSection) && !showingDetail && !showingAdmin)
                    renderHome();
            });
        });
    }

    void collectSeriesNodes(JSONObject node, List<JSONObject> output) {
        if (node == null) return;
        if ("series".equals(node.optString("kind"))) {
            output.add(node);
            return;
        }
        JSONArray children = node.optJSONArray("children");
        if (children == null) return;
        for (int i = 0; i < children.length(); i++) collectSeriesNodes(children.optJSONObject(i), output);
    }

    JSONObject decorateResumeItem(JSONObject item, boolean nextEpisode) {
        JSONObject decorated;
        try { decorated = new JSONObject(item.toString()); }
        catch (JSONException ignored) { decorated = new JSONObject(); }
        String seasonId = item.optString("owner_node_id", item.optString("node_id", ""));
        JSONObject seriesNode = findSeriesForSeason(seasonId);
        if (seriesNode != null) {
            try {
                if (decorated.optString("series_id", "").isEmpty())
                    decorated.put("series_id", seriesNode.optString("id", ""));
                decorated.put("series_title", displayTitle(seriesNode));
                decorated.put("series_poster_url", posterField(seriesNode, "poster_url"));
                decorated.put("episode_title", item.optString("title", ""));
                JSONObject seasonNode = findSeasonNode(seriesNode, seasonId);
                if (seasonNode != null)
                    decorated.put("season_poster_url", posterField(seasonNode, "poster_url"));
            } catch (JSONException ignored) { }
        }
        if (decorated.optString("episode_title", "").isEmpty()) {
            try { decorated.put("episode_title", item.optString("title", "")); }
            catch (JSONException ignored) { }
        }
        try { decorated.put("next_episode_row", nextEpisode); }
        catch (JSONException ignored) { }
        return decorated;
    }

    String posterField(JSONObject node, String key) {
        String value = node == null ? "" : node.optString(key, "").trim();
        return "null".equalsIgnoreCase(value) ? "" : value;
    }

    JSONObject findSeasonNode(JSONObject seriesNode, String seasonId) {
        JSONArray seasons = seriesNode == null ? null : seriesNode.optJSONArray("children");
        if (seasons == null) return null;
        for (int i = 0; i < seasons.length(); i++) {
            JSONObject season = seasons.optJSONObject(i);
            if (season != null && seasonId.equals(season.optString("id", ""))) return season;
        }
        return null;
    }

    JSONObject findSeriesForSeason(String seasonId) {
        if (seasonId.isEmpty()) return null;
        for (JSONObject root : series) {
            JSONObject match = findSeriesForSeason(root, seasonId);
            if (match != null) return match;
        }
        return null;
    }

    JSONObject findSeriesForSeason(JSONObject node, String seasonId) {
        if ("series".equals(node.optString("kind"))) {
            JSONArray seasons = node.optJSONArray("children");
            if (seasons != null) {
                for (int i = 0; i < seasons.length(); i++) {
                    JSONObject season = seasons.optJSONObject(i);
                    if (season != null && seasonId.equals(season.optString("id"))) return node;
                }
            }
            return null;
        }
        JSONArray children = node.optJSONArray("children");
        if (children == null) return null;
        for (int i = 0; i < children.length(); i++) {
            JSONObject child = children.optJSONObject(i);
            if (child == null) continue;
            JSONObject found = findSeriesForSeason(child, seasonId);
            if (found != null) return found;
        }
        return null;
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
        String title = cleanDisplayText(node.optString("display_title", node.optString("title", "Zinema")));
        if (title.isEmpty()) title = cleanDisplayText(node.optString("title", "Zinema"));
        if (node.optString("kind").equals("season")) {
            if (node.optBoolean("is_placeholder") && node.optInt("season_number") <= 0)
                return t("Capítulos pendientes");
            return t("Temporada") + " " + String.format("%02d", node.optInt("season_number"));
        }
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
        horizontal.setClipChildren(false);
        horizontal.setClipToPadding(false);
        horizontal.setTag("zinema-lazy-posters");
        LinearLayout cards = new LinearLayout(this);
        cards.setClipChildren(false);
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
        return posterCard(node, false, shouldShowSeriesFlag());
    }

    View posterCard(JSONObject node, boolean lazyPosters) {
        return posterCard(node, lazyPosters, shouldShowSeriesFlag());
    }

    boolean shouldShowSeriesFlag() {
        return !(libraryMode && !showingDetail && "series".equals(selectedSection)
                && breadcrumbs.isEmpty());
    }

    View seriesCornerMarker() {
        return new View(this) {
            final Paint flagPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                float w = getWidth();
                float h = getHeight();
                flagPaint.setColor(0xFF8067D5);
                flagPaint.setStyle(Paint.Style.FILL);
                Path corner = new Path();
                corner.moveTo(0, 0);
                corner.lineTo(w, 0);
                corner.lineTo(0, h);
                corner.close();
                canvas.drawPath(corner, flagPaint);
                flagPaint.setColor(Color.WHITE);
                flagPaint.setStyle(Paint.Style.STROKE);
                flagPaint.setStrokeWidth(d(1.5f));
                flagPaint.setStrokeCap(Paint.Cap.ROUND);
                float left = d(7);
                float top = d(8);
                float right = d(19);
                float bottom = d(16);
                canvas.drawRoundRect(left, top, right, bottom, d(1.5f), d(1.5f), flagPaint);
                canvas.drawLine(left + d(3), top - d(3), left + d(5), top, flagPaint);
                canvas.drawLine(right - d(3), top - d(3), right - d(5), top, flagPaint);
                canvas.drawLine(left + d(6), bottom + d(1), right - d(6), bottom + d(1), flagPaint);
                flagPaint.setStyle(Paint.Style.FILL);
            }
        };
    }

    View posterCard(JSONObject node, boolean lazyPosters, boolean showSeriesFlag) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        boolean placeholder = node.optBoolean("is_placeholder");
        card.setFocusable(!placeholder);
        card.setClickable(!placeholder);
        card.setLongClickable(!placeholder);
        card.setEnabled(!placeholder);
        if (placeholder) card.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (!placeholder) applyTvFocusStyle(card, true);
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
        if (placeholder) {
            View shade = new View(this);
            shade.setBackgroundColor(0x99090A0D);
            art.addView(shade, new FrameLayout.LayoutParams(-1, -1));
            ImageView stampImage = new ImageView(this);
            stampImage.setImageResource(R.drawable.pending_request_stamp);
            stampImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
            stampImage.setAlpha(0.32f);
            stampImage.setRotation(-5f);
            FrameLayout.LayoutParams stampParams = new FrameLayout.LayoutParams(
                    d(180), d(180), Gravity.RIGHT | Gravity.BOTTOM);
            art.addView(stampImage, stampParams);
            stampImage.setTranslationX(d(58));
            stampImage.setTranslationY(d(48));
        }
        if (isCollection) {
            TextView collectionIcon = label("▦", 17, Color.WHITE, true);
            collectionIcon.setGravity(Gravity.CENTER);
            collectionIcon.setContentDescription(t("Colección"));
            FrameLayout.LayoutParams collectionParams = new FrameLayout.LayoutParams(d(34), d(34), Gravity.TOP | Gravity.LEFT);
            collectionParams.setMargins(d(6), d(6), 0, 0);
            art.addView(collectionIcon, collectionParams);
        }
        if ("series".equals(node.optString("kind")) && node.optBoolean("incomplete")) {
            TextView incomplete = label(t("INCOMPLETA"), 9, 0xFF211600, true);
            incomplete.setGravity(Gravity.CENTER);
            incomplete.setPadding(d(8), d(6), d(8), d(6));
            incomplete.setBackground(round(0xFFFFC857, d(9)));
            FrameLayout.LayoutParams incompleteParams = new FrameLayout.LayoutParams(
                    -2, -2, Gravity.TOP | Gravity.RIGHT);
            incompleteParams.setMargins(0, d(7), d(7), 0);
            art.addView(incomplete, incompleteParams);
        }
        if (showSeriesFlag && "series".equals(node.optString("kind"))) {
            View seriesMarker = seriesCornerMarker();
            seriesMarker.setContentDescription(t("Serie"));
            FrameLayout.LayoutParams seriesParams = new FrameLayout.LayoutParams(d(36), d(36),
                    Gravity.TOP | Gravity.LEFT);
            art.addView(seriesMarker, seriesParams);
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
        if (!placeholder) card.setOnLongClickListener(v -> { showActions(node); return true; });
        boolean seenNode = "episode".equals(node.optString("kind"))
                ? isMediaSeen(node) : isNodeSeen(node);
        if (seenNode) {
            TextView seen = label("✓ " + t("VISTO"), 10, Color.WHITE, true);
            seen.setGravity(Gravity.CENTER);
            seen.setPadding(d(8), d(5), d(8), d(5));
            seen.setBackground(round(0xDD22936F, d(10)));
            FrameLayout.LayoutParams seenParams = new FrameLayout.LayoutParams(-2, -2, Gravity.LEFT | Gravity.BOTTOM);
            seenParams.setMargins(d(7), 0, 0, d(7));
            art.addView(seen, seenParams);
        }
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(width, -2);
        cp.setMargins(0, 0, d(12), 0);
        if (!placeholder) card.setOnClickListener(v -> openNode(node));
        View wrapped = wrap(card, cp);
        wrapped.setTag("catalog-node:" + node.optString("id", ""));
        return wrapped;
    }

    View wrap(View view, LinearLayout.LayoutParams params) {
        view.setLayoutParams(params);
        return view;
    }

    String cardSubtitle(JSONObject node) {
        if (node.optBoolean("is_placeholder")) {
            if ("season".equals(node.optString("kind"))) {
                String seriesTitle = node.optString("series_title", "");
                String waiting = t("A la espera de ser añadido");
                return seriesTitle.isEmpty() ? waiting : seriesTitle + " · " + waiting;
            }
            String kind = "movie".equals(node.optString("kind")) ? t("Película") : t("Serie");
            String year = node.isNull("year") ? "" : node.optString("year", "");
            String subtitle = kind + (year.isEmpty() ? "" : " · " + year);
            int season = node.optInt("season_number");
            if (season > 0) subtitle += " · " + t("Temporada") + " " + season;
            return subtitle;
        }
        String kind = node.optString("kind");
        if (kind.equals("series")) {
            JSONArray children = node.optJSONArray("children");
            int seasons = 0;
            int pendingSeasons = 0;
            if (children != null) {
                for (int i = 0; i < children.length(); i++) {
                    JSONObject child = children.optJSONObject(i);
                    if (child != null && "season".equals(child.optString("kind"))) {
                        if (child.optBoolean("is_placeholder")) pendingSeasons++;
                        else seasons++;
                    }
                }
            }
            String summary = seasons + (seasons == 1 ? " " + t("temporada") : " " + t("temporadas"));
            if (pendingSeasons > 0)
                summary += " · " + pendingSeasons + " " + t("Pendiente de añadir").toLowerCase();
            return summary;
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

    String incompleteSummary(JSONObject node) {
        if (!"series".equals(node.optString("kind")) || !node.optBoolean("incomplete")) return "";
        int seasons = node.optJSONArray("missing_seasons") == null
                ? 0 : node.optJSONArray("missing_seasons").length();
        int episodes = node.optInt("missing_episode_count");
        ArrayList<String> parts = new ArrayList<>();
        if (seasons > 0) parts.add(seasons + " " + t(seasons == 1 ? "temporada" : "temporadas"));
        if (episodes > 0) parts.add(episodes + " " + t(episodes == 1 ? "capítulo" : "capítulos"));
        if (parts.isEmpty()) return t("Serie incompleta");
        return t("Faltan") + " " + android.text.TextUtils.join(" · ", parts);
    }

    GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    GradientDrawable gradientRound(int[] colors, int radius) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
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
        loadImage(path, target, backdrop, "");
    }

    void loadImage(String path, ImageView target, boolean backdrop, String fallbackPath) {
        if (path == null || path.trim().isEmpty() || "null".equalsIgnoreCase(path.trim())) {
            deferredImageRequests.remove(target);
            requestedImages.remove(target);
            target.setTag(null);
            if (fallbackPath != null && !fallbackPath.trim().isEmpty()
                    && !"null".equalsIgnoreCase(fallbackPath.trim()))
                loadImage(fallbackPath, target, backdrop, "");
            else target.setBackground(round(PANEL, d(8)));
            return;
        }
        String url = path.startsWith("http") ? path : server + (path.startsWith("/") ? path : "/" + path);
        if (url.equals(requestedImages.get(target))) return;
        if (!isImageVisible(target)) {
            requestedImages.remove(target);
            target.setImageDrawable(null);
            target.setTag(url);
            deferredImageRequests.put(target, new String[]{path, Boolean.toString(backdrop),
                    fallbackPath == null ? "" : fallbackPath});
            watchDeferredImageScroll(target);
            target.post(() -> loadDeferredImageIfVisible(target));
            return;
        }
        deferredImageRequests.remove(target);
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
                if (targets == null) return;
                if (result != null) imageCache.put(url, result);
                for (ImageView image : targets) {
                    if (!url.equals(image.getTag())) continue;
                    if (result != null) image.setImageBitmap(result);
                    else if (fallbackPath != null && !fallbackPath.isEmpty()
                            && !fallbackPath.equals(path))
                        loadImage(fallbackPath, image, backdrop, "");
                    else image.setBackground(round(PANEL, d(8)));
                }
            });
        });
    }

    boolean isImageVisible(ImageView target) {
        if (target == null || target.getVisibility() != View.VISIBLE || target.getAlpha() <= 0f) return false;
        Rect visible = new Rect();
        return target.getGlobalVisibleRect(visible) && visible.width() > 0 && visible.height() > 0;
    }

    void watchDeferredImageScroll(ImageView target) {
        if (target == null || !lazyImageScrollWatchers.add(target)) return;
        android.view.ViewTreeObserver.OnScrollChangedListener scrollListener =
                () -> loadDeferredImageIfVisible(target);
        target.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                if (target.getViewTreeObserver().isAlive())
                    target.getViewTreeObserver().addOnScrollChangedListener(scrollListener);
                loadDeferredImageIfVisible(target);
            }
            @Override public void onViewDetachedFromWindow(View view) {
                if (scrollListener != null && target.getViewTreeObserver().isAlive())
                    target.getViewTreeObserver().removeOnScrollChangedListener(scrollListener);
            }
        });
        if (target.isAttachedToWindow()) {
            if (target.getViewTreeObserver().isAlive())
                target.getViewTreeObserver().addOnScrollChangedListener(scrollListener);
        }
    }

    void loadDeferredImageIfVisible(ImageView target) {
        String[] request = deferredImageRequests.get(target);
        if (request == null || !isImageVisible(target)) return;
        loadImage(request[0], target, Boolean.parseBoolean(request[1]), request[2]);
    }

    void showPoster(String path, ImageView target, boolean lazy) {
        if (!lazy) {
            loadImage(path, target, false);
            return;
        }
        if (path == null || path.trim().isEmpty() || "null".equalsIgnoreCase(path.trim())) {
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
        View root = page == null ? content : page;
        if (root != null) loadVisibleLazyRowsIn(root);
    }

    void loadVisibleLazyRowsIn(View view) {
        if (view instanceof HorizontalScrollView &&
                ("zinema-lazy-posters".equals(view.getTag()) || "zinema-lazy-cast".equals(view.getTag()))) {
            loadLazyRow((HorizontalScrollView) view);
            return;
        }
        if (view instanceof ImageView) {
            ImageView image = (ImageView) view;
            if (deferredImageRequests.containsKey(image)) {
                loadDeferredImageIfVisible(image);
            } else if (!requestedImages.containsKey(image)) {
                Object value = image.getTag();
                if (value instanceof String && ((String) value).startsWith("http")) {
                    if (isImageVisible(image)) loadImage((String) value, image, false);
                }
            }
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
        nodes = filterKidCatalog(nodes);
        currentListingTitle = title;
        currentListingSubtitle = subtitle;
        currentListingNodes = nodes == null ? new ArrayList<>() : new ArrayList<>(nodes);
        content.removeAllViews();
        boolean showAlphabet = breadcrumbs.isEmpty() && libraryMode
                && ("movies".equals(selectedSection) || "series".equals(selectedSection));
        boolean showGenreFilter = showAlphabet
                && ("movies".equals(selectedSection) || "series".equals(selectedSection));
        List<JSONObject> alphabetized = new ArrayList<>(nodes);
        if (showAlphabet) sortAlphabetically(alphabetized);
        List<JSONObject> visibleNodes = nodes;
        if (showAlphabet) visibleNodes = alphabetized;
        Set<String> selected = null;
        if (showGenreFilter) {
            String library = selectedSection;
            selected = selectedGenresByLibrary.get(library);
            if (selected == null) {
                selected = new HashSet<>();
                selectedGenresByLibrary.put(library, selected);
            }
            visibleNodes = filterByGenres(alphabetized, selected);
            addLibraryListingHeader(title, subtitle, library, nodes, selected);
        } else {
            addSectionHeader(title, subtitle);
        }
        addGrid(visibleNodes);
        boolean showAlphabetRail = showAlphabet && alphabetVisible;
        content.setPadding(0, 0, showAlphabetRail ? d(42) : 0, d(28));
        if (showAlphabetRail) addAlphabetRail(); else removeAlphabetRail();
        if (alphabetToggleButton != null) {
            alphabetToggleButton.setVisibility(showAlphabet ? View.VISIBLE : View.GONE);
            alphabetToggleButton.setAlpha(alphabetVisible ? 1f : 0.72f);
            alphabetToggleButton.setSelected(alphabetVisible);
            updateAlphabetToggleAppearance();
        }
        if (visibleNodes.isEmpty()) {
            addEmpty(showGenreFilter && selected != null && !selected.isEmpty()
                    ? "No hay títulos que coincidan con los géneros seleccionados."
                    : "No hay títulos en esta sección.");
        }
        restoreListingScrollIfPending();
    }

    void toggleAlphabet() {
        if (currentListingNodes == null || contentScroller == null) return;
        alphabetVisible = !alphabetVisible;
        pendingListingRestoreY = contentScroller.getScrollY();
        renderListing(currentListingTitle, currentListingSubtitle, currentListingNodes);
    }

    void updateAlphabetToggleAppearance() {
        if (!(alphabetToggleButton instanceof ViewGroup)) return;
        ViewGroup button = (ViewGroup) alphabetToggleButton;
        if (button.getChildCount() < 2 || !(button.getChildAt(0) instanceof TextView)) return;
        TextView label = (TextView) button.getChildAt(0);
        label.setTextColor(alphabetVisible ? Color.WHITE : MUTED);
        View indicator = button.getChildAt(1);
        indicator.setBackground(round(alphabetVisible ? RED : Color.TRANSPARENT, d(2)));
    }

    void restoreListingScrollIfPending() {
        if (pendingListingRestoreY < 0 || contentScroller == null) return;
        int restoreY = pendingListingRestoreY;
        pendingListingRestoreY = -1;
        contentScroller.post(() -> contentScroller.scrollTo(0, restoreY));
    }

    void addAlphabetRail() {
        removeAlphabetRail();
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackground(round(0xBB151923, d(18)));
        LinearLayout letters = new LinearLayout(this);
        letters.setOrientation(LinearLayout.VERTICAL);
        letters.setPadding(d(3), d(6), d(3), d(6));
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ#";
        for (int i = 0; i < alphabet.length(); i++) {
            char letter = alphabet.charAt(i);
            boolean available = alphabetAnchors.containsKey(letter);
            TextView key = labelRaw(String.valueOf(letter), 11,
                    available ? Color.WHITE : 0xFF666B77, true);
            key.setGravity(Gravity.CENTER);
            key.setFocusable(available);
            key.setClickable(available);
            if (available) {
                key.setOnClickListener(v -> {
                    View anchor = alphabetAnchors.get(letter);
                    if (anchor != null && contentScroller != null) {
                        contentScroller.smoothScrollTo(0, viewTopInContent(anchor));
                        anchor.requestFocus();
                    }
                });
                applyTvFocusStyle(key, false);
            }
            letters.addView(key, new LinearLayout.LayoutParams(-1, d(21)));
        }
        scroll.addView(letters);
        int railHeight = Math.max(d(200), Math.min(d(620),
                getResources().getDisplayMetrics().heightPixels - d(120)));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(d(38), railHeight,
                Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        params.rightMargin = d(5);
        frame.addView(scroll, params);
        alphabetRail = scroll;
    }

    void removeAlphabetRail() {
        if (alphabetRail != null && alphabetRail.getParent() instanceof ViewGroup)
            ((ViewGroup) alphabetRail.getParent()).removeView(alphabetRail);
        alphabetRail = null;
    }

    int viewTopInContent(View target) {
        int top = 0;
        View current = target;
        while (current != null && current != content) {
            top += current.getTop();
            if (!(current.getParent() instanceof View)) break;
            current = (View) current.getParent();
        }
        return Math.max(0, top);
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
            if (node.optBoolean("is_placeholder")) {
                filtered.add(node);
                continue;
            }
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
        alphabetAnchors.clear();
        boolean reserveAlphabetRail = alphabetVisible && libraryMode && !showingDetail
                && breadcrumbs.isEmpty()
                && ("movies".equals(selectedSection) || "series".equals(selectedSection));
        int screenWidthDp = Math.round(getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density);
        int availableWidthDp = screenWidthDp - 32 - (reserveAlphabetRail ? 42 : 0);
        int columns = isTelevision()
                ? Math.max(2, Math.min(6, availableWidthDp / 160))
                : (getResources().getConfiguration().orientation == 2 ? 5 : 2);
        int gridWidth = getResources().getDisplayMetrics().widthPixels - d(32)
                - (reserveAlphabetRail ? d(42) : 0);
        int cellWidth = Math.max(1, (gridWidth - d(columns * 8)) / columns);
        LinearLayout row = null;
        int count = 0;
        for (JSONObject node : nodes) {
            if (count % columns == 0) {
                row = new LinearLayout(this);
                row.setGravity(Gravity.TOP);
                row.setClipChildren(false);
                row.setPadding(d(16), 0, d(16), d(14));
                if (!nodes.isEmpty()) row.setTag("alphabet-anchor:" + alphabetInitial(displayTitle(node)));
                content.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            View card = posterCard(node);
            char initial = alphabetInitial(displayTitle(node));
            if (!alphabetAnchors.containsKey(initial)) alphabetAnchors.put(initial, card);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(cellWidth, -2);
            p.setMargins(d(4), 0, d(4), 0);
            row.addView(card, p);
            count++;
        }
    }

    void openNode(JSONObject node) {
        if (node == null || node.optBoolean("is_placeholder")) return;
        leaveStartupHomeWait();
        if (!isKidAllowedNode(node)) {
            Toast.makeText(this, t("Este contenido no está disponible en un perfil infantil."), Toast.LENGTH_LONG).show();
            return;
        }
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

    interface StyledActionHandler { void run(int selected); }

    void showStyledActionDialog(String title, List<String> options, StyledActionHandler handler) {
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(d(18), d(18), d(18), d(14));
        GradientDrawable panelBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                isActiveKidProfile()
                        ? new int[]{0xFF253B78, 0xFF663A87, 0xFF146E82}
                        : new int[]{0xFF1C2332, 0xFF211B2B});
        panelBg.setCornerRadius(d(isActiveKidProfile() ? 26 : 20));
        panel.setBackground(panelBg);
        TextView heading = label(title, 21, Color.WHITE, true);
        heading.setPadding(d(4), d(2), d(4), d(14));
        panel.addView(heading);
        for (int i = 0; i < options.size(); i++) {
            final int index = i;
            LinearLayout option = new LinearLayout(this);
            option.setGravity(Gravity.CENTER_VERTICAL);
            option.setPadding(d(14), d(4), d(14), d(4));
            GradientDrawable optionBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    isActiveKidProfile()
                            ? new int[]{0xFF3478A5, 0xFF7650A5}
                            : new int[]{0xFF342737, 0xFF252D3D});
            optionBg.setCornerRadius(d(isActiveKidProfile() ? 18 : 12));
            option.setBackground(optionBg);
            option.setFocusable(true);
            option.setClickable(true);
            TextView symbol = labelRaw("✦", 18,
                    isActiveKidProfile() ? (i % 2 == 0 ? 0xFFFFD166 : 0xFF9EF4E2)
                            : (i == 0 ? 0xFFFF858C : 0xFFB7C4E1), true);
            symbol.setGravity(Gravity.CENTER);
            option.addView(symbol, new LinearLayout.LayoutParams(d(34), d(48)));
            TextView text = label(options.get(i), 15, Color.WHITE, true);
            text.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, d(52), 1);
            textParams.leftMargin = d(8);
            option.addView(text, textParams);
            applyTvFocusStyle(option, false);
            option.setOnClickListener(v -> { dialog.dismiss(); handler.run(index); });
            LinearLayout.LayoutParams optionParams = new LinearLayout.LayoutParams(-1, -2);
            optionParams.bottomMargin = d(8);
            panel.addView(option, optionParams);
        }
        scroll.addView(panel);
        dialog.setView(scroll);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setDimAmount(0.58f);
        }
        if (isTelevision() && panel.getChildCount() > 1) panel.getChildAt(1).requestFocus();
    }

    void showActions(JSONObject node) {
        if (node == null || node.optBoolean("is_placeholder")) return;
        List<String> actions = new ArrayList<>();
        List<Runnable> handlers = new ArrayList<>();
        String kind = node.optString("kind");
        if (kind.equals("movie") || kind.equals("series")) {
            actions.add("Marcar como visto");
            handlers.add(() -> setNodeWatched(node, true));
            actions.add("Marcar como no visto");
            handlers.add(() -> setNodeWatched(node, false));
            if (kind.equals("series") && node.optBoolean("incomplete")) {
                actions.add(t("Pedir temporadas restantes"));
                handlers.add(() -> requestRemainingSeasons(node));
            }
            if (canAccessAdminTools()) {
                actions.add("Reintentar metadata (TMDb)");
                handlers.add(() -> retryMetadata(node));
            }
        }
        if (kind.equals("collection") && node.optBoolean("poster_is_assigned")) {
            boolean showingMosaic = prefs.getBoolean(collectionMosaicKey(node), false);
            actions.add(showingMosaic ? "Usar póster manual" : "Usar mosaico de pósters");
            handlers.add(() -> {
                prefs.edit().putBoolean(collectionMosaicKey(node), !showingMosaic).apply();
                refreshPosterCard(node);
            });
        }
        if (canAccessAdminTools()) {
            actions.add("Metadata manual");
            handlers.add(() -> showMetadataEditor(node, new JSONArray()));
        }
        if (actions.isEmpty()) return;
        showStyledActionDialog(t("Acciones"), actions, index -> handlers.get(index).run());
    }

    void requestRemainingSeasons(JSONObject seriesNode) {
        String seriesId = seriesNode.optString("id", "");
        if (seriesId.isEmpty() || !"series".equals(seriesNode.optString("kind"))) return;
        new AlertDialog.Builder(this)
                .setTitle(t("Pedir temporadas restantes"))
                .setMessage(t("Enviar una solicitud para las temporadas o capítulos que faltan de ")
                        + displayTitle(seriesNode) + "?")
                .setNegativeButton(t("Cancelar"), null)
                .setPositiveButton(t("Enviar solicitud"), (dialog, which) -> {
                    JSONObject payload = new JSONObject();
                    try {
                        payload.put("request_type", "remaining_seasons");
                        payload.put("catalog_node_id", seriesId);
                    } catch (JSONException ignored) { return; }
                    io.execute(() -> {
                        try {
                            postJson("/api/v1/content-requests", payload);
                            runOnUiThread(() -> Toast.makeText(this,
                                    t("Solicitud enviada a administración."), Toast.LENGTH_LONG).show());
                        } catch (Exception e) {
                            runOnUiThread(() -> Toast.makeText(this,
                                    t("No se pudo enviar la solicitud: ") + e.getMessage(),
                                    Toast.LENGTH_LONG).show());
                        }
                    });
                }).show();
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

    void refreshWatchedDisplay(JSONObject node) {
        if (showingDetail && currentDetailNode != null
                && detailNodeId.equals(currentDetailNode.optString("id", ""))) {
            renderDetail(currentDetailNode);
            return;
        }
        refreshPosterCard(node);
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
    long mediaProgressPosition(JSONObject media) {
        String stream = media.optString("stream_url", media.optString("stream", ""));
        if (stream.isEmpty()) return 0L;
        try { return new JSONObject(prefs.getString(watchKey(stream), "{}")).optLong("position"); }
        catch (JSONException ignored) { return 0L; }
    }
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
        refreshWatchedDisplay(node);
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
        if (canAccessAdminTools() && (metadataKind.equals("movie") || metadataKind.equals("series"))) {
            actions.add("Reintentar metadata (TMDb)");
            handlers.add(() -> retryMetadata(metadataNode));
            actions.add("Metadata manual");
            handlers.add(() -> showMetadataEditor(metadataNode, new JSONArray()));
        }
        actions.add("Marcar como visto");
        handlers.add(() -> setMediaWatched(media, owner, true));
        actions.add("Marcar como no visto");
        handlers.add(() -> setMediaWatched(media, owner, false));
        showStyledActionDialog(t("Acciones"), actions, index -> handlers.get(index).run());
    }

    void setMediaWatched(JSONObject media, JSONObject owner, boolean seen) {
        String stream = media.optString("stream_url", media.optString("stream", ""));
        String key = mediaSeenKey(media);
        if (seen) prefs.edit().putBoolean(key, true).remove(watchKey(stream)).apply();
        else prefs.edit().remove(key).remove(watchKey(stream)).apply();
        scheduleProfileStateSync();
        if ("movie".equals(owner.optString("kind"))) setNodeSeen(owner.optString("id"), seen);
        if ("season".equals(owner.optString("kind"))) {
            if (seen) updateSeriesSuggestionAfterManualWatch();
            if (owner.optJSONArray("files") != null) refreshSeasonSeen(owner);
            else io.execute(() -> {
                try {
                    JSONObject fullSeason = getJson("/api/v1/catalog/" + enc(owner.optString("id")));
                    runOnUiThread(() -> refreshSeasonSeen(fullSeason));
                } catch (Exception ignored) { }
            });
        }
        else refreshParentOfNode(owner.optString("id", ""));
        refreshWatchedDisplay(owner);
        Toast.makeText(this, seen ? "Marcado como visto" : "Marcado como no visto", Toast.LENGTH_SHORT).show();
    }

    void updateSeriesSuggestionAfterManualWatch() {
        String profileId = activeProfileId();
        if (profileId.isEmpty()) return;
        prefs.edit().remove("series_suggestion_backfill_v2_ms:" + profileId).apply();
        backfillMissingSeriesSuggestions();
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
        List<String> actions = java.util.Arrays.asList(
                "Marcar como visto", "Marcar como no visto", "Quitar de Seguir viendo", t("Ir al detalle"));
        showStyledActionDialog(t("Acciones"), actions, which -> {
                    if (which == 0) setResumeSeen(item, true);
                    else if (which == 1) setResumeSeen(item, false);
                    else if (which == 3) {
                        openResumeDetail(item);
                        return;
                    }
                    else {
                        prefs.edit().remove(watchKey(item.optString("path", item.optString("stream")))).apply();
                        scheduleProfileStateSync();
                    }
                    removeResumeCard(item);
                });
    }

    void openResumeDetail(JSONObject item) {
        String ownerKind = item.optString("owner_kind", "");
        String nodeId = item.optString("owner_node_id", item.optString("node_id", ""));
        String kind = ownerKind;
        if ("season".equals(ownerKind)) {
            nodeId = item.optString("series_id", "");
            if (nodeId.isEmpty()) {
                JSONObject ownerSeries = findSeriesForSeason(item.optString("owner_node_id", ""));
                if (ownerSeries != null) nodeId = ownerSeries.optString("id", "");
            }
            kind = "series";
        }
        if (nodeId.isEmpty()) {
            Toast.makeText(this, t("No se encontró el detalle de este elemento."), Toast.LENGTH_SHORT).show();
            return;
        }
        JSONObject node = findCatalogNodeById(nodeId, movies);
        if (node == null) node = findCatalogNodeById(nodeId, series);
        if (node == null) node = new JSONObject();
        try {
            node.put("id", nodeId);
            if (node.optString("kind", "").isEmpty()) node.put("kind", kind);
        } catch (JSONException ignored) { }
        openNode(node);
    }

    JSONObject findCatalogNodeById(String id, List<JSONObject> roots) {
        for (JSONObject root : roots) {
            JSONObject found = findCatalogNodeById(id, root);
            if (found != null) return found;
        }
        return null;
    }

    JSONObject findCatalogNodeById(String id, JSONObject node) {
        if (node == null) return null;
        if (id.equals(node.optString("id", ""))) return node;
        JSONArray children = node.optJSONArray("children");
        if (children == null) return null;
        for (int i = 0; i < children.length(); i++) {
            JSONObject found = findCatalogNodeById(id, children.optJSONObject(i));
            if (found != null) return found;
        }
        return null;
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
        if (!canAccessAdminTools()) {
            if (isActiveKidProfile()) {
                showKidAdminAccessDenied();
                return;
            }
            Toast.makeText(this, t("Solo los administradores pueden modificar los metadatos."), Toast.LENGTH_LONG).show();
            return;
        }
        String nodeId = node.optString("id");
        if (nodeId.isEmpty()) return;
        captureMetadataEditorReturnState();
        showLoading(t("Buscando coincidencias en TMDb…"));
        io.execute(() -> {
            try {
                JSONObject result = postJson("/api/v1/catalog/" + enc(nodeId) + "/metadata/candidates");
                JSONArray candidates = result.optJSONArray("candidates");
                runOnUiThread(() -> showMetadataEditor(node,
                        candidates == null ? new JSONArray() : candidates));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    showMetadataEditor(node, new JSONArray());
                    String message = e.getMessage() == null ? t("Error desconocido") : e.getMessage();
                    if (message.contains("Metadata was recently requested")) {
                        message = t("Ya se buscó hace poco. Espera un minuto antes de volver a intentarlo.");
                    }
                    Toast.makeText(this, t("No se pudieron buscar coincidencias: ") + message,
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    EditText metadataField(String value, String hint, boolean multiline) {
        EditText field = new EditText(this);
        field.setTextColor(Color.WHITE);
        field.setTypeface(activeProfileTypeface(false));
        field.setHintTextColor(MUTED);
        field.setHint(hint);
        field.setText(cleanDisplayText(value));
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
        if (!canAccessAdminTools()) {
            if (isActiveKidProfile()) {
                showKidAdminAccessDenied();
                return;
            }
            Toast.makeText(this, t("Solo los administradores pueden modificar los metadatos."), Toast.LENGTH_LONG).show();
            return;
        }
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
        form.addView(label(t("Metadata manual"), 23, Color.WHITE, true));
        TextView subtitle = labelRaw(displayTitle(node) + " · " + kindLabel(node.optString("kind")), 13, MUTED, false);
        subtitle.setPadding(0, d(4), 0, d(14));
        form.addView(subtitle);

        EditText title = metadataField(node.optString("display_title", node.optString("title", "")), t("Título"), false);
        EditText year = metadataField(node.isNull("year") ? "" : node.optString("year"), t("Año"), false);
        EditText overview = metadataField(node.optString("overview", ""), t("Sinopsis"), true);
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
        form.addView(label(t("Coincidencias TMDb · selecciona para validar"), 16, Color.WHITE, true), fieldMargins());
        form.addView(candidateScroller, new LinearLayout.LayoutParams(-1, d(195)));
        form.addView(label(t("Imágenes online · selecciona una miniatura"), 16, Color.WHITE, true), fieldMargins());
        form.addView(imageScroller, new LinearLayout.LayoutParams(-1, d(174)));

        Button searchImages = actionButton(t("Buscar imágenes"), false);
        form.addView(searchImages, fieldMargins());
        searchImages.setOnClickListener(v -> searchMetadataImages(node, title.getText().toString(),
                selectedTmdbId[0], imageGallery, selectedImagePath, selectedImageType));
        Button save = actionButton(t("Guardar metadata"), true);
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
                        t("No se pudo cargar la lista de géneros: ") + e.getMessage(), Toast.LENGTH_LONG).show());
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
            String candidateLabel = candidate.optString("title", t("Título")) +
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
            candidateGallery.addView(label(t("No se encontraron coincidencias. Puedes completar los campos manualmente."),
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
        EditText input = metadataField("", t("Nombre del género"), false);
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
        Toast.makeText(this, t("Cargando coincidencia…"), Toast.LENGTH_SHORT).show();
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
                runOnUiThread(() -> Toast.makeText(this, t("No se pudo cargar la coincidencia: ") + e.getMessage(),
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
        gallery.addView(label(t("Buscando imágenes…"), 13, MUTED, false));
        io.execute(() -> {
            try {
                String path = "/api/v1/catalog/" + enc(nodeId) + "/metadata/images?q=" + enc(query.trim());
                if (tmdbId > 0) path += "&tmdb_id=" + tmdbId;
                JSONArray images = getJson(path).optJSONArray("images");
                runOnUiThread(() -> {
                    gallery.removeAllViews();
                    if (images == null || images.length() == 0) {
                        gallery.addView(label(t("No hay imágenes disponibles para esta búsqueda."), 13, MUTED, false));
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
                                ? t("Horizontal") : t("Póster"), 10, MUTED, false);
                        card.addView(kind);
                        card.setOnClickListener(v -> {
                            selectedPath[0] = option.optString("path", null);
                            selectedType[0] = option.optString("type", "poster");
                            highlightImageSelection(gallery, selectedPath[0], selectedType[0]);
                            Toast.makeText(this, t("Miniatura seleccionada."), Toast.LENGTH_SHORT).show();
                        });
                        gallery.addView(card);
                    }
                    highlightImageSelection(gallery, selectedPath[0], selectedType[0]);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    gallery.removeAllViews();
                    String error = e.getMessage() == null ? t("Error desconocido") : e.getMessage();
                    if (error.contains("HTTP 404") && error.contains("Not Found")) {
                        error = t("El servidor del NAS aún no tiene la ruta de búsqueda de imágenes. " +
                                "Actualiza el contenedor con: docker compose up --build -d");
                    } else if (error.contains("Catalog node not found")) {
                        error = t("El elemento ya no existe en el catálogo. Resincroniza el catálogo e inténtalo de nuevo.");
                    }
                    gallery.addView(label(t("No se pudieron buscar imágenes: ") + error, 12, MUTED, false));
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
        if (!canAccessAdminTools()) {
            if (isActiveKidProfile()) {
                showKidAdminAccessDenied();
                return;
            }
            Toast.makeText(this, t("Solo los administradores pueden modificar los metadatos."), Toast.LENGTH_LONG).show();
            return;
        }
        String itemTitle = title.getText().toString().trim();
        if (itemTitle.isEmpty()) {
            Toast.makeText(this, t("El título no puede estar vacío."), Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, t("Revisa el año introducido."), Toast.LENGTH_SHORT).show();
            return;
        }
        String nodeId = node.optString("id");
        saveButton.setEnabled(false);
        saveButton.setText(t("Guardando metadata…"));
        io.execute(() -> {
            try {
                postJson("/api/v1/catalog/" + enc(nodeId) + "/metadata/manual", payload);
                imageCache.evictAll();
                runOnUiThread(() -> finishMetadataRetry(t("Metadata guardada y cliente actualizado.")));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    saveButton.setEnabled(true);
                    saveButton.setText(t("Guardar metadata"));
                    Toast.makeText(this, t("No se pudo guardar: ") + e.getMessage(),
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
        if (!isKidAllowedNode(node)) {
            showingDetail = false;
            Toast.makeText(this, t("Este contenido no está disponible en un perfil infantil."), Toast.LENGTH_LONG).show();
            if (libraryMode) openLibrary(currentLibrary); else loadHome();
            return;
        }
        showingDetail = true;
        detailNodeId = node.optString("id");
        currentDetailNode = node;
        if ("series".equals(node.optString("kind")) && isKidAllowedByGenres(node)) {
            JSONArray seasons = node.optJSONArray("children");
            if (seasons != null) for (int i = 0; i < seasons.length(); i++) {
                JSONObject season = seasons.optJSONObject(i);
                if (season != null && !season.optString("id", "").isEmpty())
                    kidAllowedSeasonIds.add(season.optString("id"));
            }
        }
        if (backButton != null) backButton.setVisibility(View.VISIBLE);
        if (alphabetToggleButton != null) alphabetToggleButton.setVisibility(View.GONE);
        removeAlphabetRail();
        content.setPadding(0, 0, 0, d(28));
        content.removeAllViews();
        if (contentScroller != null) contentScroller.scrollTo(0, 0);
        FrameLayout banner = new FrameLayout(this);
        ImageView bgImage = new ImageView(this);
        bgImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        banner.addView(bgImage, new FrameLayout.LayoutParams(-1, -1));
        String detailPoster = posterField(node, "poster_url");
        String detailBackdrop = posterField(node, "backdrop_url");
        boolean seriesDetail = "series".equals(node.optString("kind"));
        String detailImage = seriesDetail ? detailPoster : detailBackdrop;
        String detailFallback = seriesDetail ? detailBackdrop : detailPoster;
        if (detailImage.isEmpty()) detailImage = detailFallback;
        loadImage(detailImage, bgImage, true, detailFallback);
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
        String incompleteText = incompleteSummary(node);
        if (!incompleteText.isEmpty()) {
            TextView incomplete = label(incompleteText, 12, 0xFFFFD278, true);
            incomplete.setPadding(0, d(5), 0, 0);
            info.addView(incomplete);
        }
        if (isNodeSeen(node)) {
            TextView watched = label("✓  " + t("VISTO"), 11, 0xFF79E3B7, true);
            watched.setPadding(0, d(5), 0, 0);
            info.addView(watched);
        }
        info.addView(labelRaw(displayTitle(node), 30, Color.WHITE, true));
        String year = cleanDisplayText(node.optString("year", ""));
        String genres = node.optJSONArray("genres") == null ? "" : cleanDisplayText(join(node.optJSONArray("genres")));
        String meta = "series".equals(node.optString("kind")) ? cleanDisplayText(cardSubtitle(node))
                : year + (year.isEmpty() || genres.isEmpty() ? "" : "   •   ") + genres;
        if (!meta.isEmpty()) {
            TextView metadata = label(meta, 13, 0xFFD7D9DF, false);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
            mp.topMargin = d(5);
            info.addView(metadata, mp);
        }
        String overviewText = cleanDisplayText(node.optString("overview", ""));
        if (!overviewText.isEmpty()) {
            TextView overview = labelRaw(overviewText, 14, 0xFFE0E1E6, false);
            overview.setMaxLines(4);
            LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, -2);
            op.topMargin = d(10);
            info.addView(overview, op);
        }
        JSONArray files = node.optJSONArray("files");
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        Button play = actionButton("Reproducir", true);
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
        play.setOnClickListener(v -> {
            if ("series".equals(node.optString("kind"))) playNextSeriesEpisode(node);
            else playFirst(node);
        });
        banner.addView(info, new FrameLayout.LayoutParams(-1, -1));
        banner.setOnLongClickListener(v -> { showActions(node); return true; });
        int heroHeight = contentScroller == null ? 0 : contentScroller.getHeight();
        if (heroHeight <= 0)
            heroHeight = getResources().getDisplayMetrics().heightPixels - d(116);
        heroHeight = Math.max(d(390), heroHeight);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, heroHeight);
        bp.setMargins(0, 0, 0, d(10));
        content.addView(banner, bp);
        if (isTelevision()) play.post(() -> play.requestFocus());

        JSONArray children = node.optJSONArray("children");
        if ("series".equals(node.optString("kind")) && children != null && children.length() > 0) {
            addSectionHeader("Temporadas", "Selecciona para continuar");
            addGrid(toList(children));
        }
        if ("movie".equals(node.optString("kind")) || "series".equals(node.optString("kind")))
            addCastSection(node.optJSONArray("cast"));
        if (!"series".equals(node.optString("kind")) && children != null && children.length() > 0) {
            addSectionHeader("Explorar", "Selecciona para continuar");
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
            case "INCOMPLETA": return "OSATUGABEA";
            case "Serie incompleta": return "Telesaila osatu gabe";
            case "Faltan": return "Falta dira";
            case "capítulo": return "atal";
            case "capítulos": return "atal";
            case "Inicio": return "Hasiera";
            case "Lo más nuevo": return "Eduki berrienak";
            case "Perfiles": return "Profilak";
            case "Perfil": return "Profila";
            case "Cambiar perfil": return "Profila aldatu";
            case "Salir": return "Irten";
            case "Cerrar sesión": return "Saioa itxi";
            case "¿Quién está viendo?": return "Nor ari da ikusten?";
            case "Cada perfil conserva su lista y su progreso de reproducción.":
                return "Profil bakoitzak bere zerrenda eta erreprodukzio-aurrerapena gordetzen ditu.";
            case "Perfil actual": return "Uneko profila";
            case "Perfil infantil": return "Haurren profila";
            case "Perfil infantil · filtro por género": return "Haurren profila · genero-iragazkia";
            case "Oculta títulos de Guerra, Crimen, Drama, Terror y Western, salvo los que también incluyan Familia.":
                return "Gerra, krimen, drama, beldurrezko eta western generoak dituzten tituluak ezkutatzen ditu, Familia ere badutenak izan ezik.";
            case "Este contenido no está disponible en un perfil infantil.":
                return "Eduki hau ez dago eskuragarri haurren profilean.";
            case "Esta opción no está disponible en un perfil infantil.":
                return "Aukera hau ez dago eskuragarri haurren profilean.";
            case "Solo los administradores pueden modificar los metadatos.":
                return "Administratzaileek soilik alda ditzakete metadatuak.";
            case "Menú de perfil": return "Profilaren menua";
            case "No hay contenido infantil para esta búsqueda.":
                return "Ez dago haurrentzako edukirik bilaketa honetarako.";
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
            case "Pedir contenido": return "Edukia eskatu";
            case "Pedir temporadas restantes": return "Gainerako denboraldiak eskatu";
            case "Gestión de cuentas": return "Kontuen kudeaketa";
            case "Gestión de Incoming": return "Incoming karpetaren kudeaketa";
            case "Detecta los vídeos de Incoming y edita su ruta de destino antes de moverlos.":
                return "Bilatu Incoming karpetako bideoak eta editatu helmugako bidea mugitu aurretik.";
            case "Detectar vídeos": return "Bideoak bilatu";
            case "Detectando vídeos…": return "Bideoak bilatzen…";
            case "No hay vídeos en Incoming.": return "Ez dago bideorik Incoming karpetan.";
            case "No se pudieron detectar los vídeos: ": return "Ezin izan dira bideoak aurkitu: ";
            case "Petición previa asociada": return "Aurreko eskaera lotuta";
            case "Ruta definitiva": return "Behin betiko bidea";
            case "Generar": return "Sortu";
            case "Moviendo…": return "Mugitzen…";
            case "Fichero movido; sincronización del catálogo en cola.":
                return "Fitxategia mugituta; katalogoaren sinkronizazioa ilaran dago.";
            case "Fichero movido; sincronización del catálogo iniciada.":
                return "Fitxategia mugituta; katalogoaren sinkronizazioa hasi da.";
            case "Fichero movido; sincronización no disponible (configura TMDb).":
                return "Fitxategia mugituta; sinkronizazioa ez dago erabilgarri (konfiguratu TMDb).";
            case "Fichero movido a su destino.": return "Fitxategia helmugara mugitu da.";
            case "No se pudo mover el fichero: ": return "Ezin izan da fitxategia mugitu: ";
            case "Toca un resultado para añadirlo a la petición. Puedes elegir la temporada de cada serie y enviar varios títulos juntos.":
                return "Ukitu emaitza bat eskaerara gehitzeko. Telesail bakoitzaren denboraldia aukeratu eta hainbat titulu batera bidal ditzakezu.";
            case "Título de película o serie": return "Filmaren edo telesailaren izenburua";
            case "Contenido seleccionado": return "Hautatutako edukia";
            case "Selecciona un resultado para continuar.": return "Hautatu emaitza bat jarraitzeko.";
            case "No se encontraron resultados.": return "Ez da emaitzarik aurkitu.";
            case "No se pudo buscar en TMDb: ": return "Ezin izan da TMDb-n bilatu: ";
            case "Cargando selección…": return "Hautapena kargatzen…";
            case "No se pudo cargar la selección: ": return "Ezin izan da hautapena kargatu: ";
            case "Serie completa": return "Telesail osoa";
            case "Selecciona una temporada": return "Hautatu denboraldi bat";
            case "Enviar solicitud": return "Eskaera bidali";
            case "Añadir a la petición": return "Gehitu eskaerara";
            case "Elementos en la petición": return "Eskaerako elementuak";
            case "Enviar solicitudes": return "Eskaerak bidali";
            case "Quitar": return "Kendu";
            case "Ese contenido ya está en la petición.": return "Eduki hori dagoeneko eskaeran dago.";
            case "Puedes añadir hasta 20 elementos.": return "Gehienez 20 elementu gehi ditzakezu.";
            case "Solicitud enviada a administración.": return "Eskaera administraziora bidali da.";
            case "No se pudo enviar la solicitud: ": return "Ezin izan da eskaera bidali: ";
            case "Enviar una solicitud para las temporadas o capítulos que faltan de ":
                return "Bidali falta diren denboraldiak edo atalak eskatzeko eskaera: ";
            case "Película": return "Filma";
            case "Serie": return "Telesaila";
            case "Solicitudes de contenido": return "Eduki-eskaerak";
            case "pendientes": return "zain";
            case "No hay solicitudes de contenido.": return "Ez dago eduki-eskaerarik.";
            case "Temporadas restantes": return "Gainerako denboraldiak";
            case "Solicitó": return "Eskatzailea";
            case "Aprobada": return "Onartuta";
            case "Validada": return "Balioztatuta";
            case "Pendiente de añadir": return "Gehitzeko zain";
            case "Pendientes de añadir": return "Gehitzeko zain";
            case "A la espera de ser añadido": return "Gehitzeko zain";
            case "Capítulos pendientes": return "Atalak gehitzeko zain";
            case "SOLICITUD VALIDADA": return "ESKAERA BALIOZTATUTA";
            case "Pendiente": return "Zain";
            case "Validar": return "Balioztatu";
            case "Enviando a Telegram…": return "Telegramera bidaltzen…";
            case "Reenviar a Telegram": return "Birbidali";
            case "Telegram enviado": return "Telegramera bidali da";
            case "Borrar": return "Ezabatu";
            case "Borrar solicitud": return "Eskaera ezabatu";
            case "¿Borrar la solicitud de ": return "Ezabatu nahi duzu honen eskaera: ";
            case "No se pudo actualizar la solicitud: ": return "Ezin izan da eskaera eguneratu: ";
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
            case "SIGUIENTE CAPÍTULO": return "HURRENGO ATALA";
            case "EMPEZAR SIGUIENTE TEMPORADA": return "HASI HURRENGO DENBORALDIA";
            case "CONTINUAR CAPÍTULO": return "ATALA IKUSTEN JARRAITU";
            case "CONTINUAR PELÍCULA": return "FILMA IKUSTEN JARRAITU";
            case "EN CURSO": return "ABIAN";
            case "Ir al detalle": return "Joan xehetasunera";
            case "Capítulo": return "Atala";
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
            case "No se encontraron pósteres.": return "Ez da posterik aurkitu.";
            case "Reproductor": return "Erreproduzitzailea";
            case "VLC devuelve posición y duración al terminar. El reproductor de Zinema incluye controles y guarda el progreso mientras ves el vídeo.":
                return "VLCk posizioa eta iraupena itzultzen ditu amaitzean. Zinemaren erreproduzitzaileak kontrolak ditu eta bideoa ikusten duzun bitartean aurrerapena gordetzen du.";
            case "Reproductor de Zinema": return "Zinemaren erreproduzitzailea";
            case "Cuentas pendientes de aprobación": return "Onarpenaren zain dauden kontuak";
            case "Estas cuentas todavía no pueden acceder a la biblioteca.":
                return "Kontu hauek oraindik ezin dira liburutegira sartu.";
            case "Aprobar": return "Onartu";
            case "Rechazar": return "Baztertu";
            case "Aprobar cuenta": return "Kontua onartu";
            case "Rechazar cuenta": return "Kontua baztertu";
            case "¿Permitir que ": return "Baimendu ";
            case " acceda a Zinema y cree sus perfiles?": return "Zinemara sartzea eta bere profilak sortzea?";
            case "¿Rechazar y eliminar la cuenta de ": return "Baztertu eta ezabatu kontu hau: ";
            case "Cuenta aprobada: ": return "Kontua onartuta: ";
            case "No se pudo aprobar la cuenta: ": return "Ezin izan da kontua onartu: ";
            case "Cuenta rechazada y eliminada: ": return "Kontua baztertu eta ezabatu da: ";
            case "No se pudo rechazar la cuenta: ": return "Ezin izan da kontua baztertu: ";
            case "Todavía no hay cuentas registradas.": return "Oraindik ez dago erregistratutako konturik.";
            case "Permite, deniega o elimina el acceso a Zinema.":
                return "Zinemarako sarbidea baimendu, ukatu edo ezabatzen du.";
            case "Permitido": return "Baimenduta";
            case "Denegado": return "Ukatuta";
            case "Permitir acceso": return "Sarbidea baimendu";
            case "Denegar acceso": return "Sarbidea ukatu";
            case " acceda al catálogo?": return " katalogora sartzea?";
            case "¿Denegar temporalmente a ": return "Aldi baterako sarbidea ukatu ";
            case "? Podrás permitirlo después.": return "? Gero baimendu ahal izango duzu.";
            case "Borrar cuenta": return "Kontua ezabatu";
            case "¿Borrar definitivamente la cuenta ": return "Betiko ezabatu kontu hau: ";
            case " y sus perfiles?": return " eta haren profilak?";
            case "Confirmar": return "Berretsi";
            case "Cuenta actualizada: ": return "Kontua eguneratuta: ";
            case "Sesión actual": return "Uneko saioa";
            case "Acceso denegado": return "Sarbidea ukatuta";
            case "Pendiente de aprobación": return "Onarpenaren zain";
            case "Admin": return "Administratzailea";
            case "No se pudieron cargar las cuentas.": return "Ezin izan dira kontuak kargatu.";
            case "No hay capítulos disponibles.": return "Ez dago atal erabilgarririk.";
            case "Buscando el siguiente capítulo…": return "Hurrengo atala bilatzen…";
            case "No hay capítulos pendientes.": return "Ez dago atal gehiagorik.";
            case "Se usará VLC. Si no está instalado, se abrirá el reproductor de Zinema.":
                return "VLC erabiliko da. Instalatu gabe badago, Zinemaren erreproduzitzailea irekiko da.";
            case "Se usará el reproductor de Zinema.": return "Zinemaren erreproduzitzailea erabiliko da.";
            case "Hay una actualización de Zinema": return "Zinemaren eguneraketa bat dago";
            case "Nueva versión disponible": return "Bertsio berria eskuragarri";
            case "Descarga la nueva versión para seguir usando el cliente actualizado.":
                return "Deskargatu bertsio berria bezero eguneratua erabiltzen jarraitzeko.";
            case "Actualizar": return "Eguneratu";
            case "Más tarde": return "Geroago";
            case "Descargando actualización…": return "Eguneraketa deskargatzen…";
            case "Permitir actualizaciones": return "Eguneraketak baimendu";
            case "Android necesita permiso para instalar actualizaciones descargadas desde Zinema.":
                return "Androiden baimena behar da Zinematik deskargatutako eguneraketak instalatzeko.";
            case "Abrir ajustes": return "Ezarpenak ireki";
            case "No se pudo abrir el instalador de Android: ": return "Ezin izan da Androiden instalatzailea ireki: ";
            case "No se encontró el detalle de este elemento.": return "Ez da elementu honen xehetasunik aurkitu.";
            case "No se pudo identificar el vídeo para reproducirlo.": return "Ezin izan da erreproduzitzeko bideoa identifikatu.";
            case "Preparando reproducción…": return "Erreprodukzioa prestatzen…";
            case "No se pudo buscar el siguiente capítulo: ": return "Ezin izan da hurrengo atala bilatu: ";
            case "No se pudo actualizar la cuenta.": return "Ezin izan da kontua eguneratu.";
            case "La descarga no coincide con la versión publicada.":
                return "Deskarga ez dator bat argitaratutako bertsioarekin.";
            case "No se pudo descargar la actualización: ": return "Ezin izan da eguneraketa deskargatu: ";
            case "No se pudo preparar el vídeo: ": return "Ezin izan da bideoa prestatu: ";
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
            case "Español": return "Gaztelania";
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
            case "Ya se buscó hace poco. Espera un minuto antes de volver a intentarlo.":
                return "Duela gutxi bilatu da. Itxaron minutu bat berriro saiatu aurretik.";
            case "No se pudieron buscar coincidencias: ": return "Ezin izan dira bat-etortzeak bilatu: ";
            case "No se pudo cargar la lista de géneros: ": return "Ezin izan da generoen zerrenda kargatu: ";
            case "No encontramos resultados para": return "Ez dugu emaitzarik aurkitu honentzat";
            case "No se encontraron coincidencias.": return "Ez da bat-etortzerik aurkitu.";
            case "No pudimos cargar la lista de géneros.": return "Ezin izan da generoen zerrenda kargatu.";
            case "Cargando coincidencia…": return "Bat-etortzea kargatzen…";
            case "Buscando imágenes…": return "Irudiak bilatzen…";
            case "No se pudo cargar la coincidencia": return "Ezin izan da bat-etortzea kargatu";
            case "No se pudo cargar la coincidencia: ": return "Ezin izan da bat-etortzea kargatu: ";
            case "No se pudieron buscar imágenes": return "Ezin izan dira irudiak bilatu";
            case "No se pudieron buscar imágenes: ": return "Ezin izan dira irudiak bilatu: ";
            case "El servidor del NAS aún no tiene la ruta de búsqueda de imágenes. Actualiza el contenedor con: docker compose up --build -d":
                return "NASeko zerbitzariak oraindik ez du irudiak bilatzeko biderik. Eguneratu edukiontzia komando honekin: docker compose up --build -d";
            case "El elemento ya no existe en el catálogo. Resincroniza el catálogo e inténtalo de nuevo.":
                return "Elementua ez dago katalogoan. Sinkronizatu katalogoa berriro eta saiatu.";
            case "No se pudo guardar": return "Ezin izan da gorde";
            case "No se pudo guardar: ": return "Ezin izan da gorde: ";
            case "Error desconocido": return "Errore ezezaguna";
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
        String filename = cleanDisplayText(file.optString("filename", file.optString("title", "Vídeo")));
        String title = cleanDisplayText(file.optString("title", ""));
        String extension = cleanDisplayText(file.optString("extension", ""));
        long bytes = file.optLong("size_bytes", 0);
        String modified = cleanDisplayText(file.optString("modified_at", ""));
        StringBuilder message = new StringBuilder();
        if (!filename.isEmpty()) message.append(t("Nombre:")).append(" ").append(filename);
        if (!title.isEmpty()) appendDetailLine(message, t("Título"), title);
        if (!extension.isEmpty()) appendDetailLine(message, t("Extensión:"), extension);
        if (bytes > 0) appendDetailLine(message, t("Tamaño:"), formatDetailedSize(bytes));
        String relativePath = cleanDisplayText(file.optString("relative_path", ""));
        if (!relativePath.isEmpty()) appendDetailLine(message, t("Ruta:"), relativePath);
        String libraryName = cleanDisplayText(library);
        if (!libraryName.isEmpty()) appendDetailLine(message, t("Biblioteca:"), libraryName);
        if (!modified.isEmpty()) appendDetailLine(message, t("Modificado:"), modified);
        new AlertDialog.Builder(this).setTitle(t("Detalles del archivo"))
                .setMessage(message.toString()).setPositiveButton(t("Cerrar"), null).show();
    }

    void appendDetailLine(StringBuilder message, String label, String value) {
        if (message.length() > 0) message.append("\n");
        message.append(label).append(" ").append(cleanDisplayText(value));
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
            item.setPadding(d(12), d(9), d(12), d(9));
            GradientDrawable episodeRowBackground = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0xFF202838, 0xFF191D29});
            episodeRowBackground.setCornerRadius(d(13));
            item.setBackground(episodeRowBackground);
            item.setFocusable(true);
            item.setClickable(true);
            applyTvFocusStyle(item, false);
            int episodeNumber = episodeNumberForDisplay(file, i + 1);
            int thumbnailWidth = d(150);
            int thumbnailHeight = d(84);
            FrameLayout thumbnailFrame = new FrameLayout(this);
            thumbnailFrame.setBackground(round(0xFF202534, d(8)));
            ImageView thumbnail = new ImageView(this);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            String episodePoster = file.optString("poster_url", fallbackPoster);
            loadImage(episodePoster, thumbnail, false);
            thumbnailFrame.addView(thumbnail, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout.LayoutParams thumbParams = new LinearLayout.LayoutParams(thumbnailWidth, thumbnailHeight);
            thumbParams.rightMargin = d(22);
            item.addView(thumbnailFrame, thumbParams);
            TextView play = label("▶", 20, Color.WHITE, true);
            play.setGravity(Gravity.CENTER);
            play.setVisibility(View.GONE);
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = labelRaw(episodeNumber + ". " + episodeTitleForDisplay(file, episodeNumber),
                    16, Color.WHITE, true);
            boolean watchedEpisode = isMediaSeen(file);
            long position = mediaProgressPosition(file);
            boolean inProgress = !watchedEpisode && position > 10000L;
            String watched = watchedEpisode ? "   ·   ✓ " + t("VISTO")
                    : inProgress ? "   ·   " + t("EN CURSO") + " ·   "
                    + formatTime((int) Math.min(Integer.MAX_VALUE, position)) : "";
            String extension = file.optString("extension", "").replace(".", "").toUpperCase();
            String subText = extension.isEmpty() ? formatSize(file.optLong("size_bytes"))
                    : extension + "   ·   " + formatSize(file.optLong("size_bytes"));
            TextView sub = label(subText + watched,
                    12, watchedEpisode ? 0xFF79E3B7 : inProgress ? 0xFFFFC078 : MUTED, false);
            texts.addView(name);
            LinearLayout.LayoutParams subP = new LinearLayout.LayoutParams(-1, -2);
            subP.topMargin = d(4);
            texts.addView(sub, subP);
            item.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
            ip.setMargins(d(16), 0, d(16), d(8));
            content.addView(item, ip);
            item.setOnClickListener(v -> launchPlayer(file.optString("stream_url"),
                    file.optString("title", "Zinema"), file.optString("poster_url", fallbackPoster),
                    owner.optString("id", ""), file.optString("id", ""), owner.optString("kind", "")));
            item.setOnLongClickListener(v -> {
                showMediaActions(file, owner);
                return true;
            });
        }
    }

    int episodeNumberForDisplay(JSONObject file, int fallback) {
        String filename = file.optString("filename", "");
        Matcher marker = EPISODE_MARKER_PATTERN.matcher(filename);
        boolean found = marker.find();
        if (!found) {
            marker = EPISODE_MARKER_PATTERN.matcher(file.optString("title", ""));
            found = marker.find();
        }
        if (found) {
            String number = marker.group(2) != null ? marker.group(2) : marker.group(4);
            try { return Integer.parseInt(number); }
            catch (NumberFormatException ignored) { }
        }
        int number = file.optInt("episode_number", 0);
        return number > 0 ? number : fallback;
    }

    String episodeTitleForDisplay(JSONObject file, int episodeNumber) {
        String title = file.optString("title", "").trim();
        String filename = file.optString("filename", "").trim();
        if (title.isEmpty() || title.equalsIgnoreCase("null")) title = filename;
        Matcher marker = EPISODE_MARKER_PATTERN.matcher(title);
        if (marker.find()) title = marker.replaceAll(" ");
        title = title.replaceAll("[._]+", " ")
                .replaceAll("^[\\s\\-–—:]+|[\\s\\-–—:]+$", "")
                .replaceAll("\\s+", " ").trim();
        return title.isEmpty() ? t("Episodio") + " " + episodeNumber : title;
    }

    void playFirst(JSONObject node) {
        if (!isKidAllowedNode(node)) {
            Toast.makeText(this, t("Este contenido no está disponible en un perfil infantil."), Toast.LENGTH_LONG).show();
            return;
        }
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
        else Toast.makeText(this, t("No hay un vídeo disponible."), Toast.LENGTH_SHORT).show();
    }

    void playNextSeriesEpisode(JSONObject seriesNode) {
        if (!isKidAllowedNode(seriesNode)) {
            Toast.makeText(this, t("Este contenido no está disponible en un perfil infantil."), Toast.LENGTH_LONG).show();
            return;
        }
        JSONArray seasonNodes = seriesNode.optJSONArray("children");
        if (seasonNodes == null || seasonNodes.length() == 0) {
            Toast.makeText(this, t("No hay capítulos disponibles."), Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, t("Buscando el siguiente capítulo…"), Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try {
                List<JSONObject> seasons = toList(seasonNodes);
                Collections.sort(seasons, Comparator.comparingInt(value -> value.optInt("season_number")));
                List<JSONObject> episodes = new ArrayList<>();
                for (JSONObject seasonStub : seasons) {
                    String seasonId = seasonStub.optString("id", "");
                    if (seasonId.isEmpty()) continue;
                    JSONObject season = getJson("/api/v1/catalog/" + enc(seasonId));
                    JSONArray files = season.optJSONArray("files");
                    if (files == null) continue;
                    for (int i = 0; i < files.length(); i++) {
                        JSONObject file = files.optJSONObject(i);
                        if (file == null) continue;
                        JSONObject episode = new JSONObject(file.toString());
                        episode.put("owner_node_id", seasonId);
                        episode.put("owner_kind", "season");
                        episodes.add(episode);
                    }
                }
                int lastSeenIndex = -1;
                JSONObject inProgressEpisode = null;
                long newestProgress = Long.MIN_VALUE;
                for (int i = 0; i < episodes.size(); i++) {
                    JSONObject episode = episodes.get(i);
                    if (isMediaSeen(episode)) {
                        lastSeenIndex = i;
                        continue;
                    }
                    if (mediaProgressPosition(episode) > 10000L) {
                        long updated = 0L;
                        String stream = episode.optString("stream_url", "");
                        try {
                            updated = new JSONObject(prefs.getString(watchKey(stream), "{}"))
                                    .optLong("updated");
                        } catch (JSONException ignored) { }
                        if (updated >= newestProgress) {
                            newestProgress = updated;
                            inProgressEpisode = episode;
                        }
                    }
                }
                JSONObject selected = inProgressEpisode;
                if (selected == null) {
                    for (int i = lastSeenIndex + 1; i < episodes.size(); i++) {
                        if (!isMediaSeen(episodes.get(i))) {
                            selected = episodes.get(i);
                            break;
                        }
                    }
                }
                JSONObject nextEpisode = selected;
                runOnUiThread(() -> {
                    if (nextEpisode == null) {
                        Toast.makeText(this, t("No hay capítulos pendientes."), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    launchPlayer(nextEpisode.optString("stream_url"),
                            nextEpisode.optString("title", displayTitle(seriesNode)),
                            nextEpisode.optString("poster_url", seriesNode.optString("poster_url", "")),
                            nextEpisode.optString("owner_node_id", ""),
                            nextEpisode.optString("id", ""), "season");
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        t("No se pudo buscar el siguiente capítulo: ") + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
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
        button.setTypeface(activeProfileTypeface(true));
        button.setPadding(d(15), 0, d(15), 0);
        button.setBackground(isActiveKidProfile()
                ? gradientRound(primary
                        ? new int[]{0xFFFF8A5B, 0xFFEC4D91}
                        : new int[]{0xFF326CB3, 0xFF6653B8}, d(18))
                : round(primary ? RED : 0xCC22252C, d(8)));
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
                    List<JSONObject> visibleResults = filterKidCatalog(toList(results));
                    content.removeAllViews();
                    addSectionHeader("Resultados", visibleResults.size() + " encontrados");
                    for (JSONObject result : visibleResults) addSearchResult(result);
                    if (visibleResults.isEmpty()) addEmpty(isActiveKidProfile()
                            ? t("No hay contenido infantil para esta búsqueda.")
                            : "No encontramos resultados para “" + query + "”.");
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
            TextView watched = label("✓ " + t("VISTO"), 10, 0xFF79E3B7, true);
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
                    card.addView(label(t("Cuentas pendientes de aprobación") + " (" + pending.length() + ")",
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
                                .setTitle(t("Aprobar cuenta"))
                                .setMessage(t("¿Permitir que ") + username + t(" acceda a Zinema y cree sus perfiles?"))
                                .setNegativeButton(t("Cancelar"), null)
                                .setPositiveButton(t("Aprobar"), (dialog, which) -> {
                                    approve.setEnabled(false);
                                    io.execute(() -> {
                                        try {
                                            postJson("/api/v1/admin/accounts/" + enc(accountId) + "/approve");
                                            runOnUiThread(() -> {
                                                Toast.makeText(this, t("Cuenta aprobada: ") + username,
                                                        Toast.LENGTH_LONG).show();
                                                loadPendingAccountControls(slot);
                                            });
                                        } catch (Exception e) {
                                            runOnUiThread(() -> {
                                                approve.setEnabled(true);
                                                Toast.makeText(this, t("No se pudo aprobar la cuenta: ") + e.getMessage(),
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
                                .setTitle(t("Rechazar cuenta"))
                                .setMessage(t("¿Rechazar y eliminar la cuenta de ") + username + "?")
                                .setNegativeButton(t("Cancelar"), null)
                                .setPositiveButton(t("Rechazar"), (dialog, which) -> {
                                    reject.setEnabled(false);
                                    approve.setEnabled(false);
                                    io.execute(() -> {
                                        try {
                                            postJson("/api/v1/admin/accounts/" + enc(accountId) + "/reject");
                                            runOnUiThread(() -> {
                                                Toast.makeText(this, t("Cuenta rechazada y eliminada: ") + username,
                                                        Toast.LENGTH_LONG).show();
                                                loadPendingAccountControls(slot);
                                            });
                                        } catch (Exception e) {
                                            runOnUiThread(() -> {
                                                reject.setEnabled(true);
                                                approve.setEnabled(true);
                                                Toast.makeText(this, t("No se pudo rechazar la cuenta: ") + e.getMessage(),
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
        if (isActiveKidProfile()) {
            showKidAdminAccessDenied();
            return;
        }
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
                    ? t("Se usará VLC. Si no está instalado, se abrirá el reproductor de Zinema.")
                    : t("Se usará el reproductor de Zinema."), Toast.LENGTH_SHORT).show();
        });

        LinearLayout syncSlot = new LinearLayout(this);
        syncSlot.setOrientation(LinearLayout.VERTICAL);
        syncSlot.setVisibility(View.GONE);
        LinearLayout.LayoutParams syncSlotParams = new LinearLayout.LayoutParams(-1, -2);
        syncSlotParams.topMargin = d(14);
        screen.addView(syncSlot, syncSlotParams);

        LinearLayout requestsSlot = new LinearLayout(this);
        requestsSlot.setOrientation(LinearLayout.VERTICAL);
        requestsSlot.setVisibility(View.GONE);
        LinearLayout.LayoutParams requestsSlotParams = new LinearLayout.LayoutParams(-1, -2);
        requestsSlotParams.topMargin = d(14);
        screen.addView(requestsSlot, requestsSlotParams);

        LinearLayout incomingSlot = new LinearLayout(this);
        incomingSlot.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams incomingSlotParams = new LinearLayout.LayoutParams(-1, -2);
        incomingSlotParams.topMargin = d(14);
        screen.addView(incomingSlot, incomingSlotParams);

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
        loadContentRequestControls(requestsSlot);
        loadIncomingControls(incomingSlot);
    }

    void loadIncomingControls(LinearLayout slot) {
        if (slot == null || !showingAdmin) return;
        io.execute(() -> {
            try {
                JSONObject account = getJson("/api/v1/auth/me");
                if (!account.optBoolean("is_admin")) return;
                runOnUiThread(() -> buildIncomingControls(slot));
            } catch (Exception ignored) { }
        });
    }

    void buildIncomingControls(LinearLayout slot) {
        if (slot == null || !showingAdmin || slot.getParent() == null) return;
        slot.removeAllViews();
        slot.setVisibility(View.VISIBLE);
        LinearLayout card = settingsCard();
        slot.addView(card);
        card.addView(label(t("Gestión de Incoming"), 16, Color.WHITE, true));
        TextView hint = label(t("Detecta los vídeos de Incoming y edita su ruta de destino antes de moverlos."),
                12, MUTED, false);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = d(5);
        card.addView(hint, hintParams);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setTag("incoming-rows");
        LinearLayout.LayoutParams rowsParams = new LinearLayout.LayoutParams(-1, -2);
        rowsParams.topMargin = d(10);
        card.addView(rows, rowsParams);
        Button detect = actionButton(t("Detectar vídeos"), true);
        detect.setTag("incoming-detect");
        LinearLayout.LayoutParams detectParams = new LinearLayout.LayoutParams(-1, d(46));
        detectParams.topMargin = d(10);
        card.addView(detect, detectParams);
        detect.setOnClickListener(v -> detectIncomingVideos(rows, detect));
        JSONArray cachedFiles = incomingFilesCache;
        if (cachedFiles != null) renderIncomingVideos(rows, cachedFiles);
    }

    void prefetchIncomingVideosForAdmin() {
        if (!isAccountAdmin()) return;
        io.execute(() -> {
            try {
                JSONArray files = getArray("/api/v1/admin/incoming");
                incomingFilesCache = files;
                runOnUiThread(() -> {
                    if (!showingAdmin || content == null) return;
                    View rowsView = content.findViewWithTag("incoming-rows");
                    if (rowsView instanceof LinearLayout)
                        renderIncomingVideos((LinearLayout) rowsView, files);
                });
            } catch (Exception ignored) { }
        });
    }

    void detectIncomingVideos(LinearLayout rows, Button detect) {
        detect.setEnabled(false);
        detect.setText(t("Detectando vídeos…"));
        io.execute(() -> {
            try {
                JSONArray files = getArray("/api/v1/admin/incoming");
                incomingFilesCache = files;
                runOnUiThread(() -> {
                    if (!showingAdmin || rows.getParent() == null) return;
                    renderIncomingVideos(rows, files);
                    detect.setText(t("Detectar vídeos"));
                    detect.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    detect.setText(t("Detectar vídeos"));
                    detect.setEnabled(true);
                    Toast.makeText(this, t("No se pudieron detectar los vídeos: ") + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    void renderIncomingVideos(LinearLayout rows, JSONArray files) {
        if (rows == null || files == null) return;
        rows.removeAllViews();
        if (files.length() == 0) {
            rows.addView(label(t("No hay vídeos en Incoming."), 13, MUTED, false));
            return;
        }
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file != null) addIncomingVideoRow(rows, file);
        }
    }

    void addIncomingVideoRow(LinearLayout rows, JSONObject file) {
        LinearLayout entry = settingsCard();
        LinearLayout.LayoutParams entryParams = new LinearLayout.LayoutParams(-1, -2);
        entryParams.bottomMargin = d(10);
        rows.addView(entry, entryParams);
        LinearLayout summary = new LinearLayout(this);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        entry.addView(summary, new LinearLayout.LayoutParams(-1, -2));
        ImageView thumbnail = new ImageView(this);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setBackground(round(PANEL, d(6)));
        LinearLayout.LayoutParams thumbnailParams = new LinearLayout.LayoutParams(d(54), d(78));
        thumbnailParams.rightMargin = d(10);
        summary.addView(thumbnail, thumbnailParams);
        loadImage(file.optString("poster_url", ""), thumbnail, false);
        LinearLayout summaryText = new LinearLayout(this);
        summaryText.setOrientation(LinearLayout.VERTICAL);
        summary.addView(summaryText, new LinearLayout.LayoutParams(0, -2, 1));
        TextView filename = labelRaw(file.optString("filename", ""), 15, Color.WHITE, true);
        filename.setMaxLines(2);
        summaryText.addView(filename);
        String kind = "series".equals(file.optString("library"))
                ? t("Serie") + " · " + t("Temporada") + " " + file.optInt("season_number")
                    + " · " + t("Capítulo") + " " + file.optInt("episode_number")
                : t("Película") + (file.isNull("year") ? "" : " · " + file.optString("year"));
        TextView info = label(kind + " · " + formatSize(file.optLong("size_bytes")), 12, MUTED, false);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(-1, -2);
        infoParams.topMargin = d(4);
        summaryText.addView(info, infoParams);
        if (file.optBoolean("request_matched")) {
            TextView request = label(t("Petición previa asociada") + ": " + file.optString("title", ""),
                    12, 0xFF79E3B7, true);
            LinearLayout.LayoutParams requestParams = new LinearLayout.LayoutParams(-1, -2);
            requestParams.topMargin = d(4);
            summaryText.addView(request, requestParams);
        }
        EditText destination = settingsField(file.optString("destination_path", ""),
                t("Ruta definitiva"), true);
        destination.setTextSize(12);
        destination.setHorizontallyScrolling(true);
        LinearLayout destinationRow = new LinearLayout(this);
        destinationRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams destinationRowParams = new LinearLayout.LayoutParams(-1, d(44));
        destinationRowParams.topMargin = d(8);
        entry.addView(destinationRow, destinationRowParams);
        LinearLayout.LayoutParams destinationParams = new LinearLayout.LayoutParams(0, d(44), 1);
        destinationRow.addView(destination, destinationParams);
        FrameLayout generateSlot = new FrameLayout(this);
        LinearLayout.LayoutParams generateSlotParams = new LinearLayout.LayoutParams(d(50), d(44));
        generateSlotParams.leftMargin = d(8);
        destinationRow.addView(generateSlot, generateSlotParams);
        Button generate = actionButton(t("Generar"), true);
        generate.setText("➜");
        generate.setTextSize(19);
        generate.setContentDescription(t("Generar"));
        generateSlot.addView(generate, new FrameLayout.LayoutParams(-1, -1));
        ProgressBar loading = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        loading.setIndeterminate(true);
        loading.setIndeterminateTintList(ColorStateList.valueOf(Color.WHITE));
        loading.setContentDescription(t("Moviendo…"));
        FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(d(26), d(26), Gravity.CENTER);
        generateSlot.addView(loading, loadingParams);
        loading.setVisibility(View.GONE);
        generate.setOnClickListener(v -> moveIncomingVideo(file, destination, generate, loading, rows));
    }

    void moveIncomingVideo(JSONObject file, EditText destination, Button generate,
                           ProgressBar loading, LinearLayout rows) {
        String fileId = file.optString("file_id", "");
        if (fileId.isEmpty()) return;
        JSONObject payload = new JSONObject();
        try { payload.put("destination_path", destination.getText().toString().trim()); }
        catch (JSONException ignored) { return; }
        generate.setEnabled(false);
        generate.setText("");
        loading.setVisibility(View.VISIBLE);
        loading.bringToFront();
        io.execute(() -> {
            try {
                JSONObject result = postJson("/api/v1/admin/incoming/" + enc(fileId) + "/generate", payload);
                runOnUiThread(() -> {
                    if (!showingAdmin) return;
                    if (result.optBoolean("request_matched")) {
                        String syncJobId = result.optString("catalog_sync_job_id", "");
                        String message = !syncJobId.isEmpty()
                                ? t("Fichero movido; sincronización del catálogo iniciada.")
                                : result.optBoolean("catalog_sync_queued")
                                    ? t("Fichero movido; sincronización del catálogo en cola.")
                                    : t("Fichero movido; sincronización no disponible (configura TMDb).");
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    } else Toast.makeText(this, t("Fichero movido a su destino."), Toast.LENGTH_SHORT).show();
                    Button detect = (Button) ((ViewGroup) rows.getParent()).findViewWithTag("incoming-detect");
                    if (detect != null) detectIncomingVideos(rows, detect);
                    else rows.removeView((View) generate.getParent());
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    generate.setEnabled(true);
                    generate.setText(t("Generar"));
                    loading.setVisibility(View.GONE);
                    Toast.makeText(this, t("No se pudo mover el fichero: ") + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    void loadContentRequestControls(LinearLayout slot) {
        io.execute(() -> {
            try {
                JSONArray requests = getArray("/api/v1/admin/content-requests");
                runOnUiThread(() -> {
                    if (!showingAdmin || slot.getParent() == null) return;
                    slot.removeAllViews();
                    slot.setVisibility(View.VISIBLE);
                    LinearLayout card = settingsCard();
                    slot.addView(card);
                    int pending = 0;
                    for (int i = 0; i < requests.length(); i++) {
                        JSONObject request = requests.optJSONObject(i);
                        if (request != null && "pending".equals(request.optString("status"))) pending++;
                    }
                    card.addView(label(t("Solicitudes de contenido") + " · " + pending
                            + " " + t("pendientes"), 16, Color.WHITE, true));
                    if (requests.length() == 0) {
                        TextView empty = label(t("No hay solicitudes de contenido."), 13, MUTED, false);
                        empty.setPadding(0, d(8), 0, 0);
                        card.addView(empty);
                        return;
                    }
                    for (int i = 0; i < requests.length(); i++) {
                        JSONObject request = requests.optJSONObject(i);
                        if (request == null) continue;
                        LinearLayout row = new LinearLayout(this);
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
                        rowParams.topMargin = d(14);
                        card.addView(row, rowParams);
                        ImageView poster = new ImageView(this);
                        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        poster.setBackground(round(PANEL, d(7)));
                        row.addView(poster, new LinearLayout.LayoutParams(d(68), d(100)));
                        loadContentRequestPoster(request, poster);
                        LinearLayout detailsColumn = new LinearLayout(this);
                        detailsColumn.setOrientation(LinearLayout.VERTICAL);
                        LinearLayout.LayoutParams detailsColumnParams = new LinearLayout.LayoutParams(0, -2, 1);
                        detailsColumnParams.leftMargin = d(10);
                        row.addView(detailsColumn, detailsColumnParams);
                        TextView title = labelRaw(request.optString("title", ""), 16, Color.WHITE, true);
                        detailsColumn.addView(title);
                        String typeLabel;
                        if ("remaining_seasons".equals(request.optString("request_type"))) {
                            typeLabel = t("Temporadas restantes");
                            JSONObject details = request.optJSONObject("details");
                            JSONArray missing = details == null ? null : details.optJSONArray("missing_seasons");
                            if (missing != null && missing.length() > 0) {
                                ArrayList<String> numbers = new ArrayList<>();
                                for (int j = 0; j < missing.length(); j++) numbers.add("T" + missing.optInt(j));
                                typeLabel += " · " + t("Faltan") + " " + android.text.TextUtils.join(", ", numbers);
                            } else if (details != null && details.optInt("missing_episode_count") > 0) {
                                typeLabel += " · " + details.optInt("missing_episode_count") + " "
                                        + t("capítulos");
                            }
                        } else {
                            typeLabel = "movie".equals(request.optString("content_type"))
                                    ? t("Película") : t("Serie");
                            int season = request.optInt("season_number");
                            if (season > 0) typeLabel += " · " + t("Temporada") + " " + season;
                        }
                        String year = request.isNull("year") ? "" : request.optString("year");
                        if (!year.isEmpty()) typeLabel += " · " + year;
                        typeLabel += " · " + t("Solicitó") + ": " + request.optString("requester_name", "");
                        String requestStatus = request.optString("status");
                        typeLabel += " · " + ("approved".equals(requestStatus)
                                ? t("Validada") : ("sending".equals(requestStatus)
                                ? t("Enviando a Telegram…") : t("Pendiente")));
                        TextView info = label(typeLabel, 12, MUTED, false);
                        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(-1, -2);
                        infoParams.topMargin = d(4);
                        detailsColumn.addView(info, infoParams);
                        LinearLayout actions = new LinearLayout(this);
                        actions.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
                        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
                        actionsParams.topMargin = d(7);
                        detailsColumn.addView(actions, actionsParams);
                        if ("pending".equals(requestStatus)) {
                            Button approve = actionButton(t("Validar"), true);
                            actions.addView(approve, new LinearLayout.LayoutParams(-2, d(40)));
                            approve.setOnClickListener(v -> updateContentRequest(
                                    request.optString("id"), "approve", approve, slot));
                        } else if ("approved".equals(requestStatus)) {
                            Button resend = actionButton(t("Reenviar a Telegram"), true);
                            actions.addView(resend, new LinearLayout.LayoutParams(-2, d(40)));
                            resend.setOnClickListener(v -> updateContentRequest(
                                    request.optString("id"), "resend", resend, slot));
                        }
                        Button delete = actionButton(t("Borrar"), false);
                        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(-2, d(40));
                        deleteParams.leftMargin = d(8);
                        actions.addView(delete, deleteParams);
                        delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                                .setTitle(t("Borrar solicitud"))
                                .setMessage(t("¿Borrar la solicitud de ") + request.optString("title", "") + "?")
                                .setNegativeButton(t("Cancelar"), null)
                                .setPositiveButton(t("Borrar"), (dialog, which) ->
                                        updateContentRequest(request.optString("id"), "delete", delete, slot))
                                .show());
                    }
                });
            } catch (Exception ignored) {
                // The protected endpoint stays hidden for non-administrator accounts.
            }
        });
    }

    void loadContentRequestPoster(JSONObject request, ImageView target) {
        String posterUrl = request.optString("poster_url", "");
        if (posterUrl.isEmpty() || "null".equals(posterUrl))
            posterUrl = tmdbPosterUrl(request.optString("poster_path", ""));
        if (!posterUrl.isEmpty()) {
            loadImage(posterUrl, target, false);
            return;
        }
        io.execute(() -> {
            String resolved = "";
            try {
                if ("remaining_seasons".equals(request.optString("request_type"))) {
                    String nodeId = request.optString("catalog_node_id", "");
                    if (!nodeId.isEmpty() && !"null".equals(nodeId))
                        resolved = getJson("/api/v1/catalog/" + enc(nodeId)).optString("poster_url", "");
                } else {
                    String type = request.optString("content_type", "");
                    long tmdbId = request.optLong("tmdb_id");
                    if (tmdbId > 0 && ("movie".equals(type) || "tv".equals(type))) {
                        JSONObject details = getJson("/api/v1/content-requests/tmdb/" + type + "/" + tmdbId);
                        resolved = tmdbPosterUrl(details.optString("poster_path", ""));
                    }
                }
            } catch (Exception ignored) { }
            String finalResolved = resolved;
            if (!finalResolved.isEmpty()) runOnUiThread(() -> {
                if (target.getParent() != null) loadImage(finalResolved, target, false);
            });
        });
    }

    void updateContentRequest(String requestId, String action, Button button, LinearLayout slot) {
        button.setEnabled(false);
        io.execute(() -> {
            try {
                if ("approve".equals(action)) {
                    postJson("/api/v1/admin/content-requests/" + enc(requestId) + "/approve");
                } else if ("resend".equals(action)) {
                    postJson("/api/v1/admin/content-requests/" + enc(requestId) + "/resend");
                } else {
                    authorizedProfileRequest("DELETE", "/api/v1/admin/content-requests/" + enc(requestId), null);
                }
                runOnUiThread(() -> {
                    if ("resend".equals(action))
                        Toast.makeText(this, t("Telegram enviado"), Toast.LENGTH_SHORT).show();
                    loadContentRequestControls(slot);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    button.setEnabled(true);
                    Toast.makeText(this, t("No se pudo actualizar la solicitud: ") + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    void showAccountManagement() {
        if (isActiveKidProfile()) {
            showKidAdminAccessDenied();
            return;
        }
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

    boolean canAccessAdminTools() {
        return isAccountAdmin() && !isActiveKidProfile();
    }

    void showKidAdminAccessDenied() {
        Toast.makeText(this, t("Esta opción no está disponible en un perfil infantil."), Toast.LENGTH_LONG).show();
        showingAdmin = false;
        showingDetail = false;
        loadHome();
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
                if (!jsonBoolean(current, "is_admin", false)) throw new Exception("Solo los administradores pueden gestionar cuentas.");
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
                        boolean approved = jsonBoolean(account, "approved", false);
                        boolean disabled = jsonBoolean(account, "disabled", false);
                        boolean isAdmin = jsonBoolean(account, "is_admin", false);
                        boolean approvalSatisfied = approved || isAdmin;
                        boolean canAccess = approvalSatisfied && !disabled;
                        String state = isCurrentAccount ? "Sesión actual"
                                : (disabled ? "Acceso denegado"
                                : (!approvalSatisfied ? "Pendiente de aprobación" : ""));
                        LinearLayout card = settingsCard();
                        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
                        cardParams.bottomMargin = d(10);
                        screen.addView(card, cardParams);
                        LinearLayout identity = new LinearLayout(this);
                        identity.setGravity(Gravity.CENTER_VERTICAL);
                        card.addView(identity, new LinearLayout.LayoutParams(-1, -2));
                        FrameLayout accountAvatar = profileAvatarView(
                                account.optString("avatar", "generic:0"), d(48), username, false);
                        LinearLayout.LayoutParams accountAvatarParams = new LinearLayout.LayoutParams(d(48), d(48));
                        accountAvatarParams.rightMargin = d(12);
                        identity.addView(accountAvatar, accountAvatarParams);
                        LinearLayout identityDetails = new LinearLayout(this);
                        identityDetails.setOrientation(LinearLayout.VERTICAL);
                        identityDetails.setGravity(Gravity.CENTER_VERTICAL);
                        identity.addView(identityDetails, new LinearLayout.LayoutParams(0, -2, 1));
                        TextView userLabel = label(username + (isAdmin ? "  ·  " + t("Admin") : ""), 16, Color.WHITE, true);
                        identityDetails.addView(userLabel);
                        if (!state.isEmpty()) {
                            TextView stateLabel = label(state, 12,
                                    !approvalSatisfied || disabled ? 0xFFFFB1B5 : 0xFF79E3B7, false);
                            LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(-1, -2);
                            stateParams.topMargin = d(4);
                            identityDetails.addView(stateLabel, stateParams);
                        }

                        LinearLayout actions = new LinearLayout(this);
                        actions.setGravity(Gravity.CENTER_VERTICAL);
                        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
                        actionsParams.topMargin = d(10);
                        card.addView(actions, actionsParams);
                        if (isCurrentAccount) continue;
                        Button allow = actionButton("Permitido", canAccess);
                        actions.addView(allow, new LinearLayout.LayoutParams(0, d(44), 1));
                        allow.setOnClickListener(v -> {
                            if (canAccess) return;
                            confirmAccountChange(username, "Permitir acceso",
                                    t("¿Permitir que ") + username + t(" acceda al catálogo?"), () -> {
                                        try { postJson("/api/v1/admin/accounts/" + enc(accountId) + "/approve"); }
                                        catch (Exception e) { throw new RuntimeException(e); }
                                    });
                        });
                        Button deny = actionButton("Denegado", !canAccess);
                        LinearLayout.LayoutParams denyParams = new LinearLayout.LayoutParams(0, d(44), 1);
                        denyParams.leftMargin = d(6);
                        actions.addView(deny, denyParams);
                        deny.setOnClickListener(v -> {
                            if (!canAccess) return;
                            confirmAccountChange(username, "Denegar acceso",
                                    t("¿Denegar temporalmente a ") + username + t("? Podrás permitirlo después."), () -> {
                                        try { adminAccountRequest("POST", "/api/v1/admin/accounts/" + enc(accountId) + "/disable"); }
                                        catch (Exception e) { throw new RuntimeException(e); }
                                    });
                        });
                        Button delete = actionButton("Borrar", false);
                        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, d(44), 1);
                        deleteParams.leftMargin = d(6);
                        actions.addView(delete, deleteParams);
                        delete.setOnClickListener(v -> confirmAccountChange(username, "Borrar cuenta",
                                t("¿Borrar definitivamente la cuenta ") + username + t(" y sus perfiles?"), () -> {
                                    try { adminAccountRequest("DELETE", "/api/v1/admin/accounts/" + enc(accountId)); }
                                    catch (Exception e) { throw new RuntimeException(e); }
                                }));
                    }
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? t("No se pudieron cargar las cuentas.") : e.getMessage();
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
        new AlertDialog.Builder(this).setTitle(t(title)).setMessage(t(message))
                .setNegativeButton(t("Cancelar"), null)
                .setPositiveButton(t("Confirmar"), (dialog, which) -> io.execute(() -> {
                    try {
                        change.run();
                        runOnUiThread(() -> {
                            Toast.makeText(this, t("Cuenta actualizada: ") + username, Toast.LENGTH_SHORT).show();
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
                boolean isAdmin = jsonBoolean(verifiedAccount, "is_admin", false);
                if (!isAdmin
                        && (!verifiedAccount.has("approved") || !jsonBoolean(verifiedAccount, "approved", false))) {
                    throw new Exception("El servidor no confirma la aprobación de esta cuenta. Actualiza el servidor antes de continuar.");
                }
                String previousUsername = prefs.getString("account_email", "");
                if (!previousUsername.equalsIgnoreCase(username)) {
                    clearLocalProfilesForAccountSwitch();
                }
                storeClientApiToken(deviceToken);
                prefs.edit().putString("client_device_id", deviceId)
                        .putString("account_email", username).putBoolean("account_is_admin", isAdmin)
                        .remove("profile_sync_token").apply();
                incomingFilesCache = null;
                if (isAdmin) prefetchIncomingVideosForAdmin();
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

    boolean jsonBoolean(JSONObject object, String key, boolean fallback) {
        if (object == null || !object.has(key) || object.isNull(key)) return fallback;
        Object value = object.opt(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        if (value instanceof String) {
            String normalized = ((String) value).trim();
            if ("1".equals(normalized) || "true".equalsIgnoreCase(normalized) || "yes".equalsIgnoreCase(normalized)) return true;
            if ("0".equals(normalized) || "false".equalsIgnoreCase(normalized) || "no".equalsIgnoreCase(normalized)) return false;
        }
        return fallback;
    }

    void checkForClientUpdate(boolean force) {
        if (prefs == null || updateCheckInProgress) return;
        long now = System.currentTimeMillis();
        if (!force && now - prefs.getLong("client_update_checked_at", 0L) < 15L * 60L * 1000L) return;
        updateCheckInProgress = true;
        prefs.edit().putLong("client_update_checked_at", now).apply();
        io.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(server + "/api/v1/client/update").openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(20000);
                connection.setRequestProperty("Accept", "application/json");
                int status = connection.getResponseCode();
                InputStream input = status < 400 ? connection.getInputStream() : connection.getErrorStream();
                String body = readText(input);
                connection.disconnect();
                if (status < 200 || status >= 300) throw new Exception("HTTP " + status);
                JSONObject update = new JSONObject(body);
                int version = update.optInt("version_code", 0);
                if (version > installedClientVersionCode() && version != lastPromptedUpdateCode) {
                    runOnUiThread(() -> showUpdateAvailable(update));
                }
            } catch (Exception ignored) {
                // Update checks are optional; offline clients continue normally.
            } finally {
                updateCheckInProgress = false;
            }
        });
    }

    void showUpdateAvailable(JSONObject update) {
        int version = update.optInt("version_code", 0);
        if (version <= installedClientVersionCode() || isFinishing()) return;
        lastPromptedUpdateCode = version;
        String versionName = update.optString("version_name", "");
        String title = versionName.isEmpty() ? t("Hay una actualización de Zinema")
                : t("Nueva versión disponible") + " " + versionName;
        new AlertDialog.Builder(this).setTitle(title)
                .setMessage(t("Descarga la nueva versión para seguir usando el cliente actualizado."))
                .setPositiveButton(t("Actualizar"), (dialog, which) -> downloadAndInstallUpdate(update))
                .setNegativeButton(t("Más tarde"), null).show();
    }

    int installedClientVersionCode() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; }
        catch (PackageManager.NameNotFoundException e) { return 0; }
    }

    void downloadAndInstallUpdate(JSONObject update) {
        Toast.makeText(this, t("Descargando actualización…"), Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            File apk = new File(getCacheDir(), "zinema-prod.apk");
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(server + "/api/v1/client/update/apk").openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(120000);
                connection.setRequestProperty("Accept", "application/vnd.android.package-archive");
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) throw new Exception("HTTP " + status);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[32768];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        digest.update(buffer, 0, count);
                        output.write(buffer, 0, count);
                    }
                }
                String expectedHash = update.optString("sha256", "");
                String actualHash = hex(digest.digest());
                if (expectedHash.isEmpty() || !expectedHash.equalsIgnoreCase(actualHash)) {
                    apk.delete();
                        throw new Exception(t("La descarga no coincide con la versión publicada."));
                }
                runOnUiThread(() -> startApkInstall(apk));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        t("No se pudo descargar la actualización: ") + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    void startApkInstall(File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            installAfterPermission = true;
            new AlertDialog.Builder(this).setTitle(t("Permitir actualizaciones"))
                    .setMessage(t("Android necesita permiso para instalar actualizaciones descargadas desde Zinema."))
                    .setPositiveButton(t("Abrir ajustes"), (dialog, which) -> {
                        Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(settings);
                    }).setNegativeButton(t("Cancelar"), null).show();
            return;
        }
        try {
            Uri apkUri = Uri.parse("content://com.zinema.client.updates/zinema-prod.apk");
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(apkUri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(install);
        } catch (Exception e) {
            Toast.makeText(this, t("No se pudo abrir el instalador de Android: ") + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    boolean isFavorite(JSONObject node) {
        return prefs.contains(favoriteKey(node.optString("id")));
    }

    void toggleFavorite(JSONObject node) {
        String key = favoriteKey(node.optString("id"));
        if (prefs.contains(key)) prefs.edit().remove(key).apply();
        else prefs.edit().putString(key, node.toString()).apply();
        scheduleProfileStateSync();
        Toast.makeText(this, prefs.contains(key) ? t("Añadido a Mi lista") : t("Eliminado de Mi lista"), Toast.LENGTH_SHORT).show();
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

    String nextEpisodeKey() { return "next_episode:" + activeProfileId(); }
    String seriesNextEpisodePrefix() { return "series_next_episode:" + activeProfileId() + ":"; }
    String seriesNextEpisodeKey(String seriesId) { return seriesNextEpisodePrefix() + seriesId; }

    List<JSONObject> readSeriesNextEpisodes() {
        List<JSONObject> list = new ArrayList<>();
        String prefix = seriesNextEpisodePrefix();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            try {
                JSONObject item = new JSONObject(String.valueOf(entry.getValue()));
                String mediaId = item.optString("media_id", item.optString("id", ""));
                if (!mediaId.isEmpty() && isMediaSeen(item)) continue;
                list.add(item);
            } catch (JSONException ignored) { }
        }
        Collections.sort(list, Comparator.comparingInt(value -> value.optInt("season_number")));
        return list;
    }

    JSONObject readNextEpisode() {
        try {
            String raw = prefs.getString(nextEpisodeKey(), "");
            if (!raw.isEmpty()) return new JSONObject(raw);
        } catch (JSONException ignored) { }
        return null;
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
        horizontal.setClipChildren(false);
        horizontal.setTag("zinema-lazy-posters");
        LinearLayout cards = new LinearLayout(this);
        cards.setClipChildren(false);
        cards.setPadding(d(16), 0, d(16), d(8));
        for (JSONObject item : items) {
            boolean nextEpisodeRow = item.optBoolean("next_episode_row", false);
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
            String actionLabel;
            if (nextEpisodeRow) {
                actionLabel = "next_season".equals(item.optString("suggestion_type"))
                        ? "▶  " + t("EMPEZAR SIGUIENTE TEMPORADA")
                        : "▶  " + t("SIGUIENTE CAPÍTULO");
            } else {
                actionLabel = "season".equals(item.optString("owner_kind"))
                        ? "▶  " + t("CONTINUAR CAPÍTULO") + " · " + formatTime(item.optInt("position"))
                        : "▶  " + t("CONTINUAR PELÍCULA") + " · " + formatTime(item.optInt("position"));
            }
            details.addView(label(actionLabel,
                    10, 0xFFFF858A, true));
            String cardTitle = item.optString("series_title", item.optString("title", "Vídeo"));
            TextView name = labelRaw(cardTitle, 14, Color.WHITE, true);
            name.setMaxLines(2);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, -2);
            nameParams.topMargin = d(5);
            details.addView(name, nameParams);
            String episodeTitle = item.optString("episode_title", "");
            if (!episodeTitle.isEmpty() && !episodeTitle.equals(cardTitle)) {
                TextView episode = labelRaw(episodeTitle, 12, 0xFFD4D7E0, false);
                episode.setMaxLines(1);
                episode.setEllipsize(android.text.TextUtils.TruncateAt.END);
                details.addView(episode);
            }
            String episodeMarker = "";
            int markerSeason = item.optInt("season_number");
            int markerEpisode = item.optInt("episode_number");
            if (markerSeason > 0 && markerEpisode > 0)
                episodeMarker = t("Temporada") + " " + markerSeason + " · " + t("Capítulo") + " " + markerEpisode;
            else if (markerSeason > 0) episodeMarker = t("Temporada") + " " + markerSeason;
            if (!episodeMarker.isEmpty()) details.addView(labelRaw(episodeMarker, 11, 0xFFB8BEC9, false));
            card.addView(details, new FrameLayout.LayoutParams(-1, -1));
            boolean nextSeasonRow = nextEpisodeRow && "next_season".equals(item.optString("suggestion_type"));
            String cover = nextSeasonRow ? posterField(item, "season_poster_url")
                    : posterField(item, "poster_url");
            if (cover.isEmpty() && !nextSeasonRow)
                cover = posterByStream.get(item.optString("stream", ""));
            if (cover.isEmpty()) cover = nextSeasonRow ? posterField(item, "series_poster_url")
                    : posterField(item, "season_poster_url");
            if (cover.isEmpty() && !nextSeasonRow) cover = posterField(item, "series_poster_url");
            String resumePoster = cover == null ? "" : cover;
            showPoster(resumePoster, poster, true);
            card.setOnClickListener(v -> {
                if (nextEpisodeRow) prefs.edit()
                        .remove(watchKey(item.optString("stream"))).apply();
                launchPlayer(item.optString("stream"),
                    item.optString("title", "Zinema"), resumePoster,
                    item.optString("owner_node_id", item.optString("node_id", "")),
                    item.optString("media_id", ""), item.optString("owner_kind", ""));
            });
            card.setOnLongClickListener(v -> {
                showResumeActions(item);
                return true;
            });
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
        if (isActiveKidProfile()) {
            JSONObject owner = findCatalogNodeById(ownerNodeId, movies);
            if (owner == null) owner = findCatalogNodeById(ownerNodeId, series);
            if (owner != null && !isKidAllowedNode(owner)) {
                Toast.makeText(this, t("Este contenido no está disponible en un perfil infantil."), Toast.LENGTH_LONG).show();
                return;
            }
            boolean allowedSeason = owner == null && "season".equals(ownerKind)
                    && kidAllowedSeasonIds.contains(ownerNodeId);
            if (owner == null && !allowedSeason) {
                Toast.makeText(this, t("Este contenido no está disponible en un perfil infantil."), Toast.LENGTH_LONG).show();
                return;
            }
        }
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
            Toast.makeText(this, t("No se pudo identificar el vídeo para reproducirlo."), Toast.LENGTH_LONG).show();
            return;
        }
        final String resolvedItemId = itemId;
        String key = watchKey(path);
        long position = 0L;
        try { position = new JSONObject(prefs.getString(key, "{}")).optLong("position"); }
        catch (JSONException ignored) { }
        final long resumePosition = position;
        Toast.makeText(this, t("Preparando reproducción…"), Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try {
                JSONObject ticket = postJson("/api/v1/items/" + enc(resolvedItemId) + "/playback-ticket");
                JSONObject nextEpisode = null;
                String seriesId = "";
                int seasonNumber = 0;
                if ("season".equals(ownerKind) && ownerNodeId != null && !ownerNodeId.isEmpty()) {
                    try {
                        JSONObject playingSeason = getJson("/api/v1/catalog/" + enc(ownerNodeId));
                        seriesId = playingSeason.optString("parent_id", "");
                        seasonNumber = playingSeason.optInt("season_number");
                        nextEpisode = findNextEpisode(ownerNodeId, resolvedItemId);
                        if (nextEpisode != null && !seriesId.isEmpty()) {
                            nextEpisode.put("series_id", seriesId);
                            nextEpisode.put("suggestion_type", ownerNodeId.equals(nextEpisode.optString("owner_node_id"))
                                    ? "next_episode" : "next_season");
                        }
                    }
                    catch (Exception ignored) { }
                }
                String ticketPath = ticket.optString("url", "");
                if (ticketPath.isEmpty()) throw new Exception("El servidor no devolvió el acceso temporal al vídeo.");
                String url = ticketPath.startsWith("http") ? ticketPath
                        : server + (ticketPath.startsWith("/") ? ticketPath : "/" + ticketPath);
                String nextEpisodeJson = nextEpisode == null ? "" : nextEpisode.toString();
                String playingSeriesId = seriesId;
                int playingSeasonNumber = seasonNumber;
                runOnUiThread(() -> startPlayerActivity(url, path, title, posterUrl, ownerNodeId,
                        resolvedItemId, ownerKind, key, resumePosition, nextEpisodeJson,
                        playingSeriesId, playingSeasonNumber));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        t("No se pudo preparar el vídeo: ") + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    void startPlayerActivity(String url, String path, String title, String posterUrl,
                             String ownerNodeId, String mediaId, String ownerKind,
                             String key, long position, String nextEpisodeJson,
                             String seriesId, int seasonNumber) {
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
        intent.putExtra("next_episode_json", nextEpisodeJson);
        intent.putExtra("series_id", seriesId);
        intent.putExtra("season_number", seasonNumber);
        playerLaunched = true;
        startActivity(intent);
    }

    JSONObject findNextEpisode(String seasonId, String mediaId) throws Exception {
        JSONObject season = getJson("/api/v1/catalog/" + enc(seasonId));
        JSONArray files = season.optJSONArray("files");
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.optJSONObject(i);
                if (file != null && mediaId.equals(file.optString("id")) && i + 1 < files.length())
                    return nextEpisodePayload(files.optJSONObject(i + 1), season);
            }
        }
        String seriesId = season.optString("parent_id", "");
        if (seriesId.isEmpty() || "null".equals(seriesId)) return null;
        JSONObject seriesNode = getJson("/api/v1/catalog/" + enc(seriesId));
        List<JSONObject> seasons = toList(seriesNode.optJSONArray("children"));
        Collections.sort(seasons, Comparator.comparingInt(value -> value.optInt("season_number")));
        boolean afterCurrent = false;
        for (JSONObject entry : seasons) {
            if (seasonId.equals(entry.optString("id"))) { afterCurrent = true; continue; }
            if (!afterCurrent) continue;
            JSONObject followingSeason = getJson("/api/v1/catalog/" + enc(entry.optString("id")));
            JSONArray followingFiles = followingSeason.optJSONArray("files");
            if (followingFiles != null && followingFiles.length() > 0)
                return nextEpisodePayload(followingFiles.optJSONObject(0), followingSeason);
        }
        return null;
    }

    JSONObject nextEpisodePayload(JSONObject file, JSONObject season) {
        if (file == null || season == null) return null;
        JSONObject next = new JSONObject();
        String stream = file.optString("stream_url", "");
        try {
            next.put("id", file.optString("id", ""));
            next.put("title", file.optString("title", "Siguiente capítulo"));
            next.put("stream", stream);
            next.put("stream_url", stream);
            String seasonPoster = posterField(season, "poster_url");
            String episodePoster = posterField(file, "poster_url");
            if (episodePoster.isEmpty()) episodePoster = seasonPoster;
            next.put("poster_url", episodePoster);
            next.put("season_poster_url", seasonPoster);
            next.put("media_id", file.optString("id", ""));
            next.put("owner_node_id", season.optString("id", ""));
            next.put("owner_kind", "season");
            JSONObject seriesNode = findSeriesForSeason(season.optString("id", ""));
            if (seriesNode != null) {
                next.put("series_id", seriesNode.optString("id", ""));
                next.put("series_poster_url", posterField(seriesNode, "poster_url"));
            }
            int seasonNumber = season.optInt("season_number");
            int episodeNumber = file.optInt("episode_number", file.optInt("episode", 0));
            next.put("season_number", seasonNumber);
            next.put("episode_number", episodeNumber);
            if (seasonNumber > 0 && episodeNumber > 0)
                next.put("season_episode_label", "Temporada " + seasonNumber + " · Capítulo " + episodeNumber);
            else if (seasonNumber > 0)
                next.put("season_episode_label", "Temporada " + seasonNumber);
        } catch (JSONException ignored) { return null; }
        return next;
    }

    @Override protected void onResume() {
        super.onResume();
        checkForClientUpdate(false);
        if (installAfterPermission && Build.VERSION.SDK_INT >= 26 && getPackageManager().canRequestPackageInstalls()) {
            installAfterPermission = false;
            File apk = new File(getCacheDir(), "zinema-prod.apk");
            if (apk.isFile()) startApkInstall(apk);
        }
        if (hasResumedOnce) {
            boolean returnedFromPlayer = playerLaunched;
            playerLaunched = false;
            processPlayerReturn();
            if (returnedFromPlayer && showingDetail) refreshCurrentScreen();
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

    void processPlayerReturn() {
        String profileId = activeProfileId();
        if (profileId.isEmpty() || advancingCompletedEpisode) return;
        String completedMedia = prefs.getString("completed_media:" + profileId, "");
        String completedOwner = prefs.getString("completed_owner:" + profileId, "");
        String completedKind = prefs.getString("completed_kind:" + profileId, "");
        String autoPlay = prefs.getString("auto_play_episode:" + profileId, "");
        if (completedMedia.isEmpty() && autoPlay.isEmpty()) return;
        prefs.edit().remove("completed_media:" + profileId).remove("completed_owner:" + profileId)
                .remove("completed_kind:" + profileId).remove("auto_play_episode:" + profileId)
                .apply();
        refreshCurrentScreen();
        if (!completedOwner.isEmpty() && "season".equals(completedKind)) {
            advancingCompletedEpisode = true;
            io.execute(() -> {
                try {
                    JSONObject season = getJson("/api/v1/catalog/" + enc(completedOwner));
                    runOnUiThread(() -> {
                        refreshSeasonSeen(season);
                        advancingCompletedEpisode = false;
                        refreshCurrentScreen();
                    });
                } catch (Exception ignored) {
                    runOnUiThread(() -> advancingCompletedEpisode = false);
                }
            });
        }
        if (!autoPlay.isEmpty()) {
            try {
                JSONObject next = new JSONObject(autoPlay);
                prefs.edit().remove(nextEpisodeKey()).apply();
                launchPlayer(next.optString("stream", next.optString("stream_url", "")),
                        next.optString("title", "Zinema"), next.optString("poster_url", ""),
                        next.optString("owner_node_id", ""), next.optString("media_id", ""),
                        next.optString("owner_kind", "season"));
            } catch (JSONException ignored) { }
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
