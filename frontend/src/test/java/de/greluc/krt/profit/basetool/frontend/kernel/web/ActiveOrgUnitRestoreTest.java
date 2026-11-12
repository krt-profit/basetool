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

package de.greluc.krt.profit.basetool.frontend.kernel.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

/**
 * Unit tests of {@link ActiveOrgUnitRestore} (REQ-ORG-031): the stored choice seeds the session
 * once per session and principal, a session that holds a pin but was never restored keeps it,
 * another principal's pin is replaced, an access gate's refusal changes nothing until the gate
 * clears, any other failed read is retried later, a lost OAuth2 session restores nothing and an
 * asset request reads nothing.
 */
class ActiveOrgUnitRestoreTest {

  private static final UUID HEL = UUID.randomUUID();
  private static final UUID ARGON = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

  private final ActiveOrgUnitChoiceSource source = mock(ActiveOrgUnitChoiceSource.class);
  private final Logger log = (Logger) LoggerFactory.getLogger(ActiveOrgUnitRestore.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private Level previousLevel;
  private MockHttpServletRequest request;
  private MockHttpSession session;

  @BeforeEach
  void setUp() {
    session = new MockHttpSession();
    request = new MockHttpServletRequest();
    request.setSession(session);
    appender.start();
    log.addAppender(appender);
    previousLevel = log.getLevel();
    log.setLevel(Level.DEBUG);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    log.detachAppender(appender);
    log.setLevel(previousLevel);
  }

  private static void signIn(String principal) {
    DefaultOAuth2User user =
        new DefaultOAuth2User(
            List.of(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")),
            Map.of("sub", principal),
            "sub");
    SecurityContextHolder.getContext()
        .setAuthentication(new OAuth2AuthenticationToken(user, user.getAuthorities(), "keycloak"));
  }

  private ActiveOrgUnitRestore restoreAt(Instant instant) {
    return new ActiveOrgUnitRestore(source, Clock.fixed(instant, ZoneOffset.UTC));
  }

  private Object sessionPin() {
    return session.getAttribute(ActiveSquadronContext.ACTIVE_ORG_UNIT_SESSION_KEY);
  }

  private static BackendServiceException gateRefusal(String problemCode) {
    return new BackendServiceException(
        "access gate refused", null, 403, problemCode, null, List.of(), null);
  }

  @Test
  void theStoredChoiceSeedsTheSessionOnceForThePrincipal() {
    signIn("lena");
    when(source.storedChoice()).thenReturn(Optional.of(ARGON));
    ActiveOrgUnitRestore restore = restoreAt(NOW);

    restore.restoreOnce(request);
    restore.restoreOnce(request);

    assertThat(sessionPin()).isEqualTo(ARGON.toString());
    verify(source, times(1)).storedChoice();
  }

  @Test
  void noStoredChoiceLeavesTheSessionOnAllOrgUnits() {
    signIn("lena");
    when(source.storedChoice()).thenReturn(Optional.empty());

    restoreAt(NOW).restoreOnce(request);

    assertThat(sessionPin()).isNull();
    assertThat(session.getAttribute(ActiveOrgUnitRestore.RESTORED_FOR_SESSION_KEY))
        .isEqualTo("lena");
  }

  @Test
  void aSessionThatAlreadyHoldsAPinKeepsIt() {
    signIn("lena");
    session.setAttribute(ActiveSquadronContext.ACTIVE_ORG_UNIT_SESSION_KEY, HEL.toString());

    restoreAt(NOW).restoreOnce(request);

    assertThat(sessionPin()).isEqualTo(HEL.toString());
    verify(source, never()).storedChoice();
  }

  @Test
  void anotherPrincipalsPinIsReplacedByTheStoredChoice() {
    session.setAttribute(ActiveSquadronContext.ACTIVE_ORG_UNIT_SESSION_KEY, HEL.toString());
    session.setAttribute(ActiveOrgUnitRestore.RESTORED_FOR_SESSION_KEY, "max");
    signIn("lena");
    when(source.storedChoice()).thenReturn(Optional.empty());

    restoreAt(NOW).restoreOnce(request);

    assertThat(sessionPin()).isNull();
    assertThat(session.getAttribute(ActiveOrgUnitRestore.RESTORED_FOR_SESSION_KEY))
        .isEqualTo("lena");
  }

  @Test
  void aFailedReadLeavesTheSessionUnpinnedAndIsRetriedAfterTheWait() {
    session.setAttribute(ActiveSquadronContext.ACTIVE_ORG_UNIT_SESSION_KEY, HEL.toString());
    session.setAttribute(ActiveOrgUnitRestore.RESTORED_FOR_SESSION_KEY, "max");
    signIn("lena");
    when(source.storedChoice())
        .thenThrow(new BackendServiceException("backend down", null, 503))
        .thenReturn(Optional.of(ARGON));

    restoreAt(NOW).restoreOnce(request);
    assertThat(sessionPin()).isNull();

    restoreAt(NOW.plusMillis(ActiveOrgUnitRestore.RETRY_AFTER_MILLIS - 1)).restoreOnce(request);
    verify(source, times(1)).storedChoice();

    restoreAt(NOW.plusMillis(ActiveOrgUnitRestore.RETRY_AFTER_MILLIS)).restoreOnce(request);
    assertThat(sessionPin()).isEqualTo(ARGON.toString());
    assertThat(session.getAttribute(ActiveOrgUnitRestore.RETRY_AT_SESSION_KEY)).isNull();
  }

  /**
   * A session that starts behind an access gate stays unrestored without a wait, logs only at
   * DEBUG, and the first request after the gate clears restores the stored choice.
   *
   * @param problemCode the gate's refusal code
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        BackendServiceException.CODE_PENDING_APPROVAL,
        BackendServiceException.CODE_TERMS_NOT_ACCEPTED,
        BackendServiceException.CODE_NO_ROLE
      })
  void anAccessGateRefusalWaitsForTheGateNotForTheRetry(String problemCode) {
    signIn("lena");
    when(source.storedChoice()).thenThrow(gateRefusal(problemCode)).thenReturn(Optional.of(ARGON));
    ActiveOrgUnitRestore restore = restoreAt(NOW);

    restore.restoreOnce(request);
    assertThat(sessionPin()).isNull();
    assertThat(session.getAttribute(ActiveOrgUnitRestore.RESTORED_FOR_SESSION_KEY)).isNull();
    assertThat(session.getAttribute(ActiveOrgUnitRestore.RETRY_AT_SESSION_KEY)).isNull();
    assertThat(appender.list).extracting(ILoggingEvent::getLevel).containsOnly(Level.DEBUG);

    restore.restoreOnce(request);
    assertThat(sessionPin()).isEqualTo(ARGON.toString());
    assertThat(session.getAttribute(ActiveOrgUnitRestore.RESTORED_FOR_SESSION_KEY))
        .isEqualTo("lena");
  }

  /** An access gate's refusal keeps the pin and marker a session holds until a read succeeds. */
  @Test
  void anAccessGateRefusalKeepsTheSessionsPinAndMarker() {
    session.setAttribute(ActiveSquadronContext.ACTIVE_ORG_UNIT_SESSION_KEY, HEL.toString());
    session.setAttribute(ActiveOrgUnitRestore.RESTORED_FOR_SESSION_KEY, "max");
    signIn("lena");
    when(source.storedChoice())
        .thenThrow(gateRefusal(BackendServiceException.CODE_TERMS_NOT_ACCEPTED))
        .thenReturn(Optional.empty());
    ActiveOrgUnitRestore restore = restoreAt(NOW);

    restore.restoreOnce(request);
    assertThat(sessionPin()).isEqualTo(HEL.toString());
    assertThat(session.getAttribute(ActiveOrgUnitRestore.RESTORED_FOR_SESSION_KEY))
        .isEqualTo("max");

    restore.restoreOnce(request);
    assertThat(sessionPin()).isNull();
    assertThat(session.getAttribute(ActiveOrgUnitRestore.RESTORED_FOR_SESSION_KEY))
        .isEqualTo("lena");
  }

  /** A static asset cannot show the context, so its request reads nothing and marks nothing. */
  @Test
  void anAssetRequestReadsNothing() {
    signIn("lena");
    request.setRequestURI("/css/styles.css");

    restoreAt(NOW).restoreOnce(request);

    verify(source, never()).storedChoice();
    assertThat(session.getAttribute(ActiveOrgUnitRestore.RESTORED_FOR_SESSION_KEY)).isNull();
  }

  /**
   * A gated session reaches only the gate pages, and the waiting page polls its status every few
   * seconds; none of them reads the choice, so no refused backend read feeds the gate's metric, and
   * the first page after the gate restores it.
   */
  @ParameterizedTest
  @ValueSource(strings = {"/pending-approval", "/pending-approval/status", "/terms/accept"})
  void aGatePageReadsNothingAndTheNextPageRestores(String gatePage) {
    signIn("lena");
    when(source.storedChoice()).thenReturn(Optional.of(ARGON));
    ActiveOrgUnitRestore restore = restoreAt(NOW);

    request.setRequestURI(gatePage);
    restore.restoreOnce(request);

    verify(source, never()).storedChoice();
    assertThat(session.getAttribute(ActiveOrgUnitRestore.RESTORED_FOR_SESSION_KEY)).isNull();

    request.setRequestURI("/");
    restore.restoreOnce(request);

    assertThat(sessionPin()).isEqualTo(ARGON.toString());
  }

  @Test
  void aLostOAuth2SessionRestoresNothingAndRetriesOnTheNextRequest() {
    signIn("lena");
    when(source.storedChoice())
        .thenThrow(new ReauthenticationRequiredException("session lost", null))
        .thenReturn(Optional.of(HEL));
    ActiveOrgUnitRestore restore = restoreAt(NOW);

    restore.restoreOnce(request);
    assertThat(session.getAttribute(ActiveOrgUnitRestore.RESTORED_FOR_SESSION_KEY)).isNull();

    restore.restoreOnce(request);
    assertThat(sessionPin()).isEqualTo(HEL.toString());
  }

  @Test
  void anAnonymousRequestOrOneWithoutASessionRestoresNothing() {
    restoreAt(NOW).restoreOnce(request);

    signIn("lena");
    restoreAt(NOW).restoreOnce(new MockHttpServletRequest());

    verify(source, never()).storedChoice();
  }
}
