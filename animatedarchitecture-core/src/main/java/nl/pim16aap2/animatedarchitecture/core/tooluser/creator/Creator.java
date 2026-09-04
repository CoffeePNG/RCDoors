package nl.pim16aap2.animatedarchitecture.core.tooluser.creator;

import com.google.errorprone.annotations.concurrent.GuardedBy;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.flogger.Flogger;
import nl.pim16aap2.animatedarchitecture.core.animation.AnimationType;
import nl.pim16aap2.animatedarchitecture.core.animation.StructureActivityManager;
import nl.pim16aap2.animatedarchitecture.core.api.Color;
import nl.pim16aap2.animatedarchitecture.core.api.HighlightedBlockSpawner;
import nl.pim16aap2.animatedarchitecture.core.api.IEconomyManager;
import nl.pim16aap2.animatedarchitecture.core.api.IExecutor;
import nl.pim16aap2.animatedarchitecture.core.api.IHighlightedBlock;
import nl.pim16aap2.animatedarchitecture.core.api.ILocation;
import nl.pim16aap2.animatedarchitecture.core.api.IPlayer;
import nl.pim16aap2.animatedarchitecture.core.api.IWorld;
import nl.pim16aap2.animatedarchitecture.core.commands.CommandFactory;
import nl.pim16aap2.animatedarchitecture.core.events.StructureActionCause;
import nl.pim16aap2.animatedarchitecture.core.events.StructureActionType;
import nl.pim16aap2.animatedarchitecture.core.managers.DatabaseManager;
import nl.pim16aap2.animatedarchitecture.core.managers.LimitsManager;
import nl.pim16aap2.animatedarchitecture.core.structures.IStructureComponent;
import nl.pim16aap2.animatedarchitecture.core.structures.PermissionLevel;
import nl.pim16aap2.animatedarchitecture.core.structures.Structure;
import nl.pim16aap2.animatedarchitecture.core.structures.StructureAnimationRequestBuilder;
import nl.pim16aap2.animatedarchitecture.core.structures.StructureBuilder;
import nl.pim16aap2.animatedarchitecture.core.structures.StructureID;
import nl.pim16aap2.animatedarchitecture.core.structures.StructureOwner;
import nl.pim16aap2.animatedarchitecture.core.structures.StructureType;
import nl.pim16aap2.animatedarchitecture.core.structures.properties.Property;
import nl.pim16aap2.animatedarchitecture.core.structures.properties.PropertyContainer;
import nl.pim16aap2.animatedarchitecture.core.text.Text;
import nl.pim16aap2.animatedarchitecture.core.text.TextArgument;
import nl.pim16aap2.animatedarchitecture.core.text.TextArgumentFactory;
import nl.pim16aap2.animatedarchitecture.core.text.TextType;
import nl.pim16aap2.animatedarchitecture.core.tooluser.BlockSelectionAction;
import nl.pim16aap2.animatedarchitecture.core.tooluser.Procedure;
import nl.pim16aap2.animatedarchitecture.core.tooluser.Step;
import nl.pim16aap2.animatedarchitecture.core.tooluser.ToolClick;
import nl.pim16aap2.animatedarchitecture.core.tooluser.ToolUser;
import nl.pim16aap2.animatedarchitecture.core.tooluser.stepexecutor.AsyncStepExecutor;
import nl.pim16aap2.animatedarchitecture.core.tooluser.stepexecutor.StepExecutorBlockSelection;
import nl.pim16aap2.animatedarchitecture.core.tooluser.stepexecutor.StepExecutorBoolean;
import nl.pim16aap2.animatedarchitecture.core.tooluser.stepexecutor.StepExecutorLocation;
import nl.pim16aap2.animatedarchitecture.core.tooluser.stepexecutor.StepExecutorOpenDirection;
import nl.pim16aap2.animatedarchitecture.core.tooluser.stepexecutor.StepExecutorString;
import nl.pim16aap2.animatedarchitecture.core.tooluser.stepexecutor.StepExecutorVoid;
import nl.pim16aap2.animatedarchitecture.core.util.BlockSelection;
import nl.pim16aap2.animatedarchitecture.core.util.BlockSelectionBuilder;
import nl.pim16aap2.animatedarchitecture.core.util.Cuboid;
import nl.pim16aap2.animatedarchitecture.core.util.FutureUtil;
import nl.pim16aap2.animatedarchitecture.core.util.Limit;
import nl.pim16aap2.animatedarchitecture.core.util.MovementDirection;
import nl.pim16aap2.animatedarchitecture.core.util.StringUtil;
import nl.pim16aap2.animatedarchitecture.core.util.Util;
import nl.pim16aap2.animatedarchitecture.core.util.vector.Vector3Di;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Represents a specialization of the {@link ToolUser} that is used for creating new {@link Structure}s.
 */
@ToString(callSuper = true, onlyExplicitlyIncluded = true)
@Flogger
public abstract class Creator extends ToolUser
{
    protected final LimitsManager limitsManager;

    protected final StructureBuilder structureBuilder;

    protected final DatabaseManager databaseManager;

    protected final IEconomyManager economyManager;

    protected final CommandFactory commandFactory;

    private final @Nullable StructureAnimationRequestBuilder structureAnimationRequestBuilder;

    private final StructureActivityManager structureActivityManager;

    private final HighlightedBlockSpawner highlightedBlockSpawner;

    private final IExecutor executor;

    /**
     * The maximum number of blocks that are highlighted at the same time during the block selection step.
     * <p>
     * Every highlighted block is an entity, so highlighting a large selection would cost more than the feedback is
     * worth. Blocks beyond this limit are still selected; they are simply not highlighted.
     */
    private static final int MAX_HIGHLIGHTED_BLOCKS = 250;

    protected final StructureID structureID = StructureID.getUnregisteredID();

    /**
     * The {@link PropertyContainer} that is used to manage the properties of the structure.
     */
    @GuardedBy("this")
    private final PropertyContainer propertyContainer;

    /**
     * The name of the structure that is to be created.
     */
    @ToString.Include
    @GuardedBy("this")
    private @Nullable String name;

    /**
     * The cuboid that defines the location and dimensions of the structure.
     * <p>
     * This region is defined by {@link #firstPos} and the second position selected by the user.
     */
    @ToString.Include
    @GuardedBy("this")
    private @Nullable Cuboid cuboid;

    /**
     * The first point that was selected in the process.
     * <p>
     * Once a second point has been selected, these two are used to construct the {@link #cuboid}.
     */
    @ToString.Include
    @GuardedBy("this")
    private @Nullable Vector3Di firstPos;

    /**
     * The blocks the user selected individually with the wand.
     * <p>
     * When the user does not select any blocks, the structure simply consists of every block in {@link #cuboid}, which
     * is how structures worked before the wand existed.
     */
    @GuardedBy("this")
    private final BlockSelectionBuilder blockSelection = new BlockSelectionBuilder();

