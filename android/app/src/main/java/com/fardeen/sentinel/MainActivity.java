package com.fardeen.sentinel;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;

import androidx.appcompat.app.AppCompatActivity;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Hosts the EXISTING index.html unchanged in a WebView, and exposes a native
 * USB OTG serial bridge ("AndroidUSB") that the injected JS polyfill
 * (usb_bridge_polyfill.js) uses to make navigator.serial work on Android.
 *
 * No app logic, HTML, CSS, or JS behavior is modified. This class only feeds
 * bytes in/out of the existing communication layer via the polyfill's
 * ReadableStream/WritableStream, exactly matching the real Web Serial API.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "SentinelUsbOtg";
    private static final String ACTION_USB_PERMISSION = "com.fardeen.sentinel.USB_PERMISSION";

    private WebView webView;
    private UsbManager usbManager;
    private UsbSerialPort usbSerialPort;
    private SerialInputOutputManager ioManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private String pendingPolyfillJs;

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            synchronized (this) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted && device != null) {
                    openDeviceAndNotify(device);
                } else {
                    notifyConnectResult(false, "USB permission denied");
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbPermissionReceiver, filter);
        }

        webView = findViewById(R.id.webview);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false); // preserves existing siren/audio behavior

        webView.addJavascriptInterface(new UsbJsBridge(), "AndroidUSB");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectPolyfill();
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    private void injectPolyfill() {
        // Loaded once and cached; read from the same assets folder as index.html.
        if (pendingPolyfillJs == null) {
            pendingPolyfillJs = readAsset("usb_bridge_polyfill.js");
        }
        if (pendingPolyfillJs != null) {
            webView.evaluateJavascript(pendingPolyfillJs, null);
        }
    }

    private String readAsset(String name) {
        try (java.io.InputStream is = getAssets().open(name)) {
            byte[] buf = new byte[is.available()];
            int read = is.read(buf);
            return new String(buf, 0, Math.max(read, 0), "UTF-8");
        } catch (IOException e) {
            Log.e(TAG, "Failed to read asset " + name, e);
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // JS bridge — every method here is called FROM the polyfill JS.
    // Mirrors the Web Serial API's async shape: JS awaits a Promise
    // that we resolve/reject later via evaluateJavascript callbacks.
    // ─────────────────────────────────────────────────────────────
    private class UsbJsBridge {

        @JavascriptInterface
        public void requestConnect() {
            mainHandler.post(MainActivity.this::findAndConnect);
        }

        @JavascriptInterface
        public void write(String base64) {
            if (usbSerialPort == null) return;
            try {
                byte[] data = Base64.decode(base64, Base64.NO_WRAP);
                usbSerialPort.write(data, 500);
            } catch (Exception e) {
                Log.w(TAG, "USB write failed: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void disconnect() {
            mainHandler.post(MainActivity.this::closeUsb);
        }
    }

    private void findAndConnect() {
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) {
            notifyConnectResult(false, "No USB serial (Arduino) device found");
            return;
        }
        UsbSerialDriver driver = drivers.get(0);
        UsbDevice device = driver.getDevice();

        if (usbManager.hasPermission(device)) {
            openDeviceAndNotify(device);
            return;
        }

        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent permissionIntent = PendingIntent.getBroadcast(
                this, 0, new Intent(ACTION_USB_PERMISSION), flags);
        usbManager.requestPermission(device, permissionIntent);
        // Result arrives asynchronously in usbPermissionReceiver above.
    }

    private void openDeviceAndNotify(UsbDevice device) {
        try {
            List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
            UsbSerialDriver driver = null;
            for (UsbSerialDriver d : drivers) {
                if (d.getDevice().equals(device)) { driver = d; break; }
            }
            if (driver == null) {
                notifyConnectResult(false, "Driver not found for device");
                return;
            }

            android.hardware.usb.UsbDeviceConnection connection = usbManager.openDevice(device);
            if (connection == null) {
                notifyConnectResult(false, "Could not open USB connection");
                return;
            }

            usbSerialPort = driver.getPorts().get(0);
            usbSerialPort.open(connection);
            // 115200 8N1 — SAME baud rate the existing app already requests via port.open({baudRate:115200})
            usbSerialPort.setParameters(115200, 8,
                    UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

            ioManager = new SerialInputOutputManager(usbSerialPort, new SerialInputOutputManager.Listener() {
                @Override
                public void onNewData(byte[] data) {
                    String base64 = Base64.encodeToString(data, Base64.NO_WRAP);
                    mainHandler.post(() -> {
                        if (webView != null) {
                            webView.evaluateJavascript(
                                    "window.__sentinelUsbOnData && window.__sentinelUsbOnData('" + base64 + "');",
                                    null);
                        }
                    });
                }

                @Override
                public void onRunError(Exception e) {
                    mainHandler.post(() -> notifyDisconnect(e.getMessage()));
                }
            });
            Executors.newSingleThreadExecutor().submit(ioManager);

            notifyConnectResult(true, null);
        } catch (IOException e) {
            notifyConnectResult(false, "USB open error: " + e.getMessage());
        }
    }

    private void closeUsb() {
        if (ioManager != null) {
            ioManager.stop();
            ioManager = null;
        }
        if (usbSerialPort != null) {
            try { usbSerialPort.close(); } catch (IOException ignored) {}
            usbSerialPort = null;
        }
    }

    private void notifyConnectResult(boolean success, String message) {
        String safeMsg = message == null ? "" : message.replace("'", "\\'");
        mainHandler.post(() -> {
            if (webView != null) {
                webView.evaluateJavascript(
                        "window.__sentinelUsbOnConnect && window.__sentinelUsbOnConnect(" + success + ", '" + safeMsg + "');",
                        null);
            }
        });
    }

    private void notifyDisconnect(String message) {
        String safeMsg = message == null ? "" : message.replace("'", "\\'");
        closeUsb();
        if (webView != null) {
            webView.evaluateJavascript(
                    "window.__sentinelUsbOnDisconnect && window.__sentinelUsbOnDisconnect('" + safeMsg + "');",
                    null);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(usbPermissionReceiver); } catch (Exception ignored) {}
        closeUsb();
    }
}
