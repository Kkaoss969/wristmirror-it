package io.github.escosis.hwtfaceswap.transport;

import java.io.IOException;
import java.util.List;

/** 本应用拥有的单个连接实例；绝不修改 Android 配对关系或其他应用的连接。 */
public interface LinkChannel {
    interface Listener {
        void onOpen(long epoch,String name);
        void onBytes(long epoch,byte[] bytes);
        void onFailure(long epoch,String error);
    }
    void connect();
    void send(List<byte[]> frames,int peerMtu,int intervalMs)throws IOException;
    void close();
}
