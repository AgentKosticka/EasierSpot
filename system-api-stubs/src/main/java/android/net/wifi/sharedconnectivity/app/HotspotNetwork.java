package android.net.wifi.sharedconnectivity.app;

import android.os.Bundle;

/** Compile-only signature stub for the API 34+ system API. */
public final class HotspotNetwork {
    public static final int NETWORK_TYPE_UNKNOWN = 0;
    public static final int NETWORK_TYPE_CELLULAR = 1;
    public static final int NETWORK_TYPE_WIFI = 2;
    public static final int NETWORK_TYPE_ETHERNET = 3;

    public long getDeviceId() { return 0L; }
    public Bundle getExtras() { return Bundle.EMPTY; }

    public static final class Builder {
        public Builder() {}
        public Builder setDeviceId(long deviceId) { return this; }
        public Builder setNetworkProviderInfo(NetworkProviderInfo info) { return this; }
        public Builder setHostNetworkType(int networkType) { return this; }
        public Builder setNetworkName(String networkName) { return this; }
        public Builder setHotspotSsid(String hotspotSsid) { return this; }
        public Builder setHotspotBssid(String hotspotBssid) { return this; }
        public Builder addHotspotSecurityType(int securityType) { return this; }
        public Builder setExtras(Bundle extras) { return this; }
        public HotspotNetwork build() { return null; }
    }
}
