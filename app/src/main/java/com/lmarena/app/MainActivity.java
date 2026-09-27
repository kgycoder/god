package com.lmarena.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final String DIRECT_URL  = "https://arena.ai/text/direct";
    private static final String ARENA_HOST  = "arena.ai";
    private static final String PREFS_NAME  = "lmarena_prefs";
    private static final String KEY_COOKIES = "saved_cookies";

    // 로그인 완료를 판정하는 JS:
    //  1) 사이드바 열기 버튼(aria-label="Open sidebar")을 클릭해 패널을 연다.
    //  2) 패널 안에 "Sign in with Google" 같은 로그인 버튼이 있으면 → 미로그인
    //  3) 없으면(= 사용자 메뉴/프로필이 표시됨) → 로그인 완료
    //  4) 사이드바 버튼 자체가 없으면 아직 페이지가 덜 뜬 것 → 재시도
    private static final String JS_CHECK_LOGIN =
        "(function() {" +
        "  try {" +
        // 사이드바 열기 버튼: aria-label="Open sidebar"
        "    var sidebarBtn = document.querySelector('button[aria-label=\"Open sidebar\"]');" +
        "    if (sidebarBtn) { sidebarBtn.click(); }" +
        // 약간의 딜레이 없이 바로 읽으면 패널이 아직 안 열릴 수 있지만,
        // 이 함수는 postDelayed로 500ms 후에 호출되므로 괜찮다.
        // 로그인 버튼 존재 여부 확인 (Google 로그인 버튼 텍스트 탐지)
        "    var body = document.body.innerText || '';" +
        "    var hasSignIn  = /sign in with google|continue with google|google로 로그인|구글로 로그인/i.test(body);" +
        "    var hasSignIn2 = document.querySelector(" +
        "      'button[class*=\"google\"], a[href*=\"/login\"], a[href*=\"/signin\"]," +
        "       button[data-provider=\"google\"]'" +
        "    ) !== null;" +
        // 사이드바 버튼이 있다 = 페이지는 뜸, 로그인 여부는 body 텍스트로 판단
        "    if (sidebarBtn && !hasSignIn && !hasSignIn2) {" +
        "      return 'logged_in';" +
        "    } else if (hasSignIn || hasSignIn2) {" +
        "      return 'need_login';" +
        "    } else {" +
        "      return 'loading';" +   // 아직 렌더링 중
        "    }" +
        "  } catch(e) { return 'error:' + e.message; }" +
        "})()";

    private WebView     webView;
    private ProgressBar progressBar;
    private TextView    statusText;
    private Button      loginBtn;
    private View        loginCard;

    private boolean loginDone = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // 폴링: 페이지 로드 완료 후 JS로 로그인 상태를 반복 확인
    private Runnable loginCheckRunnable;
    private int checkCount = 0;
    private static final int MAX_CHECKS = 20;   // 최대 20회 × 800ms = 16초
    private static final int CHECK_INTERVAL_MS = 800;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView     = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        statusText  = findViewById(R.id.statusText);
        loginBtn    = findViewById(R.id.loginBtn);
        loginCard   = findViewById(R.id.loginCard);

        setupWebView();
        loginBtn.setOnClickListener(v -> startLogin());

        // 저장된 쿠키가 있으면 바로 채팅으로
        String saved = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_COOKIES, null);
        if (saved != null) {
            proceedToChat();
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // 실제 Chrome Mobile UA (Google OAuth 차단 방지)
        s.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230901.001) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.6099.144 Mobile Safari/537.36"
        );

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                if (isAllowedUrl(url)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, req.getUrl()));
                } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url,
                                      android.graphics.Bitmap favicon) {
                stopLoginCheck();   // 이전 폴링 중단
                progressBar.setVisibility(View.VISIBLE);
                updateStatus(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                updateStatus(url);

                // Google 계정 페이지에서는 로그인 체크 불필요
                if (url == null || url.contains("google.com")
                        || url.contains("accounts.google")) return;

                // arena.ai 페이지가 뜬 뒤에만 로그인 상태 폴링 시작
                if (isArenaDomain(url) && !loginDone) {
                    startLoginCheck(view);
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode,
                                        String description, String failingUrl) {
                // 네트워크 에러는 그냥 무시 (사용자 재시도)
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                progressBar.setProgress(p);
                progressBar.setVisibility(p == 100 ? View.GONE : View.VISIBLE);
            }
        });
    }

    // ── 로그인 상태 폴링 ──────────────────────────────────────────────

    /** 페이지 로드 완료 후 JS로 반복 확인 */
    private void startLoginCheck(WebView view) {
        checkCount = 0;
        stopLoginCheck();
        loginCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (loginDone || isFinishing() || isDestroyed()) return;
                if (checkCount++ > MAX_CHECKS) {
                    // 타임아웃: 사용자에게 수동 로그인 유도
                    handler.post(() -> statusText.setText("페이지를 확인해주세요."));
                    return;
                }
                view.evaluateJavascript(JS_CHECK_LOGIN, result -> {
                    if (loginDone) return;
                    // result 는 JSON 문자열이라 따옴표 포함: "\"logged_in\""
                    String r = (result == null ? "" : result.replace("\"", "").trim());
                    if ("logged_in".equals(r)) {
                        loginDone = true;
                        saveCookies();
                        handler.post(() -> proceedToChat());
                    } else if ("need_login".equals(r)) {
                        // 로그인 버튼이 보임 → 사용자가 직접 누를 때까지 대기
                        handler.post(() ->
                            statusText.setText("Google 로그인 버튼을 눌러 로그인해주세요."));
                        // 계속 폴링해서 로그인 완료를 감지
                        handler.postDelayed(this, CHECK_INTERVAL_MS * 2L);
                    } else {
                        // "loading" 또는 에러 → 재시도
                        handler.postDelayed(this, CHECK_INTERVAL_MS);
                    }
                });
            }
        };
        // 첫 실행은 500ms 딜레이 (사이드바 클릭 애니메이션 대기)
        handler.postDelayed(loginCheckRunnable, 500);
    }

    private void stopLoginCheck() {
        if (loginCheckRunnable != null) {
            handler.removeCallbacks(loginCheckRunnable);
            loginCheckRunnable = null;
        }
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────

    private boolean isAllowedUrl(String url) {
        if (url == null) return false;
        return url.contains("arena.ai")
            || url.contains("google.com")
            || url.contains("googleapis.com")
            || url.contains("gstatic.com")
            || url.contains("accounts.google")
            || url.contains("oauth2")
            || url.contains("recaptcha")
            || url.contains("firebase");
    }

    private boolean isArenaDomain(String url) {
        return url != null && url.contains(ARENA_HOST);
    }

    private void updateStatus(String url) {
        if (url == null) return;
        if (url.contains("google.com") || url.contains("accounts.google")) {
            statusText.setText("Google 계정 인증 중...");
        } else if (url.contains(ARENA_HOST)) {
            statusText.setText("LM Arena 로그인 확인 중...");
        } else {
            statusText.setText("연결 중...");
        }
    }

    private void startLogin() {
        loginDone = false;
        checkCount = 0;
        loginCard.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
        statusText.setVisibility(View.VISIBLE);
        statusText.setText("LM Arena 연결 중...");
        webView.loadUrl(DIRECT_URL);
    }

    private void saveCookies() {
        CookieManager.getInstance().flush();
        String cookies = CookieManager.getInstance().getCookie(ARENA_HOST);
        if (cookies == null) cookies = "present"; // 쿠키가 있다는 표시만 저장
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_COOKIES, cookies)
            .apply();
    }

    private void proceedToChat() {
        if (isFinishing() || isDestroyed()) return;
        stopLoginCheck();
        startActivity(new Intent(this, ChatActivity.class));
        finish();
    }

    @Override
    public void onBackPressed() {
        if (webView.getVisibility() == View.VISIBLE) {
            if (webView.canGoBack()) {
                webView.goBack();
            } else {
                stopLoginCheck();
                webView.setVisibility(View.GONE);
                loginCard.setVisibility(View.VISIBLE);
                statusText.setVisibility(View.GONE);
                loginDone = false;
            }
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        stopLoginCheck();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
