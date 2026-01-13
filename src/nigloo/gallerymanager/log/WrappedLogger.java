package nigloo.gallerymanager.log;

import org.apache.logging.log4j.jul.LevelTranslator;
import org.apache.logging.log4j.spi.ExtendedLogger;
import org.apache.logging.log4j.spi.ExtendedLoggerWrapper;

public class WrappedLogger extends ExtendedLoggerWrapper
{

    private static final long serialVersionUID = 1L;
    private static final String FQCN = ApiLogger.class.getName();

    WrappedLogger(final ExtendedLogger logger) {
        super(logger, logger.getName(), logger.getMessageFactory());
    }

    @Override
    public void log(final org.apache.logging.log4j.Level level, final String message, final Throwable t) {
        logIfEnabled(FQCN, level, null, message, t);
    }

    @Override
    public void log(final org.apache.logging.log4j.Level level, final String message, final Object... params) {
        logIfEnabled(FQCN, level, null, message, params);
    }

    @Override
    public void log(final org.apache.logging.log4j.Level level, final String message) {
        logIfEnabled(FQCN, level, null, message);
    }

    @Override
    public void entry() {
        entry(FQCN);
    }

    @Override
    public void entry(final Object... params) {
        entry(FQCN, params);
    }

    @Override
    public void exit() {
        exit(FQCN, null);
    }

    @Override
    public <R> R exit(final R result) {
        return exit(FQCN, result);
    }

    @Override
    public <T extends Throwable> T throwing(final T t) {
        return throwing(FQCN, LevelTranslator.toLevel(java.util.logging.Level.FINER), t);
    }
}
