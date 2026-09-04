package nl.pim16aap2.animatedarchitecture.spigot.core.gui;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;
import de.themoep.inventorygui.GuiBackElement;
import de.themoep.inventorygui.GuiElement;
import de.themoep.inventorygui.GuiElementGroup;
import de.themoep.inventorygui.InventoryGui;
import de.themoep.inventorygui.StaticGuiElement;
import lombok.Getter;
import lombok.ToString;
import nl.pim16aap2.animatedarchitecture.core.api.IConfig;
import nl.pim16aap2.animatedarchitecture.core.api.IExecutor;
import nl.pim16aap2.animatedarchitecture.core.api.IPermissionsManager;
import nl.pim16aap2.animatedarchitecture.core.api.factories.ITextFactory;
import nl.pim16aap2.animatedarchitecture.core.commands.CommandFactory;
import nl.pim16aap2.animatedarchitecture.core.localization.ILocalizer;
import nl.pim16aap2.animatedarchitecture.core.managers.StructureTypeManager;
import nl.pim16aap2.animatedarchitecture.core.managers.ToolUserManager;
import nl.pim16aap2.animatedarchitecture.core.structures.StructureType;
import nl.pim16aap2.animatedarchitecture.core.text.TextArgument;
import nl.pim16aap2.animatedarchitecture.core.tooluser.creator.Creator;
import nl.pim16aap2.animatedarchitecture.core.util.FutureUtil;
import nl.pim16aap2.animatedarchitecture.spigot.core.AnimatedArchitecturePlugin;
import nl.pim16aap2.animatedarchitecture.spigot.util.implementations.PlayerSpigot;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Gui page for creating new structures.
 */
@ToString(onlyExplicitlyIncluded = true)
class CreateStructureGui implements IGuiPage
{
    private static final ItemStack FILLER = new ItemStack(Material.GRAY_STAINED_GLASS_PANE, 1);

    private final AnimatedArchitecturePlugin animatedArchitecturePlugin;
    private final StructureTypeManager structureTypeManager;
    private final InventoryGui inventoryGui;
    private final IPermissionsManager permissionsManager;
    private final ILocalizer localizer;

    private final CommandFactory commandFactory;
    private final IConfig config;
    private final IExecutor executor;
    private final ToolUserManager toolUserManager;
    private final WizardGui.IFactory wizardGuiFactory;

    @Getter
    @ToString.Include
    private final PlayerSpigot inventoryHolder;

    @AssistedInject
    CreateStructureGui(
        AnimatedArchitecturePlugin animatedArchitecturePlugin,
        StructureTypeManager structureTypeManager,
        IPermissionsManager permissionsManager,
        ILocalizer localizer,
        CommandFactory commandFactory,
        IConfig config,
        IExecutor executor,
        ToolUserManager toolUserManager,
        WizardGui.IFactory wizardGuiFactory,
        @Assisted PlayerSpigot inventoryHolder)
    {
        this.animatedArchitecturePlugin = animatedArchitecturePlugin;
        this.structureTypeManager = structureTypeManager;
        this.permissionsManager = permissionsManager;
        this.localizer = localizer;
        this.commandFactory = commandFactory;
        this.config = config;
        this.executor = executor;
        this.toolUserManager = toolUserManager;
        this.wizardGuiFactory = wizardGuiFactory;
        this.inventoryHolder = inventoryHolder;

        this.inventoryGui = createGui();

        showGUI();
    }

    private InventoryGui createGui()
    {
        final var types = structureTypeManager
            .getEnabledStructureTypes()
            .stream()
            .filter(type -> permissionsManager.hasPermissionToCreateStructure(inventoryHolder, type))
            .toList();

        final String[] guiSetup = GuiUtil.fillLinesWithChar('g', types.size(), "f        ");

        final InventoryGui gui = new InventoryGui(
            animatedArchitecturePlugin,
            inventoryHolder.getBukkitPlayer(),
            localizer.getMessage("gui.new_structure_page.title"),
            guiSetup
        );

        gui.setFiller(FILLER);

        populateGUI(gui, types);

        return gui;
    }

    private void populateGUI(InventoryGui gui, List<StructureType> types)
    {
        addHeader(gui);
        addElements(gui, types);
    }

    private void addHeader(InventoryGui gui)
    {
        gui.addElement(new GuiBackElement(
            'f',
            new ItemStack(Material.ARROW),
            localizer.getMessage("gui.new_structure_page.back_button"))
        );
    }

    private void addElements(InventoryGui gui, List<StructureType> types)
    {
        final GuiElementGroup group = new GuiElementGroup('g');
        for (final var type : types)
        {
            final GuiElement element = new StaticGuiElement(
                'g',
                new ItemStack(Material.WRITABLE_BOOK),
                click ->
                {
                    startCreationProcess(type);
                    return true;
                },
                ITextFactory.getSimpleTextFactory().newText()
                    .append(
                        localizer.getMessage("gui.new_structure_page.button.name"),
                        new TextArgument(localizer.getStructureType(type)))
                    .toString()
            );
            group.addElement(element);
        }
        group.setFiller(FILLER);
        gui.addElement(group);
    }

    private void startCreationProcess(StructureType type)
    {
        commandFactory
            .newNewStructure(inventoryHolder, type)
            .run()
            .thenRun(this::openWizardIfEnabled)
            .exceptionally(FutureUtil::exceptionally);
        GuiUtil.closeAllGuis(inventoryHolder);
    }

    /**
     * Opens the inventory wizard over the process that was just started, if this server uses it.
     * <p>
     * When the wizard is disabled, the process is left to the chat-driven flow, which is what it has always used.
     */
    private void openWizardIfEnabled()
    {
        if (!config.isCreatorWizardEnabled())
            return;

        executor.runOnMainThread(() -> toolUserManager
            .getToolUser(inventoryHolder.getUUID())
            .filter(Creator.class::isInstance)
            .map(Creator.class::cast)
            .ifPresent(creator -> wizardGuiFactory.newWizardGui(creator, inventoryHolder)));
    }

    private void showGUI()
    {
        inventoryGui.show(inventoryHolder.getBukkitPlayer());
    }

    @Override
    public String getPageName()
    {
        return "CreateStructureGui";
    }

    @AssistedFactory
    interface IFactory
    {
        CreateStructureGui newCreateStructureGui(PlayerSpigot playerSpigot);
    }
}
