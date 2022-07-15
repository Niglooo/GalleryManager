package nigloo.gallerymanager.model;

import java.lang.ref.SoftReference;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Rectangle2D;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import nigloo.tool.StopWatch;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;

public class Image
{
	long id;
	private Path path;
	private Set<TagReference> tags = new HashSet<>();
	
	private transient Set<String> implicitTags = null;
	//TODO Refactor out image cache (or at least the loading/cancel logic)
	private transient SoftReference<javafx.scene.image.Image> thumbnailCache = null;
	private transient SoftReference<FXImageVideoWrapper> fxImageVideoCache = null;
	
	@Inject
	private transient Gallery gallery;
	
	private Image()
	{
		Injector.init(this);
	}
	
	Image(Path path)
	{
		this();
		this.id = -1;
		this.path = path;
	}
	
	public void move(Path target)
	{
		if (target.isAbsolute())
			throw new IllegalArgumentException("target must be relative. Got: " + target);
		
		path = target;
		
		if (isNotSaved())
			gallery.unsavedImagesValid = false;
	}
	
	public boolean isSaved()
	{
		return this.id > 0;
	}
	
	public boolean isNotSaved()
	{
		return this.id <= 0;
	}
	
	public long getId()
	{
		return id;
	}
	
	public Path getPath()
	{
		return path;
	}
	
	public Path getAbsolutePath()
	{
		return gallery.toAbsolutePath(path);
	}
	
	public Collection<Tag> getTags()
	{
		return tags.stream().map(TagReference::getTag).collect(Collectors.toUnmodifiableSet());
	}
	
	public boolean addTag(Tag tag)
	{
		boolean added = tags.add(new TagReference(tag));
		if (added)
			implicitTags = null;
		
		return added;
	}
	
	public boolean addTag(String tagName)
	{
		boolean added = tags.add(new TagReference(gallery.getTag(tagName)));
		if (added)
			implicitTags = null;
		
		return added;
	}
	
	public boolean removeTag(Tag tag)
	{
		boolean removed = tags.remove(new TagReference(tag));
		if (removed)
			implicitTags = null;
		
		return removed;
	}
	
	public boolean removeTag(String tagName)
	{
		boolean removed = tags.remove(new TagReference(tagName));
		if (removed)
			implicitTags = null;
		
		return removed;
	}
	
	public Set<String> getImplicitTags()
	{
		if (implicitTags == null)
		{
			implicitTags = new HashSet<>();
			ArrayDeque<Tag> patentsToVisit = new ArrayDeque<>(tags.stream().map(TagReference::getTag).toList());
			
			Tag tag;
			while ((tag = patentsToVisit.poll()) != null)
				if (implicitTags.add(tag.getName()))
					patentsToVisit.addAll(tag.getParents());
			
			implicitTags = Collections.unmodifiableSet(implicitTags);
		}
		
		return implicitTags;
	}
	
	private static final int THUMBNAIL_IMAGE_SIZE = 300;

	public javafx.scene.image.Image getThumbnail(boolean async)
	{
		javafx.scene.image.Image thumbnail = (thumbnailCache == null) ? null : thumbnailCache.get();
		if (thumbnail == null || thumbnail.isError())
		{
			if (!isActuallyVideo())
			{
				try
				{
					String imageUrl = gallery.toAbsolutePath(path).toUri().toURL().toString();
					thumbnail = new javafx.scene.image.Image(imageUrl, THUMBNAIL_IMAGE_SIZE, THUMBNAIL_IMAGE_SIZE, true, true, async);
				}
				catch (MalformedURLException e)
				{
					throw new RuntimeException(e);
				}
			}
			else
			{
				thumbnail = new VideoThumbnailImage(THUMBNAIL_IMAGE_SIZE, THUMBNAIL_IMAGE_SIZE, getAbsolutePath());
			}
			
			thumbnailCache = new SoftReference<javafx.scene.image.Image>(thumbnail);
		}
		
		return thumbnail;
	}
	//TODO Move out
	public static class VideoThumbnailImage extends WritableImage
	{
		private static final Logger LOGGER = LogManager.getLogger(VideoThumbnailImage.class);
		
