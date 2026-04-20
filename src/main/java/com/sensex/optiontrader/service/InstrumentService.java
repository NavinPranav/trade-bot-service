package com.sensex.optiontrader.service;

import com.sensex.optiontrader.exception.BadRequestException;
import com.sensex.optiontrader.exception.ResourceNotFoundException;
import com.sensex.optiontrader.model.entity.Instrument;
import com.sensex.optiontrader.repository.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InstrumentService {

    private final InstrumentRepository repo;
    private final InstrumentRegistry registry;
    private final LiveMarketStreamService liveStream;

    public List<Instrument> getAllInstruments() {
        return repo.findAllByOrderByDisplayOrder();
    }

    public List<Instrument> getByMarketType(String marketType) {
        return repo.findByMarketTypeOrderByDisplayOrder(marketType.toUpperCase());
    }

    public List<Instrument> getActiveInstruments() {
        return repo.findByActiveTrueOrderByDisplayOrder();
    }

    /**
     * Switches the active instrument: deactivates all currently active instruments
     * and activates the one with the given ID. Reloads the registry and signals
     * the WebSocket to resubscribe.
     *
     * @return the newly activated instrument
     */
    @Transactional
    public Instrument switchActive(Long instrumentId) {
        Instrument target = repo.findById(instrumentId)
                .orElseThrow(() -> new ResourceNotFoundException("Instrument", "id", instrumentId));

        List<Instrument> currentlyActive = repo.findByActiveTrueOrderByDisplayOrder();
        for (Instrument inst : currentlyActive) {
            inst.setActive(false);
        }
        repo.saveAll(currentlyActive);

        target.setActive(true);
        repo.save(target);

        registry.reload();
        liveStream.onInstrumentSwitch();
        log.info("Switched active instrument to: {} ({})", target.getDisplayName(), target.getToken());
        return target;
    }

    /**
     * Activates an additional instrument (multi-instrument streaming).
     */
    @Transactional
    public Instrument activate(Long instrumentId) {
        Instrument target = repo.findById(instrumentId)
                .orElseThrow(() -> new ResourceNotFoundException("Instrument", "id", instrumentId));
        if (target.isActive()) {
            throw new BadRequestException("Instrument is already active: " + target.getName());
        }
        target.setActive(true);
        repo.save(target);
        registry.reload();
        liveStream.onInstrumentSwitch();
        log.info("Activated instrument: {} ({})", target.getDisplayName(), target.getToken());
        return target;
    }

    /**
     * Deactivates an instrument.
     */
    @Transactional
    public Instrument deactivate(Long instrumentId) {
        Instrument target = repo.findById(instrumentId)
                .orElseThrow(() -> new ResourceNotFoundException("Instrument", "id", instrumentId));
        if (!target.isActive()) {
            throw new BadRequestException("Instrument is already inactive: " + target.getName());
        }
        target.setActive(false);
        repo.save(target);
        registry.reload();
        liveStream.onInstrumentSwitch();
        log.info("Deactivated instrument: {} ({})", target.getDisplayName(), target.getToken());
        return target;
    }
}