    /**
     * The highlighted blocks that show the user which blocks they have selected so far.
     */
    @GuardedBy("this")
    private final Map<Vector3Di, IHighlightedBlock> selectionHighlights = new HashMap<>();

    /**
     * The powerblock selected by the user.
     */
    @ToString.Include
    @GuardedBy("this")
    private @Nullable Vector3Di powerblock;

    /**
     * The opening direction selected by the user.
     */
    @ToString.Include
    @GuardedBy("this")
    private @Nullable MovementDirection movementDirection;

    /**
     * The {@link IWorld} this structure is created in.
     */
    @ToString.Include
    @GuardedBy("this")
    private @Nullable IWorld world;

    /**
     * Whether the structure is created in the locked (true) or unlocked (false) state.
     */
    @ToString.Include
    @GuardedBy("this")
    private boolean isLocked = false;

    /**
     * Whether the process is in a state where is may be updated outside the normal execution order.
     */
    @ToString.Include
    @GuardedBy("this")
    private boolean processIsUpdatable = false;

    /**
     * The number of currently open editing sessions.
     * <p>
     * See {@link #beginEditingSession()}. While this is greater than zero, {@link #processIsUpdatable} is kept true so
     * that a front-end showing every step at once can issue several {@link #update(String, Object)} calls in a row.
     */
    @ToString.Include
    @GuardedBy("this")
    private int editingSessionDepth = 0;

    /**
     * Factory for the {@link Step} that provides the name.
     */
    protected final Step.Factory factoryProvideName;

    /**
     * Factory for the {@link Step} that provides the first position of the area of the structure.
     * <p>
     * Don't forget to set the message before using it!
     */
    protected final Step.Factory factoryProvideFirstPos;

    /**
     * Factory for the {@link Step} that provides the second position of the area of the structure, thus completing the
     * {@link Cuboid}.
     * <p>
     * Don't forget to set the message before using it!
     */
    protected final Step.Factory factoryProvideSecondPos;

    /**
     * Factory for the {@link Step} that provides the position of the structure's rotation point.
     * <p>
     * Don't forget to set the message before using it!
     */
    protected final Step.Factory factoryProvideRotationPointPos;

    /**
     * Factory for the {@link Step} in which the user selects the individual blocks of the structure with the wand.
     */
    protected final Step.Factory factorySelectBlocks;

    /**
     * Factory for the {@link Step} that provides the position of the structure's power block.
     */
    protected final Step.Factory factoryProvidePowerBlockPos;

    /**
     * Factory for the {@link Step} that provides the open status of the structure.
     */
    protected final Step.Factory factoryProvideOpenStatus;

    /**
     * Factory for the {@link Step} that provides the open direction of the structure.
     */
    protected final Step.Factory factoryProvideOpenDir;

    /**
     * Factory for the {@link Step} that allows the player to confirm or reject the price of the structure.
     */
    protected final Step.Factory factoryConfirmPrice;

    /**
     * Factory for the {@link Step} that allows players to review the created structure
     */
    protected final Step.Factory factoryReviewResult;

    /**
     * Factory for the {@link Step} that completes this process.
     * <p>
     * Don't forget to set the message before using it!
     */
    protected final Step.Factory factoryCompleteProcess;

    /**
     * The type of structure this creator will create.
     *
     * @return The type of structure that will be created.
     */
    @Getter
    protected final StructureType structureType;

    protected Creator(ToolUser.Context context, StructureType structureType, IPlayer player, @Nullable String name)
    {
        super(context, player);

        this.structureType = structureType;
        this.propertyContainer = PropertyContainer.forType(structureType);

        this.structureAnimationRequestBuilder = context.getStructureAnimationRequestBuilder();
        this.structureActivityManager = context.getStructureActivityManager();
        this.highlightedBlockSpawner = context.getHighlightedBlockSpawner();
        this.executor = context.getExecutor();
        this.limitsManager = context.getLimitsManager();
        this.structureBuilder = context.getStructureBuilder();
        this.databaseManager = context.getDatabaseManager();
        this.economyManager = context.getEconomyManager();
        this.commandFactory = context.getCommandFactory();

        this.name = name;

        player.sendMessage(textFactory.newText().append(
            localizer.getMessage("creator.base.init"),
            TextType.INFO,
            arg -> arg.clickable(
                "/rcdoors cancel", TextType.CLICKABLE_REFUSE, "/rcdoors cancel"))
        );

        factoryProvideName = stepFactory
            .stepName("SET_NAME")
            .stepExecutor(new StepExecutorString(this::completeNamingStep))
            .propertyName(localizer.getMessage("creator.base.property.type"))
            .propertyValueSupplier(this::getName)
            .updatable(true)
            .textSupplier(text -> text.append(
                localizer.getMessage("creator.base.give_name"),
                TextType.SUCCESS,
                getStructureArg(),
                // Clicking the command puts it in the player's chat box, so they only have to type the name itself.
                arg -> arg.suggestCommand(
                    localizer.getMessage("creator.base.give_name.command"),
                    "/rcdoors setname ",
                    localizer.getMessage("creator.base.give_name.hint"))));

        factoryProvideFirstPos = stepFactory
            .stepName("SET_FIRST_POS")
            .stepExecutor(new AsyncStepExecutor<>(ILocation.class, this::provideFirstPos));

        factoryProvideSecondPos = stepFactory
            .stepName("SET_SECOND_POS")
            .propertyName(localizer.getMessage("creator.base.property.cuboid"))
            .propertyValueSupplier(() ->
            {
                final @Nullable Cuboid cuboid0 = getCuboid();
                return cuboid0 == null ? "[]" :
                    String.format("[%s; %s]", formatVector(cuboid0.getMin()), formatVector(cuboid0.getMax()));
            })
            .stepExecutor(new AsyncStepExecutor<>(ILocation.class, this::provideSecondPos));

        factoryProvideRotationPointPos = stepFactory
            .stepName("SET_ROTATION_POINT")
            .propertyName(localizer.getMessage("creator.base.property.rotation_point"))
            .propertyValueSupplier(() -> formatVector(getRequiredProperty(Property.ROTATION_POINT)))
            .updatable(true)
            .stepExecutor(new StepExecutorLocation(this::completeSetRotationPointStep));

        factorySelectBlocks = stepFactory
            .stepName("SELECT_BLOCKS")
            // Clicks on blocks return false, so only the 'done' action moves the process to the next step.
            .stepExecutor(new StepExecutorBlockSelection(
                this::handleBlockSelectionClick,
                this::handleBlockSelectionAction))
            .propertyName(localizer.getMessage("creator.base.property.selected_blocks"))
            .propertyValueSupplier(this::describeBlockSelection)
            .textSupplier(this::selectBlocksTextSupplier);

        factoryProvidePowerBlockPos = stepFactory
            .stepName("SET_POWER_BLOCK_POS")
            .messageKey("creator.base.set_power_block")
            .propertyName(localizer.getMessage("creator.base.property.power_block_position"))
            .propertyValueSupplier(() -> formatVector(getPowerBlock()))
            .updatable(true)
            .stepExecutor(new AsyncStepExecutor<>(ILocation.class, this::completeSetPowerBlockStep));

        factoryProvideOpenStatus = stepFactory
            .stepName("SET_OPEN_STATUS")
            .stepExecutor(new StepExecutorBoolean(this::completeSetOpenStatusStep))
            .stepPreparation(this::prepareSetOpenStatus)
            .propertyName(localizer.getMessage("creator.base.property.open_status"))
            .propertyValueSupplier(
                () -> getRequiredProperty(Property.OPEN_STATUS) ?
                    localizer.getMessage("constants.open_status.open") :
                    localizer.getMessage("constants.open_status.closed"))
            .updatable(true)
            .textSupplier(this::setOpenStatusTextSupplier);

        factoryProvideOpenDir = stepFactory
            .stepName("SET_OPEN_DIRECTION")
            .stepExecutor(new StepExecutorOpenDirection(this::completeSetOpenDirStep))
            .stepPreparation(this::prepareSetOpenDirection)
            .propertyName(localizer.getMessage("creator.base.property.open_direction"))
            .propertyValueSupplier(() ->
            {
                final @Nullable MovementDirection openDir0 = getMovementDirection();
                return openDir0 == null ? "NULL" : localizer.getMessage(openDir0.getLocalizationKey());
            })
            .updatable(true)
            .textSupplier(this::setOpenDirectionTextSupplier);

        factoryReviewResult = stepFactory
            .stepName("REVIEW_RESULT")
            .stepExecutor(new StepExecutorBoolean(ignored -> true))
            .stepPreparation(this::prepareReviewResult)
            .textSupplier(this::reviewResultTextSupplier);

        factoryConfirmPrice = stepFactory
            .stepName("CONFIRM_STRUCTURE_PRICE")
            .stepExecutor(new StepExecutorBoolean(this::confirmPrice))
            .skipCondition(this::skipConfirmPrice)
            .textSupplier(this::confirmPriceTextSupplier)
            .implicitNextStep(false);

        factoryCompleteProcess = stepFactory
            .stepName("COMPLETE_CREATION_PROCESS")
            .stepExecutor(new StepExecutorVoid(this::completeCreationProcess))
            .textSupplier(text -> text.append(
                localizer.getMessage("creator.base.success"), TextType.SUCCESS, getStructureArg()))
            .waitForUserInput(false);
    }

