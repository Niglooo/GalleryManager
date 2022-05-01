package nigloo.gallerymanager.ui;

import java.awt.Desktop;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Image;
import nigloo.gallerymanager.ui.dialog.EditImageTagsDialog;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;
import nigloo.tool.javafx.component.dialog.ExceptionDialog;

public class ThumbnailsContextMenu extends ContextMenu
{
	@Inject
	private UIController uiController;
	@Inject
	private Gallery gallery;
	
	@FXML
	private MenuItem openItem;
	@FXML
	private MenuItem startSlideShowAllImagesItem;
	@FXML
	private MenuItem startSlideShowSelectionItem;
	@FXML
	private MenuItem selectAllItem;
	@FXML
	private MenuItem editTagsItem;
	@FXML
	private MenuItem deleteItem;
	
	private final VScrollablePane thumbnailsView;
	private List<Image> allImages;
	private List<Image> selectedImages;
	
	public ThumbnailsContextMenu(VScrollablePane thumbnailsView)
	{
		this.thumbnailsView = thumbnailsView;
		
		UIController.loadFXML(this, "thumbnails_context_menu.fxml");
		Injector.init(this);
		
		addEventHandler(Menu.ON_SHOWING, event ->
		{
			allImages = thumbnailsView.getTiles()
			                          .stream()
			                          .map(ThumbnailView.class::cast)
			                          .map(ThumbnailView::getGalleryImage)
			                          .toList();
			selectedImages = thumbnailsView.getSelectionModel()
			                               .getSelectedItems()
			                               .stream()
			                               .map(ThumbnailView.class::cast)
			                               .map(ThumbnailView::getGalleryImage)
			                               .sorted(Comparator.comparingInt(image -> allImages.indexOf(image)))
			                               .toList();
			
			openItem.setDisable(thumbnailsView.getSelectionModel().getSelectedItem() == null);
			startSlideShowAllImagesItem.setDisable(allImages.isEmpty());
			startSlideShowSelectionItem.setDisable(selectedImages.isEmpty());
			selectAllItem.setDisable(allImages.isEmpty());
			editTagsItem.setDisable(selectedImages.isEmpty());
			deleteItem.setDisable(selectedImages.isEmpty());
		});
	}
	
	@FXML
	protected void open()
	{
		Image image = ((ThumbnailView) thumbnailsView.getSelectionModel().getSelectedItem()).getGalleryImage();
		try
		{
			Desktop.getDesktop().open(image.getAbsolutePath().toFile());
		}
		catch (IOException e)
		{
			new ExceptionDialog(e, "Cannot open "+image.getPath());
		}
	}
	
	@FXML
	protected void startSlideShow()
	{
		int startingIndex = 0;
		if (selectedImages.size() == 1)
			startingIndex = allImages.indexOf(selectedImages.get(0));
		
		uiController.showSlideShowFromThumbnails(allImages, startingIndex);
	}
	
	@FXML
	protected void startSlideShowSelection()
	{
		uiController.showSlideShowFromThumbnails(selectedImages, 0);
	}
	
	@FXML
	protected void selectAll()
	{
		thumbnailsView.getSelectionModel().selectAll();
	}
	
	@FXML
	protected void editTags()
	{
		EditImageTagsDialog editImageTagsDialog = new EditImageTagsDialog(getScene().getWindow());
		editImageTagsDialog.setImages(selectedImages);
		
		// We somehow need to call runLater because EditImageTagsDialog is modal
		Platform.runLater(() -> editImageTagsDialog.show());
	}
	
	@FXML
	protected void delete()
	{
		uiController.delete(selectedImages.stream().map(Image::getAbsolutePath).toList(), true);
	}
}
