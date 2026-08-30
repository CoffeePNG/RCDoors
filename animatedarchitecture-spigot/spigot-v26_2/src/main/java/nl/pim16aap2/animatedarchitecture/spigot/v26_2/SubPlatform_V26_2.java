package nl.pim16aap2.animatedarchitecture.spigot.v26_2;

import nl.pim16aap2.animatedarchitecture.spigot.util.api.BlockAnalyzerSpigot;
import nl.pim16aap2.animatedarchitecture.spigot.util.api.ISpigotSubPlatform;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Represents a sub-platform for Spigot 26.2.
 */
@Singleton
public final class SubPlatform_V26_2 implements ISpigotSubPlatform
{
    private final BlockAnalyzer_V26_2 blockAnalyzer;

    @Inject
    SubPlatform_V26_2(BlockAnalyzer_V26_2 blockAnalyzer)
    {
        this.blockAnalyzer = blockAnalyzer;
    }

    @Override
    public BlockAnalyzerSpigot getBlockAnalyzer()
    {
        return blockAnalyzer;
    }
}
