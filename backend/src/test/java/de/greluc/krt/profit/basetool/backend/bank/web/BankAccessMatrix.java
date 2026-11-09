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

package de.greluc.krt.profit.basetool.backend.bank.web;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The bank access characterisation matrix: every actor against every account type and state,
 * operation, amount band and active-org-unit pin.
 *
 * <p>The matrix only names the cases; {@link BankAccessWorld} seeds the subjects, a driver such as
 * {@link BankWebAccessDriver} turns one case into an outcome, and {@code bank-access-matrix.tsv}
 * holds the outcome each case had on the web path when the matrix was recorded. An actor's cases
 * run read operations before writes, on as many fresh worlds as the per-subject write budget
 * (REQ-SEC-033) needs; every write targets a subject of its own or changes nothing another case
 * reads, so the outcomes depend neither on the order nor on where a new world starts.
 */
final class BankAccessMatrix {

  private BankAccessMatrix() {}

  /** Who calls; every actor except {@link #PENDING} holds {@code KRT Member} or {@code Admin}. */
  enum Actor {
    /** A rank-less member of the own Staffel. */
    MEMBER,
    /** The Staffelleiter of the own Staffel, its responsible holder. */
    STAFFELLEITER,
    /** The lead of the Spezialkommando under the own Bereich. */
    SK_LEAD,
    /** The Bereichsleiter of the own Bereich, which holds the own Staffel and the SK. */
    BEREICHSLEITER,
    /** The Bereichsleiter of a {@code PROFIT} Bereich, the holder of the cartel bank account. */
    PROFIT_BEREICHSLEITER,
    /** A member of the Organisationsleitung. */
    OL_MEMBER,
    /** A bank employee without any grant row. */
    EMPLOYEE,
    /** A bank employee with a grant row carrying no flag on the own Staffel and KRT accounts. */
    EMPLOYEE_VIEW,
    /** A bank employee with {@code can_deposit} on the own Staffel and KRT accounts. */
    EMPLOYEE_DEPOSIT,
    /** A bank employee with {@code can_withdraw} on the own Staffel and KRT accounts. */
    EMPLOYEE_WITHDRAW,
    /** A bank employee with {@code can_transfer} on the own Staffel and KRT accounts. */
    EMPLOYEE_TRANSFER,
    /** The Bankleitung. */
    MANAGEMENT,
    /** An administrator without memberships. */
    ADMIN,
    /** A registration still waiting for approval. */
    PENDING
  }

  /** What a case acts on. */
  sealed interface Subject permits None, Account, Holder, Request {

    /**
     * The stable name the outcome file uses.
     *
     * @return the subject's enum constant name
     */
    @NotNull
    String name();
  }

  /** The subject of an operation that names no account, holder or request. */
  enum None implements Subject {
    /** No subject. */
    NONE
  }

  /** The seeded accounts, one per type and state the rules distinguish. */
  enum Account implements Subject {
    /** The own Staffel's account, with an all-members view grant and a 1,000 all-members limit. */
    OWN_ORG_UNIT,
    /** The Spezialkommando's account. */
    SK_ORG_UNIT,
    /** A Staffel account under a foreign Bereich. */
    FOREIGN_ORG_UNIT,
    /** The own Bereich's account, with a 1,000 area-members limit. */
    AREA,
    /** The KRT account, with {@code T1} = 1,000 and {@code T2} = 10,000. */
    CARTEL,
    /** The cartel bank's own account. */
    CARTEL_BANK,
    /** A Sonderkonto without a view grant. */
    SPECIAL_PLAIN,
    /** A Sonderkonto with an all-members view grant. */
    SPECIAL_SHARED,
    /** A closed Staffel account under the own Bereich. */
    CLOSED
  }

  /** The seeded custody holders. */
  enum Holder implements Subject {
    /** The holder registered for {@link Actor#EMPLOYEE_DEPOSIT}; every seeded booking uses it. */
    EMPLOYEE_DEPOSIT_HOLDER,
    /** The holder registered for {@link Actor#MANAGEMENT}. */
    MANAGEMENT_HOLDER
  }

