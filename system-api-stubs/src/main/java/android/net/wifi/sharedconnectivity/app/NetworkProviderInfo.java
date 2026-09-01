package android.net.wifi.sharedconnectivity.app;

/** Compile-only signature stub for the API 34+ system API. */
public final class NetworkProviderInfo {
    public static final int DEVICE_TYPE_UNKNOWN = 0;
    public static final int DEVICE_TYPE_PHONE = 1;

    public static final class Builder {
        public Builder(String deviceName, String modelName) {}
        public Builder setDeviceType(int deviceType) { return this; }
        public Builder setDeviceName(String deviceName) { return this; }
        public Builder setModelName(String modelName) { return this; }
        public Builder setBatteryPercentage(int batteryPercentage) { return this; }
        public Builder setConnectionStrength(int connectionStrength) { return this; }
        public NetworkProviderInfo build() { return null; }
    }
}
