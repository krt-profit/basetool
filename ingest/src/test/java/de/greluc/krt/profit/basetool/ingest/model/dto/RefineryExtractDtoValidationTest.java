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

package de.greluc.krt.profit.basetool.ingest.model.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pins the edge validation of the frozen {@code RefineryExtract} contract v1 (ADR-0008). The
 * gateway rejects a malformed envelope <em>before</em> the backend relay, so these constraints are
 * what keeps a hostile or buggy extractor from reaching the import endpoint at all — and the nested
 * {@code @Valid} cascade is the part that silently stops working if a wrapper annotation is
 * dropped.
 */
class RefineryExtractDtoValidationTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  private static RefineryExtractGoodDto good() {
    return new RefineryExtractGoodDto(
        0, "Quantanium", 100, 500, 420, Boolean.TRUE, 0.95d, "cap.png");
  }

  private static RefineryExtractImageDto image() {
    return new RefineryExtractImageDto("capture.png", 2560, 1440, "PANEL", Instant.EPOCH);
  }

  private static RefineryExtractOrderDto order(
      List<RefineryExtractImageDto> images, List<RefineryExtractGoodDto> goods) {
    return new RefineryExtractOrderDto(
        "SETUP",
        Boolean.TRUE,
        0.9d,
        "ARC-L1",
        "Dinyx Solventation",
        1_000L,
        900L,
        1234.5d,
        90L,
        12.5d,
        images,
        goods);
  }

  private static RefineryExtractDto extract(List<RefineryExtractOrderDto> orders) {
    return new RefineryExtractDto(
        1, "krt-extractor", "1.2.3", "vlm-1", Instant.EPOCH, "de-DE", orders);
  }

  @Test
  void acceptsAFullyPopulatedExtract() {
    RefineryExtractDto dto = extract(List.of(order(List.of(image()), List.of(good()))));

    assertThat(validator.validate(dto)).isEmpty();
  }

  @Test
  void exposesTheImageProvenanceItCarries() {
    // Image bytes never leave the user's machine (ADR-0007) — only this metadata travels, and the
    // backend derives the order start time from capturedAt.
    RefineryExtractImageDto image = image();

    assertThat(image.name()).isEqualTo("capture.png");
    assertThat(image.width()).isEqualTo(2560);
    assertThat(image.height()).isEqualTo(1440);
    assertThat(image.cropMode()).isEqualTo("PANEL");
    assertThat(image.capturedAt()).isEqualTo(Instant.EPOCH);
  }

  @Test
  void rejectsAMissingSchemaVersion() {
    RefineryExtractDto dto =
        new RefineryExtractDto(
            null,
            "krt-extractor",
            "1.2.3",
            "vlm-1",
            Instant.EPOCH,
            "de-DE",
            List.of(order(List.of(), List.of(good()))));

    assertThat(validator.validate(dto))
        .anyMatch(violation -> violation.getPropertyPath().toString().equals("schemaVersion"));
  }

  @Test
  void rejectsAnEmptyOrderList() {
    assertThat(validator.validate(extract(List.of())))
        .anyMatch(violation -> violation.getPropertyPath().toString().equals("orders"));
  }

  @Test
  void rejectsMoreThanFiveOrders() {
    RefineryExtractOrderDto order = order(List.of(), List.of(good()));

    assertThat(validator.validate(extract(List.of(order, order, order, order, order, order))))
        .anyMatch(violation -> violation.getPropertyPath().toString().equals("orders"));
  }

  @Test
  void cascadesIntoNestedGoods() {
    // Without the @Valid on the list element the nested constraint would silently not run.
    RefineryExtractGoodDto invalid =
        new RefineryExtractGoodDto(0, null, 100, 500, 420, Boolean.TRUE, 0.95d, null);

    assertThat(validator.validate(extract(List.of(order(List.of(), List.of(invalid))))))
        .anyMatch(
            violation ->
                violation.getPropertyPath().toString().contains("goods")
                    && violation.getPropertyPath().toString().contains("rawMaterialName"));
  }

  @Test
  void cascadesIntoNestedSourceImages() {
    RefineryExtractImageDto invalid =
        new RefineryExtractImageDto("capture.png", 0, 1440, "PANEL", Instant.EPOCH);

    assertThat(validator.validate(extract(List.of(order(List.of(invalid), List.of(good()))))))
        .anyMatch(
            violation ->
                violation.getPropertyPath().toString().contains("sourceImages")
                    && violation.getPropertyPath().toString().contains("width"));
  }

  @Test
  void rejectsAConfidenceOutsideTheUnitInterval() {
    RefineryExtractGoodDto invalid =
        new RefineryExtractGoodDto(0, "Quantanium", 100, 500, 420, Boolean.TRUE, 1.5d, null);

    assertThat(validator.validate(extract(List.of(order(List.of(), List.of(invalid))))))
        .anyMatch(violation -> violation.getPropertyPath().toString().contains("confidence"));
  }
}
