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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.model.dto.PromotionCategoryDto;
import java.io.StringWriter;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.thymeleaf.standard.serializer.IStandardJavaScriptSerializer;

/**
 * Tests {@link ThymeleafJavaScriptSerializerConfig.JavaTimeAwareJavaScriptSerializer}: nested DTOs
 * with {@link Instant} timestamps serialize, and the XSS-protection escapes stay in place.
 */
class ThymeleafJavaScriptSerializerConfigTest {

  private final IStandardJavaScriptSerializer serializer =
      new ThymeleafJavaScriptSerializerConfig.JavaTimeAwareJavaScriptSerializer();

  @Test
  void serializeInstant_rendersAsIso8601() {
    StringWriter writer = new StringWriter();
    serializer.serializeValue(Instant.parse("2026-05-15T13:45:30Z"), writer);
    assertThat(writer.toString()).isEqualTo("\"2026-05-15T13:45:30Z\"");
  }

  @Test
  void serializeOffsetDateTime_rendersAsIso8601() {
    StringWriter writer = new StringWriter();
    OffsetDateTime value = OffsetDateTime.of(2026, 5, 15, 13, 45, 30, 0, ZoneOffset.UTC);
    serializer.serializeValue(value, writer);
    assertThat(writer.toString()).contains("2026-05-15T13:45:30");
  }

  @Test
  void serializeLocalDateTime_rendersAsIso8601() {
    StringWriter writer = new StringWriter();
    LocalDateTime value = LocalDateTime.of(2026, 5, 15, 13, 45, 30);
    serializer.serializeValue(value, writer);
    assertThat(writer.toString()).contains("2026-05-15T13:45:30");
  }

  @Test
  void serializeDtoWithInstantField_doesNotThrowAndContainsTimestamp() {
    PromotionCategoryDto dto =
        new PromotionCategoryDto(
            UUID.randomUUID(),
            1L,
            UUID.randomUUID(),
            "Topic",
            "Cat",
            "Desc",
            1,
            Instant.parse("2026-05-15T13:45:30Z"),
            Instant.parse("2026-05-15T13:45:31Z"));

    StringWriter writer = new StringWriter();
    serializer.serializeValue(dto, writer);

    assertThat(writer.toString())
        .contains("\"createdAt\":\"2026-05-15T13:45:30Z\"")
        .contains("\"updatedAt\":\"2026-05-15T13:45:31Z\"");
  }

  @Test
  void serializeMapOfListsOfDtos_reproducesOriginalFailureShapeWithoutThrowing() {
    UUID topicId = UUID.randomUUID();
    Map<String, List<PromotionCategoryDto>> categoriesByTopic =
        Map.of(
            topicId.toString(),
            List.of(
                new PromotionCategoryDto(
                    UUID.randomUUID(),
                    1L,
                    topicId,
                    "Topic",
                    "Cat",
                    "Desc",
                    1,
                    Instant.parse("2026-05-15T13:45:30Z"),
                    Instant.parse("2026-05-15T13:45:30Z"))));

    StringWriter writer = new StringWriter();
    serializer.serializeValue(categoriesByTopic, writer);

    assertThat(writer.toString()).contains("2026-05-15T13:45:30Z").contains(topicId.toString());
  }

  @Test
  void serializeStringWithSpecialChars_escapesContextBreakingSequences() {
    StringWriter writer = new StringWriter();
    serializer.serializeValue("<script>alert('xss')&\"/test\"</script>", writer);

    String result = writer.toString();
    assertThat(result).doesNotContain("</script>");
    assertThat(result).contains("\\/");
    assertThat(result).containsIgnoringCase("\\u0026");
    assertThat(result).contains("\\\"");
  }

  @Test
  void serializeStringWithUnicodeLineSeparators_escapesU2028AndU2029() {
    StringWriter writer = new StringWriter();
    serializer.serializeValue("a b c", writer);

    String result = writer.toString();
    assertThat(result).containsIgnoringCase("\\u2028").containsIgnoringCase("\\u2029");
  }
}
