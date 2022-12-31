package nigloo.gallerymanager.ui;

import java.nio.file.Path;
import java.util.concurrent.CancellationException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javafx.geometry.Rectangle2D;
import javafx.scene.SnapshotParameters;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import nigloo.gallerymanager.ui.util.CustomImage;
import nigloo.tool.StopWatch;

public class VideoThumbnailImage extends CustomImage
{
	private static final Logger LOGGER = LogManager.getLogger(VideoThumbnailImage.class);
	
	// Keep a reference so it's not garbage collected before the image is loaded
	private MediaView mView;
	
	private final String filename;
	
	public VideoThumbnailImage(int width, int height, Path source)
	{
		super(width, height);
		
		filename = source.getFileName().toString();
		
		StopWatch timer = LOGGER.isDebugEnabled() ? new StopWatch().start() : null;
		
		try {
			final double PERCENT = 10;
			
			final Media media = new Media(source.toUri().toString());
			final MediaPlayer player = new MediaPlayer(media);
			mView = new MediaView(player);
			
			player.setMute(true);
			mView.setFitWidth(width);
			mView.setFitHeight(height);
			mView.setPreserveRatio(true);
			mView.setSmooth(true);
			
			LOGGER.debug("Loading video thumbnail for {} ; Set up: {}ms", () -> filename, () -> timer.split());
			
			player.setOnReady(reportError(() ->
			{
				player.seek(Duration.millis(player.getTotalDuration().toMillis() * PERCENT / 100));
				player.play();
				loadingProgress.set(0.4);
				LOGGER.debug("Loading video thumbnail for {} ; Ready: {}ms", () -> filename, () -> timer.split());
			}));
			
			player.setOnPlaying(reportError(() ->
			{
				player.pause();
				loadingProgress.set(0.8);
				LOGGER.debug("Loading video thumbnail for {} ; Playing: {}ms", () -> filename, () -> timer.split());
			}));
			
			player.setOnPaused(reportError(() ->
			{
				synchronized(VideoThumbnailImage.this)
				{
					int actualWidth = (int) mView.getBoundsInLocal().getWidth();
					int actualHeight = (int) mView.getBoundsInLocal().getHeight();
					
					SnapshotParameters params = new SnapshotParameters();
					params.setFill(Color.TRANSPARENT);
					params.setViewport(new Rectangle2D(-(width - actualWidth) / 2,
					                                   -(height - actualHeight) / 2,
					                                   actualWidth,
					                                   actualHeight));
					mView.snapshot(params, VideoThumbnailImage.this);
					
					player.dispose();
					mView = null;
					loadingProgress.set(1);
				}
				LOGGER.debug("Loading video thumbnail for {} ; Paused: {}ms", () -> filename, () -> timer.splitAndStop());
				LOGGER.debug("Loading video thumbnail for {} ; Total: {}ms", () -> filename, () -> timer.time());
			}));
		}
		catch (Exception e) {
			loadingException.set(e);
		}
	}
	
	@Override
	public void cancel()
	{
		synchronized(this)
		{
			if (mView != null && loadingProgress.get() < 1) {
				LOGGER.debug("Loading video thumbnail for {} ; Cancelled", filename);
				mView.getMediaPlayer().dispose();
				mView = null;
				loadingException.set(new CancellationException("Loading cancelled"));
			}
		}
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
				loadingException.set(e);
			}
		};
	}
}