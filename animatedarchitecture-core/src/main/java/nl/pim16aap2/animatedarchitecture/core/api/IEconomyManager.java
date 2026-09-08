package nl.pim16aap2.animatedarchitecture.core.api;

import nl.pim16aap2.animatedarchitecture.core.structures.StructureType;

import java.util.OptionalDouble;

public interface IEconomyManager
{
    record CreationResult(String status, String detail)
    {
        public boolean successful() { return status.equals("SUCCESS"); }
    }

    /** Presents RC-owned creation messages through the host platform's configured UI catalog. */
    default boolean sendCreationMessage(IPlayer player, String key, Object... arguments)
    {
        return false;
    }

    /**
     * Completes one reviewed creation. Paid implementations must durably record the intent before
     * calling a wallet or inserting the native structure. Unknown results require reconciliation.
     * The compatibility default supports free creations and refuses unjournaled paid creation.
     */
    default java.util.concurrent.CompletableFuture<CreationResult> createStructure(
        java.util.UUID receipt, IPlayer player,
        nl.pim16aap2.animatedarchitecture.core.structures.Structure structure, double quotedPrice,
        java.util.function.Supplier<java.util.concurrent.CompletableFuture<
            nl.pim16aap2.animatedarchitecture.core.managers.DatabaseManager.StructureInsertResult>> insert)
    {
        if (quotedPrice != 0)
            return java.util.concurrent.CompletableFuture.completedFuture(
                new CreationResult("UNAVAILABLE", "Durable creation payment provider unavailable"));
        return insert.get().thenApply(result -> new CreationResult(
            result != null && result.structure().isPresent() ? "SUCCESS" : "FAILED", receipt.toString()));
    }

    /**
     * Buys a structure for a player.
     *
     * @param player
     *     The player whose bank account to use.
     * @param world
     *     The world the structure is in.
     * @param type
     *     The {@link StructureType} of the structure.
     * @param blockCount
     *     The number of blocks in the structure.
     * @return True if the player bought the structure successfully.
     */
    boolean buyStructure(IPlayer player, IWorld world, StructureType type, int blockCount);

    /**
     * Gets the price of {@link StructureType} for a specific number of blocks.
     *
     * @param type
     *     The {@link StructureType}.
     * @param blockCount
     *     The number of blocks.
     * @return The price of this {@link StructureType} with this number of blocks.
     */
    OptionalDouble getPrice(StructureType type, int blockCount);

    /**
     * Checks if the economy manager is enabled.
     *
     * @return True if the economy manager is enabled.
     */
    boolean isEconomyEnabled();
}
