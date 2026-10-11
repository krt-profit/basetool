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

package de.greluc.krt.profit.basetool.frontend.bank.web;

import de.greluc.krt.profit.basetool.frontend.bank.model.BankBookingRequestDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Derives the approval path of a booking request for the org-unit bank's "Laufende Anträge" list
 * (REQ-BANK-041, REQ-BANK-047): submitted, the owner approval by the required approver class when
 * the request needs one, and the bank's confirmation.
 */
public final class BankRequestSteps {

  /** Message key prefix of every step label. */
  private static final String KEY_PREFIX = "bank.orgUnit.step.";

  /** Request status of a request still waiting for its approvals or confirmation. */
  private static final String PENDING = "PENDING";

  /** Request status of a request the bank has booked. */
  private static final String CONFIRMED = "CONFIRMED";

  /** Utility class — not instantiable. */
  private BankRequestSteps() {}

  /** Where a step stands on a request's path. */
  public enum State {
    /** The step is complete. */
    DONE,
    /** The request waits on this step. */
    CURRENT,
    /** The step follows the current one. */
    OPEN
  }

  /**
   * One step of a request's approval path.
   *
   * @param labelKey the message key of the step label
   * @param state where the step stands
   */
  public record Step(@NotNull String labelKey, @NotNull State state) {

    /**
     * Returns the CSS modifier the template appends for this step's state.
     *
     * @return {@code done}, {@code current} or {@code open}
     */
    @NotNull
    public String modifier() {
      return switch (state) {
        case DONE -> "done";
        case CURRENT -> "current";
        case OPEN -> "open";
      };
    }
  }

  /**
   * One running request with its derived approval path.
   *
   * @param request the request
   * @param steps the approval path, submitted first
   */
  public record OpenRequest(@NotNull BankBookingRequestDto request, @NotNull List<Step> steps) {}

  /**
   * Builds the approval path of one request: "Eingereicht", the required approver when the amount
   * exceeds the requester's limit, then "Bank". A granted owner approval completes the approver
   * step; a confirmed request completes every step.
   *
   * @param request the request
   * @return the steps in path order
   */
  @NotNull
  @Unmodifiable
  public static List<Step> of(@NotNull BankBookingRequestDto request) {
    List<String> keys = new ArrayList<>();
    keys.add(KEY_PREFIX + "submitted");
    boolean needsApproval = request.requiresOwnerApproval();
    if (needsApproval) {
      keys.add(approverKey(request.requiredApprover()));
    }
    keys.add(KEY_PREFIX + "bank");
    int done;
    if (CONFIRMED.equals(request.status())) {
      done = keys.size();
    } else if (needsApproval && request.ownerApprovalGranted()) {
      done = 2;
    } else {
      done = 1;
    }
    boolean waiting = PENDING.equals(request.status());
    List<Step> steps = new ArrayList<>(keys.size());
    for (int i = 0; i < keys.size(); i++) {
      State state;
      if (i < done) {
        state = State.DONE;
      } else if (i == done && waiting) {
        state = State.CURRENT;
      } else {
        state = State.OPEN;
      }
      steps.add(new Step(keys.get(i), state));
    }
    return List.copyOf(steps);
  }

  /**
   * Collects the caller's running requests — own and those on accounts the caller approves for — as
   * one list without duplicates, newest first, each with its approval path.
   *
   * @param own the caller's own requests
   * @param foreign the requests on accounts the caller is responsible for
   * @return the pending requests with their steps
   */
  @NotNull
  @Unmodifiable
  public static List<OpenRequest> openRequests(
      @Nullable List<BankBookingRequestDto> own, @Nullable List<BankBookingRequestDto> foreign) {
    Map<UUID, BankBookingRequestDto> byId = new LinkedHashMap<>();
    for (List<BankBookingRequestDto> list : List.of(nullSafe(own), nullSafe(foreign))) {
      for (BankBookingRequestDto request : list) {
        if (request != null && request.id() != null && PENDING.equals(request.status())) {
          byId.putIfAbsent(request.id(), request);
        }
      }
    }
    return byId.values().stream()
        .sorted(
            Comparator.comparing(
                BankBookingRequestDto::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
        .map(request -> new OpenRequest(request, of(request)))
        .toList();
  }

  /**
   * Maps the request's required approver class onto its step label key; an unknown or missing class
   * falls back to the generic approval label.
   *
   * @param requiredApprover the {@code BankRequestApprover} name, or {@code null}
   * @return the step label key
   */
  @NotNull
  private static String approverKey(@Nullable String requiredApprover) {
    if (requiredApprover == null) {
      return KEY_PREFIX + "approval";
    }
    return switch (requiredApprover) {
      case "RESPONSIBLE_HOLDER", "BANK_MANAGEMENT", "ORGANISATIONSLEITUNG" ->
          KEY_PREFIX + requiredApprover;
      default -> KEY_PREFIX + "approval";
    };
  }

  /**
   * Returns the list, or an empty list for {@code null}.
   *
   * @param list the list, or {@code null}
   * @return a non-null list
   */
  @NotNull
  private static List<BankBookingRequestDto> nullSafe(@Nullable List<BankBookingRequestDto> list) {
    return list == null ? List.of() : list;
  }
}
