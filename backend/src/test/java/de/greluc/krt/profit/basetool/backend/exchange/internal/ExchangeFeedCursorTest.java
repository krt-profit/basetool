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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class ExchangeFeedCursorTest {

  @Test
  void aSnapshotPositionSurvivesTheRoundTrip() {
    UUID id = UUID.randomUUID();
    ExchangeFeedCursor cursor =
        ExchangeFeedCursor.parse(
            ExchangeFeedCursor.snapshot(new ExchangeFeedPosition(7000, 0), id).format());

    assertThat(cursor.isSnapshot()).isTrue();
    assertThat(cursor.position()).isEqualTo(new ExchangeFeedPosition(7000, 0));
    assertThat(cursor.afterId()).isEqualTo(id);
  }

  @Test
  void aFeedPositionSurvivesTheRoundTrip() {
    ExchangeFeedCursor cursor = ExchangeFeedCursor.parse("f1.7000.42");

    assertThat(cursor.isSnapshot()).isFalse();
    assertThat(cursor.position()).isEqualTo(new ExchangeFeedPosition(7000, 42));
    assertThat(cursor.format()).isEqualTo("f1.7000.42");
  }

  @Test
  void positionsOrderByTransactionFirst() {
    assertThat(new ExchangeFeedPosition(5, 900).isBefore(new ExchangeFeedPosition(6, 1))).isTrue();
    assertThat(new ExchangeFeedPosition(6, 1).isBefore(new ExchangeFeedPosition(6, 2))).isTrue();
    assertThat(new ExchangeFeedPosition(6, 2).isBefore(new ExchangeFeedPosition(6, 2))).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "f1.",
        "f1.5",
        "f1.5.",
        "f1.-1.0",
        "f2.5.0",
        "f1.99999999999999999999.0",
        "f1.9999999999999999999.0",
        "f1.5.9999999999999999999",
        "s1.5.0",
        "s1.5.0.not-a-uuid",
        "s1.5.0.00000000-0000-0000-0000-00000000000G",
        " f1.5.0"
      })
  void aValueTheServerDidNotIssueHasExpired(String value) {
    assertThatThrownBy(() -> ExchangeFeedCursor.parse(value))
        .isInstanceOfSatisfying(
            ExchangeProblemException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.GONE);
              assertThat(e.code()).isEqualTo(ExchangeProblemException.CURSOR_EXPIRED);
            });
  }

  @Test
  void aShortProductKeyIsItsOwnKeyAndALongOneIsHashed() {
    String longKey = "x".repeat(129);

    assertThat(ExchangeBlueprintFeedService.keyOf("arrowhead")).isEqualTo("arrowhead");
    assertThat(ExchangeBlueprintFeedService.keyOf("x".repeat(128))).hasSize(128);
    assertThat(ExchangeBlueprintFeedService.keyOf(longKey))
        .startsWith("h:")
        .hasSize(66)
        .isEqualTo(ExchangeBlueprintFeedService.keyOf(longKey));
  }
}
