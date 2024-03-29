package nigloo.gallerymanager.ui;

import java.io.*;
import java.lang.ref.SoftReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.ChoiceFormat;
import java.text.MessageFormat;
import java.text.ParseException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javafx.scene.control.*;
import lombok.RequiredArgsConstructor;
import nigloo.gallerymanager.filter.ImageFilter;
import nigloo.gallerymanager.model.*;
import nigloo.gallerymanager.ui.util.UIUtils;
import nigloo.tool.javafx.component.dialog.ExceptionDialog;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener.Change;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.TabPane.TabClosingPolicy;
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
import nigloo.gallerymanager.model.Script.AutoExecution;
import nigloo.gallerymanager.ui.AutoCompleteTextField.AutoCompletionBehavior;
import nigloo.gallerymanager.ui.FileSystemElement.Status;
import nigloo.gallerymanager.ui.dialog.DownloadsProgressViewDialog;
import nigloo.gallerymanager.ui.util.VScrollablePane;
import nigloo.tool.StopWatch;
import nigloo.tool.gson.DateTimeAdapter;
import nigloo.tool.gson.InjectionInstanceCreator;
import nigloo.tool.gson.PathTypeAdapter;
import nigloo.tool.gson.PatternTypeAdapter;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;
import nigloo.tool.injection.annotation.Singleton;
import nigloo.tool.injection.impl.SingletonInjectionContext;
import nigloo.tool.javafx.FXUtils;
import nigloo.tool.thread.SafeThread;
import nigloo.tool.thread.ThreadStopException;

@Singleton
public class UIController extends Application
{
	private static final Logger LOGGER = LogManager.getLogger(UIController.class);
	public static final Marker UPDATE_THUMBNAILS = MarkerManager.getMarker("UPDATE_THUMBNAILS");
	
	public static final String STYLESHEET_DEFAULT = UIController.class.getModule()
	                                                                  .getClassLoader()
	                                                                  .getResource("resources/styles/default.css")
	                                                                  .toExternalForm();
	
	
	@FXML
	private TreeView<FileSystemElement> fileSystemView;
	private FileSystemTreeManager fileSystemTreeManager;
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
	
	public UIController()
	{
	}
	
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
		
//		gallery.compactIds();
		
		loadFXML(this, primaryStage, "main_window.fxml");
		
		primaryStage.getScene().getStylesheets().add(STYLESHEET_DEFAULT);

		// ---- Tab "Gallery" ----

		tagFilterField.setAutoCompletionBehavior(getMultiTagsAutocompleteBehavior(true));
		tagFilterField.setOnAction(e -> requestRefreshThumbnails());
		
		TreeItem<FileSystemElement> root = new TreeItem<>(new FileSystemElement(gallery.getRootFolder(), Status.NOT_LOADED));
		root.setExpanded(true);
		fileSystemView.setRoot(root);
		
		fileSystemTreeManager = new FileSystemTreeManager(fileSystemView);
		fileSystemTreeManager.refresh(List.of(root.getValue().getPath()), false);
		
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
								CompletableFuture.supplyAsync(UIController.this::getThumbnailImages, AsyncPools.FX_APPLICATION)
										.thenCompose(UIController.this::cancelIfNoChange)
										.thenCompose(fileSystemTreeManager::refreshAndGetInOrder)
										.thenAcceptAsync(UIController.this::updateThumbnailImages, AsyncPools.FX_APPLICATION)
										.join();
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
		HashSet<Image> thumnails = thumbnailsView.getTiles()
		                                         .stream()
		                                         .map(ThumbnailView.class::cast)
		                                         .map(ThumbnailView::getGalleryImage)
		                                         .collect(Collectors.toCollection(HashSet::new));
		
