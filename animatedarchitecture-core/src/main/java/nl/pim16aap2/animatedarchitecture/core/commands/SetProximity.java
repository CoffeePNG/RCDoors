package nl.pim16aap2.animatedarchitecture.core.commands;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;
import lombok.ToString;
import nl.pim16aap2.animatedarchitecture.core.api.IConfig;
import nl.pim16aap2.animatedarchitecture.core.api.factories.ITextFactory;
import nl.pim16aap2.animatedarchitecture.core.localization.ILocalizer;
import nl.pim16aap2.animatedarchitecture.core.structures.Structure;
import nl.pim16aap2.animatedarchitecture.core.structures.StructureAttribute;
import nl.pim16aap2.animatedarchitecture.core.structures.properties.Property;
import nl.pim16aap2.animatedarchitecture.core.structures.retriever.StructureRetriever;
import nl.pim16aap2.animatedarchitecture.core.structures.retriever.StructureRetrieverFactory;
import nl.pim16aap2.animatedarchitecture.core.text.TextType;

import javax.annotation.Nullable;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;

/**
 * Represents the command that sets the radius within which a nearby player opens a structure.
 * <p>
 * A radius of 0 disables proximity opening for the structure.
 */
@ToString
public class SetProximity extends StructureTargetCommand
{
    public static final CommandDefinition COMMAND_DEFINITION = CommandDefinition.SET_PROXIMITY;

    private static final List<Property<?>> REQUIRED_PROPERTIES = List.of(Property.PROXIMITY_RADIUS);

    private final int radius;

    private final IConfig config;

    @AssistedInject
    SetProximity(
        @Assisted ICommandSender commandSender,
        ILocalizer localizer,
        ITextFactory textFactory,
        @Assisted StructureRetriever structureRetriever,
        @Assisted int radius,
        IConfig config)
    {
        super(commandSender, localizer, textFactory, structureRetriever, StructureAttribute.PROXIMITY_RADIUS);
        this.radius = radius;
        this.config = config;
    }

    @Override
    public CommandDefinition getCommand()
    {
        return COMMAND_DEFINITION;
    }

    @Override
    protected void handleDatabaseActionSuccess()
    {
        final var desc = getRetrievedStructureDescription();

        final String messageKey = radius > 0 ?
            "commands.set_proximity.success" :
            "commands.set_proximity.success.disabled";

        getCommandSender().sendMessage(textFactory.newText().append(
            localizer.getMessage(messageKey),
            TextType.SUCCESS,
            arg -> arg.highlight(desc.localizedTypeName()),
            arg -> arg.highlight(desc.id()),
            arg -> arg.highlight(radius))
        );
    }

    @Override
    protected void notifyMissingProperties(Structure structure)
    {
        getCommandSender().sendMessage(textFactory.newText().append(
            localizer.getMessage("commands.set_proximity.error.invalid_structure_type"),
            TextType.ERROR,
            arg -> arg.highlight(localizer.getStructureType(structure)),
            arg -> arg.highlight(structure.getBasicInfo()))
        );
    }

    @Override
    protected CompletableFuture<?> performAction(Structure structure)
    {
        if (radius < 0)
        {
            getCommandSender().sendMessage(textFactory.newText().append(
                localizer.getMessage("commands.set_proximity.error.negative_radius"),
                TextType.ERROR,
                arg -> arg.highlight(radius))
            );
            return CompletableFuture.completedFuture(null);
        }

        final OptionalInt maxRadius = config.maxProximityRadius();
        if (maxRadius.isPresent() && radius > maxRadius.getAsInt())
        {
            getCommandSender().sendMessage(textFactory.newText().append(
                localizer.getMessage("commands.set_proximity.error.radius_too_large"),
                TextType.ERROR,
                arg -> arg.highlight(radius),
                arg -> arg.highlight(maxRadius.getAsInt()))
            );
            return CompletableFuture.completedFuture(null);
        }

        final @Nullable Integer oldRadius = structure.setPropertyValue(Property.PROXIMITY_RADIUS, radius).value();

        if (oldRadius == null || oldRadius != radius)
            return structure
                .syncData()
                .thenAccept(this::handleDatabaseActionResult);

        getCommandSender().sendMessage(textFactory.newText().append(
            localizer.getMessage("commands.set_proximity.error.status_not_changed"),
            TextType.ERROR,
            arg -> arg.highlight(localizer.getStructureType(structure)),
            arg -> arg.highlight(structure.getNameAndUid()),
            arg -> arg.highlight(radius))
        );

        return CompletableFuture.completedFuture(null);
    }

    @Override
    protected List<Property<?>> getRequiredProperties()
    {
        return REQUIRED_PROPERTIES;
    }

    @AssistedFactory
    interface IFactory
    {
        /**
         * Creates (but does not execute!) a new {@link SetProximity} command.
         *
         * @param commandSender
         *     The {@link ICommandSender} responsible for changing the proximity radius of the structure.
         * @param structureRetriever
         *     A {@link StructureRetrieverFactory} representing the {@link Structure} whose proximity radius will be
         *     modified.
         * @param radius
         *     The new proximity radius in blocks. 0 disables proximity opening.
         * @return See {@link BaseCommand#run()}.
         */
        SetProximity newSetProximity(
            ICommandSender commandSender,
            StructureRetriever structureRetriever,
            int radius
        );
    }
}
