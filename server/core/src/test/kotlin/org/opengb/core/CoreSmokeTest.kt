package org.opengb.core

import de.infix.testBalloon.framework.core.testSuite

val CoreSmokeTest by testSuite {
    test("test infrastructure is wired") {
        assert(1 + 1 == 2)
    }
}
