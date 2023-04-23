package nigloo.gallerymanager.ui;

import javafx.fxml.FXML;
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
	private MenuItem nextFolderItem;
	@FXML
	private MenuItem previousFolderItem;
	@FXML
	private CheckMenuItem shuffleItem;
	@FXML
	private CheckMenuItem loopItem;
	@FXML
	private ToggleGroup speedGroup;
	
	public SlideShowContextMenu(SlideShowStage slideShow)
	{
		this.slideShow = slideShow;
		
		UIController.loadFXML(this, "slideshow_context_menu.fxml");
		
		updateItems();
	}
	
	void updateItems()
	{
		playItem.setDisable(slideShow.isAutoPlay());
		pauseItem.setDisable(!slideShow.isAutoPlay());

		nextFolderItem.setDisable(slideShow.isShuffled());
		previousFolderItem.setDisable(slideShow.isShuffled());

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
	protected void nextFolder()
	{
		slideShow.nextFolder();
	}

	@FXML
	protected void previousFolder()
	{
		slideShow.previousFolder();
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
