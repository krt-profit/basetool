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

package de.greluc.krt.profit.basetool.backend.model.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Binding test deserializing the frozen-contract example from {@code
 * docs/archive/REFINERY_SCREENSHOT_IMPORT_PLAN.md} §5 verbatim (#434) — including the 2026-06-10
 * contract amendments {@code quoted}, {@code rowIndex}, the header totals, {@code cropMode}, a
 * {@code null} {@code outputQuantity} on an un-quoted row, and the 2026-06-11 additive v1 field
 * {@code capturedAt} (REQ-REFINERY-017). A shape drift between the documented contract and these
 * records fails here before it fails in the field.
 */
class RefineryExtractDtoJsonTest {

  private static final String CONTRACT_EXAMPLE =
      """
      {
        "schemaVersion": 1,
        "tool": "basetool-sc-extractor",
        "toolVersion": "1.4.0",
        "model": "qwen3-vl:8b-instruct",
        "generatedAt": "2026-06-05T20:00:00Z",
        "clientLanguage": "en",
        "orders": [
          {
            "panelType": "SETUP",
            "quoted": true,
            "layoutConfidence": 0.92,
            "rawLocationName": "LEVSKI",
            "rawMethodName": "FERRON EXCHANGE",
            "rawInManifestTotal": 32295,
            "rawToRefineTotal": 32295,
            "expenses": 48928.00,
            "durationMinutes": 1258,
            "totalYieldScu": null,
            "sourceImages": [
              { "name": "frame_213823.png", "width": 3840, "height": 2160, "cropMode": "vlm", "capturedAt": "2026-06-05T19:38:23Z" }
            ],
            "goods": [
              {
                "rowIndex": 0,
                "rawMaterialName": "LINDINIUM (ORE)",
                "quality": 618,
                "inputQuantity": 957,
                "outputQuantity": 448,
                "refine": true,
                "confidence": 0.95,
                "sourceImage": "frame_213823.png"
              },
              {
                "rowIndex": 1,
                "rawMaterialName": "TUNGSTEN (ORE)",
                "quality": 530,
                "inputQuantity": 1431,
                "outputQuantity": null,
                "refine": true,
                "confidence": 0.91,
                "sourceImage": "frame_213823.png"
              }
            ]
          }
        ]
      }
      """;

  @Test
  void deserializesContractExampleVerbatim() throws Exception {
    // Given
    JsonMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    // When
    RefineryExtractDto extract = mapper.readValue(CONTRACT_EXAMPLE, RefineryExtractDto.class);

    // Then — envelope & provenance
    assertThat(extract.schemaVersion()).isEqualTo(1);
    assertThat(extract.tool()).isEqualTo("basetool-sc-extractor");
    assertThat(extract.model()).isEqualTo("qwen3-vl:8b-instruct");
    assertThat(extract.generatedAt()).isEqualTo(Instant.parse("2026-06-05T20:00:00Z"));
    assertThat(extract.clientLanguage()).isEqualTo("en");
    assertThat(extract.orders()).hasSize(1);

    // Then — order incl. the amended header-total and quoted fields
    RefineryExtractOrderDto order = extract.orders().getFirst();
    assertThat(order.panelType()).isEqualTo("SETUP");
    assertThat(order.quoted()).isTrue();
    assertThat(order.layoutConfidence()).isEqualTo(0.92);
    assertThat(order.rawLocationName()).isEqualTo("LEVSKI");
    assertThat(order.rawMethodName()).isEqualTo("FERRON EXCHANGE");
    assertThat(order.rawInManifestTotal()).isEqualTo(32295L);
    assertThat(order.rawToRefineTotal()).isEqualTo(32295L);
    assertThat(order.expenses()).isEqualTo(48928.0);
    assertThat(order.durationMinutes()).isEqualTo(1258L);
    assertThat(order.totalYieldScu()).isNull();
    assertThat(order.sourceImages().getFirst().name()).isEqualTo("frame_213823.png");
    assertThat(order.sourceImages().getFirst().cropMode()).isEqualTo("vlm");
    assertThat(order.sourceImages().getFirst().capturedAt())
        .isEqualTo(Instant.parse("2026-06-05T19:38:23Z"));

    // Then — goods incl. rowIndex and the nullable outputQuantity (un-quoted row)
    assertThat(order.goods()).hasSize(2);
    RefineryExtractGoodDto quoted = order.goods().getFirst();
    assertThat(quoted.rowIndex()).isZero();
    assertThat(quoted.rawMaterialName()).isEqualTo("LINDINIUM (ORE)");
    assertThat(quoted.quality()).isEqualTo(618);
    assertThat(quoted.inputQuantity()).isEqualTo(957);
    assertThat(quoted.outputQuantity()).isEqualTo(448);
    assertThat(quoted.refine()).isTrue();
    assertThat(quoted.confidence()).isEqualTo(0.95);
    RefineryExtractGoodDto unquoted = order.goods().get(1);
    assertThat(unquoted.rowIndex()).isEqualTo(1);
    assertThat(unquoted.outputQuantity()).isNull();
  }
}
