package nl.pim16aap2.animatedarchitecture.spigot.core.managers;

import com.google.common.flogger.StackSize;
import lombok.extern.flogger.Flogger;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.permission.Permission;
import nl.pim16aap2.animatedarchitecture.core.api.IConfig;
import nl.pim16aap2.animatedarchitecture.core.api.IEconomyManager;
import nl.pim16aap2.animatedarchitecture.core.api.IExecutor;
import nl.pim16aap2.animatedarchitecture.core.api.IPlayer;
import nl.pim16aap2.animatedarchitecture.core.api.IWorld;
import nl.pim16aap2.animatedarchitecture.core.api.debugging.DebuggableRegistry;
import nl.pim16aap2.animatedarchitecture.core.api.debugging.IDebuggable;
import nl.pim16aap2.animatedarchitecture.core.api.factories.ITextFactory;
import nl.pim16aap2.animatedarchitecture.core.api.restartable.IRestartable;
import nl.pim16aap2.animatedarchitecture.core.commands.ICommandSender;
import nl.pim16aap2.animatedarchitecture.core.localization.ILocalizer;
import nl.pim16aap2.animatedarchitecture.core.managers.StructureTypeManager;
import nl.pim16aap2.animatedarchitecture.core.structures.StructureAttribute;
import nl.pim16aap2.animatedarchitecture.core.structures.StructureType;
import nl.pim16aap2.animatedarchitecture.core.text.TextType;
import nl.pim16aap2.animatedarchitecture.core.util.MathUtil;
import nl.pim16aap2.animatedarchitecture.core.util.StringUtil;
import nl.pim16aap2.animatedarchitecture.spigot.util.SpigotAdapter;
import nl.pim16aap2.animatedarchitecture.spigot.util.api.IPermissionsManagerSpigot;
import nl.pim16aap2.animatedarchitecture.spigot.util.hooks.IFakePlayer;
import nl.pim16aap2.jcalculator.JCalculator;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.Nullable;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Manages all interactions with Vault.
 */
@Singleton
@Flogger
public final class VaultManager implements IRestartable, IEconomyManager, IPermissionsManagerSpigot, IDebuggable
{
    private final Map<StructureType, Double> flatPrices;
    private final Permission perms;
    private final ILocalizer localizer;
    private final ITextFactory textFactory;
    private final IConfig config;
    private final StructureTypeManager structureTypeManager;
    private final IExecutor executor;
    private final @Nullable Economy economy;
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private @Nullable CreationPayments creationPayments;
    private @Nullable FeeMessages feeMessages;
    private @Nullable net.republicraft.platform.api.data.DatabaseHandle feeDatabase;
    private @Nullable net.republicraft.platform.api.task.TaskScope feeTasks;

    @Inject
    public VaultManager(
        ILocalizer localizer,
        ITextFactory textFactory,
        IConfig config,
        StructureTypeManager structureTypeManager,
        DebuggableRegistry debuggableRegistry,
        IExecutor executor, org.bukkit.plugin.java.JavaPlugin plugin,
        nl.pim16aap2.animatedarchitecture.core.api.restartable.RestartableHolder restartableHolder)
    {
        this.localizer = localizer;
        this.textFactory = textFactory;
        this.config = config;
        this.structureTypeManager = structureTypeManager;
        this.executor = executor;
        this.plugin = plugin;

        flatPrices = new HashMap<>();
        economy = setupEconomy();
        perms = setupPermissions();

        debuggableRegistry.registerDebuggable(this);
        restartableHolder.registerRestartable(this);
    }

    @Override
    public boolean buyStructure(IPlayer player, IWorld world, StructureType type, int blockCount)
    {
        if (getPrice(type, blockCount).isEmpty())
            return true;
        sendCreationMessage(player, "receipt-required");
        return false;
    }

    @Override
    public boolean sendCreationMessage(IPlayer player, String key, Object... arguments)
    {
        final FeeMessages messages = feeMessages;
        if (messages == null) return false;
        final Player online = SpigotAdapter.getBukkitPlayer(player);
        if (online != null && online.isOnline()) messages.send(online,key,arguments);
        return true;
    }

