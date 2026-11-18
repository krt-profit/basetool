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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Finds the materials a second UEX listing of a commodity keeps beside the material holding the
 * commodity's P4K GUID (REQ-DATA-046, {@link MaterialMergeRule#UEX_WIKI_KEY}). The material that
 * stays is the only material whose stored {@code p4k_subtype_uuid} is the twin's SC Wiki UUID, and
 * the fetch lists both UEX commodity ids.
 */
final class UexCommodityTwins {

  private UexCommodityTwins() {}

  /**
   * A material to merge and the material it merges into.
   *
   * @param twinId the material a second UEX listing keeps
   * @param survivorId the material holding the P4K GUID
   * @param rule the evidence
   */
  record Twin(@NotNull UUID twinId, @NotNull UUID survivorId, @NotNull MaterialMergeRule rule) {}

  /**
   * Finds every twin among the stored materials.
   *
   * @param materials every stored material
   * @param listed the UEX commodity ids of a complete fetch
   * @return the twins with the materials they merge into
   */
  @NotNull
  @Unmodifiable
  static List<Twin> find(@NotNull Collection<Material> materials, @NotNull Set<Integer> listed) {
    Map<UUID, List<Material>> byResourceType =
        materials.stream()
            .filter(row -> row.getP4kSubtypeUuid() != null)
            .collect(Collectors.groupingBy(Material::getP4kSubtypeUuid));
    List<Twin> twins = new ArrayList<>();
    for (Material twin : materials) {
      UUID wikiKey = twin.getScwikiUuid();
      if (wikiKey == null || !isListed(twin, listed)) {
        continue;
      }
      List<Material> holders =
          byResourceType.getOrDefault(wikiKey, List.of()).stream()
              .filter(row -> !Objects.equals(row.getId(), twin.getId()))
              .toList();
      if (holders.size() == 1) {
        Material survivor = holders.getFirst();
        if (isListed(survivor, listed) && MaterialMergeRule.UEX_WIKI_KEY.holds(twin, survivor)) {
          twins.add(
              new Twin(
                  Objects.requireNonNull(twin.getId()),
                  Objects.requireNonNull(survivor.getId()),
                  MaterialMergeRule.UEX_WIKI_KEY));
        }
      }
    }
    return List.copyOf(twins);
  }

  /**
   * Tells whether the fetch lists a material's UEX commodity id.
   *
   * @param row the material
   * @param listed the fetched UEX commodity ids
   * @return whether the material holds a listed id
   */
  private static boolean isListed(@NotNull Material row, @NotNull Set<Integer> listed) {
    Integer uexId = row.getIdCommodity();
    return uexId != null && listed.contains(uexId);
  }
}
