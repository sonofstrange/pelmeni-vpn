package com.example.sshtunnel;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SpeedtestAutomationTest {
    private static final String SPEEDTEST_PACKAGE = "org.zwanoo.android.speedtest";

    @Test
    public void runOoklaSpeedtest() throws Exception {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(SPEEDTEST_PACKAGE);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);
        }

        device.wait(Until.hasObject(By.pkg(SPEEDTEST_PACKAGE).depth(0)), 10_000);

        UiObject2 goButton = device.wait(Until.findObject(By.textContains("Проверить снова")), 5_000);
        if (goButton == null) {
            goButton = device.findObject(By.textContains("НАЧАТЬ"));
        }
        if (goButton == null) {
            goButton = device.findObject(By.textContains("GO"));
        }
        if (goButton == null) {
            goButton = device.findObject(By.textContains("Test Again"));
        }
        if (goButton == null) {
            goButton = device.findObject(By.res(SPEEDTEST_PACKAGE, "go_button"));
        }

        if (goButton != null) {
            goButton.click();
        } else {
            device.click(device.getDisplayWidth() / 2, (int) (device.getDisplayHeight() * 0.55));
        }

        // Wait for speedtest to complete (approx 35-45s)
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 60_000) {
            Thread.sleep(3_000);
            UiObject2 retry = device.findObject(By.textContains("Проверить снова"));
            if (retry == null) retry = device.findObject(By.textContains("Test Again"));
            if (retry != null && (System.currentTimeMillis() - start > 18_000)) {
                break;
            }
        }

        StringBuilder sb = new StringBuilder("=== SPEEDTEST RESULTS ===\n");
        for (UiObject2 textNode : device.findObjects(By.clazz("android.widget.TextView"))) {
            String txt = textNode.getText();
            if (txt != null && !txt.trim().isEmpty()) {
                sb.append("NODE: ").append(txt.trim()).append("\n");
            }
        }
        android.util.Log.i("SPEEDTEST_RESULT", sb.toString());
        System.out.println(sb.toString());
    }
}