    @Override
    public CompletableFuture<CreationResult> createStructure(
        java.util.UUID receipt, IPlayer player,
        nl.pim16aap2.animatedarchitecture.core.structures.Structure structure, double quotedPrice,
        java.util.function.Supplier<CompletableFuture<
            nl.pim16aap2.animatedarchitecture.core.managers.DatabaseManager.StructureInsertResult>> insert)
    {
        return executor.composeOnMainThread(() -> createStructureOnMain(receipt, player, structure, quotedPrice, insert));
    }

    private CompletableFuture<CreationResult> createStructureOnMain(
        java.util.UUID receipt, IPlayer player,
        nl.pim16aap2.animatedarchitecture.core.structures.Structure structure, double quotedPrice,
        java.util.function.Supplier<CompletableFuture<
            nl.pim16aap2.animatedarchitecture.core.managers.DatabaseManager.StructureInsertResult>> insert)
    {
        final Player online = SpigotAdapter.getBukkitPlayer(player);
        if (online == null || !online.isOnline() || !online.hasPermission(structure.getType().getCreationPermission()))
            return CompletableFuture.completedFuture(new CreationResult("FAILED", "Current creation permission required"));
        final double current = getPrice(structure.getType(), structure.getCuboid().getVolume()).orElse(0);
        if (Double.compare(current, quotedPrice) != 0)
            return CompletableFuture.completedFuture(new CreationResult("FAILED", "Creation price changed; review a new quote"));
        if (quotedPrice == 0)
            return IEconomyManager.super.createStructure(receipt,player,structure,0,insert);
        final CreationPayments payments = creationPayments;
        if (payments == null)
            return CompletableFuture.completedFuture(new CreationResult("UNAVAILABLE", "Creation payment storage is unavailable"));
        return payments.create(receipt, player.getUUID(), structure.getWorld().worldName(),
            structure.getType().getSimpleName() + "/" + structure.getName() + "/" + structure.getCuboid(),
            quotedPrice, insert).toCompletableFuture();
    }

    private CreationPayments.WalletResult creationWallet(CreationPayments.Receipt receipt, boolean refund)
    {
        final var registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (registration == null || registration.getProvider() == null)
            return CreationPayments.WalletResult.FAILED;
        final Economy currentEconomy = registration.getProvider();
        try
        {
            final OfflinePlayer account = Bukkit.getOfflinePlayer(java.util.UUID.fromString(receipt.player()));
            final double amount = java.math.BigDecimal.valueOf(receipt.cents(),2).doubleValue();
            final EconomyResponse response = refund
                ? currentEconomy.depositPlayer(account,receipt.world(),amount)
                : currentEconomy.withdrawPlayer(account,receipt.world(),amount);
            if (response == null || response.type == null) return CreationPayments.WalletResult.UNKNOWN;
            return response.transactionSuccess() ? CreationPayments.WalletResult.SUCCESS : CreationPayments.WalletResult.FAILED;
        }
        catch (RuntimeException failure)
        {
            log.atSevere().withCause(failure).log("Unknown creation wallet result for %s", receipt.id());
            return CreationPayments.WalletResult.UNKNOWN;
        }
    }

