package com.sensemore.slilabs.ota;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

public class OtaActivity extends AppCompatActivity {

    private static UUID OTA_CONTROL_CHARACTERISTIC = UUID.fromString("F7BF3564-FB6D-4E53-88A4-5E37E0326063");
    private static UUID OTA_DATA_CHARACTERISTIC = UUID.fromString("984227F3-34FC-4045-A5D0-2C581F81A153");
    public static UUID OTA_SERVICE = UUID.fromString("1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0");

    private static final int PICKFILE_REQUESTCODE = 1;
    private static final int BLE_PERMISSIO_REQUSETCODE = 3;
    private static final int READ_EXTERNAL_STORAGE_REQUESTCODE = 2;
    private static final long CONNECT_TIMEOUT = 10000;

    private Button browseFileButton;
    private Button startOtaButton;
    private TextView macAddressTextView;
    private TextView fileNameTextView;

    private BluetoothAdapter mBluetoothAdapter;
    private String macAddress;
    private Timer connectionTimeout;
    private BluetoothDevice device;
    private String deviceName;
    private Handler handler;
    private int MTU = 247;
    private byte[] firmwareFile;
    private int index;

    class State {
        public static final String Connecting = "Connecting";
        public static final String ResetDFU = "ResetDFU";
        public static final String Reconnecting = "Reconnecting";
        public static final String OtaBegin = "Ota Begin";
        public static final String OtaUpload = "OtaUpload";
        public static final String OtaEnd = "OtaEnd";
        public static final String Disconnecting = "Disconnecting";
        public static final String Ready = "Ready";
    }

