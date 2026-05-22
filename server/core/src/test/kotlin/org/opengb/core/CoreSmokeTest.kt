package org.opengb.core

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

val CoreSmokeTest by testSuite {
    test("test infrastructure is wired") {
        assertEquals(2, 1 + 1)
    }
}
