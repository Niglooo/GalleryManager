package nigloo.gallerymanager.ui;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MultipleSelectionModel;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.TreeItem;
import nigloo.gallerymanager.model.FileFolderOrder;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.SortBy;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;

public class FileSystemTreeContextMenu extends ContextMenu
{
	@Inject
	private UIController uiController;
	@Inject
	private Gallery gallery;
	
	@FXML
	private CheckMenuItem inheritedOrderItem;
	@FXML
	private ToggleGroup sortByGroup;
	@FXML
	private ToggleGroup folderPositionGroup;
	@FXML
	private ToggleGroup ascendingGroup;
	
	@FXML
	private CheckMenuItem childrenInheritedOrderItem;
	@FXML
	private ToggleGroup childrenSortByGroup;
	@FXML
	private ToggleGroup childrenFolderPositionGroup;
	@FXML
	private ToggleGroup childrenAscendingGroup;
	
	private Toggle folderPositionSelected;
	private Toggle childrenFolderPositionSelected;
	
	private MultipleSelectionModel<TreeItem<FileSystemElement>> selection;
	
	public FileSystemTreeContextMenu() throws IOException
	{
		FXMLLoader fxmlLoader = new FXMLLoader(StandardCharsets.UTF_8);
		fxmlLoader.setController(this);
		fxmlLoader.setRoot(this);
		fxmlLoader.load(getClass().getModule()
		                          .getResourceAsStream("resources/fxml/file_system_tree_context_menu.fxml"));
		// new Menu().getItems()
		Injector.init(this);
		
		addEventHandler(Menu.ON_SHOWING, e ->
		{
			updateSortOrderItems();
			updateChildrenSortOrderItems();
		});
	}
	
	public void setSelection(MultipleSelectionModel<TreeItem<FileSystemElement>> selection)
	{
		this.selection = selection;
	}
	
	void updateSortOrderItems()
	{
		if (selection.getSelectedItem().getValue().isImage())
		{
			inheritedOrderItem.getParentMenu().setDisable(true);
			return;
		}
		
		Path path = selection.getSelectedItem().getValue().getPath();
		
		inheritedOrderItem.getParentMenu().setDisable(false);
		
		inheritedOrderItem.setSelected(gallery.isOrderInherited(path));
		
		FileFolderOrder order = gallery.getSortOrder(path);
		sortByGroup.getToggles().forEach(t -> t.setSelected(t.getUserData() == order.sortBy()));
		folderPositionGroup.getToggles()
		                   .forEach(t -> t.setSelected((Integer) t.getUserData() == order.directoryWeight()));
		ascendingGroup.getToggles().forEach(t -> t.setSelected((Boolean) t.getUserData() == order.ascending()));
		
		folderPositionSelected = folderPositionGroup.getSelectedToggle();
	}
	
	void updateChildrenSortOrderItems()
	{
		if (selection.getSelectedItem().getValue().isImage())
		{
			childrenInheritedOrderItem.getParentMenu().setDisable(true);
			return;
		}
		
		Path path = selection.getSelectedItem().getValue().getPath();
		
		childrenInheritedOrderItem.getParentMenu().setDisable(false);
		
		childrenInheritedOrderItem.setSelected(gallery.isSubDirectoriesOrderInherited(path));
		
		FileFolderOrder childrenOrder = gallery.getSubDirectoriesSortOrder(path);
		childrenSortByGroup.getToggles().forEach(t -> t.setSelected(t.getUserData() == childrenOrder.sortBy()));
		childrenFolderPositionGroup.getToggles()
		                           .forEach(t -> t.setSelected((Integer) t.getUserData() == childrenOrder.directoryWeight()));
		childrenAscendingGroup.getToggles()
		                      .forEach(t -> t.setSelected((Boolean) t.getUserData() == childrenOrder.ascending()));
		
		childrenFolderPositionSelected = childrenFolderPositionGroup.getSelectedToggle();
	}
	
	private List<Path> selectedPaths()
	{
		return selection.getSelectedItems().stream().map(TreeItem::getValue).map(FileSystemElement::getPath).toList();
	}
	