    private HashMap<String, ProgressBar> progressMap = new HashMap<>();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ota);
        handler = new Handler();
        browseFileButton = findViewById(R.id.browseFile);
        startOtaButton = findViewById(R.id.startOta);
        macAddressTextView = findViewById(R.id.macAddress);
        fileNameTextView = findViewById(R.id.fileName);

        progressMap.put(State.Connecting, findViewById(R.id.connectingProgress));
        progressMap.put(State.ResetDFU, findViewById(R.id.resetDFUProgress));
        progressMap.put(State.Reconnecting, findViewById(R.id.reconnectingProgress));
        progressMap.put(State.OtaBegin, findViewById(R.id.otaBeginProgress));
        progressMap.put(State.OtaUpload, findViewById(R.id.otaUploadProgress));
        progressMap.put(State.OtaEnd, findViewById(R.id.otaEndProgress));
        progressMap.put(State.Disconnecting, findViewById(R.id.disconnectingProgress));
        SetProgress(State.Ready);
        browseFileButton.setOnClickListener(view -> {
            if (CheckChooseFilePermission()) {
                Intent chooseFile = new Intent(Intent.ACTION_GET_CONTENT);
                chooseFile.setType("*/*");
                chooseFile = Intent.createChooser(chooseFile, "Choose a file");
                startActivityForResult(chooseFile, PICKFILE_REQUESTCODE);
            }
        });

        startOtaButton.setOnClickListener(v -> {
            if (CheckBlePermissions() && CheckBluetoothEnabled() && CheckLocationEnabled() && CheckMacAddress()) {

                Toast.makeText(getApplicationContext(), "BEGIN", Toast.LENGTH_SHORT).show();
                ConnectDevice();
            }
        });
    }


    private void ConnectDevice() {
        SetProgress(State.Connecting);
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        connectionTimeout = new Timer();

        //create timer for connection timeout
        connectionTimeout.schedule(new TimerTask() {
            @Override
            public void run() {
                ToastMessage("Connection timeout, make sure you write mac address correct and ble device is discoverable");
            }
        }, CONNECT_TIMEOUT);
        device = mBluetoothAdapter.getRemoteDevice(macAddress);

        // Here we are connecting to target device
        device.connectGatt(getApplicationContext(), false, new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                Log.i("OTA", "state " + newState);
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i("OTA", "Connected " + device.getName() + " address: " + device.getAddress());
                    deviceName = device.getName();
                    connectionTimeout.cancel();
                    connectionTimeout.purge();
                    gatt.discoverServices(); // Directly discovering services
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i("OTA", "Disconnecting ");
                    gatt.close();
                    gatt.disconnect();
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i("OTA", "onServicesDiscovered deviceName: " + deviceName);
                    //We have connected to device and discovered services
                    //if OTA_SERVICE has OTA_DATA_CHARACTERISTIC target device already in dfu mode
                    if (gatt.getService(OTA_SERVICE).getCharacteristic(OTA_DATA_CHARACTERISTIC) != null) {
                        ConnectOtaDevice(gatt);
                    } else {
                        ResetDFU(gatt);
                    }
                }
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                Log.i("OTA", "onCharacteristicWrite " + characteristic.getUuid().toString());
                if (characteristic.getUuid().equals(OTA_CONTROL_CHARACTERISTIC) && characteristic.getValue()[0] == 0x00) {
                    //target device ´rebooting into OTA
                    ConnectDelayedForOTA(gatt);//reconnect
                }

            }
        });
    }

    private void ResetDFU(BluetoothGatt gatt) {
        SetProgress(State.ResetDFU);
        //Writing 0x00 to control characteristic to reboot target device into DFU mode
        handler.post(() -> {
            Log.i("OTA", "OTA RESET INTO DFU");
            BluetoothGattService service = gatt.getService(OTA_SERVICE);
            BluetoothGattCharacteristic characteristic = service.getCharacteristic(OTA_CONTROL_CHARACTERISTIC);
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            characteristic.setValue(new byte[]{0x00});
            gatt.writeCharacteristic(characteristic);// result will be handled in onCharacteristicWrite callback of gatt.
        });
    }

    private void RequestMTU(BluetoothGatt gatt) {
        gatt.requestMtu(MTU+3);
    }//I dunno why but we neet to request 3 more for what required :/

    private void OtaBegin(BluetoothGatt gatt) {
        SetProgress(State.OtaBegin);

        //Writing 0x00 to control characteristic to DFU mode  target device begins OTA process
        handler.postDelayed(() -> {
            BluetoothGattService service = gatt.getService(OTA_SERVICE);
            BluetoothGattCharacteristic characteristic = service.getCharacteristic(OTA_CONTROL_CHARACTERISTIC);
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            characteristic.setValue(new byte[]{0x00});
            gatt.writeCharacteristic(characteristic);
        }, 500);
    }

    private void OtaUpload(BluetoothGatt gatt) {
        SetProgress(State.OtaUpload);

        ToastMessage("Uploading!");

        index = 0;
        new Thread(() -> {

            boolean last = false;
            int packageCount = 0;
            while (!last) {
                byte[] payload = new byte[MTU];
                if (index + MTU >= firmwareFile.length) {
                    int restSize = firmwareFile.length - index;
                    System.arraycopy(firmwareFile, index, payload, 0, restSize); //copy rest bytes
                    last = true;
                } else {
                    payload = Arrays.copyOfRange(firmwareFile, index, index + MTU);
                }
                BluetoothGattService service = gatt.getService(OTA_SERVICE);
                BluetoothGattCharacteristic characteristic = service.getCharacteristic(OTA_DATA_CHARACTERISTIC);
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                characteristic.setValue(payload);
                Log.d("OTA", "index :" + index + " firmware lenght:" + firmwareFile.length);
                while (!gatt.writeCharacteristic(characteristic)) { // attempt to write until getting success
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

                packageCount = packageCount + 1;
                index = index + MTU;
            }
            Log.i("OTA", "OTA UPLOAD SEND DONE");
            OtaEnd(gatt);
        }).start();
    }

    private void OtaEnd(BluetoothGatt gatt) {
        SetProgress(State.OtaEnd);

        handler.postDelayed(() -> {
            Log.i("OTA", "OTA END");
            BluetoothGattCharacteristic endCharacteristic = gatt.getService(OTA_SERVICE).getCharacteristic(OTA_CONTROL_CHARACTERISTIC);
            endCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            endCharacteristic.setValue(new byte[]{0x03});
            int i = 0;
            while (!gatt.writeCharacteristic(endCharacteristic)) {
                i++;
                Log.i("OTA", "Failed to write end 0x03 retry:" + i);
            }
        }, 1500);
    }

    private void ConnectOtaDevice(BluetoothGatt gatt) {

        if (gatt != null) {
            gatt.close();
            gatt.disconnect();
        }
        device = mBluetoothAdapter.getRemoteDevice(macAddress);
        device.connectGatt(OtaActivity.this, false, new BluetoothGattCallback() {
            // This is OTA devices callback
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    //Here, we are connected to target device which is in DFU mode
                    deviceName = device.getName();
                    gatt.discoverServices(); // Directly discovering services
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i("OTA", "Disconnecting ");
                    gatt.close();
                    gatt.disconnect();
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i("OTA", "onServicesDiscovered deviceName: " + deviceName);
                    //We have connected to device and discovered services
                    OtaBegin(gatt);

                }
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                super.onCharacteristicRead(gatt, characteristic, status);
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                Log.i("OTA", "onCharacteristicWrite " + characteristic.getUuid().toString());
                if (characteristic.getUuid().equals(OTA_CONTROL_CHARACTERISTIC) && characteristic.getValue()[0] == 0x00) {
                    //OTA Begin written
                    RequestMTU(gatt);// will be handled in onMtuChanged callback of gatt
                } else if (characteristic.getUuid().equals(OTA_CONTROL_CHARACTERISTIC) && characteristic.getValue()[0] == 0x03) {
                    //OTA End written
                    SetProgress(State.Disconnecting);
                    ToastMessage("Upload Done!");// will be handled in onMtuChanged callback of gatt
                    RebootTargetDevice(gatt);
                }
                else if (characteristic.getUuid().equals(OTA_CONTROL_CHARACTERISTIC) && characteristic.getValue()[0] == 0x04) {
                    //OTA End written
                    SetProgress(State.Ready);
                    ToastMessage("Upload Done!");// will be handled in onMtuChanged callback of gatt
                }
            }

            @Override
            public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
                super.onMtuChanged(gatt, mtu, status);
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i("OTA", "onMtuChanged mtu: " + mtu);
                    //We have successfully request MTU we can start upload process
                    OtaUpload(gatt);

                }
            }
        });
    }

    private void RebootTargetDevice(BluetoothGatt gatt) {
        handler.postDelayed(() -> {
            BluetoothGattService service = gatt.getService(OTA_SERVICE);
            BluetoothGattCharacteristic characteristic = service.getCharacteristic(OTA_CONTROL_CHARACTERISTIC);
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            characteristic.setValue(new byte[]{0x04});
            gatt.writeCharacteristic(characteristic);
        }, 500);
    }


    private void ConnectDelayedForOTA(BluetoothGatt gatt) {
        SetProgress(State.Reconnecting);

        //after writing 0x00 to target device device will reboot into DFU mode
        //We are waiting a little bit just to be sure
        handler.postDelayed(() -> {
            Log.i("OTA", "CONNECTING FOR OTA");
            ConnectOtaDevice(gatt);
        }, 5000);

    }

    private void ToastMessage(String message) {
        runOnUiThread(() -> {
            Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();

        });
    }

    private void SetProgress(String state) {
        runOnUiThread(() -> {
            for (ProgressBar bar : progressMap.values()) {
                bar.setVisibility(View.INVISIBLE);
            }
            if (progressMap.get(state) != null) {
                progressMap.get(state).setVisibility(View.VISIBLE);
            }
        });
    }


    private boolean CheckMacAddress() {
        macAddress = macAddressTextView.getText().toString().trim().toUpperCase();
        if (BluetoothAdapter.checkBluetoothAddress(macAddress)) {
            return true;
        } else {
            ToastMessage("Mac Address not valid");
            return false;
        }
    }

    private boolean CheckBluetoothEnabled() {
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            ToastMessage("Doesnt support bluetooth");
            return false;
        } else if (!mBluetoothAdapter.isEnabled()) {
            ToastMessage("Please enable your bluetooth");
            return false;

        } else {
            return true;
        }

    }

    private boolean CheckBlePermissions() {
        if (ContextCompat.checkSelfPermission(
                OtaActivity.this,
                Manifest.permission.BLUETOOTH)
                == PackageManager.PERMISSION_DENIED
                ||
                ContextCompat.checkSelfPermission(
                        OtaActivity.this,
                        Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_DENIED

        ) {
            ActivityCompat
                    .requestPermissions(
                            OtaActivity.this,
                            new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH},
                            BLE_PERMISSIO_REQUSETCODE);
            return false;
        } else {
            return true;
        }
    }

    private boolean CheckChooseFilePermission() {
        if (ContextCompat.checkSelfPermission(
                OtaActivity.this,
                Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_DENIED) {
            ActivityCompat
                    .requestPermissions(
                            OtaActivity.this,
                            new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                            READ_EXTERNAL_STORAGE_REQUESTCODE);
            return false;
        } else {
            return true;
        }
    }

    public boolean CheckLocationEnabled() {
        int locationMode = 0;
        String locationProviders;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            try {
                locationMode = Settings.Secure.getInt(this.getContentResolver(), Settings.Secure.LOCATION_MODE);

            } catch (Settings.SettingNotFoundException e) {
                e.printStackTrace();
                ToastMessage("Doesnt support location");
                return false;

            }
            if (locationMode == Settings.Secure.LOCATION_MODE_OFF) {
                ToastMessage("Please enable location");
                return false;
            } else {
                return true;
            }

        } else {
            locationProviders = Settings.Secure.getString(this.getContentResolver(), Settings.Secure.LOCATION_PROVIDERS_ALLOWED);
            if (TextUtils.isEmpty(locationProviders)) {
                ToastMessage("Please enable location");
                return false;
            } else {
                return true;
            }
        }
    }

    private void PrepareFile(Uri uri) {
        ToastMessage(uri.getLastPathSegment());
        fileNameTextView.setText(uri.getLastPathSegment());
        startOtaButton.setEnabled(true);
        try {
            InputStream in = getContentResolver().openInputStream(uri);

            firmwareFile = new byte[in.available()];
            in.read(firmwareFile, 0, in.available());
            in.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            switch (requestCode) {
                case PICKFILE_REQUESTCODE:
                    PrepareFile(data.getData());
                    break;
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode,
                permissions,
                grantResults);

        if (requestCode == READ_EXTERNAL_STORAGE_REQUESTCODE) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                browseFileButton.callOnClick();
            } else {
                ToastMessage("You should give permission for read storage to continue");

            }
        } else if (requestCode == BLE_PERMISSIO_REQUSETCODE) {
            if (Arrays.stream(grantResults).allMatch(x -> x == PackageManager.PERMISSION_GRANTED)) {
                startOtaButton.callOnClick();

            } else {
                ToastMessage("You should give Location and Bluetooth to continue");
            }
        }
    }

}
