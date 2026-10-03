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

package de.greluc.krt.profit.basetool.guardfixture.massassignment;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fixture controller planting one violation of each mass-assignment rule. It lives outside the
 * scanned backend package, so no application context registers it.
 */
@RestController
@RequestMapping("/api/v1/fixture-orders")
public class FixtureMassAssignmentController {

  /**
   * Returns the dual-use DTO inside a generic wrapper.
   *
   * @return an empty list
   */
  @GetMapping
  public ResponseEntity<List<FixtureOrderDto>> list() {
    return ResponseEntity.ok(List.of());
  }

  /**
   * Planted violations: binds the returned DTO as its body, without {@code @Valid}.
   *
   * @param dto the body
   * @return the body echoed back
   */
  @PostMapping
  public FixtureOrderDto create(@RequestBody FixtureOrderDto dto) {
    return dto;
  }

  /**
   * Compliant: a status transition on a {@code /status} path.
   *
   * @param id the row id
   * @param request the transition
   * @return an empty response
   */
  @PutMapping("/{id}/status")
  public ResponseEntity<Void> transition(
      @PathVariable UUID id, @RequestBody @Valid FixtureStatusRequest request) {
    Objects.requireNonNull(id);
    Objects.requireNonNull(request);
    return ResponseEntity.noContent().build();
  }

  /**
   * Planted violation: a status bound outside a transition path.
   *
   * @param request the body
   * @return an empty response
   */
  @PutMapping("/bulk")
  public ResponseEntity<Void> bulk(@RequestBody @Valid BulkStatusRequest request) {
    Objects.requireNonNull(request);
    return ResponseEntity.noContent().build();
  }

  /**
   * Planted violation: a nested line carries a server-managed org unit.
   *
   * @param request the body
   * @return an empty response
   */
  @PostMapping("/nested")
  public ResponseEntity<Void> nested(@RequestBody @Valid FixtureNestedRequest request) {
    Objects.requireNonNull(request);
    return ResponseEntity.noContent().build();
  }

  /**
   * A bulk status change bound outside a transition path.
   *
   * @param ids the rows to change
   * @param status the target status
   */
  public record BulkStatusRequest(List<UUID> ids, String status) {}
}
