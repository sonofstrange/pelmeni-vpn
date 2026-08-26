package com.jcraft.jsch;

import java.io.IOException;
import java.io.InputStream;

/** Keeps direct-tcpip channels from stalling on JSch's small receive window and 32KB pipe. */
public final class ChannelTuning {
    public static InputStream optimizeDirectTcpIp(
            ChannelDirectTCPIP channel, int windowSize, int packetSize) throws IOException {
        channel.setLocalWindowSizeMax(windowSize);
        channel.setLocalWindowSize(windowSize);
        channel.setLocalPacketSize(packetSize);
        
        int pipeBufferSize = Math.max(512 * 1024, Math.min(windowSize, 4 * 1024 * 1024));
        Channel.MyPipedInputStream in = new Channel.MyPipedInputStream(pipeBufferSize, 1000);
        channel.io.setInputStream(in);
        return in;
    }

    private ChannelTuning() {
    }
}
