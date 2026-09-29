package com.cuktech.mobile;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Blocking BLE transport for Python worker threads. Android callbacks never invoke Python. */
public final class GattBridge implements AutoCloseable {
    private static final String TAG = "CuktechGatt";
    private static final int MAX_TIMEOUT_MS = 120_000;
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Context context;
    private final BluetoothAdapter adapter;
    private final Object stateLock = new Object();
    private final NotificationBuffer notifications = new NotificationBuffer(1024);
    private final GattOperationGate operations = new GattOperationGate();
    private Session current;
    private ScanTask activeScan;
    private boolean closed;

    public GattBridge(Context context) {
        if (context == null) throw new IllegalArgumentException("An Android Context is required");
        Context application = context.getApplicationContext();
        this.context = application != null ? application : context;
        BluetoothManager manager = (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
    }

    public String scan(String mac, int timeoutMs) {
        ScanTask task = runScan(validAddress(mac), timeoutMs);
        return task.result == null ? "" : task.result;
    }

    /**
     * Scan without an address filter and report how many distinct BLE devices the radio can
     * currently see. A positive count proves scanning works; zero is inconclusive because
     * there may simply be no nearby advertisers.
     */
    public int probe(int timeoutMs) {
        // Android suspends unfiltered scans with the screen off. Such a result says
        // nothing about radio health and must never prompt the user to reset Bluetooth.
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power == null || !power.isInteractive()) return -1;
        int count = runScan(null, timeoutMs).count();
        return power.isInteractive() ? count : -1;
    }

