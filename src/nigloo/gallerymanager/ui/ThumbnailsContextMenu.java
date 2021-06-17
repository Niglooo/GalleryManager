package nigloo.gallerymanager.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Image;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;

public class ThumbnailsContextMenu extends ContextMenu
{
	@Inject
	private UIController uiController;
	@Inject
	private Gallery gallery;
	
	@FXML
	private MenuItem startSlideShowAllImagesItem;
	@FXML
	private MenuItem startSlideShowSelectionItem;
	@FXML
	private MenuItem deleteItem;

	private List<Image> allImages;
	private List<Image> selectedImages;
	
	public ThumbnailsContextMenu() throws IOException
	{
		FXMLLoader fxmlLoader = new FXMLLoader(StandardCharsets.UTF_8);
		fxmlLoader.setController(this);
		fxmlLoader.setRoot(this);
		fxmlLoader.load(getClass().getModule()
		                          .getResourceAsStream("resources/fxml/thumbnails_context_menu.fxml"));
		
		Injector.init(this);
		
		startSlideShowAllImagesItem.setDisable(true);
	}
	
	public void update(List<Image> allImages, List<Image> selectedImages)
	{
		this.allImages = allImages;
		this.selectedImages = selectedImages;
		
		startSlideShowAllImagesItem.setDisable(allImages == null || allImages.isEmpty());
		startSlideShowSelectionItem.setDisable(selectedImages == null || selectedImages.isEmpty());
		deleteItem.setDisable(selectedImages == null || selectedImages.isEmpty());
	}
	
	@FXML
	protected void startSlideShow() throws IOException
	{
		int startingIndex = 0;
		if (selectedImages.size() == 1)
			startingIndex = allImages.indexOf(selectedImages.get(0));
		
		new SlideShowStage(allImages, startingIndex).show();
	}
	
	@FXML
	protected void startSlideShowSelection() throws IOException
	{
		new SlideShowStage(selectedImages, 0).show();
	}
	
	@FXML
	protected void delete() throws IOException
	{
		uiController.delete(selectedImages.stream().map(Image::getAbsolutePath).toList(), true);
	}
}
