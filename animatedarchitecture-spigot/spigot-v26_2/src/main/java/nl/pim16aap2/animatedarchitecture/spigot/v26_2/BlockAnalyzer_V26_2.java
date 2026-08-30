package nl.pim16aap2.animatedarchitecture.spigot.v26_2;

import lombok.extern.flogger.Flogger;
import nl.pim16aap2.animatedarchitecture.core.api.restartable.IRestartable;
import nl.pim16aap2.animatedarchitecture.core.api.restartable.RestartableHolder;
import nl.pim16aap2.animatedarchitecture.spigot.util.api.BlockAnalyzerSpigot;
import nl.pim16aap2.animatedarchitecture.spigot.util.api.IBlockAnalyzerConfig;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.CommandBlock;
import org.bukkit.inventory.BlockInventoryHolder;
import org.jetbrains.annotations.Nullable;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;

/**
 * Performs block analysis for the Spigot 26.2 platform.
 */
@Flogger
@Singleton
final class BlockAnalyzer_V26_2 extends BlockAnalyzerSpigot implements IRestartable
{
    private static final List<Tag<Material>> BLOCKED_TAGS = List.of(
        Tag.AIR,
        Tag.ALL_SIGNS,
        Tag.BANNERS,
        Tag.BEDS,
        Tag.BEEHIVES,
        Tag.SHULKER_BOXES
    );

    @Inject
    BlockAnalyzer_V26_2(
        IBlockAnalyzerConfig config,
        RestartableHolder restartableHolder)
    {
        super(config, restartableHolder);
    }

    @Override
    protected MaterialStatus getDefaultMaterialStatus(Material mat)
    {
        if (!mat.isBlock())
            return MaterialStatus.BLACKLISTED;

        for (final Tag<Material> tag : BLOCKED_TAGS)
            if (tag.isTagged(mat))
                return MaterialStatus.BLACKLISTED;

        final @Nullable var blockData = safeCreateBlockData(mat);
        if (blockData instanceof Levelled || blockData instanceof CommandBlock)
            return MaterialStatus.BLACKLISTED;

        final @Nullable var blockState = safeCreateBlockState(blockData);
        if (blockState instanceof BlockInventoryHolder)
            return MaterialStatus.BLACKLISTED;

        return switch (mat)
        {
            case COMMAND_BLOCK,
                 FROGSPAWN,
                 PISTON_HEAD,
                 REDSTONE,
                 SPAWNER,
                 STRUCTURE_BLOCK,
                 STRUCTURE_VOID,
                 TRIAL_SPAWNER,
                 VAULT -> MaterialStatus.BLACKLISTED;
            default -> MaterialStatus.WHITELISTED;
        };
    }

    @SuppressWarnings("UnstableApiUsage")
    private @Nullable BlockState safeCreateBlockState(@Nullable BlockData blockData)
    {
        if (blockData == null)
            return null;

        try
        {
            return blockData.createBlockState();
        }
        catch (Exception e)
        {
            log.atInfo().log(
                "Encountered error creating block state from block data '%s': %s - %s",
                blockData,
                e.getClass().getSimpleName(),
                e.getMessage()
            );
            return null;
        }
    }
}
