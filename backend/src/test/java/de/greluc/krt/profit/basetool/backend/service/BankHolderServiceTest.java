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

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.BankHolderMapper;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.model.BankHolder;
import de.greluc.krt.profit.basetool.backend.model.BankTransactionType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BankHolderBookingDto;
import de.greluc.krt.profit.basetool.backend.model.dto.request.RegisterBankHolderRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateBankHolderRequest;
import de.greluc.krt.profit.basetool.backend.model.projection.BankCounterLeg;
import de.greluc.krt.profit.basetool.backend.model.projection.BankHolderBookingRow;
import de.greluc.krt.profit.basetool.backend.model.projection.BankHolderLeg;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit tests for {@link BankHolderService}: registration with the effective-name handle snapshot
 * (REQ-BANK-003 — the snapshot is what survives user deletion), the one-holder-per-user pre-check,
 * the activity toggle with optimistic-lock check, and the audit rows.
 */
@ExtendWith(MockitoExtension.class)
class BankHolderServiceTest {

  @Mock private BankHolderRepository holderRepository;
  @Mock private BankHolderPostingRepository holderPostingRepository;
  @Mock private BankPostingRepository postingRepository;
  @Mock private UserRepository userRepository;
  @Mock private BankHolderMapper bankHolderMapper;
  @Mock private BankAuditService bankAuditService;

  @InjectMocks private BankHolderService bankHolderService;

  private final UUID userId = UUID.randomUUID();

  @BeforeEach
  void quietMapper() {
    lenient()
        .when(bankHolderMapper.toDto(any(BankHolder.class), any(BigDecimal.class)))
        .thenReturn(null);
  }

