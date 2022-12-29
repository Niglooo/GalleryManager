package nigloo.gallerymanager.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javafx.animation.Animation.Status;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectPropertyBase;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;
import javafx.scene.robot.Robot;
import javafx.scene.text.Text;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.Duration;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Image;
import nigloo.gallerymanager.model.Tag;
import nigloo.gallerymanager.ui.util.ImageCache;
import nigloo.tool.StrongReference;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;
import nigloo.tool.javafx.FXUtils;
import nigloo.tool.thread.SafeThread;
import nigloo.tool.thread.ThreadStopException;

public class SlideShowStage extends Stage
{
	private static final int NO_CURRENT_IMAGE_INDEX = -1;
	
	private static Rectangle2D screenSize = Screen.getPrimary().getBounds();
	
	@Inject
	private Gallery gallery;
	@Inject
	private ImageCache imageCache;
	
	private List<Image> imagesOrdered = null;
	private List<Image> images = null;
	private volatile int currentImageIdx;
	
	private final CurrentImageProperty currentImageProperty = new CurrentImageProperty();
	
	private final StackPane contentRoot;
	private final ImageView imageView;
	private final MediaView mediaView;
	private final InfoZone infoZone;
	
	private final SimpleBooleanProperty altDown;
	
	private final Timeline autoplay;
	private final ImageLoaderDaemon fullImageUpdatingThread;
	

	public SlideShowStage(List<Image> images, int startingIndex)
	{
		assert images.size() > 0;
		assert startingIndex >= 0 && startingIndex < images.size();
		
		Injector.init(this);
		
		this.imagesOrdered = List.copyOf(images);
		
		currentImageIdx = NO_CURRENT_IMAGE_INDEX;
		int firstImageIdx;
		
		if (gallery.getSlideShowParameter().isShuffled())
		{
			this.images = new ArrayList<>(imagesOrdered);
			Collections.shuffle(this.images);
			firstImageIdx = this.images.indexOf(imagesOrdered.get(startingIndex));
		}
		else
		{
			this.images = this.imagesOrdered;
			firstImageIdx = startingIndex;
		}
		
		imageView = new ImageView();
		imageView.setFitWidth(screenSize.getWidth());
		imageView.setFitHeight(screenSize.getHeight());
		imageView.setPreserveRatio(true);
		imageView.setSmooth(true);
		
		mediaView = new MediaView();
		mediaView.setFitWidth(screenSize.getWidth());
		mediaView.setFitHeight(screenSize.getHeight());
		mediaView.setPreserveRatio(true);
		mediaView.setSmooth(true);
		
		
		fullImageUpdatingThread = new ImageLoaderDaemon();
		
		//Don't call next() as it also reset autoplay timer resulting in a double call
		autoplay = new Timeline(new KeyFrame(Duration.seconds(1), event -> setCurrent(validIndex(currentImageIdx, 1))));
		autoplay.setCycleCount(Timeline.INDEFINITE);
		setAutoplayDelay(gallery.getSlideShowParameter().getAutoplayDelay());
		
		contentRoot = new StackPane(imageView, mediaView);
		contentRoot.setId("slide_show_content");
		contentRoot.setCursor(Cursor.NONE);
		setScene(new Scene(contentRoot));
		getScene().getStylesheets().add(UIController.STYLESHEET_DEFAULT);
		
		infoZone = new InfoZone();
		infoZone.setVisible(false);
		contentRoot.getChildren().add(infoZone);
		StackPane.setAlignment(infoZone, Pos.TOP_LEFT);
		StackPane.setMargin(infoZone, new Insets(10));
		
		SlideShowContextMenu contextMenu = new SlideShowContextMenu(this);
		contextMenu.setHideOnEscape(true);
		
		altDown = new SimpleBooleanProperty(false);
		contentRoot.cursorProperty().bind(new ObjectBinding<Cursor>() {{
				bind(altDown, contextMenu.showingProperty());
			}
			@Override protected Cursor computeValue() {
				return altDown.get() || contextMenu.isShowing() ? null : Cursor.NONE;
			}
		});
		
		infoZone.visibleProperty().bind(altDown);
		
		addEventHandler(KeyEvent.KEY_PRESSED, event ->
		{
			altDown.set(event.isAltDown());
			
			if (event.getCode() == KeyCode.ESCAPE)
				close();
			else if (event.getCode() == KeyCode.LEFT)
				previous();
			else if (event.getCode() == KeyCode.RIGHT)
				next();
			else if (event.getCode() == KeyCode.SPACE)
			{
				setAutoPlay(!isAutoPlay());
				contextMenu.updateItems();
			}
		});
		addEventHandler(KeyEvent.KEY_RELEASED, event -> altDown.set(event.isAltDown()));
		Robot robot = new Robot();
		addEventHandler(MouseEvent.MOUSE_RELEASED, event ->
		{
			if (event.getButton() == MouseButton.SECONDARY)
			{
				double x, y;
				// If the cursor isn't visible, center it
				if (contentRoot.getCursor() == Cursor.NONE)
				{
					x = getWidth() / 2;
					y = getHeight() / 2;
					
					// Calling just Platform.runLater doesn't work...
					// My guess is that robot.mouseMove is called too early when the cursor still isn't visible, and so does nothing
					StrongReference<Runnable> r = new StrongReference<>();
					r.set(() -> {
						Platform.runLater(() -> robot.mouseMove(x, y));
						getScene().removePostLayoutPulseListener(r.get());
					});
					getScene().addPostLayoutPulseListener(r.get());
				}
				else
				{
					x = event.getScreenX();
					y = event.getScreenY();
				}
				
				contextMenu.show(contentRoot, x, y);
			}
		});
		addEventHandler(MouseEvent.MOUSE_PRESSED, event ->
		{
			if (event.getButton() == MouseButton.PRIMARY || event.getButton() == MouseButton.SECONDARY)
				contextMenu.hide();
		});
		
		addEventHandler(WindowEvent.WINDOW_SHOWN, event -> fullImageUpdatingThread.start());
		
		setFullScreenExitHint("");
		setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);
		setFullScreen(true);
		
