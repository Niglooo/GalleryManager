package nigloo.gallerymanager.ui.dialog;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableObjectValue;
import javafx.beans.value.ObservableValue;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.util.Callback;
import nigloo.gallerymanager.autodownloader.Downloader;
import nigloo.gallerymanager.model.Image;
import nigloo.gallerymanager.ui.UIController;
import nigloo.gallerymanager.ui.dialog.DownloadsProgressViewDialog.ColumnStatusData.ProgressData;
import nigloo.gallerymanager.ui.dialog.DownloadsProgressViewDialog.ItemInfo.DLStatus;
import nigloo.gallerymanager.ui.dialog.DownloadsProgressViewDialog.ItemInfo.ItemType;
import nigloo.tool.MetronomeTimer;
import nigloo.tool.injection.annotation.Singleton;
import nigloo.tool.javafx.component.dialog.ExceptionDialog;
import nigloo.tool.thread.SafeThread;
import nigloo.tool.thread.ThreadStopException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kordamp.ikonli.javafx.FontIcon;

import java.awt.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Singleton
public class DownloadsProgressViewDialog extends Stage
{
	private static final Logger LOGGER = LogManager.getLogger(DownloadsProgressViewDialog.class);
	
	@FXML
	protected TreeTableView<ItemInfo> tableTree;
	@FXML
	protected TreeTableColumn<ItemInfo, String> identifierColumn = new TreeTableColumn<>("Identifier");
	@FXML
	protected TreeTableColumn<ItemInfo, ColumnNameData> nameColumn = new TreeTableColumn<>("Name");
	@FXML
	protected TreeTableColumn<ItemInfo, ColumnStatusData> statusColumn = new TreeTableColumn<>("Status");
	@FXML
	protected TreeTableColumn<ItemInfo, ZonedDateTime> dateColumn = new TreeTableColumn<>("Date");
	
	// MUST be only used in the JavaFX Application Thread
	private final Map<String, TreeItem<ItemInfo>> idToTreeItem;
	private final Set<Long> activeSessions = new HashSet<>();
	private final ReadOnlyBooleanWrapper downloadActive = new ReadOnlyBooleanWrapper(false);
	// MUST be synchronized on
	private Map<Long,Map<String, DownloadProgressInfo>> lastProgress = new HashMap<>();
	private record DownloadProgressInfo(long nbBytesDownloaded, OptionalLong nbBytesTotal){}
	
	private final BooleanBinding showingAndDownloadActive;
	
	
	public DownloadsProgressViewDialog()
	{
		UIController.loadFXML(this, "downloads_progress_view_dialog.fxml");
		getScene().getStylesheets().add(UIController.STYLESHEET_DEFAULT);
		
		tableTree.setRoot(new TreeItem<>());
		tableTree.setRowFactory(new Callback<TreeTableView<ItemInfo>, TreeTableRow<ItemInfo>>()
		{
			@Override
			public TreeTableRow<ItemInfo> call(TreeTableView<ItemInfo> param)
			{
				TreeTableRow<ItemInfo> row = new TreeTableRow<>();
				row.setOnMouseClicked(e -> {
					if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && row.getItem() instanceof FileInfo fileInfo)
					{
						try
						{
							Desktop.getDesktop().open(fileInfo.path.toFile());
						}
						catch (IOException ex)
						{
							new ExceptionDialog(ex, "Cannot open "+fileInfo.path).show();
						}
					}
				});
				return row;
			}
		});
		
		identifierColumn.setCellValueFactory(param -> param.getValue().getValue().getIdentifier());
		
		nameColumn.setCellValueFactory(param -> param.getValue().getValue().getColumnNameData());
		nameColumn.setCellFactory(param -> new NameCell());
		
		statusColumn.setCellValueFactory(param -> param.getValue().getValue().getColumnStatusData());
		statusColumn.setCellFactory(param -> new StatusCell());
		
		dateColumn.setCellValueFactory(param -> param.getValue().getValue().getDate());
		dateColumn.setCellFactory(param -> new DateCell());
		
		idToTreeItem = new HashMap<>();
		
		ProgressUpdaterTread progressUpdaterTread = new ProgressUpdaterTread();
		
