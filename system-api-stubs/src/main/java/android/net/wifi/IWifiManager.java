package android.net.wifi;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/** Compile-only signature stub. Android's framework implementation is used at runtime. */
public interface IWifiManager extends IInterface {
    SoftApConfiguration getSoftApConfiguration() throws RemoteException;
    int getWifiApEnabledState() throws RemoteException;

    abstract class Stub extends Binder implements IWifiManager {
        public static IWifiManager asInterface(IBinder binder) {
            return null;
        }

        @Override
        public IBinder asBinder() {
            return this;
        }
    }
}
