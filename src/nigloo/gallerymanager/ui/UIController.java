package nigloo.gallerymanager.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener.Change;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TabPane.TabClosingPolicy;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;
import javafx.stage.FileChooser.ExtensionFilter;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import nigloo.gallerymanager.AsyncPools;
import nigloo.gallerymanager.Fixes;
import nigloo.gallerymanager.autodownloader.Downloader;
import nigloo.gallerymanager.autodownloader.KemonoFanboxDownloader;
import nigloo.gallerymanager.filesystem.FileSystemElement;
import nigloo.gallerymanager.filesystem.FileSystemService;
import nigloo.gallerymanager.filter.ImageFilter;
import nigloo.gallerymanager.model.Artist;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Image;
import nigloo.gallerymanager.model.Script;
import nigloo.gallerymanager.model.Script.AutoExecution;
import nigloo.gallerymanager.model.Tag;
import nigloo.gallerymanager.ui.FileSystemTreeManager.ItemValue;
import nigloo.gallerymanager.ui.dialog.DownloadsProgressViewDialog;
import nigloo.gallerymanager.ui.util.AutoCompleteTag;
import nigloo.gallerymanager.ui.util.UIUtils;
import nigloo.gallerymanager.ui.util.VScrollablePane;
import nigloo.tool.StopWatch;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;
import nigloo.tool.injection.annotation.Singleton;
import nigloo.tool.injection.impl.SingletonInjectionContext;
import nigloo.tool.javafx.FXUtils;
import nigloo.tool.javafx.component.dialog.AlertWithIcon;
import nigloo.tool.javafx.component.dialog.ExceptionDialog;
import nigloo.tool.thread.SafeThread;
import nigloo.tool.thread.ThreadStopException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.jul.Constants;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.ref.SoftReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.ChoiceFormat;
import java.text.MessageFormat;
import java.text.ParseException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Singleton
public class UIController extends Application
{
	static {
		System.setProperty(Constants.LOGGER_ADAPTOR_PROPERTY, "nigloo.gallerymanager.log.FormatApiLoggerAdapter");
		System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
	}

	private static final Logger LOGGER = LogManager.getLogger(UIController.class);
	public static final Marker UPDATE_THUMBNAILS = MarkerManager.getMarker("UPDATE_THUMBNAILS");
	
	public static final String STYLESHEET_DEFAULT = UIController.class.getModule()
	                                                                  .getClassLoader()
	                                                                  .getResource("resources/styles/default.css")
	                                                                  .toExternalForm();
	
	
	@FXML
	private TreeView<ItemValue> fileSystemView;
	private FileSystemTreeManager fileSystemTreeManager;
	private FileSystemService fileSystemService;
	@FXML
	private AutoCompleteTextField tagFilterField;
	@FXML
	private Pane tagListView;
	@FXML
	private VScrollablePane thumbnailsView;
	private ThumbnailUpdaterThread thumbnailUpdater;

	@FXML
	private ArtistsEditor artistsEditor;

	@FXML
	private TabPane scriptEditors;
	
	@FXML
	private Pane statusBar;
	@FXML
	private Label statusBarText;
	@FXML
	private Node statusBarDownloadIndicator;
	
	private Path galleryFile;
	
	private Gallery gallery;
	
	@Inject
	private DownloadsProgressViewDialog downloadsProgressDialog;

    /*
     * /!\ IF LOMBOK ERROR /!\
     * Go in
     * File | Settings | Build, Execution, Deployment | Compiler | Annotation Processors
     * Go on Annotation profile for gallery_manager and :
     * - Check "Obtain processors from project classpath"
     * - Remove (with -) any processor FQ name
     */
	public static void main(String[] args)
	{
		Injector.ENABLE();
		launch(args);
	}
	