    @Override
    protected synchronized void init()
    {
        super.init();

        if (name == null || !handleInput(name).join())
            runWithLock(this::prepareCurrentStep).join();
    }

    /**
     * Sets a property of the structure.
     *
     * @param property
     *     The property to set.
     * @param value
     *     The value to set the property to.
     * @param <T>
     *     The type of the property.
     * @throws IllegalArgumentException
     *     If the property is not valid for the structure type this property container was created for.
     */
    protected synchronized final <T> void setProperty(Property<T> property, T value)
    {
        propertyContainer.setPropertyValue(property, value);
    }

    /**
     * Gets a property of the structure.
     *
     * @param property
     *     The property to get.
     * @param <T>
     *     The type of the property.
     * @return The value of the property.
     */
    protected synchronized final <T> @Nullable T getProperty(Property<T> property)
    {
        return propertyContainer.getPropertyValue(property).value();
    }

    /**
     * Gets a required property of the structure.
     *
     * @param property
     *     The property to get.
     * @param <T>
     *     The type of the property.
     * @return The value of the property.
     *
     * @throws NullPointerException
     *     If the property is not set.
     */
    protected synchronized final <T> T getRequiredProperty(Property<T> property)
    {
        return Util.requireNonNull(getProperty(property), property.getFullKey());
    }

    /**
     * Shortcut method for creating a new highlighted argument of the structure type.
     * <p>
     * Can be used for Text object.
     *
     * @return The function that creates a new structure arg.
     */
    protected final Function<TextArgumentFactory, TextArgument> getStructureArg()
    {
        return arg -> arg.highlight(localizer.getStructureType(getStructureType()));
    }

    /**
     * Updates a step with a given name and value.
     *
     * @param stepName
     *     The name of the step to update.
     * @param stepValue
     *     The value to update the step with.
     * @return A {@link CompletableFuture} that completes when the step has been updated.
     *
     * @throws IllegalStateException
     *     If the process is not in an updatable state.
     */
    public final synchronized CompletableFuture<Boolean> update(String stepName, @Nullable Object stepValue)
    {
        if (!processIsUpdatable)
            throw new IllegalStateException(
                "Trying to update step " + stepName + " with value " + stepValue +
                    " while the process is not in an updatable state!");

        // An open editing session keeps the process updatable across multiple calls; without one, the original
        // single-shot behaviour of the chat-driven review step applies.
        processIsUpdatable = editingSessionDepth > 0;

        return runWithLock(() ->
        {
            insertStep(stepName);
            return prepareCurrentStep();
        }).exceptionally(ex ->
        {
            log.atSevere().withCause(ex).log(
                "Failed to handle updated step '%s' with value '%s' in Creator '%s'", stepName, stepValue, this);
            return false;
        });
    }

    /**
     * Prepares the step that sets the open status.
     */
    protected void prepareSetOpenStatus()
    {
        commandFactory
            .getSetOpenStatusDelayed()
            .runDelayed(getPlayer(), this, status -> CompletableFuture.completedFuture(handleInput(status)), null)
            .exceptionally(FutureUtil::exceptionally);
    }

    /**
     * Prepares the step that sets the open direction.
     */
    protected void prepareSetOpenDirection()
    {
        commandFactory
            .getSetOpenDirectionDelayed()
            .runDelayed(getPlayer(), this, this::handleInput, null)
            .exceptionally(FutureUtil::exceptionally);
    }

    /**
     * Constructs the {@link Structure} for the current structure. This is the same for all structures.
     *
     * @param component
     *     The component to use for the structure. Leave null to use the default component.
     * @return The {@link Structure} for the current structure.
     */
    protected final synchronized Structure constructStructureData(@Nullable IStructureComponent component)
    {
        final var owner =
            new StructureOwner(structureID.getId(), PermissionLevel.CREATOR, getPlayer().getPlayerData());

        return structureBuilder
            .builder(structureType, component)
            .uid(structureID)
            .name(Util.requireNonNull(name, "Name"))
            .cuboid(Util.requireNonNull(cuboid, "cuboid"))
            .powerBlock(Util.requireNonNull(powerblock, "powerblock"))
            .world(Util.requireNonNull(world, "world"))
            .isLocked(isLocked)
            .openDir(Util.requireNonNull(movementDirection, "openDir"))
            .primeOwner(owner)
            .ownersOfStructure(null)
            .propertiesOfStructure(propertyContainer)
            .build();
    }

