package com.sensex.optiontrader.service;

import com.sensex.optiontrader.config.AngelOneProperties.InstrumentToken;
import com.sensex.optiontrader.model.entity.Instrument;
import com.sensex.optiontrader.repository.InstrumentRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Resolves instruments for streaming (Angel One) and the primary underlying for predictions.
 * Showcase mode: only Bank Nifty is streamed and used as the primary instrument (no multi-symbol mix).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstrumentRegistry {

    private final InstrumentRepository instrumentRepository;
    private final CopyOnWriteArrayList<InstrumentToken> streamingTokens = new CopyOnWriteArrayList<>();

    @PostConstruct
    void init() {
        refreshStreamingSubscriptions();
    }

    /**
     * Angel One subscribes to Bank Nifty only so live ticks and UI prices stay in one unit / one index.
     */
    public synchronized void refreshStreamingSubscriptions() {
        streamingTokens.clear();
        instrumentRepository.findByNameIgnoreCase("BANKNIFTY").ifPresentOrElse(
                b -> {
                    streamingTokens.add(toToken(b));
                    log.info("Streaming registry: Bank Nifty only ({} token {}) for Angel One",
                            b.getName(), b.getToken());
                },
                () -> log.warn("BANKNIFTY not found in instruments table — Angel One streaming list is empty")
        );
    }

    /** Tokens Angel One should subscribe to (Bank Nifty only). */
    public List<InstrumentToken> getActiveInstruments() {
        return List.copyOf(streamingTokens);
    }

    /** Primary underlying for OHLCV, predictions, and live ticker — always Bank Nifty (showcase). */
    @Transactional(readOnly = true)
    public Optional<InstrumentToken> getPrimaryForUser(Long userId) {
        return defaultBankNifty();
    }

    private Optional<InstrumentToken> defaultBankNifty() {
        return instrumentRepository.findByNameIgnoreCase("BANKNIFTY").map(InstrumentRegistry::toToken);
    }

    public Optional<InstrumentToken> findByToken(String token) {
        return streamingTokens.stream()
                .filter(t -> t.token().equals(token))
                .findFirst()
                .or(() -> instrumentRepository.findByToken(token).map(InstrumentRegistry::toToken));
    }

    public Optional<InstrumentToken> findByName(String name) {
        return instrumentRepository.findByNameIgnoreCase(name).map(InstrumentRegistry::toToken);
    }

    public String resolveInstrumentName(String token) {
        return instrumentRepository.findByToken(token)
                .map(Instrument::getName)
                .orElse(token);
    }

    /** Used by Spring @Cacheable on predictions so cache invalidates when the user switches instrument. */
    public String primaryTokenOrNone(Long userId) {
        return getPrimaryForUser(userId).map(InstrumentToken::token).orElse("none");
    }

    static InstrumentToken toToken(Instrument i) {
        return new InstrumentToken(i.getName(), i.getExchange(), i.getToken(), i.getExchangeType());
    }
}
