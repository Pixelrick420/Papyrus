package io.github.pixelrick420.papyrus.viewer.pptx.chart

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

internal object ChartNumberFormats {

    fun parse(source: String?): ChartNumberFormat {
        val raw = source?.trim().orEmpty().ifEmpty { "General" }
        return ChartNumberFormat(raw, normalize(raw))
    }

    fun normalize(source: String): String {
        val code = source.trim()
        if (code.isEmpty() || code.equals("General", ignoreCase = true)) return "General"
        if (code == "0" || code == "0.00" || code == "#,##0" || code == "#,##0.00" || code == "0%") return code
        if (code.contains("%")) return "0%"
        if (isThousandsScaling(code)) return "#,##0"
        if (isCurrency(code)) return code
        if (isSimpleDate(code)) return code
        return "General"
    }

    fun format(value: Double, format: ChartNumberFormat?): String {
        val code = format?.formatCode ?: "General"
        return when {
            code == "General" -> formatGeneral(value)
            code == "0" -> formatDecimal(value, 0, grouping = false)
            code == "0.00" -> formatDecimal(value, 2, grouping = false)
            code == "#,##0" -> formatDecimal(value, 0, grouping = true)
            code == "#,##0.00" -> formatDecimal(value, 2, grouping = true)
            code == "0%" -> formatDecimal(value * 100.0, 0, grouping = false) + "%"
            isThousandsScaling(format?.sourceCode ?: code) -> formatThousandsScaled(value, format?.sourceCode ?: code)
            isCurrency(code) -> formatCurrency(value, code)
            isSimpleDate(code) -> formatDate(value, code)
            else -> formatGeneral(value)
        }
    }

    fun formatTick(value: Double, axis: ChartAxis): String = format(value, axis.numberFormat)

    private fun formatGeneral(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return value.toString()
        val rounded = round(value)
        if (abs(value - rounded) < 1e-10 && abs(rounded) < 1e12) return rounded.toLong().toString()
        val symbols = DecimalFormatSymbols(Locale.US)
        val df = DecimalFormat("0.##########", symbols)
        return df.format(value)
    }

    private fun formatDecimal(value: Double, decimals: Int, grouping: Boolean): String {
        val symbols = DecimalFormatSymbols(Locale.US)
        val pattern = buildString {
            if (grouping) append("#,##")
            append("0")
            if (decimals > 0) {
                append('.')
                repeat(decimals) { append('0') }
            }
        }
        return DecimalFormat(pattern, symbols).format(value)
    }

    private fun isThousandsScaling(code: String): Boolean {
        val trimmed = code.trim()
        if (trimmed.endsWith(",") && !trimmed.endsWith(",,")) return true
        if (trimmed.contains(",,")) return true
        return false
    }

    private fun formatThousandsScaled(value: Double, source: String): String {
        val commas = if (source.contains(",,")) 2 else 1
        val scaled = value / 1000.0.pow(commas.toDouble())
        val decimals = when {
            source.contains(".00") -> 2
            source.contains(".0") -> 1
            else -> 0
        }
        val grouping = source.contains("#,##") || source.contains("0,0")
        return formatDecimal(scaled, decimals, grouping)
    }

    private fun isCurrency(code: String): Boolean =
        code.any { it in "$€£¥" } || code.contains("[$")

    private fun formatCurrency(value: Double, code: String): String {
        val symbol = when {
            code.contains("€") -> "€"
            code.contains("£") -> "£"
            code.contains("¥") -> "¥"
            else -> "$"
        }
        val decimals = if (code.contains(".00")) 2 else 0
        val grouping = code.contains("#,##") || code.contains("0,0")
        val number = formatDecimal(value, decimals, grouping)
        // A symbol after the last digit placeholder is a suffix, as in `#,##0.00 €`.
        val lastPlaceholder = code.indexOfLast { it in "0#?.," }
        val suffix = lastPlaceholder >= 0 && code.lastIndexOf(symbol) > lastPlaceholder
        return if (suffix) "$number$symbol" else "$symbol$number"
    }

    private fun isSimpleDate(code: String): Boolean {
        val hasDate = code.contains('d') || code.contains('m') || code.contains('y')
        val hasSep = code.contains('/') || code.contains('-') || code.contains(':') || code.contains(' ')
        return hasDate && hasSep && !code.contains('#') && !code.contains('0')
    }

    private fun formatDate(value: Double, code: String): String {
        // Excel serial date: days since 1899-12-30
        val millis = ((value - 25569.0) * 86400000.0).toLong()
        val date = Date(millis)
        val pattern = when {
            code.contains("yyyy") && code.contains("mm") && code.contains("dd") -> "yyyy-MM-dd"
            code.contains("yy") && code.contains("mm") && code.contains("dd") -> "yy-MM-dd"
            code.contains("mm") && code.contains("dd") -> "MM/dd"
            code.contains("mmm", ignoreCase = true) && code.contains("yy") -> "MMM yy"
            code.contains("mmm", ignoreCase = true) -> "MMM"
            code.contains("yyyy") -> "yyyy"
            code.contains(':') -> "HH:mm"
            else -> "yyyy-MM-dd"
        }
        return try {
            SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)
        } catch (_: Exception) {
            formatGeneral(value)
        }
    }
}
