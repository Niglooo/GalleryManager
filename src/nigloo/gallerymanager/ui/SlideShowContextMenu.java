package nigloo.gallerymanager.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;

public class SlideShowContextMenu extends ContextMenu
{
	private final SlideShowStage slideShow;
	
	@FXML
	private MenuItem playItem;
	@FXML
	private MenuItem pauseItem;
	@FXML
	private CheckMenuItem shuffleItem;
	@FXML
	private CheckMenuItem loopItem;
	
	public SlideShowContextMenu(SlideShowStage slideShow) throws IOException
	{
		this.slideShow = slideShow;
		
		FXMLLoader fxmlLoader = new FXMLLoader(StandardCharsets.UTF_8);
		fxmlLoader.setController(this);
		fxmlLoader.setRoot(this);
		fxmlLoader.load(getClass().getModule()
		                          .getResourceAsStream("resources/fxml/slideshow_context_menu.fxml"));
		
		shuffleItem.setSelected(slideShow.isShuffled());
		loopItem.setSelected(slideShow.isLooped());
	}
	
	@FXML
	protected void play()
	{
		
	}
	
	@FXML
	protected void pause()
	{
		
	}
	
	@FXML
	protected void next()
	{
		slideShow.next();
	}
	
	@FXML
	protected void previous()
	{
		slideShow.previous();
	}
	
	@FXML
	protected void shuffle()
	{
		slideShow.setShuffled(shuffleItem.isSelected());
	}
	
	@FXML
	protected void loop()
	{
		slideShow.setLooped(loopItem.isSelected());
	}
}
