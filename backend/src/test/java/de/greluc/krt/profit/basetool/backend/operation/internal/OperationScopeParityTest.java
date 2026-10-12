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

package de.greluc.krt.profit.basetool.backend.operation.internal;

import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopeParityCallers;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Parity of the operation list query and the operation read gate (REQ-DATA-010, plan section 5.4):
 * over the caller matrix of the access-policy differential tests and operations of every unit and
 * of none, {@code OperationRepository#findAllScoped} never lists an operation {@link
 * OperationAccessPolicy#canSeeOperation} refuses, and the two agree on every operation without
 * participants, for callers that count as a member or above and for those that do not.
 */
@SpringBootTest
@Transactional
class OperationScopeParityTest {

  @Autowired private OperationRepository operationRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private UserRepository userRepository;

  @Test
  void theListAndTheGateWidenTogetherForMembers() {
    run(true);
  }

  @Test
  void theListAndTheGateWidenTogetherForCallersBelowMember() {
    run(false);
  }

  private void run(boolean memberOrAbove) {
    ScopeParityCallers.Units units = ScopeParityCallers.persistUnits(squadronRepository);
    UUID callerId = ScopeParityCallers.persistUser(userRepository, "caller").getId();
    List<ScopeParityCallers.Row> rows =
        List.of(
            row("operation of A", operation(units.unitA())),
            row("operation of B", operation(units.unitB())),
            row("operation of child C", operation(units.childC())),
            row("ownerless operation", operation(null)));
    operationRepository.flush();

    ScopeParityCallers.check(
        units,
        callerId,
        memberOrAbove,
        rows,
        wired ->
            operationRepository
                .findAllScoped(
                    wired.predicate().adminAllScope(),
                    wired.predicate().activeOrgUnitId(),
                    wired.predicate().memberOrgUnitIds(),
                    wired.authHelper().isMemberOrAbove(),
                    callerId,
                    PageRequest.of(0, 500))
                .stream()
                .map(Operation::getId)
                .collect(Collectors.toSet()),
        (wired, id) ->
            new OperationAccessPolicy(
                    wired.ownerScopeService(), wired.authHelper(), operationRepository)
                .canSeeOperation(id));
  }

  private static ScopeParityCallers.Row row(String name, Operation operation) {
    return new ScopeParityCallers.Row(name, operation.getId(), true);
  }

  private Operation operation(UUID unit) {
    Operation operation = new Operation();
    operation.setName("Parity-O-" + UUID.randomUUID());
    operation.setDescription("parity");
    operation.setStatus(OperationStatus.ACTIVE);
    operation.setOwningOrgUnit(unit == null ? null : squadronRepository.getReferenceById(unit));
    return operationRepository.save(operation);
  }
}
