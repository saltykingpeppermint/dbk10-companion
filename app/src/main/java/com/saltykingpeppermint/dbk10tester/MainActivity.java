package com.saltykingpeppermint.dbk10tester;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 1;
    private static final int REQUEST_ENABLE_BLUETOOTH = 2;
    private static final long SCAN_DURATION_MS = 15_000;

    private static final UUID DBK_SERVICE_UUID =
            UUID.fromString("75a2cfc3-30c6-4ab6-9c30-a55b0b5ba7df");
    private static final UUID LIGHT_CHARACTERISTIC_UUID =
            UUID.fromString("4980e5bc-099f-4bec-8b89-5f79237f8dfd");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, BluetoothDevice> discoveredDevices = new LinkedHashMap<>();

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt connectedGatt;
    private BluetoothGattCharacteristic lightCharacteristic;
    private TextView statusView;
    private TextView connectionView;
    private TextView characteristicView;
    private TextView inventoryView;
    private TextView readValueView;
    private LinearLayout deviceList;
    private Button scanButton;
    private Button disconnectButton;
    private Button onButton;
    private Button offButton;
    private Button readButton;
    private boolean scanning;
    private boolean writePending;

    private final Runnable stopScanTask = this::stopScan;

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null) {
                return;
            }
            try {
                String address = device.getAddress();
                runOnUiThread(() -> {
                    if (!discoveredDevices.containsKey(address)) {
                        discoveredDevices.put(address, device);
                        addDeviceRow(device);
                    }
                });
            } catch (SecurityException exception) {
                runOnUiThread(() -> setStatus("Bluetooth permission was revoked during scanning."));
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            runOnUiThread(() -> {
                scanning = false;
                scanButton.setEnabled(true);
                setStatus("BLE scan failed (code " + errorCode + ").");
            });
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread(() -> setStatus("BLE connection failed (code " + status + ")."));
                closeGatt(gatt);
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread(() -> {
                    connectionView.setText("Connected; discovering services…");
                    setStatus("Connected. Inspecting the candidate DBK.10 service.");
                });
                try {
                    if (!hasBluetoothPermissions()) {
                        runOnUiThread(() -> setStatus("Bluetooth permission was not granted."));
                    } else if (!gatt.discoverServices()) {
                        runOnUiThread(() -> setStatus("Could not start GATT service discovery."));
                    }
                } catch (SecurityException exception) {
                    runOnUiThread(() -> setStatus("Bluetooth permission was revoked."));
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                closeGatt(gatt);
                runOnUiThread(() -> {
                    connectionView.setText("Not connected");
                    lightCharacteristic = null;
                    writePending = false;
                    refreshControlAvailability();
                    setStatus("Disconnected.");
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread(() -> setStatus("Service discovery failed (code " + status + ")."));
                return;
            }

            BluetoothGattService service = gatt.getService(DBK_SERVICE_UUID);
            BluetoothGattCharacteristic characteristic = service == null
                    ? null
                    : service.getCharacteristic(LIGHT_CHARACTERISTIC_UUID);
            runOnUiThread(() -> {
                lightCharacteristic = characteristic;
                if (service == null) {
                    connectionView.setText("Connected; candidate service not found");
                    characteristicView.setText("Light characteristic: unavailable");
                    inventoryView.setText("Candidate service characteristics: unavailable");
                    readValueView.setText("Read value: unavailable");
                    setStatus("The expected service UUID was not found on this device.");
                    refreshControlAvailability();
                    return;
                }
                if (characteristic == null) {
                    connectionView.setText("Connected; light characteristic not found");
                    characteristicView.setText("Light characteristic: unavailable");
                    inventoryView.setText(describeCharacteristics(service));
                    readValueView.setText("Read value: unavailable");
                    setStatus("The expected light characteristic was not found.");
                    refreshControlAvailability();
                    return;
                }

                connectionView.setText("Connected; candidate light characteristic found");
                characteristicView.setText(
                        "Properties: " + propertyNames(characteristic.getProperties()));
                inventoryView.setText(describeCharacteristics(service));
                readValueView.setText("Read value: not read");
                setStatus("Service discovery finished. Use Read or the explicit ON/OFF controls.");
                refreshControlAvailability();
            });

            if (characteristic != null
                    && (characteristic.getProperties()
                    & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                requestRead(gatt, characteristic);
            }
        }

        @Override
        public void onCharacteristicRead(
                BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic,
                int status) {
            if (Build.VERSION.SDK_INT < 33) {
                handleCharacteristicRead(characteristic, characteristic.getValue(), status);
            }
        }

        @Override
        public void onCharacteristicRead(
                BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic,
                byte[] value,
                int status) {
            handleCharacteristicRead(characteristic, value, status);
        }

        @Override
        public void onCharacteristicWrite(
                BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic,
                int status) {
            if (!characteristic.getUuid().equals(LIGHT_CHARACTERISTIC_UUID)) {
                return;
            }
            runOnUiThread(() -> {
                writePending = false;
                refreshControlAvailability();
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    setStatus("BLE write acknowledged; check the physical lamp for the result.");
                    if ((characteristic.getProperties()
                            & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                        requestRead(gatt, characteristic);
                    }
                } else {
                    setStatus("Light write failed (GATT code " + status + ").");
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();
        buildLayout();
        refreshControlAvailability();

        if (bluetoothAdapter == null) {
            scanButton.setEnabled(false);
            setStatus("This phone does not provide Bluetooth.");
        } else if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            scanButton.setEnabled(false);
            setStatus("This phone does not support Bluetooth Low Energy.");
        }
    }

    private void buildLayout() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        content.setPadding(padding, padding, padding, padding);
        scrollView.addView(content);
        setContentView(scrollView);

        TextView title = new TextView(this);
        title.setText("DBK.10 BLE Tester");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(title);

        TextView intro = new TextView(this);
        intro.setText("Local Bluetooth test only. No Vodafone account, SIM, or cloud connection.");
        intro.setTextSize(15);
        addView(content, intro, 12);

        scanButton = new Button(this);
        scanButton.setText("Scan for BLE devices");
        scanButton.setOnClickListener(view -> requestScan());
        addView(content, scanButton, 8);

        disconnectButton = new Button(this);
        disconnectButton.setText("Disconnect");
        disconnectButton.setOnClickListener(view -> disconnectFromDevice());
        addView(content, disconnectButton, 4);

        connectionView = new TextView(this);
        connectionView.setText("Not connected");
        connectionView.setTextSize(16);
        connectionView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        addView(content, connectionView, 12);

        statusView = new TextView(this);
        statusView.setText("Ready.");
        statusView.setTextSize(14);
        addView(content, statusView, 4);

        TextView devicesTitle = new TextView(this);
        devicesTitle.setText("Nearby devices (tap one to connect)");
        devicesTitle.setTextSize(16);
        devicesTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        addView(content, devicesTitle, 20);

        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        content.addView(deviceList);

        TextView lightTitle = new TextView(this);
        lightTitle.setText("Candidate light characteristic");
        lightTitle.setTextSize(18);
        lightTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        addView(content, lightTitle, 24);

        characteristicView = new TextView(this);
        characteristicView.setText("Properties: unavailable");
        addView(content, characteristicView, 4);

        TextView inventoryTitle = new TextView(this);
        inventoryTitle.setText("Discovered characteristics");
        inventoryTitle.setTextSize(15);
        inventoryTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        addView(content, inventoryTitle, 12);

        inventoryView = new TextView(this);
        inventoryView.setText("Candidate service characteristics: unavailable");
        inventoryView.setTextIsSelectable(true);
        addView(content, inventoryView, 4);

        readValueView = new TextView(this);
        readValueView.setText("Read value: unavailable");
        addView(content, readValueView, 4);

        readButton = new Button(this);
        readButton.setText("Read characteristic");
        readButton.setOnClickListener(view -> readLightState());
        addView(content, readButton, 4);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_HORIZONTAL);
        onButton = new Button(this);
        onButton.setText("Send ON");
        onButton.setOnClickListener(view -> writeLightState(true));
        controls.addView(onButton, new LinearLayout.LayoutParams(0, -2, 1));
        offButton = new Button(this);
        offButton.setText("Send OFF");
        offButton.setOnClickListener(view -> writeLightState(false));
        controls.addView(offButton, new LinearLayout.LayoutParams(0, -2, 1));
        addView(content, controls, 4);

        TextView note = new TextView(this);
        note.setText("Only tap these controls when connected to your DBK.10. A successful GATT write does not confirm the lamp changed state.");
        note.setTextSize(13);
        addView(content, note, 12);
    }

    private void requestScan() {
        if (bluetoothAdapter == null) {
            setStatus("Bluetooth is unavailable.");
            return;
        }
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions();
            return;
        }
        try {
            if (!bluetoothAdapter.isEnabled()) {
                startActivityForResult(
                        new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                        REQUEST_ENABLE_BLUETOOTH);
                return;
            }
            scanner = bluetoothAdapter.getBluetoothLeScanner();
            if (scanner == null) {
                setStatus("BLE scanner is unavailable. Check that Bluetooth is enabled.");
                return;
            }
            discoveredDevices.clear();
            deviceList.removeAllViews();
            scanning = true;
            scanButton.setEnabled(false);
            setStatus("Scanning for nearby BLE devices for 15 seconds…");
            scanner.startScan(scanCallback);
            handler.postDelayed(stopScanTask, SCAN_DURATION_MS);
        } catch (SecurityException exception) {
            scanning = false;
            scanButton.setEnabled(true);
            setStatus("Bluetooth permission was not granted.");
        } catch (IllegalStateException exception) {
            scanning = false;
            scanButton.setEnabled(true);
            setStatus("Could not start BLE scan: " + exception.getMessage());
        }
    }

    private void stopScan() {
        handler.removeCallbacks(stopScanTask);
        if (!scanning) {
            return;
        }
        scanning = false;
        if (scanner != null && hasBluetoothPermissions()) {
            try {
                scanner.stopScan(scanCallback);
            } catch (SecurityException exception) {
                setStatus("Scan stopped, but Bluetooth permission was revoked.");
            }
        }
        scanButton.setEnabled(true);
        setStatus(discoveredDevices.isEmpty()
                ? "Scan finished; no BLE devices were found."
                : "Scan finished. Select a device to connect.");
    }

    private void addDeviceRow(BluetoothDevice device) {
        Button row = new Button(this);
        row.setText(deviceLabel(device));
        row.setOnClickListener(view -> connectToDevice(device));
        deviceList.addView(row);
    }

    private String deviceLabel(BluetoothDevice device) {
        String name = null;
        try {
            name = device.getName();
            return (name == null || name.trim().isEmpty() ? "Unnamed BLE device" : name)
                    + "\n" + device.getAddress();
        } catch (SecurityException exception) {
            return "Discovered BLE device";
        }
    }

    private void connectToDevice(BluetoothDevice device) {
        stopScan();
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions();
            return;
        }
        closeCurrentGatt();
        lightCharacteristic = null;
        characteristicView.setText("Properties: unavailable");
        readValueView.setText("Read value: unavailable");
        try {
            connectionView.setText("Connecting to " + device.getAddress());
            setStatus("Connecting over BLE…");
            connectedGatt = device.connectGatt(
                    this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            if (connectedGatt == null) {
                connectionView.setText("Not connected");
                setStatus("Could not create a GATT connection.");
            }
        } catch (SecurityException exception) {
            connectionView.setText("Not connected");
            setStatus("Bluetooth permission was not granted.");
        }
        refreshControlAvailability();
    }

    private void readLightState() {
        BluetoothGatt gatt = connectedGatt;
        BluetoothGattCharacteristic characteristic = lightCharacteristic;
        if (gatt == null || characteristic == null) {
            setStatus("Connect to a device with the candidate light characteristic first.");
            return;
        }
        if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
            setStatus("This characteristic does not advertise read support.");
            return;
        }
        try {
            if (!gatt.readCharacteristic(characteristic)) {
                setStatus("Could not start reading the characteristic.");
            } else {
                setStatus("Reading characteristic…");
            }
        } catch (SecurityException exception) {
            setStatus("Bluetooth permission was not granted.");
        }
    }

    private void disconnectFromDevice() {
        BluetoothGatt gatt = connectedGatt;
        if (gatt == null) {
            return;
        }
        try {
            gatt.disconnect();
            setStatus("Disconnecting…");
        } catch (SecurityException exception) {
            setStatus("Bluetooth permission was not granted.");
        }
    }

    private void writeLightState(boolean on) {
        BluetoothGatt gatt = connectedGatt;
        BluetoothGattCharacteristic characteristic = lightCharacteristic;
        if (gatt == null || characteristic == null || writePending) {
            return;
        }

        int properties = characteristic.getProperties();
        int writeType;
        if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        } else if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        } else {
            setStatus("This characteristic does not advertise write support.");
            refreshControlAvailability();
            return;
        }

        byte[] payload = lightCommand(on);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                int result = gatt.writeCharacteristic(characteristic, payload, writeType);
                if (result != BluetoothStatusCodes.SUCCESS) {
                    setStatus("Could not start the light write (code " + result + ").");
                    return;
                }
            } else {
                characteristic.setWriteType(writeType);
                characteristic.setValue(payload);
                if (!gatt.writeCharacteristic(characteristic)) {
                    setStatus("Could not start the light write.");
                    return;
                }
            }
            writePending = true;
            setStatus("Sending " + (on ? "ON" : "OFF") + "…");
            refreshControlAvailability();
        } catch (SecurityException exception) {
            setStatus("Bluetooth permission was not granted.");
        }
    }

    private void handleCharacteristicRead(
            BluetoothGattCharacteristic characteristic,
            byte[] value,
            int status) {
        if (!characteristic.getUuid().equals(LIGHT_CHARACTERISTIC_UUID)) {
            return;
        }
        runOnUiThread(() -> {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setStatus("Characteristic read failed (GATT code " + status + ").");
                return;
            }
            readValueView.setText("Read value: " + describeBytes(value));
            setStatus("Read completed. Compare the value with the physical light state.");
        });
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(
                    new String[]{
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                    },
                    REQUEST_PERMISSIONS);
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(
                    new String[]{
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    },
                    REQUEST_PERMISSIONS);
        }
    }

    private void requestRead(
            BluetoothGatt gatt,
            BluetoothGattCharacteristic characteristic) {
        try {
            if (!hasBluetoothPermissions()) {
                runOnUiThread(() -> setStatus("Bluetooth permission was not granted."));
            } else if (!gatt.readCharacteristic(characteristic)) {
                runOnUiThread(() -> setStatus("Could not start reading the characteristic."));
            } else {
                runOnUiThread(() -> setStatus("Reading characteristic…"));
            }
        } catch (SecurityException exception) {
            runOnUiThread(() -> setStatus("Bluetooth permission was revoked."));
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (hasBluetoothPermissions()) {
                requestScan();
            } else {
                setStatus("Bluetooth permission is required for local device access.");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            if (resultCode == RESULT_OK) {
                requestScan();
            } else {
                setStatus("Bluetooth was not enabled.");
            }
        }
    }

    @Override
    protected void onDestroy() {
        stopScan();
        closeCurrentGatt();
        super.onDestroy();
    }

    private void closeCurrentGatt() {
        BluetoothGatt gatt = connectedGatt;
        connectedGatt = null;
        lightCharacteristic = null;
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (SecurityException exception) {
                setStatus("Bluetooth permission was revoked before the connection closed.");
            }
        }
        refreshControlAvailability();
    }

    private void closeGatt(BluetoothGatt gatt) {
        if (connectedGatt == gatt) {
            connectedGatt = null;
            lightCharacteristic = null;
        }
        try {
            gatt.close();
        } catch (SecurityException exception) {
            runOnUiThread(() -> setStatus("Bluetooth permission was revoked before the connection closed."));
        }
        runOnUiThread(this::refreshControlAvailability);
    }

    private void refreshControlAvailability() {
        if (readButton == null) {
            return;
        }
        int properties = lightCharacteristic == null ? 0 : lightCharacteristic.getProperties();
        boolean connected = connectedGatt != null;
        boolean readable = connected
                && lightCharacteristic != null
                && (properties & BluetoothGattCharacteristic.PROPERTY_READ) != 0;
        boolean writable = connected
                && lightCharacteristic != null
                && ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                || (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0);
        readButton.setEnabled(readable);
        onButton.setEnabled(writable && !writePending);
        offButton.setEnabled(writable && !writePending);
        disconnectButton.setEnabled(connected);
    }

    private void setStatus(String message) {
        statusView.setText(message);
    }

    private static byte[] lightCommand(boolean on) {
        return (on ? "ON" : "OFF").getBytes(StandardCharsets.US_ASCII);
    }

    private static String describeBytes(byte[] value) {
        if (value == null || value.length == 0) {
            return "(empty)";
        }
        StringBuilder hex = new StringBuilder();
        for (byte item : value) {
            if (hex.length() > 0) {
                hex.append(' ');
            }
            hex.append(String.format(Locale.ROOT, "%02X", item & 0xFF));
        }
        String ascii = new String(value, StandardCharsets.US_ASCII)
                .replaceAll("[^\\x20-\\x7E]", ".");
        return "hex [" + hex + "], ASCII [" + ascii + "]";
    }

    private static String propertyNames(int properties) {
        ArrayList<String> names = new ArrayList<>();
        if ((properties & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
            names.add("read");
        }
        if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            names.add("write");
        }
        if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            names.add("write without response");
        }
        if ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
            names.add("notify");
        }
        if ((properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
            names.add("indicate");
        }
        if (names.isEmpty()) {
            return "none recognized";
        }
        StringBuilder result = new StringBuilder();
        for (String name : names) {
            if (result.length() > 0) {
                result.append(", ");
            }
            result.append(name);
        }
        return result.toString();
    }

    private static String describeCharacteristics(BluetoothGattService service) {
        StringBuilder result = new StringBuilder();
        for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(characteristic.getUuid())
                    .append(" — ")
                    .append(propertyNames(characteristic.getProperties()));
        }
        return result.length() == 0 ? "(none)" : result.toString();
    }

    private void addView(LinearLayout parent, View child, int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(topMarginDp);
        parent.addView(child, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
