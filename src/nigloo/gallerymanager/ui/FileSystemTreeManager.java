package nigloo.gallerymanager.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Image;
import nigloo.gallerymanager.ui.FileSystemElement.Status;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;

public class FileSystemTreeManager
{
	@Inject
	private UIController uiController;
	
	@Inject
	private Gallery gallery;
	
	private final ExecutorService asyncPool;
	
	private final TreeView<FileSystemElement> treeView;
	
	public FileSystemTreeManager(TreeView<FileSystemElement> treeView)
	{
		Injector.init(this);
		
		this.treeView = treeView;
		this.asyncPool = Executors.newCachedThreadPool();
	}
	
	public void refresh(TreeItem<FileSystemElement> item, boolean deep)
	{
		getAsyncAction(item.getValue().getPath(), deep);
	}
	
	private CompletableFuture<Void> getAsyncAction(Path path, boolean deep)
	{
		return CompletableFuture.runAsync(() ->
		{
			assert Files.isDirectory(path);
			
			Platform.runLater(() ->
			{
				TreeItem<FileSystemElement> item = findTreeItem(path);
				if (item != null)
					item.getValue().setStatus(Status.LOADING);
			});
			
			List<Path> subPaths = new ArrayList<>();
			List<FileSystemElement> subElements = new ArrayList<>();
			List<Path> subDirectories = new ArrayList<>();
			
			// Add files that are on disk
			try
			{
				Files.list(path).forEach(subPath ->
				{
					FileSystemElement subElement = toFileSystemElement(subPath);
					
					if (subElement != null)
					{
						subPaths.add(subPath);
						subElements.add(subElement);
						if (subElement.isDirectory())
							subDirectories.add(subPath);
					}
				});
			}
			catch (Exception e)
			{
				// TODO handle error
				e.printStackTrace();
				return;
			}
			
			// Add deleted files
			int nameCountSub = path.getNameCount() + 1;
			List<Image> deletedImages = gallery.findImagesIn(path).stream().filter(image ->
			{
				Path p = gallery.toAbsolutePath(image.getPath());
				return !subPaths.contains(p.getRoot().resolve(p.subpath(0, nameCountSub)));
			}).toList();
			

			Platform.runLater(() ->
			{
				TreeItem<FileSystemElement> item = findTreeItem(path);
				if (item == null)
					return;
				
				List<TreeItem<FileSystemElement>> itemProcessed = new ArrayList<>();
				boolean changed = false;
				
				for (FileSystemElement subElement : subElements)
				{
					TreeItem<FileSystemElement> subItem = findTreeItem(item, subElement.getPath());
					
					if (subItem == null)
					{
						subItem = new TreeItem<>(subElement);
						if (subElement.isDirectory())
						{
							subItem.getChildren().add(new TreeItem<>());
							subItem.expandedProperty().addListener(new NewFolderExpandListener(subItem));
						}
						
						item.getChildren().add(subItem);
						changed = true;
					}
					else
					{
						changed |= !subItem.getValue().equals(subElement);
						subItem.setValue(subElement);
						if (subElement.isImage())
							subItem.getChildren().clear();
					}
					itemProcessed.add(subItem);
				}
				
				// create/update deleted items
				for (Image deletedImage : deletedImages)
				{
					FileSystemElement currentElement = new FileSystemElement(deletedImage, Status.DELETED);
					TreeItem<FileSystemElement> currentItem = findTreeItem(item, deletedImage.getAbsolutePath());
					TreeItem<FileSystemElement> childItemToAdd = null;
					
					while (currentItem != item)
					{
						if (currentItem == null)
						{
							currentItem = new TreeItem<>();
							if (childItemToAdd != null)
								currentItem.getChildren().add(childItemToAdd);
							childItemToAdd = currentItem;
						}
						else
						{
							if (currentElement.isDirectory() && childItemToAdd != null)
								currentItem.getChildren().add(childItemToAdd);
							else if (currentElement.isImage())
								currentItem.getChildren().clear();
							
							childItemToAdd = null;
						}
						
						changed |= !currentElement.equals(currentItem.getValue());
						currentItem.setValue(currentElement);
						
						itemProcessed.add(currentItem);
						
						Path pathParent = currentElement.getPath().getParent();
						currentElement = new FileSystemElement(pathParent);
						currentElement.setStatus(Status.DELETED);
						currentItem = findTreeItem(item, pathParent);
					}
					
					if (childItemToAdd != null)
						currentItem.getChildren().add(childItemToAdd);
				}
				
				removeNotProcessed(item, itemProcessed);
				
				updateFolderAndParentStatus(item, deep);
				
				if (changed)
					uiController.requestRefreshThumbnails();
			});
			
			if (deep && !subDirectories.isEmpty())
			{
				CompletableFuture.allOf(subDirectories.stream()
				                                      .map(subDirectory -> getAsyncAction(subDirectory, deep))
				                                      .toArray(CompletableFuture[]::new))
				                 .thenRun(() -> Platform.runLater(() -> updateFolderAndParentStatus(findTreeItem(path),
				                                                                                    deep)));
			}

		}, asyncPool);
	}
	
