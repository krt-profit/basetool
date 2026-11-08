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

import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccount;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccountApprovalLimit;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccountApprovalLimitRepository;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccountGrant;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccountGrantId;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccountGrantRepository;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccountType;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccountViewGrant;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccountViewGrantRepository;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccountViewGranteeKind;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankBookingRequest;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankBookingRequestRepository;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankDepositRequest;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankHolder;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankHolderRepository;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankLedgerService;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankRequestApprover;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Account;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Actor;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Band;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Holder;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Pin;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Request;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Bereich;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Department;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Organisationsleitung;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Seeds one {@link BankAccessWorld} into the caller's transaction through the repositories and the
 * ledger, so the matrix runs against data shaped like production's.
 *
 * <p>The org tree is an Organisationsleitung over the own Bereich (own Staffel, Spezialkommando,
 * the closed account's Staffel), a foreign Bereich (foreign Staffel) and a {@code PROFIT} Bereich.
 * Every active account holds 1,000,000 aUEC. The KRT and cartel bank accounts are singletons, so an
 * existing one is reused and reset to the seeded state.
 */
final class BankAccessSeeder {

  /** The balance every active account starts with. */
  private static final BigDecimal OPENING_BALANCE = new BigDecimal("1000000");

  /** The all-members and area-members limit on the own Staffel's and Bereich's accounts. */
  static final BigDecimal MEMBER_LIMIT = new BigDecimal("1000");

  /** The KRT account's employee ceiling {@code T1}. */
  static final BigDecimal CARTEL_T1 = new BigDecimal("1000");

  /** The KRT account's Bankleitung ceiling {@code T2}. */
  static final BigDecimal CARTEL_T2 = new BigDecimal("10000");

  /** The justification every seeded debit carries. */
  static final String JUSTIFICATION = "Matrix";

  private final EntityManager entityManager;
  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final OrgUnitMembershipRepository membershipRepository;
  private final BankAccountRepository accountRepository;
  private final BankAccountGrantRepository grantRepository;
  private final BankAccountViewGrantRepository viewGrantRepository;
  private final BankAccountApprovalLimitRepository limitRepository;
  private final BankHolderRepository holderRepository;
  private final BankBookingRequestRepository requestRepository;
  private final BankLedgerService ledgerService;

  /** The prefix of every seeded name, unique per seeder so worlds never collide. */
  private final String tag = "m3154-" + UUID.randomUUID().toString().substring(0, 8);

  /**
   * Takes the repositories and the ledger from the application context.
   *
   * @param context the running application context
   */
  BankAccessSeeder(@NotNull ApplicationContext context) {
    this.entityManager = context.getBean(EntityManager.class);
    this.userRepository = context.getBean(UserRepository.class);
    this.roleRepository = context.getBean(RoleRepository.class);
    this.orgUnitRepository = context.getBean(OrgUnitRepository.class);
    this.membershipRepository = context.getBean(OrgUnitMembershipRepository.class);
    this.accountRepository = context.getBean(BankAccountRepository.class);
    this.grantRepository = context.getBean(BankAccountGrantRepository.class);
    this.viewGrantRepository = context.getBean(BankAccountViewGrantRepository.class);
    this.limitRepository = context.getBean(BankAccountApprovalLimitRepository.class);
    this.holderRepository = context.getBean(BankHolderRepository.class);
    this.requestRepository = context.getBean(BankBookingRequestRepository.class);
    this.ledgerService = context.getBean(BankLedgerService.class);
  }

  /**
   * Seeds a fresh world inside the current transaction.
   *
   * @return the ids of the seeded world
   */
  @NotNull
  BankAccessWorld seed() {
    Map<Actor, User> users = seedUsers();
    User outsider = user("outsider", ApprovalStatus.ACTIVE, Set.of(role("KRT Member")));

    Organisationsleitung ol = orgUnit(new Organisationsleitung(), "OL", null, null);
    Bereich ownBereich = orgUnit(new Bereich(), "Bereich", ol, Department.SUB_RADAR);
    Bereich foreignBereich = orgUnit(new Bereich(), "Fremdbereich", ol, Department.FORSCHUNG);
    Bereich profitBereich = orgUnit(new Bereich(), "Profit", ol, Department.PROFIT);
    Squadron ownStaffel = orgUnit(new Squadron(), "Staffel", ownBereich, null);
    SpecialCommand specialCommand = orgUnit(new SpecialCommand(), "SK", ownBereich, null);
    Squadron foreignStaffel = orgUnit(new Squadron(), "Fremdstaffel", foreignBereich, null);
    Squadron closedStaffel = orgUnit(new Squadron(), "Altstaffel", ownBereich, null);

    seat(users.get(Actor.MEMBER), ownStaffel, MembershipRole.MEMBER);
    seat(users.get(Actor.STAFFELLEITER), ownStaffel, MembershipRole.STAFFELLEITER);
    seat(users.get(Actor.SK_LEAD), specialCommand, MembershipRole.SK_LEAD);
    seat(users.get(Actor.BEREICHSLEITER), ownBereich, MembershipRole.BEREICHSLEITER);
    seat(users.get(Actor.PROFIT_BEREICHSLEITER), profitBereich, MembershipRole.BEREICHSLEITER);
    seat(users.get(Actor.OL_MEMBER), ol, MembershipRole.OL_MEMBER);

    Map<Account, BankAccount> accounts = new EnumMap<>(Account.class);
    accounts.put(Account.OWN_ORG_UNIT, account(BankAccountType.ORG_UNIT, "Staffel", ownStaffel));
    accounts.put(Account.SK_ORG_UNIT, account(BankAccountType.ORG_UNIT, "SK", specialCommand));
    accounts.put(
        Account.FOREIGN_ORG_UNIT,
        account(BankAccountType.ORG_UNIT, "Fremdstaffel", foreignStaffel));
    accounts.put(Account.AREA, account(BankAccountType.AREA, "Bereich", ownBereich));
    accounts.put(Account.CARTEL, singleton(BankAccountType.CARTEL, ol));
    accounts.put(Account.CARTEL_BANK, singleton(BankAccountType.CARTEL_BANK, null));
    accounts.put(Account.SPECIAL_PLAIN, account(BankAccountType.SPECIAL, "Sonderkonto", null));
    accounts.put(Account.SPECIAL_SHARED, account(BankAccountType.SPECIAL, "Geteilt", null));
    accounts.put(Account.CLOSED, account(BankAccountType.ORG_UNIT, "Altstaffel", closedStaffel));

    viewGrant(accounts.get(Account.OWN_ORG_UNIT), BankAccountViewGranteeKind.ALL_MEMBERS);
    viewGrant(accounts.get(Account.SPECIAL_SHARED), BankAccountViewGranteeKind.ALL_MEMBERS);
    limit(accounts.get(Account.OWN_ORG_UNIT), BankAccountViewGranteeKind.ALL_MEMBERS);
    limit(accounts.get(Account.AREA), BankAccountViewGranteeKind.AREA_MEMBERS);

    grant(users.get(Actor.EMPLOYEE_VIEW), accounts, false, false, false);
    grant(users.get(Actor.EMPLOYEE_DEPOSIT), accounts, true, false, false);
    grant(users.get(Actor.EMPLOYEE_WITHDRAW), accounts, false, true, false);
    grant(users.get(Actor.EMPLOYEE_TRANSFER), accounts, false, false, true);

    Map<Holder, BankHolder> holders = new EnumMap<>(Holder.class);
    holders.put(Holder.EMPLOYEE_DEPOSIT_HOLDER, holder(users.get(Actor.EMPLOYEE_DEPOSIT), "vault"));
    holders.put(Holder.MANAGEMENT_HOLDER, holder(users.get(Actor.MANAGEMENT), "management"));

    accounts.get(Account.CLOSED).setStatus(BankAccountStatus.CLOSED);
    fund(accounts, holders.get(Holder.EMPLOYEE_DEPOSIT_HOLDER), users.get(Actor.ADMIN));

    Map<Request, BankBookingRequest> requests = seedRequests(accounts, users.get(Actor.MEMBER));
    entityManager.flush();

    return new BankAccessWorld(
        ids(users, User::getId),
        ids(users, User::getUsername),
        ids(accounts, BankAccount::getId),
        ids(holders, BankHolder::getId),
        ids(requests, BankBookingRequest::getId),
        Map.of(Pin.OWN_STAFFEL, ownStaffel.getId(), Pin.OWN_BEREICH, ownBereich.getId()),
        outsider.getId());
  }

  /**
   * Seeds one user per actor with the roles the actor holds.
   *
   * @return the users by actor
   */
  @NotNull
  private Map<Actor, User> seedUsers() {
    Role member = role("KRT Member");
    Role employee = role("Bank Employee");
    Map<Actor, User> users = new EnumMap<>(Actor.class);
    for (Actor actor : Actor.values()) {
      Set<Role> roles =
          switch (actor) {
            case MEMBER, STAFFELLEITER, SK_LEAD, BEREICHSLEITER, PROFIT_BEREICHSLEITER, OL_MEMBER ->
                Set.of(member);
            case EMPLOYEE, EMPLOYEE_VIEW, EMPLOYEE_DEPOSIT, EMPLOYEE_WITHDRAW, EMPLOYEE_TRANSFER ->
                Set.of(member, employee);
            case MANAGEMENT -> Set.of(member, role("Bank Management"));
            case ADMIN -> Set.of(role("Admin"));
            case PENDING -> Set.of();
          };
      ApprovalStatus status =
          actor == Actor.PENDING ? ApprovalStatus.PENDING : ApprovalStatus.ACTIVE;
      users.put(actor, user(actor.name().toLowerCase(Locale.ROOT), status, roles));
    }
    return users;
  }

  /**
   * Loads a stored role by name.
   *
   * @param name the role's display name
   * @return the role
   */
  @NotNull
  private Role role(@NotNull String name) {
    return roleRepository.findByNameIgnoreCase(name).orElseThrow();
  }

  /**
   * Saves a user with a callsign derived from the given suffix.
   *
   * @param suffix the callsign suffix
   * @param status the registration status
   * @param roles the stored roles
   * @return the saved user
   */
  @NotNull
  private User user(
      @NotNull String suffix, @NotNull ApprovalStatus status, @NotNull Set<Role> roles) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(tag + "-" + suffix.replace('_', '-'));
    user.setApprovalStatus(status);
    user.setInKeycloak(true);
    user.setEnabledInKeycloak(true);
    user.setRoles(new HashSet<>(roles));
    return userRepository.save(user);
  }

  /**
   * Saves an org unit below the given parent.
   *
   * @param unit the unsaved unit of the wanted kind
   * @param name the name suffix, also the shorthand's base
   * @param parent the parent unit, or {@code null} for the Organisationsleitung
   * @param department the Bereich's department, or {@code null}
   * @param <T> the unit's kind
   * @return the saved unit
   */
  @NotNull
  private <T extends OrgUnit> T orgUnit(
      @NotNull T unit,
      @NotNull String name,
      @Nullable OrgUnit parent,
      @Nullable Department department) {
    unit.setName(tag + " " + name);
    unit.setShorthand(tag + "-" + name.toUpperCase(Locale.ROOT));
    unit.setParent(parent);
    unit.setDepartment(department);
    return orgUnitRepository.save(unit);
  }

  /**
   * Seats a user on an org unit.
   *
   * @param user the member
   * @param unit the org unit
   * @param role the seat's rank
   */
  private void seat(@NotNull User user, @NotNull OrgUnit unit, @NotNull MembershipRole role) {
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(user.getId(), unit.getId()));
    membership.setUser(user);
    membership.setRole(role);
    membership.setJoinedAt(Instant.now());
    membershipRepository.save(membership);
  }

  /**
   * Saves an active account.
   *
   * @param type the account type
   * @param name the name suffix
   * @param owner the owning org unit, or {@code null}
   * @return the saved account
   */
  @NotNull
  private BankAccount account(
      @NotNull BankAccountType type, @NotNull String name, @Nullable OrgUnit owner) {
    BankAccount account = new BankAccount();
    account.setAccountNo(String.format("KB-%04d", accountRepository.nextAccountNoValue()));
    account.setName(tag + " " + name);
    account.setType(type);
    account.setStatus(BankAccountStatus.ACTIVE);
    account.setOrgUnit(owner);
    return accountRepository.save(account);
  }

  /**
   * Reuses the stored singleton account of a type or saves one, active and, for the KRT account,
   * with the seeded tiers.
   *
   * @param type {@code CARTEL} or {@code CARTEL_BANK}
   * @param owner the owner a new account gets
   * @return the active singleton account
   */
  @NotNull
  private BankAccount singleton(@NotNull BankAccountType type, @Nullable OrgUnit owner) {
    BankAccount account =
        accountRepository.findFirstByType(type).orElseGet(() -> account(type, type.name(), owner));
    account.setStatus(BankAccountStatus.ACTIVE);
    account.setBalanceTarget(null);
    if (type == BankAccountType.CARTEL) {
      account.setEmployeeApprovalCeiling(CARTEL_T1);
      account.setAreaLeadApprovalCeiling(CARTEL_T2);
    }
    return accountRepository.save(account);
  }

  /**
   * Adds an audience view grant.
   *
   * @param account the account
   * @param kind {@code ALL_MEMBERS} or {@code AREA_MEMBERS}
   */
  private void viewGrant(@NotNull BankAccount account, @NotNull BankAccountViewGranteeKind kind) {
    BankAccountViewGrant grant = new BankAccountViewGrant();
    grant.setAccount(account);
    grant.setGranteeKind(kind);
    viewGrantRepository.save(grant);
  }

  /**
   * Adds an audience approval limit of {@link #MEMBER_LIMIT}.
   *
   * @param account the account
   * @param kind {@code ALL_MEMBERS} or {@code AREA_MEMBERS}
   */
  private void limit(@NotNull BankAccount account, @NotNull BankAccountViewGranteeKind kind) {
    BankAccountApprovalLimit limit = new BankAccountApprovalLimit();
    limit.setAccount(account);
    limit.setGranteeKind(kind);
    limit.setLimitAmount(MEMBER_LIMIT);
    limitRepository.save(limit);
  }

  /**
   * Gives an employee a grant row with the given flags on the own Staffel's and the KRT account.
   *
   * @param employee the bank employee
   * @param accounts the seeded accounts
   * @param deposit {@code can_deposit}
   * @param withdraw {@code can_withdraw}
   * @param transfer {@code can_transfer}
   */
  private void grant(
      @NotNull User employee,
      @NotNull Map<Account, BankAccount> accounts,
      boolean deposit,
      boolean withdraw,
      boolean transfer) {
    for (Account granted : List.of(Account.OWN_ORG_UNIT, Account.CARTEL)) {
      BankAccount account = accounts.get(granted);
      BankAccountGrant grant = new BankAccountGrant();
      grant.setId(new BankAccountGrantId(employee.getId(), account.getId()));
      grant.setUser(employee);
      grant.setAccount(account);
      grant.setCanDeposit(deposit);
      grant.setCanWithdraw(withdraw);
      grant.setCanTransfer(transfer);
      grantRepository.save(grant);
    }
  }

  /**
   * Registers a custody holder for a user.
   *
   * @param user the holder's user
   * @param handle the handle suffix
   * @return the saved holder
   */
  @NotNull
  private BankHolder holder(@NotNull User user, @NotNull String handle) {
    BankHolder holder = new BankHolder();
    holder.setUser(user);
    holder.setHandle(tag + "-" + handle);
    holder.setActive(true);
    return holderRepository.save(holder);
  }

  /**
   * Books the opening balance onto every active account through the ledger, as the admin.
   *
   * @param accounts the seeded accounts
   * @param holder the holder the money sits with
   * @param admin the booking admin
   */
  private void fund(
      @NotNull Map<Account, BankAccount> accounts,
      @NotNull BankHolder holder,
      @NotNull User admin) {
    entityManager.flush();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                Jwt.withTokenValue("matrix-seed")
                    .header("alg", "none")
                    .subject(admin.getId().toString())
                    .build(),
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    try {
      for (BankAccount account : accounts.values()) {
        if (account.getStatus() != BankAccountStatus.ACTIVE) {
          continue;
        }
        ledgerService.bookDeposit(
            new BankDepositRequest(
                account.getId(),
                holder.getId(),
                OPENING_BALANCE,
                null,
                null,
                false,
                null,
                null,
                null,
                null));
      }
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  /**
   * Seeds the pending requests, all raised by the member.
   *
   * @param accounts the seeded accounts
   * @param member the requester
   * @return the requests by name
   */
  @NotNull
  private Map<Request, BankBookingRequest> seedRequests(
      @NotNull Map<Account, BankAccount> accounts, @NotNull User member) {
    BankAccount own = accounts.get(Account.OWN_ORG_UNIT);
    BankAccount cartel = accounts.get(Account.CARTEL);
    Map<Request, BankBookingRequest> requests = new EnumMap<>(Request.class);
    for (Request request : Request.values()) {
      BankBookingRequest seeded =
          switch (request) {
            case CONFIRM_DEPOSIT_OWN -> request(own, BankBookingRequestType.DEPOSIT, Band.LOW);
            case CONFIRM_WITHDRAWAL_OWN,
                CANCEL_OWN,
                CORRECT_OWN,
                REJECT_OWN,
                APPROVE_WITHIN_LIMIT ->
                withinLimit(request(own, BankBookingRequestType.WITHDRAWAL, Band.LOW), true);
            case CONFIRM_TRANSFER_OWN ->
                withinLimit(
                    transfer(request(own, BankBookingRequestType.TRANSFER, Band.LOW), cartel),
                    true);
            case CONFIRM_DEPOSIT_CARTEL ->
                request(cartel, BankBookingRequestType.DEPOSIT, Band.LOW);
            case CONFIRM_WITHDRAWAL_CARTEL ->
                withinLimit(request(cartel, BankBookingRequestType.WITHDRAWAL, Band.LOW), false);
            case CONFIRM_TRANSFER_CARTEL ->
                withinLimit(
                    transfer(request(cartel, BankBookingRequestType.TRANSFER, Band.LOW), own),
                    false);
            case CONFIRM_WITHDRAWAL_SPECIAL ->
                request(
                    accounts.get(Account.SPECIAL_PLAIN),
                    BankBookingRequestType.WITHDRAWAL,
                    Band.LOW);
            case CONFIRM_WITHDRAWAL_CARTEL_UNAPPROVED, APPROVE_CARTEL_MANAGEMENT ->
                awaiting(
                    request(cartel, BankBookingRequestType.WITHDRAWAL, Band.MID),
                    CARTEL_T1,
                    BankRequestApprover.BANK_MANAGEMENT);
            case APPROVE_CARTEL_OL ->
                awaiting(
                    request(cartel, BankBookingRequestType.WITHDRAWAL, Band.HIGH),
                    CARTEL_T1,
                    BankRequestApprover.ORGANISATIONSLEITUNG);
            case APPROVE_OWN ->
                awaiting(
                    request(own, BankBookingRequestType.WITHDRAWAL, Band.MID),
                    MEMBER_LIMIT,
                    BankRequestApprover.RESPONSIBLE_HOLDER);
            case APPROVE_SK ->
                awaiting(
                    request(
                        accounts.get(Account.SK_ORG_UNIT),
                        BankBookingRequestType.WITHDRAWAL,
                        Band.MID),
                    null,
                    BankRequestApprover.RESPONSIBLE_HOLDER);
            case APPROVE_AREA ->
                awaiting(
                    request(
                        accounts.get(Account.AREA), BankBookingRequestType.WITHDRAWAL, Band.MID),
                    null,
                    BankRequestApprover.RESPONSIBLE_HOLDER);
          };
      seeded.setRequestedBy(member.getId());
      seeded.setRequesterHandle(member.getUsername());
      requests.put(request, requestRepository.save(seeded));
    }
    return requests;
  }

  /**
   * Builds a pending request.
   *
   * @param account the (source) account
   * @param type the movement kind
   * @param band the amount
   * @return the unsaved request
   */
  @NotNull
  private static BankBookingRequest request(
      @NotNull BankAccount account, @NotNull BankBookingRequestType type, @NotNull Band band) {
    BankBookingRequest request = new BankBookingRequest();
    request.setAccount(account);
    request.setType(type);
    request.setAmount(band.getAmount());
    if (type != BankBookingRequestType.DEPOSIT) {
      request.setJustification(JUSTIFICATION);
    }
    return request;
  }

  /**
   * Sets a transfer request's destination.
   *
   * @param request the transfer request
   * @param target the destination account
   * @return the request
   */
  @NotNull
  private static BankBookingRequest transfer(
      @NotNull BankBookingRequest request, @NotNull BankAccount target) {
    request.setTargetAccount(target);
    return request;
  }

  /**
   * Stamps the routing of a request within its requester's limit.
   *
   * @param request the request
   * @param memberLimit whether the member limit applies; otherwise the KRT ceiling {@code T1}
   * @return the request
   */
  @NotNull
  private static BankBookingRequest withinLimit(
      @NotNull BankBookingRequest request, boolean memberLimit) {
    request.setApplicableLimit(memberLimit ? MEMBER_LIMIT : CARTEL_T1);
    return request;
  }

  /**
   * Stamps the routing of a request that awaits an approver.
   *
   * @param request the request
   * @param limit the requester's applicable limit, or {@code null}
   * @param approver the required approver
   * @return the request
   */
  @NotNull
  private static BankBookingRequest awaiting(
      @NotNull BankBookingRequest request,
      @Nullable BigDecimal limit,
      @NotNull BankRequestApprover approver) {
    request.setRequiresOwnerApproval(true);
    request.setApplicableLimit(limit);
    request.setRequiredApprover(approver);
    return request;
  }

  /**
   * Maps every seeded entity to one of its attributes.
   *
   * @param seeded the entities by key
   * @param attribute the attribute to keep
   * @param <K> the key type
   * @param <E> the entity type
   * @param <V> the attribute type
   * @return the attributes by key
   */
  @NotNull
  private static <K extends Enum<K>, E, V> Map<K, V> ids(
      @NotNull Map<K, E> seeded, @NotNull Function<E, V> attribute) {
    Map<K, V> ids = new HashMap<>();
    seeded.forEach((key, entity) -> ids.put(key, attribute.apply(entity)));
    return Map.copyOf(ids);
  }
}
