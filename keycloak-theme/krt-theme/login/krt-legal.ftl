<#macro links>
    <#local krtHome = properties.krtHomeUrl!'/'>
    <nav class="krt-legal-links" aria-label="${msg("krtLegalLinksLabel")}" data-testid="krt-legal-links">
        <a href="${krtHome}impressum">${msg("krtLegalImpressum")}</a>
        <a href="${krtHome}privacy">${msg("krtLegalPrivacy")}</a>
    </nav>
</#macro>
