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
 * Spring Data repository for {@link BankAccount} rows. Offers data-level visibility helpers such as
 * {@link #findGrantedToFiltered}; the authorization decision lives in {@code BankSecurityService}
 * (REQ-BANK-010).
 */
@Repository
public interface BankAccountRepository extends JpaRepository<BankAccount, UUID> {

  /**
   * Loads one account under a pessimistic write lock for the surrounding transaction, so concurrent
   * bookings serialize and the no-overdraft check cannot race (REQ-BANK-006).
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
   * Whether an account of the given type exists; backs the singleton check for {@code CARTEL} /
   * {@code CARTEL_BANK} account creation (REQ-BANK-001).
   *
   * @param type the account type to probe
   * @return {@code true} when at least one account of the type exists
   */
  boolean existsByType(BankAccountType type);

  /**
   * Loads the singleton {@code CARTEL} or {@code CARTEL_BANK} account with its owning org unit
   * fetched (REQ-BANK-001).
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
   * Loads the single account owned by the given org unit, with the org unit fetched (REQ-BANK-021,
   * REQ-BANK-022). A unique index guarantees at most one account per org unit.
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
   * Pages over all accounts with the owning org unit fetched, filtered by name/account-number
   * substring and by status and type sets (REQ-BANK-053).
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
   * Pages over the accounts the given user holds a grant on (REQ-BANK-009), filtered like {@link
   * #findAllFiltered} (REQ-BANK-053).
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
   * Lists every account granted to the user, ordered by account number, for the dashboard
   * (REQ-BANK-016). Unbounded.
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
   * Loads every account of one type and status with its org unit fetched, for the split deposit's
   * squadron-account enumeration (REQ-BANK-043). The rows are not locked.
   *
   * @param type the account type to load (e.g. {@code ORG_UNIT})
   * @param status the account status to load (e.g. {@code ACTIVE})
   * @return the matching accounts with their org unit fetched, ordered by id
   */
  @EntityGraph(attributePaths = {"orgUnit"})
  List<BankAccount> findByTypeAndStatusOrderById(BankAccountType type, BankAccountStatus status);
}