    protected synchronized void showPreview()
    {
        if (structureAnimationRequestBuilder == null)
        {
            log.atWarning().log("No StructureAnimationRequestBuilder available for Creator '%s'", this);
            return;
        }

        structureAnimationRequestBuilder
            .builder()
            .structure(constructStructure())
            .structureActionCause(StructureActionCause.PLUGIN)
            .structureActionType(StructureActionType.TOGGLE)
            .animationType(AnimationType.PREVIEW)
            .messageReceiver(getPlayer())
            .responsible(getPlayer())
            .build()
            .execute()
            .exceptionally(FutureUtil::exceptionally);
    }

    protected synchronized void prepareReviewResult()
    {
        try
        {
            showPreview();
        }
        catch (Exception e)
        {
            log.atSevere().withCause(e).log("Failed to create structure preview!");
            getPlayer().sendMessage(
                textFactory.newText().append(localizer.getMessage("constants.error.generic"), TextType.ERROR));
        }
        this.processIsUpdatable = true;
    }

    @Override
    public void abort()
    {
        clearSelectionHighlights();
        super.abort();
    }

    /**
     * Completes the creation process. It'll construct and insert the structure and complete the {@link ToolUser}
     * process.
     *
     * @return True, so that it fits the functional interface being used for the steps.
     * <p>
     * If the insertion fails for whatever reason, it'll just be ignored, because at that point, there's no sense in
     * continuing the creation process anyway.
     */
    protected synchronized boolean completeCreationProcess()
    {
        removeTool();
        clearSelectionHighlights();
        if (super.isActive())
            insertStructure(constructStructure());
        structureActivityManager.stopAnimators(this.structureID.getId());
        return true;
    }

    private synchronized void giveTool0()
    {
        if (!super.setPlayerHasTool(true))
            giveTool();
    }

    /**
     * Adds the AnimatedArchitecture tool from the player's inventory.
     *
     * @param nameKey
     *     The localization key of the name of the tool.
     * @param loreKey
     *     The localization key of the lore of the tool.
     */
    protected final void giveTool(String nameKey, String loreKey)
    {
        super.giveTool(
            nameKey, loreKey, textFactory.newText().append(
                localizer.getMessage("creator.base.received_tool"), TextType.INFO, getStructureArg()));
    }

    /**
     * Method used to give the AnimatedArchitecture tool to the user.
     * <p>
     * Overriding methods may call {@link #giveTool(String, String, Text)}.
     */
    protected abstract void giveTool();

    /**
     * Completes the naming step for this {@link Creator}. This means that it'll set the name, go to the next step, and
     * give the user the creator tool.
     * <p>
     * Note that there are some requirements that the name must meet. See
     * {@link StringUtil#isValidStructureName(String)}.
     *
     * @param str
     *     The desired name of the structure.
     * @return True if the naming step was finished successfully.
     */
    protected synchronized boolean completeNamingStep(String str)
    {
        if (!StringUtil.isValidStructureName(str))
        {
            log.atFine().log("Invalid name '%s' for selected Creator: %s", str, this);
            getPlayer().sendMessage(textFactory.newText().append(
                localizer.getMessage("creator.base.error.invalid_name"),
                TextType.ERROR,
                arg -> arg.highlight(str),
                arg -> arg.highlight(localizer.getStructureType(getStructureType())))
            );
            return false;
        }

        name = str;
        giveTool0();
        return true;
    }

    /**
     * Provides the first location of the selection and advances the procedure if successful.
     *
     * @param loc
     *     The first location of the cuboid.
     * @return True if setting the location was successful.
     */
    protected CompletableFuture<Boolean> provideFirstPos(ILocation loc)
    {
        return playerHasAccessToLocation(loc)
            .thenApply(isAllowed ->
            {
                if (!isAllowed)
                    return false;

                synchronized (this)
                {
                    world = loc.getWorld();
                    firstPos = loc.getPosition();
                }
                return true;
            });
    }

    /**
     * Creates a new {@link Cuboid} with the first position and the combined with position.
     * <p>
     * If the created {@link Cuboid} exceeds the size limit, the player will be informed and the result will be empty.
     *
     * @param combinedWith
     *     The position to combine with the first position.
     * @return The newly-created {@link Cuboid} if the volume is within the limits, otherwise an empty {@link Optional}.
     */
    protected final synchronized Optional<Cuboid> createCuboid(Vector3Di combinedWith)
    {
        final Cuboid newCuboid = new Cuboid(
            Util.requireNonNull(firstPos, "firstPos"),
            combinedWith);

        final OptionalInt sizeLimit = limitsManager.getLimit(getPlayer(), Limit.STRUCTURE_SIZE);
        if (sizeLimit.isPresent() && newCuboid.getVolume() > sizeLimit.getAsInt())
        {
            getPlayer().sendMessage(textFactory.newText().append(
                localizer.getMessage("creator.base.error.area_too_big"),
                TextType.ERROR,
                arg -> arg.highlight(localizer.getStructureType(getStructureType())),
                arg -> arg.highlight(newCuboid.getVolume()),
                arg -> arg.highlight(sizeLimit.getAsInt()))
            );
            return Optional.empty();
        }
        return Optional.of(newCuboid);
    }

    /**
     * Provides the second location of the selection and advances the procedure if successful.
     *
     * @param loc
     *     The second location of the cuboid.
     * @return True if setting the location was successful.
     */
    protected CompletableFuture<Boolean> provideSecondPos(ILocation loc)
    {
        if (!verifyWorldMatch(loc.getWorld()))
            return CompletableFuture.completedFuture(false);

        return playerHasAccessToLocation(loc)
            .<Optional<Cuboid>>thenApply(isAllowed ->
            {
                if (!isAllowed)
                    return Optional.empty();
                return createCuboid(loc.getPosition());
            })
            .thenCompose(cuboidOpt ->
                cuboidOpt
                    .map(newCuboid -> playerHasAccessToCuboid(newCuboid, Util.requireNonNull(getWorld(), "world")))
                    .orElse(CompletableFuture.completedFuture(Optional.empty())))
            .thenApply(newCuboid ->
            {
                if (newCuboid.isEmpty())
                    return false;

                synchronized (this)
                {
                    cuboid = newCuboid.get();
                }
                return true;
            });
    }

