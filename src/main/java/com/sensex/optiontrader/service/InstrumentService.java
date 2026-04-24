package com.sensex.optiontrader.service;

import com.sensex.optiontrader.exception.BadRequestException;
import com.sensex.optiontrader.exception.ResourceNotFoundException;
import com.sensex.optiontrader.model.entity.Instrument;
import com.sensex.optiontrader.model.entity.User;
import com.sensex.optiontrader.repository.InstrumentRepository;
import com.sensex.optiontrader.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InstrumentService {

    private final InstrumentRepository repo;
    private final UserRepository userRepo;
    private final InstrumentRegistry registry;
    private final LiveMarketStreamService liveStream;

    /** Showcase: Bank Nifty only (no multi-instrument picker). */
    public List<Instrument> getAllInstruments() {
        return repo.findByNameIgnoreCase("BANKNIFTY").map(List::of).orElseGet(List::of);
    }

    public List<Instrument> getByMarketType(String marketType) {
        return repo.findByNameIgnoreCase("BANKNIFTY")
                .filter(i -> marketType != null && marketType.equalsIgnoreCase(i.getMarketType()))
                .map(List::of)
                .orElseGet(List::of);
    }

    /** Active underlying for the user — Bank Nifty only. */
    @Transactional(readOnly = true)
    public List<Instrument> getActiveInstrumentsForUser(Long userId) {
        userRepo.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        return repo.findByNameIgnoreCase("BANKNIFTY")
                .map(List::of)
                .orElseThrow(() -> new ResourceNotFoundException("Instrument", "name", "BANKNIFTY"));
    }

    /**
     * Sets the user's preferred instrument (market selection). Does not mutate global {@code instruments.active}.
     */
    @Transactional
    @CacheEvict(value = {"marketData", "predictions"}, allEntries = true)
    public Instrument switchActive(Long userId, Long instrumentId) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        Instrument target = repo.findById(instrumentId)
                .orElseThrow(() -> new ResourceNotFoundException("Instrument", "id", instrumentId));
        Instrument bankNifty = repo.findByNameIgnoreCase("BANKNIFTY")
                .orElseThrow(() -> new BadRequestException("BANKNIFTY is not configured"));
        if (!bankNifty.getId().equals(target.getId())) {
            throw new BadRequestException("Only Bank Nifty is available");
        }

        user.setPreferredInstrument(target);
        userRepo.save(user);

        registry.refreshStreamingSubscriptions();
        liveStream.onInstrumentSwitch();
        log.info("User {} switched preferred instrument to: {} ({})", userId, target.getDisplayName(), target.getToken());
        return target;
    }

    /**
     * Kept for API compatibility: same as switching the user's sole active instrument.
     */
    @Transactional
    public Instrument activate(Long userId, Long instrumentId) {
        return switchActive(userId, instrumentId);
    }

    @Transactional
    public Instrument deactivate(Long userId, Long instrumentId) {
        User user = userRepo.findByIdWithPreferredInstrument(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        Instrument target = repo.findById(instrumentId)
                .orElseThrow(() -> new ResourceNotFoundException("Instrument", "id", instrumentId));
        if (!target.getId().equals(user.getPreferredInstrument().getId())) {
            throw new BadRequestException("Instrument is not the user's current selection");
        }
        Instrument bankNifty = repo.findByNameIgnoreCase("BANKNIFTY")
                .orElseThrow(() -> new BadRequestException("Default instrument BANKNIFTY not configured"));
        return switchActive(userId, bankNifty.getId());
    }
}
