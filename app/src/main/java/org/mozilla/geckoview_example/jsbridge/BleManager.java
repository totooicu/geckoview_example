package org.mozilla.geckoview_example.jsbridge;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bluetooth Low Energy bridge: Central (scanner + GATT client) and Peripheral (advertiser +
 * GATT server).
 *
 * <p>Actions (via JsBridge): ble.status, ble.startScan, ble.stopScan, ble.connect,
 * ble.disconnect, ble.read, ble.write, ble.subscribe, ble.unsubscribe,
 * ble.peripheral.start, ble.peripheral.stop, ble.peripheral.setValue.
 */
public class BleManager {
  private static final String LOGTAG = "BleManager";
  private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

  private final Context mContext;
  private final JsBridge mBridge;

  private final BluetoothManager mBtManager;
  private final BluetoothAdapter mAdapter;
  @Nullable private BluetoothLeScanner mScanner;
  @Nullable private BluetoothLeAdvertiser mAdvertiser;

  @Nullable private BluetoothGatt mGatt;
  @Nullable private BluetoothGattServer mGattServer;
  private boolean mScanning = false;
  private String mCurrentOpId = "";

  private final Map<String, BluetoothGatt> mGattClients = new HashMap<>();
  private final Map<UUID, byte[]> mPeripheralValues = new HashMap<>();
  private final Map<BluetoothDevice, Boolean> mPeripheralSubscribed = new HashMap<>();

  public BleManager(Context context, JsBridge bridge) {
    mContext = context.getApplicationContext();
    mBridge = bridge;
    mBtManager = (BluetoothManager) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
    mAdapter = mBtManager != null ? mBtManager.getAdapter() : null;
  }

  /** Called by JsBridge router. */
  public void handle(String id, String action, JSONObject params, JsBridge bridge) {
    switch (action) {
      case "ble.status":
        handleStatus(id, params);
        break;
      case "ble.startScan":
        handleStartScan(id, params);
        break;
      case "ble.stopScan":
        stopScan();
        bridge.respond(id, true, null, null);
        break;
      case "ble.connect":
        handleConnect(id, params, bridge);
        break;
      case "ble.disconnect":
        handleDisconnect(id, bridge);
        break;
      case "ble.read":
        handleRead(id, params, bridge);
        break;
      case "ble.write":
        handleWrite(id, params, bridge);
        break;
      case "ble.subscribe":
        handleSubscribe(id, params, bridge, true);
        break;
      case "ble.unsubscribe":
        handleSubscribe(id, params, bridge, false);
        break;
      case "ble.peripheral.start":
        handlePeripheralStart(id, params, bridge);
        break;
      case "ble.peripheral.stop":
        stopPeripheral();
        bridge.respond(id, true, null, null);
        break;
      case "ble.peripheral.setValue":
        handlePeripheralSetValue(id, params, bridge);
        break;
      default:
        bridge.respond(id, false, null, "unknown ble action: " + action);
    }
  }

  /* ----------------------------------------------------------------------------
   * Helpers
   * -------------------------------------------------------------------------- */

  private boolean bleReady(String id, JsBridge bridge, boolean needAdvertise) {
    if (mAdapter == null) {
      bridge.respond(id, false, null, "蓝牙不可用（无适配器）");
      return false;
    }
    if (!mAdapter.isEnabled()) {
      bridge.respond(id, false, null, "蓝牙未开启，请在系统设置中打开蓝牙");
      return false;
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      String[] perms = needAdvertise
          ? new String[] {
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_ADVERTISE
          }
          : new String[] {
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT
          };
      for (String p : perms) {
        if (!JsBridge.hasPermission(mContext, p)) {
          bridge.respond(id, false, null, "缺少蓝牙运行时权限: " + p.split("\\.")[2]);
          return false;
        }
      }
    }
    return true;
  }

  private void emitDeviceFound(BluetoothDevice device, int rssi) {
    try {
      JSONObject d = new JSONObject();
      d.put("address", device.getAddress());
      d.put("name", device.getName() != null ? device.getName() : "");
      d.put("rssi", rssi);
      mBridge.emit("ble.deviceFound", d);
    } catch (Exception e) {
      Log.e(LOGTAG, "emitDeviceFound", e);
    }
  }

