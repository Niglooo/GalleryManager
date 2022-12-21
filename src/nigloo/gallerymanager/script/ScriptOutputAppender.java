package nigloo.gallerymanager.script;

import java.io.PrintWriter;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;

@Plugin(name = "ScriptOutputAppender", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE)
class ScriptOutputAppender extends AbstractAppender
{
	private static ScriptOutputAppender INSTANCE = null;
	
	//TODO add text color
	private record RedirectInfo(PrintWriter output, Level level)
	{
	}
	
	private final Map<String, List<RedirectInfo>> outputsByLoggerName = new HashMap<>();
	
	protected ScriptOutputAppender(String name,
	                               Filter filter,
	                               Layout<? extends Serializable> layout,
	                               boolean ignoreExceptions)
	{
		super(name, filter, layout, ignoreExceptions, Property.EMPTY_ARRAY);
	}
	
	@PluginFactory
	public static ScriptOutputAppender createAppender(@PluginAttribute("name") String name,
	                                                  @PluginElement("Filter") Filter filter,
	                                                  @PluginElement("Layout") Layout<? extends Serializable> layout,
	                                                  @PluginAttribute("ignoreExceptions") boolean ignoreExceptions)
	{
		if (INSTANCE == null)
			INSTANCE = new ScriptOutputAppender(name, filter, layout, ignoreExceptions);
		
		return INSTANCE;
	}
	
	@Override
	public void append(LogEvent event)
	{
		synchronized (INSTANCE)
		{
			List<RedirectInfo> redirectInfos = outputsByLoggerName.get(event.getLoggerName());
			
			if (redirectInfos != null)
			{
				String message = event.getMessage().getFormattedMessage();
				
				for (RedirectInfo info : redirectInfos)
				{
					if (event.getLevel().isMoreSpecificThan(info.level()))
						info.output().println(message);
				}
			}
		}
	}
	
	public static void redirectTo(PrintWriter output, String loggerName, Level level)
	{
		synchronized (INSTANCE)
		{
			INSTANCE.outputsByLoggerName.computeIfAbsent(loggerName, ln -> new ArrayList<>())
			                            .add(new RedirectInfo(output, level));
		}
	}
	
	public static void stopRedirectingTo(PrintWriter output)
	{
		synchronized (INSTANCE)
		{
			Iterator<List<RedirectInfo>> it = INSTANCE.outputsByLoggerName.values().iterator();
			
			while (it.hasNext())
			{
				List<RedirectInfo> redirectInfos = it.next();
				
				redirectInfos.removeIf(info -> info.output().equals(output));
				if (redirectInfos.isEmpty())
					it.remove();
			}
		}
	}
}
