package vela.core.sites

/** ISO 3166-1 alpha-2 与数字码。Cam4 的 blockedRegions 用数字码，比较前互转。只编进代码。 */
internal object ISO3166 {
    val alpha2ToNumeric: Map<String, Int> = mapOf(
        "AD" to 20, "AE" to 784, "AF" to 4, "AG" to 28, "AI" to 660, "AL" to 8, "AM" to 51, "AO" to 24,
        "AQ" to 10, "AR" to 32, "AS" to 16, "AT" to 40, "AU" to 36, "AW" to 533, "AX" to 248, "AZ" to 31,
        "BA" to 70, "BB" to 52, "BD" to 50, "BE" to 56, "BF" to 854, "BG" to 100, "BH" to 48, "BI" to 108,
        "BJ" to 204, "BL" to 652, "BM" to 60, "BN" to 96, "BO" to 68, "BQ" to 535, "BR" to 76, "BS" to 44,
        "BT" to 64, "BV" to 74, "BW" to 72, "BY" to 112, "BZ" to 84, "CA" to 124, "CC" to 166, "CD" to 180,
        "CF" to 140, "CG" to 178, "CH" to 756, "CI" to 384, "CK" to 184, "CL" to 152, "CM" to 120, "CN" to 156,
        "CO" to 170, "CR" to 188, "CU" to 192, "CV" to 132, "CW" to 531, "CX" to 162, "CY" to 196, "CZ" to 203,
        "DE" to 276, "DJ" to 262, "DK" to 208, "DM" to 212, "DO" to 214, "DZ" to 12, "EC" to 218, "EE" to 233,
        "EG" to 818, "EH" to 732, "ER" to 232, "ES" to 724, "ET" to 231, "FI" to 246, "FJ" to 242, "FK" to 238,
        "FM" to 583, "FO" to 234, "FR" to 250, "GA" to 266, "GB" to 826, "GD" to 308, "GE" to 268, "GF" to 254,
        "GG" to 831, "GH" to 288, "GI" to 292, "GL" to 304, "GM" to 270, "GN" to 324, "GP" to 312, "GQ" to 226,
        "GR" to 300, "GS" to 239, "GT" to 320, "GU" to 316, "GW" to 624, "GY" to 328, "HK" to 344, "HM" to 334,
        "HN" to 340, "HR" to 191, "HT" to 332, "HU" to 348, "ID" to 360, "IE" to 372, "IL" to 376, "IM" to 833,
        "IN" to 356, "IO" to 86, "IQ" to 368, "IR" to 364, "IS" to 352, "IT" to 380, "JE" to 832, "JM" to 388,
        "JO" to 400, "JP" to 392, "KE" to 404, "KG" to 417, "KH" to 116, "KI" to 296, "KM" to 174, "KN" to 659,
        "KP" to 408, "KR" to 410, "KW" to 414, "KY" to 136, "KZ" to 398, "LA" to 418, "LB" to 422, "LC" to 662,
        "LI" to 438, "LK" to 144, "LR" to 430, "LS" to 426, "LT" to 440, "LU" to 442, "LV" to 428, "LY" to 434,
        "MA" to 504, "MC" to 492, "MD" to 498, "ME" to 499, "MF" to 663, "MG" to 450, "MH" to 584, "MK" to 807,
        "ML" to 466, "MM" to 104, "MN" to 496, "MO" to 446, "MP" to 580, "MQ" to 474, "MR" to 478, "MS" to 500,
        "MT" to 470, "MU" to 480, "MV" to 462, "MW" to 454, "MX" to 484, "MY" to 458, "MZ" to 508, "NA" to 516,
        "NC" to 540, "NE" to 562, "NF" to 574, "NG" to 566, "NI" to 558, "NL" to 528, "NO" to 578, "NP" to 524,
        "NR" to 520, "NU" to 570, "NZ" to 554, "OM" to 512, "PA" to 591, "PE" to 604, "PF" to 258, "PG" to 598,
        "PH" to 608, "PK" to 586, "PL" to 616, "PM" to 666, "PN" to 612, "PR" to 630, "PS" to 275, "PT" to 620,
        "PW" to 585, "PY" to 600, "QA" to 634, "RE" to 638, "RO" to 642, "RS" to 688, "RU" to 643, "RW" to 646,
        "SA" to 682, "SB" to 90, "SC" to 690, "SD" to 729, "SE" to 752, "SG" to 702, "SH" to 654, "SI" to 705,
        "SJ" to 744, "SK" to 703, "SL" to 694, "SM" to 674, "SN" to 686, "SO" to 706, "SR" to 740, "SS" to 728,
        "ST" to 678, "SV" to 222, "SX" to 534, "SY" to 760, "SZ" to 748, "TC" to 796, "TD" to 148, "TF" to 260,
        "TG" to 768, "TH" to 764, "TJ" to 762, "TK" to 772, "TL" to 626, "TM" to 795, "TN" to 788, "TO" to 776,
        "TR" to 792, "TT" to 780, "TV" to 798, "TW" to 158, "TZ" to 834, "UA" to 804, "UG" to 800, "UM" to 581,
        "US" to 840, "UY" to 858, "UZ" to 860, "VA" to 336, "VC" to 670, "VE" to 862, "VG" to 92, "VI" to 850,
        "VN" to 704, "VU" to 548, "WF" to 876, "WS" to 882, "YE" to 887, "YT" to 175, "ZA" to 710, "ZM" to 894,
        "ZW" to 716,
    )

    val numericToAlpha2: Map<Int, String> = alpha2ToNumeric.entries.associate { (alpha, numeric) -> numeric to alpha }

    /** 数字码（`"4"` / `"004"` / `"643"`）→ alpha-2。认不出返回 null。 */
    fun alpha2(numeric: String): String? {
        val digits = numeric.filter { it.isDigit() }
        if (digits.isEmpty() || digits.length != numeric.length) return null
        val value = digits.toIntOrNull() ?: return null
        return numericToAlpha2[value]
    }
}
