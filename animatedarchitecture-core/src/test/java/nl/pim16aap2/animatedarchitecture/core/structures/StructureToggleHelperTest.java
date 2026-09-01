package nl.pim16aap2.animatedarchitecture.core.structures;

import nl.pim16aap2.animatedarchitecture.core.events.StructureActionCause;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class StructureToggleHelperTest
{
    @Test
    void onlyServerActionsBypassPlayerProtectionHooks()
    {
        Assertions.assertFalse(
            StructureToggleHelper.requiresProtectionCheck(StructureActionCause.SERVER));
        Assertions.assertTrue(
            StructureToggleHelper.requiresProtectionCheck(StructureActionCause.PLAYER));
        Assertions.assertTrue(
            StructureToggleHelper.requiresProtectionCheck(StructureActionCause.PLUGIN));
        Assertions.assertTrue(
            StructureToggleHelper.requiresProtectionCheck(StructureActionCause.REDSTONE));
    }
}
