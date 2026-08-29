// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.blacktom222.hyperos4notificationimportance;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        int gap = Math.round(14 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(24);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView status = new TextView(this);
        status.setText(R.string.installed_status);
        status.setTextSize(16);
        status.setTextColor(Color.rgb(0, 120, 80));
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = gap;
        root.addView(status, statusParams);

        TextView instructions = new TextView(this);
        instructions.setText(R.string.instructions);
        instructions.setTextSize(16);
        instructions.setTextColor(Color.DKGRAY);
        instructions.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        textParams.topMargin = gap * 2;
        root.addView(instructions, textParams);

        setContentView(root);
    }
}
