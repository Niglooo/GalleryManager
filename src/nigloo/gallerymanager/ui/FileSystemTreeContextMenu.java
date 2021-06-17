package nigloo.gallerymanager.ui;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MultipleSelectionModel;
import javafx.scene.control.TreeItem;
import nigloo.gallerymanager.model.Gallery;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;

public class FileSystemTreeContextMenu extends ContextMenu
{
	@Inject
	private UIController uiController;
	
	@Inject
	private Gallery gallery;
	
	private MultipleSelectionModel<TreeItem<FileSystemElement>> selection;
	
	public FileSystemTreeContextMenu() throws IOException
	{
		FXMLLoader fxmlLoader = new FXMLLoader(StandardCharsets.UTF_8);
		fxmlLoader.setController(this);
		fxmlLoader.setRoot(this);
		fxmlLoader.load(getClass().getModule()
		                          .getResourceAsStream("resources/fxml/file_system_tree_context_menu.fxml"));
		
		Injector.init(this);
	}
	
	public void setSelection(MultipleSelectionModel<TreeItem<FileSystemElement>> selection)
	{
		this.selection = selection;
	}
	
	private List<Path> selectedPaths()
	{
		return selection.getSelectedItems().stream().map(TreeItem::getValue).map(FileSystemElement::getPath).toList();
	}
	
	@FXML
	protected void refresh()
	{
		uiController.refreshFileSystem(selectedPaths(), true);
	}
	
	@FXML
	protected void synchronize()
	{
		// uiController.syncFileSystemItem(selectedItem);
	}
	
	@FXML
	protected void openInFileExplorer() throws IOException
	{
		Desktop.getDesktop().open(selection.getSelectedItem().getValue().getPath().toFile());
	}
	
	@FXML
	protected void delete()
	{
		uiController.delete(selectedPaths(), true);
	}
}
