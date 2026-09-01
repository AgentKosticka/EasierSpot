package android.net.wifi.sharedconnectivity.app;

import android.os.Bundle;

/** Compile-only signature stub for the API 34+ system API. */
public final class HotspotNetworkConnectionStatus {
    public static final int CONNECTION_STATUS_UNKNOWN = 0;
    public static final int CONNECTION_STATUS_ENABLING_HOTSPOT = 1;
    public static final int CONNECTION_STATUS_UNKNOWN_ERROR = 2;
    public static final int CONNECTION_STATUS_PROVISIONING_FAILED = 3;
    public static final int CONNECTION_STATUS_TETHERING_TIMEOUT = 4;
    public static final int CONNECTION_STATUS_TETHERING_UNSUPPORTED = 5;
    public static final int CONNECTION_STATUS_NO_CELL_DATA = 6;
    public static final int CONNECTION_STATUS_ENABLING_HOTSPOT_FAILED = 7;
    public static final int CONNECTION_STATUS_ENABLING_HOTSPOT_TIMEOUT = 8;
    public static final int CONNECTION_STATUS_CONNECT_TO_HOTSPOT_FAILED = 9;

    public static final class Builder {
        public Builder() {}
        public Builder setStatus(int status) { return this; }
        public Builder setHotspotNetwork(HotspotNetwork network) { return this; }
        public Builder setExtras(Bundle extras) { return this; }
        public HotspotNetworkConnectionStatus build() { return null; }
    }
}
