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
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps a brand from being listed twice when UEX names it differently from the P4K row
 * (REQ-DATA-045): a UEX company resolves to the row its name names, and a twin an earlier sync
 * created is merged into that row through {@link ManufacturerReviewMerge}, each merge in its own
 * transaction and audited as {@link AuditEventType#CATALOGUE_MANUFACTURER_MERGED}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManufacturerUexTwinService {

  private final ManufacturerRepository manufacturers;
  private final ManufacturerScwikiIdentityRepository wikiIdentities;
  private final ManufacturerP4kAliasRepository p4kAliases;
  private final ManufacturerReviewMerge merge;
  private final AuditRecorder auditRecorder;

  /** Self-reference, so each merge runs through the transaction proxy in its own transaction. */
  private final ObjectProvider<ManufacturerUexTwinService> self;

  /**
   * Finds the row holding a P4K GUID that a UEX company's name names, so the sync joins the company
   * to it instead of creating a twin.
   *
   * @param companyName the UEX company's name
   * @return the named row, or empty when the name names no such row or several
   */
  @NotNull
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Manufacturer> resolve(@NotNull String companyName) {
    return read().named(companyName).flatMap(named -> manufacturers.findById(named.survivorId()));
  }

  /**
   * Merges every twin into the row it duplicates, each in its own transaction. A merge that fails
   * is logged and left to the next sync.
   *
   * @return the number of twins merged
   */
  public int mergeTwins() {
    int merged = 0;
    for (ManufacturerUexTwins.Twin twin : read().twins()) {
      try {
        if (self.getObject().mergeInNewTransaction(twin.retiredId())) {
          merged++;
        }
      } catch (RuntimeException e) {
        log.error(
            "Failed to merge the UEX manufacturer twin {} (UEX company {})",
            twin.retiredId(),
            twin.uexCompanyId(),
            e);
      }
    }
    if (merged > 0) {
      log.info("Merged {} UEX manufacturer twin(s) into the rows the P4K knows", merged);
    }
    return merged;
  }

  /**
   * Merges one twin in its own transaction after reading the rule again, and records the merge.
   * Must be invoked through the {@link #self} proxy.
   *
   * @param retiredId the twin
   * @return whether the row was still a twin and was merged
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  boolean mergeInNewTransaction(@NotNull UUID retiredId) {
    Optional<ManufacturerUexTwins.Twin> current = read().twinOf(retiredId);
    if (current.isEmpty()) {
      return false;
    }
    ManufacturerUexTwins.Twin twin = current.get();
    Optional<Manufacturer> retired = manufacturers.findById(twin.retiredId());
    Optional<Manufacturer> survivor = manufacturers.findById(twin.survivorId());
    if (retired.isEmpty() || survivor.isEmpty()) {
      return false;
    }
    ManufacturerReviewMerge.MovedReferences moved =
        merge.mergeWithinTransaction(survivor.get(), retired.get(), null);
    auditRecorder.record(
        AuditEventType.CATALOGUE_MANUFACTURER_MERGED,
        twin.survivorId(),
        survivor.get().getName(),
        null,
        AuditDetails.of("retiredManufacturer", twin.retiredId())
            .with("rule", twin.evidence().name())
            .with("uexCompanyId", twin.uexCompanyId())
            .with("items", moved.items())
            .with("shipTypes", moved.shipTypes()));
    return true;
  }

  /**
   * Reads the rows, Wiki names and P4K aliases the rule decides on.
   *
   * @return the rule over the current rows
   */
  @NotNull
  private ManufacturerUexTwins read() {
    Set<UUID> aliased = Set.copyOf(p4kAliases.findManufacturerIds());
    List<ManufacturerUexTwins.Row> rows =
        manufacturers.findIdentityRefs().stream()
            .map(ref ->
                new ManufacturerUexTwins.Row(
                    ref.getId(),
                    ref.getName(),
                    ref.getP4kUuid(),
                    ref.getScwikiUuid(),
                    ref.getUexCompanyId(),
                    aliased.contains(ref.getId())))
            .toList();
    List<ManufacturerUexTwins.WikiName> wikiNames =
        wikiIdentities.findWikiNameRefs().stream()
            .map(ref ->
                new ManufacturerUexTwins.WikiName(ref.getManufacturerId(), ref.getWikiName()))
            .toList();
    return ManufacturerUexTwins.of(rows, wikiNames);
  }
}
