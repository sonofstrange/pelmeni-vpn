package com.jcraft.jsch;

/** Keeps direct-tcpip channels from stalling on JSch's small 128 KiB receive window. */
public final class ChannelTuning {
    public static void optimizeDirectTcpIp(
            ChannelDirectTCPIP channel, int windowSize, int packetSize) {
        channel.setLocalWindowSizeMax(windowSize);
        channel.setLocalWindowSize(windowSize);
        channel.setLocalPacketSize(packetSize);
    }

    private ChannelTuning() {
    }
}
