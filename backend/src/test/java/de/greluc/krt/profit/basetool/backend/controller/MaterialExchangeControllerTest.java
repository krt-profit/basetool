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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferStatus;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeCountsDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeOfferDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeReleasableItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeReleaseRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeRemarkUpdateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.backend.service.MaterialExchangeService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Delegation coverage for {@link MaterialExchangeController}: each endpoint forwards its arguments
 * to {@link MaterialExchangeService} and returns the service result unchanged (the security gate
 * and validation are framework-enforced by the {@code @PreAuthorize}/{@code @Valid} annotations).
 */
@ExtendWith(MockitoExtension.class)
class MaterialExchangeControllerTest {

  @Mock private MaterialExchangeService service;

  @InjectMocks private MaterialExchangeController controller;

  private final UUID offerId = UUID.randomUUID();

  @Test
  void boardDelegatesFiltersAndSort() {
    PageResponse<MaterialExchangeOfferDto> page =
        new PageResponse<>(List.of(sampleDto()), 0, 50, 1, 1, List.of());
    when(service.board("mein", "agri", 600, 100.0, "menge", 0, 50)).thenReturn(page);

    PageResponse<MaterialExchangeOfferDto> result =
        controller.board("mein", "agri", 600, 100.0, "menge", 0, 50);

    assertThat(result).isSameAs(page);
    verify(service).board("mein", "agri", 600, 100.0, "menge", 0, 50);
  }

  @Test
  void countsDelegates() {
    MaterialExchangeCountsDto counts = new MaterialExchangeCountsDto(5, 2);
    when(service.counts()).thenReturn(counts);

    assertThat(controller.counts()).isSameAs(counts);
  }

  @Test
  void detailDelegates() {
    MaterialExchangeOfferDto dto = sampleDto();
    when(service.detail(offerId)).thenReturn(dto);

    assertThat(controller.detail(offerId)).isSameAs(dto);
  }

  @Test
  void releaseDelegates() {
    MaterialExchangeReleaseRequest request =
        new MaterialExchangeReleaseRequest(UUID.randomUUID(), "tausche gegen X");
    MaterialExchangeOfferDto dto = sampleDto();
    when(service.release(request)).thenReturn(dto);

    assertThat(controller.release(request)).isSameAs(dto);
    verify(service).release(request);
  }

  @Test
  void updateRemarkDelegates() {
    MaterialExchangeRemarkUpdateRequest request =
        new MaterialExchangeRemarkUpdateRequest("neu", 3L);
    when(service.updateRemark(offerId, request)).thenReturn(sampleDto());

    controller.updateRemark(offerId, request);

    verify(service).updateRemark(offerId, request);
  }

  @Test
  void deactivateDelegates() {
    when(service.deactivate(offerId)).thenReturn(sampleDto());

    controller.deactivate(offerId);

    verify(service).deactivate(offerId);
  }

  @Test
  void deactivateForItemDelegates() {
    UUID itemId = UUID.randomUUID();
    when(service.deactivateForItem(itemId)).thenReturn(sampleDto());

    controller.deactivateForItem(itemId);

    verify(service).deactivateForItem(itemId);
  }

  @Test
  void registerInterestDelegates() {
    when(service.registerInterest(offerId)).thenReturn(sampleDto());

    controller.registerInterest(offerId);

    verify(service).registerInterest(offerId);
  }

  @Test
  void withdrawInterestDelegates() {
    when(service.withdrawInterest(offerId)).thenReturn(sampleDto());

    controller.withdrawInterest(offerId);

    verify(service).withdrawInterest(offerId);
  }

  @Test
  void releasedItemIdsDelegates() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(service.releasedInventoryItemIds(List.of(a, b))).thenReturn(java.util.Set.of(a));

    assertThat(controller.releasedItemIds(List.of(a, b))).containsExactly(a);
  }

  @Test
  void releasableItemsDelegates() {
    List<MaterialExchangeReleasableItemDto> items =
        List.of(
            new MaterialExchangeReleasableItemDto(
                UUID.randomUUID(), "Agricium", QuantityType.SCU, 796, 340.0, "Lorville", false));
    when(service.myReleasableItems("agri")).thenReturn(items);

    assertThat(controller.releasableItems("agri")).isSameAs(items);
  }

  private MaterialExchangeOfferDto sampleDto() {
    return new MaterialExchangeOfferDto(
        offerId,
        new MaterialReferenceDto(UUID.randomUUID(), "Agricium", QuantityType.SCU),
        new UserReferenceDto(UUID.randomUUID(), "Anbieter", "Anbieter", "Anbieter", null),
        null,
        false,
        false,
        796,
        340.0,
        Instant.now(),
        "tausche gegen **Titanium**",
        0,
        null,
        false,
        MaterialExchangeOfferStatus.ACTIVE,
        0L);
  }
}