		setCurrent(firstImageIdx);
		
		if (isAutoPlay())
			autoplay.playFromStart();
	}
	
	@Override
	public void close()
	{
		fullImageUpdatingThread.safeStop();
		autoplay.stop();
		super.close();
	}
	
	public ReadOnlyObjectProperty<Image> currentImageProperty()
	{
		return currentImageProperty;
	}
	
	public Image getCurrentImage()
	{
		return currentImageProperty.get();
	}
	//TODO add "nextPost" (go to next sibling folder
	public void next()
	{
		autoplay.jumpTo(Duration.ZERO);
		setCurrent(validIndex(currentImageIdx, 1));
	}
	
	public void previous()
	{
		autoplay.jumpTo(Duration.ZERO);
		setCurrent(validIndex(currentImageIdx, -1));
	}
	
	public void setAutoPlay(boolean autoplay)
	{
		if (autoplay && !isAutoPlay())
			this.autoplay.playFromStart();
		else if (!autoplay && isAutoPlay())
			this.autoplay.stop();
		
		gallery.getSlideShowParameter().setAutoplay(autoplay);
	}
	
	public boolean isAutoPlay()
	{
		return gallery.getSlideShowParameter().isAutoplay();
	}
	
	public void setShuffled(boolean shuffled)
	{
		if (shuffled == gallery.getSlideShowParameter().isShuffled())
			return;
		
		gallery.getSlideShowParameter().setShuffled(shuffled);
		
		boolean playing = autoplay.getStatus() == Status.RUNNING;
		autoplay.stop();
		if (shuffled) // ordered -> shuffled
		{
			List<Image> shuffledImages = new ArrayList<>(imagesOrdered);
			Collections.shuffle(shuffledImages);
			images = shuffledImages;
			setCurrent(0);
		}
		else // shuffled -> ordered
		{
			int index = imagesOrdered.indexOf(images.get(currentImageIdx));
			images = imagesOrdered;
			setCurrent(index);
		}
		if (playing)
			autoplay.playFromStart();
		fullImageUpdatingThread.forceRefresh();
	}
	
	public boolean isShuffled()
	{
		return gallery.getSlideShowParameter().isShuffled();
	}
	
	public boolean isLooped()
	{
		return gallery.getSlideShowParameter().isLooped();
	}
	
	public void setLooped(boolean looped)
	{
		gallery.getSlideShowParameter().setLooped(looped);
	}
	
	public double getAutoplayDelay()
	{
		return gallery.getSlideShowParameter().getAutoplayDelay();
	}
	
	public void setAutoplayDelay(double autoplayDelay)
	{
		assert autoplayDelay > 0;
		
		gallery.getSlideShowParameter().setAutoplayDelay(autoplayDelay);
		boolean playing = autoplay.getStatus() == Status.RUNNING;
		autoplay.stop();
		autoplay.setRate(1 / autoplayDelay);
		if (playing)
			autoplay.playFromStart();
	}
	
	int validIndex(int index, int offset)
	{
		assert index >= 0 && index < images.size();
		
		int nbImages = images.size();
		index += offset;
		
		if (index < 0)
		{
			if (gallery.getSlideShowParameter().isLooped())
			{
				while (index < 0)
					index += nbImages;
			}
			else
				index = 0;
		}
		else if (index >= nbImages)
		{
			if (gallery.getSlideShowParameter().isLooped())
				index %= nbImages;
			else
				index = nbImages - 1;
		}
		while (index < 0)
			index += nbImages;
		return index % nbImages;
	}
	//TODO add video control (play pause rewind, volume) visible when pressing alt (pousse action on video control then
	//TODO add possibility to (un)zoom
	private void setCurrent(int index)
	{
		assert index >= 0 && index < images.size();
		assert Platform.isFxApplicationThread();
		
		if (currentImageIdx != NO_CURRENT_IMAGE_INDEX)
		{
			Image previousImage = images.get(currentImageIdx);
			if (previousImage.isActuallyVideo())
			{
				imageCache.getAsyncFXImageVideo(previousImage).getAsFxVideo().stop();
			}
		}
		
		currentImageIdx = index;
		Image currentImage = images.get(currentImageIdx);
		
		FXImageVideoWrapper fxImageVideo = imageCache.getAsyncFXImageVideo(currentImage);
		
		if (fxImageVideo.getProgressProperty().get() >= 1)
		{
			if (fxImageVideo.isVideo())
			{
				imageView.setVisible(false);
				mediaView.setVisible(true);
			
				MediaPlayer video = fxImageVideo.getAsFxVideo();
				mediaView.setMediaPlayer(video);//FIXME concurrent modification excaption here in Application Tghread (https://bugs.openjdk.org/browse/JDK-8146918)
				video.setCycleCount(Integer.MAX_VALUE);
				video.play();
			}
			else
			{
				imageView.setVisible(true);
				mediaView.setVisible(false);
				imageView.setImage(fxImageVideo.getAsFxImage());
			}
		}
		else
		{
			imageView.setVisible(true);
			mediaView.setVisible(false);
			
			javafx.scene.image.Image thumbnail = imageCache.getThumbnail(currentImage, false);
			imageView.setImage(thumbnail);
		}
		infoZone.setImage(currentImage);
		infoZone.updateImageLoadingInfo(fxImageVideo);
		
		currentImageProperty.fireValueChangedEvent();
	}
	
	private static class InfoZone extends VBox
	{
		private final Text imagePath;
		private final Text imageSize;
		private final VBox imageTags;
		
		public InfoZone()
		{
			getStyleClass().add("info-zone");
			
			imagePath = new Text();
			imagePath.getStyleClass().add("image-path");
			
			imageSize = new Text();
			imagePath.getStyleClass().add("image-size");
			
			imageTags = new VBox();
			imageTags.getStyleClass().add("tag-list");
			
			getChildren().setAll(imagePath, imageSize, imageTags);
		}
		
		public void setImage(Image image)
		{
			imagePath.setText(image.getPath().toString());
			imageTags.getChildren().clear();
			image.getTags().stream().sorted(Comparator.comparing(Tag::getName)).forEachOrdered(tag ->
			{
				Color tagColor = tag.getColor();
				
				Text tagText = new Text(tag.getName());
				tagText.getStyleClass().add("tag");
				if (tagColor != null)
					tagText.setStyle("-fx-fill: " + FXUtils.toRGBA(tagColor) + ";");
				
				imageTags.getChildren().add(tagText);
			});
		}
		
		public void updateImageLoadingInfo(FXImageVideoWrapper fxImageVideo)
		{
			int width = 0;
			int height = 0;
			
			if (fxImageVideo.getProgressProperty().get() >= 1)
			{
				if (fxImageVideo.isVideo())
				{
					width = fxImageVideo.getAsFxVideo().getMedia().getWidth();
					height = fxImageVideo.getAsFxVideo().getMedia().getHeight();
				}
				else
				{
					width = (int) fxImageVideo.getAsFxImage().getWidth();
					height = (int) fxImageVideo.getAsFxImage().getHeight();
				}
			}
			
			String w = width > 0 ? "%d".formatted(width) : "???";
			String h = height > 0 ? "%d".formatted(height) : "???";
			
			imageSize.setText(w + "x" + h);
		}
	}
	
	private class CurrentImageProperty extends ReadOnlyObjectPropertyBase<Image>
	{
		@Override
		public Object getBean()
		{
			return SlideShowStage.this;
		}
		
		@Override
		public String getName()
		{
			return "currentImage";
		}
		
		@Override
		public Image get()
		{
			return images.get(currentImageIdx);
		}
		
		@Override
		public void fireValueChangedEvent()
		{
			super.fireValueChangedEvent();
		}
	}
	
	private class ImageLoaderDaemon extends SafeThread
	{
		private final AtomicBoolean forceRefresh = new AtomicBoolean(false);
		
		public ImageLoaderDaemon()
		{
			super("slide-show-image-loader");
			setDaemon(true);
		}
		
		public void forceRefresh()
		{
			forceRefresh.set(true);
		}
		
		@Override
		public void run()
		{
			List<Image> previousImagesToLoad = List.of();
			try
			{
				mainLoop:
				while (true)
				{
					SafeThread.checkThreadState();
					
					int current = currentImageIdx;
					List<Image> imagesToLoad = List.of(images.get(validIndex(current, 0)),
					                                   images.get(validIndex(current, 1)),
					                                   images.get(validIndex(current, -1)),
					                                   images.get(validIndex(current, 2)),
					                                   images.get(validIndex(current, 3)));
					
					for (Image image : previousImagesToLoad)
					{
						if (!imagesToLoad.contains(image))
							imageCache.cancelLoadingFXImageVideo(image);
					}
					
					for (Image image : imagesToLoad)
					{
						SafeThread.checkThreadState();
						
						FXImageVideoWrapper fxImageVideo = imageCache.getAsyncFXImageVideo(image);
						
						if (current != currentImageIdx || forceRefresh.compareAndSet(true, false))
						{
							previousImagesToLoad = imagesToLoad;
							continue mainLoop;
						}
						
						while (fxImageVideo.getProgressProperty().get() < 1)
						{
							SafeThread.checkThreadState();
							Thread.sleep(100);
							
							if (current != currentImageIdx || forceRefresh.compareAndSet(true, false))
							{
								previousImagesToLoad = imagesToLoad;
								continue mainLoop;
							}
						}
						
						if (fxImageVideo.getProgressProperty().get() >= 1 && image.equals(images.get(currentImageIdx)))
						{
							if (fxImageVideo.isVideo())
							{
								imageView.setVisible(false);
								mediaView.setVisible(true);
								
								MediaPlayer video = fxImageVideo.getAsFxVideo();
								mediaView.setMediaPlayer(video);
								video.setCycleCount(Integer.MAX_VALUE);
								video.play();
							}
							else
							{
								imageView.setVisible(true);
								mediaView.setVisible(false);
								
								imageView.setImage(fxImageVideo.getAsFxImage());
							}
							infoZone.updateImageLoadingInfo(fxImageVideo);
						}
					}
					
					while (current == currentImageIdx && !forceRefresh.get())
					{
						SafeThread.checkThreadState();
						Thread.sleep(100);
					}
				}
			}
			catch (ThreadStopException | InterruptedException e)
			{
			}
			finally
			{
				for (Image image : previousImagesToLoad)
					imageCache.cancelLoadingFXImageVideo(image);
			}
		}
	}
}