		if (new HashSet<>(images).equals(thumnails))
			return CompletableFuture.failedFuture(new CancellationException());
		else
			return CompletableFuture.completedFuture(images);
	}
	
	private void updateThumbnailImages(List<Image> sortedImages)
	{
		assert Platform.isFxApplicationThread();
		
		StopWatch timer = new StopWatch().start();
		
		List<Image> visibleImages = thumbnailsView
				.getTiles()
				.stream()
				.filter(Node::isVisible)
				.map(tv -> ((ThumbnailView) tv).getGalleryImage())
				.toList();
		
		Image imageToSrollTo = sortedImages
				.stream()
				.filter(visibleImages::contains)
				.findFirst()
				.orElse(null);
		
		thumbnailsView.getTiles().setAll(sortedImages.stream().map(UIController.this::getImageView).toList());
		
		if (imageToSrollTo != null)
			thumbnailsView.scrollTo(sortedImages.indexOf(imageToSrollTo));
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
	
	public List<String> autocompleteTags(String tagSearch)
	{
		tagSearch = Tag.normalize(tagSearch);
		if (tagSearch == null)
			tagSearch = "";
		
		List<String> matchingTags = new ArrayList<>();
		Map<String, Integer> matchingTagPos = new HashMap<>();
		
		for (Tag tag : gallery.getTags())
		{
			String tagName = tag.getName();
			
			int pos = tagName.indexOf(tagSearch);
			if (pos >= 0)
			{
				matchingTags.add(tagName);
				matchingTagPos.put(tagName, pos);
			}
		}
		
		matchingTags.sort(Comparator.comparing((String tagName) -> matchingTagPos.get(tagName))
		                            .thenComparing(String.CASE_INSENSITIVE_ORDER));
		
		return matchingTags;
	}

	public final AutoCompletionBehavior getMultiTagsAutocompleteBehavior(boolean allowMetatag)
	{
		return new MultiTagsAutocompleteBehavior(allowMetatag);
	}

	@RequiredArgsConstructor
	private class MultiTagsAutocompleteBehavior implements AutoCompletionBehavior
	{
		private static final String PATH_PREFIX;
		private static final Pattern PATH_PATTERN;
		static
		{
			PATH_PREFIX = ImageFilter.META_TAG_TYPE_PATH + ImageFilter.META_TAG_SEPARATOR;
			String path = Pattern.quote(PATH_PREFIX);
			String q = Pattern.quote(String.valueOf(ImageFilter.META_TAG_QUOTE));
			PATH_PATTERN = Pattern.compile(".*\\b(" + path + "(" + q + "[^" + q + "]*" + q + "?|\\S*))");
		}

		private final boolean allowMetatag;

		@Override
		public String getSearchText(AutoCompleteTextField field)
		{
			int caret = field.getCaretPosition();
			String text = field.getText();

			if (allowMetatag)
			{
				Matcher m = PATH_PATTERN.matcher(text.substring(0, caret));
				if (m.matches())
					return m.group(1);
			}

			if (caret < text.length() && Tag.isCharacterAllowed(text.charAt(caret)))
				return "";

			int idxBeginTag = findBeginSearchText(field);
			if (idxBeginTag == caret)
				return "";

			return text.substring(idxBeginTag, caret);
		};

		@Override
		public Collection<String> getSuggestions(AutoCompleteTextField field, String searchText)
		{
			if (allowMetatag)
			{
				if (searchText.startsWith(PATH_PREFIX))
					return List.of(searchText);

				if (PATH_PREFIX.startsWith(searchText))
				{
					ArrayList<String> suggestions = new ArrayList<>();
					suggestions.add(PATH_PREFIX);
					suggestions.addAll(autocompleteTags(searchText));
					return suggestions;
				}
			}

			return autocompleteTags(searchText);
		}

		@Override
		public void onSuggestionSelected(AutoCompleteTextField field, String suggestion)
		{
			int caret = field.getCaretPosition();
			int idxBeginTag = findBeginSearchText(field);
			String text = field.getText();

			String newText = text.substring(0, idxBeginTag) + suggestion + text.substring(caret);
			field.setText(newText);
			field.positionCaret(idxBeginTag + suggestion.length());
		};

		private int findBeginSearchText(AutoCompleteTextField field)
		{
			int caret = field.getCaretPosition();
			if (caret == 0)
				return 0;

			String text = field.getText();

			if (allowMetatag)
			{
				Matcher m = PATH_PATTERN.matcher(text.substring(0, caret));
				if (m.matches())
					return caret - m.group(1).length();
			}

			int idxBeginTag = caret;
			while (idxBeginTag > 0 && Tag.isCharacterAllowed(text.charAt(idxBeginTag - 1)))
				idxBeginTag--;

			return idxBeginTag;
		}
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
			gallery = gson().fromJson(reader, Gallery.class);
		}
		gallery.postConstruct(galleryFile.getParent());
	}
	
	@FXML
	public void saveGallery() throws IOException
	{
		if (!gallery.isValid()) {
			LOGGER.error("Cannot save gallery because it's invalid", gallery.getValidationError());
			return;
		}
		
		LOGGER.info("Saving gallery {}", galleryFile);
		String datetime = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss")
		                                   .format(Instant.now().atZone(ZoneId.systemDefault()));
		
		Path tmpFile = galleryFile.resolveSibling("gallery_" + datetime + ".json");
		
		try (Writer writer = Files.newBufferedWriter(tmpFile, StandardCharsets.UTF_8))
		{
			gson().toJson(gallery, writer);
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
	
	private Gson gson = null;
	
	private Gson gson()
	{
		if (gson == null)
		{
			gson = new GsonBuilder().registerTypeHierarchyAdapter(Path.class, new PathTypeAdapter())
			                        .registerTypeAdapter(Pattern.class, new PatternTypeAdapter())
			                        .registerTypeAdapter(ZonedDateTime.class, new DateTimeAdapter())
			                        .registerTypeAdapter(Gallery.class, new InjectionInstanceCreator())
			                        .disableHtmlEscaping()
			                        .setPrettyPrinting()
			                        .create();
		}
		
		return gson;
	}
	
	public CompletableFuture<Void> refreshFileSystem(Collection<Path> paths, boolean deep)
	{
		return fileSystemTreeManager.refresh(paths, deep);
	}
	
	public CompletableFuture<Void> synchronizeFileSystem(Collection<Path> paths, boolean deep)
	{
		return fileSystemTreeManager.synchronize(paths, deep);
	}
	
	public CompletableFuture<Void> delete(Collection<Path> paths, boolean deleteOnDisk)
	{
		return fileSystemTreeManager.delete(paths, deleteOnDisk);
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
