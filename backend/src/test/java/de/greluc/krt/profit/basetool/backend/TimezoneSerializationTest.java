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

package de.greluc.krt.profit.basetool.backend;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.dto.HandoverReportPreviewRequestDto;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Round-trip serialisation tests for the temporal types at the REST boundary.
 *
 * <ul>
 *   <li>{@link Instant} always renders as a UTC string ending in {@code Z}.
 *   <li>{@link OffsetDateTime} serialises with its own offset and is normalised to UTC on
 *       deserialisation.
 *   <li>{@link LocalDateTime} in {@link HandoverReportPreviewRequestDto} round-trips unchanged,
 *       without an added offset.
 *   <li>DST boundary timestamps keep their wall-clock value.
 * </ul>
 *
 * <p>The {@link JsonMapper} is configured like Spring Boot's default.
 */
class TimezoneSerializationTest {

  private JsonMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = JsonMapper.builder().disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();
  }

  @Test
  void instant_serializesAsIso8601WithZSuffix() throws Exception {
    Instant time = Instant.parse("2026-03-03T12:00:00Z");

    String json = objectMapper.writeValueAsString(time);

    assertThat(json).isEqualTo("\"2026-03-03T12:00:00Z\"");
  }

  @Test
  void instant_roundTripPreservesValue() throws Exception {
    Instant[] samples = {
      Instant.parse("1970-01-01T00:00:00Z"),
      Instant.parse("2026-03-03T12:34:56Z"),
      Instant.parse("2199-12-31T23:59:59Z"),
    };

    for (Instant original : samples) {
      String json = objectMapper.writeValueAsString(original);
      Instant parsed = objectMapper.readValue(json, Instant.class);
      assertThat(parsed).isEqualTo(original);
    }
  }

  @Test
  void instant_deserializesFromIsoStringWithNanos() throws Exception {
    String json = "\"2026-03-03T12:00:00.123456789Z\"";

    Instant parsed = objectMapper.readValue(json, Instant.class);

    assertThat(parsed.getEpochSecond())
        .isEqualTo(Instant.parse("2026-03-03T12:00:00Z").getEpochSecond());
    assertThat(parsed.getNano()).isEqualTo(123_456_789);
  }

  @Test
  void instant_deserializesFromOffsetSuffix() throws Exception {
    String json = "\"2026-03-03T14:00:00+02:00\"";

    Instant parsed = objectMapper.readValue(json, Instant.class);

    assertThat(parsed).isEqualTo(Instant.parse("2026-03-03T12:00:00Z"));
  }

  @Test
  void offsetDateTime_serialisesPositiveOffsetVerbatim() throws Exception {
    OffsetDateTime original = OffsetDateTime.of(2026, 3, 3, 14, 0, 0, 0, ZoneOffset.ofHours(2));

    String json = objectMapper.writeValueAsString(original);

    assertThat(json).contains("+02:00");
    assertThat(json).contains("2026-03-03T14:00:00");
  }

  @Test
  void offsetDateTime_serialisesNegativeOffsetVerbatim() throws Exception {
    OffsetDateTime original = OffsetDateTime.of(2026, 3, 3, 7, 0, 0, 0, ZoneOffset.ofHours(-5));

    String json = objectMapper.writeValueAsString(original);

    assertThat(json).contains("-05:00");
    assertThat(json).contains("2026-03-03T07:00:00");
  }

  @Test
  void offsetDateTime_serialisesZuluOffsetVerbatim() throws Exception {
    OffsetDateTime original = OffsetDateTime.of(2026, 3, 3, 12, 0, 0, 0, ZoneOffset.UTC);

    String json = objectMapper.writeValueAsString(original);

    assertThat(json).contains("Z");
    assertThat(json).contains("2026-03-03T12:00:00");
  }

  @Test
  void offsetDateTime_deserialiseNormalisesPositiveOffsetToUtc() throws Exception {
    String json = "\"2026-03-03T14:00:00+02:00\"";

    OffsetDateTime parsed = objectMapper.readValue(json, OffsetDateTime.class);

    assertThat(parsed.toInstant()).isEqualTo(Instant.parse("2026-03-03T12:00:00Z"));
    assertThat(parsed.getOffset()).isEqualTo(ZoneOffset.UTC);
  }

  @Test
  void offsetDateTime_deserialiseNormalisesNegativeOffsetToUtc() throws Exception {
    String json = "\"2026-03-03T07:00:00-05:00\"";

    OffsetDateTime parsed = objectMapper.readValue(json, OffsetDateTime.class);

    assertThat(parsed.toInstant()).isEqualTo(Instant.parse("2026-03-03T12:00:00Z"));
    assertThat(parsed.getOffset()).isEqualTo(ZoneOffset.UTC);
  }

  @Test
  void offsetDateTime_andInstant_representSameMoment() throws Exception {
    OffsetDateTime odt = OffsetDateTime.of(2026, 3, 3, 14, 0, 0, 0, ZoneOffset.ofHours(2));

    String json = objectMapper.writeValueAsString(odt);
    Instant asInstant = objectMapper.readValue(json, Instant.class);

    assertThat(asInstant).isEqualTo(odt.toInstant());
    assertThat(asInstant).isEqualTo(Instant.parse("2026-03-03T12:00:00Z"));
  }

  @Test
  void handoverReportPreviewDto_keepsLocalDateTimeWithoutOffsetAcrossRoundTrip() throws Exception {
    String json =
        "{"
            + "\"jobOrderNumber\":\"#42\","
            + "\"handoverTime\":\"2026-03-03T14:00:00\","
            + "\"recipientHandle\":\"HanSolo\","
            + "\"items\":[]"
            + "}";

    HandoverReportPreviewRequestDto dto =
        objectMapper.readValue(json, HandoverReportPreviewRequestDto.class);
    String reSerialised = objectMapper.writeValueAsString(dto);

    assertThat(dto.handoverTime()).isEqualTo(LocalDateTime.of(2026, 3, 3, 14, 0));
    assertThat(reSerialised)
        .contains("\"handoverTime\":\"2026-03-03T14:00:00\"")
        .doesNotContain("2026-03-03T14:00:00Z")
        .doesNotContain("2026-03-03T14:00:00+");
  }

  @Test
  void handoverReportPreviewDto_acceptsLocalDateTimeWithoutOffsetEvenWhenServerInUtc()
      throws Exception {
    HandoverReportPreviewRequestDto dto =
        new HandoverReportPreviewRequestDto(
            "#1", LocalDateTime.of(2026, 3, 3, 14, 0), "HanSolo", List.of());

    String json = objectMapper.writeValueAsString(dto);
    HandoverReportPreviewRequestDto roundTripped =
        objectMapper.readValue(json, HandoverReportPreviewRequestDto.class);

    assertThat(roundTripped.handoverTime()).isEqualTo(LocalDateTime.of(2026, 3, 3, 14, 0));
  }

  @Test
  void offsetDateTime_dstSpringForwardBoundary_preservesAbsoluteMomentInUtc() throws Exception {
    OffsetDateTime beforeDst = OffsetDateTime.of(2026, 3, 29, 1, 30, 0, 0, ZoneOffset.ofHours(1));
    OffsetDateTime afterDst = OffsetDateTime.of(2026, 3, 29, 3, 30, 0, 0, ZoneOffset.ofHours(2));

    OffsetDateTime beforeParsed =
        objectMapper.readValue(objectMapper.writeValueAsString(beforeDst), OffsetDateTime.class);
    OffsetDateTime afterParsed =
        objectMapper.readValue(objectMapper.writeValueAsString(afterDst), OffsetDateTime.class);

    assertThat(beforeParsed.toInstant()).isEqualTo(beforeDst.toInstant());
    assertThat(afterParsed.toInstant()).isEqualTo(afterDst.toInstant());

    assertThat(afterParsed.toInstant().getEpochSecond() - beforeParsed.toInstant().getEpochSecond())
        .isEqualTo(3600);
  }
}
