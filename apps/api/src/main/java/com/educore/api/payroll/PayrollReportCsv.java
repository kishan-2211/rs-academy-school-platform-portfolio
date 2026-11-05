package com.educore.api.payroll;

import java.math.BigDecimal;

/** RFC-style quoting plus spreadsheet formula neutralization for untrusted display text. */
final class PayrollReportCsv {
    private PayrollReportCsv() {}
    static void row(StringBuilder output, Object... values) {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) output.append(',');
            Object value = values[index];
            String text = value == null ? "" : value instanceof BigDecimal amount ? amount.toPlainString() : value.toString();
            if (!(value instanceof Number) && formula(text)) text = "'" + text;
            output.append('"').append(text.replace("\"", "\"\"")).append('"');
        }
        output.append("\r\n");
    }
    private static boolean formula(String text) {
        for (int offset = 0; offset < text.length();) {
            int code = text.codePointAt(offset);
            if (Character.isWhitespace(code) || Character.isISOControl(code) || Character.getType(code) == Character.FORMAT) {
                offset += Character.charCount(code); continue;
            }
            return code == '=' || code == '+' || code == '-' || code == '@';
        }
        return false;
    }
}
