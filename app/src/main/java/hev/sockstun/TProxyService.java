package hev.sockstun;

/** JNI registration target for the bundled MIT-licensed HevSocks5Tunnel library. */
public final class TProxyService {
    static { System.loadLibrary("hev-socks5-tunnel"); }

    public static native void TProxyStartService(String configPath, int fd);
    public static native void TProxyStopService();
    public static native long[] TProxyGetStats();

    private TProxyService() {}
}
