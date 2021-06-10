package nigloo.gallerymanager.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javafx.event.ActionEvent;
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
	
	private List<CheckMenuItem> speedItems;
	
	public SlideShowContextMenu(SlideShowStage slideShow) throws IOException
	{
		this.slideShow = slideShow;
		
		FXMLLoader fxmlLoader = new FXMLLoader(StandardCharsets.UTF_8);
		fxmlLoader.setController(this);
		fxmlLoader.setRoot(this);
		fxmlLoader.load(getClass().getModule().getResourceAsStream("resources/fxml/slideshow_context_menu.fxml"));
		
		double delay = slideShow.getAutoplayDelay();
		
		playItem.setDisable(delay != 0);
		pauseItem.setDisable(delay == 0);
		
		shuffleItem.setSelected(slideShow.isShuffled());
		loopItem.setSelected(slideShow.isLooped());
		
		// Retrieve speed items
		speedItems = getItems().stream()
		                       .filter(item -> item.getStyleClass().contains("speed-control"))
		                       .map(CheckMenuItem.class::cast)
		                       .toList();
		// Parse delay and select item with daly correcponding to parameter
		speedItems.forEach(item ->
		{
			double itemDelay = Double.parseDouble(item.getUserData().toString());
			item.setUserData(itemDelay);
			item.setSelected(itemDelay == delay);
		});
		// If no item selected, select the first one
		if (!speedItems.stream().anyMatch(item -> (double) item.getUserData() == delay))
			speedItems.get(0).setSelected(true);
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
	protected void updateSpeed(ActionEvent event)
	{
		for (CheckMenuItem item : speedItems)
			item.setSelected(item == event.getSource());
			
		double selectSpeed = (double)((CheckMenuItem) event.getSource()).getUserData();
		
		slideShow.setAutoplayDelay(selectSpeed);
	}
}