    private void startCreationPayments()
    {
        final FeeMessages messages = new FeeMessages(plugin);
        feeMessages = messages;
        final var database = net.republicraft.platform.api.service.Services
            .require(plugin,net.republicraft.platform.api.data.DatabaseService.class).open(plugin,CreationPayments.schema());
        final var tasks = net.republicraft.platform.api.service.Services
            .require(plugin,net.republicraft.platform.api.task.TaskService.class).scope(plugin,"creation-payments");
        final var payments = new CreationPayments(database,(receipt,refund) -> tasks.sync(() -> creationWallet(receipt,refund)));
        feeDatabase = database;
        feeTasks = tasks;
        creationPayments = payments;
        payments.start().thenCompose(ignored -> payments.recoverRefunds()).exceptionally(failure ->
        {
            log.atSevere().withCause(failure).log("Creation payment recovery failed; paid creation remains protected");
            return null;
        });
        tasks.repeat(java.time.Duration.ofSeconds(30),java.time.Duration.ofSeconds(30),
            () -> payments.recoverRefunds().exceptionally(failure ->
            {
                log.atSevere().withCause(failure).log("Creation refund recovery failed"); return null;
            }));
        final var command = java.util.Objects.requireNonNull(plugin.getCommand("rcdoorfees"));
        command.setExecutor((sender,unused,label,args) ->
        {
            if (!sender.hasPermission("rcdoors.fees.admin")) { messages.send(sender,"permission-denied"); return true; }
            try
            {
                if (args.length >= 1 && args[0].equalsIgnoreCase("review"))
                {
                    final int page = args.length > 1 ? Math.max(1,Integer.parseInt(args[1])) : 1;
                    payments.pending(page-1).whenComplete((rows,failure) -> tasks.run(() ->
                    {
                        if (failure != null) { messages.send(sender,"storage-unavailable"); return; }
                        messages.send(sender,"review-page","page",page);
                        for (var row : rows) messages.send(sender,"review-entry", "receipt",row.id(),
                            "state",row.state(),"player",row.player(),"amount",java.math.BigDecimal.valueOf(row.cents(),2),
                            "world",row.world(),"structure",row.description(),"detail",row.detail());
                    }));
                }
                else if (args.length >= 4 && args[0].equalsIgnoreCase("resolve"))
                {
                    final var id = java.util.UUID.fromString(args[1]);
                    final String reason = String.join(" ",java.util.Arrays.copyOfRange(args,3,args.length));
                    final String actor = sender instanceof Player player ? player.getUniqueId().toString() : "CONSOLE";
                    payments.resolve(id,actor,args[2].toLowerCase(java.util.Locale.ROOT),reason)
                        .whenComplete((changed,failure) -> tasks.run(() -> messages.send(sender,
                            failure != null ? "resolution-failed" : Boolean.TRUE.equals(changed)
                                ? "resolution-recorded" : "resolution-rejected")));
                }
                else messages.send(sender,"usage");
            }
            catch (RuntimeException invalid) { messages.send(sender,"invalid-input","detail",invalid.getMessage()); }
            return true;
        });
    }

    @Override
    public boolean isEconomyEnabled()
    {
        return economy != null;
    }

    /**
     * Tries to get a flat price from the config for a {@link StructureType}. Useful in case the price is set to zero,
     * so the plugin won't have to parse the formula every time if it is disabled.
     *
     * @param type
     *     The {@link StructureType}.
     */
    private void getFlatPrice(StructureType type)
    {
        MathUtil.parseDouble(config.getPrice(type)).ifPresent(price -> flatPrices.put(type, price));
    }

    @Override
    public boolean hasPermission(Player player, String permission)
    {
        if (executor.isMainThread() &&
            (player instanceof IFakePlayer || !player.isOnline()))
        {
            throw new RuntimeException(String.format(
                "Failed to check permission '%s' for player '%s'! " +
                    "Cannot check permissions for offline players on the main thread! Online: %b, Fake: %b",
                permission,
                player.getName(),
                player.isOnline(),
                player instanceof IFakePlayer)
            );
        }

        final boolean result = perms.playerHas(player.getWorld().getName(), player, permission);
        log.atFine().log("Player '%s' has permission '%s': %b", player.getName(), permission, result);
        return result;
    }

    @Override
    public CompletableFuture<Boolean> hasPermissionOffline(World world, OfflinePlayer player, String permission)
    {
        return CompletableFuture.supplyAsync(() -> perms.playerHas(world.getName(), player, permission));
    }

