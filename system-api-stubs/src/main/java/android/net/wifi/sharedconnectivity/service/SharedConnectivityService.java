package android.net.wifi.sharedconnectivity.service;

import android.app.Service;
import android.content.Intent;
import android.net.wifi.sharedconnectivity.app.HotspotNetwork;
import android.net.wifi.sharedconnectivity.app.HotspotNetworkConnectionStatus;
import android.net.wifi.sharedconnectivity.app.KnownNetwork;
import android.net.wifi.sharedconnectivity.app.SharedConnectivitySettingsState;
import android.os.IBinder;
import java.util.List;

/** Compile-only signature stub. The real API 34+ framework class is used at runtime. */
public abstract class SharedConnectivityService extends Service {
    @Override
    public IBinder onBind(Intent intent) { return null; }

    public final void setHotspotNetworks(List<HotspotNetwork> networks) {}
    public final void setSettingsState(SharedConnectivitySettingsState settingsState) {}
    public final void updateHotspotNetworkConnectionStatus(HotspotNetworkConnectionStatus status) {}

    public abstract void onConnectHotspotNetwork(HotspotNetwork network);
    public abstract void onDisconnectHotspotNetwork(HotspotNetwork network);
    public abstract void onConnectKnownNetwork(KnownNetwork network);
    public abstract void onForgetKnownNetwork(KnownNetwork network);
}
