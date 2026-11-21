package com.finsight.shared.security;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Format rules for security settings, used by {@link ProductionSecurityValidator} and
 * {@link SecurityConfig}. Pure functions; no I/O, no Spring.
 */
final class SecuritySettingRules {

    /** One escaped IPv4 literal, e.g. {@code 10\.0\.0\.10} (M-2). */
    private static final Pattern ESCAPED_IPV4 =
            Pattern.compile("^(\\d{1,3})\\\\\\.(\\d{1,3})\\\\\\.(\\d{1,3})\\\\\\.(\\d{1,3})$");
    /** One IPv6 literal in the full 8-group form Tomcat reports, e.g. {@code fd00:0:0:0:0:0:0:10}. */
    private static final Pattern FULL_IPV6 = Pattern.compile("^[0-9a-fA-F]{1,4}(:[0-9a-fA-F]{1,4}){7}$");
    /** Host name with an optional port; no scheme, path, wildcard or spaces (M-1). */
    private static final Pattern PUBLIC_HOST = Pattern.compile(
            "^(?=.{1,253}(:\\d{1,5})?$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*(:\\d{1,5})?$");

    private SecuritySettingRules() {}

    /** {@code https://host[:port]} with nothing else: no wildcard, user-info, path, query or fragment. */
    static boolean isExactHttpsOrigin(String origin) {
        if (origin == null || origin.isEmpty() || origin.contains("*")) {
            return false;
        }
        try {
            var uri = new URI(origin);
            return "https".equals(uri.getScheme())
                    && uri.getHost() != null && !uri.getHost().isBlank()
                    && uri.getRawUserInfo() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * M-2: exact proxy addresses only, separated by {@code |}: escaped IPv4 literals
     * ({@code 10\.0\.0\.10}) or full-form IPv6 literals. No ranges or wildcards.
     */
    static boolean isExactAddressList(String regex) {
        if (regex == null || regex.isBlank()) {
            return false;
        }
        for (var part : regex.trim().split("\\|", -1)) {
            var ipv4 = ESCAPED_IPV4.matcher(part);
            if (ipv4.matches()) {
                for (int i = 1; i <= 4; i++) {
                    if (Integer.parseInt(ipv4.group(i)) > 255) {
                        return false;
                    }
                }
            } else if (!FULL_IPV6.matcher(part).matches()) {
                return false;
            }
        }
        return true;
    }

    /** M-1: a public host name ({@code host} or {@code host:port}) for the HTTPS redirect target. */
    static boolean isValidPublicHost(String host) {
        return host != null && PUBLIC_HOST.matcher(host.trim().toLowerCase(Locale.ROOT)).matches();
    }
}