		showingAndDownloadActive = showingProperty().and(downloadActive);
		showingAndDownloadActive.addListener((obs, oldValue, dialogShowingAndDownloadActive) -> {
			if (dialogShowingAndDownloadActive)
				progressUpdaterTread.safeResume();
			else
				progressUpdaterTread.safeSuspend();
		});
		
		progressUpdaterTread.start();
		progressUpdaterTread.safeSuspend();
	}
	
	private static String id(Object... ids)
	{
		return Stream.of(ids).map(Object::toString).collect(Collectors.joining(";"));
	}
	
	@FXML
	protected void clearInactiveSessions()
	{
		Platform.runLater(() ->
		{
			Iterator<Entry<String, TreeItem<ItemInfo>>> it = idToTreeItem.entrySet().iterator();
			while (it.hasNext())
			{
				Entry<String, TreeItem<ItemInfo>> entry = it.next();
				String id = entry.getKey();
				int posSep = id.indexOf(';');
				long sessionId = Integer.parseInt(posSep >= 0 ? id.substring(0, posSep) : id);
				
				if (!activeSessions.contains(sessionId))
				{
					it.remove();
					TreeItem<ItemInfo> item = entry.getValue();
					item.getParent().getChildren().remove(item);
				}
			}
		});
	}
	
	public final ReadOnlyBooleanProperty downloadActiveProperty()
	{
		return downloadActive.getReadOnlyProperty();
	}
	
	public void newSession(long sessionId, String name)
	{
		Platform.runLater(() ->
		{
			TreeItem<ItemInfo> sessionItem = new TreeItem<>(new SessionInfo(sessionId, name));
			idToTreeItem.put(id(sessionId), sessionItem);
			sessionItem.setExpanded(true);
			tableTree.getRoot().getChildren().add(sessionItem);
			
			activeSessions.add(sessionId);
			downloadActive.set(true);
		});
	}
	
	public void newPost(long sessionId, String postId, String postTitle, ZonedDateTime postDate)
	{
		Platform.runLater(() ->
		{
			String parentId = id(sessionId);
			TreeItem<ItemInfo> sessionItem = idToTreeItem.get(parentId);
			if (sessionItem == null)
			{
				LOGGER.error("TreeItem for session " + parentId + " not found");
				return;
			}
			
			TreeItem<ItemInfo> postItem = new TreeItem<>(new PostInfo(postId, postTitle, postDate));
			idToTreeItem.put(id(sessionId, postId), postItem);
			postItem.setExpanded(true);
			sessionItem.getChildren().add(postItem);
		});
	}
	
	public void newImage(long sessionId, String postId, String imageId, Path imagePath)
	{
		Platform.runLater(() ->
		{
			String parentId = id(sessionId, postId);
			TreeItem<ItemInfo> postItem = idToTreeItem.get(parentId);
			if (postItem == null)
			{
				LOGGER.error("TreeItem for post " + parentId + " not found");
				return;
			}
			
			TreeItem<ItemInfo> imageItem = new TreeItem<>(new FileInfo(ItemType.IMAGE, imageId, imagePath, null, false));
			idToTreeItem.put(id(sessionId, postId, imageId), imageItem);
			postItem.getChildren().add(imageItem);
		});
	}
	
	public void newExistingImage(long sessionId, String postId, String imageId, Path imagePath)
	{
		Platform.runLater(() ->
		{
			String parentId = id(sessionId, postId);
			TreeItem<ItemInfo> postItem = idToTreeItem.get(parentId);
			if (postItem == null)
			{
				LOGGER.error("TreeItem for post " + parentId + " not found");
				return;
			}
			
			FileInfo fileInfo = new FileInfo(ItemType.IMAGE, imageId, imagePath, null, true);
			fileInfo.setComplete(null);
			TreeItem<ItemInfo> imageItem = new TreeItem<>(fileInfo);
			idToTreeItem.put(id(sessionId, postId, imageId), imageItem);
			postItem.getChildren().add(imageItem);
		});
	}
	
	public void newZip(long sessionId, String postId, String fileId, Path filePath)
	{
		Platform.runLater(() ->
		{
			String parentId = id(sessionId, postId);
			TreeItem<ItemInfo> postItem = idToTreeItem.get(parentId);
			if (postItem == null)
			{
				LOGGER.error("TreeItem for post " + parentId + " not found");
				return;
			}
			
			TreeItem<ItemInfo> zipItem = new TreeItem<>(new FileInfo(ItemType.ZIP, fileId, filePath, null, false));
			idToTreeItem.put(id(sessionId, postId, fileId), zipItem);
			postItem.getChildren().add(zipItem);
		});
	}
	
	public void newOtherFile(long sessionId, String postId, String fileId, Path filePath)
	{
		Platform.runLater(() ->
		{
			String parentId = id(sessionId, postId);
			TreeItem<ItemInfo> postItem = idToTreeItem.get(parentId);
			if (postItem == null)
			{
				LOGGER.error("TreeItem for post " + parentId + " not found");
				return;
			}
			
			TreeItem<ItemInfo> fileItem = new TreeItem<>(new FileInfo(ItemType.OTHER_FILE, fileId, filePath, null, false));
			idToTreeItem.put(id(sessionId, postId, fileId), fileItem);
			postItem.getChildren().add(fileItem);
		});
	}
	
	public void updateFilePath(long sessionId, String postId, String fileId, Path oldFilePath, Path newFilePath)
	{
		if (oldFilePath.equals(newFilePath))
			return;
		
		Platform.runLater(() ->
		{
			String id = id(sessionId, postId, fileId);
			TreeItem<ItemInfo> fileItem = idToTreeItem.get(id);
			if (fileItem == null)
			{
				LOGGER.error("TreeItem for file " + id + " not found");
			}
			else if (!(fileItem.getValue() instanceof FileInfo fileInfo))
			{
				LOGGER.error("TreeItem for " + id + " is not a file: " + fileItem.getValue());
			}
			else
			{
				fileInfo.updatePath(newFilePath);
			}
		});
	}
	
	public void newImageInZip(long sessionId, String postId, String zipFileId, String pathInZip, Path imagePath)
	{
		Platform.runLater(() ->
		{
			String parentId = id(sessionId, postId, zipFileId);
			TreeItem<ItemInfo> zipItem = idToTreeItem.get(parentId);
			if (zipItem == null)
			{
				LOGGER.error("TreeItem for zip " + parentId + " not found");
				return;
			}
			FileInfo fileInfo = new FileInfo(ItemType.IMAGE, pathInZip, imagePath, pathInZip, false);
			fileInfo.setComplete(null);
			TreeItem<ItemInfo> imageItem = new TreeItem<>(fileInfo);
			idToTreeItem.put(id(sessionId, postId, zipFileId, pathInZip), imageItem);
			zipItem.getChildren().add(imageItem);
		});
	}
	
	public void newFileInZip(long sessionId, String postId, String zipFileId, String pathInZip, Path filePath)
	{
		Platform.runLater(() ->
		{
			String parentId = id(sessionId, postId, zipFileId);
			TreeItem<ItemInfo> zipItem = idToTreeItem.get(parentId);
			if (zipItem == null)
			{
				LOGGER.error("TreeItem for zip " + parentId + " not found");
				return;
			}
			FileInfo fileInfo = new FileInfo(ItemType.OTHER_FILE, pathInZip, filePath, pathInZip, false);
			fileInfo.setComplete(null);
			TreeItem<ItemInfo> fileItem = new TreeItem<>(fileInfo);
			idToTreeItem.put(id(sessionId, postId, zipFileId, pathInZip), fileItem);
			zipItem.getChildren().add(fileItem);
		});
	}
	
	public void updateDownloadProgress(long sessionId,
	                                   String postId,
	                                   String fileId,
	                                   long nbBytesDownloaded,
	                                   OptionalLong nbBytesTotal)
	{
		String id = id(sessionId, postId, fileId);
		DownloadProgressInfo newProgressInfo = new DownloadProgressInfo(nbBytesDownloaded, nbBytesTotal);
		synchronized (lastProgress)
		{
			lastProgress.computeIfAbsent(sessionId, s -> new HashMap<>())
			            .compute(id, (i, progressInfo) -> (progressInfo == null
			                              || progressInfo.nbBytesDownloaded() < newProgressInfo.nbBytesDownloaded)
			                                      ? newProgressInfo
			                                      : progressInfo);
		}
	}
	
	public void endDownload(long sessionId, String postId, String fileId, Throwable error)
	{
		Platform.runLater(() ->
		{
			String id = id(sessionId, postId, fileId);
			TreeItem<ItemInfo> fileItem = idToTreeItem.get(id);
			if (fileItem == null)
			{
				LOGGER.error("TreeItem for file " + id + " not found");
				return;
			}
			
			fileItem.getValue().setComplete(error);
		});
	}
	
	public void endPost(long sessionId, String postId, Throwable error)
	{
		Platform.runLater(() ->
		{
			String id = id(sessionId, postId);
			TreeItem<ItemInfo> postItem = idToTreeItem.get(id);
			if (postItem == null)
			{
				LOGGER.error("TreeItem for post " + id + " not found");
				return;
			}
			
			postItem.getValue().setComplete(error);
			boolean allFilesInPostAlreadyExist = postItem
					.getChildren()
					.stream()
					.map(TreeItem::getValue)
					.map(FileInfo.class::cast)
					.allMatch(FileInfo::alreadyExists);
			if (allFilesInPostAlreadyExist)
			{
				postItem.setExpanded(false);
			}
		});
	}
	
	public void endSession(long sessionId, Throwable error)
	{
		Platform.runLater(() ->
		{
			String id = id(sessionId);
			TreeItem<ItemInfo> sessionItem = idToTreeItem.get(id);
			if (sessionItem == null)
			{
				LOGGER.error("TreeItem for session " + id + " not found");
				return;
			}
			
			sessionItem.getValue().setComplete(error);
			
			if (activeSessions.remove(sessionId) && activeSessions.isEmpty())
				downloadActive.set(false);
		});
	}
	
	private class ProgressUpdaterTread extends SafeThread
	{
		static final int UPDATE_INTERVAL = 500;
		
		public ProgressUpdaterTread()
		{
			super("download-progress-updater");
			setDaemon(true);
		}
		
		@Override
		public void run()
		{
			try
			{
				MetronomeTimer timer = new MetronomeTimer(UPDATE_INTERVAL);
				while (true)
				{
					checkThreadState();
					timer.waitNextTick();
					
					synchronized (lastProgress)
					{
						lastProgress.values().stream().flatMap(e -> e.entrySet().stream()).forEach(e ->
						{
							String id = e.getKey();
							DownloadProgressInfo progressInfo = e.getValue();
							
							Platform.runLater(() ->
							{
								TreeItem<ItemInfo> fileItem = idToTreeItem.get(id);
								if (fileItem == null)
								{
									LOGGER.error("TreeItem for file " + id + " not found");
								}
								else if (!(fileItem.getValue() instanceof FileInfo fileInfo))
								{
									LOGGER.error("TreeItem for " + id + " is not a file: " + fileItem.getValue());
								}
								else
								{
									fileInfo.setProgress(progressInfo.nbBytesDownloaded, progressInfo.nbBytesTotal);
								}
							});
						});
						
						LOGGER.trace(() -> "Updated "
						        + lastProgress.values().stream().flatMap(e -> e.entrySet().stream()).count() + " in progress files");
						
						lastProgress.clear();
					}
				}
			}
			catch (ThreadStopException | InterruptedException e)
			{
			}
		}
	}
	
	record ColumnNameData(ItemType type, String name, List<String> styleClasses) implements Comparable<ColumnNameData>
	{
		@Override
		public int compareTo(ColumnNameData o)
		{
			return name.compareToIgnoreCase(o.name);
		}
	}
	
	record ColumnStatusData(DLStatus status, ProgressData progress, Throwable error) implements Comparable<ColumnStatusData>
	{
		record ProgressData(double progress, OptionalLong nbDone, OptionalLong nbTotal)
		{
		}
		
		@Override
		public int compareTo(ColumnStatusData o)
		{
			return Integer.compare(status.ordinal(), o.status.ordinal());
		}
	}
	
	abstract static class ItemInfo
	{
		enum ItemType
		{
			SESSION, POST, IMAGE, ZIP, OTHER_FILE
		}
		
		enum DLStatus
		{
			IN_PROGRESS, COMPLETE, ERROR
		}
		
		protected final ObservableObjectValue<String> identifier;
		protected final SimpleObjectProperty<ColumnNameData> columnNameData;
		protected final ObservableObjectValue<ZonedDateTime> date;
		protected final ObservableValue<ColumnStatusData> columnStatusData;
		protected final SimpleObjectProperty<DLStatus> status;
		protected final SimpleObjectProperty<ProgressData> progress;
		protected final SimpleObjectProperty<Throwable> error;
		
		protected ItemInfo(ItemType type, String identifier, String name, List<String> nameStyleClasses, ZonedDateTime date)
		{
			this.identifier = new ConstantObservableObject<String>(identifier);
			this.columnNameData = new SimpleObjectProperty<>(new ColumnNameData(type, name, nameStyleClasses));
			this.date = new ConstantObservableObject<ZonedDateTime>(date);
			this.status = new SimpleObjectProperty<>(DLStatus.IN_PROGRESS);
			this.progress = new SimpleObjectProperty<>(new ProgressData(0d, OptionalLong.empty(), OptionalLong.empty()));
			this.error = new SimpleObjectProperty<>();
			this.columnStatusData = Bindings.createObjectBinding(() -> new ColumnStatusData(status.getValue(),
			                                                                                progress.getValue(),
			                                                                                error.getValue()),
			                                                     status,
			                                                     progress,
			                                                     error);
		}
		
		public final void setComplete(Throwable error)
		{
			if (error != null)
			{
				this.error.setValue(error);
				this.status.setValue(DLStatus.ERROR);
			}
			else
			{
				this.status.setValue(DLStatus.COMPLETE);
			}
		}
		
		public final ObservableObjectValue<String> getIdentifier()
		{
			return identifier;
		}
		
		public final ObservableObjectValue<ColumnNameData> getColumnNameData()
		{
			return columnNameData;
		}
		
		public final ObservableObjectValue<ZonedDateTime> getDate()
		{
			return date;
		}
		
		public final ObservableValue<ColumnStatusData> getColumnStatusData()
		{
			return columnStatusData;
		}
	}
	
	private static class SessionInfo extends ItemInfo
	{
		public SessionInfo(long sessionId, String name)
		{
			super(ItemType.SESSION, String.valueOf(sessionId), name, null, null);
			progress.setValue(null);
		}
	}
	
	private static class PostInfo extends ItemInfo
	{
		public PostInfo(String postId, String title, ZonedDateTime date)
		{
			super(ItemType.POST, postId, title.isBlank() ? "[No Title]" : title, null, date);
			progress.setValue(null);
		}
	}
	
	private static class FileInfo extends ItemInfo
	{
		private Path path;
		private final boolean alreadyExists;
		
		public FileInfo(ItemType type, String fileId, Path path, String pathInZip, boolean alreadyExist)
		{
			super(type, fileId, path.getFileName().toString(), List.of(alreadyExist ? "already-exist" : "new"), null);
			this.path = path;
			this.alreadyExists = alreadyExist;
		}
		
		public void updatePath(Path newFilePath)
		{
			String filename = newFilePath.getFileName().toString();
			
			ItemType type = columnNameData.get().type();
			if (Downloader.isZip(filename))
				type = ItemType.ZIP;
			else if (Image.isImage(newFilePath))
				type = ItemType.IMAGE;
			
			columnNameData.setValue(new ColumnNameData(type, filename, columnNameData.get().styleClasses()));
			
			path = newFilePath;
		}
		
		public void setProgress(long nbBytesDownloaded, OptionalLong nbBytesTotal)
		{
			double progress = nbBytesTotal.isEmpty() ? ProgressBar.INDETERMINATE_PROGRESS
			        : ((double) nbBytesDownloaded) / nbBytesTotal.getAsLong();
			this.progress.set(new ProgressData(progress, OptionalLong.of(nbBytesDownloaded), nbBytesTotal));
		}

		public boolean alreadyExists() {
			return alreadyExists;
		}
	}
	
	private static class NameCell extends TreeTableCell<ItemInfo, ColumnNameData>
	{
		private static final String CELL_STYLE_CLASS = "download-name-cell";
		
		private static final String ICON_STYLE_CLASS_SESSION = "session-icon";
		private static final String ICON_STYLE_CLASS_POST = "post-icon";
		private static final String ICON_STYLE_CLASS_IMAGE = "image-icon";
		private static final String ICON_STYLE_CLASS_ZIP = "zip-icon";
		private static final String ICON_STYLE_CLASS_OTHER_FILE = "other-file-icon";
		
		private final FontIcon icon;
		
		private List<String> extraStyleClasses;
		
		public NameCell()
		{
			this.getStyleClass().add(CELL_STYLE_CLASS);
			this.icon = new FontIcon();
			this.extraStyleClasses = null;
		}
		
		@Override
		protected void updateItem(ColumnNameData nameData, boolean empty)
		{
			super.updateItem(nameData, empty);
			
			if (empty || nameData == null)
			{
				setGraphic(null);
				setText(null);
			}
			else
			{
				icon.getStyleClass()
				    .removeAll(ICON_STYLE_CLASS_SESSION,
				               ICON_STYLE_CLASS_POST,
				               ICON_STYLE_CLASS_IMAGE,
				               ICON_STYLE_CLASS_ZIP,
				               ICON_STYLE_CLASS_OTHER_FILE);
				icon.getStyleClass().add(switch (nameData.type())
				{
					case SESSION -> ICON_STYLE_CLASS_SESSION;
					case POST -> ICON_STYLE_CLASS_POST;
					case IMAGE -> ICON_STYLE_CLASS_IMAGE;
					case ZIP -> ICON_STYLE_CLASS_ZIP;
					case OTHER_FILE -> ICON_STYLE_CLASS_OTHER_FILE;
				});
				
				setGraphic(icon);
				setText(nameData.name());
			}
			
			if (extraStyleClasses != null)
				getStyleClass().removeAll(extraStyleClasses);
			
			extraStyleClasses = nameData == null ? null : nameData.styleClasses();
			if (extraStyleClasses != null)
				getStyleClass().addAll(extraStyleClasses);
		}
	}
	
	private static class StatusCell extends TreeTableCell<ItemInfo, ColumnStatusData>
	{
		private static final String CELL_STYLE_CLASS = "download-status-cell";
		
		private static final String ICON_STYLE_CLASS_IN_PROGRESS = "download-in-progress-icon";
		private static final String ICON_STYLE_CLASS_COMPLETE = "download-complete-icon";
		private static final String ICON_STYLE_CLASS_ERROR = "download-error-icon";
		
		private final FontIcon icon;
		
		private final StackPane progressStackPane;
		private final ProgressBar progressBar;
		private final Label progressLabel;
		private final Tooltip tooltip;
		
		public StatusCell()
		{
			this.getStyleClass().add(CELL_STYLE_CLASS);
			this.icon = new FontIcon();
			this.progressBar = new ProgressBar();
			this.progressBar.setMaxWidth(Double.MAX_VALUE);
			this.progressLabel = new Label("lol");
			this.progressStackPane = new StackPane(progressBar, progressLabel);
			this.tooltip = new Tooltip();
			this.tooltip.setFont(Font.getDefault()); // Explicitly set the font to default or it will inherit the FontIcon's font
		}
		
		@Override
		protected void updateItem(ColumnStatusData statusData, boolean empty)
		{
			super.updateItem(statusData, empty);
			Tooltip.uninstall(icon, tooltip);

			if (empty || statusData == null)
			{
				setGraphic(null);
				setText(null);
			}
			else
			{
				switch (statusData.status)
				{
					case IN_PROGRESS ->
					{
						if (statusData.progress() == null)
						{
							setIcon(ICON_STYLE_CLASS_IN_PROGRESS);
						}
						else
						{
							progressBar.setProgress(statusData.progress.progress);
							String text;
							if (statusData.progress.progress == ProgressBar.INDETERMINATE_PROGRESS)
							{
								if (statusData.progress.nbDone.isPresent())
								{
									// 10Mo
									text = formatOctetSize(statusData.progress.nbDone.getAsLong());
								}
								else
								{
									text = "";
								}
							}
							else
							{
								if (statusData.progress.nbDone.isPresent())
								{
									if (statusData.progress.nbTotal.isPresent())
									{
										// 10Mo / 100Mo (10%)
										text = formatOctetSize(statusData.progress.nbDone.getAsLong()) + " / "
										        + formatOctetSize(statusData.progress.nbTotal.getAsLong()) + " ("
										        + formatPercent(statusData.progress.progress) + ")";
									}
									else
									{
										// 10Mo (10%)
										text = formatOctetSize(statusData.progress.nbDone.getAsLong()) + " ("
										        + formatPercent(statusData.progress.progress) + ")";
									}
								}
								else
								{
									// 90%
									text = formatPercent(statusData.progress.progress);
								}
							}
							
							this.progressLabel.setText(text);
							setGraphic(progressStackPane);
						}
					}
					case COMPLETE ->
					{
						setIcon(ICON_STYLE_CLASS_COMPLETE);
					}
					case ERROR ->
					{
						setIcon(ICON_STYLE_CLASS_ERROR);
						icon.setOnMouseClicked(event ->
						{
							new ExceptionDialog(statusData.error(), "Error").show();
							event.consume();
						});

						tooltip.setText(statusData.error().getLocalizedMessage());
						Tooltip.install(icon, tooltip);
					}
				}
			}
		}
		
		private void setIcon(String styleClass)
		{
			icon.getStyleClass()
			    .removeAll(ICON_STYLE_CLASS_IN_PROGRESS, ICON_STYLE_CLASS_COMPLETE, ICON_STYLE_CLASS_ERROR);
			icon.getStyleClass().add(styleClass);
			icon.setOnMouseClicked(null);
			setGraphic(icon);
		}
		
		private static final String[] BYTE_UNITS = {"o", "Ko", "Mo", "Go", "To", "Po"};
		private static final BigDecimal _1024 = BigDecimal.valueOf(1024);
		
		private static String formatOctetSize(long size)
		{
			BigDecimal bdSize = java.math.BigDecimal.valueOf(size);
			
			int nbShift = 0;
			while (bdSize.precision() - bdSize.scale() > 3 && nbShift < BYTE_UNITS.length)
			{
				bdSize = bdSize.divide(_1024);
				nbShift++;
			}
			
			int nbDigitToRemove = Math.min(bdSize.precision() - 3, bdSize.scale());
			bdSize = bdSize.setScale(bdSize.scale() - nbDigitToRemove, RoundingMode.HALF_UP);
			
			return bdSize.toPlainString() + BYTE_UNITS[nbShift];
		}
		
		private static String formatPercent(double ratio)
		{
			BigDecimal percent = BigDecimal.valueOf(ratio).scaleByPowerOfTen(2);
			
			int nbDigitToRemove = Math.min(percent.precision() - 3, percent.scale());
			percent = percent.setScale(percent.scale() - nbDigitToRemove, RoundingMode.HALF_UP);
			
			return percent.toPlainString()+"%";
		}
	}
	
	private static class DateCell extends TreeTableCell<ItemInfo, ZonedDateTime>
	{
		private static final String CELL_STYLE_CLASS = "download-date-cell";
		
		private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy - HH:mm:ss Z");
		
		public DateCell()
		{
			this.getStyleClass().add(CELL_STYLE_CLASS);
		}
		
		protected void updateItem(ZonedDateTime date, boolean empty)
		{
			super.updateItem(date, empty);
			
			if (empty || date == null)
			{
				setGraphic(null);
				setText(null);
			}
			else
			{
				setText(FORMAT.format(date));
			}
		}
	}
	
	private static class ConstantObservableObject<T> implements ObservableObjectValue<T>
	{
		// @formatter:off
		private final T value;
		
		public ConstantObservableObject(T value) {this.value = value;}
		
		@Override public T getValue() {return value;}
		@Override public T get() {return value;}
		
		@Override public void addListener(ChangeListener<? super T> listener) {}
		@Override public void removeListener(ChangeListener<? super T> listener) {}
		@Override public void addListener(InvalidationListener listener) {}
		@Override public void removeListener(InvalidationListener listener) {}
		// @formatter:on
	}
}
