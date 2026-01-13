package nigloo.gallerymanager.log;

import org.apache.logging.log4j.message.MessageFactory;
import org.apache.logging.log4j.message.SimpleMessageFactory;
import org.apache.logging.log4j.spi.LoggerContext;

import java.util.logging.Logger;

public class FormatApiLoggerAdapter extends org.apache.logging.log4j.jul.ApiLoggerAdapter
{
    private static final MessageFactory MESSAGE_FACTORY = new SimpleMessageFactory();

    @Override
    protected Logger newLogger(final String name, final LoggerContext context) {
        return new ApiLogger(context.getLogger(name, MESSAGE_FACTORY));
    }
}
