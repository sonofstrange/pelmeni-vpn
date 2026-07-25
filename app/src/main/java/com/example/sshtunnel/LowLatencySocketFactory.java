package com.example.sshtunnel;

import android.net.Network;

import com.jcraft.jsch.SocketFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Creates the SSH transport socket with Nagle buffering disabled. */
final class LowLatencySocketFactory implements SocketFactory {
    private final Network network;

    LowLatencySocketFactory(Network network) {
        this.network = network;
    }

    @Override public Socket createSocket(String host, int port) throws IOException {
        Socket socket = network == null
                ? new Socket() : network.getSocketFactory().createSocket();
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        InetSocketAddress address = network == null
                ? new InetSocketAddress(host, port)
                : new InetSocketAddress(network.getByName(host), port);
        socket.connect(address, 15_000);
        return socket;
    }

    @Override public InputStream getInputStream(Socket socket) throws IOException {
        return socket.getInputStream();
    }

    @Override public OutputStream getOutputStream(Socket socket) throws IOException {
        return socket.getOutputStream();
    }
}
