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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.app.web.MeController;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Verifies that {@code GET /api/v1/me/layout} answers all four parts in one read-only transaction
 * and that the supply and purchasing tab flags add no statement per unit. Runs without a
 * surrounding test transaction so the counts are meaningful.
 */
@SpringBootTest
class MeLayoutSingleTransactionTest {

  private static final List<String> SURFACE_KEYS = List.of("supply.enabled", "purchasing.enabled");

  @Autowired private MeController meController;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private EntityManagerFactory entityManagerFactory;

  private UUID userId;
  private Jwt jwt;

  @BeforeEach
  void signIn() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("layout-" + UUID.randomUUID());
    userId = userRepository.save(user).getId();
    jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject(userId.toString())
            .issuedAt(Instant.now())
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")), userId.toString()));
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
  }

  @AfterEach
  void signOut() {
    SecurityContextHolder.clearContext();
    RequestContextHolder.resetRequestAttributes();
    jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
  }

  @Test
  void layoutRunsInOneTransaction() {
    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);
    stats.clear();

    MeController.LayoutResponse layout = meController.getLayout(jwt, userId);

    assertThat(stats.getTransactionCount()).isEqualTo(1L);
    assertThat(layout.capabilities()).isNotNull();
    assertThat(layout.orgUnits()).isNotNull();
    assertThat(layout.unreadNotifications()).isZero();
  }

  @Test
  void theTabFlagsCostTheSameStatementsWhateverTheNumberOfAcceptingUnits() {
    Map<String, String> previous = enableSurfaces();
    List<UUID> units = new ArrayList<>();
    try {
      signInAsAdmin();
      units.add(acceptingUnit());
      long withOne = statementsOfOneLayoutRead();
      units.add(acceptingUnit());
      units.add(acceptingUnit());
      units.add(acceptingUnit());
      long withFour = statementsOfOneLayoutRead();

      MeController.CapabilitiesResponse capabilities = meController.getCapabilities();
      assertThat(capabilities.canViewSupplyIncoming()).isTrue();
      assertThat(capabilities.canViewSupplyStats()).isTrue();
      assertThat(capabilities.canViewPurchasingPartners()).isTrue();
      assertThat(capabilities.canViewPurchasingDelegates()).isTrue();
      assertThat(capabilities.canViewPurchasingReport()).isTrue();
      assertThat(withFour).isEqualTo(withOne);
    } finally {
      units.forEach(id -> jdbcTemplate.update("DELETE FROM org_unit WHERE id = ?", id));
      restoreSettings(previous);
    }
  }

  @Test
  void theTabFlagsAreOffWhileTheSurfacesAreOff() {
    Map<String, String> previous = disableSurfaces();
    try {
      signInAsAdmin();

      MeController.CapabilitiesResponse capabilities = meController.getCapabilities();

      assertThat(capabilities.canViewSupplyIncoming()).isFalse();
      assertThat(capabilities.canViewSupplyStats()).isFalse();
      assertThat(capabilities.canViewPurchasingPartners()).isFalse();
      assertThat(capabilities.canViewPurchasingDelegates()).isFalse();
      assertThat(capabilities.canViewPurchasingReport()).isFalse();
    } finally {
      restoreSettings(previous);
    }
  }

  private long statementsOfOneLayoutRead() {
    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);
    stats.clear();
    meController.getLayout(jwt, userId);
    return stats.getPrepareStatementCount();
  }

  private void signInAsAdmin() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                jwt,
                List.of(
                    new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
                    new SimpleGrantedAuthority("ROLE_ADMIN")),
                userId.toString()));
  }

  private UUID acceptingUnit() {
    UUID id = UUID.randomUUID();
    String shorthand = "T" + id.toString().substring(0, 7);
    jdbcTemplate.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_promotion_enabled,"
            + " is_profit_eligible) VALUES (?, 'SQUADRON', ?, ?, TRUE, FALSE, FALSE)",
        id,
        "Layout tabs " + id,
        shorthand);
    jdbcTemplate.update(
        "INSERT INTO supply_unit_setting (org_unit_id, accepts_donations) VALUES (?, TRUE)", id);
    return id;
  }

  private Map<String, String> enableSurfaces() {
    return setSurfaces("true");
  }

  private Map<String, String> disableSurfaces() {
    return setSurfaces("false");
  }

  private Map<String, String> setSurfaces(String value) {
    Map<String, String> previous = new HashMap<>();
    for (String key : SURFACE_KEYS) {
      jdbcTemplate
          .query(
              "SELECT setting_value FROM system_setting WHERE setting_key = ?",
              (rs, _) -> rs.getString(1),
              key)
          .stream()
          .findFirst()
          .ifPresent(old -> previous.put(key, old));
      jdbcTemplate.update(
          "INSERT INTO system_setting (setting_key, setting_value) VALUES (?, ?)"
              + " ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value",
          key,
          value);
    }
    return previous;
  }

  private void restoreSettings(Map<String, String> previous) {
    for (String key : SURFACE_KEYS) {
      if (previous.containsKey(key)) {
        jdbcTemplate.update(
            "UPDATE system_setting SET setting_value = ? WHERE setting_key = ?",
            previous.get(key),
            key);
      } else {
        jdbcTemplate.update("DELETE FROM system_setting WHERE setting_key = ?", key);
      }
    }
  }

  @Test
  void theSeparateCallsOpenSeveral_soTheCounterSeesTheDifference() {
    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);
    stats.clear();

    meController.getActiveOrgUnit();
    meController.getPinnableOrgUnits(jwt);
    meController.getCapabilities();

    assertThat(stats.getTransactionCount()).isGreaterThan(1L);
  }
}
