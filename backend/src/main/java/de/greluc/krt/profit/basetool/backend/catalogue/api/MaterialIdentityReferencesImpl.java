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

/** Keeps the material lookups working for the ids of merged materials (REQ-DATA-046). */
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaterialIdentityReferencesImpl implements MaterialIdentityReferences {

  private final EntityManager entityManager;

  @Override
  public @NotNull Optional<Material> findById(@NotNull UUID id) {
    return entityManager
        .createQuery(
            "SELECT m FROM Material m WHERE m.id = :id OR m.id ="
                + " (SELECT r.canonicalMaterial.id FROM MaterialRedirect r WHERE r.id = :id)",
            Material.class)
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
  public @NotNull List<Material> findAllById(@NotNull Iterable<UUID> ids) {
    Set<UUID> distinct = new LinkedHashSet<>();
    ids.forEach(distinct::add);
    if (distinct.isEmpty()) {
      return List.of();
    }
    return entityManager
        .createQuery(
            "SELECT DISTINCT m FROM Material m WHERE m.id IN :ids OR m.id IN"
                + " (SELECT r.canonicalMaterial.id FROM MaterialRedirect r WHERE r.id IN :ids)",
            Material.class)
        .setParameter("ids", distinct)
        .getResultList();
  }

  @Override
  public @NotNull Material getReferenceById(@NotNull UUID id) {
    UUID current = findLocalIdRedirects(List.of(id)).getOrDefault(id, id);
    return entityManager.getReference(Material.class, current);
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
                "SELECT r.id AS retiredId, r.canonicalMaterial.id AS canonicalId"
                    + " FROM MaterialRedirect r WHERE r.id IN :ids",
                Tuple.class)
            .setParameter("ids", new LinkedHashSet<>(ids))
            .getResultList()) {
      redirects.put(redirect.get("retiredId", UUID.class), redirect.get("canonicalId", UUID.class));
    }
    return Map.copyOf(redirects);
  }
}
