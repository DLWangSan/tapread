package com.dlwangsan.tapread

object TextCleaner {
    private val multiSpace = Regex("\\s+")
    private val codeLike = Regex("""(?i)^(验证码|校验码|code)[:：\s]*\d{4,8}$""")
    private val mostlyDigits = Regex("""^\d{4,8}$""")

    fun clean(raw: String): String {
        return raw
            .replace('\u00A0', ' ')
            .replace(Regex("[\\r\\n]+"), "。")
            .replace(multiSpace, " ")
            .trim()
            .trim('。', '，', ',', '.', ' ', '\t')
    }

    fun shouldSkipAutoRead(text: String): Boolean {
        val cleaned = clean(text)
        if (cleaned.length < 2) return true
        if (cleaned.length > 4000) return true
        if (codeLike.matches(cleaned) || mostlyDigits.matches(cleaned)) return true
        return false
    }
}
