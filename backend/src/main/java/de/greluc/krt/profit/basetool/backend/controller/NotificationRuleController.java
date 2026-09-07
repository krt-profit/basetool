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

package de.greluc.krt.profit.basetool.backend.controller;

import de.greluc.krt.profit.basetool.backend.model.dto.NotificationRuleDto;
import de.greluc.krt.profit.basetool.backend.model.dto.NotificationRuleWriteRequest;
import de.greluc.krt.profit.basetool.backend.service.NotificationRuleService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin REST surface for the data-driven notification rules (REQ-NOTIF-007). Admin-only: rules
 * decide who is notified for what, so editing them is an administrative capability.
 */
@RestController
@RequestMapping("/api/v1/notification-rules")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_ADMIN)
@Tag(name = "Notification Rules", description = "Admin-managed notification recipient rules.")
@SecurityRequirement(name = "bearer-jwt")
public class NotificationRuleController {

  private final NotificationRuleService service;

  /**
   * Lists all notification rules.
   *
   * @return the rules
   */
  @GetMapping
  @Operation(summary = "List all notification rules.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "All rules."),
    @ApiResponse(responseCode = "403", description = "Admin role required.")
  })
  public List<NotificationRuleDto> list() {
    return service.list();
  }

  /**
   * Fetches one notification rule.
   *
   * @param id rule id
   * @return the rule
   */
  @GetMapping("/{id}")
  @Operation(summary = "Fetch a single notification rule.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Rule found."),
    @ApiResponse(responseCode = "404", description = "Rule not found.")
  })
  public NotificationRuleDto get(@PathVariable UUID id) {
    return service.get(id);
  }

  /**
   * Creates a notification rule.
   *
   * @param request create payload
   * @return the persisted rule
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Create a notification rule.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Rule created."),
    @ApiResponse(responseCode = "400", description = "Validation failed.")
  })
  public NotificationRuleDto create(@Valid @RequestBody NotificationRuleWriteRequest request) {
    return service.create(request);
  }

  /**
   * Updates a notification rule, replacing its selectors.
   *
   * @param id rule id
   * @param request update payload (carries the expected version)
   * @return the persisted rule
   */
  @PutMapping("/{id}")
  @Operation(summary = "Update a notification rule.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Rule updated."),
    @ApiResponse(responseCode = "400", description = "Validation failed."),
    @ApiResponse(responseCode = "404", description = "Rule not found."),
    @ApiResponse(responseCode = "409", description = "Optimistic lock conflict.")
  })
  public NotificationRuleDto update(
      @PathVariable UUID id, @Valid @RequestBody NotificationRuleWriteRequest request) {
    return service.update(id, request);
  }

  /**
   * Deletes a notification rule and its selectors.
   *
   * @param id rule id
   */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Delete a notification rule.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Rule deleted."),
    @ApiResponse(responseCode = "404", description = "Rule not found.")
  })
  public void delete(@PathVariable UUID id) {
    service.delete(id);
  }
}
