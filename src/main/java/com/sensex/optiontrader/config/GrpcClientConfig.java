package com.sensex.optiontrader.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class GrpcClientConfig {
    private final AppProperties props;
    private ManagedChannel channel;

    @Bean
    public ManagedChannel mlServiceChannel() {
        String host = props.getMlService().getHost();
        int port = props.getMlService().getPort();
        boolean tls = props.getMlService().isTls();

        if (tls) {
            try {
                this.channel = NettyChannelBuilder.forAddress(host, port)
                        .sslContext(GrpcSslContexts.forClient().build())
                        .keepAliveTime(30, TimeUnit.SECONDS)
                        .keepAliveTimeout(10, TimeUnit.SECONDS)
                        .build();
                log.info("ML gRPC channel: {}:{} (TLS)", host, port);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to create TLS gRPC channel", e);
            }
        } else {
            this.channel = ManagedChannelBuilder.forAddress(host, port)
                    .usePlaintext()
                    .keepAliveTime(30, TimeUnit.SECONDS)
                    .keepAliveTimeout(10, TimeUnit.SECONDS)
                    .build();
            log.info("ML gRPC channel: {}:{} (plaintext)", host, port);
        }
        return channel;
    }

    @PreDestroy
    public void shutdown() {
        if (channel != null && !channel.isShutdown()) channel.shutdown();
    }
}
