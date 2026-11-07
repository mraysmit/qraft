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

package dev.mars.qraft.raft;

import dev.mars.qraft.common.async.BlockingTaskExecutor;
import dev.mars.qraft.common.tls.ServerTls;
import dev.mars.qraft.common.tls.TlsMaterial;
import dev.mars.qraft.common.tls.TlsSettings;
import io.grpc.BindableService;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests TLS on the server's API gRPC port (transport security list, decision 3, Phase 2): with a required client
 * certificate, a caller with one from the cluster's CA is answered, and one with none, with one from another CA, or
 * without TLS is refused; without the requirement, a caller with no certificate is answered.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
class GrpcServiceServerTlsTest {
    private static final MethodDescriptor.Marshaller<String> TEXT = new MethodDescriptor.Marshaller<>() {
        @Override public InputStream stream(String value) {
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override public String parse(InputStream stream) {
            try {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    };
    private static final MethodDescriptor<String, String> ECHO = MethodDescriptor.<String, String>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY).setFullMethodName("test.Echo/Say")
            .setRequestMarshaller(TEXT).setResponseMarshaller(TEXT).build();
    private static final BindableService ECHO_SERVICE = () -> ServerServiceDefinition.builder("test.Echo")
            .addMethod(ECHO, ServerCalls.asyncUnaryCall((request, answer) -> {
                answer.onNext("echo " + request);
                answer.onCompleted();
            })).build();

    private BlockingTaskExecutor blockingTasks;
    private GrpcServiceServer server;
    private ManagedChannel channel;

    @AfterEach
    void stop() throws Exception {
        if (channel != null) channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        if (server != null) server.stop().get(15, TimeUnit.SECONDS);
        if (blockingTasks != null) blockingTasks.close();
    }

    static Path fixture(String name) {
        try {
            return Path.of(GrpcServiceServerTlsTest.class.getResource("/tls/" + name).toURI());
        } catch (Exception e) {
            throw new IllegalStateException("test TLS fixture " + name + " is missing", e);
        }
    }

    static TlsMaterial material(String ca, String cert, String key) {
        return TlsMaterial.load(new TlsSettings("test.tls", fixture(ca),
                Optional.ofNullable(cert).map(GrpcServiceServerTlsTest::fixture),
                Optional.ofNullable(key).map(GrpcServiceServerTlsTest::fixture), List.of("TLSv1.3", "TLSv1.2")));
    }

    private int start(boolean requireClientCertificate) throws Exception {
        blockingTasks = new BlockingTaskExecutor("grpc-tls-test", 2, Runnable::run);
        server = new GrpcServiceServer(blockingTasks, 0,
                Optional.of(new ServerTls(material("ca.pem", "server.pem", "server.key"), requireClientCertificate)),
                ECHO_SERVICE);
        server.start().get(15, TimeUnit.SECONDS);
        return server.port();
    }

    private String say(TlsMaterial caller, int port) throws Exception {
        NettyChannelBuilder builder = NettyChannelBuilder.forAddress("localhost", port);
        if (caller == null) {
            builder.usePlaintext();
        } else {
            builder.sslContext(GrpcTls.clientContext(caller));
        }
        channel = builder.build();
        return ClientCalls.blockingUnaryCall(channel, ECHO,
                CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS), "hello");
    }

    @Test
    void aCallerWithACertificateFromTheCaIsAnswered() throws Exception {
        int port = start(true);

        assertEquals("echo hello", say(material("ca.pem", "client.pem", "client.key"), port));
    }

    @Test
    void aCallerWithoutACertificateIsRefused() throws Exception {
        int port = start(true);

        StatusRuntimeException refused = assertThrows(StatusRuntimeException.class,
                () -> say(material("ca.pem", null, null), port));
        assertEquals(Status.Code.UNAVAILABLE, refused.getStatus().getCode());
    }

    @Test
    void aCallerWithACertificateFromAnotherCaIsRefused() throws Exception {
        int port = start(true);

        assertThrows(StatusRuntimeException.class,
                () -> say(material("ca.pem", "other-client.pem", "other-client.key"), port));
    }

    @Test
    void aCallerWithoutTlsIsRefused() throws Exception {
        int port = start(true);

        assertThrows(StatusRuntimeException.class, () -> say(null, port));
    }

    @Test
    void withoutTheRequirementACallerWithoutACertificateIsAnswered() throws Exception {
        int port = start(false);

        assertEquals("echo hello", say(material("ca.pem", null, null), port));
    }
}
