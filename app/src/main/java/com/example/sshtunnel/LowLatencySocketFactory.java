package com.example.sshtunnel;

import android.net.Network;

import com.jcraft.jsch.SocketFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;

/** Creates a low-latency SSH socket with enough TCP buffering for high-RTT links. */
final class LowLatencySocketFactory implements SocketFactory {
    private final Network network;
    private final int socketBufferBytes;

    LowLatencySocketFactory(Network network) {
        this(network, NetworkTuning.DEFAULT_WINDOW_KIB * 1024);
    }

    LowLatencySocketFactory(Network network, int sshWindowBytes) {
        this.network = network;
        this.socketBufferBytes = NetworkTuning.socketBufferBytes(sshWindowBytes);
    }

    @Override public Socket createSocket(String host, int port) throws IOException {
        Socket socket = network == null
                ? new Socket() : network.getSocketFactory().createSocket();
        configure(socket);
        InetSocketAddress address = network == null
                ? new InetSocketAddress(host, port)
                : new InetSocketAddress(network.getByName(host), port);
        socket.connect(address, 15_000);
        configure(socket);
        return socket;
    }

    private void configure(Socket socket) throws SocketException {
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.setReceiveBufferSize(socketBufferBytes);
        socket.setSendBufferSize(socketBufferBytes);
    }

    @Override public InputStream getInputStream(Socket socket) throws IOException {
        return socket.getInputStream();
    }

    @Override public OutputStream getOutputStream(Socket socket) throws IOException {
        return socket.getOutputStream();
    }
}
