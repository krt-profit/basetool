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
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.livesync.api.AdminDashboardLiveSync;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lists the manufacturers the SC Wiki carries more than once and lets an admin choose which Wiki
 * entry is the primary link; the others stay aliases (ADR-0361).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ManufacturerWikiIdentityService {

  private final ManufacturerRepository manufacturerRepository;
  private final ManufacturerScwikiIdentityRepository identityRepository;
  private final AuditRecorder auditRecorder;
  private final AdminDashboardLiveSync adminDashboardLiveSync;

  /**
   * Returns every manufacturer with more than one Wiki entry, ordered by name.
   *
   * @return the duplicated manufacturers with their Wiki entries
   */
  @NotNull
  public List<ManufacturerWikiDuplicateDto> listDuplicates() {
    Map<UUID, List<ManufacturerScwikiIdentity>> byManufacturer = new LinkedHashMap<>();
    for (ManufacturerScwikiIdentity identity : identityRepository.findDuplicated()) {
      byManufacturer
          .computeIfAbsent(identity.getManufacturer().getId(), _ -> new ArrayList<>())
          .add(identity);
    }
    return byManufacturer.values().stream()
        .map(identities -> toDto(identities.getFirst().getManufacturer(), identities))
        .toList();
  }

  /**
   * Makes one of a manufacturer's Wiki entries its primary link.
   *
   * <p>The Wiki manufacturer sync keeps the choice: it never moves a link whose UUID the Wiki still
   * carries. A choice of the current primary changes nothing and records nothing.
   *
   * @param manufacturerId the local manufacturer id
   * @param request the chosen Wiki entry and the version the admin last saw
   * @return the manufacturer with its Wiki entries after the choice
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when no manufacturer
   *     matches
   * @throws BadRequestException when the Wiki entry does not belong to the manufacturer
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the version is
   *     stale
   */
  @Transactional
  @NotNull
  public ManufacturerWikiDuplicateDto choosePrimary(
      @NotNull UUID manufacturerId, @NotNull ManufacturerWikiPrimaryRequest request) {
    Manufacturer manufacturer =
        Entities.require(manufacturerRepository.findById(manufacturerId), "Manufacturer not found");
    OptimisticLock.checkRequired(
        manufacturer.getVersion(), request.version(), Manufacturer.class, manufacturerId);
    ManufacturerScwikiIdentity chosen =
        identityRepository
            .findById(request.scwikiUuid())
            .filter(identity -> identity.getManufacturer().getId().equals(manufacturerId))
            .orElseThrow(() ->
                new BadRequestException("The Wiki entry does not belong to the manufacturer."));
    UUID previous = manufacturer.getScwikiUuid();
    if (!chosen.getScwikiUuid().equals(previous)) {
      manufacturer.setScwikiUuid(chosen.getScwikiUuid());
      String code = chosen.getWikiCode();
      if (code != null) {
        manufacturer.setScwikiCode(code);
      }
      manufacturer = manufacturerRepository.saveAndFlush(manufacturer);
      auditRecorder.record(
          AuditEventType.CATALOGUE_MANUFACTURER_WIKI_PRIMARY_CHANGED,
          manufacturer.getId(),
          manufacturer.getName(),
          null,
          AuditDetails.of("from", previous).with("to", chosen.getScwikiUuid()));
      adminDashboardLiveSync.syncStatusChanged();
    }
    return toDto(manufacturer, identityRepository.findOfManufacturer(manufacturerId));
  }

  private static ManufacturerWikiDuplicateDto toDto(
      Manufacturer manufacturer, List<ManufacturerScwikiIdentity> identities) {
    return new ManufacturerWikiDuplicateDto(
        manufacturer.getId(),
        manufacturer.getName(),
        manufacturer.getAbbreviation(),
        Objects.requireNonNull(manufacturer.getVersion(), "a persisted manufacturer has a version"),
        identities.stream()
            .map(identity ->
                new ManufacturerWikiIdentityDto(
                    identity.getScwikiUuid(),
                    identity.getWikiName(),
                    identity.getWikiCode(),
                    identity.getScwikiUuid().equals(manufacturer.getScwikiUuid()),
                    identity.getSyncedAt()))
            .toList());
  }
}
