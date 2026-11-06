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
import java.util.ArrayList;
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

/** Keeps repository lookup semantics stable for local ids retained by item reconciliation. */
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GameItemIdentityReferencesImpl implements GameItemIdentityReferences {

  private static final int REDIRECT_LOOKUP_BATCH_SIZE = 500;

  private final EntityManager entityManager;

  @Override
  public @NotNull Optional<GameItem> findById(@NotNull UUID id) {
    return entityManager
        .createQuery(
            "SELECT g FROM GameItem g WHERE g.id = :id OR g.id ="
                + " (SELECT r.canonicalItem.id FROM GameItemRedirect r WHERE r.id = :id)",
            GameItem.class)
        .setParameter("id", id)
        .getResultList()
        .stream()
        .findFirst();
  }

  @Override
  public @NotNull List<GameItem> findAllById(@NotNull Iterable<UUID> ids) {
    Set<UUID> canonicalIds = new LinkedHashSet<>();
    ids.forEach(canonicalIds::add);
    if (canonicalIds.isEmpty()) {
      return List.of();
    }
    return entityManager
        .createQuery(
            "SELECT DISTINCT g FROM GameItem g WHERE g.id IN :ids OR g.id IN"
                + " (SELECT r.canonicalItem.id FROM GameItemRedirect r WHERE r.id IN :ids)",
            GameItem.class)
        .setParameter("ids", canonicalIds)
        .getResultList();
  }

  @Override
  public @NotNull GameItem getReferenceById(@NotNull UUID id) {
    UUID canonicalId =
        entityManager
            .createQuery(
                "SELECT r.canonicalItem.id FROM GameItemRedirect r WHERE r.id = :id", UUID.class)
            .setParameter("id", id)
            .getResultList()
            .stream()
            .findFirst()
            .orElse(id);
    return entityManager.getReference(GameItem.class, canonicalId);
  }

  @Override
  public @NotNull GameItem getCanonicalReferenceById(@NotNull UUID id) {
    return entityManager.getReference(GameItem.class, id);
  }

  @Override
  public @NotNull Map<UUID, UUID> findLocalIdRedirects(@NotNull Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    final Map<UUID, UUID> resolved = new HashMap<>();
    List<UUID> distinct = new ArrayList<>(new LinkedHashSet<>(ids));
    for (int start = 0; start < distinct.size(); start += REDIRECT_LOOKUP_BATCH_SIZE) {
      List<Tuple> redirects =
          entityManager
              .createQuery(
                  "SELECT r.id AS retiredId, r.canonicalItem.id AS canonicalId FROM"
                      + " GameItemRedirect r WHERE r.id IN :ids",
                  Tuple.class)
              .setParameter(
                  "ids",
                  distinct.subList(
                      start, Math.min(start + REDIRECT_LOOKUP_BATCH_SIZE, distinct.size())))
              .getResultList();
      for (Tuple redirect : redirects) {
        resolved.put(
            redirect.get("retiredId", UUID.class), redirect.get("canonicalId", UUID.class));
      }
    }
    return Map.copyOf(resolved);
  }

  @Override
  public @NotNull List<UUID> resolveLocalIds(@NotNull Collection<UUID> ids) {
    Map<UUID, UUID> redirects = findLocalIdRedirects(ids);
    return ids.stream().map(id -> redirects.getOrDefault(id, id)).distinct().toList();
  }
}
