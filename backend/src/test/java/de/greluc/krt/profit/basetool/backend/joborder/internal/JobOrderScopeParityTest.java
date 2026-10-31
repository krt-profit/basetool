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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopeParityCallers;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Parity of the job-order queue query and the job-order read gate (REQ-DATA-010, plan section 5.4):
 * over the caller matrix of the access-policy differential tests and orders of every responsible
 * unit and of a Spezialkommando, {@code
 * JobOrderRepository#findScopedOrdersWithMaterialRequirements} never lists an order {@link
 * JobOrderAccessPolicy#canSeeJobOrder(UUID)} refuses, and the two agree on every order, for callers
 * that reach the queue.
 */
@SpringBootTest
@Transactional
class JobOrderScopeParityTest {

  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private JobOrderHandoverRepository jobOrderHandoverRepository;
  @Autowired private JobOrderItemHandoverRepository jobOrderItemHandoverRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private SpecialCommandRepository specialCommandRepository;
  @Autowired private UserRepository userRepository;

  @Test
  void theListAndTheGateWidenTogether() {
    ScopeParityCallers.Units units = ScopeParityCallers.persistUnits(squadronRepository);
    UUID callerId = ScopeParityCallers.persistUser(userRepository, "caller").getId();
    SpecialCommand specialCommand = specialCommand();
    OrgUnit requester = squadronRepository.getReferenceById(units.unitA());
    List<ScopeParityCallers.Row> rows =
        List.of(
            row("order of A", order(squadronRepository.getReferenceById(units.unitA()), requester)),
            row("order of B", order(squadronRepository.getReferenceById(units.unitB()), requester)),
            row(
                "order of child C",
                order(squadronRepository.getReferenceById(units.childC()), requester)),
            row("order of a Spezialkommando", order(specialCommand, requester)));
    jobOrderRepository.flush();

    ScopeParityCallers.check(
        units,
        callerId,
        true,
        rows,
        wired ->
            wired.ownerScopeService().canViewJobOrders()
                ? jobOrderRepository
                    .findScopedOrdersWithMaterialRequirements(
                        List.of(JobOrderStatus.OPEN),
                        wired.predicate().adminAllScope(),
                        wired.predicate().activeOrgUnitId(),
                        wired.predicate().memberOrgUnitIds())
                    .stream()
                    .map(JobOrder::getId)
                    .collect(Collectors.toSet())
                : Collections.<UUID>emptySet(),
        (wired, id) ->
            new JobOrderAccessPolicy(
                    wired.ownerScopeService(),
                    wired.authHelper(),
                    jobOrderRepository,
                    jobOrderHandoverRepository,
                    jobOrderItemHandoverRepository)
                .canSeeJobOrder(id));
  }

  private static ScopeParityCallers.Row row(String name, JobOrder order) {
    return new ScopeParityCallers.Row(name, order.getId(), true);
  }

  private JobOrder order(OrgUnit responsible, OrgUnit requesting) {
    JobOrder order = new JobOrder();
    order.setResponsibleOrgUnit(responsible);
    order.setRequestingOrgUnit(requesting);
    order.setHandle("parity-" + UUID.randomUUID());
    return jobOrderRepository.save(order);
  }

  private SpecialCommand specialCommand() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    SpecialCommand command = new SpecialCommand();
    command.setName("Parity SK " + suffix);
    command.setShorthand("PS" + suffix.substring(0, 3));
    return specialCommandRepository.saveAndFlush(command);
  }
}
