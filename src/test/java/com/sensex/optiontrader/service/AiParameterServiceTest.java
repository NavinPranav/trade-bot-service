package com.sensex.optiontrader.service;

import com.sensex.optiontrader.config.AppProperties;
import com.sensex.optiontrader.model.entity.AiParameterSetting;
import com.sensex.optiontrader.repository.AiParameterSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AiParameterService}. We mock the JPA repo and rely on
 * the empty-{@code httpUrl} default of {@link AppProperties.MlService} so the
 * sync side-effect short-circuits without making a real HTTP request.
 */
@ExtendWith(MockitoExtension.class)
class AiParameterServiceTest {

    @Mock
    private AiParameterSettingRepository repo;

    private AppProperties props;

    @InjectMocks
    private AiParameterService service;

    @BeforeEach
    void setUp() {
        // Real props with empty httpUrl ⇒ pushToMlService is a no-op
        props = new AppProperties();
        service = new AiParameterService(repo, props);
    }

    @Test
    void listAll_returnsRowsInSortOrder() {
        AiParameterSetting required = aSetting("ohlcv_bars", true, true, "OHLCV", 10);
        AiParameterSetting optional = aSetting("news_sentiment", true, false, "News", 130);
        when(repo.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of(required, optional));

        List<AiParameterSetting> all = service.listAll();
        assertThat(all).extracting(AiParameterSetting::getParameterKey)
                .containsExactly("ohlcv_bars", "news_sentiment");
    }

    @Test
    void currentToggleMap_buildsKeyEnabledMap() {
        when(repo.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of(
                aSetting("ohlcv_bars", true, true, "OHLCV", 10),
                aSetting("news_sentiment", false, false, "News", 130)));

        Map<String, Boolean> map = service.currentToggleMap();
        assertThat(map).containsExactly(
                Map.entry("ohlcv_bars", true),
                Map.entry("news_sentiment", false));
    }

    @Test
    void updateEnabled_disablingRequiredParameterIsRejected() {
        AiParameterSetting required = aSetting("ohlcv_bars", true, true, "OHLCV", 10);
        when(repo.findByParameterKey("ohlcv_bars")).thenReturn(Optional.of(required));

        assertThatThrownBy(() -> service.updateEnabled("ohlcv_bars", false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");

        verify(repo, never()).save(any());
    }

    @Test
    void updateEnabled_unknownKeyThrows() {
        when(repo.findByParameterKey("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateEnabled("nope", true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown parameter key");
    }

    @Test
    void updateEnabled_optionalParameterIsToggledAndSaved() {
        AiParameterSetting optional = aSetting("news_sentiment", true, false, "News", 130);
        when(repo.findByParameterKey("news_sentiment")).thenReturn(Optional.of(optional));
        // After update, the service re-reads the full map for the ML sync.
        when(repo.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of(optional));
        when(repo.save(any(AiParameterSetting.class))).thenAnswer(inv -> inv.getArgument(0));

        AiParameterSetting saved = service.updateEnabled("news_sentiment", false, null);

        assertThat(saved.isEnabled()).isFalse();
        verify(repo).save(any(AiParameterSetting.class));
    }

    @Test
    void updateEnabled_noopWhenStateUnchanged() {
        AiParameterSetting optional = aSetting("news_sentiment", true, false, "News", 130);
        when(repo.findByParameterKey("news_sentiment")).thenReturn(Optional.of(optional));

        AiParameterSetting result = service.updateEnabled("news_sentiment", true, null);

        assertThat(result.isEnabled()).isTrue();
        verify(repo, never()).save(any());
    }

    @Test
    void syncOnStartup_emptyRepo_doesNotThrow() {
        when(repo.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of());
        // Should swallow gracefully and log a notice — no exception.
        service.syncOnStartup();
    }

    private static AiParameterSetting aSetting(String key, boolean enabled, boolean required, String displayName, int order) {
        return AiParameterSetting.builder()
                .parameterKey(key)
                .enabled(enabled)
                .required(required)
                .displayName(displayName)
                .description("desc")
                .sortOrder(order)
                .build();
    }
}
