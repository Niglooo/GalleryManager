package nigloo.gallerymanager.script;

import java.io.IOException;
import java.io.PrintWriter;

import org.apache.logging.log4j.Level;

import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.ui.UIController;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;

public class ScriptAPI implements AutoCloseable
{
	@Inject
	private UIController uiController;
	@Inject
	private Gallery gallery;
	
	private final PrintWriter output;
	
	public ScriptAPI(PrintWriter output)
	{
		this.output = output;
		Injector.init(this);
	}
	
	public Gallery getGallery()
	{
		return gallery;
	}
	
	public void saveGallery() throws IOException
	{
		uiController.saveGallery();
	}
	
	public void printStackTrace(Throwable t)
	{
		if (t != null)
			t.printStackTrace(output);
	}
	
	public void connectLoggerToOutput(String loggerName, String level)
	{
		ScriptOutputAppender.redirectTo(output, loggerName, Level.valueOf(level));
	}
	
	@Override
	public void close()
	{
		ScriptOutputAppender.stopRedirectingTo(output);
	}
}