	@Override
	public void start(Stage primaryStage) throws Exception
	{
		List<String> args = getParameters().getRaw();
		if (args.size() >= 1)
		{
			galleryFile = Paths.get(args.get(0)).toAbsolutePath();
		}
		else
		{
			FileChooser fileChooser = new FileChooser();
			fileChooser.setTitle("Open");
			fileChooser.getExtensionFilters().add(new ExtensionFilter("Gallery file", "*.json"));
			
			File file = fileChooser.showOpenDialog(primaryStage);
			if (file == null)
			{
				Platform.exit();
				return;
			}
			
			galleryFile = file.toPath();
		}
		
		primaryStage.addEventHandler(WindowEvent.WINDOW_CLOSE_REQUEST, EventHandler -> Platform.exit());
		
		SingletonInjectionContext singletonCtx = new SingletonInjectionContext();
		Injector.addContext(singletonCtx);
		singletonCtx.setSingletonInstance(UIController.class, this);
		singletonCtx.setSingletonInstance(Gallery.class, new Gallery());
		
		Injector.init(this);
		
		openGallery();

		if (false)
		{
			Path path = Paths.get("H:\\Data\\Documents\\Favorites\\med_rev2\\ssdssd\\Images\\ZZZ Gallery\\Milkshake");
			Downloader downloader = gallery.getArtists()
										   .stream()
										   .map(Artist::getAutodownloaders)
										   .flatMap(List::stream)
										   .filter(KemonoFanboxDownloader.class::isInstance)
										   .findAny()
										   .get();
			Fixes.removeBadFiles(gallery, path, downloader);
			saveGallery();
			System.exit(42);
		}
		
//		gallery.compactIds();
		// Load with fake ressource bundle which is a copy or the real one but with all key=key
		loadFXML(this, primaryStage, "main_window.fxml");
		//TODO Recusively scan primaryStage.sccen.root, find all "resource bundle" string and register them in a map<Labeled, LabenInfo{resourceBundleKey}>
		primaryStage.getScene().getStylesheets().add(STYLESHEET_DEFAULT);

		// ---- Tab "Gallery" ----

		AutoCompleteTag.tagSearchExpression(gallery, tagFilterField);
		tagFilterField.setOnAction(e -> requestRefreshThumbnails());

		//TODO make configurable (keepEmptyFolder)
		fileSystemService = new FileSystemService(false);
		fileSystemService.refresh(List.of(gallery.getRootFolder()), false);
		
		fileSystemTreeManager = new FileSystemTreeManager(fileSystemView, fileSystemService);
		
		thumbnailsView.setContextMenu(new ThumbnailsContextMenu(thumbnailsView));
		thumbnailsView.getTiles().addListener((Change<? extends Node> c) -> updateStatusBar());
		thumbnailsView.getSelectionModel().getSelectedItems().addListener((Change<? extends Node> c) -> updateStatusBar());

		// ---- Tab "Scripts" ----
		scriptEditors.setTabClosingPolicy(TabClosingPolicy.ALL_TABS);
		for (Script script : gallery.getScripts())
		{
			scriptEditors.getTabs().add(newScriptEditorTab(script));
		}
		UIUtils.addableTabs(scriptEditors, "Add Script", () -> {
			Script script = gallery.newScript();
			script.setTitle("New script");
			return newScriptEditorTab(script);
		});
		
		downloadsProgressDialog.downloadActiveProperty().addListener((obs, oldValue, newValue) -> updateStatusBar());
		updateStatusBar();
		
		thumbnailUpdater = new ThumbnailUpdaterThread(500);
		thumbnailUpdater.start();
		
		primaryStage.show();
		
		runScripts(AutoExecution.ON_APP_START);
	}
	
	@Override
	public void stop() throws Exception
	{
		if (galleryFile == null)
			return;
		
		runScripts(AutoExecution.ON_APP_STOP);
		
		thumbnailUpdater.safeStop();
		saveGallery();
	}
	
	public void requestRefreshThumbnails()
	{
		thumbnailUpdater.requestUpdate();
	}
	
	private class ThumbnailUpdaterThread extends SafeThread
	{
		private final long UPDATE_INTERVAL;
		
		private long lastUpdate = 0;
		private volatile boolean updateRequested = false;
		
		public ThumbnailUpdaterThread(long updateInterval)
		{
			super("thumbnail-updater");
			setDaemon(true);
			UPDATE_INTERVAL = updateInterval;
		}
		
		public void requestUpdate()
		{
			updateRequested = true;
			safeResume();
		}
		