  /* ----------------------------------------------------------------------------
   * Central: scan
   * -------------------------------------------------------------------------- */

  private void handleStatus(String id, JSONObject params) {
    try {
      JSONObject r = new JSONObject();
      r.put("supported", mAdapter != null);
      r.put("enabled", mAdapter != null && mAdapter.isEnabled());
      r.put("scanning", mScanning);
      r.put("connected", mGatt != null);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        r.put("scanPermission", JsBridge.hasPermission(mContext, android.Manifest.permission.BLUETOOTH_SCAN));
        r.put("connectPermission", JsBridge.hasPermission(mContext, android.Manifest.permission.BLUETOOTH_CONNECT));
        r.put("advertisePermission", JsBridge.hasPermission(mContext, android.Manifest.permission.BLUETOOTH_ADVERTISE));
      } else {
        r.put("scanPermission", true);
        r.put("connectPermission", true);
        r.put("advertisePermission", true);
      }
      mBridge.respond(id, true, r, null);
    } catch (Exception e) {
      mBridge.respond(id, false, null, e.getMessage());
    }
  }

  private final ScanCallback mScanCallback =
      new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
          BluetoothDevice device = result.getDevice();
          if (device != null) {
            emitDeviceFound(device, result.getRssi());
          }
        }

        @Override
        public void onScanFailed(int errorCode) {
          mScanning = false;
          mBridge.emitError("ble.scanFailed", "扫描失败 code=" + errorCode);
        }
      };

  private void handleStartScan(String id, JSONObject params) {
    if (!bleReady(id, mBridge, false)) {
      return;
    }
    JsBridge.PermissionCallback cb =
        new JsBridge.PermissionCallback() {
          @Override
          public void onGranted() {
            startScanInternal();
            mBridge.respond(id, true, null, null);
          }

          @Override
          public void onDenied() {
            mBridge.respond(id, false, null, "用户拒绝蓝牙扫描权限");
          }
        };
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      JsBridge.requestPermissions(
          mBridge.activity(),
          JsBridge.PERM_BLE,
          new String[] {
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT
          },
          cb);
    } else {
      cb.onGranted();
    }
  }

  @SuppressLint("MissingPermission")
  private void startScanInternal() {
    if (mScanning) {
      return;
    }
    try {
      if (mScanner == null) {
        mScanner = mAdapter.getBluetoothLeScanner();
      }
      ScanSettings settings =
          new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
      mScanner.startScan(null, settings, mScanCallback);
      mScanning = true;
    } catch (Exception e) {
      Log.e(LOGTAG, "startScan failed", e);
      mBridge.emitError("ble.scanFailed", e.getMessage());
    }
  }

  @SuppressLint("MissingPermission")
  public void stopScan() {
    if (!mScanning || mScanner == null) {
      return;
    }
    try {
      mScanner.stopScan(mScanCallback);
    } catch (Exception e) {
      Log.e(LOGTAG, "stopScan", e);
    }
    mScanning = false;
  }

  /* ----------------------------------------------------------------------------
   * Central: GATT client
   * -------------------------------------------------------------------------- */

  private void handleConnect(String id, JSONObject params, JsBridge bridge) {
    String address = params.optString("address", "");
    if (address.isEmpty()) {
      bridge.respond(id, false, null, "缺少 address");
      return;
    }
    if (!bleReady(id, bridge, false)) {
      return;
    }
    BluetoothDevice device = mAdapter.getRemoteDevice(address);
    BluetoothGatt gatt = device.connectGatt(mContext, false, mGattCallback);
    if (mGatt != null) {
      mGatt.close();
    }
    mGatt = gatt;
  }

  private final BluetoothGattCallback mGattCallback =
      new BluetoothGattCallback() {
      @Override
      public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        if (newState == BluetoothProfile.STATE_CONNECTED) {
          try {
            JSONObject d = new JSONObject();
            d.put("address", gatt.getDevice().getAddress());
            mBridge.emit("ble.connected", d);
          } catch (Exception e) {
            Log.e(LOGTAG, "onConnected", e);
          }
          gatt.discoverServices();
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
          try {
            JSONObject d = new JSONObject();
            d.put("address", gatt.getDevice().getAddress());
            mBridge.emit("ble.disconnected", d);
          } catch (Exception e) {
            Log.e(LOGTAG, "onDisconnected", e);
          }
          mGatt = null;
        }
      }

      @Override
      public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        JSONObject d = new JSONObject();
        try {
          d.put("address", gatt.getDevice().getAddress());
          JSONArray services = new JSONArray();
          for (BluetoothGattService s : gatt.getServices()) {
            JSONObject so = new JSONObject();
            so.put("uuid", s.getUuid().toString());
            JSONArray chars = new JSONArray();
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
              JSONObject co = new JSONObject();
              co.put("uuid", c.getUuid().toString());
              co.put("properties", propertiesToString(c.getProperties()));
              chars.put(co);
            }
            so.put("characteristics", chars);
            services.put(so);
          }
          d.put("services", services);
        } catch (Exception e) {
          Log.e(LOGTAG, "services", e);
        }
        mBridge.emit("ble.services", d);
      }

      @Override
      public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic ch, int status) {
        JSONObject result = new JSONObject();
        try {
          result.put("address", gatt.getDevice().getAddress());
          result.put("uuid", ch.getUuid().toString());
          result.put("value", Base64.encodeToString(ch.getValue(), Base64.NO_WRAP));
        } catch (Exception e) {
          Log.e(LOGTAG, "read", e);
        }
        mBridge.respond(mCurrentOpId, status == BluetoothGatt.GATT_SUCCESS, result, status == BluetoothGatt.GATT_SUCCESS ? null : "GATT status=" + status);
      }

      @Override
      public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic ch, int status) {
        mBridge.respond(mCurrentOpId, status == BluetoothGatt.GATT_SUCCESS, null, status == BluetoothGatt.GATT_SUCCESS ? null : "GATT status=" + status);
      }

      @Override
      public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic ch) {
        try {
          JSONObject d = new JSONObject();
          d.put("address", gatt.getDevice().getAddress());
          d.put("uuid", ch.getUuid().toString());
          d.put("value", Base64.encodeToString(ch.getValue(), Base64.NO_WRAP));
          mBridge.emit("ble.characteristicChanged", d);
        } catch (Exception e) {
          Log.e(LOGTAG, "changed", e);
        }
      }

      @Override
      public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
        mBridge.respond(mCurrentOpId, status == BluetoothGatt.GATT_SUCCESS, null, status == BluetoothGatt.GATT_SUCCESS ? null : "GATT status=" + status);
      }
    };

  private void handleDisconnect(String id, JsBridge bridge) {
    if (mGatt != null) {
      mGatt.disconnect();
      mGatt.close();
      mGatt = null;
    }
    bridge.respond(id, true, null, null);
  }

  private BluetoothGattCharacteristic findCharacteristic(JSONObject params, boolean needWrite) {
    if (mGatt == null) {
      return null;
    }
    String serviceUuid = params.optString("service", "");
    String charUuid = params.optString("characteristic", "");
    BluetoothGattService s = mGatt.getService(UUID.fromString(serviceUuid));
    if (s == null) {
      return null;
    }
    return s.getCharacteristic(UUID.fromString(charUuid));
  }

  private void handleRead(String id, JSONObject params, JsBridge bridge) {
    BluetoothGattCharacteristic ch = findCharacteristic(params, false);
    if (ch == null) {
      bridge.respond(id, false, null, "未找到特征值（先 connect 并等 ble.services 事件）");
      return;
    }
    mCurrentOpId = id;
    mGatt.readCharacteristic(ch);
  }

  private void handleWrite(String id, JSONObject params, JsBridge bridge) {
    BluetoothGattCharacteristic ch = findCharacteristic(params, true);
    if (ch == null) {
      bridge.respond(id, false, null, "未找到特征值（先 connect 并等 ble.services 事件）");
      return;
    }
    byte[] value = Base64.decode(params.optString("value", ""), Base64.NO_WRAP);
    ch.setValue(value);
    mCurrentOpId = id;
    boolean ok = mGatt.writeCharacteristic(ch);
    if (!ok) {
      bridge.respond(id, false, null, "写入失败");
    }
  }

  private void handleSubscribe(String id, JSONObject params, JsBridge bridge, boolean enable) {
    BluetoothGattCharacteristic ch = findCharacteristic(params, false);
    if (ch == null) {
      bridge.respond(id, false, null, "未找到特征值（先 connect 并等 ble.services 事件）");
      return;
    }
    mGatt.setCharacteristicNotification(ch, enable);
    BluetoothGattDescriptor descriptor = ch.getDescriptor(CCCD);
    if (descriptor == null) {
      bridge.respond(id, false, null, "该特征值无通知描述符");
      return;
    }
    byte[] value =
        enable
            ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
    descriptor.setValue(value);
    mCurrentOpId = id;
    boolean ok = mGatt.writeDescriptor(descriptor);
    if (!ok) {
      bridge.respond(id, false, null, "设置通知失败");
    }
  }

  /* ----------------------------------------------------------------------------
   * Peripheral: advertise + GATT server
   * -------------------------------------------------------------------------- */

  private void handlePeripheralStart(String id, JSONObject params, JsBridge bridge) {
    if (!bleReady(id, bridge, true)) {
      return;
    }
    final String name = params.optString("name", "GeckoBridge");
    final UUID serviceUuid = UUID.fromString(params.optString("serviceUuid", "0000ffe0-0000-1000-8000-00805f9b34fb"));
    final UUID charUuid = UUID.fromString(params.optString("charUuid", "0000ffe1-0000-1000-8000-00805f9b34fb"));
    final byte[] value = Base64.decode(params.optString("value", ""), Base64.NO_WRAP);

    JsBridge.PermissionCallback cb =
        new JsBridge.PermissionCallback() {
          @Override
          public void onGranted() {
            startPeripheralInternal(name, serviceUuid, charUuid, value, bridge, id);
          }

          @Override
          public void onDenied() {
            bridge.respond(id, false, null, "用户拒绝蓝牙广播权限");
          }
        };
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      JsBridge.requestPermissions(
          mBridge.activity(),
          JsBridge.PERM_BLE,
          new String[] {
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_ADVERTISE
          },
          cb);
    } else {
      cb.onGranted();
    }
  }

  @SuppressLint("MissingPermission")
  private void startPeripheralInternal(
      String name, UUID serviceUuid, UUID charUuid, byte[] value, JsBridge bridge, String id) {
    try {
      // GATT server
      mGattServer = mBtManager.openGattServer(mContext, mGattServerCallback);
      if (mGattServer == null) {
        bridge.respond(id, false, null, "打开 GATT Server 失败");
        return;
      }
      mPeripheralValues.put(charUuid, value);
      BluetoothGattCharacteristic characteristic =
          new BluetoothGattCharacteristic(
              charUuid,
              BluetoothGattCharacteristic.PROPERTY_READ
                  | BluetoothGattCharacteristic.PROPERTY_WRITE
                  | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
              BluetoothGattCharacteristic.PERMISSION_READ
                  | BluetoothGattCharacteristic.PERMISSION_WRITE);
      characteristic.addDescriptor(new BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
      BluetoothGattService service = new BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY);
      service.addCharacteristic(characteristic);
      mGattServer.addService(service);

      // Advertising
      mAdvertiser = mAdapter.getBluetoothLeAdvertiser();
      if (mAdvertiser == null) {
        bridge.respond(id, false, null, "设备不支持 BLE 广播");
        return;
      }
      AdvertiseSettings settings =
          new AdvertiseSettings.Builder()
              .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
              .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
              .setConnectable(true)
              .build();
      AdvertiseData data =
          new AdvertiseData.Builder()
              .setIncludeDeviceName(true)
              .addServiceUuid(ParcelUuid.fromString(serviceUuid.toString()))
              .build();
      mAdvertiser.startAdvertising(
          settings,
          data,
          new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
              bridge.respond(id, true, null, null);
            }

            @Override
            public void onStartFailure(int errorCode) {
              bridge.respond(id, false, null, "广播启动失败 code=" + errorCode);
            }
          });
    } catch (Exception e) {
      Log.e(LOGTAG, "peripheral start", e);
      bridge.respond(id, false, null, "peripheral: " + e.getMessage());
    }
  }

  private final BluetoothGattServerCallback mGattServerCallback =
      new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
          try {
            JSONObject d = new JSONObject();
            d.put("address", device.getAddress());
            mBridge.emit(
                newState == BluetoothProfile.STATE_CONNECTED
                    ? "ble.peripheral.connected"
                    : "ble.peripheral.disconnected",
                d);
          } catch (Exception e) {
            Log.e(LOGTAG, "peripheral conn", e);
          }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
          byte[] v = mPeripheralValues.get(characteristic.getUuid());
          if (mGattServer != null) {
            if (v == null) {
              mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH, offset, null);
            } else {
              mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, v);
            }
          }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
          mPeripheralValues.put(characteristic.getUuid(), value);
          if (responseNeeded && mGattServer != null) {
            mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
          }
          try {
            JSONObject d = new JSONObject();
            d.put("address", device.getAddress());
            d.put("uuid", characteristic.getUuid().toString());
            d.put("value", Base64.encodeToString(value, Base64.NO_WRAP));
            mBridge.emit("ble.peripheral.write", d);
          } catch (Exception e) {
            Log.e(LOGTAG, "peripheral write", e);
          }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
          boolean subscribed = java.util.Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
          mPeripheralSubscribed.put(device, subscribed);
          if (responseNeeded && mGattServer != null) {
            mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
          }
          try {
            JSONObject d = new JSONObject();
            d.put("address", device.getAddress());
            d.put("uuid", descriptor.getCharacteristic().getUuid().toString());
            d.put("subscribed", subscribed);
            mBridge.emit("ble.peripheral.subscribed", d);
          } catch (Exception e) {
            Log.e(LOGTAG, "peripheral descriptor", e);
          }
        }
      };

  private void handlePeripheralSetValue(String id, JSONObject params, JsBridge bridge) {
    String uuid = params.optString("charUuid", "");
    byte[] value = Base64.decode(params.optString("value", ""), Base64.NO_WRAP);
    mPeripheralValues.put(UUID.fromString(uuid), value);
    // Notify subscribed clients
    if (mGattServer != null && mPeripheralSubscribed.size() > 0) {
      for (BluetoothGattService s : mGattServer.getServices()) {
        BluetoothGattCharacteristic ch = s.getCharacteristic(UUID.fromString(uuid));
        if (ch != null) {
          ch.setValue(value);
          for (Map.Entry<BluetoothDevice, Boolean> e : mPeripheralSubscribed.entrySet()) {
            if (e.getValue()) {
              mGattServer.notifyCharacteristicChanged(e.getKey(), ch, false);
            }
          }
          break;
        }
      }
    }
    bridge.respond(id, true, null, null);
  }

  @SuppressLint("MissingPermission")
  private void stopPeripheral() {
    if (mAdvertiser != null) {
      try {
        mAdvertiser.stopAdvertising(mAdvertiseCallbackRef);
      } catch (Exception e) {
        Log.e(LOGTAG, "stopAdvertising", e);
      }
      mAdvertiser = null;
    }
    if (mGattServer != null) {
      mGattServer.close();
      mGattServer = null;
    }
    mPeripheralValues.clear();
    mPeripheralSubscribed.clear();
  }

  private final AdvertiseCallback mAdvertiseCallbackRef =
      new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {}

        @Override
        public void onStartFailure(int errorCode) {}
      };

  /* ----------------------------------------------------------------------------
   * Lifecycle
   * -------------------------------------------------------------------------- */

  public void onDestroy() {
    stopScan();
    if (mGatt != null) {
      mGatt.disconnect();
      mGatt.close();
      mGatt = null;
    }
    stopPeripheral();
  }

  private String propertiesToString(int props) {
    List<String> p = new ArrayList<>();
    if ((props & BluetoothGattCharacteristic.PROPERTY_READ) != 0) p.add("read");
    if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) p.add("write");
    if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) p.add("writeNoResponse");
    if ((props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) p.add("notify");
    if ((props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) p.add("indicate");
    return String.join(",", p);
  }
}
