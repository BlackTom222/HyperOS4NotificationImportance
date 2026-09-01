// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.blacktom222.hyperos4notificationimportance;

import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

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
    private View activationIndicator;
    private TextView rootStatus;
    private Button restartButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        activationStatus = findViewById(R.id.activation_status);
        frameworkDetails = findViewById(R.id.framework_details);
        settingsScopeStatus = findViewById(R.id.settings_scope_status);
        systemUiScopeStatus = findViewById(R.id.systemui_scope_status);
        activationIndicator = findViewById(R.id.activation_indicator);
        rootStatus = findViewById(R.id.root_status);
        restartButton = findViewById(R.id.restart_button);
        restartButton.setOnClickListener(view -> restartScopeWithRoot());
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
        if (service == null) {
            activationStatus.setText(R.string.activation_inactive);
            setActivationAppearance(R.color.status_error, R.drawable.bg_status_error);
            frameworkDetails.setText(R.string.activation_inactive_details);
            updateScopeStatus(settingsScopeStatus, false);
            updateScopeStatus(systemUiScopeStatus, false);
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

            activationStatus.setText(getString(
                    settingsScoped && systemUiScoped
                            ? R.string.activation_active
                            : R.string.activation_scope_incomplete,
                    service.getApiVersion()));
            setActivationAppearance(
                    settingsScoped && systemUiScoped ? R.color.status_success : R.color.status_warning,
                    settingsScoped && systemUiScoped
                            ? R.drawable.bg_status_success
                            : R.drawable.bg_status_warning);
            updateScopeStatus(settingsScopeStatus, settingsScoped);
            updateScopeStatus(systemUiScopeStatus, systemUiScoped);
            frameworkDetails.setText(getString(
                    R.string.activation_details,
                    service.getFrameworkName(),
                    service.getFrameworkVersion(),
                    formatScope(settingsScoped, systemUiScoped),
                    runningProcesses.isEmpty()
                            ? getString(R.string.no_running_scope_process)
                            : String.join("、", runningProcesses)));
        } catch (Throwable throwable) {
            activationStatus.setText(R.string.activation_service_error);
            setActivationAppearance(R.color.status_error, R.drawable.bg_status_error);
            frameworkDetails.setText(throwable.getClass().getSimpleName());
            updateScopeStatus(settingsScopeStatus, false);
            updateScopeStatus(systemUiScopeStatus, false);
        }
    }

    private void setActivationAppearance(int textColor, int indicatorBackground) {
        activationStatus.setTextColor(getColor(textColor));
        activationIndicator.setBackgroundResource(indicatorBackground);
    }

    private void updateScopeStatus(TextView view, boolean enabled) {
        view.setText(enabled ? R.string.scope_enabled : R.string.scope_disabled);
        view.setTextColor(getColor(enabled ? R.color.status_success : R.color.status_error));
    }

    private String formatScope(boolean settingsScoped, boolean systemUiScoped) {
        return getString(
                R.string.scope_state,
                settingsScoped ? getString(R.string.scope_enabled) : getString(R.string.scope_disabled),
                systemUiScoped ? getString(R.string.scope_enabled) : getString(R.string.scope_disabled));
    }

    private void restartScopeWithRoot() {
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
                Toast.makeText(
                        this,
                        restartSucceeded
                                ? R.string.restart_scope_success
                                : R.string.restart_scope_failed,
                        Toast.LENGTH_LONG).show();
            });
        }, "scope-root-restart");
        worker.start();
    }
}
