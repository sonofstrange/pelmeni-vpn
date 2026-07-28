package com.example.sshtunnel.devcontrol;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Private companion. Signature permission prevents other apps from controlling branding. */
public final class DeveloperControlActivity extends Activity {
    private static final String TARGET_PACKAGE = "com.example.sshtunnel";
    private static final String ACTION_PREFIX =
            "com.example.sshtunnel.branding.";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(48, 72, 48, 48);

        TextView title = new TextView(this);
        title.setText(R.string.title);
        title.setTextSize(24);
        page.addView(title);

        addCommand(page, R.string.enable, "ENABLE");
        addCommand(page, R.string.disable, "DISABLE");
        addCommand(page, R.string.toggle, "TOGGLE");
        setContentView(page);
    }

    private void addCommand(LinearLayout page, int label, String command) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> {
            sendBroadcast(new Intent(ACTION_PREFIX + command)
                    .setPackage(TARGET_PACKAGE));
            Toast.makeText(this, R.string.sent, Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = 20;
        page.addView(button, params);
    }
}
