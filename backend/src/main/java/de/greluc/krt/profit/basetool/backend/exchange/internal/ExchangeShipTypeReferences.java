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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

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

/**
 * Reports the ship types a journaled ship state names, which an undo restores (REQ-P4K-052,
 * REQ-XCH-017).
 */
@Component
@RequiredArgsConstructor
public class ExchangeShipTypeReferences implements ShipTypeReferenceParticipant {

  private final EntityManager entityManager;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull Set<UUID> referencedShipTypes(@NotNull Collection<UUID> shipTypeIds) {
    Set<UUID> referenced = new HashSet<>();
    for (UUID id : shipTypeIds) {
      String state = "%\"shipType\":\"" + id + "\"%";
      boolean named =
          !entityManager
              .createQuery(
                  "SELECT j.id FROM ExchangeJournalEntry j WHERE j.resource = :resource"
                      + " AND (j.beforeState LIKE :state OR j.afterState LIKE :state)",
                  UUID.class)
              .setParameter("resource", ExchangeResource.SHIP)
              .setParameter("state", state)
              .setMaxResults(1)
              .getResultList()
              .isEmpty();
      if (named) {
        referenced.add(id);
      }
    }
    return referenced;
  }
}
