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

package de.greluc.krt.profit.basetool.backend.architecture.fixture;

import de.greluc.krt.profit.basetool.backend.bank.api.BankAccountReads;
import de.greluc.krt.profit.basetool.backend.bank.api.BankActor;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Planted breaches of the bank access policy rules (ADR-0352), so every rule is proven able to
 * fail. None of these types is a Spring bean.
 */
public final class BankAccessPolicyFixtures {

  /** Not instantiable. */
  private BankAccessPolicyFixtures() {}

  /** A policy that reaches the security context through a helper it calls. */
  @RequiredArgsConstructor
  public static final class PlantedPolicy {

    /** The helper that reads the security context. */
    private final PlantedContextReader reader;

    /**
     * Decides from the security context, one call away.
     *
     * @return whether a caller is bound
     */
    public boolean decides() {
      return reader.callerBound();
    }
  }

  /** The helper the planted policy calls. */
  public static final class PlantedContextReader {

    /**
     * Reads the security context.
     *
     * @return whether an authentication is bound
     */
    public boolean callerBound() {
      return SecurityContextHolder.getContext().getAuthentication() != null;
    }
  }

  /** A bank service that decides a bank rule itself. */
  @RequiredArgsConstructor
  public static final class PlantedRuleOutsideThePolicy {

    /** The role checks the planted rule reads. */
    private final AuthHelperService authHelperService;

    /** The membership reads the planted rule uses. */
    private final RequestScopeResolver requestScopeResolver;

    /**
     * Reads the caller's role.
     *
     * @return whether the caller is an administrator
     */
    public boolean checksTheRole() {
      return authHelperService.isAdmin();
    }

    /**
     * Reads the caller's seats.
     *
     * @return whether the caller holds an OL seat
     */
    public boolean checksTheSeat() {
      return requestScopeResolver.currentUserIsOlMember();
    }
  }

  /** An exchange reader that reaches past the policy into the bank's internals. */
  @RequiredArgsConstructor
  public static final class PlantedExchangeReader {

    /** The bank's internal repository the planted reader uses. */
    private final BankAccountRepository accountRepository;

    /** The bank reads the rule allows. */
    private final BankAccountReads reads;

    /**
     * Counts the accounts behind the policy's back.
     *
     * @return the number of stored accounts
     */
    public long countsTheAccounts() {
      return accountRepository.count();
    }

    /**
     * Reads the accounts through the allowed port.
     *
     * @param actor the actor
     * @return whether a page is served
     */
    public boolean readsThroughThePort(BankActor actor) {
      return reads.viewableAccounts(actor, null, 1).isPresent();
    }
  }

  /** A bank adapter that only reads who calls and the pin, which the rules allow. */
  @RequiredArgsConstructor
  public static final class PlantedAdapter {

    /** The identity read. */
    private final AuthHelperService authHelperService;

    /** The pin read. */
    private final RequestScopeResolver requestScopeResolver;

    /**
     * Reads who calls.
     *
     * @return the caller's id
     */
    public Optional<UUID> readsTheCaller() {
      return authHelperService.currentUserId();
    }

    /**
     * Reads the pin.
     *
     * @return the pinned org unit
     */
    public Optional<UUID> readsThePin() {
      return requestScopeResolver.readActiveSquadronFromHeader();
    }
  }
}
