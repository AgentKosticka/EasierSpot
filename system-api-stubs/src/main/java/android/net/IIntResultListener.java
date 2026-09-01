package android.net;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/** Compile-only signature stub. Android's framework implementation is used at runtime. */
public interface IIntResultListener extends IInterface {
    void onResult(int resultCode) throws RemoteException;

    abstract class Stub extends Binder implements IIntResultListener {
        @Override
        public IBinder asBinder() {
            return this;
        }
    }
}
