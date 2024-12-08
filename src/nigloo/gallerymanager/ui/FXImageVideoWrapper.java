package nigloo.gallerymanager.ui;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.CancellationException;

import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
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

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;

public class FXImageVideoWrapper
{
	private static final Logger LOGGER = LogManager.getLogger(FXImageVideoWrapper.class);
	
	private final Path absPath;
	private final String filename;
	private final javafx.scene.image.Image fxImage;
	private final MediaPlayer fxPlayer;
	private final ReadOnlyObjectWrapper<Exception> exception;
	private final ReadOnlyDoubleWrapper progress;

	private int originalWidth = 0;
	private int originalHeight = 0;
	
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
			fxPlayer.setAutoPlay(false);
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

			// Displaying a large image smaller in an ImageView results in an aliased image...
			// So we load the image smaller if it's way bigger than the screen (to avoid aliased resizing by ImageView)
			int[] dim = getImageDim(absPath);
			originalWidth = dim[0];
			originalHeight = dim[1];
			Rectangle2D screen = Screen.getPrimary().getBounds();
			int screenWidth = (int) screen.getWidth();
			int screenHeight = (int) screen.getHeight();
			if (originalWidth > screenWidth*2 || originalHeight > screenHeight*2)
			{
				LOGGER.trace("Loading {} at smaller size: {}x{} ; Original size: {}x{}", absPath, screenWidth, screenHeight, originalWidth, originalHeight);
				fxImage = new javafx.scene.image.Image(
						url,
						screenWidth,
						screenHeight,
						true,
						true,
						true);
			}
			else
			{
				fxImage = new javafx.scene.image.Image(url, true);
			}


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
				// Always "dispose" otherwise the file is locked (we can't move it for example)
				LOGGER.debug("Loading video for {} ; Cancelled", filename);
				fxPlayer.dispose();
				exception.set(new CancellationException("Loading cancelled"));
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

	public final int getOriginalWidth()
	{
		return isVideo() ? this.fxPlayer.getMedia().getWidth() : originalWidth;
	}
	public final int getOriginalHeight()
	{
		return isVideo() ? this.fxPlayer.getMedia().getHeight() : originalHeight;
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

	// Get an image dimensions without fully loading it
	// From https://stackoverflow.com/a/2911772
	private static int[] getImageDim(Path path)
	{
		String filename = path.getFileName().toString();
		int posExt = filename.lastIndexOf('.');
		String ext = posExt >= 0 ? filename.substring(posExt + 1) : "";
		if (ext.equals("jpe"))
			ext = "jpg";
		Iterator<ImageReader> iter = ImageIO.getImageReadersBySuffix(ext);

		if (iter.hasNext())
		{
			ImageReader reader = iter.next();
			try
			{
				ImageInputStream stream = new FileImageInputStream(path.toFile());
				reader.setInput(stream);
				int width = reader.getWidth(reader.getMinIndex());
				int height = reader.getHeight(reader.getMinIndex());
				return new int[] {width, height};
			}
			catch (IOException e)
			{
				LOGGER.error("Error when loading size of image "+path, e);
			}
			finally
			{
				reader.dispose();
			}
		}
		else
		{
			LOGGER.warn("No reader found for given format: " + (ext.isEmpty() ? "(no extention)" : ext) + " of image " + path);
		}
        return new int[] {0, 0};
    }
}