		@Override
		public void run()
		{
			try
			{
				while (true)
				{
					SafeThread.checkThreadState();

					if (updateRequested)
					{
						long waitFor = lastUpdate + UPDATE_INTERVAL - System.currentTimeMillis();
						
						if (waitFor <= 0)
						{
							try {
								updateRequested = false;

								record InfoFromUI(Collection<Path> selection, Predicate<Image> tagFilter){}
								CompletableFuture.supplyAsync(() -> new InfoFromUI(
									fileSystemTreeManager.getSelectionWithoutChildren(),
									getTagFilter()
								), AsyncPools.FX_APPLICATION).thenApplyAsync(uiInfo -> {
									List<Path> selection = uiInfo.selection.stream().map(gallery::toRelativePath).toList();
									System.out.println("selection: "+selection);
									System.out.println("tagFilter: "+uiInfo.tagFilter());
									Stream<Image> images = gallery.getImages(true).stream();
									if (!selection.isEmpty()) {
										images = images.filter(image -> selection.stream().anyMatch(selectedPath -> image.getPath().startsWith(selectedPath)));
									}
								    List<Image> list = images.filter(uiInfo.tagFilter).toList();
									System.out.println(list.size()+" images matching");
									return list;
								}, AsyncPools.DISK_IO)
								 .thenComposeAsync(fileSystemService::refresh, AsyncPools.DISK_IO)
								 .thenApply(fileSystemService::sort)
								 .thenAcceptAsync(UIController.this::updateThumbnailImages, AsyncPools.FX_APPLICATION)
								 .join();

//								CompletableFuture.supplyAsync(UIController.this::getThumbnailImages, AsyncPools.FX_APPLICATION)
//										.thenCompose(UIController.this::cancelIfNoChange)
//										.thenCompose(fileSystemTreeManager::refreshAndGetInOrder)
//										.thenAcceptAsync(UIController.this::updateThumbnailImages, AsyncPools.FX_APPLICATION)
//										.join();
							}
							catch (CancellationException ignored) {}
							catch (CompletionException e) {
								if (!(e.getCause() instanceof CancellationException))
									AsyncPools.FX_APPLICATION.execute(() -> new ExceptionDialog(e, "Error while refreshing thumbnails").show());
							}
							lastUpdate = System.currentTimeMillis();
						}
						else
						{
							SafeThread.uninterruptedSleep(waitFor);
						}
					}
					else
						safeSuspend();
				}
			}
			catch (ThreadStopException e)
			{
			}
		}
	}
	
	
	private Collection<Image> getThumbnailImages()
	{
		StopWatch timer = new StopWatch();
		timer.start();
		
		Collection<Path> fsSelection = fileSystemTreeManager.getSelectionWithoutChildren();
		
		LOGGER.debug(UPDATE_THUMBNAILS,
		             "fileSystemTreeManager.getSelectionWithoutChildren() ({}) : {}ms",
		             fsSelection.size(),
		             timer.split());
		
		if (tagFilterField.getText().isBlank() && fsSelection.isEmpty())
			return List.of();
		
		Predicate<Image> tagFilter = getTagFilter();
		
		Collection<Image> images = gallery.getImages(true);
		
		LOGGER.debug(UPDATE_THUMBNAILS, "gallery.getImages(true) ({}) : {}ms", images.size(), timer.split());
		
		if (!fsSelection.isEmpty())
		{
			images = images.stream()
			               .filter(image -> fsSelection.stream()
			                                           .anyMatch(selectedPath -> image.getAbsolutePath()
			                                                                          .startsWith(selectedPath)))
			               .toList();
			
			LOGGER.debug(UPDATE_THUMBNAILS, "Keep only selection ({}) : {}ms", images.size(), timer.split());
		}
		
		images = images.stream().filter(tagFilter).toList();
		
		LOGGER.debug(UPDATE_THUMBNAILS, "Keep only with tags ({}) : {}ms", images.size(), timer.split());
		
		return images;
	}
	
	private CompletableFuture<Collection<Image>> cancelIfNoChange(Collection<Image> images)
	{
		List<Image> thumbnails = thumbnailsView.getTiles()
		                                         .stream()
		                                         .map(ThumbnailView.class::cast)
		                                         .map(ThumbnailView::getGalleryImage)
		                                         .toList();
		
		if (images.stream().toList().equals(thumbnails))
			return CompletableFuture.failedFuture(new CancellationException());
		else
			return CompletableFuture.completedFuture(images);
	}
	
