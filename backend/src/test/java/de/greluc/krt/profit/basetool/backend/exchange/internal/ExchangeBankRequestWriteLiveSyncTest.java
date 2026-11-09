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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.bank.api.BankActor;
import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.bank.api.BankOwnRequestWrites;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankRequestCorrectionDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankRequestCreateDto;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A bank request written through the exchange reaches the open web pages as the browser forms'
 * writes do (REQ-XCH-030, REQ-BANK-068): every applied raise, correction and cancel announces the
 * staff queue and the org-unit bank, a refused one announces nothing.
 */
@ExtendWith(MockitoExtension.class)
class ExchangeBankRequestWriteLiveSyncTest {

  private static final UUID REQUEST = UUID.fromString("11111111-2222-4333-8444-555555555555");

  @Mock private ExchangeBankActors bankActors;
  @Mock private BankOwnRequestWrites requestWrites;
  @Mock private ExchangeBankMetrics metrics;
  @Mock private ExchangeLiveSync liveSync;
  @Mock private BankActor actor;

  @InjectMocks private ExchangeBankRequestWriteService service;

  private final ExchangeCaller caller =
      new ExchangeCaller(
          UUID.fromString("66666666-7777-4888-8999-000000000000"), "versekit", "key");

  private final BankOwnRequestWrites.Written written =
      new BankOwnRequestWrites.Written(REQUEST, "PENDING", 1L, new BigDecimal("500"));

  @BeforeEach
  void setUp() {
    when(bankActors.of(caller)).thenReturn(actor);
  }

  @Test
  void anAppliedRaiseCorrectionAndCancelEachAnnounceTheBankPages() {
    when(requestWrites.raise(eq(actor), any(), eq("versekit"), isNull())).thenReturn(written);
    when(requestWrites.correct(eq(actor), eq(REQUEST), any())).thenReturn(written);
    when(requestWrites.cancel(actor, REQUEST, 1L)).thenReturn(written);

    service.raise(
        caller,
        new ExchangeBankRequestCreateDto(
            BankBookingRequestType.DEPOSIT,
            REQUEST,
            null,
            new BigDecimal("500"),
            null,
            null,
            false,
            null),
        null);
    service.correct(
        caller,
        REQUEST,
        new ExchangeBankRequestCorrectionDto(new BigDecimal("600"), null, null, null, 1L));
    service.cancel(caller, REQUEST, 1L);

    verify(liveSync, times(3)).bankRequestsChanged();
  }

  @Test
  void aRefusedWriteAnnouncesNothing() {
    when(requestWrites.cancel(eq(actor), eq(REQUEST), anyLong()))
        .thenThrow(new NotFoundException("hidden"));

    assertThatThrownBy(() -> service.cancel(caller, REQUEST, 1L))
        .isInstanceOf(NotFoundException.class);

    verify(liveSync, never()).bankRequestsChanged();
    verify(metrics).countWrite(anyString(), anyString());
  }
}
