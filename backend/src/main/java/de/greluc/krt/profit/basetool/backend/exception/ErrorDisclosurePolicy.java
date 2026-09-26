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

package de.greluc.krt.profit.basetool.backend.exception;

/**
 * How {@code GlobalExceptionHandler}'s generic dispatch treats an {@link AppException}'s message
 * and log level.
 *
 * <p>{@link #SUPPRESSED} keeps messages that may carry upstream or library internals away from the
 * client (CWE-209); every other exception uses {@link #STANDARD}.
 */
public enum ErrorDisclosurePolicy {

  /**
   * The message reaches the client via {@code resolveDetail}, and the problem is logged at WARN as
   * an expected client-side outcome.
   */
  STANDARD,

  /**
   * Info-leak protection: the client receives a generic, purely-localized detail ({@code
   * getMessage()} is never consulted), and the full exception — message and stack trace — is logged
   * at ERROR with the correlation id instead, so triage retains everything the WARN path would have
   * discarded.
   */
  SUPPRESSED
}
