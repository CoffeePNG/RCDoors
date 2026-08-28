package nl.pim16aap2.animatedarchitecture.core.util.updater;

import lombok.Getter;
import nl.pim16aap2.animatedarchitecture.core.api.debugging.IDebuggable;
import org.jetbrains.annotations.Nullable;
import org.semver4j.Semver;

import javax.inject.Singleton;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Compatibility shell for the upstream update checker.
 * <p>
 * RCDoors deliberately performs no external update checks. The type remains available so extensions compiled against
 * the upstream API continue to link, but every check completes locally without contacting an upstream service.
 */
@Singleton
public final class UpdateChecker implements IDebuggable
{
    /**
     * Retained for binary/source compatibility. RCDoors never populates update information.
     */
    @Getter
    private volatile @Nullable UpdateInformation updateInformation;

    /**
     * Creates a disabled update checker.
     *
     * @param currentVersion
     *     The current project version. It is validated but never sent anywhere.
     */
    public UpdateChecker(Semver currentVersion)
    {
        Objects.requireNonNull(currentVersion, "currentVersion");
    }

    /**
     * Completes locally without performing a network request.
     *
     * @return A completed future containing no update information.
     */
    public CompletableFuture<@Nullable UpdateInformation> checkForUpdates()
    {
        return CompletableFuture.completedFuture(updateInformation);
    }

    @Override
    public String getDebugInformation()
    {
        return "Update checks disabled by RCDoors";
    }
}
