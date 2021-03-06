package nigloo.gallerymanager.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.cell.TextFieldTreeCell;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.util.Callback;
import javafx.util.Duration;
import javafx.util.StringConverter;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Image;
import nigloo.gallerymanager.ui.FileSystemElement.Status;
import nigloo.tool.StrongReference;
import nigloo.tool.Utils;
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
	
	private final TreeView<FileSystemElement> treeView;
	private final ExecutorService asyncPool;
	
	public FileSystemTreeManager(TreeView<FileSystemElement> treeView)
	{
		Injector.init(this);
		
		this.treeView = treeView;
		this.asyncPool = Executors.newCachedThreadPool();
		
		treeView.setCellFactory(new FileSystemTreeCellFactory());
		treeView.setEditable(true);
		treeView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
		treeView.getSelectionModel()
		        .getSelectedItems()
		        .addListener(new ListChangeListener<TreeItem<FileSystemElement>>()
		        {
			        @Override
			        public void onChanged(Change<? extends TreeItem<FileSystemElement>> c)
			        {
				        while (c.next())
					        c.getRemoved()
					         .stream()
					         .flatMap(item -> getImages(item))
					         .forEach(Image::cancelLoadingThumbnail);
				        
				        uiController.requestRefreshThumbnails();
			        }
		        });
		
		StrongReference<List<File>> oldContentRef = new StrongReference<>();
		Timeline clipboardObserver = new Timeline(new KeyFrame(Duration.millis(200), e ->
		{
			List<File> oldContent = oldContentRef.get();
			List<File> newContent = Clipboard.getSystemClipboard().getFiles();
			
			if (Objects.equals(oldContent, newContent))
				return;
			
			// Force old and new element to update
			if (oldContent != null)
			{
				for (File file : oldContent)
				{
					TreeItem<FileSystemElement> item = findTreeItem(file.toPath());
					if (item != null)
					{
						FileSystemElement element = item.getValue();
						item.setValue(null);
						item.setValue(element);
					}
				}
			}
			
			if (newContent != null)
			{
				for (File file : newContent)
				{
					TreeItem<FileSystemElement> item = findTreeItem(file.toPath());
					if (item != null)
					{
						FileSystemElement element = item.getValue();
						item.setValue(null);
						item.setValue(element);
					}
				}
			}
			
			oldContentRef.set(newContent);
		}));
		clipboardObserver.setCycleCount(Timeline.INDEFINITE);
		clipboardObserver.play();
	}
	
	public void refresh(Collection<Path> paths, boolean deep)
	{
		assert paths != null;
		assert paths.stream().allMatch(Path::isAbsolute);
		
		for (Path path : (deep ? commonParents(paths) : paths))
			getAsyncAction(path, deep).exceptionally(e ->
			{
				Platform.runLater(() -> new ExceptionDialog(e, "Error will refreshing " + path).show());
				return null;
			});
	}
	
	// TODO simplify getAsyncAction(Path path, boolean deep)
	private CompletableFuture<Void> getAsyncAction(Path path, boolean deep)
	{
		// ---- case path doesn't exist ----
		if (!Files.exists(path))
		{
			Platform.runLater(() ->
			{
				TreeItem<FileSystemElement> item = findTreeItem(path);
				if (item == null)
					return;
				
				Image image = gallery.findImage(path);
				if (image != null)
				{
					item.setValue(new FileSystemElement(image, Status.DELETED));
					item.getChildren().clear();
					updateFolderAndParentStatus(item.getParent(), deep);
				}
				else
				{
					item.setValue(new FileSystemElement(path));
					
					// create/update deleted items
					Collection<Image> deletedImages = gallery.findImagesIn(path);
					List<TreeItem<FileSystemElement>> itemProcessed = new ArrayList<>();
					createUpdateDeleteItems(deletedImages, item, itemProcessed);
					removeNotProcessed(item, itemProcessed);
					sort(item);
					updateFolderAndParentStatus(item, deep);
				}
				
				uiController.requestRefreshThumbnails();
			});
			
			return CompletableFuture.completedFuture(null);
		}
		
		// ---- case path is an image ----
		if (Image.isImage(path) && Files.isRegularFile(path))
		{
			Platform.runLater(() ->
			{
				TreeItem<FileSystemElement> item = findTreeItem(path);
				if (item == null)
					return;
				
				Image image = gallery.getImage(path);
				FileSystemElement element = image.isSaved() ? new FileSystemElement(image, Status.SYNC)
				        : new FileSystemElement(image, Status.UNSYNC);
				
				item.setValue(element);
				item.getChildren().clear();
				
				updateFolderAndParentStatus(item.getParent(), deep);
			});
			
			return CompletableFuture.completedFuture(null);
		}
		
		// ---- case path something we don't are about ----
		if (!Files.isDirectory(path))
		{
			Platform.runLater(() ->
			{
				TreeItem<FileSystemElement> item = findTreeItem(path);
				if (item == null)
					return;
				
				TreeItem<FileSystemElement> parent = item.getParent();
				parent.getChildren().remove(item);
				updateFolderAndParentStatus(parent, deep);
			});
			
			return CompletableFuture.completedFuture(null);
		}
		
		// ---- case path is a directory ----
		return CompletableFuture.runAsync(() ->
		{
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
			try (Stream<Path> list = Files.list(path))
			{
				list.forEach(subPath ->
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
				e.printStackTrace();
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
				changed |= createUpdateDeleteItems(deletedImages, item, itemProcessed);
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
			Image image = gallery.getImage(path);
			
			return image.isSaved() ? new FileSystemElement(image, Status.SYNC)
			        : new FileSystemElement(image, Status.UNSYNC);
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
	
	private static boolean createUpdateDeleteItems(Collection<Image> deletedImages,
	                                               TreeItem<FileSystemElement> item,
	                                               Collection<TreeItem<FileSystemElement>> itemProcessed)
	{
		boolean changed = false;
		
		for (Image deletedImage : deletedImages)
		{
			FileSystemElement currentElement = new FileSystemElement(deletedImage, Status.DELETED);
			TreeItem<FileSystemElement> currentItem = findTreeItem(item, deletedImage.getAbsolutePath());
			if (currentItem == item)
			{
				item.setValue(currentElement);
				item.getChildren().clear();
				continue;
			}
			
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
		
		return changed;
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
		else if (statusFound.size() == 1 && statusFound.contains(Status.SYNC))
			newSatus = Status.SYNC;
		else if (statusFound.size() == 1 && statusFound.contains(Status.DELETED)
		        && !Files.exists(item.getValue().getPath()))
			newSatus = Status.DELETED;
		else if ((statusFound.contains(Status.UNSYNC) || statusFound.contains(Status.DELETED)) && allFullyLoaded)
			newSatus = Status.UNSYNC;
		else
			newSatus = Status.NOT_FULLY_LOADED;
		
		if (newSatus == item.getValue().getStatus())
			return;
		
		item.getValue().setStatus(newSatus);
		
		TreeItem<FileSystemElement> parent = item.getParent();
		if (parent != null)
		{
			if (newSatus == Status.EMPTY)
				parent.getChildren().remove(item);
			
			updateFolderAndParentStatus(parent, updateOnlyIfFullyLoaded);
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
				
				if (removeFakeItem(item))
					refresh(List.of(item.getValue().getPath()), true);
			}
		}
	}
	
	private static boolean removeFakeItem(TreeItem<FileSystemElement> item)
	{
		return item.getChildren().removeIf(subItem -> subItem.getValue() == null);
	}
	
	public void synchronizeFileSystem(Collection<Path> paths, boolean deep)
	{
		assert paths != null;
		assert paths.stream().allMatch(Path::isAbsolute);
		
		Platform.runLater(() ->
		{
			for (Path path : (deep ? commonParents(paths) : paths))
				doSynchronize(findTreeItem(path), deep);
		});
	}
	
	private void doSynchronize(TreeItem<FileSystemElement> item, boolean deep)
	{
		if (item == null || item.getValue() == null)
			return;
		
		FileSystemElement element = item.getValue();
		
		if (element.isImage())
		{
			Image image = element.getImage();
			if (!image.isSaved())
			{
				gallery.saveImage(image);
				item.setValue(new FileSystemElement(image, Status.SYNC));
				updateFolderAndParentStatus(item.getParent(), false);
			}
		}
		else
		{
			for (TreeItem<FileSystemElement> subItem : item.getChildren())
				doSynchronize(subItem, deep);
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
	
	public List<Image> getSelectedImages()
	{
		return treeView.getSelectionModel()
		               .getSelectedItems()
		               .stream()
		               .flatMap(FileSystemTreeManager::getImages)
		               .distinct()
		               .toList();
	}
	
	private static Stream<Image> getImages(TreeItem<FileSystemElement> rootItem)
	{
		if (rootItem == null || rootItem.getValue() == null)
			return Stream.empty();
		else if (rootItem.getValue().isImage())
			return Stream.of(rootItem.getValue().getImage());
		else
			return rootItem.getChildren().stream().flatMap(FileSystemTreeManager::getImages);
	}
	
	private class FileSystemTreeCellFactory
	        implements Callback<TreeView<FileSystemElement>, TreeCell<FileSystemElement>>
	{
		private final FileSystemTreeContextMenu contextMenu;
		private boolean contextMenuListenerSet = false;
		
		public FileSystemTreeCellFactory()
		{
			this.contextMenu = new FileSystemTreeContextMenu();
		}
		
		@Override
		public TreeCell<FileSystemElement> call(TreeView<FileSystemElement> treeView)
		{
			if (!contextMenuListenerSet)
			{
				contextMenu.setSelection(treeView.getSelectionModel());
				contextMenuListenerSet = true;
			}
			
			TextFieldTreeCell<FileSystemElement> cell = new TextFieldTreeCell<FileSystemElement>()
			{
				@Override
				public void updateItem(FileSystemElement element, boolean empty)
				{
					super.updateItem(element, empty);
					
					getStyleClass().remove(CUT_ELEMENT_STYLE_CLASS);
					
					if (empty)
					{
						setText("");
						setGraphic(null);
					}
					else
					{
						setText(element.getPath().getFileName().toString());
						ImageView iv = new ImageView(element.getIcon());
						iv.setFitHeight(16);
						iv.setFitWidth(16);
						iv.setPreserveRatio(true);
						setGraphic(iv);
						
						if (Clipboard.getSystemClipboard().hasFiles()
						        && Clipboard.getSystemClipboard().getFiles().contains(element.getPath().toFile()))
							getStyleClass().add(CUT_ELEMENT_STYLE_CLASS);
					}
				}
				
				@Override
				public void commitEdit(FileSystemElement newElement)
				{
					FileSystemElement oldElement = getItem();
					
					if (newElement == oldElement)
						cancelEdit();
					else
					{
						try
						{
							Path source = oldElement.getPath();
							Path target = newElement.getPath();
							
							Utils.move(source, target, StandardCopyOption.REPLACE_EXISTING);
							gallery.move(source, target);
							
							// If oldElement is an image, then gallery.move updated its Image, which we want
							// to keep because it have the right id) while newElement.getImage was just a
							// temporary object
							if (oldElement.isImage())
								newElement = oldElement;
							
							super.commitEdit(newElement);
							
							TreeItem<FileSystemElement> item = getTreeItem();
							TreeItem<FileSystemElement> parent = item.getParent();
							
							parent.getChildren().remove(item);
							merge(parent, List.of(item));
							
							updateMovedItem(source, target, item);
							sort(parent);
						}
						catch (Exception e)
						{
							cancelEdit();
						}
					}
				}
			};
			
			cell.setContextMenu(contextMenu);
			cell.setOnContextMenuRequested(e -> contextMenu.setSelectedCell(cell));
			cell.setOnDragDetected((MouseEvent event) -> dragDetected(event, cell));
			cell.setOnDragOver((DragEvent event) -> dragOver(event, cell));
			cell.setOnDragDropped((DragEvent event) -> drop(event, cell));
			cell.setOnDragDone((DragEvent event) -> clearDropLocation());
			cell.setEditable(false);
			
			StringConverter<FileSystemElement> converter = new StringConverter<FileSystemElement>()
			{
				@Override
				public String toString(FileSystemElement element)
				{
					if (element == null)
						return null;
					
					return element.getPath().getFileName().toString();
				}
				
				@Override
				public FileSystemElement fromString(String filename)
				{
					FileSystemElement oldElement = cell.getItem();
					if (toString(oldElement).equals(filename))
						return oldElement;
					
					Path newPath = oldElement.getPath().resolveSibling(filename);
					FileSystemElement element;
					
					if (oldElement.isDirectory())
					{
						element = new FileSystemElement(newPath);
						element.setStatus(oldElement.getStatus());
					}
					else
					{
						Image image = gallery.getImage(gallery.toRelativePath(newPath));
						element = new FileSystemElement(image, oldElement.getStatus());
					}
					
					return element;
				}
			};
			cell.setConverter(converter);
			
			return cell;
		}
	}
	
	static private final String DROP_HINT_STYLE_CLASS = "drop-target";
	
	private TreeCell<FileSystemElement> dropZone = null;
	
	// only if all selected have same parent and not root
	private void dragDetected(MouseEvent event, TreeCell<FileSystemElement> treeCell)
	{
		List<File> draggedItemsPath = treeView.getSelectionModel()
		                                      .getSelectedItems()
		                                      .stream()
		                                      .map(TreeItem::getValue)
		                                      .map(FileSystemElement::getPath)
		                                      .map(Path::toFile)
		                                      .toList();
		if (draggedItemsPath.isEmpty())
		{
			draggedItemsPath = null;
			return;
		}
		
		Dragboard db = treeCell.startDragAndDrop(TransferMode.MOVE);
		
		ClipboardContent content = new ClipboardContent();
		content.putFiles(draggedItemsPath);
		db.setContent(content);
		db.setDragView(treeCell.snapshot(null, null));
		event.consume();
	}
	
	// not parent of selection or any subdirectory
	private void dragOver(DragEvent event, TreeCell<FileSystemElement> treeCell)
	{
		if (!event.getDragboard().hasContent(DataFormat.FILES))
			return;
		
		if (!Objects.equals(dropZone, treeCell))
			clearDropLocation();
		
		TreeItem<FileSystemElement> thisItem = treeCell.getTreeItem();
		List<Path> draggedItemsPath = event.getDragboard().getFiles().stream().map(File::toPath).toList();
		
		if (thisItem.getValue().isImage() || thisItem.getValue().getStatus() == Status.DELETED
		        || !canBeMovedTo(thisItem.getValue().getPath(), draggedItemsPath))
			return;
		
		event.acceptTransferModes(TransferMode.MOVE);
		if (!Objects.equals(dropZone, treeCell))
		{
			clearDropLocation();
			dropZone = treeCell;
			dropZone.getStyleClass().add(DROP_HINT_STYLE_CLASS);
		}
	}
	
	private void drop(DragEvent event, TreeCell<FileSystemElement> treeCell)
	{
		Dragboard db = event.getDragboard();
		if (!db.hasContent(DataFormat.FILES) || dropZone == null)
		{
			event.setDropCompleted(false);
			return;
		}
		
		TreeItem<FileSystemElement> thisItem = treeCell.getTreeItem();
		Path target = thisItem.getValue().getPath();
		List<Path> draggedItemsPath = event.getDragboard().getFiles().stream().map(File::toPath).toList();
		
		move(target, draggedItemsPath, true);
		
		clearDropLocation();
		
		event.setDropCompleted(true);
	}
	
	private void clearDropLocation()
	{
		if (dropZone != null)
		{
			dropZone.getStyleClass().remove(DROP_HINT_STYLE_CLASS);
			dropZone = null;
		}
	}
	
	static private final String CUT_ELEMENT_STYLE_CLASS = "cut-element";
	
	public void cut(Collection<Path> paths)
	{
		if (paths == null || paths.isEmpty())
			return;
		
		assert paths.stream().allMatch(Path::isAbsolute);
		
		// longest/deepest path first so hen move a directory, it doesn't change
		// following paths
		List<File> files = paths.stream()
		                        .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
		                        .map(Path::toFile)
		                        .toList();
		
		ClipboardContent cbc = new ClipboardContent();
		cbc.putFiles(files);
		Clipboard.getSystemClipboard().setContent(cbc);
	}
	
	public boolean canPaste(Path targetPath)
	{
		Clipboard cb = Clipboard.getSystemClipboard();
		return cb.hasFiles() && canBeMovedTo(targetPath, cb.getFiles().stream().map(File::toPath).toList());
	}
	
	public void paste(Path targetPath)
	{
		Clipboard cb = Clipboard.getSystemClipboard();
		if (!cb.hasFiles())
			return;
		
		move(targetPath, cb.getFiles().stream().map(File::toPath).toList(), true);
		
		cb.clear();
	}
	
	private static boolean canBeMovedTo(Path target, Collection<Path> pathsToMove)
	{
		return pathsToMove != null && target != null
		        && !pathsToMove.stream().anyMatch(p -> target.startsWith(p) || target.equals(p));
	}
	
	private void move(Path target, Collection<Path> pathsToMove, boolean updateSelection)
	{
		if (target == null || pathsToMove == null || pathsToMove.isEmpty())
			return;
		
		List<Path> fPathsToMove = pathsToMove.stream()
		                                     .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
		                                     .toList();
		
		Platform.runLater(() ->
		{
			TreeItem<FileSystemElement> targetItem = findTreeItem(target);
			if (targetItem == null)
				return;
			
			List<TreeItem<FileSystemElement>> movedItems = new ArrayList<>(fPathsToMove.size());
			Set<TreeItem<FileSystemElement>> movedItemsParents = new HashSet<>();
			
			for (Path path : fPathsToMove)
			{
				TreeItem<FileSystemElement> item = findTreeItem(path);
				if (item == null)
					continue;
				
				Path newPath = target.resolve(path.getFileName());
				
				try
				{
					// Move files on disk
					Utils.move(path, newPath, StandardCopyOption.REPLACE_EXISTING);
					
					// Move images in gallery, sort order preference, etc
					gallery.move(path, newPath);
					
					// remove from previous location
					TreeItem<FileSystemElement> itemParent = item.getParent();
					itemParent.getChildren().remove(item);
					
					// add to new location
					merge(targetItem, List.of(item));
					
					// Update FileElement
					updateMovedItem(path, newPath, item);
					
					movedItems.add(item);
					movedItemsParents.add(itemParent);
				}
				catch (Exception e)
				{
					e.printStackTrace();
					new ExceptionDialog(e, "Error while moving files").show();
				}
			}
			
			for (TreeItem<FileSystemElement> item : movedItemsParents)
				updateFolderAndParentStatus(item, false);
			updateFolderAndParentStatus(targetItem, false);
			sort(targetItem);
			
			if (updateSelection)
			{
				treeView.getSelectionModel().clearSelection();
				movedItems.forEach(i -> treeView.getSelectionModel().select(i));
			}
		});
	}

	/**
	 * Update the FileSystemElement of directory elements (Images are moved by
	 * Gallery.move)
	 * 
	 * @param source
	 * @param target
	 * @param item
	 */
	private static void updateMovedItem(Path source, Path target, TreeItem<FileSystemElement> item)
	{
		Path itemPath = item.getValue().getPath();
		Path newPath = itemPath.startsWith(source) ? target.resolve(source.relativize(itemPath)) : itemPath;
		
		assert newPath.startsWith(target);
		
		if (item.getValue().isDirectory())
		{
			FileSystemElement element = new FileSystemElement(newPath);
			element.setStatus(item.getValue().getStatus());
			item.setValue(element);
			
			for (TreeItem<FileSystemElement> subItem : item.getChildren())
				updateMovedItem(source, target, subItem);
		}
		// If item.getValue().isImage() no need to do anything as Gallery.move should
		// have been called before (moving the image)
	}
	
	private static void merge(TreeItem<FileSystemElement> target, List<TreeItem<FileSystemElement>> itemsToAdd)
	{
		if (itemsToAdd.isEmpty())
			return;
		
		removeFakeItem(target);
		
		for (TreeItem<FileSystemElement> itemToAdd : itemsToAdd)
		{
			Path filename = itemToAdd.getValue().getPath().getFileName();
			
			boolean found = false;
			for (TreeItem<FileSystemElement> item : target.getChildren())
			{
				if (item.getValue().getPath().getFileName().equals(filename))
				{
					found = true;
					
					if (itemToAdd.getValue().isDirectory())
					{
						List<TreeItem<FileSystemElement>> children = List.copyOf(itemToAdd.getChildren());
						itemToAdd.getChildren().clear();
						
						merge(item, children);
					}
					else
					{
						EnumSet<Status> status = EnumSet.of(item.getValue().getStatus(),
						                                    itemToAdd.getValue().getStatus());
						
						if (status.contains(Status.SYNC))
							item.getValue().setStatus(Status.SYNC);
						else if (status.contains(Status.UNSYNC))
							item.getValue().setStatus(Status.UNSYNC);
						else
							item.getValue().setStatus(Status.DELETED);
					}
				}
			}
			
			if (!found)
				target.getChildren().add(itemToAdd);
		}
		
		updateFolderAndParentStatus(target, false);
	}
}
