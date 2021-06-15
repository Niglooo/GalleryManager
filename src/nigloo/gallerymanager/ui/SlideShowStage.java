package nigloo.gallerymanager.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javafx.animation.Animation.Status;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.Duration;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Image;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;
import nigloo.tool.thread.SafeThread;
import nigloo.tool.thread.ThreadStopException;

public class SlideShowStage extends Stage
{
	static private Rectangle2D screenSize = Screen.getPrimary().getBounds();
	
	@Inject
	private Gallery gallery;
	
	private List<Image> imagesOrdered = null;
	private List<Image> images = null;
	private volatile int currentImageIdx;
	
	private final ImageView imageView;
	
	private final Timeline autoplay;
	private final FullImageUpdatingThread fullImageUpdatingThread;
	
	public SlideShowStage(List<Image> images, int startingIndex) throws IOException
	{
		assert images.size() > 0;
		assert startingIndex >= 0 && startingIndex < images.size();
		
		Injector.init(this);
		
		this.imagesOrdered = List.copyOf(images);
		
		if (gallery.getSlideShowParameter().isShuffled())
		{
			this.images = new ArrayList<>(imagesOrdered);
			Collections.shuffle(this.images);
			currentImageIdx = this.images.indexOf(imagesOrdered.get(startingIndex));
		}
		else
		{
			this.images = this.imagesOrdered;
			this.currentImageIdx = startingIndex;
		}
		
		imageView = new ImageView();
		imageView.setFitWidth(screenSize.getWidth());
		imageView.setFitHeight(screenSize.getHeight());
		imageView.setPreserveRatio(true);
		imageView.setSmooth(true);
		
		fullImageUpdatingThread = new FullImageUpdatingThread();
		
		autoplay = new Timeline(new KeyFrame(Duration.seconds(1), event -> next()));
		autoplay.setCycleCount(Timeline.INDEFINITE);
		setAutoplayDelay(gallery.getSlideShowParameter().getAutoplayDelay());
		
		StackPane contentRoot = new StackPane(imageView);
		contentRoot.setBackground(new Background(new BackgroundFill(Color.BLACK, null, null)));
		contentRoot.setCursor(Cursor.NONE);
		setScene(new Scene(contentRoot));
		
		SlideShowContextMenu contextMenu = new SlideShowContextMenu(this);
		contextMenu.setHideOnEscape(true);
		
		contextMenu.addEventHandler(WindowEvent.WINDOW_HIDDEN, event -> contentRoot.setCursor(Cursor.NONE));
		
		addEventHandler(KeyEvent.KEY_PRESSED, event ->
		{
			if (event.getCode() == KeyCode.ESCAPE)
				close();
			else if (event.getCode() == KeyCode.LEFT)
				previous();
			else if (event.getCode() == KeyCode.RIGHT)
				next();
		});
		addEventHandler(MouseEvent.MOUSE_RELEASED, event ->
		{
			if (event.getButton() == MouseButton.SECONDARY)
			{
				contentRoot.setCursor(Cursor.DEFAULT);
				contextMenu.show(contentRoot, event.getScreenX(), event.getScreenY());
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
		
		setCurrent(currentImageIdx);
		
		autoplay.playFromStart();
	}
	
	@Override
	public void close()
	{
		// TODO Scroll to last slideshow image visible in thumbnailview
		fullImageUpdatingThread.safeStop();
		autoplay.stop();
		super.close();
	}
	
	public void next()
	{
		setCurrent(validIndex(currentImageIdx, 1));
	}
	
	public void previous()
	{
		setCurrent(validIndex(currentImageIdx, -1));
	}
	
	public void setAutoPlay(boolean autoplay)
	{
		if (autoplay && this.autoplay.getStatus() != Status.RUNNING)
			this.autoplay.playFromStart();
		else if (!autoplay && this.autoplay.getStatus() == Status.RUNNING)
			this.autoplay.stop();
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
	
	private void setCurrent(int index)
	{
		assert index >= 0 && index < images.size();
		
		currentImageIdx = index;
		javafx.scene.image.Image fxImage = images.get(currentImageIdx).getFXImage(true);
		
		if (fxImage.getProgress() >= 1)
			imageView.setImage(fxImage);
		else
		{
			javafx.scene.image.Image thumbnail = images.get(currentImageIdx).getThumbnail(false);
			imageView.setImage(thumbnail);
		}
	}
	
	private class FullImageUpdatingThread extends SafeThread
	{
		private final AtomicBoolean forceRefresh = new AtomicBoolean(false);
		
		public FullImageUpdatingThread()
		{
			super("slide-show-deamon");
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
					checkThreadState();
					
					int current = currentImageIdx;
					List<Image> imagesToLoad = List.of(images.get(validIndex(current, 0)),
					                                   images.get(validIndex(current, 1)),
					                                   images.get(validIndex(current, -1)),
					                                   images.get(validIndex(current, 2)));
					
					previousImagesToLoad.forEach(image ->
					{
						if (!imagesToLoad.contains(image))
							image.cancelLoadingFXImage();
					});
					
					for (Image image : imagesToLoad)
					{
						checkThreadState();
						
						javafx.scene.image.Image fxImage = image.getFXImage(true);
						
						if (current != currentImageIdx || forceRefresh.compareAndSet(true, false))
						{
							previousImagesToLoad = imagesToLoad;
							continue mainLoop;
						}
						
						while (fxImage.getProgress() < 1)
						{
							checkThreadState();
							Thread.sleep(100);
							
							if (current != currentImageIdx || forceRefresh.compareAndSet(true, false))
							{
								previousImagesToLoad = imagesToLoad;
								continue mainLoop;
							}
						}
						
						if (fxImage.getProgress() >= 1 && image.equals(images.get(currentImageIdx)))
							imageView.setImage(fxImage);
					}
					
					while (current == currentImageIdx && !forceRefresh.get())
					{
						checkThreadState();
						Thread.sleep(100);
					}
				}
			}
			catch (ThreadStopException | InterruptedException e)
			{
			}
			finally
			{
				previousImagesToLoad.forEach(image -> image.cancelLoadingFXImage());
			}
		}
	}
}
