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

package de.greluc.krt.profit.basetool.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.FinanceType;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionFinanceEntry;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Execution-level coverage for {@link MissionFinanceEntryRepository#aggregateFinanceByMission}
 * against the real Postgres test container (Flyway-migrated schema). The unit tests ({@code
 * MissionFinanceEntryServiceTest}) only <em>mock</em> this method, so this is what actually
 * validates the JPQL constructor-expression aggregate: that Hibernate parses the {@code sum(case
 * ...)} / {@code count(case ...)} query, executes it, and instantiates the {@link
 * FinanceEntryAggregate} record from the numeric/bigint results — plus the SQL semantics the
 * service relies on: a {@code SUM} over an absent type is {@code NULL} (coalesced to zero by the
 * service), and counts are per-type. Closes the ADR-0078 finance-summary follow-up's last gap.
 *
 * <p>The Testcontainer is shared and other suites commit rows, so every mission id is random and
 * the aggregate is scoped to one mission — assertions only see the rows created here. Class-level
 * {@code @Transactional} rolls each test back; the aggregate query auto-flushes the pending inserts
 * before it runs, so it sees them within the same transaction.
 *
 * <p>Also covers the operation finance roll-up queries added in #1121/#1124: the grouped
 * per-mission finance aggregate ({@link
 * MissionFinanceEntryRepository#aggregateFinanceByMissionIds}), the grouped refinery profit
 * aggregate ({@code RefineryOrderRepository#aggregateProfitByMissionIds}) and the
 * status/recency-bounded operation picker ({@code OperationRepository#findAllReferenceScoped}) —
 * all raw JPQL that only executes for real against the container.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MissionFinanceEntryRepositoryIntegrationTest {

  @Autowired private MissionFinanceEntryRepository financeEntryRepository;
  @Autowired private MissionRepository missionRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private MissionParticipantRepository participantRepository;
  @Autowired private RefineryOrderRepository refineryOrderRepository;
  @Autowired private OperationRepository operationRepository;

  @Test
  void aggregateFinanceByMission_sumsAndCountsPerType() {
    Mission mission = newMission();
    MissionParticipant participant = newParticipant(mission);
    saveEntry(mission, participant, FinanceType.INCOME, new BigDecimal("500.00"));
    saveEntry(mission, participant, FinanceType.INCOME, new BigDecimal("75.00"));
    saveEntry(mission, participant, FinanceType.EXPENSE, new BigDecimal("150.00"));

    FinanceEntryAggregate agg = financeEntryRepository.aggregateFinanceByMission(mission.getId());

    assertThat(agg.incomeSum()).isEqualByComparingTo("575");
    assertThat(agg.incomeCount()).isEqualTo(2L);
    assertThat(agg.expenseSum()).isEqualByComparingTo("150");
    assertThat(agg.expenseCount()).isEqualTo(1L);
  }

  @Test
  void aggregateFinanceByMission_absentType_yieldsNullSumAndZeroCount() {
    Mission mission = newMission();
    MissionParticipant participant = newParticipant(mission);
    saveEntry(mission, participant, FinanceType.INCOME, new BigDecimal("42.00"));

    FinanceEntryAggregate agg = financeEntryRepository.aggregateFinanceByMission(mission.getId());

    // A SQL SUM over an empty set is NULL — the service coalesces it to zero.
    assertThat(agg.expenseSum()).isNull();
    assertThat(agg.expenseCount()).isEqualTo(0L);
    assertThat(agg.incomeSum()).isEqualByComparingTo("42");
    assertThat(agg.incomeCount()).isEqualTo(1L);
  }

  @Test
  void aggregateFinanceByMission_noEntries_yieldsNullSumsAndZeroCounts() {
    Mission mission = newMission();

    FinanceEntryAggregate agg = financeEntryRepository.aggregateFinanceByMission(mission.getId());

    assertThat(agg.incomeSum()).isNull();
    assertThat(agg.incomeCount()).isEqualTo(0L);
    assertThat(agg.expenseSum()).isNull();
    assertThat(agg.expenseCount()).isEqualTo(0L);
  }

  @Test
  void aggregateFinanceByMissionIds_groupsSumsPerMission() {
    // #1121: the operation finance roll-up sums per mission in ONE grouped query. Validates the
    // grouped constructor-expression JPQL against real Postgres (the unit tests only mock it).
    Mission m1 = newMission();
    MissionParticipant p1 = newParticipant(m1);
    saveEntry(m1, p1, FinanceType.INCOME, new BigDecimal("500.00"));
    saveEntry(m1, p1, FinanceType.EXPENSE, new BigDecimal("100.00"));

    Mission m2 = newMission();
    MissionParticipant p2 = newParticipant(m2);
    saveEntry(m2, p2, FinanceType.EXPENSE, new BigDecimal("200.00"));

    Mission m3 = newMission(); // no entries -> no aggregate row at all

    List<MissionFinanceGroupAggregate> aggs =
        financeEntryRepository.aggregateFinanceByMissionIds(
            List.of(m1.getId(), m2.getId(), m3.getId()));

    assertThat(aggs).hasSize(2); // m3 contributes no row
    MissionFinanceGroupAggregate a1 =
        aggs.stream().filter(a -> a.missionId().equals(m1.getId())).findFirst().orElseThrow();
    assertThat(a1.incomeSum()).isEqualByComparingTo("500");
    assertThat(a1.expenseSum()).isEqualByComparingTo("100");
    MissionFinanceGroupAggregate a2 =
        aggs.stream().filter(a -> a.missionId().equals(m2.getId())).findFirst().orElseThrow();
    // No INCOME row for m2 -> SUM over the empty set is NULL (coalesced to zero by the service).
    assertThat(a2.incomeSum()).isNull();
    assertThat(a2.expenseSum()).isEqualByComparingTo("200");
  }

  @Test
  void aggregateProfitByMissionIds_parsesAndExecutes() {
    // #1121: smoke-test the grouped refinery profit JPQL (coalesce(sales) - coalesce(expenses) -
    // coalesce(other), grouped per mission). A random, never-persisted mission id yields no row —
    // enough to prove Hibernate parses and executes the query on real Postgres.
    List<RefineryMissionProfitAggregate> aggs =
        refineryOrderRepository.aggregateProfitByMissionIds(List.of(UUID.randomUUID()));
    assertThat(aggs).isEmpty();
  }

  @Test
  void findAllReferenceScoped_withStatusRecencyBound_parsesAndExecutes() {
    // #1124: smoke-test the operation-picker query after adding the PLANNED/ACTIVE-always +
    // terminal-within-cutoff status bound. Admin-all-scope + a now cutoff exercises the new WHERE;
    // the assertion only proves the JPQL parses and executes (rows from other suites may appear).
    assertThat(
            operationRepository.findAllReferenceScoped(
                true, null, List.of(), false, null, Instant.now()))
        .isNotNull();
  }

  private Mission newMission() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    Squadron squadron = new Squadron();
    squadron.setName("Fin-Agg-" + tag);
    squadron.setShorthand("FA" + tag);
    OrgUnit owner = squadronRepository.save(squadron);

    Mission mission = new Mission();
    mission.setName("Fin-Agg-Mission-" + tag);
    mission.setStatus("ACTIVE");
    mission.setIsInternal(false);
    mission.setOwningOrgUnit(owner);
    return missionRepository.save(mission);
  }

  private MissionParticipant newParticipant(Mission mission) {
    MissionParticipant participant = new MissionParticipant();
    participant.setMission(mission);
    participant.setGuestName("Aggregate Tester");
    return participantRepository.save(participant);
  }

  private void saveEntry(
      Mission mission, MissionParticipant participant, FinanceType type, BigDecimal amount) {
    financeEntryRepository.save(
        MissionFinanceEntry.builder()
            .mission(mission)
            .participant(participant)
            .type(type)
            .amount(amount)
            .build());
  }
}
