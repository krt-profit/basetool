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

package de.greluc.krt.profit.basetool.backend.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/** Verifies the length cap the JSON string deserializer derives from a property's declaration. */
class NormalizedStringDeserializerTest {

  private static final int DEFAULT_CAP = StringNormalization.MAX_FREE_TEXT_LENGTH;

  private final JsonMapper mapper =
      JsonMapper.builder()
          .addModule(
              new SimpleModule().addDeserializer(String.class, new NormalizedStringDeserializer()))
          .build();

  /**
   * A string property without a declared maximum.
   *
   * @param text the value
   */
  record Plain(String text) {}

  /**
   * A string property declaring a maximum above the default cap.
   *
   * @param text the value
   */
  record LongText(@Size(max = 20_000) String text) {}

  /**
   * A string property declaring a maximum below the default cap.
   *
   * @param text the value
   */
  record ShortText(@Size(max = 255) String text) {}

  /**
   * A string property declaring only a minimum.
   *
   * @param text the value
   */
  record MinimumOnly(@Size(min = 1) String text) {}

  /**
   * A list property whose declared maximum bounds its element count.
   *
   * @param texts the values
   */
  record Texts(@Size(max = 20_000) List<String> texts) {}

  @Test
  void aStringWithoutADeclaredMaximumKeepsTheDefaultCap() {
    assertThat(read(Plain.class, DEFAULT_CAP).text()).hasSize(DEFAULT_CAP);
    assertRejected(Plain.class, DEFAULT_CAP + 1);
  }

  @Test
  void aLargerDeclaredMaximumRaisesTheCapToThatMaximum() {
    assertThat(read(LongText.class, 15_000).text()).hasSize(15_000);
    assertThat(read(LongText.class, 20_000).text()).hasSize(20_000);
    assertRejected(LongText.class, 20_001);
  }

  @Test
  void aSmallerOrMissingDeclaredMaximumLeavesTheDefaultCap() {
    assertThat(read(ShortText.class, DEFAULT_CAP).text()).hasSize(DEFAULT_CAP);
    assertRejected(ShortText.class, DEFAULT_CAP + 1);
    assertRejected(MinimumOnly.class, DEFAULT_CAP + 1);
  }

  @Test
  void aContainersDeclaredMaximumDoesNotRaiseItsElementsCap() {
    String json = "{\"texts\":[\"" + "x".repeat(DEFAULT_CAP + 1) + "\"]}";

    assertThatThrownBy(() -> mapper.readValue(json, Texts.class))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aRaisedCapStillTrimsAndNormalizes() {
    LongText value =
        mapper.readValue("{\"text\":\"  e\\u0301" + "x".repeat(9_000) + "  \"}", LongText.class);

    assertThat(value.text()).startsWith("é").hasSize(9_001);
  }

  private <T> T read(Class<T> type, int length) {
    return mapper.readValue(json(length), type);
  }

  private void assertRejected(Class<?> type, int length) {
    assertThatThrownBy(() -> mapper.readValue(json(length), type))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }

  private static String json(int length) {
    return "{\"text\":\"" + "x".repeat(length) + "\"}";
  }
}
