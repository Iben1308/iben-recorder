package ua.iben.recorder;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Owns only the requested Bluetooth route. Never substitutes the built-in microphone. */
final class BluetoothRoute implements AutoCloseable {
    static final String LEGACY="bt:sco";
    private final Context context;
    private final AudioManager manager;
    private final String key;
    private final BooleanSupplier stopping;
    private final Runnable lost;
    private final Object signal=new Object();
    private volatile boolean connected,armed,closed;
    private boolean scoRequested,changedMode;
    private int oldMode;
    private AudioDeviceInfo selected;
    private BroadcastReceiver receiver;
    private Runnable modernCleanup=() -> { };
    BluetoothRoute(Context context,String key,BooleanSupplier stopping,Runnable lost) {
        this.context=context;this.key=key;this.stopping=stopping;this.lost=lost;
        manager=context.getSystemService(AudioManager.class);
    }
    static boolean permitted(Context context) {
        return Build.VERSION.SDK_INT<31 || Platform.granted(context,Manifest.permission.BLUETOOTH_CONNECT);
    }
    static boolean type(int type) {
        return type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO || Build.VERSION.SDK_INT>=31 && type==AudioDeviceInfo.TYPE_BLE_HEADSET;
    }
    @android.annotation.TargetApi(31)
    static String key(AudioDeviceInfo device) {
        return "bt:"+device.getType()+":"+(device.getAddress().isEmpty() ? device.getProductName() : device.getAddress());
    }
    static void choices(Context context,List<AudioInputs.Choice> out) {
        if(Build.VERSION.SDK_INT>=31 && permitted(context)) {
            try {
                for(AudioDeviceInfo device:context.getSystemService(AudioManager.class).getAvailableCommunicationDevices())
                    if(type(device.getType()))out.add(new AudioInputs.Choice(key(device),I18n.s("input_bluetooth")+" · "+device.getProductName()));
            } catch(SecurityException ignored) { }
        }
        boolean found=false;for(AudioInputs.Choice choice:out)found|=AudioInputPolicy.bluetooth(choice.key);
        if(!found)out.add(new AudioInputs.Choice(LEGACY,I18n.s("input_bluetooth_active")));
    }
    void start() throws Exception {
        if(!permitted(context))throw new RecordingFailure(RecordingFailure.Reason.PERMISSION);
        oldMode=manager.getMode();
        if(oldMode!=AudioManager.MODE_NORMAL)throw new RecordingFailure(RecordingFailure.Reason.INPUT_BUSY);
        if(Build.VERSION.SDK_INT>=31) {
            for(AudioDeviceInfo device:manager.getAvailableCommunicationDevices())
                if(type(device.getType()) && (key(device).equals(key) || LEGACY.equals(key) && device.getType()==AudioDeviceInfo.TYPE_BLUETOOTH_SCO)) {selected=device;break;}
            if(selected==null)throw new RecordingFailure(RecordingFailure.Reason.INPUT_UNAVAILABLE);
        } else {
            BluetoothManager bluetooth=context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter=bluetooth==null ? null : bluetooth.getAdapter();
            try {
                if(adapter==null || !adapter.isEnabled() || adapter.getProfileConnectionState(BluetoothProfile.HEADSET)!=BluetoothAdapter.STATE_CONNECTED)
                    throw new RecordingFailure(RecordingFailure.Reason.INPUT_UNAVAILABLE);
            } catch(SecurityException denied) {
                throw new RecordingFailure(RecordingFailure.Reason.PERMISSION,denied);
            }
            if(!manager.isBluetoothScoAvailableOffCall())throw new RecordingFailure(RecordingFailure.Reason.CONFIGURATION);
        }
        manager.setMode(AudioManager.MODE_IN_COMMUNICATION);changedMode=true;
        if(Build.VERSION.SDK_INT>=31)modernCleanup=Api31.start(this);
        else {
            receiver=new BroadcastReceiver() {
                @Override public void onReceive(Context c,Intent intent) {
                    state(intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE,-1)==AudioManager.SCO_AUDIO_STATE_CONNECTED);
                }
            };
            Intent sticky=context.registerReceiver(receiver,new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
            if(sticky!=null)connected=sticky.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE,-1)==AudioManager.SCO_AUDIO_STATE_CONNECTED;
            scoRequested=true;manager.startBluetoothSco();
        }
        long end=SystemClock.elapsedRealtime()+15000;
        synchronized(signal) {
            while(!connected && !stopping.getAsBoolean() && SystemClock.elapsedRealtime()<end)signal.wait(200);
        }
        if(stopping.getAsBoolean())throw new InterruptedIOException();
        if(!connected)throw new RecordingFailure(RecordingFailure.Reason.INPUT_UNAVAILABLE);
        armed=true;
        if(Build.VERSION.SDK_INT<31)manager.setBluetoothScoOn(true);
    }
    private void state(boolean value) {
        if(closed)return;
        connected=value;synchronized(signal){signal.notifyAll();}
        if(armed && !value)lost.run();
    }
    boolean accepts(AudioDeviceInfo input) {
        if(!connected || input==null || !input.isSource() || !type(input.getType()))return false;
        if(Build.VERSION.SDK_INT<31)return input.getType()==AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
        return selected!=null && input.getType()==selected.getType()
                && (selected.getAddress().isEmpty() ? input.getProductName().toString().equals(selected.getProductName().toString())
                : input.getAddress().equals(selected.getAddress()));
    }
    @Override public void close() {
        closed=true;armed=false;
        if(receiver!=null) {try{context.unregisterReceiver(receiver);}catch(RuntimeException ignored){}receiver=null;}
        try{modernCleanup.run();}catch(RuntimeException ignored) { }
        if(scoRequested)try{manager.stopBluetoothSco();manager.setBluetoothScoOn(false);}catch(RuntimeException ignored) { }
        if(changedMode)try{if(manager.getMode()==AudioManager.MODE_IN_COMMUNICATION)manager.setMode(oldMode);}catch(RuntimeException ignored) { }
    }
    @android.annotation.TargetApi(31)
    private static final class Api31 {
        static Runnable start(BluetoothRoute route) throws RecordingFailure {
            Handler main=new Handler(Looper.getMainLooper());
            AudioManager.OnCommunicationDeviceChangedListener listener=device -> route.state(device!=null && device.getId()==route.selected.getId());
            route.manager.addOnCommunicationDeviceChangedListener(command -> main.post(command),listener);
            // Install cleanup before requesting a route, so a rejected/throwing request cannot leak the listener.
            route.modernCleanup=() -> {
                route.manager.removeOnCommunicationDeviceChangedListener(listener);
                route.manager.clearCommunicationDevice();
            };
            if(!route.manager.setCommunicationDevice(route.selected))throw new RecordingFailure(RecordingFailure.Reason.INPUT_UNAVAILABLE);
            AudioDeviceInfo current=route.manager.getCommunicationDevice();
            route.state(current!=null && current.getId()==route.selected.getId());
            return route.modernCleanup;
        }
    }
}
