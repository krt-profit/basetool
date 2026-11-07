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

import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reads and writes the starmap link of the nine UEX location tables (REQ-P4K-035). The link columns
 * are not mapped on the UEX entities, so a UEX sync saving an entity never overwrites a link; only
 * these statements write them.
 */
@Component
@RequiredArgsConstructor
public class StarmapPlaceRows {

  private final EntityManager entityManager;

  /**
   * Reads every row of one table.
   *
   * @param kind the table
   * @return its rows
   */
  @NotNull
  public List<StarmapPlaceRow> rows(@NotNull StarmapPlaceKind kind) {
    List<?> tuples = entityManager.createNativeQuery(kind.rowsQuery()).getResultList();
    return tuples.stream().map(t -> StarmapPlaceRow.of(kind, (Object[]) t)).toList();
  }

  /**
   * Reads one row.
   *
   * @param kind the table
   * @param id the row
   * @return the row, if it exists
   */
  @NotNull
  public Optional<StarmapPlaceRow> row(@NotNull StarmapPlaceKind kind, @NotNull UUID id) {
    List<?> tuples =
        entityManager
            .createNativeQuery(kind.rowsQuery() + " WHERE id = :id")
            .setParameter("id", id)
            .getResultList();
    return tuples.stream().map(t -> StarmapPlaceRow.of(kind, (Object[]) t)).findFirst();
  }

  /**
   * Whether a city or a space station links to a starmap object, so its storage place comes from
   * the UEX sync (REQ-P4K-036).
   *
   * @param objectId the starmap object
   * @return {@code true} when one of them links to it
   */
  public boolean isLinkedByCityOrSpaceStation(@NotNull UUID objectId) {
    Object hit =
        entityManager
            .createNativeQuery(
                "SELECT EXISTS (SELECT 1 FROM city WHERE starmap_object_id = :id)"
                    + " OR EXISTS (SELECT 1 FROM space_station WHERE starmap_object_id = :id)")
            .setParameter("id", objectId)
            .getSingleResult();
    return Boolean.TRUE.equals(hit);
  }

  /**
   * Writes an APPLY's links of a table's rows in one statement, leaving every row an admin linked.
   *
   * @param kind the table
   * @param links the linked object of each row, {@code null} to unlink it
   */
  public void autoLink(@NotNull StarmapPlaceKind kind, @NotNull Map<UUID, UUID> links) {
    if (links.isEmpty()) {
      return;
    }
    entityManager
        .createNativeQuery(kind.autoLinkStatement())
        .setParameter("ids", UuidArrayLiteral.of(links.keySet()))
        .setParameter("objectIds", UuidArrayLiteral.of(new ArrayList<>(links.values())))
        .executeUpdate();
  }

  /**
   * Writes an admin's link of a row when it still holds the link the admin saw.
   *
   * @param kind the table
   * @param id the row
   * @param objectId the new link, or {@code null}
   * @param manual whether the link is the admin's ({@code false} hands it back to the import)
   * @param expectedObjectId the link the admin saw
   * @param expectedManual the manual flag the admin saw
   * @return {@code true} when the row held that link and was written
   */
  public boolean manualLink(
      @NotNull StarmapPlaceKind kind,
      @NotNull UUID id,
      @Nullable UUID objectId,
      boolean manual,
      @Nullable UUID expectedObjectId,
      boolean expectedManual) {
    return entityManager
            .createNativeQuery(kind.manualLinkStatement())
            .setParameter("objectId", objectId == null ? null : objectId.toString())
            .setParameter("manual", manual)
            .setParameter("id", id)
            .setParameter("expectedManual", expectedManual)
            .setParameter(
                "expectedObjectId", expectedObjectId == null ? null : expectedObjectId.toString())
            .executeUpdate()
        == 1;
  }
}
