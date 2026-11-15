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

package de.greluc.krt.profit.basetool.frontend.hangar.web;

import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The answer a hangar AJAX write gets when the frontend refuses its request before forwarding it: a
 * {@code 422} {@code problem+json} with the stable code {@code VALIDATION}, which the hangar pages
 * show as an error toast without a navigation.
 */
final class HangarValidationProblem {

  /** The problem code the hangar pages map to their validation toast. */
  static final String CODE = "VALIDATION";

  private HangarValidationProblem() {}

  /**
   * Builds the refusal.
   *
   * @return a {@code 422} {@code problem+json} response with code {@link #CODE}
   */
  static @NotNull ResponseEntity<Object> response() {
    int status = HttpStatus.UNPROCESSABLE_CONTENT.value();
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(Map.of("status", status, "code", CODE));
  }
}
