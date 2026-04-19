package com.sensex.optiontrader.repository;
import com.sensex.optiontrader.model.entity.MarketSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.*;
public interface MarketSnapshotRepository extends JpaRepository<MarketSnapshot,Long> {
    Optional<MarketSnapshot> findTopByOrderByTimestampDesc();
    List<MarketSnapshot> findByTimestampBetweenOrderByTimestampAsc(LocalDateTime s, LocalDateTime e);
}