    /** Null address counts every advertiser instead of waiting for one specific device. */
    private ScanTask runScan(String address, int timeoutMs) {
        // A zero timeout would report "no devices seen" without ever listening.
        validateTimeout(timeoutMs, false);
        checkBluetooth(true);
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) throw new IllegalStateException("Bluetooth LE scanning is unavailable; enable Bluetooth");
        ScanTask task = new ScanTask(scanner, address);
        ScanTask previous;
        synchronized (stateLock) {
            requireOpen();
            previous = activeScan;
            activeScan = task;
        }
        if (previous != null) previous.stop(true);
        try {
            task.start();
            if (address == null) task.done.await(timeoutMs, TimeUnit.MILLISECONDS);
            else if (!task.done.await(timeoutMs, TimeUnit.MILLISECONDS)) return task;
            if (task.failure != null) throw task.failure;
            return task;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Bluetooth scan interrupted", interrupted);
        } finally {
            task.stop(false);
            synchronized (stateLock) {
                if (activeScan == task) activeScan = null;
            }
        }
    }

    public void connect(String mac, int timeoutMs) {
        long started = System.nanoTime();
        String address = validAddress(mac);
        validateTimeout(timeoutMs, false);
        checkBluetooth(false);
        long deadline = deadline(timeoutMs);
        Session session;
        Session previous;
        ScanTask scan;
        synchronized (stateLock) {
            requireOpen();
            previous = clearSessionLocked(new IllegalStateException("Bluetooth connection replaced"));
            scan = activeScan;
            activeScan = null;
            session = new Session(operations.reset(), notifications.reset());
            current = session;
        }
        if (scan != null) scan.stop(true);
        closeGatt(previous);
        try (GattOperationGate.Lease ignored = operations.acquire(session.generation, remaining(deadline))) {
            Pending connected = new Pending("connect", null);
            synchronized (stateLock) {
                requireCurrent(session, false);
                session.pending = connected;
                BluetoothDevice device = adapter.getRemoteDevice(address);
                session.gatt = device.connectGatt(context, false, callback(session), BluetoothDevice.TRANSPORT_LE);
                if (session.gatt == null) throw new IllegalStateException("Android could not create a Bluetooth GATT client");
            }
            await(session, connected, remaining(deadline), true);
            run(session, "discover services", null, remaining(deadline), true, BluetoothGatt::discoverServices);
            // Some peripherals reject MTU exchange or omit its callback. Services are already usable.
            int mtuTimeout = Math.min(2000, remaining(deadline));
            try {
                run(session, "MTU", null, mtuTimeout, false, gatt -> gatt.requestMtu(247));
            } catch (IllegalStateException bestEffortMtuFailure) {
                synchronized (stateLock) { requireCurrent(session, false); }
                if (Thread.currentThread().isInterrupted()) throw bestEffortMtuFailure;
            }
            synchronized (stateLock) {
                requireCurrent(session, false);
                // Request a shorter connection interval for authentication and
                // live V/I notifications. This is a hint; some stacks/peripherals
                // reject it, which must not fail an otherwise usable connection.
                try {
                    boolean accepted = session.gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                    Log.i(TAG, "High connection priority accepted=" + accepted);
                } catch (RuntimeException unsupported) {
                    Log.w(TAG, "Connection priority hint unavailable", unsupported);
                }
                session.ready = true;
            }
            Log.i(TAG, "GATT ready in " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    + " ms; MTU=" + session.mtu);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            IllegalStateException failure = new IllegalStateException("Bluetooth connection interrupted", interrupted);
            endSession(session, failure);
            throw failure;
        } catch (TimeoutException timeout) {
            IllegalStateException failure = new IllegalStateException("Bluetooth connection timed out", timeout);
            endSession(session, failure);
            throw failure;
        } catch (RuntimeException failure) {
            endSession(session, failure);
            throw failure;
        }
    }

    public boolean isConnected() {
        synchronized (stateLock) { return current != null && current.ready && !closed; }
    }

    public int getMtu() {
        synchronized (stateLock) { return current == null ? 23 : current.mtu; }
    }

    public byte[] read(String uuid, int timeoutMs) {
        UUID id = validUuid(uuid);
        return perform(timeoutMs, (session, remaining) -> {
            BluetoothGattCharacteristic characteristic = characteristic(session, id);
            if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
                throw new IllegalArgumentException("Characteristic is not readable: " + id);
            }
            return run(session, "read", id, remaining, true, gatt -> gatt.readCharacteristic(characteristic));
        });
    }

    public void write(String uuid, byte[] value, boolean response, int timeoutMs) {
        UUID id = validUuid(uuid);
        if (value == null) throw new IllegalArgumentException("Bluetooth write value cannot be null");
        byte[] bytes = value.clone();
        if (bytes.length > 512) throw new IllegalArgumentException("Bluetooth characteristic writes cannot exceed 512 bytes");
        perform(timeoutMs, (session, remaining) -> {
            BluetoothGattCharacteristic characteristic = characteristic(session, id);
            int property = response ? BluetoothGattCharacteristic.PROPERTY_WRITE
                    : BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;
            if ((characteristic.getProperties() & property) == 0) {
                throw new IllegalArgumentException("Characteristic does not support the requested write type: " + id);
            }
            if (!response && bytes.length > session.mtu - 3) {
                throw new IllegalArgumentException("Write without response exceeds the negotiated MTU payload (" + (session.mtu - 3) + ")");
            }
            int writeType = response ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
            // Android still reports onCharacteristicWrite for WRITE_TYPE_NO_RESPONSE. Wait for
            // that local completion before submitting another ATT operation.
            return run(session, "write", id, remaining, true, gatt -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    return gatt.writeCharacteristic(characteristic, bytes, writeType) == BluetoothStatusCodes.SUCCESS;
                }
                characteristic.setWriteType(writeType);
                return characteristic.setValue(bytes) && gatt.writeCharacteristic(characteristic);
            });
        });
    }

    public void notify(String uuid, boolean enable, int timeoutMs) {
        UUID id = validUuid(uuid);
        perform(timeoutMs, (session, remaining) -> {
            BluetoothGattCharacteristic characteristic = characteristic(session, id);
            int properties = characteristic.getProperties();
            byte[] flags;
            if (!enable) flags = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
            else if ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                flags = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            } else if ((properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                flags = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
            } else throw new IllegalArgumentException("Characteristic does not support notifications or indications: " + id);
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
            if (descriptor == null) throw new IllegalArgumentException("Characteristic has no CCCD: " + id);
            boolean previouslyEnabled;
            synchronized (stateLock) { previouslyEnabled = session.enabledNotifications.contains(id); }
            try {
                run(session, "CCCD", id, remaining, true, gatt -> {
                    if (!gatt.setCharacteristicNotification(characteristic, enable)) return false;
                    if (Build.VERSION.SDK_INT >= 33) {
                        return gatt.writeDescriptor(descriptor, flags) == BluetoothStatusCodes.SUCCESS;
                    }
                    return descriptor.setValue(flags) && gatt.writeDescriptor(descriptor);
                });
                synchronized (stateLock) {
                    requireCurrent(session, true);
                    if (enable) session.enabledNotifications.add(id);
                    else session.enabledNotifications.remove(id);
                }
            } catch (RuntimeException failure) {
                synchronized (stateLock) {
                    if (current == session && session.gatt != null) {
                        try { session.gatt.setCharacteristicNotification(characteristic, previouslyEnabled); }
                        catch (RuntimeException ignored) { /* A revoked permission cannot be repaired here. */ }
                    }
                }
                throw failure;
            }
            return null;
        });
    }

    public String poll(int timeoutMs) {
        validateTimeout(timeoutMs, true);
        long generation;
        synchronized (stateLock) {
            if (closed || current == null) return "";
            generation = current.notificationGeneration;
        }
        try {
            String notification = notifications.poll(generation, timeoutMs);
            return notification == null ? "" : notification;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Bluetooth notification poll interrupted", interrupted);
        }
    }

    public void disconnect() { shutdown(false); }

    /**
     * Deeper recovery for a stack that answers nothing: release every app-owned GATT handle and
     * scan, then cancel any classic discovery that would otherwise suppress LE scan results.
     * An app targeting API 33+ cannot toggle the Bluetooth radio itself, so the caller must fall
     * back to asking the user to power-cycle Bluetooth when this is not enough.
     */
    public void reset() {
        shutdown(false);
        try { adapter.cancelDiscovery(); }
        catch (RuntimeException ignored) { /* Adapter off or permission revoked. */ }
    }

    @Override public void close() { shutdown(true); }

    private void shutdown(boolean permanently) {
        Session previous;
        ScanTask scan;
        synchronized (stateLock) {
            if (permanently) closed = true;
            previous = clearSessionLocked(new IllegalStateException("Bluetooth disconnected"));
            scan = activeScan;
            activeScan = null;
        }
        if (scan != null) scan.stop(true);
        closeGatt(previous);
    }

    private byte[] perform(int timeoutMs, Operation operation) {
        validateTimeout(timeoutMs, false);
        checkBluetooth(false);
        long deadline = deadline(timeoutMs);
        Session session;
        synchronized (stateLock) {
            session = current;
            requireCurrent(session, true);
        }
        try (GattOperationGate.Lease ignored = operations.acquire(session.generation, remaining(deadline))) {
            synchronized (stateLock) { requireCurrent(session, true); }
            return operation.execute(session, remaining(deadline));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a GATT operation", interrupted);
        } catch (TimeoutException timeout) {
            // A timeout before acquiring the slot must not abort somebody else's operation.
            throw new IllegalStateException("Timed out waiting for the GATT operation slot", timeout);
        }
    }

    private byte[] run(Session session, String kind, UUID uuid, int timeoutMs,
                       boolean fatalTimeout, GattRequest request) {
        Pending pending = new Pending(kind, uuid);
        synchronized (stateLock) {
            requireCurrent(session, false);
            session.pending = pending;
            try {
                if (!request.start(session.gatt)) throw new IllegalStateException("Android rejected GATT " + kind + " request");
            } catch (RuntimeException failure) {
                session.pending = null;
                throw failure;
            }
        }
        return await(session, pending, timeoutMs, fatalTimeout);
    }

    private byte[] await(Session session, Pending pending, int timeoutMs, boolean fatalTimeout) {
        try {
            if (!pending.done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                IllegalStateException failure = new IllegalStateException("GATT " + pending.kind + " timed out",
                        new TimeoutException(pending.kind));
                // A late callback carries no request ID, so the entire connection must be
                // invalidated after an ordinary operation timeout before another can begin.
                if (fatalTimeout) endSession(session, failure);
                throw failure;
            }
            if (pending.failure != null) throw pending.failure;
            return pending.value == null ? new byte[0] : pending.value;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            IllegalStateException failure = new IllegalStateException("GATT " + pending.kind + " interrupted", interrupted);
            endSession(session, failure);
            throw failure;
        } finally {
            synchronized (stateLock) {
                if (session.pending == pending) session.pending = null;
            }
        }
    }

    private BluetoothGattCharacteristic characteristic(Session session, UUID uuid) {
        synchronized (stateLock) {
            requireCurrent(session, true);
            BluetoothGattCharacteristic found = null;
            for (BluetoothGattService service : session.gatt.getServices()) {
                for (BluetoothGattCharacteristic candidate : service.getCharacteristics()) {
                    if (uuid.equals(candidate.getUuid())) {
                        if (found != null) throw new IllegalArgumentException("Characteristic UUID is ambiguous across services: " + uuid);
                        found = candidate;
                    }
                }
            }
            if (found == null) throw new IllegalArgumentException("Bluetooth characteristic not found: " + uuid);
            return found;
        }
    }

    private BluetoothGattCallback callback(final Session session) {
        return new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                synchronized (stateLock) {
                    if (!acceptCallback(session, gatt)) return;
                    if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                        completeLocked(session, "connect", null, status, null);
                        return;
                    }
                    if (status == BluetoothGatt.GATT_SUCCESS && newState != BluetoothProfile.STATE_DISCONNECTED) return;
                }
                endSession(session, new IllegalStateException("Bluetooth disconnected (GATT status " + status + ")"));
                Log.i(TAG, "Connection ended: status=" + status + ", state=" + newState);
            }

            @Override public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                complete(session, gatt, "discover services", null, status, null);
            }

            @Override public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
                synchronized (stateLock) {
                    if (!acceptCallback(session, gatt)) return;
                    if (status == BluetoothGatt.GATT_SUCCESS && mtu >= 23 && mtu <= 517) session.mtu = mtu;
                    completeLocked(session, "MTU", null, status, null);
                }
            }

            @Override public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                if (Build.VERSION.SDK_INT < 33) {
                    complete(session, gatt, "read", characteristic.getUuid(), status, characteristic.getValue());
                }
            }

            @Override public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic,
                                                       byte[] value, int status) {
                complete(session, gatt, "read", characteristic.getUuid(), status, value);
            }

            @Override public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                complete(session, gatt, "write", characteristic.getUuid(), status, null);
            }

            @Override public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                if (CCCD.equals(descriptor.getUuid())) {
                    complete(session, gatt, "CCCD", descriptor.getCharacteristic().getUuid(), status, null);
                }
            }

            @Override public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                if (Build.VERSION.SDK_INT < 33) notification(session, gatt, characteristic.getUuid(), characteristic.getValue());
            }

            @Override public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
                notification(session, gatt, characteristic.getUuid(), value);
            }
        };
    }

    private void complete(Session session, BluetoothGatt gatt, String kind, UUID uuid, int status, byte[] value) {
        synchronized (stateLock) {
            if (acceptCallback(session, gatt)) completeLocked(session, kind, uuid, status, value);
        }
    }

    private void completeLocked(Session session, String kind, UUID uuid, int status, byte[] value) {
        Pending pending = session.pending;
        if (pending == null || !pending.kind.equals(kind) ||
                (uuid == null ? pending.uuid != null : !uuid.equals(pending.uuid)) || pending.done.getCount() == 0) return;
        if (status != BluetoothGatt.GATT_SUCCESS) {
            pending.failure = new IllegalStateException("GATT " + kind + " failed with status " + status);
        } else pending.value = value == null ? null : value.clone();
        pending.done.countDown();
    }

    private void notification(Session session, BluetoothGatt gatt, UUID uuid, byte[] value) {
        synchronized (stateLock) {
            if (!acceptCallback(session, gatt) || value == null) return;
            notifications.offer(session.notificationGeneration, json("uuid", uuid.toString(), "data", BleValueCodec.hex(value)));
        }
    }

    private boolean acceptCallback(Session session, BluetoothGatt gatt) {
        return !closed && current == session && session.gatt == gatt;
    }

    private void endSession(Session session, RuntimeException failure) {
        Session previous;
        synchronized (stateLock) {
            if (current != session) return;
            previous = clearSessionLocked(failure);
        }
        closeGatt(previous);
    }

    private Session clearSessionLocked(RuntimeException failure) {
        Session previous = current;
        current = null;
        operations.reset();
        notifications.reset();
        if (previous != null) {
            previous.ready = false;
            previous.failure = failure;
            if (previous.pending != null) {
                previous.pending.failure = failure;
                previous.pending.done.countDown();
            }
        }
        return previous;
    }

    private static void closeGatt(Session session) {
        if (session == null || session.gatt == null) return;
        try { session.gatt.disconnect(); }
        catch (RuntimeException ignored) { /* Bluetooth may be off or permission may have been revoked. */ }
        finally {
            try { session.gatt.close(); }
            catch (RuntimeException ignored) { /* Always attempt both disconnect and close. */ }
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Bluetooth transport is closed");
    }

    private void requireCurrent(Session session, boolean ready) {
        requireOpen();
        if (session == null || current != session) {
            if (session != null && session.failure != null) throw session.failure;
            throw new IllegalStateException("Bluetooth is disconnected");
        }
        if (ready && !session.ready) throw new IllegalStateException("Bluetooth services are not ready");
    }

    private void checkBluetooth(boolean scanning) {
        synchronized (stateLock) { requireOpen(); }
        if (adapter == null) throw new IllegalStateException("This device does not support Bluetooth");
        if (Build.VERSION.SDK_INT >= 31) {
            requirePermission(Manifest.permission.BLUETOOTH_CONNECT, "Nearby devices (Bluetooth connect)");
            if (scanning) requirePermission(Manifest.permission.BLUETOOTH_SCAN, "Nearby devices (Bluetooth scan)");
        } else if (scanning) {
            requirePermission(Manifest.permission.ACCESS_FINE_LOCATION, "Location for Bluetooth scanning");
        }
        if (!adapter.isEnabled()) throw new IllegalStateException("Bluetooth is disabled; enable Bluetooth and retry");
    }

    private void requirePermission(String permission, String label) {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(label + " permission is required; grant it in the Android app");
        }
    }

    private static String validAddress(String mac) {
        String address = mac == null ? "" : mac.trim().toUpperCase(Locale.ROOT);
        if (!BluetoothAdapter.checkBluetoothAddress(address)) throw new IllegalArgumentException("Invalid Bluetooth MAC address: " + address);
        return address;
    }

    private static UUID validUuid(String value) {
        if (value == null) throw new IllegalArgumentException("A Bluetooth characteristic UUID is required");
        String uuid = value.trim().toLowerCase(Locale.ROOT);
        if (uuid.matches("[0-9a-f]{4}")) uuid = "0000" + uuid + "-0000-1000-8000-00805f9b34fb";
        else if (uuid.matches("[0-9a-f]{8}")) uuid += "-0000-1000-8000-00805f9b34fb";
        if (!uuid.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Invalid Bluetooth characteristic UUID: " + value);
        }
        return UUID.fromString(uuid);
    }

    private static void validateTimeout(int timeoutMs, boolean allowZero) {
        if (timeoutMs < (allowZero ? 0 : 1) || timeoutMs > MAX_TIMEOUT_MS) {
            throw new IllegalArgumentException("Bluetooth timeout must be " + (allowZero ? "0" : "1") + ".." + MAX_TIMEOUT_MS + " milliseconds");
        }
    }

    private static long deadline(int timeoutMs) { return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs); }

    private static int remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new IllegalStateException("Bluetooth operation timed out", new TimeoutException());
        return (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos));
    }

    private static String json(String firstKey, String firstValue, String secondKey, String secondValue) {
        try { return new JSONObject().put(firstKey, firstValue).put(secondKey, secondValue).toString(); }
        catch (JSONException impossible) { throw new IllegalStateException("Cannot encode Bluetooth result", impossible); }
    }

    private interface GattRequest { boolean start(BluetoothGatt gatt); }
    private interface Operation { byte[] execute(Session session, int remainingMs); }

    private static final class Session {
        final long generation;
        final long notificationGeneration;
        final Set<UUID> enabledNotifications = new HashSet<>();
        BluetoothGatt gatt;
        Pending pending;
        RuntimeException failure;
        boolean ready;
        volatile int mtu = 23;
        Session(long generation, long notificationGeneration) {
            this.generation = generation;
            this.notificationGeneration = notificationGeneration;
        }
    }

    private static final class Pending {
        final String kind;
        final UUID uuid;
        final CountDownLatch done = new CountDownLatch(1);
        volatile RuntimeException failure;
        volatile byte[] value;
        Pending(String kind, UUID uuid) { this.kind = kind; this.uuid = uuid; }
    }

    private static final class ScanTask {
        final BluetoothLeScanner scanner;
        final String address;
        final Set<String> addresses = new HashSet<>();
        final CountDownLatch done = new CountDownLatch(1);
        volatile String result;
        volatile RuntimeException failure;
        boolean finished;
        boolean stopped;
        boolean attempted;
        final ScanCallback callback = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult scanResult) { accept(scanResult); }
            @Override public void onBatchScanResults(List<ScanResult> results) {
                for (ScanResult scanResult : results) accept(scanResult);
            }
            @Override public void onScanFailed(int errorCode) {
                synchronized (ScanTask.this) {
                    if (finished) return;
                    failure = new IllegalStateException("Android Bluetooth scan failed with code " + errorCode);
                    finished = true;
                    done.countDown();
                }
            }
        };

        ScanTask(BluetoothLeScanner scanner, String address) { this.scanner = scanner; this.address = address; }

        synchronized int count() { return addresses.size(); }

        synchronized void start() {
            if (stopped) throw new IllegalStateException("Bluetooth scan cancelled");
            attempted = true;
            List<ScanFilter> filters = address == null
                    ? Collections.emptyList()
                    : Collections.singletonList(new ScanFilter.Builder().setDeviceAddress(address).build());
            scanner.startScan(filters, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback);
        }

        synchronized void accept(ScanResult scanResult) {
            if (finished || stopped || scanResult == null) return;
            try {
                BluetoothDevice device = scanResult.getDevice();
                if (device == null) return;
                if (address == null) {
                    addresses.add(device.getAddress());
                    return;
                }
                if (!address.equalsIgnoreCase(device.getAddress())) return;
                String name = scanResult.getScanRecord() == null ? null : scanResult.getScanRecord().getDeviceName();
                if (name == null) name = device.getName();
                result = json("address", address, "name", name == null ? "" : name);
            } catch (RuntimeException error) { failure = error; }
            finished = true;
            done.countDown();
        }

        synchronized void stop(boolean cancelled) {
            if (stopped) return;
            stopped = true;
            if (cancelled && !finished) {
                failure = new IllegalStateException("Bluetooth scan cancelled");
                finished = true;
                done.countDown();
            }
            if (attempted) {
                try { scanner.stopScan(callback); }
                catch (RuntimeException ignored) { /* Permission revocation or adapter shutdown. */ }
            }
        }
    }
}
