package nigloo.gallerymanager.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleGroup;

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
	@FXML
	private ToggleGroup speedGroup;
	
	public SlideShowContextMenu(SlideShowStage slideShow) throws IOException
	{
		this.slideShow = slideShow;
		
		FXMLLoader fxmlLoader = new FXMLLoader(StandardCharsets.UTF_8);
		fxmlLoader.setController(this);
		fxmlLoader.setRoot(this);
		fxmlLoader.load(getClass().getModule().getResourceAsStream("resources/fxml/slideshow_context_menu.fxml"));
		
		updateItems();
	}
	
	void updateItems()
	{
		playItem.setDisable(slideShow.isAutoPlay());
		pauseItem.setDisable(!slideShow.isAutoPlay());
		
		shuffleItem.setSelected(slideShow.isShuffled());
		loopItem.setSelected(slideShow.isLooped());
		
		speedGroup.selectToggle(speedGroup.getToggles()
		                                  .stream()
		                                  .filter(item -> ((Double) item.getUserData()) == slideShow.getAutoplayDelay())
		                                  .findFirst()
		                                  .orElse(speedGroup.getToggles().get(0)));
	}
	
	@FXML
	protected void play()
	{
		playItem.setDisable(true);
		pauseItem.setDisable(false);
		
		slideShow.setAutoPlay(true);
	}
	
	@FXML
	protected void pause()
	{
		playItem.setDisable(false);
		pauseItem.setDisable(true);
		
		slideShow.setAutoPlay(false);
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
	
	@FXML
	protected void updateSpeed()
	{
		slideShow.setAutoplayDelay((Double) speedGroup.getSelectedToggle().getUserData());
	}
	
	@FXML
	protected void exit()
	{
		slideShow.close();
	}
}
