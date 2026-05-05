package com.sensex.optiontrader.repository;

import com.sensex.optiontrader.model.entity.Instrument;
import com.sensex.optiontrader.model.entity.Prediction;
import com.sensex.optiontrader.model.entity.User;
import com.sensex.optiontrader.model.enums.Direction;
import com.sensex.optiontrader.model.enums.OutcomeStatus;
import com.sensex.optiontrader.model.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 — Query sanity for risk limits: {@link PredictionRepository#countDirectionalSignalsOnDate}
 * and {@link PredictionRepository#sumResolvedPnlPctOnDate}. Uses PostgreSQL (Testcontainers) so JPQL/SQL
 * matches production; in-memory H2 + Hibernate 6 identity inserts are not compatible without extra tuning.
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PredictionRepositoryRiskQueriesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 4, 30);
    private static final LocalDate OTHER_DAY = LocalDate.of(2026, 4, 29);

    /** Same membership as RiskLimitService.DIRECTIONAL */
    private static final List<Direction> DIRECTIONAL = List.of(
            Direction.BUY, Direction.SELL, Direction.BULLISH, Direction.BEARISH);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void registerPg(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        r.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired
    private PredictionRepository predictionRepo;
    @Autowired
    private UserRepository userRepo;
    @Autowired
    private InstrumentRepository instrumentRepo;

    private User user;

    @BeforeEach
    void setUp() {
        Instrument inst = instrumentRepo.save(Instrument.builder()
                .name("BANKNIFTY")
                .displayName("Bank Nifty")
                .exchange("nse_cm")
                .token("99926009")
                .exchangeType(1)
                .marketType("EQ")
                .active(true)
                .displayOrder(0)
                .build());
        user = userRepo.save(User.builder()
                .email("risk-test@example.com")
                .password("hash")
                .name("Risk Test")
                .role(UserRole.USER)
                .preferredInstrument(inst)
                .build());
    }

    @Test
    void countDirectionalSignalsOnDate_countsDirectionalSameUserAndDate_includesPending() {
        savePrediction(DAY, Direction.BUY, OutcomeStatus.PENDING, null);
        savePrediction(DAY, Direction.SELL, OutcomeStatus.PENDING, null);
        savePrediction(DAY, Direction.HOLD, OutcomeStatus.PENDING, null);
        savePrediction(DAY, Direction.BULLISH, OutcomeStatus.EXPIRED, null);
        savePrediction(OTHER_DAY, Direction.BUY, OutcomeStatus.PENDING, null);

        long n = predictionRepo.countDirectionalSignalsOnDate(user.getId(), DAY, DIRECTIONAL);
        assertThat(n).isEqualTo(3);
    }

    @Test
    void sumResolvedPnlPctOnDate_excludesPendingAndNullActualPnl() {
        savePrediction(DAY, Direction.BUY, OutcomeStatus.PENDING, new BigDecimal("-99.0000"));
        savePrediction(DAY, Direction.BUY, OutcomeStatus.EXPIRED, null);
        savePrediction(DAY, Direction.BUY, OutcomeStatus.TARGET_HIT, new BigDecimal("1.5000"));
        savePrediction(DAY, Direction.SELL, OutcomeStatus.STOP_LOSS_HIT, new BigDecimal("-2.2500"));
        savePrediction(OTHER_DAY, Direction.BUY, OutcomeStatus.EXPIRED, new BigDecimal("10"));

        BigDecimal sum = predictionRepo.sumResolvedPnlPctOnDate(user.getId(), DAY);
        assertThat(sum).isEqualByComparingTo(new BigDecimal("-0.7500"));
    }

    @Test
    void sumResolvedPnlPctOnDate_returnsZeroWhenNoMatchingRows() {
        BigDecimal sum = predictionRepo.sumResolvedPnlPctOnDate(user.getId(), DAY);
        assertThat(sum).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private void savePrediction(LocalDate date, Direction dir, OutcomeStatus outcome, BigDecimal actualPnlPct) {
        predictionRepo.save(Prediction.builder()
                .user(user)
                .predictionDate(date)
                .predictionTimestamp(Instant.parse("2026-04-30T04:00:00Z"))
                .horizon("15M")
                .direction(dir)
                .outcomeStatus(outcome)
                .actualPnlPct(actualPnlPct)
                .build());
    }
}
