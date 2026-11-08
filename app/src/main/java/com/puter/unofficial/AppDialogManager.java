package com.puter.unofficial;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import androidx.webkit.WebViewAssetLoader;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.puter.unofficial.adapters.FullscreenGalleryAdapter;
import com.puter.unofficial.adapters.LogConsoleAdapter;
import com.puter.unofficial.http.LocalHttpServerManager;
import com.puter.unofficial.logging.LiveLogManager;
import com.puter.unofficial.logging.LogEntry;
import com.puter.unofficial.models.ChatSession;
import com.puter.unofficial.models.ScrapedImageItem;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Dedicated Dialog and Overlay Manager for Puter Unofficial.
 * Encapsulates all modal sheets, full-screen code/image previews,
 * diagnostic consoles, fullscreen swipeable galleries, rich help manuals,
 * Formspree contact support dialogs, and the Puter OAuth WebView dialog.
 */
public class AppDialogManager {

    public interface OnSessionSelectedListener {
        void onSessionSelected(ChatSession session);
    }

    private final Activity activity;
    private final SharedPreferences prefs;
    private final ChatSessionManager sessionManager;
    private final GeminiService geminiService;
    private final Runnable onAuthSuccessCallback;

    private Dialog activeAuthDialog;
    private Dialog activePortalDialog;
    private BottomSheetDialog activeBottomSheet;
    private boolean isPuterAuthProcessing = false;

    public AppDialogManager(Activity activity,
                            SharedPreferences prefs,
                            ChatSessionManager sessionManager,
                            GeminiService geminiService,
                            Runnable onAuthSuccessCallback) {
        this.activity = activity;
        this.prefs = prefs;
        this.sessionManager = sessionManager;
        this.geminiService = geminiService;
        this.onAuthSuccessCallback = onAuthSuccessCallback;
    }

    // --- SYSTEM DIAGNOSTIC LOG CONSOLE ---

    public void openSystemLogConsole() {
        BottomSheetDialog dialog = new BottomSheetDialog(activity, R.style.BottomSheetDialogTheme);
        activeBottomSheet = dialog;
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        dialog.getBehavior().setSkipCollapsed(true);

        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_system_log_console, null);

        ImageView btnMinimize = sheet.findViewById(R.id.btnMinimizeConsole);
        EditText etSearch = sheet.findViewById(R.id.etLogSearch);
        ImageView btnClearSearch = sheet.findViewById(R.id.btnClearSearch);
        RecyclerView rvConsole = sheet.findViewById(R.id.rvLogConsole);
        TextView tvSummary = sheet.findViewById(R.id.tvLogCountSummary);
        TextView btnClearAll = sheet.findViewById(R.id.btnClearAllLogs);
        LinearLayout llEmpty = sheet.findViewById(R.id.llEmptyLogsState);

        TextView chipAll = sheet.findViewById(R.id.chipFilterAll);
        TextView chipErrors = sheet.findViewById(R.id.chipFilterErrors);
        TextView chipCrashes = sheet.findViewById(R.id.chipFilterCrashes);
        TextView chipProcess = sheet.findViewById(R.id.chipFilterProcess);
        TextView chipPuter = sheet.findViewById(R.id.chipFilterPuter);
        TextView chipGemini = sheet.findViewById(R.id.chipFilterGemini);
        TextView chipAuth = sheet.findViewById(R.id.chipFilterAuth);

        TextView[] allChips = {chipAll, chipErrors, chipCrashes, chipProcess, chipPuter, chipGemini, chipAuth};

        LogConsoleAdapter consoleAdapter = new LogConsoleAdapter();
        rvConsole.setLayoutManager(new LinearLayoutManager(activity));
        rvConsole.setAdapter(consoleAdapter);

        Runnable refreshUI = () -> {
            int displayed = consoleAdapter.getItemCount();
            int total = consoleAdapter.getMasterCount();
            tvSummary.setText("Showing " + displayed + " of " + total + " total logs");
            llEmpty.setVisibility(displayed == 0 ? View.VISIBLE : View.GONE);
        };

        LiveLogManager logger = LiveLogManager.getInstance(activity);
        consoleAdapter.setLogs(logger.getAllLogs());
        refreshUI.run();

        LiveLogManager.LogEventListener dialogListener = new LiveLogManager.LogEventListener() {
            @Override
            public void onNewLogEntry(LogEntry entry, int totalCount, int errorCount) {
                activity.runOnUiThread(() -> {
                    consoleAdapter.addEntry(entry);
                    refreshUI.run();
                });
            }
            @Override
            public void onLogsCleared() {
                activity.runOnUiThread(() -> {
                    consoleAdapter.clearLogs();
                    refreshUI.run();
                });
            }
        };
        logger.addListener(dialogListener);
        dialog.setOnDismissListener(d -> logger.removeListener(dialogListener));

        btnMinimize.setOnClickListener(v -> dialog.dismiss());
        btnClearAll.setOnClickListener(v -> logger.clearLogs());

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                btnClearSearch.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                consoleAdapter.setFilterQuery(s.toString());
                refreshUI.run();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        btnClearSearch.setOnClickListener(v -> etSearch.setText(""));

        View.OnClickListener chipClickListener = v -> {
            for (TextView chip : allChips) {
                chip.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#181C2B")));
                chip.setTextColor(Color.parseColor("#8C95AB"));
            }
            v.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#7C4DFF")));
            ((TextView) v).setTextColor(Color.WHITE);

            String cat = ((TextView) v).getText().toString();
            consoleAdapter.setCategoryFilter(cat);
            refreshUI.run();
        };

