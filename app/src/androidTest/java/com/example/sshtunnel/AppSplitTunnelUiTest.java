package com.example.sshtunnel;

import android.app.Instrumentation;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class AppSplitTunnelUiTest {
    @Test public void appRoutingPageShowsModesSearchAndInstalledApps() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(
                instrumentation.getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity activity =
                (MainActivity) instrumentation.startActivitySync(intent);
        Method showPage =
                MainActivity.class.getDeclaredMethod("showAppSplitTunnelPage");
        showPage.setAccessible(true);
        instrumentation.runOnMainSync(() -> {
            try {
                showPage.invoke(activity);
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        instrumentation.waitForIdleSync();

        List<String> text = new ArrayList<>();
        collectText(activity.findViewById(android.R.id.content), text);
        assertTrue(text.contains("Туннелирование приложений"));
        assertTrue(text.stream().anyMatch(value ->
                value.startsWith("VPN везде, кроме выбранных")));
        assertTrue(text.stream().anyMatch(value ->
                value.startsWith("VPN только для выбранных")));
        assertTrue(text.stream().anyMatch(value ->
                value.equals("Поиск по названию или пакету")));
        assertTrue(text.stream().anyMatch(value ->
                value.startsWith("com.") || value.startsWith("org.")));
        activity.finish();
    }

    private static void collectText(View view, List<String> output) {
        if (view instanceof TextView) {
            CharSequence value = ((TextView) view).getText();
            if (value != null && value.length() > 0) {
                output.add(value.toString());
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectText(group.getChildAt(i), output);
            }
        }
    }
}