    /**
     * The instructions for the block selection step.
     * <p>
     * Besides explaining both mouse buttons, this lists the actions the player can click instead of having to type a
     * command for them.
     */
    protected Text selectBlocksTextSupplier(Text text)
    {
        return text
            .append(
                localizer.getMessage("creator.base.select_blocks"),
                TextType.INFO,
                arg -> arg.highlight(localizer.getStructureType(getStructureType())))
            .append("\n")
            .append(
                localizer.getMessage("creator.base.select_blocks.actions"),
                TextType.INFO,
                arg -> selectionActionArgument(arg, BlockSelectionAction.DONE),
                arg -> selectionActionArgument(arg, BlockSelectionAction.FILL),
                arg -> selectionActionArgument(arg, BlockSelectionAction.UNDO),
                arg -> selectionActionArgument(arg, BlockSelectionAction.CLEAR));
    }

    private TextArgument selectionActionArgument(TextArgumentFactory arg, BlockSelectionAction action)
    {
        final String key = "creator.base.select_blocks.action." + action.getCommandName();
        return arg.clickable(
            localizer.getMessage(key),
            "/rcdoors selectblocks " + action.getCommandName(),
            localizer.getMessage(key + ".hint"));
    }

    /**
     * Describes the current selection for the review step, e.g. "42 blocks".
     */
    private synchronized String describeBlockSelection()
    {
        if (blockSelection.isEmpty())
            return localizer.getMessage("creator.base.property.selected_blocks.whole_region");
        return String.valueOf(blockSelection.size());
    }

    /**
     * Handles a single click with the wand during the block selection step.
     * <p>
     * Left-clicking a block adds it to the selection, right-clicking it removes it again. The step is never completed
     * by a click: the player decides when they are done. See {@link #handleBlockSelectionAction(BlockSelectionAction)}.
     *
     * @param toolClick
     *     The click to handle.
     * @return False, as a click never completes this step.
     */
    protected CompletableFuture<Boolean> handleBlockSelectionClick(ToolClick toolClick)
    {
        final ILocation loc = toolClick.location();

        if (!verifyWorldMatch(loc.getWorld()))
            return CompletableFuture.completedFuture(false);

        final Vector3Di position = loc.getPosition();
        final @Nullable Cuboid currentCuboid = getCuboid();

        if (currentCuboid == null || !currentCuboid.isPosInsideCuboid(position))
        {
            getPlayer().sendMessage(textFactory.newText().append(
                localizer.getMessage("creator.base.error.block_outside_region"),
                TextType.ERROR)
            );
            return CompletableFuture.completedFuture(false);
        }

        if (toolClick.isLeftClick())
            selectBlock(position);
        else
            deselectBlock(position);

        sendSelectionStatus();
        return CompletableFuture.completedFuture(false);
    }

    /**
     * Handles one of the actions the player can click during the block selection step.
     *
     * @param action
     *     The action to handle.
     * @return True if the action was handled successfully.
     */
    protected CompletableFuture<Boolean> handleBlockSelectionAction(BlockSelectionAction action)
    {
        switch (action)
        {
            case FILL ->
            {
                fillSelection();
                sendSelectionStatus();
            }
            case UNDO ->
            {
                undoSelection();
                sendSelectionStatus();
            }
            case CLEAR ->
            {
                clearSelection();
                sendSelectionStatus();
            }
            case DONE ->
            {
                return CompletableFuture.completedFuture(completeBlockSelectionStep());
            }
        }
        return CompletableFuture.completedFuture(false);
    }

    /**
     * Applies an action of the block selection step.
     * <p>
     * This is the entry point used by the {@code selectblocks} command: it takes the input lock and, when the player is
     * done selecting, advances the procedure to the next step.
     *
     * @param action
     *     The action to apply.
     * @return A future that completes once the action has been handled.
     */
    public final CompletableFuture<Boolean> applyBlockSelectionAction(BlockSelectionAction action)
    {
        return handleInput(action);
    }

    /**
     * Finishes the block selection step.
     * <p>
     * An empty selection means the structure consists of every block in its cuboid, which is also the case for a
     * selection that happens to contain every block of the cuboid. In both cases no mask is stored, so these structures
     * behave exactly like structures created before the wand existed.
     *
     * @return True if the step was completed.
     */
    private synchronized boolean completeBlockSelectionStep()
    {
        if (!blockSelection.isEmpty())
        {
            final BlockSelection selection = blockSelection.build();
            setProperty(Property.BLOCK_MASK, BlockSelection.isFullCuboid(selection) ? null : selection);
        }
        clearSelectionHighlights();
        return true;
    }

    private synchronized void selectBlock(Vector3Di position)
    {
        if (!blockSelection.add(position))
            return;
        highlightBlock(position);
    }

    private synchronized void deselectBlock(Vector3Di position)
    {
        if (!blockSelection.remove(position))
            return;
        removeHighlight(position);
    }

    private synchronized void fillSelection()
    {
        final @Nullable Cuboid currentCuboid = getCuboid();
        if (currentCuboid == null)
            return;

        final Vector3Di min = currentCuboid.getMin();
        final Vector3Di max = currentCuboid.getMax();

        for (int x = min.x(); x <= max.x(); ++x)
            for (int y = min.y(); y <= max.y(); ++y)
                for (int z = min.z(); z <= max.z(); ++z)
                {
                    final Vector3Di position = new Vector3Di(x, y, z);
                    if (blockSelection.add(position))
                        highlightBlock(position);
                }
    }

    private synchronized void undoSelection()
    {
        blockSelection
            .undo()
            .ifPresent(position ->
            {
                if (blockSelection.contains(position))
                    highlightBlock(position);
                else
                    removeHighlight(position);
            });
    }

    private synchronized void clearSelection()
    {
        blockSelection.clear();
        clearSelectionHighlights();
    }

    /**
     * Sends the player a short summary of their selection, so that they can see the effect of every click without
     * having to count blocks themselves.
     */
    private synchronized void sendSelectionStatus()
    {
        getPlayer().sendMessage(textFactory.newText().append(
            localizer.getMessage("creator.base.select_blocks.status"),
            TextType.INFO,
            arg -> arg.highlight(blockSelection.size()),
            arg -> arg.highlight(
                blockSelection
                    .getBoundingCuboid()
                    .map(Cuboid::getVolume)
                    .orElseGet(() -> Optional.ofNullable(getCuboid()).map(Cuboid::getVolume).orElse(0))))
        );
    }

    /**
     * Highlights a selected block for the player, so that they can see their selection take shape while they are
     * making it.
     */
    private synchronized void highlightBlock(Vector3Di position)
    {
        final @Nullable IWorld currentWorld = getWorld();
        if (currentWorld == null ||
            selectionHighlights.containsKey(position) ||
            selectionHighlights.size() >= MAX_HIGHLIGHTED_BLOCKS)
            return;

        // Highlighted blocks are entities, so they can only be spawned on the main thread.
        executor.runOnMainThread(() -> highlightedBlockSpawner
            .builder()
            .forPlayer(getPlayer())
            .inWorld(currentWorld)
            .atPosition(position.x() + 0.5, position.y(), position.z() + 0.5)
            .withColor(Color.GREEN)
            .spawn()
            .ifPresent(highlightedBlock -> addHighlight(position, highlightedBlock)));
    }

