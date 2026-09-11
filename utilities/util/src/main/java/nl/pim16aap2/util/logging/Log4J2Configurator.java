package nl.pim16aap2.util.logging;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.appender.rolling.DefaultRolloverStrategy;
import org.apache.logging.log4j.core.appender.rolling.SizeBasedTriggeringPolicy;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.nio.file.Path;

import static nl.pim16aap2.util.logging.floggerbackend.Log4j2LogEventUtil.toLog4jLevel;

/**
 * Configures Log4J2 to log to a file at the specified path.
 * <p>
 * Only logs messages from the package {@code nl.pim16aap2}.
 * <p>
 * This class is a singleton. Use {@link #getInstance()} to get the instance.
 */
public final class Log4J2Configurator
{
    private static final Log4J2Configurator INSTANCE = new Log4J2Configurator();

    private static final String LOGGER_NAME = "nl.pim16aap2";
    private static final String MAIN_PACKAGE = "nl.pim16aap2.animatedarchitecture";
    private static final String UTIL_PACKAGE = "nl.pim16aap2.util";

    private final VariableLevelFilter levelFilter;

    private Log4J2Configurator()
    {
        levelFilter = new VariableLevelFilter(Level.ALL);
        levelFilter.start();
    }

    /**
     * Gets the instance of the Log4J2Configurator.
     *
     * @return The instance of the Log4J2Configurator.
     */
    public static Log4J2Configurator getInstance()
    {
        return INSTANCE;
    }

    /**
     * Sets the level to filter on.
     * <p>
     * Any log event with a level lower than this level will be filtered out.
     *
     * @param level
     *     The level to filter on.
     */
    public void setLevel(Level level)
    {
        levelFilter.level(level);
    }

    /**
     * Sets the level to filter on.
     * <p>
     * Any log event with a level lower than this level will be filtered out.
     *
     * @param level
     *     The level to filter on.
     */
    public void setJULLevel(java.util.logging.Level level)
    {
        setLevel(toLog4jLevel(level));
    }

    /**
     * Sets the path to log to.
     * <p>
     * This method will create a new appender and logger for the specified path.
     * <p>
     * This also replaces the console prefix for our log messages (normally the fully qualified name of the class
     * that logged the message) with the given display name, so that messages show up in the console as e.g.
     * {@code [displayName]} instead of {@code [nl.pim16aap2.animatedarchitecture.spigot.core.SomeClass]}.
     *
     * @param path
     *     The path to log to.
     * @param displayName
     *     The name to show as the console prefix for our log messages, e.g. the plugin's name.
     */
    public void setLogPath(Path path, String displayName)
    {
        final LoggerContext loggerContext = (LoggerContext) LogManager.getContext(false);
        final Configuration configuration = loggerContext.getConfiguration();

        final var pattern = PatternLayout
            .newBuilder()
            .withPattern("[%date{ISO8601}] [%thread/%-5level] [%c{1.1.1.*}]: %message%n")
            .build();

        final var rollOverStrategy = DefaultRolloverStrategy
            .newBuilder()
            .withMax("3")
            .withFileIndex("min")
            .withConfig(configuration)
            .withCompressionLevelStr("5")
            .build();

        final String logFileBaseName = path.toAbsolutePath().resolve("aa").toString();

        final RollingFileAppender fileAppender = RollingFileAppender
            .newBuilder()
            .withStrategy(rollOverStrategy)
            .setConfiguration(configuration)
            .setName(LOGGER_NAME)
            .withAppend(true)
            .withFilePattern(logFileBaseName + ".%i.log.gz")
            .withFileName(logFileBaseName + ".log")
            .withPolicy(SizeBasedTriggeringPolicy.createPolicy("10MB"))
            .setLayout(pattern)
            .setFilter(levelFilter)
            .build();
        fileAppender.start();

        final var consolePattern = PatternLayout
            .newBuilder()
            .withPattern("%highlight{[%d{HH:mm:ss} %level]}: [" + displayName + "] %message%n")
            .build();

        final ConsoleAppender consoleAppender = ConsoleAppender
            .newBuilder()
            .setTarget(ConsoleAppender.Target.SYSTEM_OUT)
            .setConfiguration(configuration)
            .setName(LOGGER_NAME + ".console")
            .setLayout(consolePattern)
            .setFilter(levelFilter)
            .build();
        consoleAppender.start();

        // Create a custom filter to log only messages from AnimatedArchitecture and its utilities.
        final Filter customFilter = new AbstractFilter()
        {
            @Override
            public Result filter(LogEvent event)
            {
                final String loggerName = event.getLoggerName();
                if (loggerName != null &&
                    (loggerName.startsWith(MAIN_PACKAGE) || loggerName.startsWith(UTIL_PACKAGE)))
                    return Result.ACCEPT;
                return Result.DENY;
            }
        };

        // Additivity is disabled so that our messages are only handled by our own appenders above. Otherwise, they
        // would *also* propagate to the server's root console appender, which would print them a second time using
        // the fully qualified class name instead of our display name. One side effect: our console/log messages no
        // longer land in the server's own combined logs/latest.log; they remain fully available in our own log
        // file at the path passed to this method instead.
        final LoggerConfig loggerConfig = LoggerConfig
            .newBuilder()
            .withAdditivity(false)
            .withConfig(configuration)
            .withtFilter(levelFilter)
            .withLevel(Level.ALL)
            .withLoggerName(LOGGER_NAME)
            .build();

        configuration.getCustomLevels();

        loggerConfig.addFilter(customFilter);
        loggerConfig.addAppender(fileAppender, Level.ALL, levelFilter);
        loggerConfig.addAppender(consoleAppender, Level.ALL, levelFilter);
        configuration.addLogger(LOGGER_NAME, loggerConfig);

        loggerContext.updateLoggers();
    }
}
