/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.server.PathContainer;

/** Tests the parsing and matching rules of the retired-operation list (REQ-API-020). */
class RetiredOperationsTest {

  @Test
  @DisplayName("an entry matches its exact verb and path, any value in a placeholder")
  void matchesExactVerbAndPath() {
    RetiredOperations retired =
        RetiredOperations.parse(List.of("", "  GET /api/v1/old/{id}/things  ", ""));

    assertThat(retired.entries()).hasSize(1);
    assertThat(retired.match("GET", path("/api/v1/old/42/things"))).isPresent();
    assertThat(retired.match("GET", path("/api/v1/old/anything-at-all/things"))).isPresent();
    assertThat(retired.match("POST", path("/api/v1/old/42/things"))).isEmpty();
    assertThat(retired.match("GET", path("/api/v1/old/42/things/more"))).isEmpty();
    assertThat(retired.match("GET", path("/api/v1/old/42"))).isEmpty();
    assertThat(retired.match("GET", path("/api/v1/old/4/2/things"))).isEmpty();
  }

  @Test
  @DisplayName("the empty list matches nothing")
  void theEmptyListMatchesNothing() {
    assertThat(RetiredOperations.parse(List.of()).isEmpty()).isTrue();
    assertThat(RetiredOperations.none().match("GET", path("/api/v1/anything"))).isEmpty();
  }

  /**
   * A malformed, wildcard, out-of-scope or T0 entry is refused.
   *
   * @param line the refused line
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/no-verb",
        "GET api/v1/relative",
        "GET  /api/v1/two-spaces",
        "TRACE /api/v1/old",
        "HEAD /api/v1/old",
        "GET /api/v1/old/*",
        "GET /api/v1/old/**",
        "GET /api/v1/old/{*rest}",
        "GET /api/v1/old?x=1",
        "GET /api/v1/old/",
        "GET /internal/discord/account-existence",
        "GET /actuator/health",
        "GET /api/v1/app/version-policy",
        "POST /api/v1/exchange/v1/stock",
        "GET /api/v1/live-sync/stream",
        "POST /api/v1/live-sync/changed",
        "GET /api/v1/notifications/stream"
      })
  @DisplayName("a malformed, wildcard, out-of-scope or T0 entry is refused")
  void refusesALine(String line) {
    assertThatIllegalArgumentException().isThrownBy(() -> RetiredOperations.parse(List.of(line)));
  }

  @Test
  @DisplayName("a duplicate entry is refused, whatever its placeholder names")
  void refusesADuplicate() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                RetiredOperations.parse(List.of("GET /api/v1/old/{id}", "GET /api/v1/old/{key}")));
  }

  @Test
  @DisplayName("a lower-case verb is normalised")
  void normalisesTheVerb() {
    RetiredOperations retired = RetiredOperations.parse(List.of("delete /api/v1/old/{id}"));
    assertThat(retired.entries().getFirst().method()).isEqualTo("DELETE");
    assertThat(retired.entries().getFirst()).hasToString("DELETE /api/v1/old/{id}");
  }

  @Test
  @DisplayName("the committed list loads")
  void theCommittedListLoads() throws Exception {
    assertThat(new RetiredOperationsConfig().retiredOperations()).isNotNull();
  }

  /**
   * Parses a request path.
   *
   * @param value the path
   * @return the parsed path
   */
  private static PathContainer path(String value) {
    return PathContainer.parsePath(value);
  }
}
