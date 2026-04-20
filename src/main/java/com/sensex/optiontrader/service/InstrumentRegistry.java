package com.sensex.optiontrader.service;

import com.sensex.optiontrader.config.AngelOneProperties.InstrumentToken;
import com.sensex.optiontrader.model.entity.Instrument;
import com.sensex.optiontrader.repository.InstrumentRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runtime holder for the active instrument list.
 * On startup, loads active instruments from the DB and converts them to
 * {@link InstrumentToken} records so existing code (WebSocket, historical client, etc.)
 * works unchanged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstrumentRegistry {

    private final InstrumentRepository repo;
    private final CopyOnWriteArrayList<InstrumentToken> activeTokens = new CopyOnWriteArrayList<>();

    @PostConstruct
    void init() {
        reload();
    }

    public void reload() {
        List<Instrument> active = repo.findByActiveTrueOrderByDisplayOrder();
        activeTokens.clear();
        activeTokens.addAll(active.stream().map(InstrumentRegistry::toToken).toList());
        log.info("InstrumentRegistry loaded {} active instruments", activeTokens.size());
    }

    public List<InstrumentToken> getActiveInstruments() {
        return List.copyOf(activeTokens);
    }

    public Optional<InstrumentToken> getPrimary() {
        return activeTokens.isEmpty() ? Optional.empty() : Optional.of(activeTokens.get(0));
    }

    public Optional<InstrumentToken> findByToken(String token) {
        return activeTokens.stream().filter(t -> t.token().equals(token)).findFirst();
    }

    public Optional<InstrumentToken> findByName(String name) {
        return activeTokens.stream()
                .filter(t -> t.name().equalsIgnoreCase(name))
                .findFirst();
    }

    public String resolveInstrumentName(String token) {
        return findByToken(token).map(InstrumentToken::name).orElse(token);
    }

    static InstrumentToken toToken(Instrument i) {
        return new InstrumentToken(i.getName(), i.getExchange(), i.getToken(), i.getExchangeType());
    }
}
