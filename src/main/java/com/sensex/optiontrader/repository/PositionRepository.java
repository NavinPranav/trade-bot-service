package com.sensex.optiontrader.repository;
import com.sensex.optiontrader.model.entity.Position;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface PositionRepository extends JpaRepository<Position,Long> {
    List<Position> findByUserIdAndIsOpenTrue(Long userId);
    List<Position> findByUserIdOrderByOpenedAtDesc(Long userId);
}