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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipPatchWipe;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipWipeCount;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes the in-game ships a game patch wiped through the ordinary deletion path (REQ-PATCH-015);
 * the hangar's trail gets one summary event instead of one per ship (REQ-PATCH-017).
 */
@Service
@RequiredArgsConstructor
public class HangarPatchWipeService implements ShipPatchWipe {

  private final ShipRepository shipRepository;
  private final HangarShipRemoval shipRemoval;
  private final AuditRecorder auditRecorder;
  private final LiveSyncPublisher liveSyncPublisher;

  @Override
  @Transactional(readOnly = true)
  public @NotNull ShipWipeCount countWipeable(@NotNull Instant liveAt) {
    return new ShipWipeCount(
        shipRepository.countPatchWipeable(liveAt), shipRepository.countPatchWipeableOwners(liveAt));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull ShipWipeCount wipe(
      @NotNull Instant liveAt, @NotNull UUID patchChangeId, @NotNull String patchLabel) {
    List<Ship> ships = shipRepository.lockPatchWipeable(liveAt);
    Set<UUID> owners = new LinkedHashSet<>();
    ships.forEach(ship -> owners.add(ship.getOwner().getId()));
    int detached = shipRemoval.remove(ships);
    auditRecorder.record(
        AuditEventType.HANGAR_SHIPS_WIPED_BY_PATCH,
        patchChangeId,
        patchLabel,
        null,
        AuditDetails.of("ships", ships.size())
            .with("owners", owners.size())
            .with("detachedUnits", detached)
            .with("patchChange", patchChangeId));
    owners.forEach(owner ->
        liveSyncPublisher.publishAfterCommit(
            LiveSyncTopic.of(LiveSyncTopicClass.HANGAR_OWN, owner), List.of("ships")));
    return new ShipWipeCount(ships.size(), owners.size());
  }
}
