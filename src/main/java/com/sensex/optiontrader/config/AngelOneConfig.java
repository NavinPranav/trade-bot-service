package com.sensex.optiontrader.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

@Slf4j
@Configuration
@EnableConfigurationProperties(AngelOneProperties.class)
public class AngelOneConfig {

    @Bean
    public RestClient angelOneRestClient(AngelOneProperties props) {
        String localIp = resolveLocalIp();
        String macAddress = resolveMacAddress();

        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .defaultHeader("X-UserType", "USER")
                .defaultHeader("X-SourceID", "WEB")
                .defaultHeader("X-PrivateKey", props.apiKey())
                .defaultHeader("X-ClientLocalIP", localIp)
                .defaultHeader("X-ClientPublicIP", localIp)
                .defaultHeader("X-MACAddress", macAddress)
                .build();
    }

    private static String resolveLocalIp() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not resolve local IP: {}", e.getMessage());
        }
        return "127.0.0.1";
    }

    private static String resolveMacAddress() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                byte[] mac = iface.getHardwareAddress();
                if (mac != null && mac.length > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < mac.length; i++) {
                        sb.append(String.format("%02X", mac[i]));
                        if (i < mac.length - 1) sb.append(':');
                    }
                    return sb.toString();
                }
            }
        } catch (Exception e) {
            log.warn("Could not resolve MAC address: {}", e.getMessage());
        }
        return "00:00:00:00:00:00";
    }
}