  /** How an operation uses a seeded request. */
  enum RequestUse {
    /** A request bank staff confirm. */
    CONFIRM,
    /** A request awaiting an approver's sign-off. */
    APPROVE,
    /** The member's own request the member cancels. */
    CANCEL,
    /** The member's own request the member corrects. */
    CORRECT,
    /** A request bank staff reject. */
    REJECT
  }

  /** The seeded pending requests; {@link Actor#MEMBER} raised every one of them. */
  @Getter
  @RequiredArgsConstructor
  enum Request implements Subject {
    /** A deposit onto the own Staffel's account. */
    CONFIRM_DEPOSIT_OWN(RequestUse.CONFIRM),
    /** A withdrawal from the own Staffel's account. */
    CONFIRM_WITHDRAWAL_OWN(RequestUse.CONFIRM),
    /** A transfer from the own Staffel's account. */
    CONFIRM_TRANSFER_OWN(RequestUse.CONFIRM),
    /** A deposit onto the KRT account. */
    CONFIRM_DEPOSIT_CARTEL(RequestUse.CONFIRM),
    /** A withdrawal from the KRT account below {@code T1}. */
    CONFIRM_WITHDRAWAL_CARTEL(RequestUse.CONFIRM),
    /** A transfer from the KRT account below {@code T1}. */
    CONFIRM_TRANSFER_CARTEL(RequestUse.CONFIRM),
    /** A withdrawal from the plain Sonderkonto. */
    CONFIRM_WITHDRAWAL_SPECIAL(RequestUse.CONFIRM),
    /** A KRT withdrawal awaiting the Bankleitung, confirmed without an attestation. */
    CONFIRM_WITHDRAWAL_CARTEL_UNAPPROVED(RequestUse.CONFIRM),
    /** An over-limit withdrawal from the own Staffel's account. */
    APPROVE_OWN(RequestUse.APPROVE),
    /** An over-limit withdrawal from the Spezialkommando's account. */
    APPROVE_SK(RequestUse.APPROVE),
    /** An over-limit withdrawal from the own Bereich's account. */
    APPROVE_AREA(RequestUse.APPROVE),
    /** A KRT withdrawal between {@code T1} and {@code T2}, routed to the Bankleitung. */
    APPROVE_CARTEL_MANAGEMENT(RequestUse.APPROVE),
    /** A KRT withdrawal above {@code T2}, routed to the Organisationsleitung. */
    APPROVE_CARTEL_OL(RequestUse.APPROVE),
    /** A withdrawal from the own Staffel's account within the limit, needing no approval. */
    APPROVE_WITHIN_LIMIT(RequestUse.APPROVE),
    /** The member's withdrawal from the own Staffel's account to cancel. */
    CANCEL_OWN(RequestUse.CANCEL),
    /** The member's withdrawal from the own Staffel's account to correct. */
    CORRECT_OWN(RequestUse.CORRECT),
    /** A withdrawal from the own Staffel's account to reject. */
    REJECT_OWN(RequestUse.REJECT);

    /** The operation family the request is seeded for. */
    private final RequestUse use;
  }

  /** The amount of a raise, a correction or a direct booking, placed against the seeded limits. */
  @Getter
  @RequiredArgsConstructor
  enum Band {
    /** Below {@code T1} and within the 1,000 member limits. */
    LOW(new BigDecimal("500")),
    /** Between {@code T1} and {@code T2}, and over the member limits. */
    MID(new BigDecimal("5000")),
    /** Above {@code T2}. */
    HIGH(new BigDecimal("50000"));

    /** The whole-aUEC amount. */
    private final BigDecimal amount;
  }

  /** The active-org-unit pin sent in {@code X-Active-Org-Unit-Id}. */
  enum Pin {
    /** No pin. */
    NONE,
    /** The own Staffel. */
    OWN_STAFFEL,
    /** The own Bereich. */
    OWN_BEREICH
  }

