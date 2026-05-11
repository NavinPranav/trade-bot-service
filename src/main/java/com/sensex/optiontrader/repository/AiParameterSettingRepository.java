package com.sensex.optiontrader.repository;

import com.sensex.optiontrader.model.entity.AiParameterSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiParameterSettingRepository extends JpaRepository<AiParameterSetting, Long> {

    Optional<AiParameterSetting> findByParameterKey(String parameterKey);

    List<AiParameterSetting> findAllByOrderBySortOrderAscIdAsc();
}
