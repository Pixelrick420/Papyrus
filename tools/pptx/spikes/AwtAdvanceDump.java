import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Spike S2, JVM half: dumps unhinted per-character advances from AWT for one bundled TTF.
 *
 * <p>Each character is measured on its own, so no kerning or ligature substitution can make the
 * glyph count differ from the character count. {@code app/src/androidTest/.../FontAdvanceSpikeTest}
 * measures the same characters with {@code Paint} and compares them against this file. Standalone:
 * not part of the app build.
 */
public final class AwtAdvanceDump {

  private static final float SIZE_PX = 64f;
  private static final String TEXT = ascii();

  private AwtAdvanceDump() {}

  public static void main(String[] args) throws Exception {
    File fontFile = new File(args.length > 0 ? args[0]
        : "app/src/main/assets/ooxml/fonts/humanist/PptxHumanist-Regular.ttf");
    File out = new File(args.length > 1 ? args[1]
        : "app/src/androidTest/assets/spikes/awt-advances.json");

    Font font = Font.createFont(Font.TRUETYPE_FONT, fontFile).deriveFont(SIZE_PX);
    // Fractional metrics keeps advances unhinted, which is the closest match to linear text.
    FontRenderContext frc = new FontRenderContext(null, true, true);

    StringBuilder sb = new StringBuilder();
    sb.append("{\n");
    sb.append("  \"formatVersion\": 1,\n");
    sb.append("  \"generator\": \"AWT ").append(System.getProperty("java.version")).append("\",\n");
    sb.append("  \"font\": \"").append(fontFile.getName()).append("\",\n");
    sb.append("  \"sizePx\": ").append(num(SIZE_PX)).append(",\n");
    sb.append("  \"chars\": \"").append(escape(TEXT)).append("\",\n");
    sb.append("  \"advances\": [");
    for (int i = 0; i < TEXT.length(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      double advance = font.getStringBounds(TEXT.substring(i, i + 1), frc).getWidth();
      sb.append(num(advance));
    }
    sb.append("]\n}\n");

    out.getParentFile().mkdirs();
    try (Writer w = Files.newBufferedWriter(out.toPath(), StandardCharsets.UTF_8)) {
      w.write(sb.toString());
    }
    System.err.println("wrote " + TEXT.length() + " advances at " + SIZE_PX + " px to " + out);
  }

  private static String ascii() {
    StringBuilder sb = new StringBuilder();
    for (char c = 0x20; c <= 0x7e; c++) {
      sb.append(c);
    }
    return sb.toString();
  }

  private static String num(double v) {
    return String.format(Locale.ROOT, "%.4f", v);
  }

  private static String escape(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