  /** The kind of subject an operation acts on. */
  enum SubjectKind {
    /** No subject. */
    NONE,
    /** Every seeded account. */
    ACCOUNT,
    /** Every seeded holder. */
    HOLDER,
    /** The seeded requests of the operation's {@link RequestUse}. */
    REQUEST
  }

  /** The bank operations the access rules decide, each as one web call or a two-call sequence. */
  @Getter
  @RequiredArgsConstructor
  enum Operation {
    /** The org-unit bank page's balance cards. */
    ORG_BALANCES(SubjectKind.NONE, null, 0, false, true),
    /** The pin-free list of the viewable org-unit accounts. */
    ORG_VISIBLE(SubjectKind.NONE, null, 0, false, true),
    /** The read-only org-unit drill-in of one account. */
    ORG_VIEW(SubjectKind.ACCOUNT, null, 0, false, true),
    /** The org-unit settings panel of one account. */
    ORG_SETTINGS(SubjectKind.ACCOUNT, null, 0, false, false),
    /** The bank staff's account list. */
    STAFF_LIST(SubjectKind.NONE, null, 0, false, false),
    /** The bank staff's account detail. */
    STAFF_VIEW(SubjectKind.ACCOUNT, null, 0, false, false),
    /** A holder's custody record. */
    HOLDER_VIEW(SubjectKind.HOLDER, null, 0, false, false),
    /** The requests on the accounts the caller holds or approves. */
    FOREIGN_REQUESTS(SubjectKind.NONE, null, 0, false, false),
    /** The bank staff's confirmation queue with the per-row confirm flag. */
    STAFF_QUEUE(SubjectKind.NONE, null, 0, false, false),
    /** Raising a deposit request. */
    RAISE_DEPOSIT(SubjectKind.ACCOUNT, null, 1, false, false),
    /** Raising a withdrawal request. */
    RAISE_WITHDRAWAL(SubjectKind.ACCOUNT, null, 1, true, true),
    /** Raising a transfer request. */
    RAISE_TRANSFER(SubjectKind.ACCOUNT, null, 1, true, false),
    /** Correcting the member's own pending request to the band's amount. */
    CORRECT_OWN(SubjectKind.REQUEST, RequestUse.CORRECT, 1, true, false),
    /** Cancelling the member's own pending request. */
    CANCEL_OWN(SubjectKind.REQUEST, RequestUse.CANCEL, 1, false, false),
    /** Confirming a pending request. */
    CONFIRM(SubjectKind.REQUEST, RequestUse.CONFIRM, 1, false, false),
    /** Rejecting a pending request. */
    REJECT(SubjectKind.REQUEST, RequestUse.REJECT, 1, false, false),
    /** Granting an approver's sign-off. */
    APPROVE(SubjectKind.REQUEST, RequestUse.APPROVE, 1, false, false),
    /** Raising a deposit request onto the own Staffel's account and confirming it oneself. */
    FOUR_EYES_CONFIRM(SubjectKind.NONE, null, 2, false, false),
    /** Raising a KRT withdrawal between {@code T1} and {@code T2} and approving it oneself. */
    FOUR_EYES_APPROVE(SubjectKind.NONE, null, 2, false, false),
    /** Granting an outsider a view grant from the org-unit settings. */
    SET_VISIBILITY(SubjectKind.ACCOUNT, null, 1, false, false),
    /** Setting the balance target from the org-unit settings. */
    SET_TARGET(SubjectKind.ACCOUNT, null, 1, false, false),
    /** Setting an outsider's approval limit from the org-unit settings. */
    SET_LIMIT(SubjectKind.ACCOUNT, null, 1, false, false),
    /** Setting the balance target from the bank staff's account page. */
    STAFF_SET_TARGET(SubjectKind.ACCOUNT, null, 1, false, false),
    /** Re-saving the seeded KRT approval tiers from the bank staff's account page. */
    STAFF_SET_TIERS(SubjectKind.ACCOUNT, null, 1, false, false),
    /** A bank staff direct deposit. */
    DIRECT_DEPOSIT(SubjectKind.ACCOUNT, null, 1, false, false),
    /** A bank staff direct withdrawal. */
    DIRECT_WITHDRAWAL(SubjectKind.ACCOUNT, null, 1, true, false),
    /** A bank staff direct transfer to an account the granted employees see. */
    DIRECT_TRANSFER(SubjectKind.ACCOUNT, null, 1, true, false),
    /** A bank staff direct transfer to a Sonderkonto no granted employee sees. */
    DIRECT_TRANSFER_TO_HIDDEN(SubjectKind.ACCOUNT, null, 1, false, false);

