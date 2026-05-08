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

        // ── Effective ML-service config banner ────────────────────────────
        // Logged on startup so deploy verification is a single-line grep instead of
        // hunting for behaviour. Look for these markers in the Render log right
        // after Spring startup to confirm the latest code + env vars landed:
        //
        //   ML_TRANSPORT  : the resolved transport (AUTO / REST / GRPC)
        //   ML_HTTP_URL   : the REST fallback target (empty = REST disabled)
        //   ML_MIN_BARS   : per-horizon minimum bars (proves the new config was read)
        //
        // If you see "ML_TRANSPORT=AUTO" but expected REST, the env var
        // ML_SERVICE_TRANSPORT did not reach the JVM.
        var ml = props.getMlService();
        log.info("ML-SERVICE-CONFIG | ML_TRANSPORT={} ML_HTTP_URL={} ML_GRPC={}:{} TLS={} ML_MIN_BARS={} ML_DEFAULT_MIN_BARS={}",
                ml.getTransport(),
                ml.getHttpUrl() == null || ml.getHttpUrl().isBlank() ? "<unset>" : ml.getHttpUrl(),
                ml.getHost(), ml.getPort(), ml.isTls(),
                ml.getMinBarsByHorizon(), ml.getDefaultMinBars());

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