  @Test
  void registerHolder_snapshotsTheEffectiveNameAsHandle() {
    User user = new User();
    user.setId(userId);
    user.setUsername("greluc_raw");
    user.setDisplayName("greluc");
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(holderRepository.existsByUserId(userId)).thenReturn(false);
    when(holderRepository.save(any(BankHolder.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    bankHolderService.registerHolder(new RegisterBankHolderRequest(userId));

    ArgumentCaptor<BankHolder> saved = ArgumentCaptor.forClass(BankHolder.class);
    verify(holderRepository).save(saved.capture());
    assertEquals("greluc", saved.getValue().getHandle());
    assertTrue(saved.getValue().isActive());
    verify(bankAuditService)
        .record(eq(BankAuditEventType.HOLDER_REGISTERED), any(), any(), eq(userId), eq("greluc"));
  }

  @Test
  void registerHolder_rejectsSecondRowForSameUser() {
    User user = new User();
    user.setId(userId);
    user.setUsername("greluc");
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(holderRepository.existsByUserId(userId)).thenReturn(true);

    assertThrows(
        DuplicateEntityException.class,
        () -> bankHolderService.registerHolder(new RegisterBankHolderRequest(userId)));
    verify(holderRepository, never()).save(any());
  }

  @Test
  void updateHolder_deactivationIsAudited() {
    UUID holderId = UUID.randomUUID();
    BankHolder holder = new BankHolder();
    holder.setId(holderId);
    holder.setHandle("carol");
    holder.setActive(true);
    holder.setVersion(4L);
    when(holderRepository.findById(holderId)).thenReturn(Optional.of(holder));
    when(holderRepository.save(any(BankHolder.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(holderPostingRepository.holderTotal(holderId)).thenReturn(BigDecimal.ZERO);

    bankHolderService.updateHolder(holderId, new UpdateBankHolderRequest(false, 4L));

    verify(bankAuditService)
        .record(eq(BankAuditEventType.HOLDER_DEACTIVATED), any(), any(), any(), eq("carol"));
  }

  @Test
  void updateHolder_staleVersionFailsFastWith409() {
    UUID holderId = UUID.randomUUID();
    BankHolder holder = new BankHolder();
    holder.setId(holderId);
    holder.setHandle("carol");
    holder.setVersion(9L);
    when(holderRepository.findById(holderId)).thenReturn(Optional.of(holder));

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> bankHolderService.updateHolder(holderId, new UpdateBankHolderRequest(false, 8L)));
    verify(holderRepository, never()).save(any());
  }

  @Test
  void getHolder_pairsTheGlobalCustodyTotal() {
    UUID holderId = UUID.randomUUID();
    BankHolder holder = new BankHolder();
    holder.setId(holderId);
    holder.setHandle("greluc");
    BigDecimal total = new BigDecimal("1000000");
    when(holderRepository.findById(holderId)).thenReturn(Optional.of(holder));
    when(holderPostingRepository.holderTotal(holderId)).thenReturn(total);

    bankHolderService.getHolder(holderId);

    verify(bankHolderMapper).toDto(holder, total);
  }

  @Test
  void getHolder_missingHolder_throwsNotFound() {
    UUID holderId = UUID.randomUUID();
    when(holderRepository.findById(holderId)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> bankHolderService.getHolder(holderId));
  }

  @Test
  void getHolderBookings_resolvesAccountForDepositAndCounterHolderForUmbuchung() {
    UUID holderId = UUID.randomUUID();
    UUID tx1 = UUID.randomUUID();
    UUID tx2 = UUID.randomUUID();
    Instant now = Instant.parse("2026-06-10T18:30:00Z");
    when(holderRepository.findById(holderId)).thenReturn(Optional.of(new BankHolder()));

    BankHolderBookingRow deposit =
        new BankHolderBookingRow(
            UUID.randomUUID(),
            tx1,
            BankTransactionType.DEPOSIT,
            new BigDecimal("1000"),
            "Missionsertrag",
            now,
            null,
            BigDecimal.ZERO);
    BankHolderBookingRow umbuchung =
        new BankHolderBookingRow(
            UUID.randomUUID(),
            tx2,
            BankTransactionType.HOLDER_TRANSFER,
            new BigDecimal("-500"),
            "Ausgleich",
            now,
            null,
            BigDecimal.ZERO);
    when(holderPostingRepository.findHolderBookings(eq(holderId), any()))
        .thenReturn(new PageImpl<>(List.of(deposit, umbuchung)));

    when(postingRepository.findLegsByTransactionIds(any()))
        .thenReturn(
            List.of(
                new BankCounterLeg(
                    tx1,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "KB-0001",
                    "Staffel IRIDIUM",
                    new BigDecimal("1000"))));
    when(holderPostingRepository.findHolderLegsByTransactionIds(any()))
        .thenReturn(
            List.of(
                new BankHolderLeg(tx1, holderId, "greluc", new BigDecimal("1000")),
                new BankHolderLeg(tx2, holderId, "greluc", new BigDecimal("-500")),
                new BankHolderLeg(tx2, UUID.randomUUID(), "carol", new BigDecimal("500"))));

    List<BankHolderBookingDto> rows =
        bankHolderService.getHolderBookings(holderId, PageRequest.of(0, 20)).getContent();

    assertEquals(2, rows.size());
    BankHolderBookingDto depositRow = rows.get(0);
    assertEquals("KB-0001", depositRow.counterAccountNo());
    assertEquals("Staffel IRIDIUM", depositRow.counterAccountName());
    assertNull(depositRow.counterHolderHandle());
    BankHolderBookingDto umbuchungRow = rows.get(1);
    assertEquals("carol", umbuchungRow.counterHolderHandle());
    assertNull(umbuchungRow.counterAccountNo());
    assertEquals(0, umbuchungRow.transferFee().signum(), "Umbuchung is fee-free");
  }

  @Test
  void getHolderBookings_missingHolder_throwsNotFound() {
    UUID holderId = UUID.randomUUID();
    when(holderRepository.findById(holderId)).thenReturn(Optional.empty());

    assertThrows(
        NotFoundException.class,
        () -> bankHolderService.getHolderBookings(holderId, PageRequest.of(0, 20)));
    verify(holderPostingRepository, never()).findHolderBookings(any(), any());
  }
}
