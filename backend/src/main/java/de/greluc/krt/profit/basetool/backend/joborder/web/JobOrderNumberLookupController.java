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

package de.greluc.krt.profit.basetool.backend.joborder.web;

import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderNumberHitDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderQueryService;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The job-order number lookup behind the quick access's entity search (REQ-ORDERS-048): the orders
 * whose number starts with given digits among those the caller may open.
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Operations related to job orders")
public class JobOrderNumberLookupController {

  private static final Pattern NUMBER_PREFIX = Pattern.compile("[0-9]{1,9}");
  private static final int MAX_HITS = 20;

  private final JobOrderQueryService jobOrderQueryService;

  /**
   * Finds the orders whose number starts with the given digits among those the caller may open.
   *
   * @param number the digits the order number starts with, one to nine
   * @param limit the most hits, clamped to 1&ndash;20
   * @return the matching orders by ascending number
   * @throws BadRequestException when {@code number} is not one to nine digits
   */
  @GetMapping("/by-number")
  @Operation(
      summary = "Find job orders by number",
      description =
          "Returns the orders whose number starts with the given digits that the caller may open:"
              + " the scoped order list and the orders the caller is an assignee of.")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public @NotNull List<JobOrderNumberHitDto> findByNumber(
      @RequestParam String number, @RequestParam(required = false, defaultValue = "5") int limit) {
    if (!NUMBER_PREFIX.matcher(number).matches()) {
      throw new BadRequestException("number must be one to nine digits");
    }
    return jobOrderQueryService.findByNumberPrefix(number, Math.clamp(limit, 1, MAX_HITS));
  }
}
