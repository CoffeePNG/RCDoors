package nl.pim16aap2.animatedarchitecture.spigot.core.gui;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;
import de.themoep.inventorygui.GuiElementGroup;
import de.themoep.inventorygui.InventoryGui;
import de.themoep.inventorygui.StaticGuiElement;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.flogger.Flogger;
import nl.pim16aap2.animatedarchitecture.core.api.IExecutor;
import nl.pim16aap2.animatedarchitecture.core.localization.ILocalizer;
import nl.pim16aap2.animatedarchitecture.core.tooluser.Step;
import nl.pim16aap2.animatedarchitecture.core.tooluser.ToolUser;
import nl.pim16aap2.animatedarchitecture.core.tooluser.creator.Creator;
import nl.pim16aap2.animatedarchitecture.core.util.FutureUtil;
import nl.pim16aap2.animatedarchitecture.spigot.core.AnimatedArchitecturePlugin;
import nl.pim16aap2.animatedarchitecture.spigot.util.implementations.PlayerSpigot;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Gui page that drives an active {@link Creator} process.
 * <p>
 * The creation process is already a complete step machine; this page is a second front-end over it, alongside the
 * chat-driven flow. It shows every step at once, so a player can see what they have answered so far and jump back to
 * an earlier answer without restarting.
 * <p>
 * Most steps need an in-world click, which an open inventory cannot receive. Clicking a step therefore hands control
 * back to the world: the wizard closes, the player performs the action, and {@link ToolUser.IProcedureListener}
 * reports that the procedure advanced, at which point the wizard reopens. The wizard only reopens after a hand-off it
 * started itself, so it stays out of the way while a player works through the procedure in order.
 */
@Flogger
@ToString(onlyExplicitlyIncluded = true)
class WizardGui implements IGuiPage, ToolUser.IProcedureListener
{
    private static final ItemStack FILLER = new ItemStack(Material.GRAY_STAINED_GLASS_PANE, 1);

    /**
     * The step the process is waiting on right now.
     */
    private static final Material MATERIAL_CURRENT = Material.NETHER_STAR;

    /**
     * A step that has been answered and may be answered again.
     */
    private static final Material MATERIAL_COMPLETED = Material.LIME_DYE;

    /**
     * A step that has been answered but cannot be revisited.
     */
    private static final Material MATERIAL_LOCKED = Material.LIGHT_GRAY_DYE;

    /**
     * A step the process has not reached yet.
     */
    private static final Material MATERIAL_PENDING = Material.GRAY_DYE;

    private final AnimatedArchitecturePlugin plugin;
    private final ILocalizer localizer;
    private final IExecutor executor;

    @ToString.Include
    private final Creator creator;

    @Getter
    @ToString.Include
    private final PlayerSpigot inventoryHolder;

    /**
     * The gui that is currently on screen, or null while the player is performing an in-world action.
     */
    private @Nullable InventoryGui inventoryGui;

    /**
     * Whether the wizard closed itself to let the player act in the world.
     * <p>
     * While this is set, closing the inventory does not end the wizard, and the next procedure advance reopens it.
     */
    private boolean handingOff;

    /**
     * Whether this wizard has been torn down. Tear-down is idempotent, because it can be reached both from the player
     * closing the inventory and from the procedure shutting down.
     */
    private boolean closed;

    @AssistedInject
    WizardGui(
        AnimatedArchitecturePlugin plugin,
        ILocalizer localizer,
        IExecutor executor,
        @Assisted Creator creator,
        @Assisted PlayerSpigot inventoryHolder)
    {
        this.plugin = plugin;
        this.localizer = localizer;
        this.executor = executor;
        this.creator = creator;
        this.inventoryHolder = inventoryHolder;

        // The editing session is what lets the player answer more than one step out of order. Without it, Creator
        // only accepts a single out-of-order update, which is all the chat review step needs.
        creator.beginEditingSession();
        creator.addProcedureListener(this);

        open();
    }

    /**
     * Builds a gui for the current state of the process and shows it.
     */
    private void open()
    {
        executor.assertMainThread();
        if (closed)
            return;

        final InventoryGui gui = createGui();
        this.inventoryGui = gui;
        gui.show(inventoryHolder.getBukkitPlayer());
    }

    /**
     * The steps that make sense as a slot: the ones that set a value they can describe.
     * <p>
     * The rest of the procedure is either internal (reviewing the result, confirming the price, committing the
     * structure) or reported by another step: the first position has no value of its own, because the second position
     * reports the whole cuboid.
     */
    private List<Step> visibleSteps()
    {
        return creator.getAllSteps().stream().filter(Step::reportsProperty).toList();
    }

    private InventoryGui createGui()
    {
        final List<Step> steps = visibleSteps();

        // Row one holds the controls; the steps fill the rows below it.
        final String[] guiSetup = GuiUtil.fillLinesWithChar('s', steps.size(), "c       x");

        final InventoryGui gui = new InventoryGui(
            plugin,
            inventoryHolder.getBukkitPlayer(),
            localizer.getMessage(
                "gui.wizard_page.title",
                localizer.getStructureType(creator.getStructureType()),
                creator.getCurrentStepNumber(),
                creator.getStepCount()),
            guiSetup
        );

        gui.setFiller(FILLER);
        gui.setCloseAction(close ->
        {
            onInventoryClosed();
            return true;
        });

        addControls(gui);
        addSteps(gui, steps);

        return gui;
    }

