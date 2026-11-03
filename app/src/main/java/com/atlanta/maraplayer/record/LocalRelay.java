package com.atlanta.maraplayer.record;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Kayit sirasinda tek baglantiyla izlemeye devam etmeyi saglayan yerel aktarim.
 *
 * Saglayici (panel) genellikle tek eszamanli baglantiya izin verir. Kayit
 * ikinci bir baglanti actiginda panel oynaticinin baglantisini dusurur; bu
 * yuzden kayit sirasinda yayin durur. Bu sinif, kaydin yazdigi dosyayi
 * 127.0.0.1 uzerinden HTTP ile sunar: oynatici/onizleme ayni tek baglantidan
 * (kayit akisindan) beslenir, boylece ek baglanti acilmaz.
 *
 * Akis canli kenardan baslar; dosya buyudukce istemciye aktarilir. Kayit
 * bitince baglanti kapanir (oynatici canli adrese geri doner).
 */
public final class LocalRelay {

    /** Yerel aktarim portu (web panel 8765 kullanir). */
    public static final int PORT = 8767;

    private static volatile boolean running;
    private static ServerSocket server;

    private LocalRelay() {
    }

    public static boolean isRunning() {
        return running;
    }

    /** Sunucuyu (gerekirse) baslatir. */
    public static synchronized void ensureStarted() {
        if (running) return;
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
            server = ss;
            running = true;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (running) {
                        try {
                            final Socket s = server.accept();
                            Thread c = new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    serve(s);
                                }
                            }, "relay-client");
                            c.setDaemon(true);
                            c.start();
                        } catch (Exception e) {
                            if (!running) break;
                        }
                    }
                }
            }, "relay");
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            running = false;
            server = null;
        }
    }

    /** Sunucuyu kapatir (aktif kayit kalmadiginda cagrilir). */
    public static synchronized void stop() {
        running = false;
        if (server != null) {
            try {
                server.close();
            } catch (Exception ignored) {
            }
            server = null;
        }
    }

    /** Kayit kimligi icin yerel oynatma adresi. */
    public static String url(long recId) {
        return "http://127.0.0.1:" + PORT + "/live/" + recId + ".ts";
    }

    /** Canli kenardan ne kadar geriden baslanir (yaklasik 5-8 sn). */
    private static final long EDGE_BACK = 1024L * 1024L;

    private static void serve(Socket s) {
        RandomAccessFile raf = null;
        try {
            s.setSoTimeout(10000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            String first = in.readLine();
            String h;
            while ((h = in.readLine()) != null && !h.isEmpty()) {
                // basliklar yok sayilir (Range desteklenmez, tam akis sunulur)
            }
            if (first == null) return;
            long id = parseId(first);
            Recorder.Active a = id > 0 ? Recorder.ACTIVE.get(id) : null;
            OutputStream out = s.getOutputStream();
            if (a == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));
                out.flush();
                return;
            }
            // Basliklar: chunked, boyut bilinmiyor (dosya buyuyor)
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: video/mp2t\r\n"
                    + "Cache-Control: no-cache\r\n"
                    + "Transfer-Encoding: chunked\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes("UTF-8"));
            out.flush();

            raf = new RandomAccessFile(a.file, "r");
            long pos = raf.length();
            pos = pos > EDGE_BACK ? pos - EDGE_BACK : 0;
            pos -= pos % 188; // TS paket siniri
            raf.seek(pos);

            byte[] buf = new byte[64 * 1024];
            while (running) {
                int n = raf.read(buf);
                if (n > 0) {
                    // HTTP chunk: boyut + veri
                    out.write((Integer.toHexString(n) + "\r\n").getBytes("UTF-8"));
                    out.write(buf, 0, n);
                    out.write("\r\n".getBytes("UTF-8"));
                    out.flush();
                } else {
                    if (Recorder.ACTIVE.get(id) == null) break; // kayit bitti
                    Thread.sleep(120);
                }
            }
            out.write("0\r\n\r\n".getBytes("UTF-8"));
            out.flush();
        } catch (Exception ignored) {
            // istemci kapatti / kayit bitti
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Exception ignored) {
                }
            }
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** "GET /live/12.ts HTTP/1.1" -> 12 */
    private static long parseId(String requestLine) {
        try {
            int a = requestLine.indexOf("/live/");
            if (a < 0) return -1;
            a += 6;
            int b = requestLine.indexOf('.', a);
            if (b < 0) b = requestLine.indexOf(' ', a);
            return Long.parseLong(requestLine.substring(a, b));
        } catch (Exception e) {
            return -1;
        }
    }
}
