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

package de.greluc.krt.profit.basetool.backend.repository;

import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link BankAccount} rows (epic #556). Visibility filtering happens
 * here only as data-level helpers ({@link #findGrantedToFiltered}); the authorization decision
 * itself lives in {@code BankSecurityService} (REQ-BANK-010).
 */
@Repository
public interface BankAccountRepository extends JpaRepository<BankAccount, UUID> {

  /**
   * Loads one account under a pessimistic write lock for the duration of the surrounding
   * transaction. Every booking flow locks the affected account(s) first so concurrent bookings on
   * the same account serialize and the no-overdraft check (REQ-BANK-006) cannot race.
   *
   * @param id the account id
   * @return the locked account, or empty when it does not exist
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT a FROM BankAccount a WHERE a.id = :id")
  Optional<BankAccount> findByIdForUpdate(@Param("id") UUID id);

  /**
   * Loads ALL accounts under pessimistic write locks in deterministic id order — the wipe reset
   * (REQ-BANK-013) serializes against every concurrent booking without deadlock risk because the
   * booking flows acquire their per-account locks in the same id order.
   *
   * @return all accounts, locked, ordered by id
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT a FROM BankAccount a ORDER BY a.id")
  List<BankAccount> findAllForUpdateOrderById();

  /**
   * Existence probe backing the singleton pre-check for {@code CARTEL} / {@code CARTEL_BANK}
   * account creation (REQ-BANK-001) — gives a clean 409 before the V150 partial unique index would
   * reject the insert.
   *
   * @param type the account type to probe
   * @return {@code true} when at least one account of the type exists
   */
  boolean existsByType(BankAccountType type);

  /**
   * Loads the singleton account of the given type with its owning org unit pre-fetched — used only
   * for the {@code CARTEL} / {@code CARTEL_BANK} singletons (REQ-BANK-001) when the
   * responsible-holder change audit resolves the Profit-Bereichsleiter ripple onto them
   * (REQ-BANK-034/-047, ADR-0070).
   *
   * @param type the singleton account type ({@code CARTEL} or {@code CARTEL_BANK})
   * @return the account, or empty when none of that type exists yet
   */
  @EntityGraph(attributePaths = {"orgUnit"})
  Optional<BankAccount> findFirstByType(BankAccountType type);

  /**
   * Existence probe backing the one-account-per-org-unit pre-check (REQ-BANK-001).
   *
   * @param orgUnitId the org unit to probe
   * @return {@code true} when the org unit already owns an account
   */
  boolean existsByOrgUnitId(UUID orgUnitId);

  /**
   * Loads the single account owned by the given org unit, with the owning org unit pre-fetched.
   * Backs the org-unit officer/lead balance view (REQ-BANK-021) and the confirm-before-post request
   * flow (REQ-BANK-022): both resolve a caller's own-level org unit to its account. Since epic #692
   * Phase 6 (REQ-ORG-019) the owning org unit can be any account-owning kind — a Staffel/SK for an
   * {@code ORG_UNIT} account, the Bereich for an {@code AREA} account, or the Organisationsleitung
   * for the {@code CARTEL} account — all carried by the same {@code org_unit_id} FK. The V150
   * partial unique index {@code uq_bank_account_org_unit} guarantees at most one row per org unit.
   *
   * @param orgUnitId the owning org unit
   * @return the org unit's account (ORG_UNIT / AREA / CARTEL), or empty when it owns none
   */
  @EntityGraph(attributePaths = {"orgUnit"})
  @Query("SELECT a FROM BankAccount a WHERE a.orgUnit.id = :orgUnitId")
  Optional<BankAccount> findByOrgUnitId(@Param("orgUnitId") UUID orgUnitId);

  /**
   * Draws the next value from the {@code bank_account_no_seq} sequence (V150) backing the
   * server-generated, never-reused {@code KB-<n>} account numbers.
   *
   * @return the next sequence value
   */
  @Query(value = "SELECT nextval('bank_account_no_seq')", nativeQuery = true)
  long nextAccountNoValue();

  /**
   * Pages over all accounts with the owning org unit pre-fetched — the management/admin account
   * list (REQ-BANK-010 "sees all") — narrowed by a case-insensitive name/account-number substring
   * and by status/type sets (REQ-BANK-053: the server-side account search behind the remote account
   * pickers and the paged management table). The {@code query} is a bound-parameter substring; an
   * <strong>empty string</strong> means "no filter" (it becomes the match-all {@code LIKE '%%'},
   * never {@code null}) — the "empty means all" convention shared with {@code
   * UserRepository.searchScoped}. The status/type filters are IN-lists by design: callers pass the
   * full enum set for "no filter", so the query never binds a null enum.
   *
   * @param query the name/account-no substring, or the empty string for no text filter
   * @param statuses the statuses to include (pass all values for "no filter")
   * @param types the types to include (pass all values for "no filter")
   * @param pageable page, size and whitelisted sort
   * @return one page of matching accounts
   */
  @EntityGraph(attributePaths = {"orgUnit"})
  @Query(
      """
      SELECT a FROM BankAccount a WHERE
      (LOWER(a.name) LIKE LOWER(CONCAT('%', :query, '%'))
        OR LOWER(a.accountNo) LIKE LOWER(CONCAT('%', :query, '%')))
      AND a.status IN :statuses AND a.type IN :types
      """)
  Page<BankAccount> findAllFiltered(
      @Param("query") String query,
      @Param("statuses") Set<BankAccountStatus> statuses,
      @Param("types") Set<BankAccountType> types,
      Pageable pageable);

  /**
   * Pages over exactly the accounts the given user holds a grant row on — the employee account list
   * (REQ-BANK-009: row existence = view access) — narrowed exactly like {@link #findAllFiltered}
   * (REQ-BANK-053), so an employee's remote account picker and paged table scope to their grants.
   *
   * @param userId the employee's user id
   * @param query the name/account-no substring, or the empty string for no text filter
   * @param statuses the statuses to include (pass all values for "no filter")
   * @param types the types to include (pass all values for "no filter")
   * @param pageable page, size and whitelisted sort
   * @return one page of matching granted accounts
   */
  @EntityGraph(attributePaths = {"orgUnit"})
  @Query(
      """
      SELECT a FROM BankAccount a WHERE a.id IN
      (SELECT g.id.accountId FROM BankAccountGrant g WHERE g.id.userId = :userId)
      AND (LOWER(a.name) LIKE LOWER(CONCAT('%', :query, '%'))
        OR LOWER(a.accountNo) LIKE LOWER(CONCAT('%', :query, '%')))
      AND a.status IN :statuses AND a.type IN :types
      """)
  Page<BankAccount> findGrantedToFiltered(
      @Param("userId") UUID userId,
      @Param("query") String query,
      @Param("statuses") Set<BankAccountStatus> statuses,
      @Param("types") Set<BankAccountType> types,
      Pageable pageable);

  /**
   * Unfiltered list variant of the granted-accounts query for the dashboard, ordered by account
   * number (REQ-BANK-016). Unbounded by design — the org holds a handful of accounts and the
   * dashboard renders all of them as cards.
   *
   * @param userId the employee's user id
   * @return every granted account, ordered by account number
   */
  @EntityGraph(attributePaths = {"orgUnit"})
  @Query(
      """
      SELECT a FROM BankAccount a WHERE a.id IN
      (SELECT g.id.accountId FROM BankAccountGrant g WHERE g.id.userId = :userId)
      ORDER BY a.accountNo
      """)
  List<BankAccount> findAllGrantedTo(@Param("userId") UUID userId);

  /**
   * All accounts ordered by account number — the management/admin dashboard card grid and the
   * wipe-reset preview counts. Unbounded by design (see {@link #findAllGrantedTo(UUID)}).
   *
   * @return every account, ordered by account number
   */
  @EntityGraph(attributePaths = {"orgUnit"})
  List<BankAccount> findAllByOrderByAccountNoAsc();

  /**
   * Loads every account of one type/status with the owning org unit pre-fetched — backs the split
   * deposit's squadron-account enumeration (REQ-BANK-043): it reads {@code ORG_UNIT}/{@code ACTIVE}
   * accounts, then filters them to those whose org unit is a {@code SQUADRON} in Java (the {@code
   * org_unit.kind} discriminator is read via {@code OrgUnit#getKind()}, not a JPQL attribute, see
   * {@code OrgUnit}). Unbounded by design — the org holds a handful of accounts. The returned rows
   * are <strong>not</strong> locked; the split flow re-locks each target via {@link
   * #findByIdForUpdate(UUID)} in ascending id order before posting.
   *
   * @param type the account type to load (e.g. {@code ORG_UNIT})
   * @param status the account status to load (e.g. {@code ACTIVE})
   * @return the matching accounts with their org unit fetched, ordered by id
   */
  @EntityGraph(attributePaths = {"orgUnit"})
  List<BankAccount> findByTypeAndStatusOrderById(BankAccountType type, BankAccountStatus status);
}
