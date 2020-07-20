package nigloo.gallerymanager.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.TreeItem;

public class FileSystemTreeContextMenu extends ContextMenu {

	private final UIController uiController;
	private TreeItem<FileSystemElement> selectedItem;
	
	public FileSystemTreeContextMenu(UIController uiController) {
		this.uiController = uiController;
		try {
			FXMLLoader fxmlLoader = new FXMLLoader(StandardCharsets.UTF_8);
			fxmlLoader.setController(this);
			fxmlLoader.setRoot(this);
			fxmlLoader.load(getClass().getModule().getResourceAsStream("resources/fxml/file_system_tree_context_menu.fxml"));
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public void setSelectedItem(TreeItem<FileSystemElement> selectedItem) {
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
}
