package com.papyrus.app.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {

    @Test
    fun `a double dollar is the only math`() {
        assertTrue(MarkdownRenderer.hasMath("Einstein: \$\$E=mc^2\$\$ holds"))
        assertTrue(MarkdownRenderer.hasMath("\$\$\n\\sum_i x_i\n\$\$"))
    }

    @Test
    fun `prices, shell variables and a single dollar are not math`() {
        assertFalse(MarkdownRenderer.hasMath("It costs \$5 and \$10"))
        assertFalse(MarkdownRenderer.hasMath("echo \$HOME and \${PATH}"))
        assertFalse(MarkdownRenderer.hasMath("inline \$x\$ is not supported"))
        assertFalse(MarkdownRenderer.hasMath(""))
    }
}
