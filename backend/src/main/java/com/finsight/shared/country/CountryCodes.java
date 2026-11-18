package com.finsight.shared.country;

import java.util.Set;

/**
 * Static validator for ISO 3166-1 alpha-2 country codes.
 *
 * <p>The allowlist is the complete set of 249 officially assigned codes as of
 * ISO 3166 Maintenance Agency publication dated 2024-11-01
 * (source: <a href="https://www.iso.org/obp/ui/#search/code/">ISO OBP</a>).
 *
 * <p>Usage rule: validate {@code countryCode} fields at the service layer boundary
 * before persisting or running fraud rules. An invalid or blank code must be
 * rejected with HTTP 400 before any fraud evaluation occurs (AC-FRAUD-5).
 *
 * <p>No external network calls are made — the list is embedded at compile time.
 * Update by regenerating this file from the ISO dataset when new codes are assigned.
 */
public final class CountryCodes {

    private CountryCodes() {}

    /**
     * Returns {@code true} if {@code code} is a valid, officially assigned
     * ISO 3166-1 alpha-2 country code (case-sensitive, uppercase two letters).
     *
     * @param code the two-character code to validate; may be {@code null}
     * @return {@code true} if the code is in the allowlist; {@code false} otherwise
     */
    public static boolean isValid(String code) {
        if (code == null || code.length() != 2) return false;
        return CODES.contains(code);
    }

    /** Complete ISO 3166-1 alpha-2 allowlist (249 codes, uppercase). */
    private static final Set<String> CODES = Set.of(
            "AD", "AE", "AF", "AG", "AI", "AL", "AM", "AO", "AQ", "AR",
            "AS", "AT", "AU", "AW", "AX", "AZ", "BA", "BB", "BD", "BE",
            "BF", "BG", "BH", "BI", "BJ", "BL", "BM", "BN", "BO", "BQ",
            "BR", "BS", "BT", "BV", "BW", "BY", "BZ", "CA", "CC", "CD",
            "CF", "CG", "CH", "CI", "CK", "CL", "CM", "CN", "CO", "CR",
            "CU", "CV", "CW", "CX", "CY", "CZ", "DE", "DJ", "DK", "DM",
            "DO", "DZ", "EC", "EE", "EG", "EH", "ER", "ES", "ET", "FI",
            "FJ", "FK", "FM", "FO", "FR", "GA", "GB", "GD", "GE", "GF",
            "GG", "GH", "GI", "GL", "GM", "GN", "GP", "GQ", "GR", "GS",
            "GT", "GU", "GW", "GY", "HK", "HM", "HN", "HR", "HT", "HU",
            "ID", "IE", "IL", "IM", "IN", "IO", "IQ", "IR", "IS", "IT",
            "JE", "JM", "JO", "JP", "KE", "KG", "KH", "KI", "KM", "KN",
            "KP", "KR", "KW", "KY", "KZ", "LA", "LB", "LC", "LI", "LK",
            "LR", "LS", "LT", "LU", "LV", "LY", "MA", "MC", "MD", "ME",
            "MF", "MG", "MH", "MK", "ML", "MM", "MN", "MO", "MP", "MQ",
            "MR", "MS", "MT", "MU", "MV", "MW", "MX", "MY", "MZ", "NA",
            "NC", "NE", "NF", "NG", "NI", "NL", "NO", "NP", "NR", "NU",
            "NZ", "OM", "PA", "PE", "PF", "PG", "PH", "PK", "PL", "PM",
            "PN", "PR", "PS", "PT", "PW", "PY", "QA", "RE", "RO", "RS",
            "RU", "RW", "SA", "SB", "SC", "SD", "SE", "SG", "SH", "SI",
            "SJ", "SK", "SL", "SM", "SN", "SO", "SR", "SS", "ST", "SV",
            "SX", "SY", "SZ", "TC", "TD", "TF", "TG", "TH", "TJ", "TK",
            "TL", "TM", "TN", "TO", "TR", "TT", "TV", "TW", "TZ", "UA",
            "UG", "UM", "US", "UY", "UZ", "VA", "VC", "VE", "VG", "VI",
            "VN", "VU", "WF", "WS", "YE", "YT", "ZA", "ZM", "ZW"
    );
}
