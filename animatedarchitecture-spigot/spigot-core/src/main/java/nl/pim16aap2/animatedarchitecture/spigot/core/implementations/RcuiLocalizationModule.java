package nl.pim16aap2.animatedarchitecture.spigot.core.implementations;

import dagger.Binds;
import dagger.Module;
import nl.pim16aap2.animatedarchitecture.core.localization.ILocalizer;

@Module
public interface RcuiLocalizationModule
{
    @Binds ILocalizer localizer(RcuiLocalizer localizer);
}
