package cc.savely.android.finance

import android.content.Context

/** Device-local preferences, separate for each account, with durable writes verified before UI dismissal. */
class PreferenceRecurringDismissals(context: Context) : RecurringDismissals {
    private val preferences = context.applicationContext.getSharedPreferences("finance_recurring_suggestions", Context.MODE_PRIVATE)
    @Synchronized override fun keys(userId: String): Set<String> = preferences.getStringSet(userId, emptySet()).orEmpty().toSet()
    @Synchronized override fun ignore(userId: String, key: String) {
        check(preferences.edit().putStringSet(userId, keys(userId) + key).commit()) {
            "Impossibile salvare il suggerimento ignorato. Riprova."
        }
    }
}
