package com.sensex.optiontrader.service;

import com.sensex.optiontrader.integration.angelone.AngelOneOptionsClient;
import com.sensex.optiontrader.service.InstrumentRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Options chain snapshot service — fetches and caches the nearest-expiry options
 * chain for the user's primary instrument from Angel One SmartAPI.
 *
 * <p>Caches for {@code options-chain-ttl} seconds (default 180 s from application.yml)
 * so rapid successive prediction calls don't hammer the Angel One API.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OptionsChainService {

    private final AngelOneOptionsClient optionsClient;
    private final InstrumentRegistry instrumentRegistry;

    /**
     * Returns the merged options chain for the user's primary instrument.
     * Each row map has keys: strike, call_oi, put_oi, call_volume, put_volume,
     * call_iv, put_iv, call_ltp, put_ltp.
     * Returns an empty list when Angel One is unavailable or the instrument is not configured.
     */
    @Cacheable(
            value = "optionsChain",
            key = "'v1-oc-'+@instrumentRegistry.primaryTokenOrNone(#userId)"
    )
    public List<Map<String, Object>> getOptionsChainForUser(Long userId) {
        String name = instrumentRegistry.getPrimaryForUser(userId)
                .map(inst -> inst.name())
                .orElse(null);

        if (name == null || name.isBlank()) {
            log.debug("No primary instrument for userId={} — skipping options chain fetch", userId);
            return List.of();
        }

        log.debug("Fetching options chain for userId={} instrument={}", userId, name);
        List<Map<String, Object>> chain = optionsClient.fetchNearestExpiry(name);
        log.info("Options chain fetched: userId={} instrument={} strikes={}", userId, name, chain.size());
        return chain;
    }
}