    /**
     * Registers a highlighted block that was spawned for the given position.
     * <p>
     * The block is killed right away when the position was deselected again while it was being spawned.
     */
    private synchronized void addHighlight(Vector3Di position, IHighlightedBlock highlightedBlock)
    {
        if (blockSelection.contains(position) && selectionHighlights.putIfAbsent(position, highlightedBlock) == null)
            return;
        highlightedBlock.kill();
    }

    private synchronized void removeHighlight(Vector3Di position)
    {
        final @Nullable IHighlightedBlock highlightedBlock = selectionHighlights.remove(position);
        if (highlightedBlock != null)
            executor.runOnMainThread(highlightedBlock::kill);
    }

    /**
     * Removes every highlighted block that was spawned for the block selection step.
     */
    protected final synchronized void clearSelectionHighlights()
    {
        if (selectionHighlights.isEmpty())
            return;

        final List<IHighlightedBlock> highlightedBlocks = List.copyOf(selectionHighlights.values());
        selectionHighlights.clear();
        executor.runOnMainThread(() -> highlightedBlocks.forEach(IHighlightedBlock::kill));
    }

    /**
     * Attempts to buy the structure for the player and advances the procedure if successful.
     * <p>
     * Note that if the player does not end up buying the structure, either because of insufficient funds or because
     * they rejected the offer, the current step is NOT advanced!
     *
     * @param confirm
     *     Whether the player confirmed they want to buy this structure.
     * @return Always returns true, because either they can and do buy the structure, or they cannot or refuse to buy
     * the structure and the process is aborted.
     */
    // This method always returns the same value (S3516). However, in the case of this method, there is no reason to
    // return false as every input is valid and leads to a valid state.
    @SuppressWarnings("squid:S3516")
    protected synchronized boolean confirmPrice(boolean confirm)
    {
        if (!confirm)
        {
            getPlayer().sendMessage(
                textFactory, TextType.INFO,
                localizer.getMessage("creator.base.error.creation_cancelled"));
            abort();
            return true;
        }
        if (!buyStructure())
        {
            getPlayer().sendMessage(textFactory.newText().append(
                localizer.getMessage("creator.base.error.insufficient_funds"),
                TextType.ERROR,
                arg -> arg.highlight(localizer.getStructureType(getStructureType())),
                arg -> arg.highlight(getPrice().orElse(0)))
            );
            abort();
            return true;
        }

        goToNextStep();
        return true;
    }

    /**
     * Attempts to complete the step that provides the value of the {@link Property#OPEN_STATUS}.
     *
     * @param isOpen
     *     True if the current status of the structure is open.
     * @return True if the open status was set successfully.
     */
    protected synchronized boolean completeSetOpenStatusStep(boolean isOpen)
    {
        setProperty(Property.OPEN_STATUS, isOpen);
        return true;
    }

    /**
     * Attempts to complete the step that provides the {@link #movementDirection}.
     * <p>
     * If the open direction is not valid for this type, nothing changes.
     *
     * @param direction
     *     The {@link MovementDirection} that was selected by the player.
     * @return True if the {@link #movementDirection} was set successfully.
     */
    protected synchronized boolean completeSetOpenDirStep(MovementDirection direction)
    {
        if (!getValidOpenDirections().contains(direction))
        {
            getPlayer().sendMessage(textFactory.newText().append(
                localizer.getMessage("creator.base.error.invalid_option"),
                TextType.ERROR,
                arg -> arg.highlight(localizer.getMessage(direction.getLocalizationKey())))
            );
            prepareSetOpenDirection();
            return false;
        }
        movementDirection = direction;
        return true;
    }

    /**
     * Constructs the structure at the end of the creation process.
     *
     * @return The newly-created structure.
     */
    protected Structure constructStructure()
    {
        return constructStructureData(null);
    }

    /**
     * Verifies that the world of the selected location matches the world that this structure is being created in.
     *
     * @param targetWorld
     *     The world to check.
     * @return True if the world is the same world this structure is being created in.
     */
    protected synchronized boolean verifyWorldMatch(IWorld targetWorld)
    {
        if (Util.requireNonNull(world, "world").worldName().equals(targetWorld.worldName()))
            return true;

        getPlayer().sendError(
            textFactory, localizer.getMessage("creator.base.error.world_mismatch"));

        log.atFine().log("World mismatch in ToolUser for player: %s", getPlayer());
        return false;
    }

    /**
     * Takes care of inserting the structure.
     *
     * @param structure
     *     The structure to send to the {@link DatabaseManager}.
     */
    protected void insertStructure(Structure structure)
    {
        databaseManager
            .addStructure(structure, getPlayer())
            .thenAccept(result ->
            {
                if (result.cancelled())
                {
                    getPlayer().sendError(
                        textFactory, localizer.getMessage("creator.base.error.creation_cancelled"));
                    return;
                }

                if (result.structure().isEmpty())
                {
                    getPlayer().sendError(textFactory, localizer.getMessage("constants.error.generic"));
                    log.atSevere().log("Failed to insert structure after creation!");
                }
            }).exceptionally(FutureUtil::exceptionally);
    }

    /**
     * Attempts to buy the structure for the current player.
     *
     * @return True if the player has bought the structure or if the economy is not enabled.
     */
    protected synchronized boolean buyStructure()
    {
        if (!economyManager.isEconomyEnabled())
            return true;

        return economyManager.buyStructure(
            getPlayer(),
            Util.requireNonNull(world, "world"),
            getStructureType(),
            Util.requireNonNull(cuboid, "cuboid").getVolume()
        );
    }

    /**
     * Gets the price of the structure based on its volume. If the structure is free because the price is &lt;= 0 or the
     * {@link IEconomyManager} is disabled, the price will be empty.
     *
     * @return The price of the structure if a positive price could be found.
     */
    protected synchronized OptionalDouble getPrice()
    {
        if (!economyManager.isEconomyEnabled())
            return OptionalDouble.empty();
        return economyManager.getPrice(getStructureType(), Util.requireNonNull(cuboid, "cuboid").getVolume());
    }

    /**
     * Checks if the step that asks the user to confirm that they want to buy the structure should be skipped.
     * <p>
     * It should be skipped if the structure is free for whatever reason. See {@link #getPrice()}.
     *
     * @return True if the step that asks the user to confirm that they want to buy the structure should be skipped.
     */
    protected boolean skipConfirmPrice()
    {
        return getPrice().isEmpty();
    }

    /**
     * Gets the list of valid open directions for this type. It returns a subset of
     * {@link StructureType#getValidMovementDirections()} based on the current physical aspects of the
     * {@link Structure}.
     *
     * @return The list of valid open directions for this type given its current physical dimensions.
     */
    public Set<MovementDirection> getValidOpenDirections()
    {
        return getStructureType().getValidMovementDirections();
    }