    private void addControls(InventoryGui gui)
    {
        gui.addElement(new StaticGuiElement(
            'c',
            new ItemStack(Material.LIME_STAINED_GLASS_PANE),
            click ->
            {
                confirm();
                return true;
            },
            localizer.getMessage("gui.wizard_page.button.confirm")
        ));

        gui.addElement(new StaticGuiElement(
            'x',
            new ItemStack(Material.BARRIER),
            click ->
            {
                cancel();
                return true;
            },
            localizer.getMessage("gui.wizard_page.button.cancel")
        ));
    }

    private void addSteps(InventoryGui gui, List<Step> steps)
    {
        final @Nullable Step currentStep = creator.getCurrentStep().orElse(null);

        final GuiElementGroup group = new GuiElementGroup('s');
        for (final Step step : steps)
            group.addElement(createStepElement(step, step.equals(currentStep)));
        group.setFiller(FILLER);
        gui.addElement(group);
    }

    private StaticGuiElement createStepElement(Step step, boolean isCurrent)
    {
        final @Nullable Object value = step.getPropertyValue();
        final boolean answered = value != null;

        final Material material;
        if (isCurrent)
            material = MATERIAL_CURRENT;
        else if (!answered)
            material = MATERIAL_PENDING;
        else if (step.isUpdatable())
            material = MATERIAL_COMPLETED;
        else
            material = MATERIAL_LOCKED;

        return new StaticGuiElement(
            's',
            new ItemStack(material),
            click ->
            {
                handOffTo(step);
                return true;
            },
            describe(step, value, isCurrent)
        );
    }

    /**
     * Builds the lines shown for a step: what it sets, what it is set to, and whether it can be clicked.
     */
    private String[] describe(Step step, @Nullable Object value, boolean isCurrent)
    {
        // Only steps that report a property are rendered, so the name is always present. See visibleSteps().
        final String title = Objects.requireNonNull(step.getPropertyName(), "propertyName");

        final String state;
        if (isCurrent)
            state = localizer.getMessage("gui.wizard_page.step.current");
        else if (value != null)
            state = localizer.getMessage("gui.wizard_page.step.value", String.valueOf(value));
        else
            state = localizer.getMessage("gui.wizard_page.step.pending");

        final String hint = step.isUpdatable() ?
            localizer.getMessage("gui.wizard_page.step.click_to_change") :
            localizer.getMessage("gui.wizard_page.step.not_changeable");

        return new String[]{title, state, hint};
    }

    /**
     * Closes the wizard so that the player can answer a step in the world, and points the process at that step.
     * <p>
     * The gui is closed before the process is moved, because moving it can complete the step immediately and ask the
     * wizard to redraw.
     */
    private void handOffTo(Step step)
    {
        executor.assertMainThread();

        if (!step.isUpdatable() || !creator.canUpdate())
            return;

        handingOff = true;
        closeGui();

        creator
            .update(step.getName(), null)
            .exceptionally(throwable ->
            {
                log.atSevere().withCause(throwable).log(
                    "Failed to move creator process to step '%s' for player %s.",
                    step.getName(),
                    inventoryHolder.asString());
                return false;
            });
    }

    private void confirm()
    {
        executor.assertMainThread();
        handingOff = false;
        closeGui();
        creator.handleInput(true).exceptionally(FutureUtil::exceptionally);
    }

    private void cancel()
    {
        executor.assertMainThread();
        handingOff = false;
        closeGui();
        creator.abort();
    }

    private void closeGui()
    {
        executor.assertMainThread();
        final @Nullable InventoryGui gui = this.inventoryGui;
        this.inventoryGui = null;
        if (gui != null)
            gui.close(false);
    }

    /**
     * Called when the inventory is closed, whether by the player or by the wizard itself.
     */
    private void onInventoryClosed()
    {
        // A hand-off closes the inventory on purpose and expects to come back, so it is not the player walking away.
        if (handingOff)
            return;
        tearDown();
    }

    /**
     * Detaches the wizard from the process. Safe to call more than once.
     */
    private void tearDown()
    {
        if (closed)
            return;
        closed = true;
        creator.removeProcedureListener(this);
        creator.endEditingSession();
    }

    @Override
    public void onStepChanged(ToolUser toolUser)
    {
        // This fires from whichever thread advanced the procedure, which for an async step is not the main thread.
        // Scheduling rather than running inline also guarantees the reopen does not happen while Bukkit is still
        // inside the close event of the gui this hand-off just closed.
        executor.scheduleOnMainThread(() ->
        {
            if (closed || !handingOff)
                return;
            handingOff = false;
            open();
        });
    }

    @Override
    public void onProcedureShutDown(ToolUser toolUser)
    {
        executor.scheduleOnMainThread(() ->
        {
            handingOff = false;
            closeGui();
            tearDown();
        });
    }

    @Override
    public String getPageName()
    {
        return "WizardGui";
    }

    @AssistedFactory
    interface IFactory
    {
        WizardGui newWizardGui(Creator creator, PlayerSpigot inventoryHolder);
    }
}
