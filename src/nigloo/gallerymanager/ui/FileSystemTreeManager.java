package nigloo.gallerymanager.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Image;
import nigloo.gallerymanager.ui.FileSystemElement.Status;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;
import nigloo.tool.javafx.component.dialog.AlertWithIcon;
import nigloo.tool.javafx.component.dialog.ExceptionDialog;

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
	
	public void refresh(Collection<Path> paths, boolean deep)
	{
		assert paths != null;
		assert paths.stream().allMatch(Path::isAbsolute);
		
		for (Path path : (deep ? commonParents(paths) : paths))
			getAsyncAction(path, deep);
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
				if (Files.exists(path))
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
				new ExceptionDialog(e, "Error when listing " + path).show();
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
				sort(item);
				
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
		if (!path.startsWith(treeView.getRoot().getValue().getPath()))
			return null;
		
		if (Files.isDirectory(path))
			return new FileSystemElement(path);
		
		if (Image.isImage(path))
		{
			Image image = gallery.findImage(path);
			
			return (image != null) ? new FileSystemElement(image, Status.SYNC)
			        : new FileSystemElement(new Image(gallery.toRelativePath(path)), Status.UNSYNC);
		}
		
		return null;
	}
	
	private TreeItem<FileSystemElement> findTreeItem(Path path)
	{
		return findTreeItem(treeView.getRoot(), path);
	}
	
	private static TreeItem<FileSystemElement> findTreeItem(TreeItem<FileSystemElement> fromItem, Path path)
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
	
	private static void removeNotProcessed(TreeItem<FileSystemElement> item,
	                                       Collection<TreeItem<FileSystemElement>> processedItems)
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
	
	private void sort(TreeItem<FileSystemElement> item)
	{
		Comparator<FileSystemElement> comparator = gallery.getSortOrder(item.getValue().getPath());
		
		item.getChildren().sort(Comparator.comparing(TreeItem::getValue, comparator));
		for (TreeItem<FileSystemElement> subItem : item.getChildren())
			if (subItem.getValue().getStatus() == Status.DELETED)
				sort(subItem);
	}
	
	private static void updateFolderAndParentStatus(TreeItem<FileSystemElement> item, boolean updateOnlyIfFullyLoaded)
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
					refresh(List.of(item.getValue().getPath()), true);
				}
			}
		}
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
	
	public void delete(Collection<Path> paths, boolean deleteOnDisk)
	{
		assert paths != null;
		assert paths.stream().allMatch(Path::isAbsolute);
		
		final Collection<Path> pathsToDelete = commonParents(paths);
		
		Platform.runLater(() ->
		{
			List<TreeItem<FileSystemElement>> itemsToDelete = pathsToDelete.stream()
			                                                               .map(this::findTreeItem)
			                                                               .filter(item -> item != null)
			                                                               .filter(item -> item.getParent() != null)
			                                                               .toList();
			
			List<FileSystemElement> elements = itemsToDelete.stream()
			                                                .flatMap(FileSystemTreeManager::getElements)
			                                                .toList();
			
			long nbImages = elements.stream().filter(FileSystemElement::isImage).count();
			
			AlertWithIcon warningPopup = new AlertWithIcon(AlertType.WARNING);
			warningPopup.setTitle("Delete images");
			warningPopup.setHeaderText("Delete " + nbImages + " image(s)?");
			warningPopup.setContentText("This action cannot be undone!");
			warningPopup.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
			warningPopup.setDefaultButton(ButtonType.NO);
			
			Optional<ButtonType> button = warningPopup.showAndWait();
			if (button.isEmpty() || button.get() != ButtonType.YES)
				return;
			
			for (TreeItem<FileSystemElement> item : itemsToDelete)
			{
				TreeItem<FileSystemElement> parent = item.getParent();
				parent.getChildren().remove(item);
				updateFolderAndParentStatus(parent, false);
			}
			
			uiController.requestRefreshThumbnails();
			
			CompletableFuture.runAsync(() ->
			{
				gallery.deleteImages(elements.stream()
				                             .filter(FileSystemElement::isImage)
				                             .map(FileSystemElement::getImage)
				                             .toList());
				elements.forEach(element ->
				{
					gallery.setSortOrder(element.getPath(), null);
					gallery.setSubDirectoriesSortOrder(element.getPath(), null);
				});
				
				if (deleteOnDisk)
				{
					IOException error = null;
					Path errorPath = null;
					for (FileSystemElement element : elements)
					{
						try
						{
							if (element.isImage() || Files.list(element.getPath()).findAny().isEmpty())
								Files.delete(element.getPath());
						}
						catch (IOException e)
						{
							error = e;
							errorPath = element.getPath();
						}
					}
					
					if (error != null)
						new ExceptionDialog(error, "Error when deleting " + errorPath).show();
				}
			}, asyncPool);
		});
	}
	
	private static Stream<FileSystemElement> getElements(TreeItem<FileSystemElement> item)
	{
		if (item.getValue() == null)
			return Stream.of();
		
		return Stream.concat(item.getChildren().stream().flatMap(FileSystemTreeManager::getElements),
		                     Stream.of(item.getValue()));
	}
	
	private static Collection<Path> commonParents(Collection<Path> paths)
	{
		return paths.stream().filter(p -> paths.stream().noneMatch(p2 -> p != p2 && p.startsWith(p2))).toList();
	}
}
