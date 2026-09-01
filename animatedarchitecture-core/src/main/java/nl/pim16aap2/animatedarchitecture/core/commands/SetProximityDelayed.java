package nl.pim16aap2.animatedarchitecture.core.commands;

import nl.pim16aap2.animatedarchitecture.core.structures.retriever.StructureRetriever;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.CompletableFuture;

/**
 * Delayed version of {@link SetProximity}.
 * <p>
 * The radius within which a nearby player opens the structure can be provided as delayed input.
 */
@Singleton
public class SetProximityDelayed extends DelayedCommand<Integer>
{
    @Inject
    public SetProximityDelayed(
        Context context,
        DelayedCommandInputRequest.IFactory<Integer> inputRequestFactory)
    {
        super(context, inputRequestFactory, Integer.class);
    }

    @Override
    protected CommandDefinition getCommandDefinition()
    {
        return SetProximity.COMMAND_DEFINITION;
    }

    @Override
    protected CompletableFuture<?> delayedInputExecutor(
        ICommandSender commandSender,
        StructureRetriever structureRetriever,
        Integer radius)
    {
        return commandFactory.get().newSetProximity(commandSender, structureRetriever, radius).run();
    }

    @Override
    protected String inputRequestMessage(ICommandSender commandSender, StructureRetriever structureRetriever)
    {
        return localizer.getMessage("commands.set_proximity.delayed.init");
    }
}
