package com.sensex.optiontrader.repository;

import com.sensex.optiontrader.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query("SELECT DISTINCT u.preferredInstrument.id FROM User u WHERE u.preferredInstrument IS NOT NULL")
    List<Long> findDistinctPreferredInstrumentIds();

    /** Loads user with preferred instrument in one round-trip (safe outside a web request / on worker threads). */
    @Query("SELECT u FROM User u JOIN FETCH u.preferredInstrument WHERE u.id = :id")
    Optional<User> findByIdWithPreferredInstrument(@Param("id") Long id);
}