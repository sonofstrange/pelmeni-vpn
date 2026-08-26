package com.jcraft.jsch;

import java.io.InputStream;

/** Keeps direct-tcpip channels from stalling on JSch's small 128 KiB receive window. */
public final class ChannelTuning {
    public static InputStream optimizeDirectTcpIp(
            ChannelDirectTCPIP channel, int windowSize, int packetSize) {
        channel.setLocalWindowSizeMax(windowSize);
        channel.setLocalWindowSize(windowSize);
        channel.setLocalPacketSize(packetSize);
        try {
            Channel.MyPipedInputStream in = new Channel.MyPipedInputStream(256 * 1024);
            channel.io.setInputStream(in);
            return in;
        } catch (Exception ignored) {
            try {
                return channel.getInputStream();
            } catch (Exception fallbackError) {
                return null;
            }
        }
    }

    private ChannelTuning() {
    }
}
