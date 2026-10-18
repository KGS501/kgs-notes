package com.kgs.notes

import android.content.Context
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate

/** Application-owned work also follows the selected app language on Android 12 and earlier. */
internal fun Context.localizedResources(): Resources {
    val locales = AppCompatDelegate.getApplicationLocales()
    if (locales.isEmpty) return resources
    val configuration = android.content.res.Configuration(resources.configuration)
    configuration.setLocales(android.os.LocaleList.forLanguageTags(locales.toLanguageTags()))
    return createConfigurationContext(configuration).resources
}
