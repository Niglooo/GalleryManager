package nigloo.gallerymanager.ui;

import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.util.Callback;
import nigloo.gallerymanager.model.Gallery;

class FileSystemTreeCellFactory implements Callback<TreeView<FileSystemElement>, TreeCell<FileSystemElement>>
{
	private final Gallery gallery;
	
	private final FileSystemTreeContextMenu contextMenu;
	
	public FileSystemTreeCellFactory(UIController uiController, Gallery gallery) {
		this.gallery = gallery;
		this.contextMenu = new FileSystemTreeContextMenu(uiController);
	}

	@Override
	public TreeCell<FileSystemElement> call(TreeView<FileSystemElement> fileSystemView)
	{
		TreeCell<FileSystemElement> cell = new TreeCell<FileSystemElement> ()
		{
			@Override
			protected void updateItem(FileSystemElement item, boolean empty)
			{
				super.updateItem(item, empty);
				
				if (empty)
				{
					setText("");
					setGraphic(null);
				}
				else
				{
					setText(item.getPath().getFileName().toString());
					ImageView iv = new ImageView(item.getIcon());
					iv.setFitHeight(16);
					iv.setFitWidth(16);
					iv.setPreserveRatio(true);
					setGraphic(iv);
				}
			}
		};
		
		cell.setContextMenu(contextMenu);
		cell.setOnMouseClicked(event ->
		{
			if (cell.isEmpty())
				return;
			
			TreeItem<FileSystemElement> item = cell.getTreeItem();
			
			if (event.getButton() == MouseButton.PRIMARY &&
				event.getClickCount() == 2)
			{
				//TODO double click
			}
			else if (event.getButton() == MouseButton.SECONDARY) {
				contextMenu.setSelectedItem(item);
			}
		});
		
		return cell;
	}
}
