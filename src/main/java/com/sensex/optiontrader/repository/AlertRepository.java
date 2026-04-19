package com.sensex.optiontrader.repository;
import com.sensex.optiontrader.model.entity.Alert;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface AlertRepository extends JpaRepository<Alert,Long> {
    List<Alert> findByUserIdAndActiveTrue(Long userId);
    List<Alert> findByActiveTrueAndTriggeredFalse();
}