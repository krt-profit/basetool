<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true displayInfo=realm.password && realm.registrationAllowed && !registrationDisabled; section>
    <#if section = "header">
        ${msg("loginAccountTitle")}
    <#elseif section = "form">
        <div class="login-container">
            <img src="${url.resourcesPath}/img/basetool-logo.svg" alt="" class="login-logo">
            <h1>PROFIT BASETOOL</h1>
            <form id="kc-form-login" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post">
                <div class="form-group">
                    <label for="username" class="krt-label"><#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if></label>
                    <input tabindex="1" id="username" class="krt-input" name="username" value="${(login.username!'')}"  type="text" autofocus autocomplete="off" />
                </div>

                <div class="form-group">
                    <label for="password" class="krt-label">${msg("password")}</label>
                    <input tabindex="2" id="password" class="krt-input" name="password" type="password" autocomplete="off" />
                </div>

                <#if realm.rememberMe>
                    <div class="form-group">
                        <label class="krt-checkbox-label">
                            <input tabindex="3" id="rememberMe" name="rememberMe" type="checkbox" class="krt-checkbox" checked />
                            ${msg("rememberMe")}
                        </label>
                    </div>
                </#if>

                <#if realm.resetPasswordAllowed>
                    <div class="krt-form-footer">
                        <a tabindex="5" href="${url.loginResetCredentialsUrl!''}" class="krt-link">${msg("doForgotPassword")}</a>
                    </div>
                </#if>

                <div class="form-group login-action">
                    <input tabindex="4" class="krt-button" name="login" id="kc-login" type="submit" value="${msg("doLogIn")}"/>
                </div>
            </form>

            <#-- Social / IdP login buttons (e.g. Discord). Rendered on the Keycloak login page itself
                 so the entry point is reachable from EVERY login surface — the credential form, the
                 device-grant verification page used by the extractor, and direct logins — not only the
                 app sidebar shortcut. Keycloak populates `social.providers` only with IdPs that are
                 NOT hidden on the login page, so a `discord` IdP with "Hide on login page" = OFF shows
                 up here automatically. -->
            <#if realm.password && social.providers??>
                <div id="kc-social-providers" class="krt-social">
                    <div class="krt-social-divider"><span>${msg("identity-provider-login-label")}</span></div>
                    <#list social.providers as p>
                        <a id="social-${p.alias}" class="krt-button-social" href="${p.loginUrl}">
                            <#if p.alias == "discord">
                                <svg class="krt-social-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M20.317 4.3698a19.7913 19.7913 0 0 0-4.8851-1.5152.0741.0741 0 0 0-.0785.0371c-.211.3753-.4447.8648-.6083 1.2495-1.8447-.2762-3.68-.2762-5.4868 0-.1636-.3933-.4058-.8742-.6177-1.2495a.077.077 0 0 0-.0785-.037 19.7363 19.7363 0 0 0-4.8852 1.515.0699.0699 0 0 0-.0321.0277C.5334 9.0458-.319 13.5799.0992 18.0578a.0824.0824 0 0 0 .0312.0561c2.0528 1.5076 4.0413 2.4228 5.9929 3.0294a.0777.0777 0 0 0 .0842-.0276c.4616-.6304.8731-1.2952 1.226-1.9942a.076.076 0 0 0-.0416-.1057c-.6528-.2476-1.2743-.5495-1.8722-.8923a.077.077 0 0 1-.0076-.1277c.1258-.0943.2517-.1923.3718-.2914a.0743.0743 0 0 1 .0776-.0105c3.9278 1.7933 8.18 1.7933 12.0614 0a.0739.0739 0 0 1 .0785.0095c.1202.099.246.1981.3728.2924a.077.077 0 0 1-.0066.1276 12.2986 12.2986 0 0 1-1.873.8914.0766.0766 0 0 0-.0407.1067c.3604.698.7719 1.3628 1.225 1.9932a.076.076 0 0 0 .0842.0286c1.961-.6067 3.9495-1.5219 6.0023-3.0294a.077.077 0 0 0 .0313-.0552c.5004-5.177-.8382-9.6739-3.5485-13.6604a.061.061 0 0 0-.0312-.0286zM8.02 15.3312c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9555-2.4189 2.157-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.9555 2.4189-2.1569 2.4189zm7.9748 0c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9554-2.4189 2.1569-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.946 2.4189-2.1568 2.4189z"/></svg>
                            </#if>
                            <span>${p.displayName!}</span>
                        </a>
                    </#list>
                </div>
            </#if>
        </div>
    </#if>
</@layout.registrationLayout>
