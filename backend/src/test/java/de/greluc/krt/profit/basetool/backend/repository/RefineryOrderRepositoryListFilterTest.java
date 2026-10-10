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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.kernel.LikePatterns;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.RefineryGood;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.RefiningMethod;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for the paged list queries {@link RefineryOrderRepository#findFilteredScoped}
 * and {@link RefineryOrderRepository#findOwnedFiltered} against real Postgres (REQ-REFINERY-019):
 * the search over every searched field, one row per order however many goods match, the ready
 * filter at its boundary, the database-computed {@code endsAt} sort and the unchanged scope.
 *
 * <p>Every order is stamped to a Staffel created for the test and the scoped queries pin that
 * Staffel, so the assertions see only the test's own rows. Each test rolls back.
 */
@SpringBootTest
@Transactional
class RefineryOrderRepositoryListFilterTest {

  private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

  private static final Set<RefineryOrderStatus> EVERY_STATUS =
      EnumSet.allOf(RefineryOrderStatus.class);

  private static final Set<RefineryOrderStatus> OPEN_STATUSES =
      EnumSet.of(RefineryOrderStatus.OPEN, RefineryOrderStatus.IN_PROGRESS);

  private static final Pageable FIRST_PAGE = PageRequest.of(0, 50);

  @Autowired private RefineryOrderRepository refineryOrderRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private RefiningMethodRepository refiningMethodRepository;
  @Autowired private MaterialRepository materialRepository;

  @PersistenceContext private EntityManager entityManager;

  private String token;
  private Squadron staffel;
  private User plainOwner;
  private Location plainLocation;
  private RefiningMethod plainMethod;
  private Material plainMaterial;

  @BeforeEach
  void setUp() {
    token = "zq" + UUID.randomUUID().toString().substring(0, 8);
    staffel = createSquadron();
    plainOwner = createUser("plain-" + UUID.randomUUID(), null);
    plainLocation = createLocation("Plain-Hub-" + UUID.randomUUID());
    plainMethod = createMethod("Plain-Method-" + UUID.randomUUID());
    plainMaterial = createMaterial("Plain-Ore-" + UUID.randomUUID());
  }

  /** The search finds an order through each searched field alone, ignoring case. */
  @Test
  void search_matchesTheOwnerLocationMethodAndBothGoodsMaterials() {
    RefineryOrder byUsername =
        createOrder(createUser("owner-" + token, null), plainLocation, plainMethod, staffel);
    RefineryOrder byDisplayName =
        createOrder(
            createUser("display-" + UUID.randomUUID(), "Pilot " + token.toUpperCase(Locale.ROOT)),
            plainLocation,
            plainMethod,
            staffel);
    RefineryOrder byLocation =
        createOrder(plainOwner, createLocation("Hub-" + token), plainMethod, staffel);
    RefineryOrder byMethod =
        createOrder(plainOwner, plainLocation, createMethod("Method-" + token), staffel);
    RefineryOrder byInput = createOrder(plainOwner, plainLocation, null, staffel);
    addGood(byInput, createMaterial("Ore-" + token), plainMaterial);
    RefineryOrder byOutput = createOrder(plainOwner, plainLocation, null, staffel);
    addGood(byOutput, plainMaterial, createMaterial("Refined-" + token));
    RefineryOrder noMatch = createOrder(plainOwner, plainLocation, plainMethod, staffel);
    addGood(noMatch, plainMaterial, plainMaterial);
    flushAndClear();

    List<UUID> ids = idsOf(scoped(EVERY_STATUS, false, pattern(token), FIRST_PAGE));

    assertThat(ids)
        .containsExactlyInAnyOrder(
            byUsername.getId(),
            byDisplayName.getId(),
            byLocation.getId(),
            byMethod.getId(),
            byInput.getId(),
            byOutput.getId());
  }

  /** No search returns every order of every requested status in scope. */
  @Test
  void noSearch_returnsEveryOrderOfTheRequestedStatuses() {
    RefineryOrder open = createOrder(plainOwner, plainLocation, null, staffel);
    RefineryOrder completed = createOrder(plainOwner, plainLocation, null, staffel);
    completed.setStatus(RefineryOrderStatus.COMPLETED);
    flushAndClear();

    assertThat(idsOf(scoped(EVERY_STATUS, false, null, FIRST_PAGE)))
        .containsExactlyInAnyOrder(open.getId(), completed.getId());
    assertThat(idsOf(scoped(EnumSet.of(RefineryOrderStatus.COMPLETED), false, null, FIRST_PAGE)))
        .containsExactly(completed.getId());
  }

  /** An order with several matching goods is one row, and the total counts it once. */
  @Test
  void search_orderWithSeveralMatchingGoods_isReturnedAndCountedOnce() {
    RefineryOrder order = createOrder(plainOwner, plainLocation, null, staffel);
    addGood(order, createMaterial("Ore-A-" + token), createMaterial("Refined-A-" + token));
    addGood(order, createMaterial("Ore-B-" + token), createMaterial("Refined-B-" + token));
    addGood(order, createMaterial("Ore-C-" + token), plainMaterial);
    RefineryOrder second = createOrder(plainOwner, createLocation("Hub-" + token), null, staffel);
    addGood(second, createMaterial("Ore-D-" + token), plainMaterial);
    flushAndClear();

    Page<RefineryOrder> all = scoped(EVERY_STATUS, false, pattern(token), FIRST_PAGE);
    Page<RefineryOrder> firstOfOne =
        scoped(EVERY_STATUS, false, pattern(token), PageRequest.of(0, 1, Sort.by("id")));

    assertThat(idsOf(all)).containsExactlyInAnyOrder(order.getId(), second.getId());
    assertThat(all.getTotalElements()).isEqualTo(2);
    assertThat(firstOfOne.getContent()).hasSize(1);
    assertThat(firstOfOne.getTotalElements()).isEqualTo(2);
    assertThat(firstOfOne.getTotalPages()).isEqualTo(2);
  }

  /** A LIKE wildcard in the search text matches only itself. */
  @Test
  void search_likeWildcardsInTheTextMatchLiterally() {
    RefineryOrder percent =
        createOrder(plainOwner, createLocation(token + " 50%off"), null, staffel);
    createOrder(plainOwner, createLocation(token + " 50xoff"), null, staffel);
    createOrder(plainOwner, createLocation(token + " 5_0off"), null, staffel);
    flushAndClear();

    assertThat(idsOf(scoped(EVERY_STATUS, false, pattern(token + " 50%"), FIRST_PAGE)))
        .containsExactly(percent.getId());
    assertThat(idsOf(scoped(EVERY_STATUS, false, pattern("_0off"), FIRST_PAGE))).hasSize(1);
  }

  /**
   * The ready filter keeps an order whose end is exactly now, one whose end has passed and one
   * whose end is unknown, and drops one that ends a minute later; the status filter still applies.
   */
  @Test
  void ready_keepsEndsAtOrBeforeNowAndUnknownEnds_andCombinesWithTheStatuses() {
    RefineryOrder endsNow = createTimedOrder(NOW.minus(90, ChronoUnit.MINUTES), 90L);
    RefineryOrder endsLater = createTimedOrder(NOW.minus(89, ChronoUnit.MINUTES), 90L);
    RefineryOrder endedLongAgo = createTimedOrder(NOW.minus(300, ChronoUnit.MINUTES), 10L);
    RefineryOrder unknownEnd = createTimedOrder(NOW.minus(10, ChronoUnit.MINUTES), null);
    RefineryOrder storedAndEnded = createTimedOrder(NOW.minus(300, ChronoUnit.MINUTES), 10L);
    storedAndEnded.setStatus(RefineryOrderStatus.COMPLETED);
    flushAndClear();

    assertThat(idsOf(scoped(OPEN_STATUSES, true, null, FIRST_PAGE)))
        .containsExactlyInAnyOrder(endsNow.getId(), endedLongAgo.getId(), unknownEnd.getId());
    assertThat(idsOf(scoped(OPEN_STATUSES, false, null, FIRST_PAGE)))
        .containsExactlyInAnyOrder(
            endsNow.getId(), endsLater.getId(), endedLongAgo.getId(), unknownEnd.getId());
  }

  /** {@code endsAt} is computed as start plus duration and sorts the page ascending. */
  @Test
  void endsAt_isStartPlusDuration_andSortsAscending() {
    RefineryOrder third = createTimedOrder(NOW.minus(30, ChronoUnit.MINUTES), 60L);
    RefineryOrder first = createTimedOrder(NOW.minus(70, ChronoUnit.MINUTES), 60L);
    RefineryOrder second = createTimedOrder(NOW.minus(55, ChronoUnit.MINUTES), 60L);
    flushAndClear();

    Pageable byEnd = PageRequest.of(0, 50, Sort.by(Sort.Order.asc("endsAt"), Sort.Order.asc("id")));
    Page<RefineryOrder> page = scoped(OPEN_STATUSES, false, null, byEnd);

    assertThat(idsOf(page)).containsExactly(first.getId(), second.getId(), third.getId());
    assertThat(page.getContent().getFirst().getEndsAt())
        .isEqualTo(NOW.minus(10, ChronoUnit.MINUTES));
    assertThat(refineryOrderRepository.findById(third.getId()).orElseThrow().getEndsAt())
        .isEqualTo(NOW.plus(30, ChronoUnit.MINUTES));
  }

  /** A matching order of a Staffel outside the caller's scope stays hidden; an admin sees both. */
  @Test
  void search_doesNotWidenTheScope() {
    Squadron foreign = createSquadron();
    RefineryOrder inScope = createOrder(plainOwner, createLocation("Hub-" + token), null, staffel);
    RefineryOrder foreignOrder =
        createOrder(plainOwner, createLocation("Hub-2-" + token), null, foreign);
    flushAndClear();

    List<UUID> memberView =
        idsOf(
            refineryOrderRepository.findFilteredScoped(
                EVERY_STATUS,
                false,
                NOW,
                pattern(token),
                false,
                null,
                Set.of(staffel.getId()),
                FIRST_PAGE));
    List<UUID> adminView =
        idsOf(
            refineryOrderRepository.findFilteredScoped(
                EVERY_STATUS, false, NOW, pattern(token), true, null, Set.of(), FIRST_PAGE));

    assertThat(memberView).containsExactly(inScope.getId());
    assertThat(adminView).contains(inScope.getId(), foreignOrder.getId());
  }

  /** The own-orders query returns only the owner's orders, whatever else matches the search. */
  @Test
  void ownedFiltered_returnsOnlyTheOwnersMatchingOrders() {
    User owner = createUser("me-" + UUID.randomUUID(), null);
    RefineryOrder mine = createOrder(owner, createLocation("Hub-" + token), null, staffel);
    createOrder(owner, plainLocation, null, staffel);
    createOrder(plainOwner, createLocation("Hub-3-" + token), null, staffel);
    flushAndClear();

    assertThat(
            idsOf(
                refineryOrderRepository.findOwnedFiltered(
                    owner.getId(), EVERY_STATUS, false, NOW, pattern(token), FIRST_PAGE)))
        .containsExactly(mine.getId());
  }

  /**
   * Runs the scoped list query pinned to the test's Staffel at {@link #NOW}.
   *
   * @param statuses the statuses to include
   * @param readyOnly whether only ready orders are kept
   * @param pattern the search pattern, or {@code null}
   * @param pageable the page request
   * @return the page
   */
  @NotNull
  private Page<RefineryOrder> scoped(
      @NotNull Set<RefineryOrderStatus> statuses,
      boolean readyOnly,
      @Nullable String pattern,
      @NotNull Pageable pageable) {
    return refineryOrderRepository.findFilteredScoped(
        statuses, readyOnly, NOW, pattern, false, staffel.getId(), Set.of(), pageable);
  }

  /**
   * Builds the search pattern the service hands to the queries.
   *
   * @param text the search text
   * @return the lower-cased, escaped {@code %text%} pattern
   */
  @NotNull
  private static String pattern(@NotNull String text) {
    return LikePatterns.contains(text.toLowerCase(Locale.ROOT));
  }

  /**
   * The ids of a page's orders, in page order.
   *
   * @param page the page
   * @return the ids
   */
  @NotNull
  private static List<UUID> idsOf(@NotNull Page<RefineryOrder> page) {
    return page.map(RefineryOrder::getId).getContent();
  }

  /**
   * Persists a Staffel with a unique name and shorthand.
   *
   * @return the persisted Staffel
   */
  @NotNull
  private Squadron createSquadron() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    Squadron squadron = new Squadron();
    squadron.setName("ListFilter Refinery " + suffix);
    squadron.setShorthand("LFR" + suffix);
    return squadronRepository.saveAndFlush(squadron);
  }

  /**
   * Persists a user.
   *
   * @param username the unique username
   * @param displayName the display name, or {@code null}
   * @return the persisted user
   */
  @NotNull
  private User createUser(@NotNull String username, @Nullable String displayName) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    user.setDisplayName(displayName);
    return userRepository.save(user);
  }

  /**
   * Persists a location with the given unique name.
   *
   * @param name the name
   * @return the persisted location
   */
  @NotNull
  private Location createLocation(@NotNull String name) {
    Location location = new Location();
    location.setName(name);
    return locationRepository.save(location);
  }

  /**
   * Persists a refining method with the given name.
   *
   * @param name the name
   * @return the persisted method
   */
  @NotNull
  private RefiningMethod createMethod(@NotNull String name) {
    RefiningMethod method = new RefiningMethod();
    method.setName(name);
    return refiningMethodRepository.save(method);
  }

  /**
   * Persists a raw material with the given unique name.
   *
   * @param name the name
   * @return the persisted material
   */
  @NotNull
  private Material createMaterial(@NotNull String name) {
    Material material = new Material();
    material.setName(name);
    material.setType(MaterialType.RAW);
    return materialRepository.save(material);
  }

  /**
   * Persists an {@code OPEN} order.
   *
   * @param owner the owner
   * @param location the location
   * @param method the refining method, or {@code null}
   * @param owningOrgUnit the stamped org unit
   * @return the persisted order
   */
  @NotNull
  private RefineryOrder createOrder(
      @NotNull User owner,
      @NotNull Location location,
      @Nullable RefiningMethod method,
      @NotNull OrgUnit owningOrgUnit) {
    RefineryOrder order = new RefineryOrder();
    order.setOwner(owner);
    order.setLocation(location);
    order.setRefiningMethod(method);
    order.setOwningOrgUnit(owningOrgUnit);
    order.setStartedAt(NOW);
    order.setDurationMinutes(60L);
    return refineryOrderRepository.save(order);
  }

  /**
   * Persists an {@code OPEN} order of the plain owner in the test's Staffel with the given run.
   *
   * @param startedAt the start of the run
   * @param durationMinutes the duration, or {@code null} for an unknown end
   * @return the persisted order
   */
  @NotNull
  private RefineryOrder createTimedOrder(
      @NotNull Instant startedAt, @Nullable Long durationMinutes) {
    RefineryOrder order = createOrder(plainOwner, plainLocation, null, staffel);
    order.setStartedAt(startedAt);
    order.setDurationMinutes(durationMinutes);
    return order;
  }

  /**
   * Adds a good to an order; the cascade persists it with the order.
   *
   * @param order the managed order
   * @param input the input material
   * @param output the output material
   */
  private static void addGood(
      @NotNull RefineryOrder order, @NotNull Material input, @NotNull Material output) {
    RefineryGood good = new RefineryGood();
    good.setInputMaterial(input);
    good.setInputQuantity(100);
    good.setOutputMaterial(output);
    good.setOutputQuantity(80);
    good.setQuality(500);
    good.setRefineryOrder(order);
    order.getGoods().add(good);
  }

  /** Flushes the seeded rows, then clears the context so the reads hit the database. */
  private void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
  }
}