    /**
     * Checks if the power block is within the range limit.
     * <p>
     * If the power block is too far away, the player will be informed and the method will return false.
     * <p>
     * If the distance limit is not set, the method will return true.
     * <p>
     * If the power block is inside the structure, the player will be informed and the method will return false.
     *
     * @param pos
     *     The position of the power block.
     * @param cuboid
     *     The cuboid that defines the structure.
     * @return True if the power block is within the range limit.
     */
    protected final boolean isPowerBlockWithinRangeLimit(Vector3Di pos, Cuboid cuboid)
    {
        final int distance = cuboid.getDistanceToPoint(pos);
        if (distance == -1)
        {
            getPlayer().sendMessage(textFactory.newText().append(
                "creator.base.error.powerblock_inside_structure",
                TextType.ERROR,
                arg -> arg.highlight(localizer.getStructureType(getStructureType())))
            );
            return false;
        }

        final OptionalInt distanceLimit = limitsManager.getLimit(getPlayer(), Limit.POWERBLOCK_DISTANCE);
        if (distanceLimit.isEmpty() || distance <= distanceLimit.getAsInt())
            return true;

        getPlayer().sendMessage(textFactory.newText().append(
            "creator.base.error.powerblock_too_far",
            TextType.ERROR,
            arg -> arg.highlight(localizer.getStructureType(getStructureType())),
            arg -> arg.highlight(distance),
            arg -> arg.highlight(distanceLimit.getAsInt()))
        );

        return false;
    }

    /**
     * Attempts to complete the step in the {@link Procedure} that sets the second position of the {@link Structure}
     * that is being created.
     *
     * @param loc
     *     The selected location of the rotation point.
     * @return True if the location of the area was set successfully.
     */
    protected synchronized CompletableFuture<Boolean> completeSetPowerBlockStep(ILocation loc)
    {
        if (!verifyWorldMatch(loc.getWorld()))
            return CompletableFuture.completedFuture(false);

        final Vector3Di pos = loc.getPosition();

        if (!isPowerBlockWithinRangeLimit(pos, Util.requireNonNull(cuboid, "cuboid")))
            return CompletableFuture.completedFuture(false);

        return playerHasAccessToLocation(loc)
            .thenApply(isAllowed ->
            {
                if (!isAllowed)
                    return false;
                setPowerblock(pos);
                return true;
            });
    }

    /**
     * Attempts to complete the step in the {@link Procedure} that sets the location of the rotation point for the
     * {@link Structure} that is being created.
     *
     * @param loc
     *     The selected location of the rotation point.
     * @return True if the location of the rotation point was set successfully.
     */
    protected synchronized boolean completeSetRotationPointStep(ILocation loc)
    {
        if (!verifyWorldMatch(loc.getWorld()))
            return false;

        if (!Util.requireNonNull(cuboid, "cuboid").isInRange(loc, 1))
        {
            log.atFinest().log("Rotation point not in range of cuboid for player: %s", getPlayer());
            getPlayer().sendError(
                textFactory, localizer.getMessage("creator.base.error.invalid_rotation_point"));
            return false;
        }

        setProperty(Property.ROTATION_POINT, loc.getPosition());
        return true;
    }

    protected Text setOpenStatusTextSupplier(Text text)
    {
        return text.append(
            localizer.getMessage("creator.base.set_open_status"),
            TextType.INFO,

            arg -> arg.highlight(localizer.getStructureType(getStructureType())),

            arg -> arg.clickable(
                localizer.getMessage("constants.open_status.open"),
                "/rcdoors SetOpenStatus " + localizer.getMessage("constants.open_status.open"),
                localizer.getMessage("creator.base.set_open_status.arg2.open.hint")
            ),

            arg -> arg.clickable(
                localizer.getMessage("constants.open_status.closed"),
                "/rcdoors SetOpenStatus " + localizer.getMessage("constants.open_status.closed"),
                localizer.getMessage("creator.base.set_open_status.arg2.closed.hint"))
        );
    }

    protected Text setOpenDirectionTextSupplier(Text text)
    {
        text.append(
            localizer.getMessage("creator.base.set_open_direction") + "\n",
            TextType.INFO,
            arg -> arg.highlight(localizer.getStructureType(getStructureType()))
        );

        getValidOpenDirections()
            .stream()
            .map(dir -> localizer.getMessage(dir.getLocalizationKey()))
            .sorted()
            .forEach(dir -> text.appendClickableText(
                dir + "\n", TextType.CLICKABLE,
                "/rcdoors SetOpenDirection " + dir,
                localizer.getMessage("creator.base.set_open_direction.arg0.hint"))
            );

        return text;
    }

    private Text reviewResultTextSupplier(Text text)
    {
        text.append(localizer.getMessage("creator.base.review_result.header") + "\n", TextType.SUCCESS);
        text.append(
            localizer.getMessage("creator.base.property.type") + "\n",
            TextType.INFO,
            arg -> arg.highlight(localizer.getStructureType(getStructureType()))
        );

        for (final Step step : getAllSteps())
            step.getPropertyText(textFactory).ifPresent(property -> text.append(property).append('\n'));

        text.append(
            localizer.getMessage("creator.base.review_result.footer"),
            TextType.INFO,
            arg -> arg.clickable(
                localizer.getMessage("creator.base.review_result.footer.arg0.message"),
                TextType.CLICKABLE_CONFIRM,
                "/rcdoors confirm",
                localizer.getMessage("creator.base.review_result.footer.arg0.hint")),
            arg -> arg.clickable(
                localizer.getMessage("creator.base.review_result.footer.arg1.message"),
                TextType.CLICKABLE_REFUSE,
                "/rcdoors cancel",
                localizer.getMessage("creator.base.review_result.footer.arg1.hint"))
        );
        return text;
    }

    private Text confirmPriceTextSupplier(Text text)
    {
        return text.append(
            localizer.getMessage("creator.base.confirm_structure_price"),
            TextType.INFO,

            arg -> arg.info(localizer.getStructureType(getStructureType())),
            arg -> arg.highlight(getPrice().orElse(0)),

            arg -> arg.clickable(
                localizer.getMessage("creator.base.confirm_structure_price.arg2.message"),
                TextType.CLICKABLE_CONFIRM,
                "/rcdoors confirm",
                localizer.getMessage("creator.base.confirm_structure_price.arg2.hint")),

            arg -> arg.clickable(
                localizer.getMessage("creator.base.confirm_structure_price.arg3.message"),
                TextType.CLICKABLE_REFUSE,
                "/rcdoors cancel",
                localizer.getMessage("creator.base.confirm_structure_price.arg3.hint"))
        );
    }

