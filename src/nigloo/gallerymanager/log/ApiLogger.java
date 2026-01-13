package nigloo.gallerymanager.log;

import org.apache.logging.log4j.jul.LevelTranslator;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.MessageFactory;
import org.apache.logging.log4j.spi.ExtendedLogger;
import org.apache.logging.log4j.status.StatusLogger;

import java.util.logging.Filter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class ApiLogger extends Logger
{

    private final WrappedLogger logger;
    private static final String FQCN = ApiLogger.class.getName();

    ApiLogger(final ExtendedLogger logger) {
        super(logger.getName(), null);
        final Level javaLevel = LevelTranslator.toJavaLevel(logger.getLevel());
        // "java.util.logging.LoggingPermission" "control"
        super.setLevel(javaLevel);
        this.logger = new WrappedLogger(logger);
    }

    @Override
    public void log(final LogRecord record) {
        if (isFiltered(record)) {
            return;
        }
        final org.apache.logging.log4j.Level level = LevelTranslator.toLevel(record.getLevel());
        final Object[] parameters = record.getParameters();
        final MessageFactory messageFactory = logger.getMessageFactory();
        final Message message = parameters == null
                                ? messageFactory.newMessage(record.getMessage()) /* LOG4J2-1251: not formatted case */
                                : messageFactory.newMessage(record.getMessage(), parameters);
        final Throwable thrown = record.getThrown();
        logger.logIfEnabled(FQCN, level, null, message, thrown);
    }

    // support for Logger.getFilter()/Logger.setFilter()
    boolean isFiltered(final LogRecord logRecord) {
        final Filter filter = getFilter();
        return filter != null && !filter.isLoggable(logRecord);
    }

    @Override
    public boolean isLoggable(final Level level) {
        return logger.isEnabled(LevelTranslator.toLevel(level));
    }

    @Override
    public String getName() {
        return logger.getName();
    }

    @Override
    public void setLevel(final Level newLevel) throws SecurityException {
        StatusLogger.getLogger()
                    .error(
                            "Cannot set JUL log level through log4j-api: " + "ignoring call to Logger.setLevel({})",
                            newLevel);
    }

    /**
     * Provides access to {@link Logger#setLevel(java.util.logging.Level)}. This method should only be used by child
     * classes.
     *
     * @see Logger#setLevel(java.util.logging.Level)
     */
    protected void doSetLevel(final Level newLevel) throws SecurityException {
        super.setLevel(newLevel);
    }

    /**
     * Unsupported operation.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    public void setParent(final Logger parent) {
        throw new UnsupportedOperationException("Cannot set parent logger");
    }

    @Override
    public void log(final Level level, final String msg) {
        if (getFilter() == null) {
            logger.log(LevelTranslator.toLevel(level), msg);
        } else {
            super.log(level, msg);
        }
    }

    @Override
    public void log(final Level level, final String msg, final Object param1) {
        if (getFilter() == null) {
            logger.log(LevelTranslator.toLevel(level), msg, param1);
        } else {
            super.log(level, msg, param1);
        }
    }

    @Override
    public void log(final Level level, final String msg, final Object[] params) {
        if (getFilter() == null) {
            logger.log(LevelTranslator.toLevel(level), msg, params);
        } else {
            super.log(level, msg, params);
        }
    }

    @Override
    public void log(final Level level, final String msg, final Throwable thrown) {
        if (getFilter() == null) {
            logger.log(LevelTranslator.toLevel(level), msg, thrown);
        } else {
            super.log(level, msg, thrown);
        }
    }

    @Override
    public void logp(final Level level, final String sourceClass, final String sourceMethod, final String msg) {
        log(level, msg);
    }

    @Override
    public void logp(
            final Level level,
            final String sourceClass,
            final String sourceMethod,
            final String msg,
            final Object param1) {
        log(level, msg, param1);
    }

    @Override
    public void logp(
            final Level level,
            final String sourceClass,
            final String sourceMethod,
            final String msg,
            final Object[] params) {
        log(level, msg, params);
    }

    @Override
    public void logp(
            final Level level,
            final String sourceClass,
            final String sourceMethod,
            final String msg,
            final Throwable thrown) {
        log(level, msg, thrown);
    }

    @Override
    public void logrb(
            final Level level,
            final String sourceClass,
            final String sourceMethod,
            final String bundleName,
            final String msg) {
        log(level, msg);
    }

    @Override
    public void logrb(
            final Level level,
            final String sourceClass,
            final String sourceMethod,
            final String bundleName,
            final String msg,
            final Object param1) {
        log(level, msg, param1);
    }

    @Override
    public void logrb(
            final Level level,
            final String sourceClass,
            final String sourceMethod,
            final String bundleName,
            final String msg,
            final Object[] params) {
        log(level, msg, params);
    }

    @Override
    public void logrb(
            final Level level,
            final String sourceClass,
            final String sourceMethod,
            final String bundleName,
            final String msg,
            final Throwable thrown) {
        log(level, msg, thrown);
    }

    @Override
    public void entering(final String sourceClass, final String sourceMethod) {
        logger.entry();
    }

    @Override
    public void entering(final String sourceClass, final String sourceMethod, final Object param1) {
        logger.entry(param1);
    }

    @Override
    public void entering(final String sourceClass, final String sourceMethod, final Object[] params) {
        logger.entry(params);
    }

    @Override
    public void exiting(final String sourceClass, final String sourceMethod) {
        logger.exit();
    }

    @Override
    public void exiting(final String sourceClass, final String sourceMethod, final Object result) {
        logger.exit(result);
    }

    @Override
    public void throwing(final String sourceClass, final String sourceMethod, final Throwable thrown) {
        logger.throwing(thrown);
    }

    @Override
    public void severe(final String msg) {
        if (getFilter() == null) {
            logger.logIfEnabled(FQCN, org.apache.logging.log4j.Level.ERROR, null, msg);
        } else {
            super.severe(msg);
        }
    }

    @Override
    public void warning(final String msg) {
        if (getFilter() == null) {
            logger.logIfEnabled(FQCN, org.apache.logging.log4j.Level.WARN, null, msg);
        } else {
            super.warning(msg);
        }
    }

    @Override
    public void info(final String msg) {
        if (getFilter() == null) {
            logger.logIfEnabled(FQCN, org.apache.logging.log4j.Level.INFO, null, msg);
        } else {
            super.info(msg);
        }
    }

    @Override
    public void config(final String msg) {
        if (getFilter() == null) {
            logger.logIfEnabled(FQCN, LevelTranslator.CONFIG, null, msg);
        } else {
            super.config(msg);
        }
    }

    @Override
    public void fine(final String msg) {
        if (getFilter() == null) {
            logger.logIfEnabled(FQCN, org.apache.logging.log4j.Level.DEBUG, null, msg);
        } else {
            super.fine(msg);
        }
    }

    @Override
    public void finer(final String msg) {
        if (getFilter() == null) {
            logger.logIfEnabled(FQCN, org.apache.logging.log4j.Level.TRACE, null, msg);
        } else {
            super.finer(msg);
        }
    }

    @Override
    public void finest(final String msg) {
        if (getFilter() == null) {
            logger.logIfEnabled(FQCN, LevelTranslator.FINEST, null, msg);
        } else {
            super.finest(msg);
        }
    }
}
