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

import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeReferenceParticipant;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reports the ship types a member's hangar ship is of (REQ-P4K-052). */
@Component
@RequiredArgsConstructor
public class HangarShipTypeReferences implements ShipTypeReferenceParticipant {

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
                "SELECT DISTINCT s.shipType.id FROM Ship s WHERE s.shipType.id IN :ids", UUID.class)
            .setParameter("ids", shipTypeIds)
            .getResultList());
  }
}
