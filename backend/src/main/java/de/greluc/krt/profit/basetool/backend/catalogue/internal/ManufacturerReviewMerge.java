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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Moves every manufacturer reference and source identity to a confirmed review survivor. */
@Service
@RequiredArgsConstructor
class ManufacturerReviewMerge {

  private final ManufacturerRepository manufacturers;
  private final ManufacturerReassignmentRepository references;
  private final ManufacturerP4kAliasRepository p4kAliases;
  private final ManufacturerUexCompanyRepository uexCompanies;
  private final ManufacturerScwikiIdentityRepository wikiIdentities;
  private final P4kReviewCaseRepository reviewCases;
  private final EntityManager entityManager;

  /**
   * Reassigns both rows' item, ship and source references, then removes the duplicate.
   *
   * @param survivor the row with the canonical P4K identity
   * @param retired the source duplicate with no canonical P4K identity
   * @param activeCaseId the case whose merge proposal is being applied
   */
  @Transactional(propagation = Propagation.MANDATORY)
  void mergeWithinTransaction(
      @NotNull Manufacturer survivor, @NotNull Manufacturer retired, @NotNull UUID activeCaseId) {
    UUID survivorId = survivor.getId();
    UUID retiredId = retired.getId();
    if (survivorId.equals(retiredId)
        || survivor.getP4kUuid() == null
        || retired.getP4kUuid() != null) {
      throw new BusinessConflictException("The manufacturer identities cannot be merged.");
    }
    lockRows(survivor, retired);
    Integer retiredCompany = retired.getUexCompanyId();
    UUID retiredWiki = retired.getScwikiUuid();
    verifySourceOwnership(retired, retiredCompany, retiredWiki);
    movePrimaryIdentities(survivor, retired);
    references.repointItems(retiredId, survivorId);
    references.repointShips(retiredId, survivorId);
    p4kAliases.findOfManufacturer(retiredId).forEach(alias -> alias.setManufacturer(survivor));
    uexCompanies.findOfManufacturer(retiredId).forEach(alias -> alias.setManufacturer(survivor));
    wikiIdentities.findOfManufacturer(retiredId).forEach(alias -> alias.setManufacturer(survivor));
    repointOpenReviews(retiredId, survivorId, activeCaseId);
    if (retiredCompany != null && !uexCompanies.existsById(retiredCompany)) {
      uexCompanies.save(new ManufacturerUexCompany(retiredCompany, survivor));
    }
    if (retiredWiki != null && !wikiIdentities.existsById(retiredWiki)) {
      Instant syncedAt = Objects.requireNonNullElseGet(retired.getScwikiSyncedAt(), Instant::now);
      wikiIdentities.save(
          new ManufacturerScwikiIdentity(
              retiredWiki, survivor, retired.getName(), retired.getScwikiCode(), syncedAt));
    }
    entityManager.lock(survivor, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
    manufacturers.delete(retired);
  }

  private void repointOpenReviews(UUID retiredId, UUID survivorId, UUID activeCaseId) {
    for (P4kReviewCase review :
        reviewCases.findOpenReferences(P4kKind.MANUFACTURER, retiredId, activeCaseId)) {
      UUID row = retiredId.equals(review.getRowId()) ? survivorId : review.getRowId();
      UUID other = retiredId.equals(review.getOtherRowId()) ? survivorId : review.getOtherRowId();
      review.setRowId(row);
      review.setOtherRowId(Objects.equals(row, other) ? null : other);
    }
  }

  private void lockRows(Manufacturer survivor, Manufacturer retired) {
    List<ManufacturerReassignmentRepository.LockedRow> rows =
        references.lockRows(List.of(survivor.getId(), retired.getId()));
    if (rows.size() != 2) {
      throw new BusinessConflictException("A manufacturer in the proposal is no longer available.");
    }
    for (ManufacturerReassignmentRepository.LockedRow row : rows) {
      Manufacturer expected = row.getId().equals(survivor.getId()) ? survivor : retired;
      Long version = expected.getVersion();
      if (version == null) {
        throw new BusinessConflictException("The manufacturer proposal has changed.");
      }
      OptimisticLock.checkRequired(row.getVersion(), version, Manufacturer.class, row.getId());
    }
  }

  private void verifySourceOwnership(
      @NotNull Manufacturer retired, @Nullable Integer company, @Nullable UUID wikiUuid) {
    if (company != null) {
      uexCompanies
          .findManufacturerByUexCompanyId(company)
          .ifPresent(owner -> verifyOwner(owner, retired));
    }
    if (wikiUuid != null) {
      wikiIdentities
          .findManufacturerByScwikiUuid(wikiUuid)
          .ifPresent(owner -> verifyOwner(owner, retired));
    }
  }

  private static void verifyOwner(Manufacturer owner, Manufacturer retired) {
    if (!owner.getId().equals(retired.getId())) {
      throw new BusinessConflictException("An external identity belongs to another manufacturer.");
    }
  }

  private void movePrimaryIdentities(Manufacturer survivor, Manufacturer retired) {
    Integer survivorCompany = survivor.getUexCompanyId();
    Integer retiredCompany = retired.getUexCompanyId();
    UUID retiredWiki = retired.getScwikiUuid();
    boolean adoptCompany =
        retiredCompany != null && (survivorCompany == null || retiredCompany < survivorCompany);
    boolean adoptWiki = survivor.getScwikiUuid() == null && retiredWiki != null;
    if (!adoptCompany && !adoptWiki) {
      return;
    }
    if (adoptCompany) {
      retired.setUexCompanyId(null);
    }
    if (adoptWiki) {
      retired.setScwikiUuid(null);
    }
    manufacturers.flush();
    if (adoptCompany) {
      survivor.setUexCompanyId(retiredCompany);
      survivor.setUexSyncedAt(retired.getUexSyncedAt());
      survivor.setUexDeletedAt(retired.getUexDeletedAt());
      survivor.setNickname(retired.getNickname());
      survivor.setWiki(retired.getWiki());
      survivor.setIndustry(retired.getIndustry());
      survivor.setIsItemManufacturer(retired.getIsItemManufacturer());
      survivor.setIsVehicleManufacturer(retired.getIsVehicleManufacturer());
    }
    if (adoptWiki) {
      survivor.setScwikiUuid(retiredWiki);
      survivor.setScwikiCode(retired.getScwikiCode());
      survivor.setScwikiSyncedAt(retired.getScwikiSyncedAt());
      survivor.setScwikiDeletedAt(retired.getScwikiDeletedAt());
    }
  }
}
