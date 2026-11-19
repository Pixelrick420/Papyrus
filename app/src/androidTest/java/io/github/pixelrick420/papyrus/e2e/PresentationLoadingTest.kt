package io.github.pixelrick420.papyrus.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pixelrick420.papyrus.viewer.OfficeFixtures
import io.github.pixelrick420.papyrus.viewer.Preset
import io.github.pixelrick420.papyrus.viewer.Type
import io.github.pixelrick420.papyrus.viewer.pptx
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PresentationLoadingTest : E2eTest() {

    private val app = AppDriver()

    private val load = AppDriver.LOAD_TIMEOUT_MS

    @Test
    fun everySlideOfAMultiSlideDeckShowsItsOwnTitleAndBody() {
        val deck = pptx {
            slide {
                textPlaceholder(Type.TITLE, idx = 0, x = TITLE_X, y = TITLE_Y, w = TITLE_W, h = TITLE_H) {
                    para("Opening roadmap")
                }
                textPlaceholder(Type.BODY, idx = 1, x = BODY_X, y = BODY_Y, w = BODY_W, h = BODY_H) {
                    para("First quarter sets the direction.")
                }
            }
            slide {
                textPlaceholder(Type.TITLE, idx = 0, x = TITLE_X, y = TITLE_Y, w = TITLE_W, h = TITLE_H) {
                    para("Midyear review")
                }
                textPlaceholder(Type.BODY, idx = 1, x = BODY_X, y = BODY_Y, w = BODY_W, h = BODY_H) {
                    para("Midyear numbers beat the plan.")
                }
            }
            slide {
                textPlaceholder(Type.TITLE, idx = 0, x = TITLE_X, y = TITLE_Y, w = TITLE_W, h = TITLE_H) {
                    para("Year end outlook")
                }
                textPlaceholder(Type.BODY, idx = 1, x = BODY_X, y = BODY_Y, w = BODY_W, h = BODY_H) {
                    para("Year end looks strong.")
                }
            }
        }
        app.openFromAnotherApp(Fixtures.bytes("roadmap.pptx", deck.toBytes()))

        app.assertShown(app.descStartsWith("Slide 1 of 3. Opening roadmap (heading)"), load)
        app.assertShown(app.descContains("First quarter sets the direction"), load)

        app.scrollTo(app.descStartsWith("Slide 2 of 3"))
        app.assertShown(app.descStartsWith("Slide 2 of 3. Midyear review (heading)"), load)
        app.assertShown(app.descContains("Midyear numbers beat the plan"), load)

        app.scrollTo(app.descStartsWith("Slide 3 of 3"))
        app.assertShown(app.descStartsWith("Slide 3 of 3. Year end outlook (heading)"), load)
        app.assertShown(app.descContains("Year end looks strong"), load)
    }

    @Test
    fun aSlideWithAPictureAndAShapeStillShowsItsTitleAndAltText() {
        val deck = pptx {
            slide {
                textPlaceholder(Type.TITLE, idx = 0, x = TITLE_X, y = TITLE_Y, w = TITLE_W, h = TITLE_H) {
                    para("Revenue chart")
                }
                shape(Preset.RECT, x = 838_200L, y = 2_000_000L, w = 4_000_000L, h = 2_000_000L, name = "Band")
                picture(
                    bytes = OfficeFixtures.PNG_1X1,
                    x = 5_400_000L,
                    y = 2_000_000L,
                    w = 6_000_000L,
                    h = 3_000_000L,
                    description = "Bar chart of quarterly revenue",
                    name = "Revenue picture",
                )
            }
        }
        app.openFromAnotherApp(Fixtures.bytes("charts.pptx", deck.toBytes()))

        app.assertShown(app.descStartsWith("Slide 1 of 1. Revenue chart (heading)"), load)
        app.assertShown(app.descContains("Bar chart of quarterly revenue"), load)
    }

    @Test
    fun openingASecondPresentationLoadsItsOwnTextInsteadOfTheFirst() {
        val first = pptx {
            slide {
                textPlaceholder(Type.TITLE, idx = 0, x = TITLE_X, y = TITLE_Y, w = TITLE_W, h = TITLE_H) {
                    para("First deck headline")
                }
            }
        }
        app.openFromAnotherApp(Fixtures.bytes("first.pptx", first.toBytes()))
        app.assertShown(app.descStartsWith("Slide 1 of 1. First deck headline (heading)"), load)

        val second = pptx {
            slide {
                textPlaceholder(Type.TITLE, idx = 0, x = TITLE_X, y = TITLE_Y, w = TITLE_W, h = TITLE_H) {
                    para("Second deck headline")
                }
            }
        }
        app.openFromAnotherApp(Fixtures.bytes("second.pptx", second.toBytes()))
        app.assertShown(app.descStartsWith("Slide 1 of 1. Second deck headline (heading)"), load)
        app.assertGone(app.descContains("First deck headline"))
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
