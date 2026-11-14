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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.bank.api.BankOwnRequestReads;
import de.greluc.krt.profit.basetool.backend.bank.api.BankOwnRequestReads.OwnRequest;
import de.greluc.krt.profit.basetool.backend.bank.api.BankOwnRequestReads.OwnRequestPage;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankRequestDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankRequestPageDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangePatchChangeRefDto;
import de.greluc.krt.profit.basetool.backend.patchchange.api.PatchChangeLabels;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The member's own requests name the patch change whose currency wipe cancelled one as {@code
 * cancelledByPatch}, with one label read per page and none for a page without such a request, and
 * leave the field out for every other request (REQ-BXCH-003, REQ-PATCH-042).
 */
@ExtendWith(MockitoExtension.class)
class ExchangeBankRequestServiceTest {

  private static final UUID MEMBER = UUID.randomUUID();

  private static final ExchangeCaller CALLER =
      new ExchangeCaller(MEMBER, "versekit", "k".repeat(43));

  private static final int LIMIT = 50;

  @Mock private BankOwnRequestReads requestReads;
  @Mock private ExchangeFeedReader feedReader;
  @Mock private ExchangeBankMetrics metrics;
  @Mock private PatchChangeLabels patchChangeLabels;

  private ExchangeBankRequestService service;

  @BeforeEach
  void wireTheService() {
    service = new ExchangeBankRequestService(requestReads, feedReader, metrics, patchChangeLabels);
    when(feedReader.snapshotStart()).thenReturn(ExchangeFeedPosition.START);
    when(requestReads.openRequestCap()).thenReturn(20);
  }

  @Test
  void aSnapshotNamesThePatchChangeThatCancelledARequestWithOneRead() {
    UUID patchChange = UUID.randomUUID();
    when(requestReads.ownRequests(MEMBER, null, LIMIT))
        .thenReturn(new OwnRequestPage(List.of(cancelled(patchChange), cancelled(null)), false));
    when(patchChangeLabels.labels(Set.of(patchChange))).thenReturn(Map.of(patchChange, "4.4"));

    ExchangeBankRequestPageDto page = service.page(CALLER, null, LIMIT);

    assertThat(page.items())
        .extracting(ExchangeBankRequestDto::cancelledByPatch)
        .containsExactly(new ExchangePatchChangeRefDto(patchChange, "4.4"), null);
    verify(patchChangeLabels).labels(Set.of(patchChange));
  }

  @Test
  void aSnapshotWithoutAPatchCancellationReadsNoLabel() {
    when(requestReads.ownRequests(MEMBER, null, LIMIT))
        .thenReturn(new OwnRequestPage(List.of(cancelled(null)), false));

    service.page(CALLER, null, LIMIT);

    verifyNoInteractions(patchChangeLabels);
  }

  @Test
  void theFieldIsLeftOutOfEveryOtherRequest() {
    UUID patchChange = UUID.randomUUID();
    when(requestReads.ownRequests(MEMBER, null, LIMIT))
        .thenReturn(new OwnRequestPage(List.of(cancelled(patchChange), cancelled(null)), false));
    when(patchChangeLabels.labels(Set.of(patchChange))).thenReturn(Map.of(patchChange, "4.4"));

    JsonNode items =
        JsonMapper.builder().build().valueToTree(service.page(CALLER, null, LIMIT)).path("items");

    assertThat(items.get(0).path("cancelledByPatch").path("id").asString())
        .isEqualTo(patchChange.toString());
    assertThat(items.get(0).path("cancelledByPatch").path("label").asString()).isEqualTo("4.4");
    assertThat(items.get(1).has("cancelledByPatch")).isFalse();
    assertThat(items.get(1).has("rejectReason")).as("the other fields stay explicit").isTrue();
  }

  /**
   * One of the member's cancelled withdrawals.
   *
   * @param patchChange the patch change whose currency wipe cancelled it, or {@code null} for one
   *     the member withdrew
   * @return the request as the bank reads it
   */
  private static @NotNull OwnRequest cancelled(@Nullable UUID patchChange) {
    return new OwnRequest(
        UUID.randomUUID(),
        1L,
        "CANCELLED",
        Instant.parse("2026-11-02T18:00:00Z"),
        Instant.parse("2026-11-06T12:00:00Z"),
        BankBookingRequestType.WITHDRAWAL,
        new BigDecimal("25000"),
        null,
        null,
        new BankOwnRequestReads.Account(UUID.randomUUID(), "KB-0042", "Staffel Nord", "NORD"),
        null,
        false,
        null,
        false,
        null,
        null,
        false,
        null,
        patchChange);
  }
}
