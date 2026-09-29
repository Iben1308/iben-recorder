package ua.iben.recorder;

import android.content.Context;
import android.content.res.Configuration;
import java.util.Locale;

final class LocaleContext {
    static Context wrap(Context context) {
        String code = context.getSharedPreferences("recorder", Context.MODE_PRIVATE).getString("language", "uk");
        I18n.use(code);
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(new Locale(code));
        return context.createConfigurationContext(configuration);
    }
}