	private void updateThumbnailImages(List<Image> sortedImages)
	{
		assert Platform.isFxApplicationThread();
		
		StopWatch timer = new StopWatch().start();

		Image imageToScrollTo = thumbnailsView
				.getTiles()
				.stream()
				.filter(Node::isVisible)
				.map(tv -> ((ThumbnailView) tv).getGalleryImage())
				.filter(sortedImages::contains)
				.findFirst()
				.orElse(null);
		
		thumbnailsView.getTiles().setAll(sortedImages.stream().map(UIController.this::getImageView).toList());
		
		if (imageToScrollTo != null)
			thumbnailsView.scrollTo(sortedImages.indexOf(imageToScrollTo));
		else
			thumbnailsView.scrollTo(0);
		
		LOGGER.debug("thumbnailsView.getTiles().setAll(...) ({}) : {}ms", sortedImages.size(), timer.split());
		
		tagListView.getChildren().clear();
		sortedImages.stream()
		            .flatMap(image -> image.getTags().stream())
		            .collect(Collectors.groupingBy(tag -> tag, Collectors.counting()))
		            .entrySet()
		            .stream()
		            .sorted(Comparator.<Entry<Tag,Long>,Long>comparing(Entry::getValue, Comparator.reverseOrder()).thenComparing(e -> e.getKey().getName()))
		            .forEachOrdered(entry ->
		            {
			            Tag tag = entry.getKey();
			            String tagName = tag.getName();
			            Color tagColor = tag.getColor();
			            long count = entry.getValue();
			            
			            Hyperlink tagText = new Hyperlink(tagName);
			            tagText.getStyleClass().add("tag");
			            if (tagColor != null)
				            tagText.setStyle("-fx-text-fill: " + FXUtils.toRGBA(tagColor) + ";");
			            tagText.setOnAction(event -> tagFilterField.setText(tagName));
			            
			            Text tagCountText = new Text(String.valueOf(count));
			            tagCountText.getStyleClass().add("tag-count");
			            
			            TextFlow tagEntry = new TextFlow(tagText, new Text(" "), tagCountText);
			            tagEntry.getStyleClass().add("tag-entry");
			            
			            tagListView.getChildren().add(tagEntry);
		            });
		
		LOGGER.debug("Update tagListView : {}ms", timer.split());
	}
	
	private final Map<Image, SoftReference<ThumbnailView>> thumbnailImageViewCache = new WeakHashMap<>();
	
	private ThumbnailView getImageView(Image image)
	{
		SoftReference<ThumbnailView> ref = thumbnailImageViewCache.get(image);
		ThumbnailView imageView = ref == null ? null : ref.get();
		
		if (imageView == null)
		{
			imageView = new ThumbnailView(image);
			
			imageView.fitWidthProperty().bind(thumbnailsView.tileWidthProperty());
			imageView.fitHeightProperty().bind(thumbnailsView.tileHeightProperty());
			imageView.setPreserveRatio(true);
			
			Tooltip tooltip = new Tooltip(image.getPath().toString());
			Tooltip.install(imageView, tooltip);
			
			ThumbnailView finalImageView = imageView;
			imageView.addEventHandler(MouseEvent.MOUSE_PRESSED, event ->
			{
				if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2)
				{
					ObservableList<Node> tiles = thumbnailsView.getTiles();
					showSlideShowFromThumbnails(tiles.stream()
					                                 .map(ThumbnailView.class::cast)
					                                 .map(ThumbnailView::getGalleryImage)
					                                 .toList(),
					                            tiles.indexOf(finalImageView));
				}
			});
			
			thumbnailImageViewCache.put(image, new SoftReference<>(imageView));
		}
		
