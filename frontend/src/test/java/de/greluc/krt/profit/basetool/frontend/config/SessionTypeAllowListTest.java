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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.frontend.model.dto.ImportIssueCode;
import de.greluc.krt.profit.basetool.frontend.model.dto.ImportIssueDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ImportIssueSeverity;
import de.greluc.krt.profit.basetool.frontend.model.dto.ImportSuggestionDto;
import de.greluc.krt.profit.basetool.frontend.model.form.InventoryForm;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.tomcat.websocket.server.WsHttpSessionBindingListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.FlashMap;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the session type allow-list (REQ-SEC-067, ADR-0206): every value a real session holds is
 * read under {@code ENFORCE} exactly as under the permissive validator of before, and a class
 * outside the list is refused before it is instantiated.
 *
 * <p><strong>Why a parity test rather than a list of successes.</strong> Switching the validator
 * cannot change what is <em>written</em>, only what is <em>read</em>, so the question a deploy
 * poses is precisely "does anything that was readable yesterday stop being readable today". Each
 * value below is written once and read under {@code OFF} and under {@code ENFORCE}; whatever the
 * permissive validator could read, the enforcing one must read to the same bytes. A value neither
 * can read (there are none today, but a flash-map shape once was) is not this list's business.
 *
 * <p>The sample is the session as production writes it: the OIDC security context, the authorized
 * client with both tokens, the pre-login authorization request, the CSRF token, the saved request,
 * a redirect's flash maps with a form and the refinery-import DTOs (and one with a form's {@code
 * BindingResult}, which no validator can read — see {@link #realisticSession()}), the attributes
 * our own filters write, and Tomcat's WebSocket binding listener.
 */
class SessionTypeAllowListTest {

  /** Flipped by {@link GadgetLikeBean}'s setter; the gadget test proves it is never flipped. */
  private static final AtomicBoolean GADGET_SETTER_RAN = new AtomicBoolean();

  private final ClassLoader loader = getClass().getClassLoader();

  private final RedisSerializer<Object> permissive = serializer(SessionTypeAllowList.Mode.OFF);

  private final RedisSerializer<Object> enforcing = serializer(SessionTypeAllowList.Mode.ENFORCE);

  @BeforeEach
  void resetGadget() {
    GADGET_SETTER_RAN.set(false);
  }

  @Test
  void everyValueARealSessionHolds_isReadUnderEnforceExactlyAsBefore() {
    Map<String, Object> session = realisticSession();
    List<String> readableBefore = new ArrayList<>();

    for (Map.Entry<String, Object> attribute : session.entrySet()) {
      byte[] written = permissive.serialize(attribute.getValue());
      Object before = readOrNull(permissive, written);
      if (before == null) {
        continue;
      }
      readableBefore.add(attribute.getKey());
      Object after = enforcing.deserialize(written);
      assertThat(new String(enforcing.serialize(after), StandardCharsets.UTF_8))
          .as("attribute %s must read back identically under ENFORCE", attribute.getKey())
          .isEqualTo(new String(permissive.serialize(before), StandardCharsets.UTF_8));
    }

    assertThat(readableBefore)
        .contains(
            "SPRING_SECURITY_CONTEXT",
            "authorizedClients",
            "authorizationRequest",
            "csrfToken",
            "savedRequest",
            "flashMaps",
            "rolesSyncedAt",
            "syncedAuthorities",
            "activeOrgUnit",
            "welcomeMessageShown",
            "wsBindingListener",
            "sessionCreatedEventPayload");
  }

  @Test
  void aRealOidcLoginsIdTokenIsReadUnderEnforce() {
    OidcIdToken token = decodedIdToken(Instant.parse("2026-09-23T10:00:00Z"));

    Object back = enforcing.deserialize(enforcing.serialize(new ArrayList<>(List.of(token))));

    OidcIdToken read = (OidcIdToken) ((List<?>) back).getFirst();
    assertThat(read.getClaims().get("iss")).isInstanceOf(java.net.URL.class);
    assertThat(read.getClaims().get("custom_numeric")).isEqualTo(5L);
    assertThat(read.getClaims().get("realm_access")).isInstanceOf(Map.class);
  }

  @Test
  void theTokenResponsesOrderedMapIsReadUnderEnforce() {
    com.nimbusds.oauth2.sdk.util.OrderedJSONObject parsed =
        new com.nimbusds.oauth2.sdk.util.OrderedJSONObject();
    parsed.put("session_state", "e2e");

    Object back = enforcing.deserialize(enforcing.serialize(new ArrayList<>(List.of(parsed))));

    assertThat(((List<?>) back).getFirst())
        .isInstanceOf(com.nimbusds.oauth2.sdk.util.OrderedJSONObject.class)
        .isEqualTo(parsed);
  }

  @Test
  void theSecurityContextKeepsItsPrincipalAndAuthorities() {
    SecurityContextImpl context =
        (SecurityContextImpl) realisticSession().get("SPRING_SECURITY_CONTEXT");

    Object back = enforcing.deserialize(enforcing.serialize(context));

    assertThat(back).isInstanceOf(SecurityContextImpl.class);
    OAuth2AuthenticationToken token =
        (OAuth2AuthenticationToken) ((SecurityContextImpl) back).getAuthentication();
    assertThat(token.getName()).isEqualTo("member-subject");
    assertThat(token.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .contains("ROLE_MEMBER");
  }

  @Test
  void aGadgetShapedClassOutsideTheList_isRefusedBeforeItIsBuilt() {
    GadgetLikeBean gadget = new GadgetLikeBean();
    byte[] written = permissive.serialize(new ArrayList<>(List.of(gadget)));
    GADGET_SETTER_RAN.set(false);

    assertThatThrownBy(() -> enforcing.deserialize(written))
        .isInstanceOf(SerializationException.class);
    assertThat(GADGET_SETTER_RAN)
        .as("a refused class must never be instantiated, let alone have its setters called")
        .isFalse();
  }

  @Test
  void theSameGadget_isStillReadUnderReportAndOff() {
    byte[] written = permissive.serialize(new ArrayList<>(List.of(new GadgetLikeBean())));
    List<String> reported = new ArrayList<>();
    RedisSerializer<Object> reporting =
        new GenericJacksonJsonRedisSerializer(
            RedisSessionConfig.buildSessionJsonMapper(
                loader,
                SessionTypeAllowList.validatorBuilder(
                    SessionTypeAllowList.Mode.REPORT, (name, mode) -> reported.add(name))));

    assertThat(reporting.deserialize(written)).isInstanceOf(List.class);
    assertThat(permissive.deserialize(written)).isInstanceOf(List.class);
    assertThat(reported).containsExactly(GadgetLikeBean.class.getName());
  }

  @Test
  void aJdkSubpackageIsNotCoveredByTheJdkEntry() {
    Map<String, String> concurrent = new ConcurrentHashMap<>(Map.of("k", "v"));
    byte[] written = permissive.serialize(concurrent);

    assertThatThrownBy(() -> enforcing.deserialize(written))
        .isInstanceOf(SerializationException.class);
  }

  @ParameterizedTest
  @CsvSource({
    "java.util.HashMap,true",
    "java.lang.Long,true",
    "java.lang.String,true",
    "java.lang.ProcessBuilder,false",
    "java.lang.Thread,false",
    "java.net.URL,true",
    "java.net.InetAddress,false",
    "java.math.BigDecimal,true",
    "com.nimbusds.jose.shaded.gson.internal.LinkedTreeMap,true",
    "com.nimbusds.oauth2.sdk.util.OrderedJSONObject,true",
    "com.nimbusds.oauth2.sdk.util.JSONObjectUtils,false",
    "com.nimbusds.jose.shaded.gson.Gson,false",
    "java.util.Collections$UnmodifiableMap,true",
    "java.time.Instant,true",
    "java.util.logging.FileHandler,false",
    "java.util.concurrent.ConcurrentHashMap,false",
    "org.springframework.validation.FieldError,true",
    "org.springframework.validation.beanvalidation.LocalValidatorFactoryBean,false",
    "org.springframework.web.servlet.FlashMap,true",
    "org.springframework.web.servlet.FlashMapManager,false"
  })
  void theNamePatternsCoverExactlyTheirPackage(String className, boolean allowed) {
    boolean matched =
        SessionTypeAllowList.JDK_VALUE_TYPES.matcher(className).matches()
            || SessionTypeAllowList.VALIDATION_TYPES.matcher(className).matches()
            || SessionTypeAllowList.JAVA_LANG_SCALARS.matcher(className).matches()
            || SessionTypeAllowList.ALLOWED_EXACT_NAMES.contains(className)
            || SessionTypeAllowList.ALLOWED_PREFIXES.stream().anyMatch(className::startsWith);

    assertThat(matched).as(className).isEqualTo(allowed);
  }

  @Test
  void aRefusalUnderEnforce_isDroppedCountedAndNamedByMode() {
    MeterRegistry registry = new SimpleMeterRegistry();
    ObjectProvider<MeterRegistry> provider = provider(registry);
    RedisSerializer<Object> production =
        new FaultTolerantSessionSerializer(
            new GenericJacksonJsonRedisSerializer(
                RedisSessionConfig.buildSessionJsonMapper(
                    loader,
                    SessionTypeAllowList.validatorBuilder(
                        SessionTypeAllowList.Mode.ENFORCE,
                        new SessionTypeAllowList.MeteredRefusalListener(provider)))),
            provider);
    byte[] written = permissive.serialize(new ArrayList<>(List.of(new GadgetLikeBean())));

    Object back = production.deserialize(written);

    assertThat(back).isInstanceOf(UnreadableSessionValue.class);
    assertThat(((UnreadableSessionValue) back).cause()).isEqualTo("InvalidTypeIdException");
    assertThat(GADGET_SETTER_RAN).isFalse();
    assertThat(
            registry
                .counter(MetricNames.SESSION_TYPE_REFUSED, MetricNames.TAG_MODE, "enforce")
                .count())
        .isEqualTo(1.0);
    assertThat(
            registry
                .counter(
                    MetricNames.SESSION_VALUE_DROPPED,
                    MetricNames.TAG_CAUSE,
                    "InvalidTypeIdException")
                .count())
        .as("the refused attribute is dropped like any unreadable one, so the drop alert sees it")
        .isEqualTo(1.0);
  }

  @Test
  void aReportedClass_isCountedUnderTheReportTag() {
    MeterRegistry registry = new SimpleMeterRegistry();
    SessionTypeAllowList.MeteredRefusalListener listener =
        new SessionTypeAllowList.MeteredRefusalListener(provider(registry));

    listener.refused(GadgetLikeBean.class.getName(), SessionTypeAllowList.Mode.REPORT);
    listener.refused(GadgetLikeBean.class.getName(), SessionTypeAllowList.Mode.REPORT);

    assertThat(
            registry
                .counter(MetricNames.SESSION_TYPE_REFUSED, MetricNames.TAG_MODE, "report")
                .count())
        .isEqualTo(2.0);
  }

  @ParameterizedTest
  @CsvSource({
    "enforce,ENFORCE",
    "ENFORCE,ENFORCE",
    " report ,REPORT",
    "off,OFF",
    "enforced,REPORT",
    "'',REPORT"
  })
  void theModeParsesLenientlyAndFallsBackToReport(String raw, SessionTypeAllowList.Mode expected) {
    assertThat(SessionTypeAllowList.Mode.parse(raw)).isEqualTo(expected);
  }

  @Test
  void aMissingModeFallsBackToReport() {
    assertThat(SessionTypeAllowList.Mode.parse(null)).isEqualTo(SessionTypeAllowList.Mode.REPORT);
  }

  /**
   * Builds a session serializer in the given mode, with refusals reported nowhere.
   *
   * @param mode the allow-list mode.
   * @return the serializer.
   */
  private RedisSerializer<Object> serializer(SessionTypeAllowList.Mode mode) {
    JsonMapper mapper =
        RedisSessionConfig.buildSessionJsonMapper(
            loader,
            SessionTypeAllowList.validatorBuilder(mode, SessionTypeAllowList.RefusalListener.NONE));
    return new GenericJacksonJsonRedisSerializer(mapper);
  }

  /**
   * Reads a value, answering {@code null} where it cannot be read.
   *
   * @param serializer the serializer to read with.
   * @param bytes the written value.
   * @return the value read, or {@code null} when the read failed.
   */
  private static Object readOrNull(RedisSerializer<Object> serializer, byte[] bytes) {
    try {
      return serializer.deserialize(bytes);
    } catch (SerializationException ex) {
      return null;
    }
  }

  /**
   * Wraps a registry in the lazy provider the production wiring hands over.
   *
   * @param registry the registry.
   * @return a provider over it.
   */
  private static ObjectProvider<MeterRegistry> provider(MeterRegistry registry) {
    DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    beanFactory.registerSingleton("meterRegistry", registry);
    return beanFactory.getBeanProvider(MeterRegistry.class);
  }

  /**
   * An ID token exactly as a real login produces it: signed, decoded by Nimbus, and run through
   * Spring's default OIDC claim-type conversion — so its claims carry the runtime types production
   * stores ({@code URL}, {@code Instant}, {@code Long}, Nimbus's {@code LinkedTreeMap}), not the
   * ones a hand-built map would.
   *
   * @param now the issue time.
   * @return the decoded token.
   */
  private static OidcIdToken decodedIdToken(Instant now) {
    try {
      RSAKey key = new RSAKeyGenerator(2048).keyID("test").generate();
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .issuer("https://keycloak.example.test/auth/realms/iri")
              .subject("member-subject")
              .audience("basetool-frontend")
              .issueTime(Date.from(now))
              .expirationTime(Date.from(now.plusSeconds(300)))
              .claim("auth_time", now.getEpochSecond())
              .claim("azp", "basetool-frontend")
              .claim("sid", "session-id")
              .claim("email_verified", true)
              .claim("preferred_username", "member")
              .claim("realm_access", Map.of("roles", List.of("MEMBER")))
              .claim("custom_numeric", 5L)
              .build();
      SignedJWT jwt =
          new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims);
      jwt.sign(new RSASSASigner(key));
      NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
      decoder.setJwtValidator(token -> OAuth2TokenValidatorResult.success());
      decoder.setClaimSetConverter(
          new MappedJwtClaimSetConverter(
              OidcIdTokenDecoderFactory.createDefaultClaimTypeConverters()));
      Jwt decoded = decoder.decode(jwt.serialize());
      return new OidcIdToken(
          decoded.getTokenValue(),
          decoded.getIssuedAt(),
          decoded.getExpiresAt(),
          decoded.getClaims());
    } catch (JOSEException ex) {
      throw new IllegalStateException(ex);
    }
  }

  /**
   * One value of each kind a production session holds, keyed by a readable name.
   *
   * @return the sample, in insertion order.
   */
  private static Map<String, Object> realisticSession() {
    Instant now = Instant.parse("2026-09-23T10:00:00Z");
    OidcIdToken idToken = decodedIdToken(now);
    OidcUserInfo userInfo =
        new OidcUserInfo(Map.of("sub", "member-subject", "email_verified", true));
    Set<GrantedAuthority> authorities = new HashSet<>();
    authorities.add(new OidcUserAuthority(idToken, userInfo));
    authorities.add(new SimpleGrantedAuthority("ROLE_MEMBER"));
    authorities.add(new SimpleGrantedAuthority("SCOPE_openid"));
    DefaultOidcUser user = new DefaultOidcUser(authorities, idToken, userInfo, "sub");
    OAuth2AuthenticationToken authentication =
        new OAuth2AuthenticationToken(user, authorities, "keycloak");

    ClientRegistration registration =
        ClientRegistration.withRegistrationId("keycloak")
            .clientId("basetool-frontend")
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .scope("openid", "profile", "email", "roles")
            .authorizationUri("https://keycloak.example.test/realms/iri/auth")
            .tokenUri("https://keycloak.example.test/realms/iri/token")
            .jwkSetUri("https://keycloak.example.test/realms/iri/certs")
            .userInfoUri("https://keycloak.example.test/realms/iri/userinfo")
            .userNameAttributeName("preferred_username")
            .issuerUri("https://keycloak.example.test/realms/iri")
            .build();
    OAuth2AccessToken accessToken =
        new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "access-token-value",
            now,
            now.plusSeconds(300),
            Set.of("openid", "profile"));
    OAuth2RefreshToken refreshToken =
        new OAuth2RefreshToken("refresh-token-value", now, now.plusSeconds(1800));
    Map<String, OAuth2AuthorizedClient> authorizedClients = new HashMap<>();
    authorizedClients.put(
        "keycloak",
        new OAuth2AuthorizedClient(registration, "member-subject", accessToken, refreshToken));

    OAuth2AuthorizationRequest authorizationRequest =
        OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri("https://keycloak.example.test/realms/iri/auth")
            .clientId("basetool-frontend")
            .redirectUri("https://frontend.example.test/login/oauth2/code/keycloak")
            .scopes(Set.of("openid", "profile"))
            .state("state-value")
            .additionalParameters(Map.of("nonce", "nonce-value"))
            .attributes(
                attributes -> attributes.put(OAuth2ParameterNames.REGISTRATION_ID, "keycloak"))
            .build();

    MockHttpServletRequest original = new MockHttpServletRequest("GET", "/missions");
    original.setServerName("frontend.example.test");
    original.setQueryString("page=2");
    original.addParameter("page", "2");

    InventoryForm form = new InventoryForm();
    form.setAmount(-1.0);
    form.setLocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    BeanPropertyBindingResult errors = new BeanPropertyBindingResult(form, "inventoryForm");
    errors.rejectValue("amount", "Min", "must be greater than or equal to 0");
    ImportIssueDto issue =
        new ImportIssueDto(
            "goods[1].inputMaterial",
            "UNKNOWN",
            ImportIssueCode.UNMATCHED_MATERIAL,
            ImportIssueSeverity.WARNING,
            null,
            List.of(
                new ImportSuggestionDto(
                    UUID.fromString("00000000-0000-0000-0000-000000000002"), "Material", 0.84)));
    Map<String, List<ImportIssueDto>> rowIssues = new LinkedHashMap<>();
    rowIssues.put("1", List.of(issue));
    FlashMap flash = new FlashMap();
    flash.setTargetRequestPath("/inventory");
    flash.addTargetRequestParam("tab", "stock");
    flash.put("inventoryForm", form);
    flash.put("importRowIssues", rowIssues);
    flash.put("errorToast", "error.inventory.save");
    flash.put("showItemModal", true);
    flash.put("deletedCount", 3L);
    flash.startExpirationPeriod(180);
    FlashMap flashWithErrors = new FlashMap();
    flashWithErrors.setTargetRequestPath("/inventory");
    flashWithErrors.put("inventoryForm", form);
    flashWithErrors.put(BindingResult.MODEL_KEY_PREFIX + "inventoryForm", errors);
    flashWithErrors.startExpirationPeriod(180);

    Map<String, Object> session = new LinkedHashMap<>();
    session.put("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(authentication));
    session.put("authorizedClients", authorizedClients);
    session.put("authorizationRequest", authorizationRequest);
    session.put("csrfToken", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "csrf-token-value"));
    session.put("savedRequest", new DefaultSavedRequest(original));
    session.put("flashMaps", new ArrayList<>(List.of(flash)));
    session.put("flashMapsWithErrors", new ArrayList<>(List.of(flashWithErrors)));
    session.put("rolesSyncedAt", now.toEpochMilli());
    Map<String, Object> createdEvent = new HashMap<>();
    createdEvent.put("creationTime", now.toEpochMilli());
    createdEvent.put("lastAccessedTime", now.toEpochMilli());
    createdEvent.put("maxInactiveInterval", 1800);
    session.put("sessionCreatedEventPayload", createdEvent);
    session.put("syncedAuthorities", new ArrayList<>(List.of("ROLE_MEMBER", "ROLE_LOGISTICIAN")));
    session.put("approvalState", "APPROVED");
    session.put(
        "activeOrgUnit", UUID.fromString("00000000-0000-0000-0000-000000000003").toString());
    session.put("welcomeMessageShown", true);
    session.put("wsBindingListener", new WsHttpSessionBindingListener("a-session-id"));
    return session;
  }

  /**
   * The shape of a Jackson deserialization gadget without its payload: a public non-final bean
   * whose setter has a side effect. Outside every allow-list entry by package, so {@code ENFORCE}
   * must refuse it before Jackson ever calls {@link #setCommand(String)}.
   */
  @Getter
  @NoArgsConstructor
  public static class GadgetLikeBean {

    /** What a real gadget would execute or resolve; here only recorded. */
    private String command = "probe";

    /**
     * Records that the setter ran, which for a real gadget is the moment the damage is done.
     *
     * @param command the value Jackson read from the payload.
     */
    public void setCommand(String command) {
      GADGET_SETTER_RAN.set(true);
      this.command = command;
    }
  }
}
