/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.mars.qraft.common.dns;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Qraft's DNS listener (DNS discovery list, decisions 7 and 8): it answers queries over UDP and TCP on one port, a
 * UDP answer within 512 bytes or the query's EDNS size and marked truncated when it is cut, so that a resolver asks
 * again over TCP.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-09
 * @version 1.0
 */
public final class DnsServer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(DnsServer.class);
    /** How long a TCP connection may stay idle between queries. */
    private static final int TCP_IDLE_MILLIS = 10_000;
    private static final int BIND_ATTEMPTS = 10;

    private final DatagramSocket udp;
    private final ServerSocket tcp;
    private final DnsResolver resolver;
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final Thread udpLoop;
    private final Thread tcpLoop;
    private volatile boolean closed;

    private DnsServer(DatagramSocket udp, ServerSocket tcp, DnsResolver resolver) {
        this.udp = udp;
        this.tcp = tcp;
        this.resolver = resolver;
        this.udpLoop = Thread.ofPlatform().daemon().name("qraft-dns-udp").start(this::receiveUdp);
        this.tcpLoop = Thread.ofPlatform().daemon().name("qraft-dns-tcp").start(this::acceptTcp);
    }

    /** Listens on {@code host} and {@code port}, UDP and TCP both, where port 0 picks a port free for both. */
    public static DnsServer start(String host, int port, DnsResolver resolver) throws IOException {
        Objects.requireNonNull(resolver, "resolver");
        if (port < 0 || port > 65_535) throw new IllegalArgumentException("The DNS port must be 0 to 65535: " + port);
        InetAddress address = InetAddress.getByName(Objects.requireNonNull(host, "host"));
        for (int attempt = 1; ; attempt++) {
            ServerSocket tcp = new ServerSocket();
            try {
                tcp.bind(new InetSocketAddress(address, port), 128);
                DatagramSocket udp = new DatagramSocket(new InetSocketAddress(address, tcp.getLocalPort()));
                DnsServer server = new DnsServer(udp, tcp, resolver);
                LOG.info("DNS listening on {}:{} over UDP and TCP", address.getHostAddress(), tcp.getLocalPort());
                return server;
            } catch (IOException taken) {
                tcp.close();
                // A port the system picked for TCP may be taken for UDP; another is tried.
                if (port != 0 || attempt == BIND_ATTEMPTS) {
                    BindException failed = new BindException("DNS could not listen on " + host + ":" + port + ": "
                            + taken.getMessage());
                    failed.initCause(taken);
                    throw failed;
                }
            }
        }
    }

    /** The port both listeners use. */
    public int port() {
        return tcp.getLocalPort();
    }

    private void receiveUdp() {
        byte[] buffer = new byte[DnsCodec.MAX_UDP_SIZE];
        while (!closed) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                udp.receive(packet);
            } catch (IOException failed) {
                if (!closed) LOG.warn("The DNS UDP listener could not receive: {}", failed.getMessage());
                continue;
            }
            byte[] query = java.util.Arrays.copyOf(packet.getData(), packet.getLength());
            SocketAddress from = packet.getSocketAddress();
            Thread.ofVirtual().name("qraft-dns-query").start(() -> {
                byte[] answer = answer(query, true);
                if (answer == null || closed) return;
                try {
                    udp.send(new DatagramPacket(answer, answer.length, from));
                } catch (IOException failed) {
                    LOG.debug("A DNS answer to {} could not be sent: {}", from, failed.getMessage());
                }
            });
        }
    }

    private void acceptTcp() {
        while (!closed) {
            Socket socket;
            try {
                socket = tcp.accept();
            } catch (IOException failed) {
                if (!closed) LOG.warn("The DNS TCP listener could not accept: {}", failed.getMessage());
                continue;
            }
            connections.add(socket);
            Thread.ofVirtual().name("qraft-dns-tcp-connection").start(() -> serve(socket));
        }
    }

    /** Answers the queries on one connection in turn, until it closes or stays idle. */
    private void serve(Socket socket) {
        try (socket) {
            socket.setSoTimeout(TCP_IDLE_MILLIS);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            while (!closed) {
                int length;
                try {
                    length = in.readUnsignedShort();
                } catch (EOFException | SocketTimeoutException finished) {
                    return;
                }
                byte[] query = new byte[length];
                in.readFully(query);
                byte[] answer = answer(query, false);
                if (answer == null) return;
                out.writeShort(answer.length);
                out.write(answer);
                out.flush();
            }
        } catch (IOException failed) {
            if (!closed) LOG.debug("A DNS TCP connection ended: {}", failed.getMessage());
        } finally {
            connections.remove(socket);
        }
    }

    /** The answer to the packet {@code query}, or {@code null} when it is dropped. */
    private byte[] answer(byte[] query, boolean overUdp) {
        DnsQuery parsed;
        try {
            parsed = DnsCodec.parse(query, query.length);
        } catch (DnsFormatException unreadable) {
            LOG.debug("A DNS packet was not a query to answer: {}", unreadable.getMessage());
            return unreadable.answerable() ? DnsCodec.encodeError(unreadable) : null;
        }
        int limit = !overUdp ? DnsCodec.TCP_SIZE
                : parsed.ednsUdpSize() > 0 ? Math.min(parsed.ednsUdpSize(), DnsCodec.MAX_UDP_SIZE) : DnsCodec.UDP_SIZE;
        try {
            return DnsCodec.encode(parsed, resolver.resolve(parsed).join(), limit);
        } catch (RuntimeException failed) {
            LOG.debug("A DNS answer to {} could not be written: {}", parsed.name(), String.valueOf(failed));
            return DnsCodec.encode(parsed, DnsAnswer.of(DnsCodec.SERVFAIL), limit);
        }
    }

    /** Stops both listeners and waits for them. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        udp.close();
        try {
            tcp.close();
        } catch (IOException ignored) {
            // Closing a listener that is already closed.
        }
        for (Socket socket : connections) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing a connection that is already closed.
            }
        }
        try {
            udpLoop.join(5_000);
            tcpLoop.join(5_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
