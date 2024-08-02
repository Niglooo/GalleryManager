package nigloo.gallerymanager.ui;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
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
	private SeparatorMenuItem sortBySeparator;
	@FXML
	private ToggleGroup sortByGroup;
	@FXML
	private ToggleGroup folderPositionGroup;
	@FXML
	private ToggleGroup ascendingGroup;
	
	@FXML
	private CheckMenuItem childrenInheritedOrderItem;
	@FXML
	private SeparatorMenuItem childrenSortBySeparator;
	@FXML
	private ToggleGroup childrenSortByGroup;
	@FXML
	private ToggleGroup childrenFolderPositionGroup;
	@FXML
	private ToggleGroup childrenAscendingGroup;
	
	@FXML
	private MenuItem pasteItem;
	
	private Toggle folderPositionSelected;
	private Toggle childrenFolderPositionSelected;
	
	private final TreeView<FileSystemElement> treeView;
	
	public FileSystemTreeContextMenu(TreeView<FileSystemElement> treeView)
	{
		this.treeView = treeView;
		UIController.loadFXML(this, "file_system_tree_context_menu.fxml");
		Injector.init(this);

		List<SortBy> allSortBy = new ArrayList<>();
		allSortBy.add(SortBy.NAME);
		allSortBy.add(SortBy.DATE);
		allSortBy.add(SortBy.ROCKSET);
		allSortBy.add(SortBy.KOUYOU);
		allSortBy.addAll(gallery.getCustomSortBy());

		List<MenuItem> sortByItems = sortBySeparator.getParentMenu().getItems();
		int sortByPos = sortByItems.indexOf(sortBySeparator) + 1;

		List<MenuItem> childrenSortByItems = childrenSortBySeparator.getParentMenu().getItems();
		int childrenSortByPos = childrenSortByItems.indexOf(childrenSortBySeparator) + 1;

		for (int i = 0 ; i < allSortBy.size() ; i++)
		{
			SortBy sortBy = allSortBy.get(i);
			RadioMenuItem item;

			item = new RadioMenuItem();
			item.setText(sortBy.name().substring(0, 1).toUpperCase(Locale.ROOT) + sortBy.name().substring(1).toLowerCase(Locale.ROOT));
			item.setOnAction(this::updateSortBy);
			item.setToggleGroup(sortByGroup);
			item.setUserData(sortBy);
			sortByItems.add(sortByPos + i, item);

			item = new RadioMenuItem();
			item.setText(sortBy.name().substring(0, 1).toUpperCase(Locale.ROOT) + sortBy.name().substring(1).toLowerCase(Locale.ROOT));
			item.setOnAction(this::updateChildrenSortBy);
			item.setToggleGroup(childrenSortByGroup);
			item.setUserData(sortBy);
			childrenSortByItems.add(childrenSortByPos + i, item);
		}

		setOnShowing(e -> {
			updateSortOrderItems();
			updateChildrenSortOrderItems();
			pasteItem.setDisable(selectedElement() == null
			        || !selectedElement().isDirectory()
			        || !uiController.canPaste(selectedElement().getPath()));
		});
	}
	
	private void updateSortOrderItems()
	{
		if (selectedElement().isImage())
		{
			inheritedOrderItem.getParentMenu().setDisable(true);
			return;
		}
		
		Path path = selectedElement().getPath();
		
		inheritedOrderItem.getParentMenu().setDisable(false);
		
		inheritedOrderItem.setSelected(gallery.isOrderInherited(path));
		
		FileFolderOrder order = gallery.getSortOrder(path);
		sortByGroup.getToggles().forEach(t -> t.setSelected(t.getUserData().equals(order.sortBy())));
		folderPositionGroup.getToggles()
		                   .forEach(t -> t.setSelected((Integer) t.getUserData() == order.directoryWeight()));
		ascendingGroup.getToggles().forEach(t -> t.setSelected((Boolean) t.getUserData() == order.ascending()));
		
		folderPositionSelected = folderPositionGroup.getSelectedToggle();
	}
	
	private void updateChildrenSortOrderItems()
	{
		if (selectedElement().isImage())
		{
			childrenInheritedOrderItem.getParentMenu().setDisable(true);
			return;
		}
		
		Path path = selectedElement().getPath();
		
		childrenInheritedOrderItem.getParentMenu().setDisable(false);
		
		childrenInheritedOrderItem.setSelected(gallery.isSubDirectoriesOrderInherited(path));
		
		FileFolderOrder childrenOrder = gallery.getSubDirectoriesSortOrder(path);
		childrenSortByGroup.getToggles().forEach(t -> t.setSelected(t.getUserData().equals(childrenOrder.sortBy())));
		childrenFolderPositionGroup.getToggles()
		                           .forEach(t -> t.setSelected((Integer) t.getUserData() == childrenOrder.directoryWeight()));
		childrenAscendingGroup.getToggles()
		                      .forEach(t -> t.setSelected((Boolean) t.getUserData() == childrenOrder.ascending()));
		
		childrenFolderPositionSelected = childrenFolderPositionGroup.getSelectedToggle();
	}
	
	private FileSystemElement selectedElement()
	{
		TreeItem<FileSystemElement> selectedItem = treeView.getSelectionModel().getSelectedItem();
		return selectedItem != null ? selectedItem.getValue() : null;
	}
	
	private List<Path> selectedPaths()
	{
		return treeView.getSelectionModel().getSelectedItems().stream().map(TreeItem::getValue).map(FileSystemElement::getPath).toList();
	}
	
	@FXML
	protected void openInFileExplorer() throws IOException
	{
		Desktop.getDesktop().open(selectedElement().getPath().toFile());
	}
	
	@FXML
	protected void updateSortBy(ActionEvent event)
	{
		TreeItem<FileSystemElement> item = treeView.getSelectionModel().getSelectedItem();
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

		SortBy.times.get(true).clear();
		SortBy.times.get(false).clear();
		sort(item, order);
		SortBy.times.entrySet().forEach(e -> {
			System.out.println("Avg "+(e.getKey()?"":"NOT ")+"compiled ("+e.getValue().size()+"): "+e.getValue().stream().mapToLong(Long::longValue).average().orElse(-1)+"ns");
		});
		uiController.requestRefreshThumbnails();
	}
	
	@FXML
	protected void updateChildrenSortBy(ActionEvent event)
	{
		TreeItem<FileSystemElement> item = treeView.getSelectionModel().getSelectedItem();
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
		uiController.synchronizeFileSystem(selectedPaths(), true);
	}
	
	@FXML
	protected void cut()
	{
		uiController.cut(selectedPaths());
	}
	
	@FXML
	protected void paste()
	{
		uiController.paste(selectedElement().getPath());
	}
	
	@FXML
	protected void delete()
	{
		uiController.delete(selectedPaths(), true);
	}
	
	@FXML
	protected void rename()
	{
		treeView.edit(treeView.getSelectionModel().getSelectedItem());
	}
	
	@FXML
	protected void newFolder()
	{
		uiController.newDirectoryIn(selectedElement().getPath(), true);
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
