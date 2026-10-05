package com.atlanta.maraplayer.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Android 4.1-4.4'te TLS 1.1/1.2 desteklenir ama varsayilan olarak kapalidir.
 * Bu sarmalayici olusturulan her sokette destekleniyorsa TLS 1.1/1.2/1.3'u acar.
 */
public class Tls12SocketFactory extends SSLSocketFactory {

    private final SSLSocketFactory base;

    public Tls12SocketFactory(SSLSocketFactory base) {
        this.base = base;
    }

    private Socket patch(Socket s) {
        if (s instanceof SSLSocket) {
            SSLSocket ss = (SSLSocket) s;
            try {
                List<String> supported = Arrays.asList(ss.getSupportedProtocols());
                List<String> enabled = new ArrayList<>();
                for (String p : new String[]{"TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1"}) {
                    if (supported.contains(p)) enabled.add(p);
                }
                if (!enabled.isEmpty()) ss.setEnabledProtocols(enabled.toArray(new String[0]));
            } catch (Throwable ignored) {
            }
        }
        return s;
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return base.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return base.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return patch(base.createSocket(s, host, port, autoClose));
    }

    @Override
    public Socket createSocket() throws IOException {
        return patch(base.createSocket());
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return patch(base.createSocket(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return patch(base.createSocket(host, port, localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return patch(base.createSocket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return patch(base.createSocket(address, port, localAddress, localPort));
    }
}
