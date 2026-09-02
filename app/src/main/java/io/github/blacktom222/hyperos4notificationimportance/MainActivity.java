// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.blacktom222.hyperos4notificationimportance;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;
import fan.appcompat.app.AppCompatActivity;

public final class MainActivity extends AppCompatActivity
        implements ModuleApplication.ServiceStateListener {
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final String STATE_DETAILS_EXPANDED = "details_expanded";
    private static final String ROOT_RESTART_COMMAND =
            "[ \"$(id -u)\" = \"0\" ] || exit 126; "
                    + "am force-stop com.android.settings; "
                    + "systemui_pid=$(pidof com.android.systemui); "
                    + "if [ -n \"$systemui_pid\" ]; then kill -TERM $systemui_pid; fi; "
                    + "exit 0";

    private TextView activationStatus;
    private TextView frameworkDetails;
    private TextView settingsScopeStatus;
    private TextView systemUiScopeStatus;
    private View activationCard;
    private TextView activationCardSummary;
    private TextView androidVersion;
    private TextView appVersion;
    private TextView rootStatus;
    private Button restartButton;
    private Button detailsToggle;
    private View detailsPanel;
    private boolean restartInProgress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        activationStatus = findViewById(R.id.activation_status);
        frameworkDetails = findViewById(R.id.framework_details);
        settingsScopeStatus = findViewById(R.id.settings_scope_status);
        systemUiScopeStatus = findViewById(R.id.systemui_scope_status);
        activationCard = findViewById(R.id.activation_card);
        activationCardSummary = findViewById(R.id.activation_card_summary);
        androidVersion = findViewById(R.id.android_version);
        androidVersion.setText(getString(
                R.string.android_version_format, Build.VERSION.RELEASE, Build.VERSION.SDK_INT));
        appVersion = findViewById(R.id.app_version);
        appVersion.setText(getString(
                R.string.app_version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
        rootStatus = findViewById(R.id.root_status);
        restartButton = findViewById(R.id.restart_button);
        restartButton.setOnClickListener(view -> confirmRestart());
        detailsToggle = findViewById(R.id.details_toggle);
        detailsPanel = findViewById(R.id.details_panel);
        setDetailsExpanded(savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_DETAILS_EXPANDED));
        detailsToggle.setOnClickListener(view ->
                setDetailsExpanded(detailsPanel.getVisibility() != View.VISIBLE));
    }

    private void setDetailsExpanded(boolean expanded) {
        detailsPanel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        detailsToggle.setText(expanded ? R.string.details_collapse : R.string.details_expand);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_DETAILS_EXPANDED, detailsPanel.getVisibility() == View.VISIBLE);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onStart() {
        super.onStart();
        ModuleApplication.addServiceStateListener(this, true);
    }

    @Override
    public void onStop() {
        ModuleApplication.removeServiceStateListener(this);
        super.onStop();
    }

    @Override
    public void onServiceStateChanged(@Nullable XposedService service) {
        runOnUiThread(() -> updateActivationState(service));
    }

    private void updateActivationState(@Nullable XposedService service) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (service == null) {
            activationStatus.setText(R.string.activation_inactive);
            activationCardSummary.setText(R.string.activation_inactive_details);
            setActivationAppearance(R.color.status_error, R.drawable.bg_dashboard_error);
            frameworkDetails.setText(R.string.framework_disconnected);
            updateScopeStatus(settingsScopeStatus, null);
            updateScopeStatus(systemUiScopeStatus, null);
            return;
        }

        try {
            List<String> scope = service.getScope();
            boolean settingsScoped = scope.contains(SETTINGS_PACKAGE);
            boolean systemUiScoped = scope.contains(SYSTEM_UI_PACKAGE);
            List<String> runningProcesses = new ArrayList<>();
            for (HookedTarget target : service.getRunningTargets()) {
                String processName = target.getProcessName();
                if (processName.startsWith(SETTINGS_PACKAGE)
                        || processName.startsWith(SYSTEM_UI_PACKAGE)) {
                    runningProcesses.add(processName);
                }
            }

            activationStatus.setText(
                    settingsScoped && systemUiScoped
                            ? R.string.activation_active
                            : R.string.activation_scope_incomplete);
            setActivationAppearance(
                    settingsScoped && systemUiScoped ? R.color.status_success : R.color.status_warning,
                    settingsScoped && systemUiScoped
                            ? R.drawable.bg_dashboard_active
                            : R.drawable.bg_dashboard_warning);
            activationCardSummary.setText(settingsScoped && systemUiScoped
                    ? R.string.activation_card_connected
                    : R.string.activation_card_incomplete);
            updateScopeStatus(settingsScopeStatus, settingsScoped);
            updateScopeStatus(systemUiScopeStatus, systemUiScoped);
            frameworkDetails.setText(getString(
                    R.string.activation_details,
                    service.getFrameworkName(),
                    service.getFrameworkVersion(),
                    service.getApiVersion(),
                    runningProcesses.isEmpty()
                            ? getString(R.string.no_running_scope_process)
                            : String.join("、", runningProcesses)));
        } catch (Throwable throwable) {
            activationStatus.setText(R.string.activation_service_error);
            activationCardSummary.setText(R.string.activation_service_error);
            setActivationAppearance(R.color.status_error, R.drawable.bg_dashboard_error);
            frameworkDetails.setText(throwable.getClass().getSimpleName());
            updateScopeStatus(settingsScopeStatus, null);
            updateScopeStatus(systemUiScopeStatus, null);
        }
    }

    private void setActivationAppearance(int summaryColor, int cardBackground) {
        activationStatus.setTextColor(getColor(R.color.text_primary));
        activationCardSummary.setTextColor(getColor(summaryColor));
        activationCard.setBackgroundResource(cardBackground);
    }

    private void updateScopeStatus(TextView view, @Nullable Boolean enabled) {
        if (enabled == null) {
            view.setText(R.string.scope_unknown);
            view.setTextColor(getColor(R.color.text_secondary));
            return;
        }
        view.setText(enabled ? R.string.scope_enabled : R.string.scope_disabled);
        view.setTextColor(getColor(enabled ? R.color.status_success : R.color.status_error));
    }

    private void confirmRestart() {
        if (restartInProgress) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.restart_confirm_title)
                .setMessage(R.string.restart_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.restart_confirm_action,
                        (dialog, which) -> restartScopeWithRoot())
                .show();
    }

    private void restartScopeWithRoot() {
        if (restartInProgress) {
            return;
        }
        restartInProgress = true;
        restartButton.setEnabled(false);
        rootStatus.setText(R.string.root_requesting);
        rootStatus.setTextColor(getColor(R.color.status_warning));

        Thread worker = new Thread(() -> {
            boolean success = false;
            try {
                Process process = new ProcessBuilder("su", "-c", ROOT_RESTART_COMMAND)
                        .redirectErrorStream(true)
                        .start();
                boolean completed = process.waitFor(90, TimeUnit.SECONDS);
                success = completed && process.exitValue() == 0;
                if (!completed) {
                    process.destroy();
                }
            } catch (Throwable ignored) {
                success = false;
            }

            boolean restartSucceeded = success;
            runOnUiThread(() -> {
                restartInProgress = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                restartButton.setEnabled(true);
                restartButton.performHapticFeedback(restartSucceeded
                        ? HapticFeedbackConstants.CONFIRM
                        : HapticFeedbackConstants.REJECT);
                rootStatus.setText(restartSucceeded
                        ? R.string.restart_scope_success
                        : R.string.restart_scope_failed);
                rootStatus.setTextColor(getColor(restartSucceeded
                        ? R.color.status_success
                        : R.color.status_error));
            });
        }, "scope-root-restart");
        worker.start();
    }
}
