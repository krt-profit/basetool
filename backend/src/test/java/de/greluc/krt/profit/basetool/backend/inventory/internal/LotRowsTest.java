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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.inventory.api.OverAllocationException;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeObserver;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeReason;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins the split-and-lower primitive of the Lager: a split copies the lot identity under the given
 * placement, and lowering a row checks its earmarks, keeps the Materialbörse in step and deletes a
 * depleted row only when it carries no pledged donation.
 */
@ExtendWith(MockitoExtension.class)
class LotRowsTest {

  @Mock private InventoryItemRepository repository;
  @Mock private StockChangeObserver observer;

  /**
   * Builds a material row of ten SCU with a full identity.
   *
   * @return the row
   */
  private static InventoryItem source() {
    User owner = new User();
    owner.setId(UUID.randomUUID());
    Material material = new Material();
    material.setId(UUID.randomUUID());
    Location location = new Location();
    location.setId(UUID.randomUUID());
    Squadron unit = new Squadron();
    unit.setId(UUID.randomUUID());
    InventoryItem row = new InventoryItem();
    row.setId(UUID.randomUUID());
    row.setUser(owner);
    row.setMaterial(material);
    row.setLocation(location);
    row.setOwningOrgUnit(unit);
    row.setQuality(650);
    row.setAmount(10.0);
    row.setPersonal(false);
    row.setStolen(true);
    row.setNote("keep");
    return row;
  }

  @Test
  void aSplitCopiesTheLotIdentityUnderTheSourcePlacement() {
    InventoryItem source = source();

    InventoryItem part = LotRows.split(source, 3.0004, LotRows.Placement.of(source));

    assertThat(part.getId()).isNull();
    assertThat(part.getUser()).isSameAs(source.getUser());
    assertThat(part.getMaterial()).isSameAs(source.getMaterial());
    assertThat(part.getGameItem()).isNull();
    assertThat(part.getLocation()).isSameAs(source.getLocation());
    assertThat(part.getOwningOrgUnit()).isSameAs(source.getOwningOrgUnit());
    assertThat(part.getQuality()).isEqualTo(650);
    assertThat(part.getAmount()).isEqualTo(3.0);
    assertThat(part.getPersonal()).isFalse();
    assertThat(part.getStolen()).isTrue();
    assertThat(part.getNote()).isNull();
    assertThat(part.getJobOrderAllocations()).isEmpty();
  }

  @Test
  void aSplitTakesTheOverriddenPlacement() {
    InventoryItem source = source();
    User other = new User();
    Location elsewhere = new Location();

    InventoryItem part =
        LotRows.split(
            source,
            2.0,
            LotRows.Placement.of(source)
                .withUser(other)
                .withLocation(elsewhere)
                .withOwningOrgUnit(null)
                .withPersonal(true)
                .withStolen(false));

    assertThat(part.getUser()).isSameAs(other);
    assertThat(part.getLocation()).isSameAs(elsewhere);
    assertThat(part.getOwningOrgUnit()).isNull();
    assertThat(part.getPersonal()).isTrue();
    assertThat(part.getStolen()).isFalse();
    assertThat(part.getMaterial()).isSameAs(source.getMaterial());
  }

  @Test
  void loweringARowThatKeepsStockFlushesItAndLowersItsOffers() {
    InventoryItem row = source();
    when(repository.saveAndFlush(row)).thenReturn(row);
    when(observer.lower(row.getId(), 4.0, StockChangeReason.TRANSFER)).thenReturn(1);

    LotRows.Lowered lowered =
        LotRows.lower(repository, observer, row, 4.0, StockChangeReason.TRANSFER);

    assertThat(row.getAmount()).isEqualTo(4.0);
    assertThat(lowered.row()).isSameAs(row);
    assertThat(lowered.depleted()).isFalse();
    assertThat(lowered.offers().reduced()).isEqualTo(1);
    assertThat(lowered.offers().removed()).isZero();
    verify(observer).lower(row.getId(), 4.0, StockChangeReason.TRANSFER);
    verify(repository, never()).delete(any());
  }

  @Test
  void loweringARowBelowItsEarmarksIsRefusedBeforeAnyWrite() {
    InventoryItem row = source();
    InventoryAllocations.addJobOrder(row, UUID.randomUUID(), 6.0, false);

    assertThatThrownBy(
            () -> LotRows.lower(repository, observer, row, 4.0, StockChangeReason.REBOOK))
        .isInstanceOf(OverAllocationException.class);
    verifyNoInteractions(repository, observer);
  }

  @Test
  void aDepletedRowIsDeletedAfterItsOffersAreAnnounced() {
    InventoryItem row = source();
    when(observer.beforeDelete(List.of(row.getId()), StockChangeReason.CHECKOUT)).thenReturn(2);

    LotRows.Lowered lowered =
        LotRows.lower(repository, observer, row, 0.00004, StockChangeReason.CHECKOUT);

    assertThat(lowered.row()).isSameAs(row);
    assertThat(lowered.depleted()).isTrue();
    assertThat(lowered.offers().reduced()).isZero();
    assertThat(lowered.offers().removed()).isEqualTo(2);
    verify(repository).delete(row);
    verify(repository, never()).saveAndFlush(any());
    verify(observer, never()).lower(any(), anyDouble(), any());
  }

  @Test
  void aDepletedRowThatCarriesADonationIsNotDeleted() {
    InventoryItem row = source();
    InventoryAllocations.addDonation(row, UUID.randomUUID(), 1.0);

    assertThatThrownBy(
            () -> LotRows.lower(repository, observer, row, 0.0, StockChangeReason.REBOOK))
        .isInstanceOf(OverAllocationException.class);
    verifyNoInteractions(repository, observer);
  }
}
