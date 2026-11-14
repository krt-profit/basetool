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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockOfferLookup;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The no-mixing rule of the write-time merge (REQ-INV-026, REQ-PATCH-004): while the holder's
 * cutoff stands, a row created before it, or without a creation time, never absorbs another row,
 * and the merge asks the group only for rows from the cutoff on.
 */
@ExtendWith(MockitoExtension.class)
class InventoryMergeCutoffTest {

  private static final Instant CUTOFF = Instant.parse("2026-11-14T20:00:00Z");

  @Mock private InventoryOrderWrites orderWrites;
  @Mock private InventoryItemRepository repository;
  @Mock private StockOfferLookup offers;
  @Mock private AuditRecorder auditRecorder;

  private InventoryMergeService service;

  @BeforeEach
  void setUp() {
    service =
        new InventoryMergeService(
            orderWrites, repository, offers, auditRecorder, _ -> Optional.of(CUTOFF));
  }

  @Test
  void aRowCreatedBeforeTheCutoffIsNeverASurvivor() {
    InventoryItem prePatch = pieceRow(CUTOFF.minusSeconds(1));

    InventoryItem result = service.mergeStockIfRequested(prePatch, false);

    assertThat(result).isSameAs(prePatch);
    verify(repository, never())
        .findMergeGroupForUpdate(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void aRowWithoutACreationTimeIsNeverASurvivorWhileACutoffStands() {
    InventoryItem undated = pieceRow(null);

    InventoryItem result = service.mergeStockIfRequested(undated, false);

    assertThat(result).isSameAs(undated);
    verify(repository, never())
        .findMergeGroupForUpdate(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void aHolderWithoutACutoffMergesEveryRowDatedOrNot() {
    InventoryMergeService unrestricted =
        new InventoryMergeService(
            orderWrites, repository, offers, auditRecorder, _ -> Optional.empty());
    InventoryItem undated = pieceRow(null);
    when(repository.findMergeGroupForUpdate(
            eq(undated.getUser().getId()),
            eq(undated.getMaterial().getId()),
            eq(undated.getLocation().getId()),
            eq(undated.getQuality()),
            eq(false),
            eq(false),
            eq((UUID) null),
            eq((Instant) null)))
        .thenReturn(List.of(undated));

    InventoryItem result = unrestricted.mergeStockIfRequested(undated, false);

    assertThat(result).isSameAs(undated);
  }

  @Test
  void theCutoffIsAskedForTheRowsHolder() {
    InventoryItem prePatch = pieceRow(CUTOFF.minusSeconds(1));
    UUID holder = prePatch.getUser().getId();
    InventoryMergeService perHolder =
        new InventoryMergeService(
            orderWrites,
            repository,
            offers,
            auditRecorder,
            asked -> asked.equals(holder) ? Optional.of(CUTOFF) : Optional.empty());

    InventoryItem result = perHolder.mergeStockIfRequested(prePatch, false);

    assertThat(result).isSameAs(prePatch);
    verify(repository, never())
        .findMergeGroupForUpdate(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void aRowCreatedFromTheCutoffOnAsksOnlyForRowsFromTheCutoffOn() {
    InventoryItem postPatch = pieceRow(CUTOFF.plusSeconds(1));
    when(repository.findMergeGroupForUpdate(
            eq(postPatch.getUser().getId()),
            eq(postPatch.getMaterial().getId()),
            eq(postPatch.getLocation().getId()),
            eq(postPatch.getQuality()),
            eq(false),
            eq(false),
            eq((UUID) null),
            eq(CUTOFF)))
        .thenReturn(List.of(postPatch));

    InventoryItem result = service.mergeStockIfRequested(postPatch, false);

    assertThat(result).isSameAs(postPatch);
    verify(repository, never()).deleteAll(any());
  }

  /**
   * Builds a piece-counted stock row created at the given time.
   *
   * @param createdAt when the row was created, or {@code null} for an undated row
   * @return the row
   */
  private static InventoryItem pieceRow(Instant createdAt) {
    User user = new User();
    user.setId(UUID.randomUUID());
    Material material = new Material();
    material.setId(UUID.randomUUID());
    material.setQuantityType(QuantityType.PIECE);
    Location location = new Location();
    location.setId(UUID.randomUUID());
    InventoryItem row = new InventoryItem();
    row.setId(UUID.randomUUID());
    row.setUser(user);
    row.setMaterial(material);
    row.setLocation(location);
    row.setQuality(500);
    row.setPersonal(false);
    row.setStolen(false);
    row.setAmount(3.0);
    row.setCreatedAt(createdAt);
    return row;
  }
}
