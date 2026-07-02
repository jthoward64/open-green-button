package org.opengb.utility

/**
 * Parses a human-friendly history-window spec into seconds.
 *
 * Grammar: `<positive integer><unit>`, optional whitespace between the two, where unit is one of:
 *   - `d` days   (1 day   = 86 400 s)
 *   - `w` weeks  (1 week  = 7 days)
 *   - `m` months (1 month = 30 days — a fixed, calendar-approximate value)
 *   - `y` years  (1 year  = 365 days)
 *
 * Examples: `2y` → 63 072 000, `6m` → 15 552 000, `90d` → 7 776 000.
 *
 * Months and years are deliberately fixed approximations (30 / 365 days). This value only sizes
 * how far back we ask a utility to backfill, where a few days either way is immaterial — keeping
 * the units fixed makes the config trivial to reason about and free of calendar edge cases. (For
 * reference, the legacy `HistoryLength=94608000` scope value was likewise computed as 3 × 365d.)
 */
fun parseHistoryWindowSeconds(spec: String): Long {
  val match =
    HISTORY_WINDOW_REGEX.matchEntire(spec.trim())
      ?: throw IllegalArgumentException(
        "Invalid history window '$spec'; expected <positive integer><d|w|m|y>, e.g. '2y', '6m', '90d'",
      )
  val amount = match.groupValues[1].toLong()
  require(amount > 0) { "History window must be positive: '$spec'" }
  val unitSeconds =
    when (match.groupValues[2].lowercase()) {
      "d" -> DAY_SECONDS
      "w" -> 7 * DAY_SECONDS
      "m" -> 30 * DAY_SECONDS
      "y" -> 365 * DAY_SECONDS
      else -> error("unreachable — regex constrains the unit")
    }
  return amount * unitSeconds
}

private const val DAY_SECONDS = 86_400L
private val HISTORY_WINDOW_REGEX = Regex("""(\d+)\s*([dwmyDWMY])""")
