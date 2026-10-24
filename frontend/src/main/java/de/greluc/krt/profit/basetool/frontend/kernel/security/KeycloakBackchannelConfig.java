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

package de.greluc.krt.profit.basetool.frontend.kernel.security;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.AppHttpProperties;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.AuthenticationMethod;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.converter.ClaimTypeConverter;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

/**
 * Moves the frontend's server-to-server Keycloak calls onto the internal connector when {@code
 * app.security.keycloak-backchannel.uri} is set (REQ-SEC-087).
 *
 * <p>The {@code keycloak} registration is built without discovery: issuer, authorization and
 * end-session endpoints name the public issuer, which the browser and the {@code iss} check need;
 * token, JWKS and userinfo name the internal realm base. The ID-token decoder and the userinfo call
 * use the {@value KeycloakBackchannelTls#KEYCLOAK_TRUST_BUNDLE} trust. Blank leaves Spring Boot's
 * discovered registration and Spring Security's defaults in place.
 *
 * <p>Binds {@link OAuth2ClientProperties} itself: Spring Boot binds them only together with its own
 * repository, which this one replaces.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("!'${app.security.keycloak-backchannel.uri:}'.isBlank()")
@EnableConfigurationProperties(OAuth2ClientProperties.class)
public class KeycloakBackchannelConfig {

  /** Keycloak's OIDC endpoint path below a realm base. */
  static final String OIDC_PATH = "/protocol/openid-connect";

  /**
   * The {@code keycloak} registration with its back-channel endpoints on the internal connector.
   *
   * @param clientProperties the bound {@code spring.security.oauth2.client} properties
   * @param backchannel the internal realm base
   * @return a repository holding the one registration
   */
  @Bean
  public @NotNull ClientRegistrationRepository clientRegistrationRepository(
      @NotNull OAuth2ClientProperties clientProperties,
      @NotNull KeycloakBackchannelProperties backchannel) {
    ClientRegistration registration =
        keycloakRegistration(clientProperties, Objects.requireNonNull(backchannel.realmBase()));
    log.info(
        "Keycloak back-channel is internal: token, JWKS and userinfo at {}; issuer stays {}"
            + " (REQ-SEC-087).",
        backchannel.realmBase(),
        registration.getProviderDetails().getIssuerUri());
    return new InMemoryClientRegistrationRepository(registration);
  }

  /**
   * ID-token decoder factory whose JWKS fetch runs over the pinned internal trust.
   *
   * @param sslBundles the registered SSL bundles
   * @param httpProperties the frontend's connect and response timeouts
   * @return the decoder factory picked up by {@code oauth2Login}
   */
  @Bean
  public @NotNull JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory(
      @NotNull SslBundles sslBundles, @NotNull AppHttpProperties httpProperties) {
    return new BackchannelIdTokenDecoderFactory(
        new RestTemplate(requestFactory(sslBundles, httpProperties)));
  }

  /**
   * OIDC user service whose userinfo call runs over the pinned internal trust.
   *
   * @param sslBundles the registered SSL bundles
   * @param httpProperties the frontend's connect and response timeouts
   * @return the user service picked up by {@code oauth2Login}
   */
  @Bean
  public @NotNull OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService(
      @NotNull SslBundles sslBundles, @NotNull AppHttpProperties httpProperties) {
    RestTemplate restTemplate = new RestTemplate(requestFactory(sslBundles, httpProperties));
    restTemplate.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
    DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
    delegate.setRestOperations(restTemplate);
    OidcUserService service = new OidcUserService();
    service.setOauth2UserService(delegate);
    return service;
  }

  /**
   * Builds the {@code keycloak} registration from the bound properties without contacting Keycloak.
   *
   * @param clientProperties the bound client properties carrying the registration and its provider
   * @param realmBase the internal realm base, without a trailing slash
   * @return the registration
   * @throws IllegalStateException if the registration, its provider or the issuer is not configured
   */
  static @NotNull ClientRegistration keycloakRegistration(
      @NotNull OAuth2ClientProperties clientProperties, @NotNull String realmBase) {
    String id = FrontendClientAuthenticationConfig.REGISTRATION_ID;
    OAuth2ClientProperties.Registration registration = clientProperties.getRegistration().get(id);
    OAuth2ClientProperties.Provider provider = clientProperties.getProvider().get(id);
    if (registration == null || provider == null || provider.getIssuerUri() == null) {
      throw new IllegalStateException(
          "app.security.keycloak-backchannel.uri needs the '"
              + id
              + "' registration and its provider issuer-uri");
    }
    String issuer =
        KeycloakBackchannelProperties.stripTrailingSlash(provider.getIssuerUri().trim());
    String publicOidc = issuer + OIDC_PATH;
    String internalOidc = realmBase + OIDC_PATH;
    ClientRegistration.Builder builder =
        ClientRegistration.withRegistrationId(id)
            .clientId(registration.getClientId())
            .clientSecret(registration.getClientSecret())
            .clientName(registration.getClientName())
            .redirectUri(registration.getRedirectUri())
            .scope(registration.getScope())
            .issuerUri(issuer)
            .authorizationUri(publicOidc + "/auth")
            .tokenUri(internalOidc + "/token")
            .jwkSetUri(internalOidc + "/certs")
            .userInfoUri(internalOidc + "/userinfo")
            .userInfoAuthenticationMethod(AuthenticationMethod.HEADER)
            .userNameAttributeName(provider.getUserNameAttribute())
            .providerConfigurationMetadata(
                Map.of("issuer", issuer, "end_session_endpoint", publicOidc + "/logout"));
    if (registration.getClientAuthenticationMethod() != null) {
      builder.clientAuthenticationMethod(
          new ClientAuthenticationMethod(registration.getClientAuthenticationMethod()));
    }
    if (registration.getAuthorizationGrantType() != null) {
      builder.authorizationGrantType(
          new AuthorizationGrantType(registration.getAuthorizationGrantType()));
    }
    return builder.build();
  }

  private static ClientHttpRequestFactory requestFactory(
      SslBundles sslBundles, AppHttpProperties httpProperties) {
    return KeycloakBackchannelTls.requestFactory(
        KeycloakBackchannelTls.sslContext(sslBundles),
        httpProperties.connectTimeout(),
        httpProperties.responseTimeout());
  }

  /**
   * ID-token decoder factory equivalent to {@link OidcIdTokenDecoderFactory}'s defaults (RS256,
   * timestamp and OIDC ID-token validation, the standard claim conversion), with the JWKS fetched
   * through the given client and one decoder cached per registration.
   */
  static final class BackchannelIdTokenDecoderFactory
      implements JwtDecoderFactory<ClientRegistration> {

    private final RestTemplate restOperations;
    private final Map<String, JwtDecoder> decoders = new ConcurrentHashMap<>();

    /**
     * Creates the factory.
     *
     * @param restOperations the client the JWKS is fetched with
     */
    BackchannelIdTokenDecoderFactory(@NotNull RestTemplate restOperations) {
      this.restOperations = restOperations;
    }

    /**
     * Returns the registration's cached decoder, building it on first use.
     *
     * @param registration the registration whose ID tokens are decoded
     * @return the decoder
     */
    @Override
    public @NotNull JwtDecoder createDecoder(@NotNull ClientRegistration registration) {
      return decoders.computeIfAbsent(registration.getRegistrationId(), _ -> build(registration));
    }

    private JwtDecoder build(ClientRegistration registration) {
      NimbusJwtDecoder decoder =
          NimbusJwtDecoder.withJwkSetUri(registration.getProviderDetails().getJwkSetUri())
              .restOperations(restOperations)
              .build();
      decoder.setJwtValidator(
          new DelegatingOAuth2TokenValidator<>(
              new JwtTimestampValidator(), new OidcIdTokenValidator(registration)));
      decoder.setClaimSetConverter(
          new ClaimTypeConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverters()));
      return decoder;
    }
  }
}
