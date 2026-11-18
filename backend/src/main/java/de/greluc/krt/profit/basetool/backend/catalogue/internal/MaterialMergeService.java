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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialReferenceParticipant;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merges a material into the material of the same commodity (REQ-DATA-046): both rows are locked,
 * every module's references and the catalogue's own move onto the material that stays, which takes
 * the merged row's SC Wiki identity, and the merged id stays resolvable through a redirect that
 * also keeps its UEX commodity id. Both rows and their prices are kept as JSON.
 */
@Service
@RequiredArgsConstructor
class MaterialMergeService {

  private final MaterialRepository materials;
  private final MaterialRedirectRepository redirects;
  private final CatalogueMaterialReassignmentRepository catalogue;
  private final List<MaterialReferenceParticipant> participants;
  private final P4kReviewCaseRepository reviewCases;
  private final AuditRecorder auditRecorder;

  /**
   * Merges one material into another inside the caller's transaction.
   *
   * @param retiredId the material to merge away
   * @param survivorId the material that stays
   * @param rule the evidence that both stand for one commodity, read again under the row locks
   * @return whether the merge happened; {@code false} when a row is gone or the evidence no longer
   *     holds
   */
  @Transactional(propagation = Propagation.MANDATORY)
  boolean merge(
      @NotNull UUID retiredId, @NotNull UUID survivorId, @NotNull MaterialMergeRule rule) {
    if (retiredId.equals(survivorId)) {
      return false;
    }
    List<Material> locked = materials.lockMergeRows(List.of(retiredId, survivorId));
    Material retired = find(locked, retiredId);
    Material survivor = find(locked, survivorId);
    if (retired == null || survivor == null || !rule.holds(retired, survivor)) {
      return false;
    }
    final String snapshot = redirects.snapshot(retiredId, survivorId);
    int references = 0;
    for (MaterialReferenceParticipant participant : participants) {
      references += participant.reassignMaterial(retiredId, survivorId);
    }
    references += moveCatalogueRows(retiredId, survivorId);
    redirects.repoint(retiredId, survivorId);
    reviewCases.repointOpenReferences(P4kKind.COMMODITY, retiredId, survivorId, null);
    MaterialMergeIdentity identity = MaterialMergeIdentity.of(retired);
    final Integer uexCommodityId = retired.getIdCommodity();
    materials.delete(retired);
    materials.flush();
    identity.giveTo(survivor);
    materials.saveAndFlush(survivor);
    redirects.alias(retiredId, survivorId, snapshot, uexCommodityId);
    auditRecorder.record(
        AuditEventType.CATALOGUE_MATERIAL_MERGED,
        survivorId,
        survivor.getName(),
        null,
        AuditDetails.of("retiredMaterial", retiredId)
            .with("rule", rule)
            .with("uexCommodityId", uexCommodityId)
            .with("references", references));
    return true;
  }

  /**
   * Moves the catalogue's own rows; a price, yield or location both materials hold for the same
   * terminal or place stays with the material that stays.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the number of rows moved
   */
  private int moveCatalogueRows(@NotNull UUID retiredId, @NotNull UUID survivorId) {
    catalogue.dropCollidingPrices(retiredId, survivorId);
    catalogue.dropCollidingYields(retiredId, survivorId);
    catalogue.dropCollidingOccurrences(retiredId, survivorId);
    return catalogue.ingredients(retiredId, survivorId)
        + catalogue.dismantleReturns(retiredId, survivorId)
        + catalogue.yields(retiredId, survivorId)
        + catalogue.prices(retiredId, survivorId)
        + catalogue.aliases(retiredId, survivorId)
        + catalogue.refinedFrom(retiredId, survivorId)
        + catalogue.occurrences(retiredId, survivorId);
  }

  /**
   * Picks a row out of the locked ones.
   *
   * @param locked the locked rows
   * @param id the row's id
   * @return the row, or {@code null} when it is gone
   */
  @Nullable
  private static Material find(@NotNull List<Material> locked, @NotNull UUID id) {
    return locked.stream().filter(row -> id.equals(row.getId())).findFirst().orElse(null);
  }
}
