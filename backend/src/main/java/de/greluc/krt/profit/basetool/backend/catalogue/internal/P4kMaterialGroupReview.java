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
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialCategory;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The materials that end an APPLY without a resource group (REQ-P4K-025, ADR-0339): each live one
 * the game files know opens a {@code NO_RESOURCE_GROUP} case, which the admin resolves by picking
 * an existing group, and a case whose material gained a group or that the game files lack is closed
 * by the run (REQ-P4K-074). Once no material references an admin category any longer, the APPLY
 * deletes it.
 */
@Component
@RequiredArgsConstructor
public class P4kMaterialGroupReview {

  /** The owner prefix of the case GUIDs. */
  private static final String CASE_GUID_PREFIX = "material-group:";

  /** The reason this component owns. */
  private static final List<P4kReviewReason> REASONS = List.of(P4kReviewReason.NO_RESOURCE_GROUP);

  /** The live materials whose category is none or an admin category. */
  private static final String UNGROUPED =
      "SELECT m FROM Material m LEFT JOIN m.category c WHERE (c IS NULL OR c.p4kUuid IS NULL)"
          + " AND m.p4kDeletedAt IS NULL AND m.p4kHiddenAt IS NULL";

  /** The admin categories no material references. */
  private static final String UNREFERENCED_ADMIN_CATEGORIES =
      "SELECT c FROM MaterialCategory c WHERE c.p4kUuid IS NULL"
          + " AND NOT EXISTS (SELECT 1 FROM Material m WHERE m.category = c)";

  private final EntityManager entityManager;
  private final P4kRunResolvedCases runResolvedCases;

  /**
   * Opens a case for every live material the game files know that has no resource group after the
   * run and, on APPLY, closes the other cases and deletes the unreferenced admin categories.
   *
   * @param outcome the commodities' outcome: the materials the run gives a resource group, the ones
   *     the game files know and the ones it creates without a group, which an unwritten preview
   *     does not show yet
   * @param context the run
   * @return the admin categories deleted, {@code 0} in a preview
   */
  public int review(
      @NotNull P4kCommodityReconciler.Outcome outcome, @NotNull P4kImportContext context) {
    Set<UUID> vanished =
        context.isApply() ? Set.of() : context.getReport().vanishedGuids(P4kKind.COMMODITY);
    List<Material> ungrouped = new ArrayList<>();
    for (Material material : entityManager.createQuery(UNGROUPED, Material.class).getResultList()) {
      UUID guid = material.getP4kUuid();
      if (!outcome.grouped().contains(material.getId())
          && outcome.known().contains(material.getId())
          && (guid == null || !vanished.contains(guid))) {
        ungrouped.add(material);
      }
    }
    outcome.createdWithoutGroup().stream().filter(m -> m.getId() == null).forEach(ungrouped::add);
    ungrouped.sort(Comparator.comparing(Material::getName, String.CASE_INSENSITIVE_ORDER));
    Set<P4kRunResolvedCases.Found> found = new HashSet<>();
    for (Material material : ungrouped) {
      P4kMaterialRecord record = new P4kMaterialRecord(material);
      P4kRunResolvedCases.Found finding =
          new P4kRunResolvedCases.Found(record.caseGuid(), P4kReviewReason.NO_RESOURCE_GROUP);
      found.add(finding);
      context
          .getReport()
          .review(
              P4kKind.COMMODITY,
              record,
              record.caseGuid(),
              P4kReviewReason.NO_RESOURCE_GROUP,
              material.getId());
    }
    if (!context.isApply()) {
      return 0;
    }
    runResolvedCases.settle(P4kKind.COMMODITY, REASONS, found, context.getNow());
    List<MaterialCategory> retired =
        entityManager
            .createQuery(UNREFERENCED_ADMIN_CATEGORIES, MaterialCategory.class)
            .getResultList();
    retired.forEach(entityManager::remove);
    return retired.size();
  }

  /**
   * Derives the stable GUID of a material's case from its unique name, so a preview names the case
   * of a material it would create the way the APPLY does.
   *
   * @param materialName the material's name
   * @return a name-based UUID of the lower-cased name
   */
  @NotNull
  static UUID caseGuid(@NotNull String materialName) {
    return P4kRunResolvedCases.caseGuid(CASE_GUID_PREFIX + materialName.toLowerCase(Locale.ROOT));
  }
}
