package com.sensex.optiontrader.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
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
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .build();
        log.info(
                "ML gRPC channel: {}:{} (plaintext). If you see DecodeError/UNKNOWN, verify this is the gRPC port — not the HTTP/FastAPI port (e.g. 8000).",
                host,
                port);
        return channel;
    }
    @PreDestroy public void shutdown() { if (channel != null && !channel.isShutdown()) channel.shutdown(); }
}