    /**
     * Evaluates the price formula given a specific blockCount using {@link JCalculator}
     *
     * @param formula
     *     The formula of the price.
     * @param blockCount
     *     The number of blocks in the structure.
     * @return The price of the structure given the formula and the blockCount variable.
     */
    private double evaluateFormula(String formula, int blockCount)
    {
        try
        {
            return JCalculator.getResult(formula, new String[]{"blockCount"}, new double[]{blockCount});
        }
        catch (Exception e)
        {
            log.atSevere().withCause(e).log(
                "Failed to determine structure creation price! Please contact the RCDoors maintainers! "
                    + "Include this: '%s' and stacktrace:",
                formula
            );
            throw new IllegalArgumentException("Invalid structure creation price formula: " + formula, e);
        }
    }

    @Override
    public OptionalDouble getPrice(StructureType type, int blockCount)
    {
        // TODO: Store flat prices as OptionalDoubles.
        final double price = flatPrices.getOrDefault(type, evaluateFormula(config.getPrice(type), blockCount));

        if (!Double.isFinite(price) || price < 0
            || java.math.BigDecimal.valueOf(price).stripTrailingZeros().scale() > 2)
            throw new IllegalArgumentException("Structure creation price must be finite, nonnegative, and exact cents");

        return price <= 0 ? OptionalDouble.empty() : OptionalDouble.of(price);
    }

    /**
     * Checks if the player has a certain amount of money in their bank account.
     *
     * @param player
     *     The player whose bank account to check.
     * @param amount
     *     The amount of money.
     * @return True if the player has at least this much money.
     */
    private boolean has(OfflinePlayer player, double amount)
    {
        final boolean defaultValue = false;
        if (economy == null)
        {
            log.atWarning().log(
                "Economy not enabled! Could not subtract %f from the balance of player: %s!",
                amount,
                player
            );
            return defaultValue;
        }

        try
        {
            return economy.has(player, amount);
        }
        catch (Exception e)
        {
            log.atSevere().withCause(e).log(
                "Failed to check balance of player %s! Please contact the RCDoors maintainers!", player);
        }
        return defaultValue;
    }

    /**
     * Withdraw a certain amount of money from a player's bank account in a certain world.
     *
     * @param player
     *     The player.
     * @param worldName
     *     The name of the world.
     * @param amount
     *     The amount of money.
     * @return True if the money was successfully withdrawn from the player's accounts.
     */
    private boolean withdrawPlayer(OfflinePlayer player, String worldName, double amount)
    {
        final boolean defaultValue = false;
        if (economy == null)
        {
            log.atWarning().log(
                "Economy not enabled! Could not subtract %f from the balance of player: %s in world: %s!",
                amount,
                player,
                worldName
            );
            return defaultValue;
        }

        try
        {
            if (has(player, amount))
                return economy
                    .withdrawPlayer(player, worldName, amount)
                    .type
                    .equals(EconomyResponse.ResponseType.SUCCESS);
            return false;
        }
        catch (Exception e)
        {
            log.atSevere().withCause(e).log(
                "Failed to subtract %f money from player %s! Please contact the RCDoors maintainers!",
                amount,
                player
            );
        }
        return defaultValue;
    }

    /**
     * Withdraw a certain amount of money from a player's bank account in a certain world.
     *
     * @param player
     *     The player.
     * @param worldName
     *     The name of the world.
     * @param amount
     *     The amount of money.
     * @return True if the money was successfully withdrawn from the player's accounts.
     */
    private boolean withdrawPlayer(Player player, String worldName, double amount)
    {
        return withdrawPlayer(Bukkit.getOfflinePlayer(player.getUniqueId()), worldName, amount);
    }

    /**
     * Initialize the economy dependency. Assumes Vault is installed on this server.
     *
     * @return True if the initialization process was successful.
     */
    private @Nullable Economy setupEconomy()
    {
        try
        {
            final @Nullable RegisteredServiceProvider<Economy> economyProvider =
                Bukkit.getServer().getServicesManager().getRegistration(Economy.class);

            if (economyProvider == null)
                return null;

            return economyProvider.getProvider();
        }
        catch (Exception e)
        {
            log.atSevere().withCause(e).log("Failed to initialize economy!");
            return null;
        }
    }

