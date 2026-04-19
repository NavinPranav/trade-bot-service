package com.sensex.optiontrader.repository;

import com.sensex.optiontrader.model.entity.MlServiceConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MlServiceConfigRepository extends JpaRepository<MlServiceConfig, Long> {
    Optional<MlServiceConfig> findByConfigKey(String configKey);
}
