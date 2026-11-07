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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import de.greluc.krt.profit.basetool.backend.kernel.DomainProblem;
import java.io.Serial;
import java.util.Map;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.http.HttpStatus;

/**
 * Thrown when the P4K upload gate refuses a catalogue (REQ-P4K-001, REQ-P4K-002).
 *
 * <p>Answers {@code 400} with code {@code P4K_UPLOAD_REJECTED}, a localized detail per reason and
 * the extension property {@code reason}.
 */
@Getter
public final class P4kUploadRejectedException extends DomainProblem {

  @Serial private static final long serialVersionUID = 1L;

  /** Why the catalogue was refused. */
  @NotNull private final P4kRejectionReason reason;

  /**
   * Creates the exception; the message is the i18n key of the reason's detail.
   *
   * @param reason why the catalogue was refused
   */
  public P4kUploadRejectedException(@NotNull P4kRejectionReason reason) {
    super("problem.p4k_upload_rejected.detail." + reason.tagValue());
    this.reason = reason;
  }

  /**
   * The status the refusal is answered with.
   *
   * @return {@code 400}
   */
  @NotNull
  @Override
  public HttpStatus status() {
    return CatalogueProblemCode.P4K_UPLOAD_REJECTED.status();
  }

  /**
   * The stable code of the refusal.
   *
   * @return {@code P4K_UPLOAD_REJECTED}
   */
  @NotNull
  @Override
  public String code() {
    return CatalogueProblemCode.P4K_UPLOAD_REJECTED.code();
  }

  /**
   * The label the handler's log line names the refusal by.
   *
   * @return {@code "P4K upload rejected"}
   */
  @NotNull
  @Override
  public String logLabel() {
    return "P4K upload rejected";
  }

  /**
   * Adds the refusal reason to the problem body.
   *
   * @return {@code reason} with the metric tag value
   */
  @NotNull
  @Unmodifiable
  @Override
  public Map<String, Object> extraProperties() {
    return Map.of("reason", reason.tagValue());
  }
}
