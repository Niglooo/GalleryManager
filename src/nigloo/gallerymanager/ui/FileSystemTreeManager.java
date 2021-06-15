package nigloo.gallerymanager.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.TreeItem;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Image;
import nigloo.gallerymanager.ui.FileSystemElement.Status;
import nigloo.tool.Utils;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;

public class FileSystemTreeManager
{
	@Inject
	private UIController uiController;
	
	@Inject
	private Gallery gallery;
	
	private final ForkJoinPool pool;
	
	public FileSystemTreeManager()
	{
		Injector.init(this);
		
		pool = new ForkJoinPool();
	}
	
	public void refresh(TreeItem<FileSystemElement> item, boolean deep)
	{
		pool.invoke(getTask(item, deep));
		
		uiController.refreshThumbnails();
	}
	
	// TODO Make it async
	private RecursiveAction getTask(TreeItem<FileSystemElement> item, boolean deep)
	{
		RecursiveAction task = new RecursiveAction()
		{
			@Override
			protected void compute()
			{
				Path path = item.getValue().getPath();
				assert Files.isDirectory(path);
				
				// Remove fake item (here just so not loaded folder can be opened)
				if (item.getValue().getStatus() == Status.NOT_LOADED)
					item.getChildren().clear();
				
				// Remove deleted elements
				item.getChildren().removeIf(i -> i.getValue().getStatus() == Status.DELETED);
				
				Map<Path, TreeItem<FileSystemElement>> pathToItem = new HashMap<>();
				pathToItem.put(path, item);
				item.getChildren().stream().forEach(i -> pathToItem.put(i.getValue().getPath(), i));
				
				List<TreeItem<FileSystemElement>> subDirectoryItems = new ArrayList<>();
				
				// Add files that are on disk
				try
				{
					Files.list(path).forEach(subPath ->
					{
						boolean isDirectory = Files.isDirectory(subPath);
						boolean isImage = Image.isImage(subPath);
						TreeItem<FileSystemElement> subItem = pathToItem.get(subPath);
						
						FileSystemElement fsElem;
						if (isDirectory)
						{
							fsElem = new FileSystemElement(subPath);
						}
						else if (isImage)
						{
							Image image = gallery.findImage(subPath);
							
							fsElem = (image != null) ? new FileSystemElement(image, Status.SYNC)
							        : new FileSystemElement(new Image(subPath), Status.UNSYNC);
						}
						else
						{
							if (subItem != null)
								item.getChildren().remove(subItem);
							return;
						}
						
						if (subItem == null)
						{
							subItem = newItem(fsElem);
							
							item.getChildren().add(subItem);
						}
						else
							subItem.setValue(fsElem);
						
						pathToItem.put(subPath, subItem);
						
						if (isDirectory)
						{
							subDirectoryItems.add(subItem);
						}
					});
				}
				catch (Exception e)
				{
					item.getValue().setStatus(Status.NOT_LOADED);
				}
				
				// Add deleted files
				int nameCountSub = path.getNameCount() + 1;
				gallery.findImagesIn(path).stream().filter(image ->
				{
					Path p = gallery.toAbsolutePath(image.getPath());
					return !pathToItem.containsKey(p.getRoot().resolve(p.subpath(0, nameCountSub)));
				}).forEach(deletedImage ->
				{
					TreeItem<FileSystemElement> childItem = new TreeItem<FileSystemElement>(new FileSystemElement(deletedImage,
					                                                                                              Status.DELETED));
					Path currentPath = gallery.toAbsolutePath(deletedImage.getPath()).getParent();
					TreeItem<FileSystemElement> currentItem = pathToItem.get(currentPath);
					
					while (currentItem == null)
					{
						currentItem = new TreeItem<FileSystemElement>(new FileSystemElement(currentPath));
						currentItem.getValue().setStatus(Status.DELETED);
						currentItem.getChildren().add(childItem);
						pathToItem.put(currentPath, currentItem);
						
						childItem = currentItem;
						currentPath = currentPath.getParent();
						currentItem = pathToItem.get(currentPath);
					}
					
					currentItem.getChildren().add(childItem);
				});
				
				updateFolderStatus(item);
				sort(item);
				
				if (deep)
				{
					invokeAll(subDirectoryItems.stream().map(subItem -> getTask(subItem, deep)).toList());
					updateFolderStatus(item);
				}
			}
		};
		
		return task;
	}
	
	private void updateFolderStatus(TreeItem<FileSystemElement> item)
	{
		Status newStatus;
		
		EnumSet<Status> statusFound = EnumSet.noneOf(Status.class);
		
		for (TreeItem<FileSystemElement> subItem : item.getChildren())
			statusFound.add(subItem.getValue().getStatus());
		
		statusFound.remove(Status.EMPTY); // Ignore empty folders
		
		if (statusFound.isEmpty())
			newStatus = Status.EMPTY;
		else if (statusFound.size() == 1 && statusFound.contains(Status.DELETED))
			newStatus = Status.DELETED;
		else if (statusFound.size() == 1 && statusFound.contains(Status.SYNC))
			newStatus = Status.SYNC;
		else if (statusFound.contains(Status.UNSYNC))
			newStatus = Status.UNSYNC;
		else
			newStatus = Status.NOT_FULLY_LOADED;
		
		item.getValue().setStatus(newStatus);
	}
	
	private void sort(TreeItem<FileSystemElement> rootItem)
	{
		rootItem.getChildren()
		        .sort(Comparator.comparing(ti -> ti.getValue().getPath().getFileName().toString(),
		                                   Utils.NATURAL_ORDER));
		
		for (TreeItem<FileSystemElement> item : rootItem.getChildren())
			sort(item);
	}
	
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
	
	public TreeItem<FileSystemElement> newItem(final FileSystemElement fsElem)
	{
		assert fsElem != null;
		
		TreeItem<FileSystemElement> item = new TreeItem<FileSystemElement>(fsElem);
		if (fsElem.isDirectory())
		{
			item.getChildren().add(new TreeItem<>());
			item.expandedProperty().addListener(new NewFolderExpandListener(item));
		}
		return item;
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
				refresh(item, true);
			}
		}
	}
}
