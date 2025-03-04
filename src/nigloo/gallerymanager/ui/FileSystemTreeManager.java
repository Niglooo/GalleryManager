package nigloo.gallerymanager.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener.Change;
import javafx.css.Styleable;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.cell.TextFieldTreeCell;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.util.Duration;
import javafx.util.StringConverter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import nigloo.gallerymanager.AsyncPools;
import nigloo.gallerymanager.filesystem.FileSystemElement;
import nigloo.gallerymanager.filesystem.FileSystemElement.Status;
import nigloo.gallerymanager.filesystem.FileSystemService;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Image;
import nigloo.gallerymanager.script.ScriptAPI.APIFileSystemElement;
import nigloo.tool.Utils;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;
import nigloo.tool.javafx.component.dialog.AlertWithIcon;
import nigloo.tool.javafx.component.dialog.ExceptionDialog;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FileSystemTreeManager
{
	private static final Logger LOGGER = LogManager.getLogger(FileSystemTreeManager.class);
	private static final Marker UPDATE_THUMBNAILS = UIController.UPDATE_THUMBNAILS;
	
	private static boolean KEEP_EMPTY_FOLDER = true;
	
	@Inject
	private UIController uiController;
	@Inject
	private Gallery gallery;

	private final FileSystemService fileSystemService;
	
	private final TreeView<ItemValue> treeView;
	private final FileSystemTreeContextMenu contextMenu;
	
	public FileSystemTreeManager(TreeView<ItemValue> treeView, FileSystemService fileSystemService)
	{
		Injector.init(this);

		this.fileSystemService = fileSystemService;
		this.treeView = treeView;

		this.treeView.setRoot(new TreeItem<>(new ItemValue(fileSystemService.getRoot(), true)));
		this.treeView.getRoot().setExpanded(true);
		this.contextMenu = new FileSystemTreeContextMenu(treeView);
		
		treeView.setCellFactory(tv -> new FileSystemTreeCell());
		treeView.setEditable(true);
		treeView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
		treeView.getSelectionModel()
		        .getSelectedItems()
		        .addListener((Change<? extends TreeItem<ItemValue>> c) -> uiController.requestRefreshThumbnails());
		
		AtomicReference<List<File>> oldContentRef = new AtomicReference<>();
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
					TreeItem<ItemValue> item = getTreeItem(file.toPath());
					if (item != null)
					{
						ItemValue itemValue = item.getValue();
						item.setValue(null);
						item.setValue(itemValue);
					}
				}
			}
			
			if (newContent != null)
			{
				for (File file : newContent)
				{
					TreeItem<ItemValue> item = getTreeItem(file.toPath());
					if (item != null)
					{
						ItemValue itemValue = item.getValue();
						item.setValue(null);
						item.setValue(itemValue);
					}
				}
			}
			
			oldContentRef.set(newContent);
		}));
		clipboardObserver.setCycleCount(Timeline.INDEFINITE);
		clipboardObserver.play();

		AsyncPools.SCHEDULED_TASK.scheduleAtFixedRate(
				() -> AsyncPools.FX_APPLICATION.execute(FileSystemTreeManager.this::updateTree),
				REFRESH_PAUSE_DURATION_MS,
				REFRESH_PAUSE_DURATION_MS,
				TimeUnit.MILLISECONDS
		);
	}

	@RequiredArgsConstructor
	public static class ItemValue implements APIFileSystemElement {
		private final FileSystemElement element;
		private final boolean isDirectory;
		private long lastUpdate;

		private Status forcedStatus = null;

		public Path getPath() {
			return element.getPath();
		}

		@Override
		public boolean isDirectory() {
			return isDirectory;
		}

		public boolean isImage() {
			return !isDirectory;
		}

		public Image getImage() {
			return isDirectory ? null : element.getImage();
		}

		public long getLastModified() {
			return element.getLastModified();
		}

		public Status getStatus() {
			return forcedStatus != null ? forcedStatus :
				   isDirectory ? element.getStatusDirectory() :
				   element.getStatusImage();
		}
	}


	private static final long REFRESH_MAX_DURATION_MS = 100;
	private static final long REFRESH_PAUSE_DURATION_MS = 1000;

	private final ArrayList<UpdateInfo> toUpdate = new ArrayList<>();

	private record UpdateInfo(
			boolean sort,
			TreeItem<ItemValue>  parentItem, TreeItem<ItemValue> item,
			FileSystemElement parentElement, @NonNull FileSystemElement element, boolean folder) {}

	private void updateTree()
	{
		assert Platform.isFxApplicationThread();

//		uiController.fileSystemService.printDebug(System.out);

		if (toUpdate.isEmpty()) {
			toUpdate.add(new UpdateInfo(false, null, treeView.getRoot(), null, treeView.getRoot().getValue().element, true));
		}

		int nbItemRefreshed = 0;
		long start = System.currentTimeMillis();

		while (!toUpdate.isEmpty() && (System.currentTimeMillis() - start) < REFRESH_MAX_DURATION_MS)
		{
			UpdateInfo updateInfo = toUpdate.removeLast();
			assert updateInfo.folder && updateInfo.element.isDirectory() || !updateInfo.folder && updateInfo.element.isImage();
			TreeItem<ItemValue> item = updateInfo.item;
			FileSystemElement element = updateInfo.element;
			boolean isDirectory = updateInfo.folder;
			long lastUpdateElement = element.getLastUpdate();

			if (updateInfo.sort) {
				sort(item);
				continue;
			}

			//Up-to-date
			if (item != null && lastUpdateElement > 0 && item.getValue().lastUpdate >= lastUpdateElement) {
				continue;
			}

			if (item == null) {
				item = new TreeItem<>(new ItemValue(element, isDirectory));
				updateInfo.parentItem.getChildren().add(item);
			}
			else if (item.getValue().element != element) {
				item.setValue(new ItemValue(element, isDirectory));
			}

			item.getValue().forcedStatus = null;

			if (!item.getValue().isDirectory)
			{
				item.getChildren().clear();
			}
			else
			{
				disableAutoRefreshOnOpen(item);
				toUpdate.add(new UpdateInfo(true, null, item, null, element, true));

				record ItemKey(Path path, boolean isDirectory){
					static ItemKey fromItem(TreeItem<ItemValue> item) {
						return new ItemKey(item.getValue().getPath(), item.getValue().isDirectory());
					}
				}
				Map<ItemKey, TreeItem<ItemValue>> childrenItems = item
						.getChildren()
						.stream()
						.collect(Collectors.toMap(ItemKey::fromItem, i -> i));

				var itemsToRemove = new ArrayList<>(item.getChildren());
				for (FileSystemElement childElement : element.getChildren()) {
					if (childElement.isImage()) {
						TreeItem<ItemValue> childItem = childrenItems.get(new ItemKey(childElement.getPath(),false));
						toUpdate.add(new UpdateInfo(false, item, childItem, element, childElement, false));
						itemsToRemove.remove(childItem);
					}
					if (childElement.isDirectory()) {
						TreeItem<ItemValue> childItem = childrenItems.get(new ItemKey(childElement.getPath(),true));
						toUpdate.add(new UpdateInfo(false, item, childItem, element, childElement, true));
						itemsToRemove.remove(childItem);
					}
				}

				item.getChildren().removeAll(itemsToRemove);

				if (element.getStatusDirectory() == Status.NOT_LOADED) {
					enableAutoRefreshOnOpen(item);
				}
			}

			item.getValue().lastUpdate = lastUpdateElement;

			nbItemRefreshed++;
		}

		LOGGER.trace("{} item(s) refresh", nbItemRefreshed);
	}

	private void enableAutoRefreshOnOpen(TreeItem<ItemValue> item)
	{
		assert item.getValue().isDirectory();

		item.getChildren().add(new TreeItem<>());
		item.setExpanded(false);
		item.expandedProperty().addListener(new NewFolderExpandListener(item));
	}

	private static void disableAutoRefreshOnOpen(TreeItem<ItemValue> item)
	{
		item.getChildren().removeIf(subItem -> subItem.getValue() == null);
		//TODO remove listener from expandedProperty but where to store it??
	}

	private static boolean isEmptyDirectoryWithAutoRefreshOnOpen(TreeItem<ItemValue> item)
	{
		return item.getChildren().size() == 1 && item.getChildren().getFirst().getValue() == null;
	}

	private class NewFolderExpandListener implements ChangeListener<Boolean>
	{
		private final TreeItem<ItemValue> item;

		public NewFolderExpandListener(TreeItem<ItemValue> item)
		{
			this.item = item;
		}

		@Override
		public void changed(ObservableValue<? extends Boolean> obs, Boolean expandedBefore, Boolean expanded)
		{
			if (isEmptyDirectoryWithAutoRefreshOnOpen(item))
			{
				item.getValue().forcedStatus = Status.LOADING;
				fileSystemService.refresh(List.of(item.getValue().getPath()), false);
			}
			item.expandedProperty().removeListener(this);
			disableAutoRefreshOnOpen(item);
		}
	}

	private void sort(TreeItem<ItemValue> item)
	{
		Comparator<APIFileSystemElement> comparator = gallery.getSortOrder(item.getValue().getPath());
		item.getChildren().sort(Comparator.comparing(TreeItem::getValue, comparator));
	}
	
	private static void setStatus(TreeItem<ItemValue> item, Status status)
	{
		item.getValue().forcedStatus = status;
	}
	
	private TreeItem<ItemValue> getTreeItem(Path path)
	{
		return getTreeItem(path, false);
	}
	
	private TreeItem<ItemValue> getTreeItem(Path path, boolean createWithParents)
	{
		return getTreeItem(treeView.getRoot(), path, createWithParents);
	}
	
	private TreeItem<ItemValue> getTreeItem(TreeItem<ItemValue> fromItem,
											Path path,
											boolean createWithParents)
	{
		if (path.equals(fromItem.getValue().getPath()))
			return fromItem;
		
		if (!isEmptyDirectoryWithAutoRefreshOnOpen(fromItem))
			for (TreeItem<ItemValue> subItem : fromItem.getChildren())
				if (path.startsWith(subItem.getValue().getPath()))
					return getTreeItem(subItem, path, createWithParents);
		
		if (!createWithParents)
			return null;
		
		disableAutoRefreshOnOpen(fromItem);
		
		Path parentPath = fromItem.getValue().getPath();
		Path newPath = parentPath.getRoot().resolve(path.subpath(0, parentPath.getNameCount() + 1));
		TreeItem<ItemValue> newItem = new TreeItem<>();
		fromItem.getChildren().add(newItem);
		if (newPath.equals(path))
			return newItem;
		
		newItem.setValue(new ItemValue(new FileSystemElement(newPath, Status.NOT_FULLY_LOADED), true));
		sort_old(fromItem);
		updateFolderAndParentStatus(fromItem, false);
		
		return getTreeItem(newItem, path, true);
	}

	@Deprecated
	private void sort_old(TreeItem<ItemValue> item)
	{
		Comparator<APIFileSystemElement> comparator = gallery.getSortOrder(item.getValue().getPath());
		
		item.getChildren().sort(Comparator.comparing(TreeItem::getValue, comparator));
		for (TreeItem<ItemValue> subItem : item.getChildren())
			if (subItem.getValue().getStatus() == Status.DELETED)
				sort_old(subItem);
	}
	
	private void updateFolderAndParentStatus(TreeItem<ItemValue> item, boolean hasAllChildren)
	{
		Status currentStatus = item.getValue().getStatus();
		
		if (currentStatus != Status.NOT_LOADED && currentStatus.isNotFullyLoaded() && !hasAllChildren)
			return;
		
		EnumSet<Status> statusFound = EnumSet.noneOf(Status.class);
		if (!isEmptyDirectoryWithAutoRefreshOnOpen(item))
			for (TreeItem<ItemValue> subItem : item.getChildren())
				statusFound.add(subItem.getValue().getStatus());
		
		statusFound.remove(Status.EMPTY); // Ignore empty folders
		boolean allFullyLoaded = statusFound.stream().allMatch(Status::isFullyLoaded);
		
		Status newSatus;
		if (statusFound.isEmpty())
			newSatus = Status.EMPTY;
		else if (statusFound.size() == 1 && statusFound.contains(Status.SYNC) && allFullyLoaded)
			newSatus = Status.SYNC;
		else if (statusFound.size() == 1 && statusFound.contains(Status.DELETED)
		        && !Files.exists(item.getValue().getPath()))
			newSatus = Status.DELETED;
		else if (statusFound.contains(Status.LOADING))
			newSatus = Status.LOADING;
		else if ((statusFound.contains(Status.UNSYNC) || statusFound.contains(Status.DELETED)) && allFullyLoaded)
			newSatus = Status.UNSYNC;
		else
			newSatus = Status.NOT_FULLY_LOADED;
		
		if (newSatus == currentStatus)
			return;
		
		setStatus(item, newSatus);
		
		TreeItem<ItemValue> parent = item.getParent();
		if (parent != null)
		{
			if (newSatus == Status.EMPTY && !KEEP_EMPTY_FOLDER)
				parent.getChildren().remove(item);
			
			updateFolderAndParentStatus(parent, false);
		}
	}

	
	public CompletableFuture<Void> delete(Collection<Path> paths, boolean deleteOnDisk)
	{
		assert paths != null;
		assert paths.stream().allMatch(Path::isAbsolute);
		assert paths.stream().allMatch(p -> p.startsWith(gallery.getRootFolder()));
		
		final Collection<Path> pathsToDelete = FileSystemService.withoutChildren(paths);
		
		return CompletableFuture.supplyAsync(() ->
		{
			List<TreeItem<ItemValue>> itemsToDelete = pathsToDelete.stream()
																   .map(this::getTreeItem)
																   .filter(Objects::nonNull)
																   .filter(item -> item.getParent() != null)
																   .toList();

			List<ItemValue> elements = itemsToDelete.stream()
													.flatMap(FileSystemTreeManager::getElements)
													.toList();

			long nbImages = elements.stream().filter(ItemValue::isImage).count();

			AlertWithIcon warningPopup = new AlertWithIcon(AlertType.WARNING);
			warningPopup.setTitle("Delete images");
			if (nbImages == 1)
				warningPopup.setHeaderText("Delete \""
				        + elements.stream().filter(ItemValue::isImage).findAny().get().getPath().getFileName()
				        + "\"?");
			else
				warningPopup.setHeaderText("Delete " + nbImages + " image(s)?");
			warningPopup.setContentText("This action cannot be undone!");
			warningPopup.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
			warningPopup.setDefaultButton(ButtonType.NO);

			Optional<ButtonType> button = warningPopup.showAndWait();
			if (button.isEmpty() || button.get() != ButtonType.YES)
				return List.<ItemValue>of();

			for (TreeItem<ItemValue> item : itemsToDelete)
			{
				TreeItem<ItemValue> parent = item.getParent();
				parent.getChildren().remove(item);
				LOGGER.debug("Remove item " + item.getValue() + " from " + parent.getValue());
				updateFolderAndParentStatus(parent, false);
			}

			uiController.requestRefreshThumbnails();

			return elements;
		}, AsyncPools.FX_APPLICATION).thenAcceptAsync((List<ItemValue> elements) ->
		{
			gallery.deleteImages(elements.stream()
			                             .filter(ItemValue::isImage)
			                             .map(ItemValue::getImage)
			                             .toList());
			for (ItemValue element : elements)
			{
				gallery.setSortOrder(element.getPath(), null);
				gallery.setSubDirectoriesSortOrder(element.getPath(), null);
			}
			
			if (deleteOnDisk)
			{
				IOException error = null;
				for (ItemValue element : elements)
				{
					try
					{
						if (element.isImage() || (Files.exists(element.getPath()) && Files.list(element.getPath()).findAny().isEmpty()))
						{
							Files.deleteIfExists(element.getPath());
							LOGGER.debug("Deleting from disk : " + element.getPath());
						}
					}
					catch (IOException e)
					{
						error = e;
					}
				}
				
				if (error != null)
					throw new RuntimeException(error);
			}
		}, AsyncPools.DISK_IO).whenCompleteAsync(showException("Error when deleting files"), AsyncPools.FX_APPLICATION);
	}
	
	private static Stream<ItemValue> getElements(TreeItem<ItemValue> item)
	{
		if (isEmptyDirectoryWithAutoRefreshOnOpen(item))
			return Stream.of();
		
		return Stream.concat(item.getChildren().stream().flatMap(FileSystemTreeManager::getElements),
		                     Stream.of(item.getValue()));
	}
	
	public Collection<Path> getSelectionWithoutChildren()
	{
		List<Path> selectedPaths = treeView.getSelectionModel()
		               .getSelectedItems()
		               .stream()
		               .map(TreeItem::getValue)
		               .map(ItemValue::getPath).toList();
		
		return FileSystemService.withoutChildren(selectedPaths);
	}
	
	private class FileSystemTreeCell extends TextFieldTreeCell<ItemValue>
	{
		private final static String IMAGE_STYLE_CLASS = "image";
		private final static String FOLDER_STYLE_CLASS = "folder";
		private final static Map<Status, String> STATUS_STYLE_CLASSES;
		static {
			STATUS_STYLE_CLASSES = new EnumMap<>(Status.class);
			STATUS_STYLE_CLASSES.put(Status.NOT_LOADED, "not-loaded");
			STATUS_STYLE_CLASSES.put(Status.LOADING, "loading");
			STATUS_STYLE_CLASSES.put(Status.NOT_FULLY_LOADED, "not-fully-loaded");
			STATUS_STYLE_CLASSES.put(Status.EMPTY, "empty");
			STATUS_STYLE_CLASSES.put(Status.SYNC, "sync");
			STATUS_STYLE_CLASSES.put(Status.UNSYNC, "unsync");
			STATUS_STYLE_CLASSES.put(Status.DELETED, "deleted");
		}
		
		public FileSystemTreeCell()
		{
			this.setOnDragDetected((MouseEvent event) -> dragDetected(event, this));
			this.setOnDragOver((DragEvent event) -> dragOver(event, this));
			this.setOnDragDropped((DragEvent event) -> drop(event, this));
			this.setOnDragDone((DragEvent event) -> clearDropLocation());
			this.setEditable(true);
			// Disable edit on click selected element
			this.addEventFilter(MouseEvent.MOUSE_PRESSED, e ->
			{
				// If we simple click on the cell while it's selected, we want to consume the
				// event to prevent it to go into edit mode (defaut behavior, non
				// overridable...)
				if (e.getClickCount() == 1 && e.getButton() == MouseButton.PRIMARY && this.isSelected()
				        && !e.isControlDown() && !e.isShiftDown())
				{
					// ... except if the target of the event is the arrow to expand/collapse, we
					// want to keep that behavior
					if (e.getTarget() instanceof Styleable n && n.getStyleClass().contains("arrow"))
						return;
					
					// Behavior we want instead.
					int row = treeView.getRow(getTreeItem());
					treeView.getSelectionModel().clearAndSelect(row);
					treeView.requestFocus();
					
					e.consume();
				}
			});
			this.setConverter(new StringConverter<ItemValue>()
			{
				@Override
				public String toString(ItemValue element)
				{
					if (element == null)
						return null;
					
					return element.getPath().getFileName().toString();
				}
				
				@Override
				public ItemValue fromString(String filename)
				{
					ItemValue oldElement = FileSystemTreeCell.this.getItem();
					if (toString(oldElement).equals(filename))
						return oldElement;
					
					Path newPath = oldElement.getPath().resolveSibling(filename);
					
					if (oldElement.isDirectory())
						return new ItemValue(new FileSystemElement(newPath, oldElement.getStatus()), oldElement.isDirectory());
					else
						return new ItemValue(new FileSystemElement(gallery.getImage(newPath), oldElement.getStatus()), oldElement.isDirectory());
				}
			});
		}
		
		@Override
		public void updateItem(ItemValue element, boolean empty)
		{
			super.updateItem(element, empty);
			
			getStyleClass().remove(CUT_ELEMENT_STYLE_CLASS);
			
			if (empty)
			{
				setText("");
				setGraphic(null);
				setContextMenu(null);
			}
			else
			{
				setText(element.getPath().getFileName().toString());
				
				// Recycle the icon
				FontIcon icon = (FontIcon) getGraphic();
				if (icon != null) {
					icon.getStyleClass().removeAll(IMAGE_STYLE_CLASS, FOLDER_STYLE_CLASS);
					icon.getStyleClass().removeAll(STATUS_STYLE_CLASSES.values());
				} else {
					icon = new FontIcon();
					setGraphic(icon);
				}
				
				if (element.isImage())
					icon.getStyleClass().add(IMAGE_STYLE_CLASS);
				else if (element.isDirectory())
					icon.getStyleClass().add(FOLDER_STYLE_CLASS);
				
				icon.getStyleClass().add(STATUS_STYLE_CLASSES.get(element.getStatus()));
				
				icon.applyCss();// Necessary to avoid blinking (broken icon (square) because the css in not applied on time)
				
				setContextMenu(contextMenu);
				
				//TODO use pseudo class for cut element? same with drop-target ?)
				if (Clipboard.getSystemClipboard().hasFiles()
				        && Clipboard.getSystemClipboard().getFiles().contains(element.getPath().toFile()))
					getStyleClass().add(CUT_ELEMENT_STYLE_CLASS);
			}
		}
		
		@Override
		public void commitEdit(ItemValue newElement)
		{
			ItemValue oldElement = getItem();
			
			if (newElement == oldElement)
				cancelEdit();
			else
			{
				try
				{
					Path source = oldElement.getPath();
					Path target = newElement.getPath();
					
					if (Files.exists(source))
						Utils.move(source, target, StandardCopyOption.REPLACE_EXISTING);
					
					gallery.move(source, target);
					
					// If oldElement is an image, then gallery.move updated its Image, which we want
					// to keep because it have the right id, while newElement.getImage was just a
					// temporary object
					if (oldElement.isImage())
						newElement = oldElement;
					
					super.commitEdit(newElement);
					
					TreeItem<ItemValue> item = getTreeItem();
					TreeItem<ItemValue> parent = item.getParent();
					
					parent.getChildren().remove(item);
					merge(parent, List.of(item));
					
					updateMovedItem(source, target, item);
					sort_old(parent);
					
					treeView.getSelectionModel().clearSelection();
					treeView.getSelectionModel().select(item);
				}
				catch (Exception e)
				{
					cancelEdit();
				}
			}
		}
	}
	
	static private final String DROP_HINT_STYLE_CLASS = "drop-target";
	
	private TreeCell<ItemValue> dropZone = null;
	
	// only if all selected have same parent and not root
	private void dragDetected(MouseEvent event, TreeCell<ItemValue> treeCell)
	{
		List<File> draggedItemsPath = treeView.getSelectionModel()
		                                      .getSelectedItems()
		                                      .stream()
		                                      .map(TreeItem::getValue)
		                                      .map(ItemValue::getPath)
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
	private void dragOver(DragEvent event, TreeCell<ItemValue> treeCell)
	{
		if (treeCell.getTreeItem() == null)
			return;

		if (!event.getDragboard().hasContent(DataFormat.FILES))
			return;
		
		if (!Objects.equals(dropZone, treeCell))
			clearDropLocation();
		
		TreeItem<ItemValue> thisItem = treeCell.getTreeItem();
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
	
	private void drop(DragEvent event, TreeCell<ItemValue> treeCell)
	{
		Dragboard db = event.getDragboard();
		if (!db.hasContent(DataFormat.FILES) || dropZone == null)
		{
			event.setDropCompleted(false);
			return;
		}
		
		TreeItem<ItemValue> thisItem = treeCell.getTreeItem();
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
		        && pathsToMove.stream().noneMatch(p -> target.startsWith(p) || target.equals(p));
	}


	private void move(Path targetDirectory, Collection<Path> pathsToMove, boolean updateSelection)
	{
		if (targetDirectory == null || pathsToMove == null || pathsToMove.isEmpty())
			return;

		assert canBeMovedTo(targetDirectory, pathsToMove);

		record ToMove(Path source, CompletableFuture<FileSystemElement> future) {}
		List<ToMove> moves = new ArrayList<>();
		for (Path source : pathsToMove) {
			Path target = targetDirectory.resolve(source.getFileName());
			moves.add(new ToMove(source, fileSystemService.move(source, target)));
		}

		CompletableFuture
				.allOf(moves.stream().map(ToMove::future).toArray(CompletableFuture[]::new))
				.thenRunAsync(() -> {
					TreeItem<ItemValue> targetDirectoryItem = getTreeItem(targetDirectory);
					if (targetDirectoryItem == null)
						return;

					ArrayList<TreeItem<ItemValue>> actuallyMovedItems = new ArrayList<>(moves.size());
					for (ToMove move : moves) {
						if (move.future().isCompletedExceptionally())
							continue;

						TreeItem<ItemValue> movedItem = getTreeItem(move.source);
						if (movedItem == null)
							continue;

						disableAutoRefreshOnOpen(targetDirectoryItem);
						targetDirectoryItem.getChildren().add(movedItem);
						actuallyMovedItems.add(movedItem);
					}

					sort(targetDirectoryItem);

					if (updateSelection)
					{
						treeView.getSelectionModel().clearSelection();
						for (TreeItem<ItemValue> item : actuallyMovedItems)
							treeView.getSelectionModel().select(item);
					}

				}, AsyncPools.FX_APPLICATION);



		//TODO remove
//		List<Path> fPathsToMove = pathsToMove.stream()
//											 .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
//											 .toList();
//
//		CompletableFuture.runAsync(() ->
//		{
//			TreeItem<FileSystemElement> targetItem = getTreeItem(targetDirectory);
//			if (targetItem == null)
//				return;
//
//			List<TreeItem<FileSystemElement>> movedItems = new ArrayList<>(fPathsToMove.size());
//			Set<TreeItem<FileSystemElement>> movedItemsParents = new HashSet<>();
//
//			for (Path path : fPathsToMove)
//			{
//				TreeItem<FileSystemElement> item = getTreeItem(path);
//				if (item == null)
//					continue;
//
//				Path newPath = targetDirectory.resolve(path.getFileName());
//
//				try
//				{
//					// Move files on disk
//					if (Files.exists(path))
//						Utils.move(path, newPath, StandardCopyOption.REPLACE_EXISTING);
//
//					// Move images in gallery, sort order preference, etc
//					gallery.move(path, newPath);
//
//					// remove from previous location
//					TreeItem<FileSystemElement> itemParent = item.getParent();
//					itemParent.getChildren().remove(item);
//
//					// add to new location
//					merge(targetItem, List.of(item));
//
//					// Update FileElement
//					updateMovedItem(path, newPath, item);
//
//					movedItems.add(item);
//					movedItemsParents.add(itemParent);
//				}
//				catch (Exception e)
//				{
//					new ExceptionDialog(e, "Error while moving files").show();
//				}
//			}
//
//			for (TreeItem<FileSystemElement> item : movedItemsParents)
//				updateFolderAndParentStatus(item, false);
//			updateFolderAndParentStatus(targetItem, false);
//			sort_old(targetItem);
//
//			if (updateSelection)
//			{
//				treeView.getSelectionModel().clearSelection();
//				for (TreeItem<FileSystemElement> item : movedItems)
//					treeView.getSelectionModel().select(item);
//			}
//		}, AsyncPools.FX_APPLICATION);
	}
	
	/**
	 * Update the FileSystemElement of directory elements (Images are moved by
	 * Gallery.move)
	 * 
	 * @param source
	 * @param target
	 * @param item
	 */
	private static void updateMovedItem(Path source, Path target, TreeItem<ItemValue> item)
	{
		Path itemPath = item.getValue().getPath();
		Path newPath = itemPath.startsWith(source) ? target.resolve(source.relativize(itemPath)) : itemPath;
		
		assert newPath.startsWith(target);
		
		if (item.getValue().isDirectory())
		{
			item.setValue(new ItemValue(new FileSystemElement(newPath, item.getValue().getStatus()), item.getValue().isDirectory()));
			
			for (TreeItem<ItemValue> subItem : item.getChildren())
				updateMovedItem(source, target, subItem);
		}
		// If item.getValue().isImage() no need to do anything as Gallery.move should
		// have been called before (moving the image)
	}
	
	private void merge(TreeItem<ItemValue> target, List<TreeItem<ItemValue>> itemsToAdd)
	{
		if (itemsToAdd.isEmpty())
			return;
		
		disableAutoRefreshOnOpen(target);
		
		for (TreeItem<ItemValue> itemToAdd : itemsToAdd)
		{
			Path filename = itemToAdd.getValue().getPath().getFileName();
			
			boolean found = false;
			for (TreeItem<ItemValue> item : target.getChildren())
			{
				if (item.getValue().getPath().getFileName().equals(filename))
				{
					found = true;
					
					if (itemToAdd.getValue().isDirectory())
					{
						List<TreeItem<ItemValue>> children = List.copyOf(itemToAdd.getChildren());
						itemToAdd.getChildren().clear();
						
						merge(item, children);
					}
					else
					{
						EnumSet<Status> status = EnumSet.of(item.getValue().getStatus(),
						                                    itemToAdd.getValue().getStatus());
						
						if (status.contains(Status.SYNC))
							setStatus(item, Status.SYNC);
						else if (status.contains(Status.UNSYNC))
							setStatus(item, Status.UNSYNC);
						else
							setStatus(item, Status.DELETED);
					}
				}
			}

			if (!found)
				target.getChildren().add(itemToAdd);
		}
		
		updateFolderAndParentStatus(target, false);
	}
	
	public void newDirectoryIn(Path parentDirectory, boolean editInView)
	{
		assert parentDirectory != null;
		assert parentDirectory.isAbsolute();
		assert parentDirectory.startsWith(gallery.getRootFolder());
		
		CompletableFuture.supplyAsync(() ->
		{
			TreeItem<ItemValue> item = getTreeItem(parentDirectory);
			if (item == null)
				return null;
			
			if (isEmptyDirectoryWithAutoRefreshOnOpen(item)) {
				disableAutoRefreshOnOpen(item);
			}
			
			String newFolderName = "New folder";
			for (int i = 2 ; Files.exists(parentDirectory.resolve(newFolderName)) ; i++)
				newFolderName = "New folder (" + i + ")";
			
			Path newFolder = parentDirectory.resolve(newFolderName);
			try
			{
				Files.createDirectory(newFolder);
			}
			catch (IOException e)
			{
				throw new RuntimeException(e);
			}
			
			FileSystemElement element = new FileSystemElement(newFolder, Status.EMPTY);
			TreeItem<ItemValue> newItem = new TreeItem<>(new ItemValue(element, true));
			
			item.getChildren().add(newItem);
			sort_old(item);
			item.setExpanded(true);
			
			int newItemIdx = treeView.getRow(newItem);
			treeView.scrollTo(newItemIdx);
			treeView.getSelectionModel().clearAndSelect(newItemIdx);
			
			return newItem;
		}, AsyncPools.FX_APPLICATION).thenAcceptAsync(newItem ->
		{
			if (editInView)
				treeView.edit(newItem);
		}, AsyncPools.FX_APPLICATION).whenComplete(showException("Error while creating new directory"));
	}
	
	private static <T> CompletableFuture<List<T>> completableFutureAllOf(Collection<CompletableFuture<T>> cfs)
	{
		return CompletableFuture.allOf(cfs.toArray(CompletableFuture[]::new)).thenApply(v -> cfs.stream().map(CompletableFuture<T>::join).toList());
	}
	
	private static <T, E extends Throwable> BiConsumer<T, E> showException(String errorMessage)
	{
		return (T value, E error) -> {
			if (error != null)
			{
				LOGGER.error(errorMessage, error);
				new ExceptionDialog(error, errorMessage).show();
			}
		};
	}
}
