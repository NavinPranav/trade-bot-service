package com.sensex.optiontrader.repository;
import com.sensex.optiontrader.model.entity.BacktestJob;
import com.sensex.optiontrader.model.enums.BacktestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface BacktestJobRepository extends JpaRepository<BacktestJob,Long> {
    List<BacktestJob> findByUserIdOrderByCreatedAtDesc(Long userId);
    long countByUserIdAndStatus(Long userId, BacktestStatus status);
}