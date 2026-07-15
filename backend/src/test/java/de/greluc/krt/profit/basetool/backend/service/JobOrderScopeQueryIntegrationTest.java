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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration coverage for the Phase 3 (#343) visibility scope of {@link
 * JobOrderRepository#findScopedJobOrders}, exercised against the real Postgres test container so
 * the {@code TYPE(...) = SpecialCommand} discriminator predicate, the membership {@code IN} clause
 * and the SK-public escape are verified end-to-end rather than mocked.
 *
 * <p>Three orders are created — one responsible to squadron A (private), one to squadron B
 * (private), one to a Spezialkommando (public). The four caller classes from the acceptance matrix
 * are then replayed by feeding the corresponding {@link ScopePredicate} triple into the repository
 * query. The test container is shared across the suite and other tests commit job-order rows, so
 * the assertions use {@code contains} / {@code doesNotContain} on the freshly-created ids rather
 * than exact result counts.
 */
@SpringBootTest
@ActiveProfiles("test")
class JobOrderScopeQueryIntegrationTest {

  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private SpecialCommandRepository specialCommandRepository;
  @Autowired private OrgUnitRepository orgUnitRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DataSource dataSource;

  private static final List<JobOrderStatus> ALL_STATUSES = List.of(JobOrderStatus.values());
  private static final PageRequest ALL_ROWS = PageRequest.of(0, 10_000);

  private UUID squadronAId;
  private UUID squadronBId;
  private UUID skId;
  private UUID orderRespA;
  private UUID orderRespB;
  private UUID orderRespSk;

  @BeforeEach
  void seed() {
    transactionTemplate.executeWithoutResult(
        status -> {
          String tag = UUID.randomUUID().toString().substring(0, 8);
          Squadron sqA = newSquadron("Scope-A-" + tag, "A" + tag);
          Squadron sqB = newSquadron("Scope-B-" + tag, "B" + tag);
          SpecialCommand sk = newSpecialCommand("Scope-SK-" + tag, "S" + tag);
          squadronAId = sqA.getId();
          squadronBId = sqB.getId();
          skId = sk.getId();

          // Every order's requester is squadron A, so the squadron-B display filter can only match
          // the order that is *responsible* to B — isolating the display-filter semantics from the
          // requester side.
          orderRespA = newOrder(sqA, sqA).getId();
          orderRespB = newOrder(sqB, sqA).getId();
          orderRespSk = newOrder(sk, sqA).getId();
        });
  }

  @Test
  void memberOfSquadronA_seesOwnPrivateAndAllSk_butNotForeignPrivate() {
    Set<UUID> visible = visibleIds(new ScopePredicate(false, null, Set.of(squadronAId)));

    assertThat(visible).contains(orderRespA, orderRespSk);
    assertThat(visible).doesNotContain(orderRespB);
  }

  @Test
  void adminWithoutPin_seesEveryOrder() {
    Set<UUID> visible = visibleIds(new ScopePredicate(true, null, Set.of()));

    assertThat(visible).contains(orderRespA, orderRespB, orderRespSk);
  }

  @Test
  void adminPinnedToSquadronA_seesPinnedPrivateAndAllSk_butNotForeignPrivate() {
    Set<UUID> visible = visibleIds(new ScopePredicate(false, squadronAId, Set.of()));

    assertThat(visible).contains(orderRespA, orderRespSk);
    assertThat(visible).doesNotContain(orderRespB);
  }

  @Test
  void squadronDisplayFilter_narrowsWithinScope() {
    // Admin all-scope so the security filter is wide open; the squadronId display filter then keeps
    // only orders whose responsible OR requesting side is squadron B — that is the B-responsible
    // order alone (all requesters are squadron A here).
    Set<UUID> visible =
        jobOrderRepository
            .findScopedJobOrders(
                ALL_STATUSES, false, Set.of(squadronBId), true, null, Set.of(), ALL_ROWS)
            .stream()
            .map(JobOrder::getId)
            .collect(Collectors.toSet());

    assertThat(visible).contains(orderRespB);
    assertThat(visible).doesNotContain(orderRespA, orderRespSk);
  }

  @Test
  void v130_tightensResponsibleOrgUnitToNotNull() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String nullable =
        jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns WHERE table_name = 'job_order' AND"
                + " column_name = 'responsible_org_unit_id'",
            String.class);
    assertThat(nullable).isEqualTo("NO");
  }

  @Test
  void countProfitEligibleByIdIn_countsProfitEligibleAcrossBothKinds() {
    // Backs OwnerScopeService.canViewJobOrders(): a profit-eligible Squadron and a profit-eligible
    // SK both count, a default (non-profit) Squadron does not. Validates the JPQL attribute name
    // and
    // the single-table cross-kind reach end-to-end against the real schema.
    String tag = UUID.randomUUID().toString().substring(0, 8);
    Squadron profit = new Squadron();
    profit.setName("Profit-" + tag);
    profit.setShorthand("P" + tag);
    profit.setProfitEligible(true);
    Squadron nonProfit = new Squadron();
    nonProfit.setName("NonProfit-" + tag);
    nonProfit.setShorthand("N" + tag);
    SpecialCommand profitSk = new SpecialCommand();
    profitSk.setName("ProfitSK-" + tag);
    profitSk.setShorthand("X" + tag);
    profitSk.setProfitEligible(true);
    UUID profitId = squadronRepository.save(profit).getId();
    UUID nonProfitId = squadronRepository.save(nonProfit).getId();
    UUID profitSkId = specialCommandRepository.save(profitSk).getId();

    assertThat(
            orgUnitRepository.countProfitEligibleByIdIn(Set.of(profitId, nonProfitId, profitSkId)))
        .isEqualTo(2L);
    assertThat(orgUnitRepository.countProfitEligibleByIdIn(Set.of(nonProfitId))).isZero();
    assertThat(orgUnitRepository.countProfitEligibleByIdIn(Set.of(profitSkId))).isEqualTo(1L);
  }

  private Set<UUID> visibleIds(ScopePredicate scope) {
    return jobOrderRepository
        .findScopedJobOrders(
            ALL_STATUSES,
            true,
            Set.of(new UUID(0L, 0L)),
            scope.adminAllScope(),
            scope.activeOrgUnitId(),
            scope.memberOrgUnitIds(),
            ALL_ROWS)
        .stream()
        .map(JobOrder::getId)
        .collect(Collectors.toSet());
  }

  private Squadron newSquadron(String name, String shorthand) {
    Squadron s = new Squadron();
    s.setName(name);
    s.setShorthand(shorthand);
    return squadronRepository.save(s);
  }

  private SpecialCommand newSpecialCommand(String name, String shorthand) {
    SpecialCommand sc = new SpecialCommand();
    sc.setName(name);
    sc.setShorthand(shorthand);
    return specialCommandRepository.save(sc);
  }

  private JobOrder newOrder(OrgUnit responsible, OrgUnit requesting) {
    JobOrder o =
        JobOrder.builder()
            .responsibleOrgUnit(responsible)
            .requestingOrgUnit(requesting)
            .handle("scope-test")
            .status(JobOrderStatus.OPEN)
            .build();
    return jobOrderRepository.save(o);
  }
}
