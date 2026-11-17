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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeReassignmentParticipant;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeReferenceParticipant;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The catalogue's own ship type references: the vehicle a catalogue item belongs to (REQ-P4K-052),
 * moved onto the ship type that stays when two are merged (REQ-DATA-042); the merge's own audit
 * event counts them.
 */
@Component
@RequiredArgsConstructor
public class CatalogueShipTypeReferences
    implements ShipTypeReferenceParticipant, ShipTypeReassignmentParticipant {

  private final EntityManager entityManager;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull Set<UUID> referencedShipTypes(@NotNull Collection<UUID> shipTypeIds) {
    if (shipTypeIds.isEmpty()) {
      return Set.of();
    }
    return new HashSet<>(
        entityManager
            .createQuery(
                "SELECT DISTINCT g.linkedShipType.id FROM GameItem g"
                    + " WHERE g.linkedShipType.id IN :ids",
                UUID.class)
            .setParameter("ids", shipTypeIds)
            .getResultList());
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int reassignShipType(@NotNull UUID retiredId, @NotNull UUID survivorId) {
    List<GameItem> items =
        entityManager
            .createQuery(
                "SELECT g FROM GameItem g WHERE g.linkedShipType.id = :id ORDER BY g.id",
                GameItem.class)
            .setParameter("id", retiredId)
            .getResultList();
    ShipType survivor = entityManager.getReference(ShipType.class, survivorId);
    items.forEach(item -> item.setLinkedShipType(survivor));
    if (!items.isEmpty()) {
      entityManager.flush();
    }
    return items.size();
  }
}