		// Keep a reference so it's not garbage collected before the image is loaded
		private MediaView mView;
		
		private final String filename;
		
		private final ReadOnlyObjectWrapper<Exception> loadingException;
		private final ReadOnlyDoubleWrapper loadingProgress;
		
		public VideoThumbnailImage(int width, int height, Path source)
		{
			super(width, height);
			
			filename = source.getFileName().toString();
			
			loadingException = new ReadOnlyObjectWrapper<>(this, "loadingException");
			loadingProgress = new ReadOnlyDoubleWrapper(this, "loadingProgress");
			
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
						
						player.stop();
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
		
		public final ReadOnlyObjectProperty<Exception> loadingExceptionProperty()
		{
			return loadingException.getReadOnlyProperty();
		}
		
		public ReadOnlyDoubleProperty loadingProgressProperty()
		{
			return loadingProgress.getReadOnlyProperty();
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
	
	public void cancelLoadingThumbnail()
	{
		javafx.scene.image.Image thumbnail = (thumbnailCache == null) ? null : thumbnailCache.get();
		if (thumbnail != null)
			thumbnail.cancel();
	}
	
	public FXImageVideoWrapper getAsyncFXImageVideo()
	{
		FXImageVideoWrapper fxImageVideo = (fxImageVideoCache == null) ? null : fxImageVideoCache.get();
		if (fxImageVideo == null || fxImageVideo.exceptionProperty().get() != null)
		{
			fxImageVideo = new FXImageVideoWrapper(gallery.toAbsolutePath(path));
			fxImageVideoCache = new SoftReference<FXImageVideoWrapper>(fxImageVideo);
		}
		
		return fxImageVideo;
	}
	
	public void cancelLoadingFXImage()
	{
		FXImageVideoWrapper fxImageVideo = (fxImageVideoCache == null) ? null : fxImageVideoCache.get();
		if (fxImageVideo != null)
			fxImageVideo.cancel();
	}
	
	public static class FXImageVideoWrapper
	{
		private static final Logger LOGGER = LogManager.getLogger(VideoThumbnailImage.class);
		
		private final Path absPath;
		private final String filename;
		private final javafx.scene.image.Image fxImage;
		private final MediaPlayer fxPlayer;
		private final ReadOnlyObjectWrapper<Exception> exception;
		private final ReadOnlyDoubleWrapper progress;
		
		private FXImageVideoWrapper(Path absPath)
		{
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
			
			if (isActuallyVideo(absPath))
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
	
	@Override
	public String toString()
	{
		StringBuilder sb = new StringBuilder();
		sb.append("{id=");
		sb.append(id);
		sb.append(", path=");
		sb.append(path);
		javafx.scene.image.Image thumbnail;
		if (thumbnailCache != null && (thumbnail = thumbnailCache.get()) != null)
		{
			sb.append(", thumbnail(");
			sb.append((int) (thumbnail.getProgress() * 100));
			sb.append("%)");
		}
		FXImageVideoWrapper fxImageVideo;
		if (fxImageVideoCache != null && (fxImageVideo = fxImageVideoCache.get()) != null)
		{
			sb.append(", fxImageVideo(");
			sb.append((int) (fxImageVideo.getProgressProperty().get() * 100));
			sb.append("%)");
		}
		sb.append("}");
		
		return sb.toString();
	}
	
	public static boolean isImage(Path file)
	{
		String filename = file.getFileName().toString();
		int posExt = filename.lastIndexOf('.');
		if (posExt == -1)
			return false;
		
		String extention = filename.substring(posExt + 1).toLowerCase(Locale.ROOT);
		return extention.equals("jpg") || extention.equals("jpeg") || extention.equals("jfif")
		        || extention.equals("png") || extention.equals("gif") || isActuallyVideo(file);
	}
	
	private static boolean isActuallyVideo(Path file)
	{
		String filename = file.getFileName().toString();
		int posExt = filename.lastIndexOf('.');
		if (posExt == -1)
			return false;
		
		String extention = filename.substring(posExt + 1).toLowerCase(Locale.ROOT);
		return extention.equals("mp4");
	}
	
	public boolean isActuallyVideo()
	{
		return Image.isActuallyVideo(path);
	}
}
