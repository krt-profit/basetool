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
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeReassignmentParticipant;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merges a ship type created a second time into the ship type of the same game ship (REQ-DATA-042):
 * every module's references move onto the survivor, the survivor takes the source identities it
 * lacks, the merged id stays resolvable through a redirect, and both rows are kept as JSON.
 */
@Service
@RequiredArgsConstructor
class ShipTypeMergeService {

  private final ShipTypeRepository shipTypes;
  private final ShipTypeRedirectRepository redirects;
  private final List<ShipTypeReassignmentParticipant> participants;
  private final AuditRecorder auditRecorder;

  /**
   * Merges one ship type into another inside the caller's transaction. A row that holds a P4K GUID
   * is never merged away, since its record's identity stays with it (REQ-P4K-007).
   *
   * @param retiredId the ship type to merge away
   * @param survivorId the ship type that stays
   * @param survivorGuid the P4K GUID the survivor holds once the caller's run is through, or {@code
   *     null} when it holds none; a Wiki UUID equal to it outranks the survivor's own
   * @param reason the evidence that both stand for the same game ship
   * @return whether the merge happened; {@code false} when a row is gone or the retired row holds a
   *     P4K GUID
   */
  @Transactional(propagation = Propagation.MANDATORY)
  boolean merge(
      @NotNull UUID retiredId,
      @NotNull UUID survivorId,
      @Nullable UUID survivorGuid,
      @NotNull ShipTypeMergeReason reason) {
    if (retiredId.equals(survivorId)) {
      return false;
    }
    List<ShipType> locked = shipTypes.lockMergeRows(List.of(retiredId, survivorId));
    ShipType retired = find(locked, retiredId);
    ShipType survivor = find(locked, survivorId);
    if (retired == null || survivor == null || retired.getP4kUuid() != null) {
      return false;
    }
    final String snapshot = redirects.snapshot(retiredId, survivorId);
    int references = 0;
    for (ShipTypeReassignmentParticipant participant : participants) {
      references += participant.reassignShipType(retiredId, survivorId);
    }
    redirects.repoint(retiredId, survivorId);
    Identity identity = Identity.of(retired);
    shipTypes.delete(retired);
    shipTypes.flush();
    identity.giveTo(survivor, survivorGuid);
    shipTypes.saveAndFlush(survivor);
    redirects.alias(retiredId, survivorId, snapshot);
    auditRecorder.record(
        AuditEventType.CATALOGUE_SHIP_TYPE_MERGED,
        survivorId,
        survivor.getName(),
        null,
        AuditDetails.of("retiredShipType", retiredId)
            .with("reason", reason.name())
            .with("references", references));
    return true;
  }

  /**
   * Picks a row out of the locked ones.
   *
   * @param locked the locked rows
   * @param id the row's id
   * @return the row, or {@code null} when it is gone
   */
  @Nullable
  private static ShipType find(@NotNull List<ShipType> locked, @NotNull UUID id) {
    return locked.stream().filter(row -> id.equals(row.getId())).findFirst().orElse(null);
  }

  /**
   * The Wiki and UEX identities of a merged row, kept while the row is deleted so the survivor can
   * take them without breaking their unique constraints.
   *
   * @param externalUuid the Wiki UUID
   * @param scwikiSlug the Wiki slug
   * @param gameName the Wiki's in-game name
   * @param scwikiSyncedAt when the Wiki last wrote the row
   * @param scwikiDeletedAt when the Wiki stopped listing the row
   * @param uexVehicleId the UEX vehicle id
   * @param uexSlug the UEX slug
   * @param nameFull UEX's full name
   * @param uexSyncedAt when UEX last wrote the row
   * @param uexDeletedAt when UEX stopped listing the row
   */
  private record Identity(
      @Nullable UUID externalUuid,
      @Nullable String scwikiSlug,
      @Nullable String gameName,
      @Nullable Instant scwikiSyncedAt,
      @Nullable Instant scwikiDeletedAt,
      @Nullable Integer uexVehicleId,
      @Nullable String uexSlug,
      @Nullable String nameFull,
      @Nullable Instant uexSyncedAt,
      @Nullable Instant uexDeletedAt) {

    /**
     * Reads the identities of a row.
     *
     * @param row the row
     * @return its identities
     */
    @NotNull
    static Identity of(@NotNull ShipType row) {
      return new Identity(
          row.getExternalUuid(),
          row.getScwikiSlug(),
          row.getGameName(),
          row.getScwikiSyncedAt(),
          row.getScwikiDeletedAt(),
          row.getUexVehicleId(),
          row.getUexSlug(),
          row.getNameFull(),
          row.getUexSyncedAt(),
          row.getUexDeletedAt());
    }

    /**
     * Hands the identities to the survivor: the Wiki identity when the survivor has none or when
     * the Wiki UUID is the survivor's settled P4K GUID, the UEX identity when the survivor has
     * none. A value the merged row lacks leaves the survivor's own in place.
     *
     * @param survivor the ship type that stays
     * @param survivorGuid the P4K GUID the survivor holds once the run is through, or {@code null}
     */
    void giveTo(@NotNull ShipType survivor, @Nullable UUID survivorGuid) {
      boolean wiki = false;
      if (externalUuid != null
          && (survivor.getExternalUuid() == null || externalUuid.equals(survivorGuid))) {
        survivor.setExternalUuid(externalUuid);
        fill(scwikiSlug, survivor::setScwikiSlug);
        fill(gameName, survivor::setGameName);
        fill(scwikiSyncedAt, survivor::setScwikiSyncedAt);
        survivor.setScwikiDeletedAt(scwikiDeletedAt);
        wiki = true;
      }
      boolean uex = false;
      if (uexVehicleId != null && survivor.getUexVehicleId() == null) {
        survivor.setUexVehicleId(uexVehicleId);
        fill(uexSlug, survivor::setUexSlug);
        fill(nameFull, survivor::setNameFull);
        fill(uexSyncedAt, survivor::setUexSyncedAt);
        survivor.setUexDeletedAt(uexDeletedAt);
        uex = true;
      }
      GameItemSourceSystem source = Objects.requireNonNull(survivor.getSourceSystems());
      if ((wiki && source == GameItemSourceSystem.UEX_ONLY)
          || (uex && source == GameItemSourceSystem.WIKI_ONLY)) {
        survivor.setSourceSystems(GameItemSourceSystem.BOTH);
      }
    }

    /**
     * Writes a value the merged row carries.
     *
     * @param value the merged row's value, or {@code null} when it has none
     * @param setter the survivor's setter
     * @param <T> the value's type
     */
    private static <T> void fill(@Nullable T value, @NotNull Consumer<T> setter) {
      if (value != null) {
        setter.accept(value);
      }
    }
  }
}
