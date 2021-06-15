package nigloo.gallerymanager.ui;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.ContextMenu;
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
	
	private TreeItem<FileSystemElement> selectedItem;
	private Collection<TreeItem<FileSystemElement>> selectedItems;
	
	public FileSystemTreeContextMenu() throws IOException
	{
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
	
	public void setSelectedItems(Collection<TreeItem<FileSystemElement>> selectedItems)
	{
		// Appure (remove element with parent in selection)
		this.selectedItems = new ArrayList<>(selectedItems);
		
		Iterator<TreeItem<FileSystemElement>> it = this.selectedItems.iterator();
		while (it.hasNext())
		{
			Path path = it.next().getValue().getPath();
			
			boolean parentInSelection = selectedItems.stream()
			                                         .map(TreeItem::getValue)
			                                         .map(FileSystemElement::getPath)
			                                         .filter(p -> path.startsWith(p) && !path.equals(p))
			                                         .findAny()
			                                         .isPresent();
			if (parentInSelection)
				it.remove();
		}
	}
	
	@FXML
	protected void refresh()
	{
		for (TreeItem<FileSystemElement> item : selectedItems)
			uiController.refreshFileSystemItem(item);
	}
	
	@FXML
	protected void synchronize()
	{
		// uiController.syncFileSystemItem(selectedItem);
	}
	
	@FXML
	protected void openInFileExplorer() throws IOException
	{
		Desktop.getDesktop().open(selectedItem.getValue().getPath().toFile());
	}
}
