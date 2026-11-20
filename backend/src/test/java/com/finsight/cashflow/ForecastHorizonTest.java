package com.finsight.cashflow;

import com.finsight.cashflow.domain.ForecastHorizon;
import com.finsight.shared.exception.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link ForecastHorizon#parse} (D-11). */
class ForecastHorizonTest {

    @Test
    @DisplayName("Absent or blank horizon means 30 days")
    void absent_default30() {
        assertThat(ForecastHorizon.parse(null)).isEqualTo(ForecastHorizon.D30);
        assertThat(ForecastHorizon.parse("  ")).isEqualTo(ForecastHorizon.D30);
    }

    @Test
    @DisplayName("30, 60 and 90 are accepted")
    void validValues() {
        assertThat(ForecastHorizon.parse("30").days()).isEqualTo(30);
        assertThat(ForecastHorizon.parse("60").days()).isEqualTo(60);
        assertThat(ForecastHorizon.parse("90").days()).isEqualTo(90);
    }

    @ParameterizedTest(name = "horizon={0}")
    @ValueSource(strings = {"0", "29", "31", "45", "120", "-30", "030", "30.0", "thirty", "30d"})
    @DisplayName("Any other horizon is 400")
    void invalidValues(String value) {
        assertThatThrownBy(() -> ForecastHorizon.parse(value)).isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("The error message does not echo the submitted value")
    void messageDoesNotEcho() {
        assertThatThrownBy(() -> ForecastHorizon.parse("<script>"))
                .isInstanceOf(BadRequestException.class)
                .message().doesNotContain("<script>");
    }
}