	private FileSystemElement toFileSystemElement(Path path)
	{
		if (Files.isDirectory(path))
		{
			return new FileSystemElement(path);
		}
		else if (Image.isImage(path))
		{
			Image image = gallery.findImage(path);
			
			return (image != null) ? new FileSystemElement(image, Status.SYNC)
			        : new FileSystemElement(new Image(gallery.toRelativePath(path)), Status.UNSYNC);
		}
		else
		{
			return null;
		}
	}
	
	private TreeItem<FileSystemElement> findTreeItem(Path path)
	{
		return findTreeItem(treeView.getRoot(), path);
	}
	
	private TreeItem<FileSystemElement> findTreeItem(TreeItem<FileSystemElement> fromItem, Path path)
	{
		if (fromItem.getValue() == null)
			return null;
		
		if (path.equals(fromItem.getValue().getPath()))
			return fromItem;
			
		for (TreeItem<FileSystemElement> subItem : fromItem.getChildren())
		{
			if (subItem.getValue() != null && path.startsWith(subItem.getValue().getPath()))
				return findTreeItem(subItem, path);
		}
		
		return null;
	}
	
	private static void removeNotProcessed(TreeItem<FileSystemElement> item, Collection<TreeItem<FileSystemElement>> processedItems)
	{
		Iterator<TreeItem<FileSystemElement>> it = item.getChildren().iterator();
		while (it.hasNext())
		{
			TreeItem<FileSystemElement> subItem = it.next();
			if (!processedItems.contains(subItem))
				it.remove();
			else if (subItem.getValue().getStatus() == Status.DELETED)
				removeNotProcessed(subItem, processedItems);
		}
	}
	
	private void updateFolderAndParentStatus(TreeItem<FileSystemElement> item, boolean updateOnlyIfFullyLoaded)
	{
		if (item == null)
			return;
		
		EnumSet<Status> statusFound = EnumSet.noneOf(Status.class);
		
		for (TreeItem<FileSystemElement> subItem : item.getChildren())
			statusFound.add(subItem.getValue().getStatus());
		
		boolean allFullyLoaded = statusFound.stream().allMatch(Status::isFullyLoaded);
		if (updateOnlyIfFullyLoaded && !allFullyLoaded)
			return;
		
		statusFound.remove(Status.EMPTY); // Ignore empty folders
		Status newSatus;
		if (statusFound.isEmpty())
			newSatus = Status.EMPTY;
		else if (statusFound.size() == 1 && statusFound.contains(Status.DELETED))
			newSatus = Status.DELETED;
		else if (statusFound.size() == 1 && statusFound.contains(Status.SYNC))
			newSatus = Status.SYNC;
		else if (statusFound.contains(Status.UNSYNC) && allFullyLoaded)
			newSatus = Status.UNSYNC;
		else if (statusFound.size() == 2 && statusFound.contains(Status.SYNC) && statusFound.contains(Status.DELETED))
			newSatus = Status.UNSYNC;
		else
			newSatus = Status.NOT_FULLY_LOADED;
		
		if (newSatus == item.getValue().getStatus())
			return;
		
		item.getValue().setStatus(newSatus);
		
		if (item.getParent() != null)
			updateFolderAndParentStatus(item.getParent(), updateOnlyIfFullyLoaded);
	}
	
	// TODO syncFileSystemItem
	public void syncFileSystemItem(TreeItem<FileSystemElement> rootItem)
	{
		System.out.println("Save : " + rootItem.getValue().getPath());
		try
		{
			if (false)
				throw new IOException();
			
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
	}
	
	private class NewFolderExpandListener implements ChangeListener<Boolean>
	{
		private final TreeItem<FileSystemElement> item;
		
		public NewFolderExpandListener(TreeItem<FileSystemElement> item)
		{
			this.item = item;
		}
		
		@Override
		public void changed(ObservableValue<? extends Boolean> obs, Boolean expandedBefore, Boolean expanded)
		{
			if (expanded && item.getValue().getStatus() == Status.NOT_LOADED)
			{
				item.expandedProperty().removeListener(this);
				
				if (item.getChildren().size() == 1 && item.getChildren().get(0).getValue() == null)
				{
					item.getChildren().clear();
					refresh(item, true);
				}
			}
		}
	}
}
