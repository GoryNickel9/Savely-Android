package cc.savely.android.finance

/** src/lib/constants.ts web choices; custom imported icons/colors remain editable. */
val categoryEmojiOptions = listOf(
    "🍔", "🚗", "🏠", "🎬", "💊", "📦", "💰", "📈", "💵", "✈️", "🎮", "🎵", "🏋️", "☕", "🎁", "🍕", "🎨", "🏥",
    "⚽", "📱", "🔧", "🚲", "🚌", "👕", "🚬", "♠️", "🤖", "👨‍⚕️", "🍣", "⚖️", "📉", "🎲", "🐶", "📖", "🛒", "🔄", "🏛️",
)
val categoryColorOptions = listOf("#f97316", "#3b82f6", "#8b5cf6", "#ec4899", "#f59e0b", "#ef4444", "#6b7280", "#22c55e", "#14b8a6", "#06b6d4")

fun sortedFinanceBudgets(data: FinanceData, locale: java.util.Locale = java.util.Locale.ITALY): List<FinanceBudget> {
    val names = data.categories.associate { it.id to it.name.lowercase(java.util.Locale.ROOT) }
    val collator = java.text.Collator.getInstance(locale)
    return data.budgets.sortedWith(Comparator<FinanceBudget> { a, b -> collator.compare(names[a.categoryId].orEmpty(), names[b.categoryId].orEmpty()) }.thenBy { it.id })
}
