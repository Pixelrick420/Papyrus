package io.github.pixelrick420.papyrus.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pixelrick420.papyrus.viewer.Type
import io.github.pixelrick420.papyrus.viewer.pptx
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenPresentationTest : E2eTest() {

    private val app = AppDriver()

    private val load = AppDriver.LOAD_TIMEOUT_MS

    @Test
    fun aPresentationOpensAndTheFirstSlideReadsItsTitleTextAndAltText() {
        val deck = pptx {
            slide {
                textPlaceholder(Type.TITLE, idx = 0, x = 838_200, y = 365_125, w = 10_515_600, h = 1_325_563) {
                    para("Quarterly results")
                }
                textPlaceholder(Type.BODY, idx = 1, x = 838_200, y = 2_000_000, w = 10_515_600, h = 3_000_000) {
                    para("Revenue rose in every region.")
                }
            }
            slide { title("Next steps") }
        }
        app.openFromAnotherApp(Fixtures.bytes("results.pptx", deck.toBytes()))

        app.assertShown(app.descStartsWith("Slide 1 of 2. Quarterly results (heading)"), load)
        app.assertShown(app.descContains("Revenue rose in every region"), load)
    }
}
