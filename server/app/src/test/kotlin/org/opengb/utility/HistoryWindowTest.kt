package org.opengb.utility

import de.infix.testBalloon.framework.core.testSuite

val HistoryWindowTest by testSuite {
  test("parses each unit to seconds") {
    assert(parseHistoryWindowSeconds("90d") == 90L * 86_400)
    assert(parseHistoryWindowSeconds("2w") == 2L * 7 * 86_400)
    assert(parseHistoryWindowSeconds("6m") == 6L * 30 * 86_400)
    assert(parseHistoryWindowSeconds("2y") == 2L * 365 * 86_400)
  }

  test("is case-insensitive and tolerates surrounding whitespace") {
    assert(parseHistoryWindowSeconds("2Y") == parseHistoryWindowSeconds("2y"))
    assert(parseHistoryWindowSeconds("  6 M ".trim()) == parseHistoryWindowSeconds("6m"))
  }

  test("rejects malformed, zero, and unknown-unit specs") {
    for (bad in listOf("", "2", "y", "2years", "0y", "-1d", "2.5y", "1h")) {
      try {
        parseHistoryWindowSeconds(bad)
        assert(false) { "expected '$bad' to be rejected" }
      } catch (_: IllegalArgumentException) {
        // expected
      }
    }
  }

  test("registry validates the configured window at construction") {
    try {
      UtilityRegistry(listOf(sampleProfile(initialHistory = "nonsense")))
      assert(false) { "expected an invalid initialHistory to fail registry construction" }
    } catch (e: IllegalArgumentException) {
      assert(e.message!!.contains("burlington_hydro")) { "error should name the offending utility" }
    }
  }

  test("a valid window exposes seconds via the profile") {
    val registry = UtilityRegistry(listOf(sampleProfile(initialHistory = "2y")))
    assert(registry.require("burlington_hydro").initialHistorySeconds == 2L * 365 * 86_400)
  }
}

private fun sampleProfile(initialHistory: String): UtilityProfile =
  UtilityProfile(
    id = "burlington_hydro",
    displayName = "Burlington Hydro",
    authorizeUrl = "https://example/oauth/authorize",
    tokenUrl = "https://example/oauth/token",
    clientId = "opengreenbutton",
    clientSecret = com.sksamuel.hoplite.Masked("secret"),
    defaultScope = "FB=1_3",
    initialHistory = initialHistory,
  )
