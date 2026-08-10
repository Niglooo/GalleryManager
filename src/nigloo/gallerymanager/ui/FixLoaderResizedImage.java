package nigloo.gallerymanager.ui;

import javafx.geometry.Rectangle2D;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.util.Subscription;
import lombok.extern.log4j.Log4j2;
import nigloo.gallerymanager.ui.util.CustomImage;
import nigloo.tool.StopWatch;
import nigloo.tool.Utils;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;

/**
 * Exists only because {@link com.sun.javafx.iio.java2d.J2DImageLoader#load J2DImageLoader.load} assume (incorrectly)
 * that {@link java.awt.image.BufferedImage#getScaledInstance BufferedImage.getScaledInstance} returns another
 * {@link java.awt.image.BufferedImage BufferedImage} which is not true. (returns a
 * {@link sun.awt.image.ToolkitImage ToolkitImage} on my platform)
 */
@Log4j2
public class FixLoaderResizedImage extends CustomImage
{
	private final String filename;
	private final boolean preserveRatio;
	private final boolean smooth;

	private Image originalFxImage;
	private List<Subscription> listeners;
	private StopWatch timer;

	public static Image loadResizedImage(Path source, int width, int height, boolean preserveRatio, boolean smooth, boolean async) throws MalformedURLException
	{
		String ext = Utils.getExtention(source).toLowerCase(Locale.ROOT);
		if (List.of(".webp").contains(ext))
		{
			return new FixLoaderResizedImage(source, width, height, preserveRatio, smooth, async);
		}
		else
		{
			String imageUrl = source.toUri().toURL().toString();
			return new Image(imageUrl, width, height, preserveRatio, smooth, async);
		}
	}

	private FixLoaderResizedImage(Path source, int width, int height, boolean preserveRatio, boolean smooth, boolean async) throws MalformedURLException
    {
		super(width, height);
		this.filename = source.getFileName().toString();
		this.preserveRatio = preserveRatio;
		this.smooth = smooth;

		timer = log.isDebugEnabled() ? new StopWatch().start() : null;

		String imageUrl = source.toUri().toURL().toString();
		originalFxImage = new javafx.scene.image.Image(imageUrl, async);

		if (!async)
		{
			doResize();
		}
		else
		{
			loadingProgress.bind(originalFxImage.progressProperty().multiply(0.9));
			loadingException.bind(originalFxImage.exceptionProperty());

			listeners = new ArrayList<>(2);
			listeners.add(originalFxImage.progressProperty().subscribe(this::onLoadProgress));
			listeners.add(originalFxImage.exceptionProperty().subscribe(this::onLoadProgress));
		}
	}

	private void onLoadProgress()
	{
		if (originalFxImage == null)
			return;

		if (originalFxImage.getException() != null)
		{
			log.debug("Loading resized image '{}' ; Error", filename, originalFxImage.getException());
			cleanUp();
			return;
		}

		if (originalFxImage.getProgress() == 1)
		{
			loadingProgress.unbind();
			loadingException.unbind();
			doResize();
		}
	}

	private void doResize()
	{
		try
		{
			log.debug("Loading resized image '{}' ; Loading original: {}ms ({}x{})",
					  () -> filename,
					  () -> timer.split(),
					  () -> (int) originalFxImage.getWidth(),
					  () -> (int) originalFxImage.getHeight());

			ImageView iv = new ImageView();
			iv.setImage(originalFxImage);
			iv.setFitWidth(getWidth());
			iv.setFitHeight(getHeight());
			iv.setPreserveRatio(preserveRatio);
			iv.setSmooth(smooth);

			double actualWidth = iv.getBoundsInLocal().getWidth();
			double actualHeight = iv.getBoundsInLocal().getHeight();
			SnapshotParameters params = new SnapshotParameters();
			params.setFill(Color.TRANSPARENT);
			params.setViewport(new Rectangle2D(-(getWidth() - actualWidth) / 2,
											   -(getHeight() - actualHeight) / 2,
											   actualWidth,
											   actualHeight));

			iv.snapshot(params, this);
			loadingProgress.set(1);
			log.debug("Loading resized image '{}' ; Resizing: {}ms (from {}x{} to {}x{})",
					  () -> filename,
					  () -> timer.split(),
					  () -> (int) originalFxImage.getWidth(),
					  () -> (int) originalFxImage.getHeight(),
					  () -> (int) getWidth(),
					  () -> (int) getHeight());
			log.debug("Loading resized image '{}' ; Total: {}ms",
					  () -> filename,
					  () -> timer.time());
		}
		catch (Exception e)
		{
			log.debug("Loading resized image '{}' ; Error", filename, e);
			loadingException.set(e);
		}
		cleanUp();
	}

	private void cleanUp()
	{
		originalFxImage = null;
		loadingProgress.unbind();
		loadingException.unbind();
		if (listeners != null)
		{
			listeners.forEach(Subscription::unsubscribe);
			listeners = null;
		}
		timer = null;
	}

	@Override
	public void cancel()
	{
		synchronized(this)
		{
			if (loadingException.get() == null && originalFxImage != null && loadingProgress.get() < 1) {
				log.debug("Loading resized image '{}' ; Cancelled", filename);
				cleanUp();
				loadingException.set(new CancellationException("Loading cancelled"));
			}
		}
	}
}