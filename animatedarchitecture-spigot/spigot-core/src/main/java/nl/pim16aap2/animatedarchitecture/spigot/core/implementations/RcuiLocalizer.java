package nl.pim16aap2.animatedarchitecture.spigot.core.implementations;

import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import nl.pim16aap2.animatedarchitecture.core.localization.ILocalizer;
import nl.pim16aap2.animatedarchitecture.core.localization.LocalizationManager;
import nl.pim16aap2.animatedarchitecture.spigot.core.AnimatedArchitecturePlugin;

/** Keeps native locales/patches and Text argument formatting behind the RCUI catalog. */
@Singleton
public final class RcuiLocalizer implements ILocalizer
{
    private final AnimatedArchitecturePlugin plugin;
    private final ILocalizer nativeLocalizer;

    @Inject
    public RcuiLocalizer(AnimatedArchitecturePlugin plugin, LocalizationManager manager)
    {
        this.plugin = plugin;
        this.nativeLocalizer = manager.getLocalizer();
    }

    @Override
    public String getMessage(String key, Locale locale, Object... arguments)
    {
        return render(key, locale.toLanguageTag(), nativeLocalizer.getMessage(key, locale), arguments);
    }

    @Override
    public String getMessage(String key, Object... arguments)
    {
        return render(key, "default", nativeLocalizer.getMessage(key), arguments);
    }

    private String render(String key, String locale, String nativeTemplate, Object[] arguments)
    {
        final var presentation = plugin.getNativePresentation();
        // Dagger may construct parsers while the platform is being assembled before onEnable.
        final String template = presentation == null ? nativeTemplate
            : presentation.template(key, locale, nativeTemplate);
        return arguments.length == 0 ? template : MessageFormat.format(template, arguments);
    }

    @Override public List<Locale> getAvailableLocales() { return nativeLocalizer.getAvailableLocales(); }
}
