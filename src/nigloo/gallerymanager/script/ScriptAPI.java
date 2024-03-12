package nigloo.gallerymanager.script;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

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

	private static final ThreadFactory SCRIPT_MAIN_THREAD_FACTORY = Thread
			.ofPlatform()
			.name("Script-", 1)
			.daemon()
			.factory();

	private final ScriptAsyncExecutor asyncExecutor;
	
	public ScriptAPI(PrintWriter output)
	{
		this.output = output;
		this.asyncExecutor = new ScriptAsyncExecutor(Executors.newSingleThreadExecutor(SCRIPT_MAIN_THREAD_FACTORY));
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
	
	public CompletableFuture<Void> asyncRefreshFileSystem(Collection<Path> paths, boolean deep)
	{
		return uiController.refreshFileSystem(toAbsolute(paths), deep);
	}
	
	public CompletableFuture<Void> asyncSynchronizeFileSystem(Collection<Path> paths, boolean deep)
	{
		return uiController.synchronizeFileSystem(toAbsolute(paths), deep);
	}
	
	public CompletableFuture<Void> asyncDelete(Collection<Path> paths, boolean deleteOnDisk)
	{
		return uiController.delete(toAbsolute(paths), deleteOnDisk);
	}

	public Executor getAsyncExecutor()
	{
		return asyncExecutor;
	}

	public void join(CompletableFuture<?> cf)
	{
		asyncExecutor.join(cf);
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
	
	private Collection<Path> toAbsolute(Collection<Path> paths)
	{
		if (paths == null)
			return null;
		
		ArrayList<Path> absPaths = new ArrayList<>(paths.size());
		for (Path path : paths)
			absPaths.add(gallery.toAbsolutePath(path));
		
		return absPaths;
	}
}
