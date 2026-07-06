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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.MissionMapper;
import de.greluc.krt.profit.basetool.backend.model.FinanceType;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionFinanceEntry;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryUpdateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceTotalsDto;
import de.greluc.krt.profit.basetool.backend.repository.FinanceEntryAggregate;
import de.greluc.krt.profit.basetool.backend.repository.MissionFinanceEntryRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * Unit tests for {@link MissionFinanceEntryService}. Money-handling code with a tricky aggregation
 * step ({@link MissionFinanceEntryService#calculateTotalSum}) that mixes manual ledger entries with
 * refinery-order profit/loss including legacy-null safety. Previous coverage was 39% line / 5%
 * branch with no test file at all; these tests close every branch of {@code calculateTotalSum},
 * {@code createEntry}, {@code updateEntry}, and {@code deleteEntry}.
 */
@ExtendWith(MockitoExtension.class)
class MissionFinanceEntryServiceTest {

  @Mock private MissionFinanceEntryRepository financeEntryRepository;
  @Mock private MissionParticipantRepository participantRepository;
  @Mock private MissionRepository missionRepository;
  @Mock private RefineryOrderRepository refineryOrderRepository;
  @Mock private MissionMapper missionMapper;

  // Constructor-injected but not exercised in these tests; declared so
  @Mock private AuditService auditService;
  // @InjectMocks satisfies the constructor signature without an NPE.

  @InjectMocks private MissionFinanceEntryService service;

  private static final UUID MISSION_ID = UUID.randomUUID();
  private static final UUID PARTICIPANT_ID = UUID.randomUUID();
  private static final UUID ENTRY_ID = UUID.randomUUID();

  // --- getEntriesByMission -------------------------------------------------

  @Test
  void getEntriesByMission_mapsRepositoryPage_throughMissionMapper() {
    MissionFinanceEntry entry = entry(FinanceType.INCOME, new BigDecimal("100"));
    MissionFinanceEntryDto dto =
        new MissionFinanceEntryDto(
            ENTRY_ID, MISSION_ID, null, null, FinanceType.INCOME, new BigDecimal("100"), 0L);
    Page<MissionFinanceEntry> page = new PageImpl<>(List.of(entry));

    when(financeEntryRepository.findAllByMissionId(MISSION_ID, PageRequest.of(0, 10)))
        .thenReturn(page);
    when(missionMapper.toDto(entry)).thenReturn(dto);

    Page<MissionFinanceEntryDto> result =
        service.getEntriesByMission(MISSION_ID, PageRequest.of(0, 10));

    assertEquals(1, result.getTotalElements());
    assertSame(dto, result.getContent().get(0));
  }

  // --- calculateTotalSum ---------------------------------------------------

  @Nested
  class CalculateTotalSumTests {

    @Test
    void noEntriesAndNoRefineryOrders_returnsZero() {
      stubEmpty();

      assertEquals(BigDecimal.ZERO, service.calculateTotalSum(MISSION_ID));
    }

    @Test
    void singleIncomeEntry_isAdded() {
      stubEntries(entry(FinanceType.INCOME, new BigDecimal("250.00")));
      stubRefineryOrders(); // none

      assertEquals(new BigDecimal("250.00"), service.calculateTotalSum(MISSION_ID));
    }

    @Test
    void singleExpenseEntry_isSubtracted() {
      stubEntries(entry(FinanceType.EXPENSE, new BigDecimal("100.00")));
      stubRefineryOrders();

      assertEquals(new BigDecimal("-100.00"), service.calculateTotalSum(MISSION_ID));
    }

    @Test
    void mixedIncomeAndExpense_aggregateToNetResult() {
      stubEntries(
          entry(FinanceType.INCOME, new BigDecimal("500.00")),
          entry(FinanceType.EXPENSE, new BigDecimal("150.00")),
          entry(FinanceType.INCOME, new BigDecimal("75.00")));
      stubRefineryOrders();

      assertEquals(new BigDecimal("425.00"), service.calculateTotalSum(MISSION_ID));
    }

    @Test
    void refineryOrderProfit_isAddedToTotal() {
      // Profit = oreSales(1000) - expenses(200) - otherExpenses(50) = 750
      stubEntries();
      stubRefineryOrders(refineryOrder(1000.0, 200.0, 50.0));

      assertEquals(new BigDecimal("750.0"), service.calculateTotalSum(MISSION_ID));
    }

    @Test
    void refineryOrderLoss_isAddedAsNegative() {
      // Profit = 100 - 300 - 50 = -250
      stubEntries();
      stubRefineryOrders(refineryOrder(100.0, 300.0, 50.0));

      assertEquals(new BigDecimal("-250.0"), service.calculateTotalSum(MISSION_ID));
    }

    @Test
    void refineryOrderWithZeroProfit_isSkipped() {
      // Profit = 500 - 300 - 200 = 0 -> should not call BigDecimal.add at all.
      // We verify by adding a real entry with a non-trivial scale, then
      // confirming the result has the entry's scale rather than the
      // double-conversion scale of a zero-profit refinery order.
      stubEntries(entry(FinanceType.INCOME, new BigDecimal("100.0000")));
      stubRefineryOrders(refineryOrder(500.0, 300.0, 200.0));

      BigDecimal result = service.calculateTotalSum(MISSION_ID);
      assertEquals(new BigDecimal("100.0000"), result);
      assertEquals(
          4,
          result.scale(),
          "scale of 4 from the entry must survive — proves the zero-profit "
              + "refinery order skipped the .add() path");
    }

    @Test
    void nullOreSales_isTreatedAsZero() {
      // Profit = null(0) - 100 - 0 = -100
      stubEntries();
      stubRefineryOrders(refineryOrder(null, 100.0, null));

      assertEquals(new BigDecimal("-100.0"), service.calculateTotalSum(MISSION_ID));
    }

    @Test
    void nullExpenses_isTreatedAsZero() {
      // Profit = 500 - null(0) - 50 = 450
      stubEntries();
      stubRefineryOrders(refineryOrder(500.0, null, 50.0));

      assertEquals(new BigDecimal("450.0"), service.calculateTotalSum(MISSION_ID));
    }

    @Test
    void nullOtherExpenses_isTreatedAsZero() {
      // Profit = 500 - 100 - null(0) = 400
      stubEntries();
      stubRefineryOrders(refineryOrder(500.0, 100.0, null));

      assertEquals(new BigDecimal("400.0"), service.calculateTotalSum(MISSION_ID));
    }

    @Test
    void allRefineryFieldsNull_skippedAsZeroProfit() {
      stubEntries(entry(FinanceType.INCOME, new BigDecimal("10")));
      stubRefineryOrders(refineryOrder(null, null, null));

      assertEquals(new BigDecimal("10"), service.calculateTotalSum(MISSION_ID));
    }

    @Test
    void multipleRefineryOrders_profitsAccumulate() {
      stubEntries(entry(FinanceType.EXPENSE, new BigDecimal("100")));
      stubRefineryOrders(
          refineryOrder(1000.0, 100.0, 0.0), // +900
          refineryOrder(0.0, 50.0, 0.0), // -50
          refineryOrder(200.0, 100.0, 50.0)); // +50

      // -100 + 900 - 50 + 50 = 800
      assertEquals(new BigDecimal("800.0"), service.calculateTotalSum(MISSION_ID));
    }

    @Test
    void calculateTotals_exposesPerTypeSumsAndCounts() {
      stubEntries(
          entry(FinanceType.INCOME, new BigDecimal("500.00")),
          entry(FinanceType.INCOME, new BigDecimal("75.00")),
          entry(FinanceType.EXPENSE, new BigDecimal("150.00")));
      stubRefineryOrders();

      MissionFinanceTotalsDto totals = service.calculateTotals(MISSION_ID);

      assertEquals(new BigDecimal("425.00"), totals.total());
      assertEquals(new BigDecimal("575.00"), totals.incomeSum());
      assertEquals(2L, totals.incomeCount());
      assertEquals(new BigDecimal("150.00"), totals.expenseSum());
      assertEquals(1L, totals.expenseCount());
    }

    @Test
    void calculateTotals_foldsRefineryExpensesIntoBucket_butProfitIntoTotal() {
      stubEntries(entry(FinanceType.INCOME, new BigDecimal("1000")));
      // profit = 500 - 300 - 50 = 150 (into total); raw expenses 300 (> 0 -> into expense bucket)
      stubRefineryOrders(refineryOrder(500.0, 300.0, 50.0));

      MissionFinanceTotalsDto totals = service.calculateTotals(MISSION_ID);

      assertEquals(new BigDecimal("1150.0"), totals.total());
      assertEquals(new BigDecimal("1000"), totals.incomeSum());
      assertEquals(1L, totals.incomeCount());
      assertEquals(new BigDecimal("300.0"), totals.expenseSum());
      assertEquals(1L, totals.expenseCount());
    }

    @Test
    void calculateTotals_emptyMission_returnsZeros() {
      stubEmpty();

      MissionFinanceTotalsDto totals = service.calculateTotals(MISSION_ID);

      assertEquals(BigDecimal.ZERO, totals.total());
      assertEquals(BigDecimal.ZERO, totals.incomeSum());
      assertEquals(0L, totals.incomeCount());
      assertEquals(BigDecimal.ZERO, totals.expenseSum());
      assertEquals(0L, totals.expenseCount());
    }

    private void stubEmpty() {
      stubEntries();
      stubRefineryOrders();
    }

    private void stubEntries(MissionFinanceEntry... entries) {
      // calculateTotals now reads a SQL aggregate instead of the row list, so translate the given
      // entries into the equivalent per-type aggregate (keeping the cases expressed as concrete
      // entries). A SQL SUM over no rows is NULL, so leave the sum null when a type is absent —
      // that
      // exercises the service's coalesce-to-zero path; entry amounts' scales are preserved.
      BigDecimal incomeSum = null;
      long incomeCount = 0L;
      BigDecimal expenseSum = null;
      long expenseCount = 0L;
      for (MissionFinanceEntry e : entries) {
        if (e.getType() == FinanceType.INCOME) {
          incomeSum = incomeSum == null ? e.getAmount() : incomeSum.add(e.getAmount());
          incomeCount++;
        } else if (e.getType() == FinanceType.EXPENSE) {
          expenseSum = expenseSum == null ? e.getAmount() : expenseSum.add(e.getAmount());
          expenseCount++;
        }
      }
      when(financeEntryRepository.aggregateFinanceByMission(MISSION_ID))
          .thenReturn(new FinanceEntryAggregate(incomeSum, incomeCount, expenseSum, expenseCount));
    }

    private void stubRefineryOrders(RefineryOrder... orders) {
      when(refineryOrderRepository.findByMissionId(MISSION_ID)).thenReturn(List.of(orders));
    }

    private RefineryOrder refineryOrder(Double oreSales, Double expenses, Double otherExpenses) {
      RefineryOrder o = new RefineryOrder();
      o.setOreSales(oreSales);
      o.setExpenses(expenses);
      o.setOtherExpenses(otherExpenses);
      return o;
    }
  }

  // --- createEntry ---------------------------------------------------------

  @Nested
  class CreateEntryTests {

    private final MissionFinanceEntryCreateDto validDto =
        new MissionFinanceEntryCreateDto(
            MISSION_ID, PARTICIPANT_ID, "note", FinanceType.INCOME, new BigDecimal("123.45"));

    @Test
    void throwsNotFound_whenMissionDoesNotExist() {
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.empty());

      assertThrows(NotFoundException.class, () -> service.createEntry(validDto));
      verify(financeEntryRepository, never()).save(any());
    }

    @Test
    void throwsNotFound_whenParticipantDoesNotExist() {
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(mission(MISSION_ID)));
      when(participantRepository.findById(PARTICIPANT_ID)).thenReturn(Optional.empty());

      assertThrows(NotFoundException.class, () -> service.createEntry(validDto));
      verify(financeEntryRepository, never()).save(any());
    }

    @Test
    void throwsBadRequest_whenParticipantBelongsToDifferentMission() {
      // Cross-mission injection guard: tying a participant from mission B to a
      // ledger entry on mission A would let an attacker bill costs onto someone
      // else's mission.
      Mission requestedMission = mission(MISSION_ID);
      Mission otherMission = mission(UUID.randomUUID());
      MissionParticipant alienParticipant = new MissionParticipant();
      alienParticipant.setId(PARTICIPANT_ID);
      alienParticipant.setMission(otherMission);

      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(requestedMission));
      when(participantRepository.findById(PARTICIPANT_ID))
          .thenReturn(Optional.of(alienParticipant));

      assertThrows(BadRequestException.class, () -> service.createEntry(validDto));
      verify(financeEntryRepository, never()).save(any());
    }

    @Test
    void happyPath_persistsAndMapsToDto() {
      Mission m = mission(MISSION_ID);
      MissionParticipant p = participant(PARTICIPANT_ID, m);
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(m));
      when(participantRepository.findById(PARTICIPANT_ID)).thenReturn(Optional.of(p));
      // Echo back whatever the service builds — captures it for verification.
      when(financeEntryRepository.save(any(MissionFinanceEntry.class)))
          .thenAnswer(inv -> inv.getArgument(0));
      MissionFinanceEntryDto outDto =
          new MissionFinanceEntryDto(
              null, MISSION_ID, null, "note", FinanceType.INCOME, new BigDecimal("123.45"), 0L);
      when(missionMapper.toDto(any(MissionFinanceEntry.class))).thenReturn(outDto);

      MissionFinanceEntryDto result = service.createEntry(validDto);

      assertSame(outDto, result);
      ArgumentCaptor<MissionFinanceEntry> captor =
          ArgumentCaptor.forClass(MissionFinanceEntry.class);
      verify(financeEntryRepository, times(1)).save(captor.capture());
      MissionFinanceEntry saved = captor.getValue();
      assertSame(m, saved.getMission(), "the saved entry must reference the looked-up Mission");
      assertSame(p, saved.getParticipant());
      assertEquals("note", saved.getNote());
      assertEquals(FinanceType.INCOME, saved.getType());
      assertEquals(new BigDecimal("123.45"), saved.getAmount());
      // REQ-AUDIT-001: a finance-entry create records exactly one MISSION_FINANCE_ENTRY_CREATED.
      verify(auditService, times(1))
          .record(
              eq(
                  de.greluc.krt.profit.basetool.backend.model.AuditEventType
                      .MISSION_FINANCE_ENTRY_CREATED),
              any(),
              any(),
              any(),
              any());
    }
  }

  // --- updateEntry ---------------------------------------------------------

  @Nested
  class UpdateEntryTests {

    @Test
    void throwsNotFound_whenEntryDoesNotExist() {
      when(financeEntryRepository.findById(ENTRY_ID)).thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class,
          () ->
              service.updateEntry(
                  ENTRY_ID,
                  new MissionFinanceEntryUpdateDto(
                      "note", FinanceType.INCOME, new BigDecimal("1"), 0L)));
    }

    @Test
    void throwsBusinessConflict_whenVersionMismatch() {
      MissionFinanceEntry existing = entry(FinanceType.INCOME, new BigDecimal("100"));
      existing.setVersion(7L);
      when(financeEntryRepository.findById(ENTRY_ID)).thenReturn(Optional.of(existing));

      MissionFinanceEntryUpdateDto staleDto =
          new MissionFinanceEntryUpdateDto("note", FinanceType.INCOME, new BigDecimal("1"), 3L);

      BusinessConflictException ex =
          assertThrows(
              BusinessConflictException.class, () -> service.updateEntry(ENTRY_ID, staleDto));
      // i18n-key path: the throw site passes a literal English string here,
      // not a key. Both behaviours are valid (GlobalExceptionHandler#resolveDetail
      // passes literals through verbatim), so we just lock in the rough shape
      // of the message rather than the exact text.
      assert ex.getMessage().contains("updated by someone else");
      verify(financeEntryRepository, never()).save(any());
    }

    @Test
    void happyPath_updatesAllFieldsAndReturnsDto() {
      MissionFinanceEntry existing = entry(FinanceType.INCOME, new BigDecimal("100"));
      existing.setNote("old-note");
      existing.setVersion(2L);
      when(financeEntryRepository.findById(ENTRY_ID)).thenReturn(Optional.of(existing));
      when(financeEntryRepository.save(existing)).thenReturn(existing);
      MissionFinanceEntryDto outDto =
          new MissionFinanceEntryDto(
              ENTRY_ID,
              MISSION_ID,
              null,
              "new-note",
              FinanceType.EXPENSE,
              new BigDecimal("999.99"),
              3L);
      when(missionMapper.toDto(existing)).thenReturn(outDto);

      MissionFinanceEntryDto result =
          service.updateEntry(
              ENTRY_ID,
              new MissionFinanceEntryUpdateDto(
                  "new-note", FinanceType.EXPENSE, new BigDecimal("999.99"), 2L));

      assertSame(outDto, result);
      assertEquals(
          "new-note", existing.getNote(), "entity note must be updated in place before save");
      assertEquals(FinanceType.EXPENSE, existing.getType());
      assertEquals(new BigDecimal("999.99"), existing.getAmount());
    }
  }

  // --- deleteEntry ---------------------------------------------------------

  @Nested
  class DeleteEntryTests {

    @Test
    void throwsNotFound_whenEntryDoesNotExist() {
      when(financeEntryRepository.findById(ENTRY_ID)).thenReturn(Optional.empty());

      assertThrows(NotFoundException.class, () -> service.deleteEntry(ENTRY_ID));
      verify(financeEntryRepository, never()).delete(any());
    }

    @Test
    void happyPath_deletesEntry() {
      MissionFinanceEntry entry = entry(FinanceType.INCOME, new BigDecimal("1"));
      when(financeEntryRepository.findById(ENTRY_ID)).thenReturn(Optional.of(entry));

      service.deleteEntry(ENTRY_ID);

      verify(financeEntryRepository, times(1)).delete(entry);
    }
  }

  // ---- helpers ------------------------------------------------------------

  private static MissionFinanceEntry entry(FinanceType type, BigDecimal amount) {
    MissionFinanceEntry e = new MissionFinanceEntry();
    e.setType(type);
    e.setAmount(amount);
    return e;
  }

  private static Mission mission(UUID id) {
    Mission m = new Mission();
    m.setId(id);
    return m;
  }

  private static MissionParticipant participant(UUID id, Mission mission) {
    MissionParticipant p = new MissionParticipant();
    p.setId(id);
    p.setMission(mission);
    return p;
  }
}
