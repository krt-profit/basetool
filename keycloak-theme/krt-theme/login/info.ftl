<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>
    <#if section = "header">
        <#if messageHeader??>
            ${kcSanitize(msg("${messageHeader}"))?no_esc}
        <#else>
            ${message.summary}
        </#if>
    <#elseif section = "form">
    <div id="kc-info-message">
        <p class="instruction">${message.summary}<#if requiredActions??><#list requiredActions>: <b><#items as reqActionItem>${kcSanitize(msg("requiredAction.${reqActionItem}"))?no_esc}<#sep>, </#items></b></#list><#else></#if></p>
        <#if !(skipLink??) && pageRedirectUri?has_content>
            <p><a href="${pageRedirectUri}" class="krt-link">${msg("backToApplication")}</a></p>
        <#elseif !(skipLink??) && actionUri?has_content>
            <p><a href="${actionUri}" class="krt-link">${msg("proceedWithAction")}</a></p>
        <#elseif !(skipLink??) && (client.baseUrl)?has_content>
            <p><a href="${client.baseUrl}" class="krt-link">${msg("backToApplication")}</a></p>
        <#else>
            <div class="krt-info-actions">
                <a id="krt-home-link" href="${properties.krtHomeUrl!'/'}" class="krt-button">${msg("krtBackToBasetool")}</a>
                <button id="krt-close-tab" type="button" class="krt-button-secondary" hidden>${msg("krtCloseTab")}</button>
                <p id="krt-close-tab-hint" class="krt-info-hint" role="status" hidden>${msg("krtCloseTabHint")}</p>
            </div>
            <script src="${url.resourcesPath}/js/krt-close-tab.js" defer></script>
        </#if>
    </div>
    </#if>
</@layout.registrationLayout>
