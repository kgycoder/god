package com.lmarena.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class ChatActivity extends AppCompatActivity {

    private static final String DIRECT_URL = "https://arena.ai/text/direct";
    private static final String ARENA_DOMAIN = "arena.ai";
    private static final String PREFS_NAME  = "lmarena_prefs";
    private static final String KEY_COOKIES = "saved_cookies";

    // UI components
    private WebView arenaWebView;        // Hidden WebView: communicates with LM Arena
    private RecyclerView chatRecycler;
    private EditText promptInput;
    private ImageButton sendBtn;
    private ImageButton newChatBtn;
    private ProgressBar loadingBar;
    private View emptyState;
    private Toolbar toolbar;

    private ChatAdapter chatAdapter;
    private final List<ChatMessage> messages = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean arenaReady = false;
    private boolean isGenerating = false;
    private String pendingPrompt = null;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.app_name);
        }

        arenaWebView = findViewById(R.id.arenaWebView);
        chatRecycler = findViewById(R.id.chatRecycler);
        promptInput = findViewById(R.id.promptInput);
        sendBtn = findViewById(R.id.sendBtn);
        newChatBtn = findViewById(R.id.newChatBtn);
        loadingBar = findViewById(R.id.loadingBar);
        emptyState = findViewById(R.id.emptyState);

        setupChatList();
        setupArenaWebView();
        setupInput();

        // Load arena
        loadingBar.setVisibility(View.VISIBLE);
        arenaWebView.loadUrl(DIRECT_URL);
    }

    private void setupChatList() {
        chatAdapter = new ChatAdapter(messages);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        chatRecycler.setLayoutManager(lm);
        chatRecycler.setAdapter(chatAdapter);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupArenaWebView() {
        WebSettings settings = arenaWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        );

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(arenaWebView, true);

        // JavaScript bridge
        arenaWebView.addJavascriptInterface(new ArenaJsBridge(), "AndroidBridge");

        arenaWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // Stay within arena.ai and auth pages
                if (url.contains(ARENA_DOMAIN) || url.contains("google.com") ||
                    url.contains("accounts.google") || url.contains("oauth2")) {
                    return false;
                }
                return true; // block other navigations
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url.contains(ARENA_DOMAIN)) {
                    // Check if logged out (redirected to login)
                    view.evaluateJavascript(
                        "(function(){ return document.body ? document.body.innerText : ''; })()",
                        text -> {
                            if (text != null && (text.contains("Sign in") || text.contains("sign in") ||
                                text.contains("log in") || text.contains("Experience the frontier"))) {
                                // Not logged in
                                mainHandler.post(() -> {
                                    loadingBar.setVisibility(View.GONE);
                                    showLoggedOutDialog();
                                });
                            } else {
                                mainHandler.post(() -> {
                                    loadingBar.setVisibility(View.GONE);
                                    arenaReady = true;
                                    if (pendingPrompt != null) {
                                        String p = pendingPrompt;
                                        pendingPrompt = null;
                                        sendToArena(p);
                                    }
                                });
                            }
                        }
                    );
                }
            }
        });

        arenaWebView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress == 100) {
                    mainHandler.postDelayed(() -> injectPolling(), 500);
                }
            }
        });
    }

    /** Inject a JS poller that watches the arena response and reports back */
    private void injectPolling() {
        String js =
            "(function() {" +
            "  if (window._lmBridgeActive) return;" +
            "  window._lmBridgeActive = true;" +
            "  window._lmLastText = '';" +
            "  window._lmGenerating = false;" +
            "  function getLatestResponse() {" +
            "    var containers = document.querySelectorAll('[data-testid=\"bot-turn\"], .prose, .assistant-turn, [class*=\"assistant\"], [class*=\"response\"]');" +
            "    if (!containers.length) return '';" +
            "    var last = containers[containers.length - 1];" +
            "    return last ? (last.innerText || '') : '';" +
            "  }" +
            "  function isGenerating() {" +
            "    return !!(document.querySelector('[class*=\"loading\"], [class*=\"generating\"], [class*=\"spinner\"], .animate-spin'));" +
            "  }" +
            "  setInterval(function() {" +
            "    var gen = isGenerating();" +
            "    var text = getLatestResponse();" +
            "    if (gen && !window._lmGenerating) {" +
            "      window._lmGenerating = true;" +
            "      AndroidBridge.onGenerationStart();" +
            "    }" +
            "    if (window._lmGenerating && text !== window._lmLastText) {" +
            "      window._lmLastText = text;" +
            "      AndroidBridge.onResponseUpdate(text);" +
            "    }" +
            "    if (!gen && window._lmGenerating) {" +
            "      window._lmGenerating = false;" +
            "      AndroidBridge.onGenerationEnd(text);" +
            "      window._lmLastText = '';" +
            "    }" +
            "  }, 400);" +
            "})();";
        arenaWebView.evaluateJavascript(js, null);
    }

    private void setupInput() {
        sendBtn.setOnClickListener(v -> submitPrompt());
        newChatBtn.setOnClickListener(v -> newChat());

        promptInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitPrompt();
                return true;
            }
            return false;
        });
    }

    private void submitPrompt() {
        String text = promptInput.getText().toString().trim();
        if (text.isEmpty() || isGenerating) return;

        promptInput.setText("");
        addUserMessage(text);

        if (!arenaReady) {
            pendingPrompt = text;
            loadingBar.setVisibility(View.VISIBLE);
            return;
        }
        sendToArena(text);
    }

    private void sendToArena(String prompt) {
        // Escape JS string
        String escaped = prompt
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace("`", "\\`");

        String js =
            "(function() {" +
            "  var selectors = [" +
            "    'textarea[placeholder]'," +
            "    'textarea[data-testid]'," +
            "    'div[contenteditable=\"true\"]'," +
            "    '#prompt-textarea'," +
            "    'textarea'" +
            "  ];" +
            "  var input = null;" +
            "  for (var i = 0; i < selectors.length; i++) {" +
            "    input = document.querySelector(selectors[i]);" +
            "    if (input) break;" +
            "  }" +
            "  if (!input) { AndroidBridge.onError('Input not found'); return; }" +
            "  var nativeInputSetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement ? window.HTMLTextAreaElement.prototype : Object.prototype, 'value');" +
            "  if (nativeInputSetter && nativeInputSetter.set) {" +
            "    nativeInputSetter.set.call(input, '" + escaped + "');" +
            "  } else {" +
            "    input.value = '" + escaped + "';" +
            "  }" +
            "  input.dispatchEvent(new Event('input', {bubbles:true}));" +
            "  input.dispatchEvent(new Event('change', {bubbles:true}));" +
            "  setTimeout(function() {" +
            "    var btns = document.querySelectorAll('button[type=\"submit\"], button[aria-label*=\"send\" i], button[aria-label*=\"전송\" i], button[data-testid*=\"send\" i]');" +
            "    var btn = btns.length ? btns[btns.length - 1] : null;" +
            "    if (btn && !btn.disabled) { btn.click(); }" +
            "    else { input.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', keyCode:13, bubbles:true})); }" +
            "  }, 200);" +
            "})();";

        arenaWebView.evaluateJavascript(js, null);
        isGenerating = true;
        loadingBar.setVisibility(View.VISIBLE);
    }

    private void newChat() {
        if (isGenerating) return;
        messages.clear();
        chatAdapter.notifyDataSetChanged();
        emptyState.setVisibility(View.VISIBLE);
        arenaReady = false;
        arenaWebView.loadUrl(DIRECT_URL);
        loadingBar.setVisibility(View.VISIBLE);
    }

    private void addUserMessage(String text) {
        emptyState.setVisibility(View.GONE);
        messages.add(new ChatMessage(text, ChatMessage.TYPE_USER));
        chatAdapter.notifyItemInserted(messages.size() - 1);
        chatRecycler.scrollToPosition(messages.size() - 1);
    }

    private void addOrUpdateAssistantMessage(String text, boolean done) {
        if (messages.isEmpty() || messages.get(messages.size() - 1).type == ChatMessage.TYPE_USER) {
            messages.add(new ChatMessage(text, ChatMessage.TYPE_ASSISTANT));
            chatAdapter.notifyItemInserted(messages.size() - 1);
        } else {
            ChatMessage last = messages.get(messages.size() - 1);
            last.text = text;
            chatAdapter.notifyItemChanged(messages.size() - 1);
        }
        chatRecycler.scrollToPosition(messages.size() - 1);

        if (done) {
            isGenerating = false;
            loadingBar.setVisibility(View.GONE);
        }
    }

    private void showLoggedOutDialog() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.login_required_title)
            .setMessage(R.string.login_required_msg)
            .setPositiveButton(R.string.go_to_login, (d, w) -> {
                startActivity(new Intent(this, MainActivity.class));
                finish();
            })
            .setCancelable(false)
            .show();
    }

    private void logout() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.logout)
            .setMessage(R.string.logout_confirm)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                CookieManager.getInstance().removeAllCookies(null);
                CookieManager.getInstance().flush();
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .remove(KEY_COOKIES)
                    .apply();
                startActivity(new Intent(this, MainActivity.class));
                finish();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.chat_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_logout) {
            logout();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** JavaScript bridge — called from injected JS in the hidden WebView */
    private class ArenaJsBridge {
        @JavascriptInterface
        public void onGenerationStart() {
            mainHandler.post(() -> {
                isGenerating = true;
                loadingBar.setVisibility(View.VISIBLE);
            });
        }

        @JavascriptInterface
        public void onResponseUpdate(String text) {
            mainHandler.post(() -> addOrUpdateAssistantMessage(text, false));
        }

        @JavascriptInterface
        public void onGenerationEnd(String text) {
            mainHandler.post(() -> addOrUpdateAssistantMessage(text, true));
        }

        @JavascriptInterface
        public void onError(String msg) {
            mainHandler.post(() -> {
                isGenerating = false;
                loadingBar.setVisibility(View.GONE);
                Toast.makeText(ChatActivity.this, getString(R.string.error_prefix) + msg, Toast.LENGTH_LONG).show();
            });
        }
    }
}
