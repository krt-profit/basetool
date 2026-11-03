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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Reads where a material can be found (REQ-DATA-031). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaterialOccurrenceService {

  /** Lists by kind in declaration order, then by name, nameless locations last. */
  private static final Comparator<MaterialOccurrenceDto> ORDER =
      Comparator.comparing(MaterialOccurrenceDto::kind)
          .thenComparing(
              MaterialOccurrenceDto::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));

  private final MaterialOccurrenceRepository occurrenceRepository;

  /**
   * Lists the locations UEX names for a material, ordered by kind and then by name.
   *
   * @param materialId the local material id
   * @return the material's occurrences; empty for a material without any and for an unknown id
   */
  @NotNull
  public List<MaterialOccurrenceDto> getOccurrences(@NotNull UUID materialId) {
    return occurrenceRepository.findWithLocationsByMaterialId(materialId).stream()
        .map(MaterialOccurrenceService::toDto)
        .sorted(ORDER)
        .toList();
  }

  /**
   * Maps an occurrence whose location reference is fetched to its read DTO.
   *
   * @param occurrence the occurrence, its location reference initialised
   * @return the DTO naming the location and where it lies
   */
  @NotNull
  static MaterialOccurrenceDto toDto(@NotNull MaterialOccurrence occurrence) {
    MaterialOccurrenceTarget target = occurrence.target();
    UUID id = target.locationId();
    MaterialOccurrenceKind kind = target.kind();
    return switch (kind) {
      case STAR_SYSTEM -> {
        StarSystem system = Objects.requireNonNull(occurrence.getStarSystem());
        yield new MaterialOccurrenceDto(id, kind, system.getName(), null, null);
      }
      case PLANET -> {
        Planet planet = Objects.requireNonNull(occurrence.getPlanet());
        yield new MaterialOccurrenceDto(
            id, kind, planet.getName(), planet.getStarSystemName(), null);
      }
      case MOON -> {
        Moon moon = Objects.requireNonNull(occurrence.getMoon());
        yield new MaterialOccurrenceDto(
            id, kind, moon.getName(), moon.getStarSystemName(), moon.getPlanetName());
      }
      case ORBIT -> {
        Orbit orbit = Objects.requireNonNull(occurrence.getOrbit());
        yield new MaterialOccurrenceDto(id, kind, orbit.getName(), orbit.getStarSystemName(), null);
      }
      case POI -> {
        Poi poi = Objects.requireNonNull(occurrence.getPoi());
        yield new MaterialOccurrenceDto(
            id,
            kind,
            poi.getName(),
            poi.getStarSystemName(),
            firstWithText(poi.getMoonName(), poi.getPlanetName(), poi.getOrbitName()));
      }
    };
  }

  /**
   * Picks the most specific body a point of interest belongs to.
   *
   * @param candidates the names from most to least specific
   * @return the first name with text, or {@code null} when none has any
   */
  @Nullable
  private static String firstWithText(@Nullable String... candidates) {
    return Stream.of(candidates).filter(StringUtils::hasText).findFirst().orElse(null);
  }
}
