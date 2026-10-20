package com.aleyvir.purabox;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private WebView myWebView;
    private BluetoothAdapter bluetoothAdapter;
    private static final String TAG = "PuraBoxBT";

    private BluetoothSocket bluetoothSocket;
    private OutputStream outputStream;
    private InputStream inputStream;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic bleCharacteristic;

    private static final UUID MY_UUID_CLASSIC = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final UUID UUID_BLE_SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_BLE_CHAR = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_CLIENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean stopWorker = false;
    private final Queue<String> commandQueue = new LinkedList<>();
    private boolean isWriting = false;
        private boolean isConnecting = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        myWebView = findViewById(R.id.webViewPuraBox);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (myWebView != null) {
            WebSettings ws = myWebView.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            myWebView.setWebViewClient(new WebViewClient());
            myWebView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");
            myWebView.loadUrl("file:///android_asset/index.html");
        }
    }

    public class WebAppInterface {
        @JavascriptInterface
        public void conectarBluetooth() {
            if (isConnecting) return;
            mainHandler.post(() -> checkPermissionsAndConnect());
        }
        @JavascriptInterface
        public void enviarBluetooth(String data) {
            queueCommand(data);
        }
    }

    private void checkPermissionsAndConnect() {
        String[] permissions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? 
            new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION} :
            new String[]{Manifest.permission.ACCESS_FINE_LOCATION};

        boolean allGranted = true;
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false; break;
            }
        }

        if (!allGranted) {
            ActivityCompat.requestPermissions(this, permissions, 1);
        } else {
            findAndConnect();
        }
    }

    @SuppressLint("MissingPermission")
    private void findAndConnect() {
        if (isConnecting) return;
        isConnecting = true;

        Set<BluetoothDevice> paired = bluetoothAdapter.getBondedDevices();
        BluetoothDevice target = null;
        for (BluetoothDevice d : paired) {
            String name = d.getName();
            if (name != null) {
                String n = name.toUpperCase();
                if ((n.contains("HC-") || n.contains("JDY") || n.contains("PURA") || n.contains("BT0")) 
                     && !n.contains("SPEAKER") && !n.contains("AUDIO")) {
                    target = d; break;
                }
            }
        }

        if (target != null) {
            final BluetoothDevice device = target;
            new Thread(() -> {
                closePreviousConnections();
                // Intentar Classic primero. Si falla, intentar LE.
                if (!connectClassic(device)) {
                    runOnUiThread(() -> connectLE(device));
                }
            }).start();
        } else {
            isConnecting = false;
            notifyErrorToJS("Módulo no vinculado en el sistema");
        }
    }

    @SuppressLint("MissingPermission")
    private void closePreviousConnections() {
        stopWorker = true;
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        if (bluetoothSocket != null) {
            try { bluetoothSocket.close(); } catch (IOException ignored) {}
            bluetoothSocket = null;
        }
        bleCharacteristic = null;
        outputStream = null;
        inputStream = null;
    }

    @SuppressLint("MissingPermission")
    private boolean connectClassic(BluetoothDevice device) {
        try {
            bluetoothSocket = device.createRfcommSocketToServiceRecord(MY_UUID_CLASSIC);
            bluetoothSocket.connect();
            outputStream = bluetoothSocket.getOutputStream();
            inputStream = bluetoothSocket.getInputStream();
            startReaderClassic();
            onSuccessConnection();
            return true;
        } catch (IOException e) { return false; }
    }

    @SuppressLint("MissingPermission")
    private void connectLE(BluetoothDevice device) {
        // Uso de TRANSPORT_LE para mayor estabilidad en módulos modernos
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnecting = false;
                notifyErrorToJS("Conexión perdida");
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BluetoothGattService s = gatt.getService(UUID_BLE_SERVICE);
            if (s != null) {
                bleCharacteristic = s.getCharacteristic(UUID_BLE_CHAR);
                if (bleCharacteristic != null) {
                    gatt.setCharacteristicNotification(bleCharacteristic, true);
                    BluetoothGattDescriptor d = bleCharacteristic.getDescriptor(UUID_CLIENT_CONFIG);
                    if (d != null) {
                        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        gatt.writeDescriptor(d);
                    }
                    onSuccessConnection();
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic c) {
            sendToJs(new String(c.getValue()));
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic c, int status) {
            isWriting = false;
            processNextCommand();
        }
    };

    private void onSuccessConnection() {
        isConnecting = false;
        runOnUiThread(() -> {
            myWebView.evaluateJavascript("onBluetoothConectado()", null);
            mainHandler.postDelayed(() -> queueCommand("STATUS_CHECK"), 1000);
        });
    }

    private void startReaderClassic() {
        stopWorker = false;
        new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while (!stopWorker && (line = r.readLine()) != null) {
                    sendToJs(line);
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void sendToJs(String data) {
        runOnUiThread(() -> myWebView.evaluateJavascript("onArduinoMensaje(" + JSONObject.quote(data.trim()) + ")", null));
    }

    private void queueCommand(String data) {
        synchronized (commandQueue) {
            commandQueue.add(data);
            if (!isWriting) processNextCommand();
        }
    }

    @SuppressLint("MissingPermission")
    private void processNextCommand() {
        String data;
        synchronized (commandQueue) { data = commandQueue.poll(); }
        if (data == null) return;
        final String d = data.endsWith("\n") ? data : data + "\n";
        
        if (outputStream != null) {
            new Thread(() -> {
                try { outputStream.write(d.getBytes()); Thread.sleep(200); processNextCommand(); } catch (Exception ignored) {}
            }).start();
        } else if (bluetoothGatt != null && bleCharacteristic != null) {
            isWriting = true;
            bleCharacteristic.setValue(d.getBytes());
            bluetoothGatt.writeCharacteristic(bleCharacteristic);
        }
    }

    private void notifyErrorToJS(String m) {
        isConnecting = false;
        runOnUiThread(() -> {
            Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
            myWebView.evaluateJavascript("onBluetoothError(" + JSONObject.quote(m) + ")", null);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closePreviousConnections();
    }
}
