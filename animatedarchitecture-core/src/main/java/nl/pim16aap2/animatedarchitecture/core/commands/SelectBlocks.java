package nl.pim16aap2.animatedarchitecture.core.commands;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;
import lombok.ToString;
import nl.pim16aap2.animatedarchitecture.core.api.IPlayer;
import nl.pim16aap2.animatedarchitecture.core.api.factories.ITextFactory;
import nl.pim16aap2.animatedarchitecture.core.localization.ILocalizer;
import nl.pim16aap2.animatedarchitecture.core.managers.ToolUserManager;
import nl.pim16aap2.animatedarchitecture.core.tooluser.BlockSelectionAction;
import nl.pim16aap2.animatedarchitecture.core.tooluser.creator.Creator;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * Represents the command that applies an action to the block selection of an active creation process.
 * <p>
 * The actions of this command are offered as clickable options in the instructions of the block selection step, so
 * players do not have to type them.
 */
@ToString
public class SelectBlocks extends BaseCommand
{
    private final String action;
    private final ToolUserManager toolUserManager;

    @AssistedInject
    SelectBlocks(
        @Assisted ICommandSender commandSender,
        @Assisted String action,
        ILocalizer localizer,
        ITextFactory textFactory,
        ToolUserManager toolUserManager)
    {
        super(commandSender, localizer, textFactory);
        this.action = action;
        this.toolUserManager = toolUserManager;
    }

    @Override
    public CommandDefinition getCommand()
    {
        return CommandDefinition.SELECT_BLOCKS;
    }

    @Override
    protected boolean availableForNonPlayers()
    {
        return false;
    }

    @Override
    protected CompletableFuture<?> executeCommand(PermissionsStatus permissions)
    {
        final @Nullable var toolUser =
            toolUserManager.getToolUser(((IPlayer) getCommandSender()).getUUID()).orElse(null);

        if (!(toolUser instanceof Creator creator))
        {
            getCommandSender().sendError(
                textFactory,
                localizer.getMessage("commands.select_blocks.error.no_selection_process")
            );
            return CompletableFuture.completedFuture(null);
        }

        final var parsedAction = BlockSelectionAction.parse(action);
        if (parsedAction.isEmpty())
        {
            getCommandSender().sendError(
                textFactory,
                localizer.getMessage("commands.select_blocks.error.invalid_action", action)
            );
            return CompletableFuture.completedFuture(null);
        }

        return creator.applyBlockSelectionAction(parsedAction.get());
    }

    @AssistedFactory
    public interface IFactory
    {
        /**
         * Creates (but does not execute!) a new {@link SelectBlocks} command.
         *
         * @param commandSender
         *     The command sender whose block selection to update.
         * @param action
         *     The name of the {@link BlockSelectionAction} to apply.
         * @return The new command.
         */
        SelectBlocks newSelectBlocks(ICommandSender commandSender, String action);
    }
}