	@FXML
	protected void openInFileExplorer() throws IOException
	{
		Desktop.getDesktop().open(selection.getSelectedItem().getValue().getPath().toFile());
	}
	
	@FXML
	protected void updateSortBy(ActionEvent event)
	{
		TreeItem<FileSystemElement> item = selection.getSelectedItem();
		Path path = item.getValue().getPath();
		FileFolderOrder order;
		
		if (event.getSource() == inheritedOrderItem)
		{
			if (inheritedOrderItem.isSelected())
			{
				gallery.setSortOrder(path, null);
				order = gallery.getSortOrder(path);
				updateSortOrderItems();
			}
			else
			{
				order = gallery.getSortOrder(path);
				gallery.setSortOrder(path, order);
			}
		}
		else
		{
			order = gallery.getSortOrder(path);
			if (folderPositionGroup.getToggles().contains(event.getSource())
			        && folderPositionGroup.getSelectedToggle() == folderPositionSelected)
				folderPositionGroup.selectToggle(null);
			
			order = new FileFolderOrder((SortBy) sortByGroup.getSelectedToggle().getUserData(),
			                            folderPositionGroup.getSelectedToggle() == null ? 0
			                                    : (Integer) folderPositionGroup.getSelectedToggle().getUserData(),
			                            (Boolean) ascendingGroup.getSelectedToggle().getUserData());
			
			gallery.setSortOrder(path, order);
		}
		
		folderPositionSelected = folderPositionGroup.getSelectedToggle();

		sort(item, order);
		uiController.requestRefreshThumbnails();
	}
	
	@FXML
	protected void updateChildrenSortBy(ActionEvent event)
	{
		TreeItem<FileSystemElement> item = selection.getSelectedItem();
		Path path = item.getValue().getPath();
		FileFolderOrder order;
		
		if (event.getSource() == childrenInheritedOrderItem)
		{
			if (childrenInheritedOrderItem.isSelected())
			{
				gallery.setSubDirectoriesSortOrder(path, null);
				order = gallery.getSubDirectoriesSortOrder(path);
				updateChildrenSortOrderItems();
			}
			else
			{
				order = gallery.getSubDirectoriesSortOrder(path);
				gallery.setSubDirectoriesSortOrder(path, order);
			}
		}
		else
		{
			order = gallery.getSubDirectoriesSortOrder(path);
			if (childrenFolderPositionGroup.getToggles().contains(event.getSource())
			        && childrenFolderPositionGroup.getSelectedToggle() == childrenFolderPositionSelected)
				childrenFolderPositionGroup.selectToggle(null);
			
			order = new FileFolderOrder((SortBy) childrenSortByGroup.getSelectedToggle().getUserData(),
			                            childrenFolderPositionGroup.getSelectedToggle() == null ? 0
			                                    : (Integer) childrenFolderPositionGroup.getSelectedToggle()
			                                                                           .getUserData(),
			                            (Boolean) childrenAscendingGroup.getSelectedToggle().getUserData());
			
			gallery.setSubDirectoriesSortOrder(path, order);
		}
		
		childrenFolderPositionSelected = childrenFolderPositionGroup.getSelectedToggle();
		
		for (TreeItem<FileSystemElement> child : item.getChildren())
			if (child.getValue() != null && gallery.isOrderInherited(child.getValue().getPath()))
				sort(child, order);
		
		uiController.requestRefreshThumbnails();
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
	protected void delete()
	{
		uiController.delete(selectedPaths(), true);
	}
	
	private void sort(TreeItem<FileSystemElement> item, FileFolderOrder order)
	{
		Path path = item.getValue().getPath();
		
		item.getChildren().sort(Comparator.comparing(TreeItem::getValue, order));
		
		if (gallery.isSubDirectoriesOrderInherited(path))
			for (TreeItem<FileSystemElement> child : item.getChildren())
				if (child.getValue() != null && gallery.isOrderInherited(child.getValue().getPath()))
					sort(child, order);
	}
}
