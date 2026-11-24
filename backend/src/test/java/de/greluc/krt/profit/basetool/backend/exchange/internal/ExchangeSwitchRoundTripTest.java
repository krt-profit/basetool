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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.testsupport.exchange.ExchangeSeam;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every exchange switch travels alone from the settings row into the registry snapshot and through
 * the mirror document under its own member and back (REQ-XCH-003, REQ-BXCH-006, REQ-OXCH-009). The
 * expected settings field and mirror member of each switch are written out here, so a new switch
 * without its wiring fails to compile or fails a case.
 */
class ExchangeSwitchRoundTripTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @ParameterizedTest
  @EnumSource(ExchangeSwitch.class)
  void aSwitchOnInTheSettingsRowIsTheOnlyOneOnInTheSnapshot(@NotNull ExchangeSwitch only) {
    ExchangeRegistrySnapshot snapshot = ExchangeRegistrySnapshot.of(onlyOn(only), List.of());

    assertThat(snapshot.switchedOn()).containsExactly(only);
    assertThat(snapshot.isOn(only)).isTrue();
  }

  @ParameterizedTest
  @EnumSource(ExchangeSwitch.class)
  void aSwitchCrossesTheMirrorDocumentUnderItsOwnMemberAndBack(@NotNull ExchangeSwitch only) {
    AtomicReference<String> stored = new AtomicReference<>();
    RedisExchangeRegistryMirror mirror =
        new RedisExchangeRegistryMirror(redis(stored), "k", clock());
    ExchangeRegistrySnapshot written =
        new ExchangeRegistrySnapshot(EnumSet.of(only), new TreeMap<>());

    mirror.write(written, 7L);

    JsonNode document = MAPPER.readTree(stored.get());
    assertThat(ExchangeSeam.REGISTRY_DOCUMENT_FIELDS).contains(mirrorMember(only));
    for (ExchangeSwitch any : ExchangeSwitch.values()) {
      assertThat(document.path(mirrorMember(any)).isBoolean())
          .as("the document carries %s", mirrorMember(any))
          .isTrue();
      assertThat(document.path(mirrorMember(any)).booleanValue())
          .as("%s with only %s on", mirrorMember(any), only)
          .isEqualTo(any == only);
    }
    assertThat(mirror.read()).contains(written);
  }

  /**
   * Builds a stored settings row with only one switch on.
   *
   * @param on the switch to turn on
   * @return the row
   */
  private static @NotNull ExchangeSettings onlyOn(@NotNull ExchangeSwitch on) {
    ExchangeSettings settings = new ExchangeSettings();
    settings.setVersion(0L);
    switch (on) {
      case GLOBAL -> settings.setEnabled(true);
      case BANK -> settings.setBankEnabled(true);
      case JOB_ORDERS -> settings.setJobOrdersEnabled(true);
      case JOB_ORDER_WRITES -> settings.setJobOrderWritesEnabled(true);
      case null -> throw new IllegalArgumentException("A switch is named");
    }
    return settings;
  }

  /**
   * Names the mirror document member the gateway reads a switch from.
   *
   * @param target the switch
   * @return the member
   */
  private static @NotNull String mirrorMember(@NotNull ExchangeSwitch target) {
    return switch (target) {
      case GLOBAL -> "enabled";
      case BANK -> "bankEnabled";
      case JOB_ORDERS -> "jobOrdersEnabled";
      case JOB_ORDER_WRITES -> "jobOrderWritesEnabled";
    };
  }

  /**
   * Builds a Redis template whose one value lives in {@code stored}.
   *
   * @param stored holds the value written
   * @return the template
   */
  private static @NotNull StringRedisTemplate redis(@NotNull AtomicReference<String> stored) {
    StringRedisTemplate template = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = mock();
    when(template.opsForValue()).thenReturn(values);
    doAnswer(
            invocation -> {
              stored.set(invocation.getArgument(1));
              return null;
            })
        .when(values)
        .set(anyString(), anyString());
    when(values.get(anyString())).thenAnswer(_ -> stored.get());
    return template;
  }

  /**
   * A fixed clock for {@code writtenAt}.
   *
   * @return the clock
   */
  private static @NotNull Clock clock() {
    return Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC);
  }
}