    /** What the operation acts on. */
    private final SubjectKind subjectKind;

    /** For a request operation, which seeded requests it acts on; {@code null} otherwise. */
    private final RequestUse requestUse;

    /** How many writing calls the operation sends; a read sends none. */
    private final int writeCalls;

    /** Whether the operation runs at every {@link Band}; otherwise only at {@link Band#LOW}. */
    private final boolean banded;

    /** Whether the operation runs under every {@link Pin}; otherwise only without one. */
    private final boolean pinned;

    /**
     * Whether the operation can change the world.
     *
     * @return {@code true} iff it sends a writing call
     */
    boolean isMutating() {
      return writeCalls > 0;
    }

    /**
     * The subjects the operation runs against.
     *
     * @return the subjects in declaration order
     */
    @NotNull
    @Unmodifiable
    List<Subject> subjects() {
      return switch (subjectKind) {
        case NONE -> List.of(None.NONE);
        case ACCOUNT -> List.of(Account.values());
        case HOLDER -> List.of(Holder.values());
        case REQUEST ->
            Arrays.stream(Request.values())
                .filter(request -> request.getUse() == requestUse)
                .map(Subject.class::cast)
                .toList();
      };
    }

    /**
     * The bands the operation runs at.
     *
     * @return every band, or {@link Band#LOW} alone
     */
    @NotNull
    @Unmodifiable
    List<Band> bands() {
      return banded ? List.of(Band.values()) : List.of(Band.LOW);
    }

    /**
     * The pins the operation runs under.
     *
     * @return every pin, or {@link Pin#NONE} alone
     */
    @NotNull
    @Unmodifiable
    List<Pin> pins() {
      return pinned ? List.of(Pin.values()) : List.of(Pin.NONE);
    }
  }

  /**
   * One cell of the matrix.
   *
   * @param actor who calls
   * @param operation what is done
   * @param subject what it is done to
   * @param band the amount, meaningful only for a banded operation
   * @param pin the active-org-unit pin
   */
  record Case(
      @NotNull Actor actor,
      @NotNull Operation operation,
      @NotNull Subject subject,
      @NotNull Band band,
      @NotNull Pin pin) {

    /**
     * The tab-separated key the outcome file stores the case under.
     *
     * @return actor, operation, subject, band and pin joined by tabs
     */
    @NotNull
    String key() {
      return String.join(
          "\t", actor.name(), operation.name(), subject.name(), band.name(), pin.name());
    }
  }

  /**
   * Every case of one actor, read operations first.
   *
   * @param actor the caller
   * @return the actor's cases
   */
  @NotNull
  @Unmodifiable
  static List<Case> casesOf(@NotNull Actor actor) {
    List<Case> cases = new ArrayList<>();
    for (Operation operation : Operation.values()) {
      for (Subject subject : operation.subjects()) {
        for (Band band : operation.bands()) {
          for (Pin pin : operation.pins()) {
            cases.add(new Case(actor, operation, subject, band, pin));
          }
        }
      }
    }
    cases.sort(Comparator.comparing(c -> c.operation().isMutating()));
    return List.copyOf(cases);
  }

  /**
   * Every case of the matrix.
   *
   * @return all cases, grouped by actor
   */
  @NotNull
  @Unmodifiable
  static List<Case> allCases() {
    return Arrays.stream(Actor.values()).flatMap(actor -> casesOf(actor).stream()).toList();
  }
}
