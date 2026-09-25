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

import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.List;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Pins BE-PERF-07: {@code GET /api/v1/me/layout} answers its four parts in <em>one</em> read-only
 * transaction, where the web layout used to open one per call ({@code /active-org-unit}, {@code
 * /org-units}, {@code /capabilities}, {@code /notifications/unread-count}).
 *
 * <p>Calls the proxied controller bean the way the dispatcher does, with a JWT principal and a
 * bound servlet request, but without a test transaction around it — a surrounding transaction would
 * absorb the controller's own and make the count meaningless.
 */
@SpringBootTest
@ActiveProfiles("test")
class MeLayoutSingleTransactionTest {

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
