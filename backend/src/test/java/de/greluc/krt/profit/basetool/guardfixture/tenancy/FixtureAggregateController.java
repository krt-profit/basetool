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

package de.greluc.krt.profit.basetool.guardfixture.tenancy;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fixture controller writing the marked aggregate. It lives outside the scanned backend package, so
 * no application context registers it; only the tenancy guard's own test imports it.
 */
@RestController
@RequestMapping("/api/v1/fixture-aggregates")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class FixtureAggregateController {

  /** The writer of the marked aggregate, which selects this controller. */
  private final FixtureAggregateWriter writer;

  /**
   * Planted violation: an {@code or} branch beside the scope call leaves the write open.
   *
   * @param id the aggregate id
   */
  @PutMapping("/{id}")
  @PreAuthorize("isAuthenticated() or @ownerScopeService.canEditSquadron(#id)")
  public void openBranch(@PathVariable UUID id) {
    writer.touch(id);
  }

  /**
   * Planted violation: only the class-level {@code isAuthenticated()} applies.
   *
   * @param id the aggregate id
   */
  @DeleteMapping("/{id}")
  public void classGateOnly(@PathVariable UUID id) {
    writer.touch(id);
  }

  /**
   * Correct: the role gate is conjoined with the scope call.
   *
   * @param id the aggregate id
   */
  @PatchMapping("/{id}")
  @PreAuthorize("hasRole('OFFICER') and @ownerScopeService.canEditSquadron(#id)")
  public void scopeGated(@PathVariable UUID id) {
    writer.touch(id);
  }

  /**
   * Correct: an admin-only gate.
   *
   * @param id the aggregate id
   */
  @PostMapping("/{id}/archive")
  @PreAuthorize("hasRole('ADMIN')")
  public void adminOnly(@PathVariable UUID id) {
    writer.touch(id);
  }

  /**
   * Listed as service-gated in the fixture test, and its service checks scope.
   *
   * @param id the aggregate id
   */
  @PostMapping("/{id}/checked")
  public void serviceChecked(@PathVariable UUID id) {
    writer.touchChecked(id);
  }

  /**
   * Planted violation when listed as service-gated: its service never checks scope.
   *
   * @param id the aggregate id
   */
  @PostMapping("/{id}/unchecked")
  public void serviceUnchecked(@PathVariable UUID id) {
    writer.touch(id);
  }

  /**
   * A read, which the write-gate rule never selects.
   *
   * @param id the aggregate id
   * @return the id echoed back
   */
  @GetMapping("/{id}")
  public UUID read(@PathVariable UUID id) {
    return id;
  }
}
