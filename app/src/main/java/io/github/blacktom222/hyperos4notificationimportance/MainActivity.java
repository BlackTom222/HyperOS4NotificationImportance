// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.blacktom222.hyperos4notificationimportance;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;

public final class MainActivity extends Activity
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
    private TextView rootStatus;
    private Button restartButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int padding = dp(24);
        int gap = dp(14);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(24);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        activationStatus = new TextView(this);
        activationStatus.setText(R.string.activation_checking);
        activationStatus.setTextSize(18);
        activationStatus.setTextColor(Color.rgb(120, 90, 0));
        activationStatus.setGravity(Gravity.CENTER);
        addWithTopMargin(root, activationStatus, gap);

        frameworkDetails = new TextView(this);
        frameworkDetails.setText(R.string.activation_waiting_details);
        frameworkDetails.setTextSize(14);
        frameworkDetails.setTextColor(Color.DKGRAY);
        frameworkDetails.setGravity(Gravity.CENTER);
        frameworkDetails.setLineSpacing(0, 1.2f);
        addWithTopMargin(root, frameworkDetails, gap);

        restartButton = new Button(this);
        restartButton.setText(R.string.restart_scope_with_root);
        restartButton.setAllCaps(false);
        restartButton.setOnClickListener(view -> restartScopeWithRoot());
        addWithTopMargin(root, restartButton, gap * 2);

        rootStatus = new TextView(this);
        rootStatus.setText(R.string.root_not_requested);
        rootStatus.setTextSize(14);
        rootStatus.setTextColor(Color.DKGRAY);
        rootStatus.setGravity(Gravity.CENTER);
        addWithTopMargin(root, rootStatus, gap);

        TextView instructions = new TextView(this);
        instructions.setText(R.string.instructions);
        instructions.setTextSize(16);
        instructions.setTextColor(Color.DKGRAY);
        instructions.setLineSpacing(0, 1.25f);
        addWithTopMargin(root, instructions, gap * 2);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scrollView);
    }

    @Override
    protected void onStart() {
        super.onStart();
        ModuleApplication.addServiceStateListener(this, true);
    }

    @Override
    protected void onStop() {
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
            activationStatus.setTextColor(Color.rgb(190, 45, 45));
            frameworkDetails.setText(R.string.activation_inactive_details);
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
            activationStatus.setTextColor(settingsScoped && systemUiScoped
                    ? Color.rgb(0, 125, 80)
                    : Color.rgb(190, 105, 0));
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
            activationStatus.setTextColor(Color.rgb(190, 45, 45));
            frameworkDetails.setText(throwable.getClass().getSimpleName());
        }
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
        rootStatus.setTextColor(Color.rgb(120, 90, 0));

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
                rootStatus.setText(restartSucceeded
                        ? R.string.restart_scope_success
                        : R.string.restart_scope_failed);
                rootStatus.setTextColor(restartSucceeded
                        ? Color.rgb(0, 125, 80)
                        : Color.rgb(190, 45, 45));
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void addWithTopMargin(LinearLayout parent, TextView view, int margin) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = margin;
        parent.addView(view, params);
    }
}
