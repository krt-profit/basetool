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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import de.greluc.krt.profit.basetool.backend.admin.api.events.HangarNotices;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipOrigin;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marks a selection of one member's ships as pledged or bought in-game in one action, for the owner
 * and for an admin (REQ-HANGAR-018).
 *
 * <p>The ships are locked in ascending id order and checked before the first write, so an unknown
 * or foreign ship refuses the whole selection. Ships that already read as the target and ships an
 * import locked to {@code PLEDGE} are skipped. The origin is part of each ship's version, so a
 * single edit opened before the action 409s. One summary event is recorded when anything changed;
 * it names counts only, never the origin (REQ-HANGAR-004).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HangarShipOriginService {

  /** The {@code changeCode} of an admin's notice that one ship changed. */
  private static final String UPDATED = "UPDATED";

  private final ShipRepository shipRepository;
  private final AuditRecorder auditRecorder;
  private final HangarAdminNotifier adminNotifier;

  /**
   * Marks a selection of the caller's ships with one origin.
   *
   * @param userId the calling owner
   * @param shipIds the selected ships
   * @param origin {@code PLEDGE} or {@code IN_GAME}
   * @return how many ships changed and how many were skipped
   * @throws BadRequestException when the origin is absent or {@code UNKNOWN}
   * @throws NotFoundException when a selected ship does not exist
   * @throws AccessDeniedException when a selected ship belongs to another member
   */
  @Transactional
  public @NotNull ShipBulkOriginResultDto bulkChangeOrigin(
      @NotNull UUID userId, @NotNull List<UUID> shipIds, @Nullable ShipOrigin origin) {
    return change(userId, shipIds, origin).result();
  }

  /**
   * Marks a selection of a member's ships with one origin on an admin's behalf, with the rules of
   * {@link #bulkChangeOrigin}. The member hears one notice: about the ship when one changed, a
   * summary with the count when several did, nothing when none did.
   *
   * @param memberId the ships' owner
   * @param shipIds the selected ships
   * @param origin {@code PLEDGE} or {@code IN_GAME}
   * @return how many ships changed and how many were skipped
   * @throws BadRequestException when the origin is absent or {@code UNKNOWN}
   * @throws NotFoundException when a selected ship does not exist
   * @throws AccessDeniedException when a selected ship belongs to another member
   */
  @Transactional
  public @NotNull ShipBulkOriginResultDto bulkChangeOriginByAdmin(
      @NotNull UUID memberId, @NotNull List<UUID> shipIds, @Nullable ShipOrigin origin) {
    OriginChange change = change(memberId, shipIds, origin);
    int changed = change.changedShips().size();
    if (changed == 1) {
      adminNotifier.shipChanged(memberId, UPDATED, change.changedShips().getFirst());
    } else if (changed > 1) {
      adminNotifier.tell(
          memberId, actor -> HangarNotices.shipsOriginChanged(memberId, changed, actor));
    }
    return change.result();
  }

  /**
   * Sets the origin on the selected ships of {@code userId} under sorted row locks and records one
   * summary event when anything changed.
   *
   * @param userId the ships' owner
   * @param shipIds the selected ships
   * @param origin the requested origin
   * @return the ships that changed and the counts
   */
  private @NotNull OriginChange change(
      @NotNull UUID userId, @NotNull List<UUID> shipIds, @Nullable ShipOrigin origin) {
    ShipOrigin target = ShipOriginRules.requireChoice(origin);
    List<UUID> distinctIds = shipIds.stream().distinct().toList();
    List<Ship> ships = shipRepository.lockAllByIdOrderById(distinctIds);
    if (ships.size() != distinctIds.size()) {
      throw new NotFoundException("Ship not found");
    }
    ships.forEach(ship -> ShipOwnership.requireOwner(ship, userId));
    List<Ship> changing =
        ships.stream()
            .filter(ship -> !ship.isOriginLocked() && ship.getOrigin() != target)
            .toList();
    int skipped = ships.size() - changing.size();
    if (changing.isEmpty()) {
      return new OriginChange(List.of(), new ShipBulkOriginResultDto(0, skipped));
    }
    changing.forEach(ship -> ship.setOrigin(target));
    shipRepository.flush();
    auditRecorder.record(
        AuditEventType.HANGAR_SHIPS_BULK_ORIGIN_CHANGED,
        null,
        null,
        userId,
        AuditDetails.of("changed", changing.size()).with("skipped", skipped));
    return new OriginChange(changing, new ShipBulkOriginResultDto(changing.size(), skipped));
  }

  /**
   * The outcome of a bulk origin change.
   *
   * @param changedShips the ships whose origin changed
   * @param result the changed and skipped counts
   */
  private record OriginChange(
      @NotNull List<Ship> changedShips, @NotNull ShipBulkOriginResultDto result) {}
}