        for (TextView chip : allChips) {
            chip.setOnClickListener(chipClickListener);
        }

        dialog.setContentView(sheet);
        dialog.show();
    }

    // --- CHAT HISTORY DIALOG ---

    public void showHistoryDialog(ChatSession currentSession,
                                  OnSessionSelectedListener selectListener,
                                  Runnable onAllSessionsDeleted) {
        BottomSheetDialog dialog = new BottomSheetDialog(activity, R.style.BottomSheetDialogTheme);
        activeBottomSheet = dialog;

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        TextView title = new TextView(activity);
        title.setText("Chat History");
        title.setTextSize(18);
        title.setTextColor(Color.parseColor("#333333"));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        RecyclerView rv = new RecyclerView(activity);
        rv.setLayoutManager(new LinearLayoutManager(activity));
        List<ChatSession> sessions = sessionManager.getAllSessions();

        SessionHistoryAdapter adapter = new SessionHistoryAdapter(sessions, session -> {
            if (selectListener != null) selectListener.onSessionSelected(session);
            dialog.dismiss();
        }, session -> {
            sessionManager.deleteSession(session.getId());
            if (currentSession != null && currentSession.getId().equals(session.getId())) {
                if (onAllSessionsDeleted != null) onAllSessionsDeleted.run();
            }
            dialog.dismiss();
        });
        rv.setAdapter(adapter);
        root.addView(rv);

        dialog.setContentView(root);
        dialog.show();
    }

    // --- SETTINGS HUB & SUB-DIALOGS ---

    public void showSettingsHubDialog(ChatSession currentSession, Runnable onModelsUpdated) {
        BottomSheetDialog dialog = new BottomSheetDialog(activity, R.style.BottomSheetDialogTheme);
        activeBottomSheet = dialog;
        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_settings_hub, null);

        sheet.findViewById(R.id.btnHubCustomInstruction).setOnClickListener(v -> {
            dialog.dismiss();
            showCustomInstructionDialog(currentSession);
        });

        sheet.findViewById(R.id.btnHubGeminiSettings).setOnClickListener(v -> {
            dialog.dismiss();
            showGeminiSettingsDialog(onModelsUpdated);
        });

        sheet.findViewById(R.id.btnHubNostrIdentity).setOnClickListener(v -> {
            dialog.dismiss();
            showNostrIdentityDialog();
        });

        // HTTP Bridge Settings hook (Direct kiwi web scraping transport)
        View btnHttpBridge = sheet.findViewById(R.id.btnHubHttpBridge);
        if (btnHttpBridge != null) {
            btnHttpBridge.setOnClickListener(v -> {
                dialog.dismiss();
                showHttpBridgeDialog();
            });
        }

        sheet.findViewById(R.id.btnHubClose).setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(sheet);
        dialog.show();
    }

    public void showCustomInstructionDialog(ChatSession currentSession) {
        BottomSheetDialog dialog = new BottomSheetDialog(activity, R.style.BottomSheetDialogTheme);
        activeBottomSheet = dialog;
        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_custom_instruction, null);
        EditText et = sheet.findViewById(R.id.etCustomInstructionInput);
        et.setText(prefs.getString(AppConstants.KEY_CUSTOM_INSTRUCTION, ""));

        sheet.findViewById(R.id.btnSaveCustom).setOnClickListener(v -> {
            String val = et.getText().toString().trim();
            prefs.edit().putString(AppConstants.KEY_CUSTOM_INSTRUCTION, val).apply();
            if (currentSession != null) {
                currentSession.getConfig().setSystemInstructions(val);
                sessionManager.saveSession(currentSession);
            }
            Toast.makeText(activity, "Custom Instruction saved", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        sheet.findViewById(R.id.btnCancelCustom).setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(sheet);
        dialog.show();
    }

    public void showGeminiSettingsDialog(Runnable onModelsUpdated) {
        BottomSheetDialog dialog = new BottomSheetDialog(activity, R.style.BottomSheetDialogTheme);
        activeBottomSheet = dialog;
        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_gemini_settings, null);

        EditText etKey = sheet.findViewById(R.id.etGeminiApiKey);
        TextView tvStatus = sheet.findViewById(R.id.tvGeminiFetchStatus);
        TextView tvGrounding = sheet.findViewById(R.id.tvGroundingMode);
        TextView tvVoice = sheet.findViewById(R.id.tvVoiceCharacter);

        etKey.setText(prefs.getString(AppConstants.KEY_GEMINI_API_KEY, ""));

        String[] groundingModes = new String[]{"None (Standard)", "Google Search", "Google Maps"};
        String currentGrounding = prefs.getString("gemini_grounding_mode", "None (Standard)");
        if (tvGrounding != null) {
            tvGrounding.setText(currentGrounding);
            tvGrounding.setOnClickListener(v -> {
                new AlertDialog.Builder(activity)
                        .setTitle("Select Grounding Mode")
                        .setItems(groundingModes, (d, which) -> {
                            String selected = groundingModes[which];
                            prefs.edit().putString("gemini_grounding_mode", selected).apply();
                            tvGrounding.setText(selected);
                        })
                        .show();
            });
        }

        String[] voiceCharacters = new String[]{
                "Aoede (Breezy)", "Charon (Informative)", "Kore (Firm)",
                "Puck (Upbeat)", "Fenrir (Excitable)", "Leda (Youthful)", "Zephyr (Bright)"
        };
        String currentVoice = prefs.getString("gemini_voice_character", "Leda (Youthful)");
        if (tvVoice != null) {
            tvVoice.setText(currentVoice);
            tvVoice.setOnClickListener(v -> {
                new AlertDialog.Builder(activity)
                        .setTitle("Select Voice Character")
                        .setItems(voiceCharacters, (d, which) -> {
                            String selected = voiceCharacters[which];
                            prefs.edit().putString("gemini_voice_character", selected).apply();
                            tvVoice.setText(selected);
                        })
                        .show();
            });
        }

        sheet.findViewById(R.id.btnFetchGeminiModels).setOnClickListener(v -> {
            String key = etKey.getText().toString().trim();
            if (key.isEmpty()) {
                Toast.makeText(activity, "Enter API Key first", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.edit().putString(AppConstants.KEY_GEMINI_API_KEY, key).apply();
            tvStatus.setText("Fetching models from Google...");
            geminiService.fetchAvailableModels(key, new GeminiService.GeminiModelsListener() {
                @Override
                public void onModelsFetched(String json) {
                    activity.runOnUiThread(() -> {
                        prefs.edit().putString(AppConstants.KEY_GEMINI_CACHED_LIVE_MODELS, json).apply();
                        if (onModelsUpdated != null) onModelsUpdated.run();
                        tvStatus.setText("Models fetched successfully!");
                        Toast.makeText(activity, "Live Gemini models updated!", Toast.LENGTH_SHORT).show();
                    });
                }

                @Override
                public void onError(String errorMessage) {
                    activity.runOnUiThread(() -> {
                        tvStatus.setText("Fetch error: " + errorMessage);
                        Toast.makeText(activity, "Fetch error: " + errorMessage, Toast.LENGTH_SHORT).show();
                    });
                }
            });
        });

        sheet.findViewById(R.id.btnGeminiSettingsDone).setOnClickListener(v -> {
            prefs.edit().putString(AppConstants.KEY_GEMINI_API_KEY, etKey.getText().toString().trim()).apply();
            Toast.makeText(activity, "Gemini settings saved", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        sheet.findViewById(R.id.btnGeminiSettingsBack).setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(sheet);
        dialog.show();
    }

    public void showNostrIdentityDialog() {
        BottomSheetDialog dialog = new BottomSheetDialog(activity, R.style.BottomSheetDialogTheme);
        activeBottomSheet = dialog;
        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_nostr_identity, null);

        TextView tvNpub = sheet.findViewById(R.id.tvNostrNpub);
        TextView tvNsec = sheet.findViewById(R.id.tvNostrNsec);

        String npub = prefs.getString(AppConstants.KEY_NOSTR_PUBLIC_KEY, null);
        String nsec = prefs.getString(AppConstants.KEY_NOSTR_PRIVATE_KEY, null);

        if (npub == null || nsec == null) {
            String[] pair = NostrKeyManager.generateKeyPair();
            nsec = pair[0];
            npub = pair[1];
            prefs.edit().putString(AppConstants.KEY_NOSTR_PRIVATE_KEY, nsec)
                    .putString(AppConstants.KEY_NOSTR_PUBLIC_KEY, npub).apply();
        }

        final String finalNpub = npub;
        final String finalNsec = nsec;
        tvNpub.setText(finalNpub);
        tvNsec.setText(finalNsec);

        sheet.findViewById(R.id.btnCopyNpub).setOnClickListener(v -> copyText(finalNpub));
        sheet.findViewById(R.id.btnCopyNsec).setOnClickListener(v -> copyText(finalNsec));

        sheet.findViewById(R.id.btnNostrDone).setOnClickListener(v -> dialog.dismiss());
        sheet.findViewById(R.id.btnNostrBack).setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(sheet);
        dialog.show();
    }

    /**
     * Dedicated Dialog for configuring and managing the Local Embedded HTTP Server.
     * Provides one-tap IP/Port copy actions and activates the local loopback transport.
     */
    public void showHttpBridgeDialog() {
        BottomSheetDialog dialog = new BottomSheetDialog(activity, R.style.BottomSheetDialogTheme);
        activeBottomSheet = dialog;
        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_http_bridge_settings, null);

        SwitchCompat switchEnable = sheet.findViewById(R.id.switchEnableHttpBridge);
        TextView tvStatus = sheet.findViewById(R.id.tvHttpServerStatus);
        TextView tvLoopback = sheet.findViewById(R.id.tvLoopbackUrl);
        Button btnCopyLoopback = sheet.findViewById(R.id.btnCopyLoopback);
        TextView tvLan = sheet.findViewById(R.id.tvLanUrl);
        Button btnCopyLan = sheet.findViewById(R.id.btnCopyLan);
        EditText etPort = sheet.findViewById(R.id.etHttpPort);
        Button btnClose = sheet.findViewById(R.id.btnHttpBridgeClose);
        Button btnApply = sheet.findViewById(R.id.btnHttpBridgeApply);

        boolean isCurrentlyEnabled = prefs.getBoolean(AppConstants.KEY_HTTP_BRIDGE_ENABLED, false);
        int savedPort = prefs.getInt(AppConstants.KEY_HTTP_BRIDGE_PORT, AppConstants.DEFAULT_HTTP_BRIDGE_PORT);
        etPort.setText(String.valueOf(savedPort));
        switchEnable.setChecked(isCurrentlyEnabled);

        Runnable refreshUrls = () -> {
            int portVal;
            try {
                portVal = Integer.parseInt(etPort.getText().toString().trim());
            } catch (Exception e) {
                portVal = AppConstants.DEFAULT_HTTP_BRIDGE_PORT;
            }
            tvLoopback.setText("http://127.0.0.1:" + portVal);
            tvLan.setText("http://" + LocalHttpServerManager.getLocalIpAddress() + ":" + portVal);
        };

        Runnable updateStatusUI = () -> {
            boolean running = LocalHttpServerManager.getInstance().isRunning();
            if (running) {
                tvStatus.setText("Status: Running on port " + LocalHttpServerManager.getInstance().getCurrentPort());
                tvStatus.setTextColor(Color.parseColor("#10B981")); // Emerald Green
            } else {
                tvStatus.setText("Status: Stopped");
                tvStatus.setTextColor(Color.parseColor("#EF4444")); // Red
            }
        };

        refreshUrls.run();
        updateStatusUI.run();

        etPort.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refreshUrls.run(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        btnCopyLoopback.setOnClickListener(v -> copyText(tvLoopback.getText().toString()));
        btnCopyLan.setOnClickListener(v -> copyText(tvLan.getText().toString()));

        btnApply.setOnClickListener(v -> {
            int enteredPort;
            try {
                enteredPort = Integer.parseInt(etPort.getText().toString().trim());
                if (enteredPort < 1024 || enteredPort > 65535) {
                    Toast.makeText(activity, "Port must be between 1024 and 65535", Toast.LENGTH_SHORT).show();
                    return;
                }
            } catch (Exception e) {
                enteredPort = AppConstants.DEFAULT_HTTP_BRIDGE_PORT;
            }

            boolean shouldEnable = switchEnable.isChecked();
            prefs.edit()
                    .putBoolean(AppConstants.KEY_HTTP_BRIDGE_ENABLED, shouldEnable)
                    .putInt(AppConstants.KEY_HTTP_BRIDGE_PORT, enteredPort)
                    .putString(AppConstants.KEY_AUTO_MODE_TRANSPORT, shouldEnable ? AppConstants.TRANSPORT_HTTP : AppConstants.TRANSPORT_NOSTR)
                    .apply();

            if (shouldEnable) {
                boolean started = LocalHttpServerManager.getInstance().startServer(activity, enteredPort);
                if (started) {
                    Toast.makeText(activity, "HTTP Bridge running on port " + enteredPort, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(activity, "Failed to start HTTP server", Toast.LENGTH_SHORT).show();
                }
            } else {
                LocalHttpServerManager.getInstance().stopServer();
                Toast.makeText(activity, "HTTP Bridge stopped (reverted to Nostr)", Toast.LENGTH_SHORT).show();
            }

            updateStatusUI.run();
            dialog.dismiss();
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(sheet);
        dialog.show();
    }

    // --- RICH HELP MANUAL & FORMSPREE CONTACT SUPPORT (EXACT FROM VIDEO 1) ---

    /**
     * Displays the complete 8-point manual from Video 1 with an envelope icon to open support feedback.
     */
    public void showHelpDialog() {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_help_manual);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.90);
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        dialog.findViewById(R.id.btnHelpClose).setOnClickListener(v -> dialog.dismiss());
        
        // Pinned Envelope Icon in bottom left
        dialog.findViewById(R.id.btnHelpContact).setOnClickListener(v -> {
            dialog.dismiss();
            showContactSupportDialog();
        });

        dialog.show();
    }

    /**
     * Modal dialog allowing users to submit support and feedback directly to Formspree API.
     */
    public void showContactSupportDialog() {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_contact_support);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.90);
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        EditText etName = dialog.findViewById(R.id.etContactName);
        EditText etEmail = dialog.findViewById(R.id.etContactEmail);
        EditText etMessage = dialog.findViewById(R.id.etContactMessage);
        Button btnSend = dialog.findViewById(R.id.btnSubmitContactSupport);
        ImageView btnClose = dialog.findViewById(R.id.btnCloseContactSupport);

        btnClose.setOnClickListener(v -> dialog.dismiss());

        btnSend.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            String email = etEmail.getText().toString().trim();
            String message = etMessage.getText().toString().trim();

            if (name.isEmpty() || email.isEmpty() || message.isEmpty()) {
                Toast.makeText(activity, "Please fill in all fields", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(activity, "Please enter a valid email address", Toast.LENGTH_SHORT).show();
                return;
            }

            btnSend.setEnabled(false);
            btnSend.setText("SENDING...");

            sendFeedbackToFormspree(name, email, message, new FormspreeCallback() {
                @Override
                public void onSuccess() {
                    activity.runOnUiThread(() -> {
                        Toast.makeText(activity, "Thank you! Message sent successfully.", Toast.LENGTH_LONG).show();
                        dialog.dismiss();
                    });
                }

                @Override
                public void onError(String errorMsg) {
                    activity.runOnUiThread(() -> {
                        btnSend.setEnabled(true);
                        btnSend.setText("SEND MESSAGE");
                        Toast.makeText(activity, "Failed to send: " + errorMsg, Toast.LENGTH_LONG).show();
                    });
                }
            });
        });

        dialog.show();
    }

    private interface FormspreeCallback {
        void onSuccess();
        void onError(String errorMsg);
    }

    private void sendFeedbackToFormspree(String name, String email, String message, FormspreeCallback callback) {
        try {
            JSONObject json = new JSONObject();
            json.put("name", name);
            json.put("email", email);
            json.put("message", message);
            json.put("_subject", "Puter Unofficial Feedback from " + name);

            MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
            RequestBody body = RequestBody.create(json.toString(), mediaType);

            Request request = new Request.Builder()
                    .url(AppConstants.FORMSPREE_ENDPOINT_URL)
                    .addHeader("Accept", "application/json")
                    .post(body)
                    .build();

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build();

            LiveLogManager.getInstance().process(AppConstants.TAG_SUPPORT, "Submitting Feedback", "Target: Formspree API");

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    LiveLogManager.getInstance().error(AppConstants.TAG_SUPPORT, "Feedback Submission Failed", e.getMessage());
                    ActionReportLogger.logError("SUPPORT_FAIL", e.getMessage());
                    callback.onError(e.getMessage());
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (response.isSuccessful()) {
                        LiveLogManager.getInstance().ok(AppConstants.TAG_SUPPORT, "Feedback Delivered", "HTTP " + response.code());
                        ActionReportLogger.logAction("SUPPORT_SUCCESS", "Feedback sent by " + email);
                        callback.onSuccess();
                    } else {
                        String err = "HTTP " + response.code();
                        LiveLogManager.getInstance().error(AppConstants.TAG_SUPPORT, "Feedback Rejected", err);
                        ActionReportLogger.logError("SUPPORT_REJECT", err);
                        callback.onError(err);
                    }
                }
            });
        } catch (Exception e) {
            LiveLogManager.getInstance().error(AppConstants.TAG_SUPPORT, "Feedback Error", e.getMessage());
            callback.onError(e.getMessage());
        }
    }

    // --- BROWSER & CRYPTO BOT OVERLAYS ---

    public void showBrowserDialog() {
        showBrowserDialog(null);
    }

    @SuppressLint("SetJavaScriptEnabled")
    public void showBrowserDialog(WebAppInterface webAppInterface) {
        BottomSheetDialog dialog = new BottomSheetDialog(activity, R.style.BottomSheetDialogTheme);
        activeBottomSheet = dialog;
        activePortalDialog = dialog;

        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_code_preview_fullscreen, null);
        WebView wv = sheet.findViewById(R.id.wvCodePreview);

        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        wv.setWebChromeClient(new WebChromeClient());

        if (webAppInterface != null) {
            wv.addJavascriptInterface(webAppInterface, "AndroidInterface");
        }

        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(activity))
                .build();

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    return assetLoader.shouldInterceptRequest(request.getUrl());
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    String url = request.getUrl().toString();
                    if (url.contains("index.html")) {
                        activity.runOnUiThread(dialog::dismiss);
                        return true;
                    }
                }
                return false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && url.contains("index.html")) {
                    activity.runOnUiThread(dialog::dismiss);
                    return true;
                }
                return false;
            }
        });

        dialog.setOnShowListener(d -> {
            BottomSheetDialog bsd = (BottomSheetDialog) d;
            FrameLayout bottomSheet = bsd.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(bottomSheet);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                behavior.setSkipCollapsed(true);
                bottomSheet.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
                bottomSheet.requestLayout();
            }
        });

        wv.loadUrl(AppConstants.LOCAL_BROWSER_URL);
        sheet.findViewById(R.id.btnCloseCodePreview).setOnClickListener(v -> dialog.dismiss());
        sheet.findViewById(R.id.btnReloadCodePreview).setOnClickListener(v -> wv.reload());
        dialog.setContentView(sheet);
        dialog.show();
    }

    public void showCryptoBotDialog() {
        showCryptoBotDialog(null);
    }

    @SuppressLint("SetJavaScriptEnabled")
    public void showCryptoBotDialog(WebAppInterface webAppInterface) {
        BottomSheetDialog dialog = new BottomSheetDialog(activity, R.style.BottomSheetDialogTheme);
        activeBottomSheet = dialog;
        activePortalDialog = dialog;

        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_code_preview_fullscreen, null);
        WebView wv = sheet.findViewById(R.id.wvCodePreview);

        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        wv.setWebChromeClient(new WebChromeClient());

        if (webAppInterface != null) {
            wv.addJavascriptInterface(webAppInterface, "AndroidInterface");
        }

        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(activity))
                .build();

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    return assetLoader.shouldInterceptRequest(request.getUrl());
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    String url = request.getUrl().toString();
                    if (url.contains("index.html")) {
                        activity.runOnUiThread(dialog::dismiss);
                        return true;
                    }
                }
                return false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && url.contains("index.html")) {
                    activity.runOnUiThread(dialog::dismiss);
                    return true;
                }
                return false;
            }
        });

        dialog.setOnShowListener(d -> {
            BottomSheetDialog bsd = (BottomSheetDialog) d;
            FrameLayout bottomSheet = bsd.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(bottomSheet);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                behavior.setSkipCollapsed(true);
                bottomSheet.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
                bottomSheet.requestLayout();
            }
        });

        wv.loadUrl(AppConstants.LOCAL_BOT_URL);
        sheet.findViewById(R.id.btnCloseCodePreview).setOnClickListener(v -> dialog.dismiss());
        sheet.findViewById(R.id.btnReloadCodePreview).setOnClickListener(v -> wv.reload());
        dialog.setContentView(sheet);
        dialog.show();
    }

    public void dismissPortalDialog() {
        activity.runOnUiThread(() -> {
            if (activePortalDialog != null && activePortalDialog.isShowing()) {
                try {
                    activePortalDialog.dismiss();
                } catch (Exception ignored) {}
                activePortalDialog = null;
            }
            if (activeBottomSheet != null && activeBottomSheet.isShowing()) {
                try {
                    activeBottomSheet.dismiss();
                } catch (Exception ignored) {}
                activeBottomSheet = null;
            }
        });
    }

    // --- FULLSCREEN PREVIEWERS ---

    @SuppressLint("SetJavaScriptEnabled")
    public void showCodePreviewFullscreen(String code) {
        BottomSheetDialog dialog = new BottomSheetDialog(activity, R.style.BottomSheetDialogTheme);
        activeBottomSheet = dialog;
        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_code_preview_fullscreen, null);
        WebView wv = sheet.findViewById(R.id.wvCodePreview);
        wv.getSettings().setJavaScriptEnabled(true);
        wv.loadDataWithBaseURL(null, code, "text/html", "UTF-8", null);

        sheet.findViewById(R.id.btnCloseCodePreview).setOnClickListener(v -> dialog.dismiss());
        sheet.findViewById(R.id.btnReloadCodePreview).setOnClickListener(v ->
                wv.loadDataWithBaseURL(null, code, "text/html", "UTF-8", null));
        dialog.setContentView(sheet);
        dialog.show();
    }

    public void showImagePreviewViewer(String base64OrUrl, WebAppInterface webAppInterface) {
        BottomSheetDialog dialog = new BottomSheetDialog(activity, R.style.BottomSheetDialogTheme);
        activeBottomSheet = dialog;
        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_image_preview_viewer, null);
        ImageView iv = sheet.findViewById(R.id.ivFullPreviewImage);

        if (base64OrUrl != null && base64OrUrl.startsWith("data:image")) {
            String clean = base64OrUrl.replaceFirst("^data:image/[a-zA-Z]+;base64,", "");
            byte[] decoded = Base64.decode(clean, Base64.DEFAULT);
            Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
            iv.setImageBitmap(bmp);
        }

        sheet.findViewById(R.id.btnCloseImagePreview).setOnClickListener(v -> dialog.dismiss());
        sheet.findViewById(R.id.btnSaveImageToGallery).setOnClickListener(v -> {
            if (webAppInterface != null) {
                webAppInterface.saveImageToGallery(base64OrUrl, "Puter_" + System.currentTimeMillis() + ".jpg");
            }
        });
        sheet.findViewById(R.id.btnShareImage).setOnClickListener(v ->
                Toast.makeText(activity, "Sharing image...", Toast.LENGTH_SHORT).show());

        dialog.setContentView(sheet);
        dialog.show();
    }

    /**
     * Full-screen swipeable multi-image gallery viewer.
     * Features horizontal ViewPager2 navigation, real-time index counter,
     * download to gallery action, and clickable "Learn more" attribution bar.
     */
    public void showFullscreenImageGallery(int initialPosition,
                                           List<ScrapedImageItem> images,
                                           WebAppInterface webAppInterface) {
        if (images == null || images.isEmpty()) return;

        Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(R.layout.dialog_fullscreen_image_gallery);

        ViewPager2 vp = dialog.findViewById(R.id.vpGalleryPager);
        View topBar = dialog.findViewById(R.id.topGalleryBar);
        View bottomBar = dialog.findViewById(R.id.bottomAttributionBar);
        ImageButton btnClose = dialog.findViewById(R.id.btnGalleryClose);
        TextView tvCounter = dialog.findViewById(R.id.tvGalleryCounter);
        ImageButton btnDownload = dialog.findViewById(R.id.btnGalleryDownload);
        TextView tvTitle = dialog.findViewById(R.id.tvGalleryTitle);
        TextView tvDomain = dialog.findViewById(R.id.tvGalleryDomain);
        View btnLearnMore = dialog.findViewById(R.id.btnGalleryLearnMore);

        FullscreenGalleryAdapter adapter = new FullscreenGalleryAdapter(activity, images, (pos, item) -> {
            // Tap image to toggle action header and attribution footer for immersion
            int newVis = (topBar.getVisibility() == View.VISIBLE) ? View.GONE : View.VISIBLE;
            topBar.setVisibility(newVis);
            bottomBar.setVisibility(newVis);
        });
        vp.setAdapter(adapter);

        int total = images.size();
        int startPos = Math.max(0, Math.min(initialPosition, total - 1));

        Runnable updateMetadataForPosition = () -> {
            int current = vp.getCurrentItem();
            tvCounter.setText((current + 1) + " / " + total);
            ScrapedImageItem item = adapter.getItem(current);
            if (item != null) {
                String title = item.getTitle();
                tvTitle.setText(title != null && !title.isEmpty() ? title : "Web Image");
                String domain = item.getSourceDomain();
                tvDomain.setText(domain != null && !domain.isEmpty() ? domain : "Web Source");

                btnLearnMore.setOnClickListener(v -> {
                    String srcUrl = item.getSourceUrl();
                    if (srcUrl != null && !srcUrl.isEmpty()) {
                        try {
                            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(srcUrl));
                            activity.startActivity(browserIntent);
                        } catch (Exception e) {
                            Toast.makeText(activity, "Cannot open source link", Toast.LENGTH_SHORT).show();
                        }
                    }
                });

                btnDownload.setOnClickListener(v -> {
                    String imgUrl = item.getImageUrl();
                    if (imgUrl != null && !imgUrl.isEmpty()) {
                        Toast.makeText(activity, R.string.gallery_saving, Toast.LENGTH_SHORT).show();
                        if (webAppInterface != null) {
                            webAppInterface.saveImageToGallery(imgUrl, "WebImage_" + System.currentTimeMillis() + ".jpg");
                        } else {
                            Toast.makeText(activity, R.string.gallery_save_success, Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        };

        vp.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                updateMetadataForPosition.run();
            }
        });

        vp.setCurrentItem(startPos, false);
        updateMetadataForPosition.run();

        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    public void showAttachmentPickerDialog(ActivityResultLauncher<String> galleryLauncher,
                                           ActivityResultLauncher<String> documentLauncher) {
        BottomSheetDialog dialog = new BottomSheetDialog(activity, R.style.BottomSheetDialogTheme);
        activeBottomSheet = dialog;
        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_attachment_picker, null);

        sheet.findViewById(R.id.btnOptionGallery).setOnClickListener(v -> {
            dialog.dismiss();
            if (galleryLauncher != null) galleryLauncher.launch("image/*");
        });

        sheet.findViewById(R.id.btnOptionDocument).setOnClickListener(v -> {
            dialog.dismiss();
            if (documentLauncher != null) documentLauncher.launch("*/*");
        });

        sheet.findViewById(R.id.btnOptionCamera).setOnClickListener(v -> {
            dialog.dismiss();
            if (galleryLauncher != null) galleryLauncher.launch("image/*");
        });

        dialog.setContentView(sheet);
        dialog.show();
    }

    // --- PUTER AUTHENTICATION POPUP DIALOG ---

    @SuppressLint("SetJavaScriptEnabled")
    public void openPuterAuthDialog() {
        if (activeAuthDialog != null && activeAuthDialog.isShowing()) {
            activeAuthDialog.dismiss();
        }
        isPuterAuthProcessing = false;

        LinearLayout dialogLayout = new LinearLayout(activity);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        dialogLayout.setBackgroundColor(Color.WHITE);

        final WebView popupWebView = new WebView(activity);

        Button closeButton = new Button(activity);
        closeButton.setText("Close Window (Tap when Signed In)");
        closeButton.setBackgroundColor(Color.parseColor("#1a73e8"));
        closeButton.setTextColor(Color.WHITE);
        closeButton.setOnClickListener(v -> {
            LiveLogManager.getInstance().warn("PUTER_AUTH", "Manual Window Close",
                    "User tapped Close Button. Inspecting storage for token...");
            CookieManager.getInstance().flush();
            extractTokensFromWebView(popupWebView, true);
        });

        dialogLayout.addView(closeButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialogLayout.addView(popupWebView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings ws = popupWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setJavaScriptCanOpenWindowsAutomatically(true);
        ws.setSupportMultipleWindows(true);

        String userAgent = ws.getUserAgentString().replace("; wv", "");
        ws.setUserAgentString(userAgent);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(popupWebView, true);

        popupWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void onAuthMessageReceived(String rawData) {
                LiveLogManager.getInstance().ok("PUTER_AUTH", "PostMessage Captured", "Raw: " + rawData);
                activity.runOnUiThread(() -> extractTokensFromWebView(popupWebView, true));
            }
        }, "AndroidPuterAuthBridge");

        popupWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onCloseWindow(WebView window) {
                LiveLogManager.getInstance().ok("PUTER_AUTH", "SDK Issued window.close()", "Finalizing auth extraction.");
                activity.runOnUiThread(() -> extractTokensFromWebView(popupWebView, true));
            }
        });

        final String openerPolyfillScript =
                "javascript:(function() {" +
                "  if (!window.opener) {" +
                "    window.opener = {" +
                "      postMessage: function(data, origin) {" +
                "        if (window.AndroidPuterAuthBridge) {" +
                "          window.AndroidPuterAuthBridge.onAuthMessageReceived(typeof data === 'object' ? JSON.stringify(data) : String(data));" +
                "        }" +
                "      }" +
                "    };" +
                "  }" +
                "})()";

        popupWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                LiveLogManager.getInstance().process("PUTER_AUTH", "OAuth Page Loading", "URL: " + url);
                view.evaluateJavascript(openerPolyfillScript, null);
                checkAuthRedirect(url, view);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    return checkAuthRedirect(request.getUrl().toString(), view);
                }
                return false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return checkAuthRedirect(url, view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                LiveLogManager.getInstance().ok("PUTER_AUTH", "OAuth Page Finished", "URL: " + url);
                view.evaluateJavascript(openerPolyfillScript, null);
                CookieManager.getInstance().flush();

                checkAuthRedirect(url, view);
            }

            private boolean checkAuthRedirect(String url, WebView view) {
                if (url == null || isPuterAuthProcessing) return false;
                AuthManager auth = AuthManager.getInstance(activity);
                if (auth.isAuthCallback(url)) {
                    isPuterAuthProcessing = true;
                    LiveLogManager.getInstance().ok("PUTER_AUTH", "Callback Detected", "Callback URL matched: " + url);
                    extractTokensFromWebView(view, true);
                    return true;
                }
                return false;
            }
        });

        activeAuthDialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
        activeAuthDialog.setContentView(dialogLayout);
        activeAuthDialog.show();

        String sdkAuthUrl = "https://puter.com/?embedded_in_popup=true&request_auth=true";
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Origin", "https://appassets.androidplatform.net");
        headers.put("Referer", "https://appassets.androidplatform.net/");
        popupWebView.loadUrl(sdkAuthUrl, headers);
    }

    private void extractTokensFromWebView(WebView webView, boolean finalizeOnFound) {
        if (webView == null) return;

        try {
            CookieManager cm = CookieManager.getInstance();
            String cookies = cm.getCookie("https://puter.com");
            if (cookies == null || cookies.isEmpty()) {
                cookies = cm.getCookie("https://api.puter.com");
            }
            if (cookies != null && !cookies.isEmpty()) {
                String[] cookieList = cookies.split(";");
                for (String c : cookieList) {
                    String[] kv = c.trim().split("=");
                    if (kv.length >= 2) {
                        String key = kv[0].trim();
                        String val = kv[1].trim();

                        if (key.equalsIgnoreCase("token") ||
                            key.equalsIgnoreCase("puter_auth_token") ||
                            key.equalsIgnoreCase("puter_token") ||
                            key.equalsIgnoreCase("auth_token") ||
                            key.equalsIgnoreCase("puter-auth")) {

                            if (!val.isEmpty()) {
                                LiveLogManager.getInstance().ok("PUTER_AUTH", "Token Discovered in Cookies",
                                        "Key [" + key + "] len: " + val.length());
                                AuthManager.getInstance(activity).saveAuthToken(val);
                                if (finalizeOnFound) {
                                    completePuterAuth();
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LiveLogManager.getInstance().error("PUTER_AUTH", "Cookie Scan Error", e.getMessage());
        }

        String jsQuery = "(function(){" +
                "  var t = '';" +
                "  try { t = localStorage.getItem('puter.auth.token') || localStorage.getItem('token') || ''; } catch(e){}" +
                "  if (!t && typeof puter !== 'undefined') {" +
                "    t = puter.authToken || (puter.auth && puter.auth.token) || '';" +
                "  }" +
                "  return t;" +
                "})()";

        webView.evaluateJavascript(jsQuery, val -> {
            if (val != null && !val.equals("null") && !val.trim().isEmpty()) {
                String clean = val.replace("\"", "").replace("'", "").trim();
                if (!clean.isEmpty()) {
                    LiveLogManager.getInstance().ok("PUTER_AUTH", "Token Extracted from LocalStorage", "Len: " + clean.length());
                    AuthManager.getInstance(activity).saveAuthToken(clean);
                    if (finalizeOnFound) {
                        completePuterAuth();
                    }
                    return;
                }
            }
            if (finalizeOnFound) {
                completePuterAuth();
            }
        });
    }

    private void completePuterAuth() {
        if (isPuterAuthProcessing && activeAuthDialog == null) return;
        isPuterAuthProcessing = true;

        CookieManager.getInstance().flush();
        AuthManager auth = AuthManager.getInstance(activity);
        auth.handleAuthCallback("https://puter.com/app");
        auth.setLoggedIn(true);

        String currentToken = auth.getAuthToken();
        if (currentToken != null && !currentToken.trim().isEmpty()) {
            LiveLogManager.getInstance().ok("PUTER_AUTH", "Authentication Verified",
                    "Token active in AuthManager (length: " + currentToken.length() + ")");
        }

        activity.runOnUiThread(() -> {
            if (activeAuthDialog != null && activeAuthDialog.isShowing()) {
                try {
                    activeAuthDialog.dismiss();
                } catch (Exception ignored) {}
                activeAuthDialog = null;
            }
            Toast.makeText(activity, "Puter Sign-In Successful!", Toast.LENGTH_SHORT).show();
            if (onAuthSuccessCallback != null) {
                onAuthSuccessCallback.run();
            }
        });
    }

    private void copyText(String text) {
        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Puter Copied", text));
            Toast.makeText(activity, "Copied to clipboard", Toast.LENGTH_SHORT).show();
        }
    }

    public void dismissAll() {
        if (activeAuthDialog != null && activeAuthDialog.isShowing()) {
            try { activeAuthDialog.dismiss(); } catch (Exception ignored) {}
            activeAuthDialog = null;
        }
        if (activeBottomSheet != null && activeBottomSheet.isShowing()) {
            try { activeBottomSheet.dismiss(); } catch (Exception ignored) {}
            activeBottomSheet = null;
        }
    }

    // --- INNER SESSION HISTORY ADAPTER ---

    public static class SessionHistoryAdapter extends RecyclerView.Adapter<SessionHistoryAdapter.ViewHolder> {
        private final List<ChatSession> sessions;
        private final OnSessionClickListener clickListener;
        private final OnSessionDeleteListener deleteListener;

        public interface OnSessionClickListener { void onClick(ChatSession session); }
        public interface OnSessionDeleteListener { void onDelete(ChatSession session); }

        public SessionHistoryAdapter(List<ChatSession> sessions, OnSessionClickListener c, OnSessionDeleteListener d) {
            this.sessions = sessions;
            this.clickListener = c;
            this.deleteListener = d;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_session_drawer_item, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ChatSession s = sessions.get(position);
            holder.tvTitle.setText(s.getTitle());
            holder.itemView.setOnClickListener(v -> clickListener.onClick(s));
            holder.btnDelete.setOnClickListener(v -> deleteListener.onDelete(s));
        }

        @Override
        public int getItemCount() {
            return sessions != null ? sessions.size() : 0;
        }

        public static class ViewHolder extends RecyclerView.ViewHolder {
            public TextView tvTitle;
            public ImageView btnDelete;
            public ViewHolder(View v) {
                super(v);
                tvTitle = v.findViewById(R.id.tvSessionTitle);
                btnDelete = v.findViewById(R.id.btnDeleteSession);
            }
        }
    }
}