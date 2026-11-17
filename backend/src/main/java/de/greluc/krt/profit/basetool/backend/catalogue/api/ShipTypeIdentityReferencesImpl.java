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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the ship type lookups working for the ids of merged ship types (REQ-DATA-042). */
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShipTypeIdentityReferencesImpl implements ShipTypeIdentityReferences {

  private final EntityManager entityManager;

  @Override
  public @NotNull Optional<ShipType> findById(@NotNull UUID id) {
    return entityManager
        .createQuery(
            "SELECT s FROM ShipType s WHERE s.id = :id OR s.id ="
                + " (SELECT r.canonicalShipType.id FROM ShipTypeRedirect r WHERE r.id = :id)",
            ShipType.class)
        .setParameter("id", id)
        .getResultList()
        .stream()
        .findFirst();
  }

  @Override
  public boolean existsById(@NotNull UUID id) {
    return findById(id).isPresent();
  }

  @Override
  public @NotNull List<ShipType> findAllById(@NotNull Iterable<UUID> ids) {
    Set<UUID> distinct = new LinkedHashSet<>();
    ids.forEach(distinct::add);
    if (distinct.isEmpty()) {
      return List.of();
    }
    return entityManager
        .createQuery(
            "SELECT DISTINCT s FROM ShipType s WHERE s.id IN :ids OR s.id IN"
                + " (SELECT r.canonicalShipType.id FROM ShipTypeRedirect r WHERE r.id IN :ids)",
            ShipType.class)
        .setParameter("ids", distinct)
        .getResultList();
  }

  @Override
  public @NotNull ShipType getReferenceById(@NotNull UUID id) {
    UUID current = findLocalIdRedirects(List.of(id)).getOrDefault(id, id);
    return entityManager.getReference(ShipType.class, current);
  }

  @Override
  public @NotNull Map<UUID, UUID> findLocalIdRedirects(@NotNull Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<UUID, UUID> redirects = new HashMap<>();
    for (Tuple redirect :
        entityManager
            .createQuery(
                "SELECT r.id AS retiredId, r.canonicalShipType.id AS canonicalId"
                    + " FROM ShipTypeRedirect r WHERE r.id IN :ids",
                Tuple.class)
            .setParameter("ids", new LinkedHashSet<>(ids))
            .getResultList()) {
      redirects.put(redirect.get("retiredId", UUID.class), redirect.get("canonicalId", UUID.class));
    }
    return Map.copyOf(redirects);
  }
}