		return imageView;
	}
	
	public void showSlideShowFromThumbnails(List<Image> images, int startingIndex)
	{
		SlideShowStage slideShow = new SlideShowStage(images, startingIndex);
		slideShow.setOnHidden(event ->
		{
			Image lastImageSeen = slideShow.getCurrentImage();
			int lastImageSeenIdx = -1;
			
			int i = 0;
			for (Node imageView : thumbnailsView.getTiles())
			{
				if (((ThumbnailView) imageView).getGalleryImage() == lastImageSeen)
				{
					lastImageSeenIdx = i;
					break;
				}
				i++;
			}
			
			if (lastImageSeenIdx >= 0)
			{
				thumbnailsView.scrollTo(lastImageSeenIdx);
				thumbnailsView.getFocusModel().focus(lastImageSeenIdx);
			}
			fileSystemView.requestFocus();
		});
		slideShow.show();
	}

	private Predicate<Image> getTagFilter()
	{
		String filterExpression = tagFilterField.getText();

		if (filterExpression.isBlank())
			return image -> true;
		else {
			try {
				return ImageFilter.parse(filterExpression);
			} catch (ParseException e) {
				new ExceptionDialog(e, "Bad filter").show();
				return image -> false;
			}
		}
	}
	
	private void updateStatusBar()
	{
		int nbItems = thumbnailsView.getTiles().size();
		int nbItemsSelected = thumbnailsView.getSelectionModel().getSelectedItems().size();
		
		MessageFormat messageFormat = new MessageFormat("{0}\t{1}");
		
		messageFormat.setFormatByArgumentIndex(0,
		                                       new ChoiceFormat(new double[] { 0, 1, ChoiceFormat.nextDouble(1) },
		                                                        new String[] { "0 items", "1 item selected",
		                                                                "{0} items" }));
		messageFormat.setFormatByArgumentIndex(1,
		                                       new ChoiceFormat(new double[] { 0, 1, ChoiceFormat.nextDouble(1) },
		                                                        new String[] { "", "1 item selected",
		                                                                "{1} items selected" }));
		
		String selectedElementsText = messageFormat.format(new Object[] { nbItems, nbItemsSelected });
		statusBarText.setText(selectedElementsText);
		
		
		
		Map<Boolean, String> downloadIndicatorStyleClasses = Map.of(true, "download-active-icon", false, "download-inactive-icon");
		
		statusBarDownloadIndicator.getStyleClass().removeAll(downloadIndicatorStyleClasses.values());
		statusBarDownloadIndicator.getStyleClass()
		                          .add(downloadIndicatorStyleClasses.get(downloadsProgressDialog.downloadActiveProperty()
		                                                                                        .get()));
	}
	
	private void openGallery() throws IOException
	{
		LOGGER.info("Opening gallery {}", galleryFile);
		try (Reader reader = Files.newBufferedReader(galleryFile, StandardCharsets.UTF_8))
		{
			gallery = Gallery.load(reader, galleryFile.getParent());
		}
	}
	
	@FXML
	public void saveGallery() throws IOException
	{if(true)return;
		if (!gallery.isValid()) {
			LOGGER.error("Cannot save gallery because it's invalid", gallery.getValidationError());
			return;
		}
		
		LOGGER.info("Saving gallery {}", galleryFile);
		String datetime = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss")
		                                   .format(LocalDateTime.now());
		
		Path tmpFile = galleryFile.resolveSibling("gallery_" + datetime + ".json");
		
		try (Writer writer = Files.newBufferedWriter(tmpFile, StandardCharsets.UTF_8))
		{
			gallery.save(writer);
		}
		
		int nbAttempt = 0;
		while (true)
		{
			try {
				Files.move(tmpFile, galleryFile, StandardCopyOption.REPLACE_EXISTING);
				break;
			}
			catch (Exception e) {
				if (nbAttempt++ >= 10)
					throw e;
				
				try {
					Thread.sleep(200);
				} catch (InterruptedException e1) {
					Thread.currentThread().interrupt();
				}
			}
		}
	}

	public CompletableFuture<Void> refreshFileSystem(Collection<Path> paths, boolean deep)
	{
		return fileSystemService.refresh(paths, deep);
	}
	
	public CompletableFuture<Void> synchronizeFileSystem(Collection<Path> paths, boolean deep)
	{
		return fileSystemService.synchronize(paths, deep);
	}
	
	public CompletableFuture<Void> delete(Collection<Path> paths, boolean deleteOnDisk)
	{
		final Collection<Path> fPaths = withoutChildren(paths);

		boolean fullyLoaded = true;
		List<FileSystemElement> elements = new ArrayList<>();
		for (Path path : fPaths) {
			FileSystemElement element = fileSystemService.findElement(path);
			if (element != null) {
				elements.add(element);
			} else {
				fullyLoaded = false;
			}
		}
		List<Image> images = new ArrayList<>();
		while (!elements.isEmpty()) {
            FileSystemElement element = elements.removeLast();
			if (element.isImage()) {
				images.add(element.getImage());
			}
			if (element.isDirectory() && element.getStatusDirectory().isNotFullyLoaded()) {
				fullyLoaded = false;
			}
			elements.addAll(element.getChildren());
		}

		AlertWithIcon warningPopup = new AlertWithIcon(AlertType.WARNING);
		warningPopup.setTitle("Delete images");
		if (images.size() == 1 && fullyLoaded) {
			warningPopup.setHeaderText("Delete \"" + images.getFirst().getPath().getFileName() + "\"?");
		}
		else {
			warningPopup.setHeaderText("Delete " + (!fullyLoaded ? "at least " : "") + images.size() + " image(s)?");
		}
		warningPopup.setContentText("This action cannot be undone!");
		warningPopup.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
		warningPopup.setDefaultButton(ButtonType.NO);

		return CompletableFuture.supplyAsync(() -> {
			Optional<ButtonType> button = warningPopup.showAndWait();
			return button.isPresent() && button.get() == ButtonType.YES;
		}, AsyncPools.FX_APPLICATION).thenComposeAsync(delete -> {
			if (!delete) {
				return CompletableFuture.completedFuture(null);
			}
			return fileSystemService.delete(paths, deleteOnDisk);
		}, AsyncPools.DISK_IO);
	}

	private static Collection<Path> withoutChildren(Collection<Path> paths)
	{
		return paths.stream().filter(p -> paths.stream().noneMatch(p2 -> p != p2 && p.startsWith(p2))).toList();
	}


	public void cut(Collection<Path> paths)
	{
		fileSystemTreeManager.cut(paths);
	}
	
	public boolean canPaste(Path targetPath)
	{
		return fileSystemTreeManager.canPaste(targetPath);
	}
	
	public void paste(Path targetPath)
	{
		fileSystemTreeManager.paste(targetPath);
	}
	
	public void newDirectoryIn(Path parentDirectory, boolean editInView)
	{
		fileSystemTreeManager.newDirectoryIn(parentDirectory, editInView);
	}
	
	@FXML
	public void showDownloadsProgress(MouseEvent event)
	{
		if (event.getButton() == MouseButton.PRIMARY)
		{
			downloadsProgressDialog.show();
			downloadsProgressDialog.toFront();
		}
	}
	
	private Tab newScriptEditorTab(Script script)
	{
		ScriptEditor scriptEditor = new ScriptEditor(script);
		
		Tab tab = new Tab();
		tab.setContent(scriptEditor);
		tab.textProperty().bind(scriptEditor.scriptTitleProperty().concat(Bindings.createStringBinding(() -> scriptEditor.changedProperty().get() ? "*" : "", scriptEditor.changedProperty())));
		tab.setOnCloseRequest(e -> {
			scriptEditor.deleteScript();
			e.consume();
		});

		return tab;
	}
	
	private void runScripts(AutoExecution when)
	{
		for (Tab tab : scriptEditors.getTabs())
		{
			if (tab.getContent() instanceof ScriptEditor scriptEditor
			        && scriptEditor.getScript().getAutoExecution() == when)
			{
				//FIXME run async... (so doesn't have time to actually run before the app stop)
				scriptEditor.runScript();
			}
		}
	}
	
	public static void loadFXML(Object controller, String filename)
	{
		loadFXML(controller, controller, filename);
	}
	
	public static void loadFXML(Object controller, Object root, String filename)
	{
		try
		{
			FXMLLoader fxmlLoader = new FXMLLoader(StandardCharsets.UTF_8);
			fxmlLoader.setController(controller);
			fxmlLoader.setRoot(root);
			fxmlLoader.setClassLoader(controller.getClass().getClassLoader());
			fxmlLoader.load(getResourceAsStream(controller.getClass(), "resources/fxml/" + filename));
		}
		catch (IOException e)
		{
			throw new Error("Error while loading FXML for " + controller.getClass().getSimpleName()
			        + " (resources/fxml/" + filename + ")", e);
		}
	}

	private static InputStream getResourceAsStream(Class<?> clazz, String name) throws IOException {
		return Injector.enabled() ? clazz.getModule().getResourceAsStream(name) : clazz.getClassLoader().getResourceAsStream(name);
	}
}
