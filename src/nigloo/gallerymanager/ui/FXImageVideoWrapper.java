package nigloo.gallerymanager.ui;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import nigloo.gallerymanager.model.Image;
import nigloo.tool.StopWatch;

public class FXImageVideoWrapper
{
	private static final Logger LOGGER = LogManager.getLogger(FXImageVideoWrapper.class);
	
	private final Path absPath;
	private final String filename;
	private final javafx.scene.image.Image fxImage;
	private final MediaPlayer fxPlayer;
	private final ReadOnlyObjectWrapper<Exception> exception;
	private final ReadOnlyDoubleWrapper progress;
	
	public FXImageVideoWrapper(Path absPath)
	{
		if (!absPath.isAbsolute())
			throw new IllegalArgumentException("absPath must be absolute. Got: "+absPath);
		
		this.absPath = absPath;
		this.filename = absPath.getFileName().toString();
		
		String url;
		try
		{
			url = absPath.toUri().toURL().toString();
		}
		catch (MalformedURLException e)
		{
			throw new RuntimeException(e);
		}
		
		exception = new ReadOnlyObjectWrapper<>(this, "loadingException");
		progress = new ReadOnlyDoubleWrapper(this, "loadingProgress");
		
		if (Image.isActuallyVideo(absPath))
		{
			fxImage = null;
			
			StopWatch timer = LOGGER.isDebugEnabled() ? new StopWatch().start() : null;
			
			final Media media = new Media(url);
			fxPlayer = new MediaPlayer(media);
			
			fxPlayer.setMute(true);
			
			LOGGER.debug("Loading video for {} ; Set up: {}ms", () -> filename, () -> timer.split());
			
			fxPlayer.setOnReady(reportError(() ->
			{
				synchronized (FXImageVideoWrapper.this)
				{
					progress.set(1);
					LOGGER.debug("Loading video for {} ; Ready: {}ms", () -> filename, () -> timer.splitAndStop());
					LOGGER.debug("Loading video for {} ; Total: {}ms", () -> filename, () -> timer.time());
				}
			}));
		}
		else
		{
			fxPlayer = null;
			fxImage = new javafx.scene.image.Image(url, true);
			exception.bind(fxImage.exceptionProperty());
			progress.bind(fxImage.progressProperty());
		}
	}
	
	public void cancel()
	{
		if (fxImage != null)
		{
			fxImage.cancel();
		}
		else
		{
			synchronized (this)
			{
				if (progress.get() < 1)
				{
					LOGGER.debug("Loading video for {} ; Cancelled", filename);
					fxPlayer.dispose();
					exception.set(new CancellationException("Loading cancelled"));
				}
			}
		}
	}
	
	public final ReadOnlyObjectProperty<Exception> exceptionProperty()
	{
		return exception.getReadOnlyProperty();
	}
	
	public ReadOnlyDoubleProperty getProgressProperty()
	{
		return progress.getReadOnlyProperty();
	}
	
	public boolean isVideo()
	{
		return fxPlayer != null;
	}
	
	public javafx.scene.image.Image getAsFxImage()
	{
		if (isVideo())
			throw new IllegalStateException(absPath+" is not an image");
		
		return fxImage;
	}
	
	public MediaPlayer getAsFxVideo()
	{
		if (!isVideo())
			throw new IllegalStateException(absPath+" is not a video");
		
		return fxPlayer;
	}
	
	private Runnable reportError(Runnable runnable)
	{
		return () ->
		{
			try {
				runnable.run();
			}
			catch (Exception e)
			{
				LOGGER.debug("Loading video for "+filename+" ; Error", e);
				exception.set(e);
			}
		};
	}
}