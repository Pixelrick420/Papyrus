package io.github.pixelrick420.papyrus.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pixelrick420.papyrus.viewer.Type
import io.github.pixelrick420.papyrus.viewer.pptx
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PresentationHiddenSlidesTest : E2eTest() {

    private val app = AppDriver()

    private val load = AppDriver.LOAD_TIMEOUT_MS

    @Test
    fun aHiddenSlideStaysViewableWithItsBadge() {
        app.openFromAnotherApp(Fixtures.bytes("hidden.pptx", hiddenDeck().toBytes()))

        app.assertShown(app.descStartsWith("Slide 1 of 2. Public agenda (heading)"), load)

        app.scrollTo(app.descStartsWith("Slide 2 of 2"))
        app.assertShown(app.descStartsWith("Slide 2 of 2"), load)
        app.assertShown(app.text("Hidden"), load)
    }

    @Test
    fun aHiddenSlidesTextIsLeftOutOfFindInFile() {
        app.openFromAnotherApp(Fixtures.bytes("hidden.pptx", hiddenDeck().toBytes()))
        app.assertShown(app.descStartsWith("Slide 1 of 2. Public agenda (heading)"), load)

        app.chooseFromViewerMenu("Find in file")
        app.typeInto(app.field(), "Zebrafish confidential")
        app.assertShown(app.text("No matches"), load)

        app.typeInto(app.field(), "Visible agenda")
        app.assertShown(app.text("1 of 1"), load)
    }

    private fun hiddenDeck() = pptx {
        slide {
            textPlaceholder(Type.TITLE, idx = 0, x = TITLE_X, y = TITLE_Y, w = TITLE_W, h = TITLE_H) {
                para("Public agenda")
            }
            textPlaceholder(Type.BODY, idx = 1, x = BODY_X, y = BODY_Y, w = BODY_W, h = BODY_H) {
                para("Visible agenda item.")
            }
        }
        slide(hidden = true) {
            textPlaceholder(Type.TITLE, idx = 0, x = TITLE_X, y = TITLE_Y, w = TITLE_W, h = TITLE_H) {
                para("Confidential notes")
            }
            textPlaceholder(Type.BODY, idx = 1, x = BODY_X, y = BODY_Y, w = BODY_W, h = BODY_H) {
                para("Zebrafish confidential figure.")
            }
        }
    }

    private companion object {
        const val TITLE_X = 838_200L
        const val TITLE_Y = 365_125L
        const val TITLE_W = 10_515_600L
        const val TITLE_H = 1_325_563L
        const val BODY_X = 838_200L
        const val BODY_Y = 2_000_000L
        const val BODY_W = 10_515_600L
        const val BODY_H = 3_000_000L
    }
}
