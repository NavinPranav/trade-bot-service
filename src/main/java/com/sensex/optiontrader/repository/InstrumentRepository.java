package com.sensex.optiontrader.repository;

import com.sensex.optiontrader.model.entity.Instrument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InstrumentRepository extends JpaRepository<Instrument, Long> {

    List<Instrument> findByActiveTrueOrderByDisplayOrder();

    List<Instrument> findByMarketTypeOrderByDisplayOrder(String marketType);

    List<Instrument> findAllByOrderByDisplayOrder();

    Optional<Instrument> findByToken(String token);

    Optional<Instrument> findByNameIgnoreCase(String name);
}