    private String formatVector(@Nullable Vector3Di vector)
    {
        return vector == null ? "NULL" : String.format("%d, %d, %d", vector.x(), vector.y(), vector.z());
    }

    /**
     * Gets the name of the structure that is to be created.
     *
     * @return The name of the structure that is to be created.
     */
    protected final synchronized @Nullable String getName()
    {
        return name;
    }

    /**
     * Returns the cuboid of the structure that is to be created.
     *
     * @return The cuboid of the structure that is to be created or null if it has not been set yet.
     */
    protected final synchronized @Nullable Cuboid getCuboid()
    {
        return cuboid;
    }

    /**
     * Returns the movement direction of the structure that is to be created.
     *
     * @return The movement direction of the structure that is to be created or null if it has not been set yet.
     */
    protected final synchronized @Nullable MovementDirection getMovementDirection()
    {
        return movementDirection;
    }

    /**
     * Returns the location of the power block of the structure that is to be created.
     *
     * @return The power block of the structure that is to be created or null if it has not been set yet.
     */
    protected final synchronized @Nullable Vector3Di getPowerBlock()
    {
        return powerblock;
    }

    /**
     * Returns the first position of the structure that is to be created.
     * <p>
     * This is used together with a second position to construct {@link #cuboid}.
     *
     * @return The first position of the structure that is to be created or null if it has not been set yet.
     */
    @SuppressWarnings("unused") // It is used by the generated toString method.
    protected final synchronized @Nullable Vector3Di getFirstPos()
    {
        return this.firstPos;
    }

    /**
     * Returns the location of the power block of the structure that is to be created.
     *
     * @return The location of the power block of the structure that is to be created or null if it has not been set
     * yet.
     */
    @SuppressWarnings("unused") // It is used by the generated toString method.
    protected final synchronized @Nullable Vector3Di getPowerblock()
    {
        return this.powerblock;
    }

    /**
     * Returns the world in which the structure that is to be created is located.
     *
     * @return The world in which the structure that is to be created is located or null if it has not been set yet.
     */
    protected final synchronized @Nullable IWorld getWorld()
    {
        return this.world;
    }

    /**
     * Whether the structure that is being created is locked.
     *
     * @return True if the structure that is being created is locked, false otherwise.
     * <p>
     * This may not have been set yet, in which case it defaults to false.
     */
    protected final synchronized boolean isLocked()
    {
        return this.isLocked;
    }

    /**
     * Whether the process is in a state where is may be updated outside the normal execution order.
     *
     * @return True if the process is in a state where is may be updated outside the normal execution order, false
     * otherwise.
     */
    protected final synchronized boolean isProcessIsUpdatable()
    {
        return this.processIsUpdatable;
    }

    /**
     * Whether this process currently accepts out-of-order updates via {@link #update(String, Object)}.
     *
     * @return True if {@link #update(String, Object)} may be called right now.
     */
    public final synchronized boolean canUpdate()
    {
        return this.processIsUpdatable;
    }

    /**
     * Opens an editing session, allowing out-of-order updates until {@link #endEditingSession()} is called.
     * <p>
     * In the chat-driven flow, out-of-order updates are only permitted from the review step, and only once: the flag is
     * set in {@link #prepareReviewResult()} and cleared again by the first {@link #update(String, Object)} call. A
     * front-end that shows every step at once (such as an inventory wizard) needs updates to stay available for as long
     * as it is on screen, so it brackets its interaction with this method and {@link #endEditingSession()}.
     * <p>
     * This does not bypass the per-step locking in {@link #update(String, Object)}; it only keeps the process marked as
     * updatable between calls.
     */
    public final synchronized void beginEditingSession()
    {
        this.editingSessionDepth++;
        this.processIsUpdatable = true;
    }

    /**
     * Closes an editing session opened by {@link #beginEditingSession()}.
     * <p>
     * Sessions are counted, so nested calls are safe; the process only stops accepting out-of-order updates once every
     * opened session has been closed.
     */
    public final synchronized void endEditingSession()
    {
        if (this.editingSessionDepth > 0)
            this.editingSessionDepth--;

        if (this.editingSessionDepth == 0)
            this.processIsUpdatable = false;
    }

    /**
     * Sets the name of the structure that is to be created.
     * <p>
     * Note that this method sets it directly without any processing or validation. Use
     * {@link #provideSecondPos(ILocation)} to set it properly.
     *
     * @param cuboid
     *     The cuboid of the structure that is to be created.
     */
    protected final synchronized void setCuboid(Cuboid cuboid)
    {
        this.cuboid = cuboid;
    }

    /**
     * Sets the power block of the structure that is to be created.
     * <p>
     * Note that this method sets it directly without any processing or validation. Use
     * {@link #completeSetPowerBlockStep(ILocation)} to set it properly.
     *
     * @param powerblock
     *     The power block of the structure that is to be created.
     */
    protected final synchronized void setPowerblock(Vector3Di powerblock)
    {
        this.powerblock = powerblock;
    }

    /**
     * Sets the movement direction of the structure that is to be created.
     * <p>
     * Note that this method sets it directly without any processing or validation. Use
     * {@link #completeSetOpenDirStep(MovementDirection)} to set it properly.
     *
     * @param movementDirection
     *     The movement direction of the structure that is to be created.
     */
    protected final synchronized void setMovementDirection(MovementDirection movementDirection)
    {
        this.movementDirection = movementDirection;
    }

    /**
     * Sets the world in which the structure that is to be created is located.
     * <p>
     * Note that this method sets it directly without any processing or validation. Use
     * {@link #provideFirstPos(ILocation)} to set it properly.
     *
     * @param world
     *     The world in which the structure that is to be created is located.
     */
    protected final synchronized void setWorld(IWorld world)
    {
        this.world = world;
    }

    /**
     * Sets the first position of the structure that is to be created.
     * <p>
     * Note that this method sets it directly without any processing or validation. Use
     * {@link #provideFirstPos(ILocation)} to set it properly.
     *
     * @param firstPos
     *     The first position of the structure that is to be created.
     */
    protected final synchronized void setFirstPos(Vector3Di firstPos)
    {
        this.firstPos = firstPos;
    }

    /**
     * Sets the locked status of the structure that is to be created.
     * <p>
     * Note that this method sets it directly without any processing or validation. Use
     * {@link #completeSetOpenStatusStep(boolean)} to set it properly.
     *
     * @param locked
     *     True if the structure that is to be created is locked, false otherwise.
     */
    protected final synchronized void setLocked(boolean locked)
    {
        isLocked = locked;
    }

    /**
     * Gets the unregistered UID of the structure.
     * <p>
     * This is a unique ID that is used to identify the structure before it is registered in the database.
     *
     * @return The unregistered UID of the structure.
     */
    public final long getUnregisteredUID()
    {
        return structureID.getId();
    }
}
