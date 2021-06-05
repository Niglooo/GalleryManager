package nigloo.gallerymanager.ui;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.TreeItem;
import nigloo.gallerymanager.model.Gallery;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;

public class FileSystemTreeContextMenu extends ContextMenu
{
	
	private final UIController uiController;
	
	@Inject
	private Gallery gallery;
	
	private TreeItem<FileSystemElement> selectedItem;
	
	public FileSystemTreeContextMenu(UIController uiController) throws IOException
	{
		this.uiController = uiController;
		FXMLLoader fxmlLoader = new FXMLLoader(StandardCharsets.UTF_8);
		fxmlLoader.setController(this);
		fxmlLoader.setRoot(this);
		fxmlLoader.load(getClass().getModule()
		                          .getResourceAsStream("resources/fxml/file_system_tree_context_menu.fxml"));
		
		Injector.init(this);
	}
	
	public void setSelectedItem(TreeItem<FileSystemElement> selectedItem)
	{
		this.selectedItem = selectedItem;
	}
	
	@FXML
	protected void refresh()
	{
		uiController.refreshFileSystemItem(selectedItem);
	}
	
	@FXML
	protected void save()
	{
		uiController.saveFileSystemItem(selectedItem);
	}
	
	@FXML
	protected void openInFileExplorer() throws IOException
	{
		Desktop.getDesktop().open(gallery.toAbsolutePath(selectedItem.getValue().getPath()).toFile());
	}
}