    /**
     * Initialize the "permissions" dependency. Assumes Vault is installed on this server.
     *
     * @return True if the initialization process was successful.
     */
    private Permission setupPermissions()
    {
        try
        {
            final RegisteredServiceProvider<Permission> permissionProvider =
                Objects.requireNonNull(Bukkit.getServer().getServicesManager().getRegistration(Permission.class));

            return Objects.requireNonNull(permissionProvider.getProvider());
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Failed to initialize permissions!", e);
        }
    }

    @Override
    public void initialize()
    {
        for (final StructureType type : structureTypeManager.getEnabledStructureTypes())
            getFlatPrice(type);
        startCreationPayments();
    }

    @Override
    public void shutDown()
    {
        flatPrices.clear();
        final CreationPayments payments = creationPayments;
        creationPayments = null;
        feeMessages = null;
        if (payments != null) payments.close();
        if (feeTasks != null) feeTasks.close();
        if (feeDatabase != null) feeDatabase.close();
        feeTasks = null;
        feeDatabase = null;
    }

    @Override
    public OptionalInt getMaxPermissionSuffix(Player player, String permissionBase)
    {
        final int permissionBaseLength = permissionBase.length();
        final Set<PermissionAttachmentInfo> playerPermissions = player.getEffectivePermissions();
        int ret = -1;
        for (final PermissionAttachmentInfo permission : playerPermissions)
            if (permission.getValue() && permission.getPermission().startsWith(permissionBase))
            {
                final OptionalInt suffix = MathUtil.parseInt(permission
                    .getPermission()
                    .substring(permissionBaseLength));
                if (suffix.isPresent())
                    ret = Math.max(ret, suffix.getAsInt());
            }
        return ret > 0 ? OptionalInt.of(ret) : OptionalInt.empty();
    }

    @Override
    public OptionalInt getMaxPermissionSuffix(IPlayer player, String permissionBase)
    {
        final @Nullable Player bukkitPlayer = getBukkitPlayer(player);
        if (bukkitPlayer == null)
            return OptionalInt.empty();
        return getMaxPermissionSuffix(bukkitPlayer, permissionBase);
    }

    @Override
    public boolean hasPermission(IPlayer player, String permissionNode)
    {
        final @Nullable Player bukkitPlayer = getBukkitPlayer(player);
        if (bukkitPlayer == null)
            return false;

        return bukkitPlayer.isOp() || bukkitPlayer.hasPermission(permissionNode);
    }

    @Override
    public boolean hasBypassPermissionsForAttribute(Player player, StructureAttribute structureAttribute)
    {
        return player.isOp() || player.hasPermission(structureAttribute.getAdminPermissionNode());
    }

    @Override
    public boolean hasBypassPermissionsForAttribute(IPlayer player, StructureAttribute structureAttribute)
    {
        final @Nullable Player bukkitPlayer = getBukkitPlayer(player);
        if (bukkitPlayer == null)
            return false;

        return hasBypassPermissionsForAttribute(bukkitPlayer, structureAttribute);
    }

    @Override
    public boolean isOp(@Nullable Player player)
    {
        return player != null && player.isOp();
    }

    @Override
    public boolean isOp(IPlayer player)
    {
        return isOp(getBukkitPlayer(player));
    }

    @Override
    public boolean hasPermissionToCreateStructure(ICommandSender sender, StructureType type)
    {
        return sender
            .getPlayer()
            .map(player -> hasPermission(player, type.getCreationPermission()))
            .orElse(true);
    }

    private @Nullable Player getBukkitPlayer(IPlayer player)
    {
        final @Nullable Player bukkitPlayer = SpigotAdapter.getBukkitPlayer(player);
        if (bukkitPlayer == null)
        {
            log.atSevere().withStackTrace(StackSize.FULL).log(
                "Failed to obtain BukkitPlayer for player: '%s'",
                player.asString()
            );
            return null;
        }
        return bukkitPlayer;
    }

    @Override
    public String getDebugInformation()
    {
        return "Economy: " + economy + "\n"
            + "Flat prices map: " +
            StringUtil.formatCollection(
                flatPrices.entrySet(),
                entry -> String.format("%s: %f", entry.getKey(), entry.getValue())
            );
    }
}
