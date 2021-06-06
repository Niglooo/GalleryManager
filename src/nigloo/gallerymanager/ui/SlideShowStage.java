package nigloo.gallerymanager.ui;

import java.util.List;

import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import nigloo.gallerymanager.model.Image;

public class SlideShowStage extends Stage
{
	static private Rectangle2D screenSize = Screen.getPrimary().getBounds();
	
	private List<Image> images = null;
	private volatile int currentImageIdx = 0;
	
	private final ImageView imageView;
	
	private final FullImageUpdatingThread fullImageUpdatingThread;
	
	public SlideShowStage(List<Image> images)
	{
		this.images = List.copyOf(images);
		
		imageView = new ImageView();
		imageView.setFitWidth(screenSize.getWidth());
		imageView.setFitHeight(screenSize.getHeight());
		imageView.setPreserveRatio(true);
		imageView.setSmooth(true);
		
		fullImageUpdatingThread = new FullImageUpdatingThread();
		
		setScene(new Scene(new StackPane(imageView)));
		getScene().setFill(Color.BLACK);
		((StackPane) getScene().getRoot()).setBackground(Background.EMPTY);
		getScene().setCursor(Cursor.NONE);
		addEventHandler(KeyEvent.KEY_PRESSED, event ->
		{
			if (event.getCode() == KeyCode.ESCAPE)
			{
				fullImageUpdatingThread.interrupt();
				close();
			}
			else if (event.getCode() == KeyCode.LEFT)
				setCurrent(currentImageIdx - 1);
			else if (event.getCode() == KeyCode.RIGHT)
				setCurrent(currentImageIdx + 1);
		});
		addEventHandler(WindowEvent.WINDOW_SHOWN, event -> fullImageUpdatingThread.start());
		setFullScreenExitHint("");
		setFullScreen(true);
		
		setCurrent(currentImageIdx);
	}
	
	int validIndex(int index)
	{
		int nbImages = images.size();
		while (index < 0)
			index += nbImages;
		return index % nbImages;
	}
	
	private void setCurrent(int index)
	{
		currentImageIdx = validIndex(index);
		javafx.scene.image.Image fxImage = images.get(currentImageIdx).getFXImage(true);
		
		if (fxImage.getProgress() >= 1)
			imageView.setImage(fxImage);
		else
		{
			javafx.scene.image.Image thumbnail = images.get(currentImageIdx).getThumbnail(false);
			imageView.setImage(thumbnail);
		}
	}
	
	private class FullImageUpdatingThread extends Thread
	{
		public FullImageUpdatingThread()
		{
			super("slide-show-deamon");
			setDaemon(true);
		}
		
		@Override
		public void run()
		{
			try
			{
				List<Integer> previousIndexesToLoad = List.of();
				
				mainLoop:
				while (!Thread.interrupted())
				{
					int current = currentImageIdx;
					List<Integer> indexesToLoad = List.of(current, validIndex(current+1), validIndex(current-1), validIndex(current+2));
					
					previousIndexesToLoad.forEach(i -> {
						if (!indexesToLoad.contains(i))
							images.get(i).cancelLoadingFXImage();
					});
					
					for (int index : indexesToLoad)
					{
						if (Thread.interrupted())
							return;
						
						javafx.scene.image.Image fxImage = images.get(index).getFXImage(true);
						
						if (current != currentImageIdx)
						{
							previousIndexesToLoad = indexesToLoad;
							continue mainLoop;
						}
						
						while (fxImage.getProgress() < 1 && !Thread.interrupted())
						{
							Thread.sleep(100);
							
							if (current != currentImageIdx)
							{
								previousIndexesToLoad = indexesToLoad;
								continue mainLoop;
							}
						}
						
						if (fxImage.getProgress() >= 1 && index == currentImageIdx)
							imageView.setImage(fxImage);
					}
					
					previousIndexesToLoad = indexesToLoad;
				}
			}
			catch (InterruptedException e)
			{
			}
		}
	}
}
