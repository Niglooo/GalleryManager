package nigloo.gallerymanager.ui;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.ref.SoftReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import nigloo.gallerymanager.AsyncPools;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Image;
import nigloo.gallerymanager.model.Tag;
import nigloo.gallerymanager.script.ScriptAPI;
import nigloo.gallerymanager.ui.AutoCompleteTextField.AutoCompletionBehavior;
import nigloo.tool.PrintString;
import nigloo.tool.StopWatch;
import nigloo.tool.Utils;
import nigloo.tool.gson.DateTimeAdapter;
import nigloo.tool.gson.InjectionInstanceCreator;
import nigloo.tool.gson.PathTypeAdapter;
import nigloo.tool.gson.RecordsTypeAdapterFactory;
import nigloo.tool.injection.Injector;
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
	
	private static javafx.scene.image.Image THUMBNAIL_LOADING_PLACEHOLDER;
	private static javafx.scene.image.Image THUMBNAIL_CANNOT_LOAD_PLACEHOLDER;
	
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
	
	private static Path galleryFile;
	
	private Gallery gallery;
	
	public UIController()
	{
	}
	
	public static void main(String[] args)
	{
		if (args.length < 1)
			throw new RuntimeException("missing gallery file");
		
		galleryFile = Paths.get(args[0]).toAbsolutePath();
		Injector.ENABLE();
		
		launch(args);
	}
	
	@Override
	public void start(Stage primaryStage) throws Exception
	{
		THUMBNAIL_LOADING_PLACEHOLDER = new javafx.scene.image.Image(GalleryImageView.class.getModule()
		                                                                                   .getResourceAsStream("resources/images/loading.gif"));
		THUMBNAIL_CANNOT_LOAD_PLACEHOLDER = new javafx.scene.image.Image(GalleryImageView.class.getModule()
		                                                                                       .getResourceAsStream("resources/images/image_deleted.png"));
		
		primaryStage.addEventHandler(WindowEvent.WINDOW_CLOSE_REQUEST, EventHandler -> Platform.exit());
		
		SingletonInjectionContext singletonCtx = new SingletonInjectionContext();
		Injector.addContext(singletonCtx);
		singletonCtx.setSingletonInstance(UIController.class, this);
		singletonCtx.setSingletonInstance(Gallery.class, new Gallery());
		
		openGallery();
		
//		gallery.compactIds();
		
		loadFXML(this, primaryStage, "ui.fxml");
		
		primaryStage.getScene().getStylesheets().add(STYLESHEET_DEFAULT);
		
		tagFilterField.setAutoCompletionBehavior(getMultiTagsAutocompleteBehavior());
		tagFilterField.setOnAction(e -> requestRefreshThumbnails());
		
		TreeItem<FileSystemElement> root = new TreeItem<FileSystemElement>(new FileSystemElement(gallery.getRootFolder()));
		root.setExpanded(true);
		fileSystemView.setRoot(root);
		
		fileSystemTreeManager = new FileSystemTreeManager(fileSystemView);
		fileSystemTreeManager.refresh(List.of(root.getValue().getPath()), false);
		
		thumbnailsView.setContextMenu(new ThumbnailsContextMenu(thumbnailsView));
		
		thumbnailUpdater = new ThumbnailUpdaterThread(500);
		thumbnailUpdater.start();
		
		afterGalleryLoadScript.textProperty().addListener((obs, oldValue, newValue) -> updateAfterGalleryLoadScriptModified());
		afterGalleryLoadScript.setText(gallery.getScripts().getAfterGalleryLoad());
		
		runAfterGalleryLoadScript();
		
		primaryStage.show();
	}
	
	@Override
	public void stop() throws Exception
	{
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
		private volatile boolean isUpdating = false;
		
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
					
					if (updateRequested && !isUpdating)
					{
						long waitFor = lastUpdate + UPDATE_INTERVAL - System.currentTimeMillis();
						
						if (waitFor <= 0)
						{
							isUpdating = true;
							
							CompletableFuture.supplyAsync(UIController.this::getThumnailImages, AsyncPools.FX_APPLICATION)
							                 .thenCompose(UIController.this::cancelIfNoChange)
							                 .thenCompose(fileSystemTreeManager::refreshAndGetInOrder)
							                 .thenAcceptAsync(UIController.this::updateThumbnailImages, AsyncPools.FX_APPLICATION)
							                 .handle((v, e) ->
							                 {
								                 lastUpdate = System.currentTimeMillis();
								                 updateRequested = false;
								                 isUpdating = false;
								                 return null;
							                 });
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
	
	
	private Collection<Image> getThumnailImages()
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
		
		List<Image> images = gallery.getAllImages();
		
		LOGGER.debug(UPDATE_THUMBNAILS, "gallery.getAllImages() ({}) : {}ms", images.size(), timer.split());
		
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
		                                         .map(GalleryImageView.class::cast)
		                                         .map(GalleryImageView::getGalleryImage)
		                                         .collect(Collectors.toCollection(HashSet::new));
		
		if (new HashSet<>(images).equals(thumnails))
			return CompletableFuture.failedFuture(new CancellationException());
		else
			return CompletableFuture.completedFuture(images);
	}
	
	private void updateThumbnailImages(List<Image> sortedImages)
	{
		assert Platform.isFxApplicationThread();
		
		StopWatch timer = new StopWatch();
		timer.start();
		
		thumbnailsView.getTiles().setAll(sortedImages.stream().map(UIController.this::getImageView).toList());
		
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
	
	private static final Function<Image, javafx.scene.image.Image> LOAD_THUMBNAIL_ASYNC = image -> image.getThumbnail(true);
	
	private final Map<Image, SoftReference<GalleryImageView>> thumbnailImageViewCache = new WeakHashMap<>();
	
	private GalleryImageView getImageView(Image image)
	{
		SoftReference<GalleryImageView> ref = thumbnailImageViewCache.get(image);
		GalleryImageView imageView = ref == null ? null : ref.get();
		
		if (imageView == null)
		{
			imageView = new GalleryImageView(image,
			                                 LOAD_THUMBNAIL_ASYNC,
			                                 THUMBNAIL_LOADING_PLACEHOLDER,
			                                 THUMBNAIL_CANNOT_LOAD_PLACEHOLDER);
			
			imageView.fitWidthProperty().bind(thumbnailsView.tileWidthProperty());
			imageView.fitHeightProperty().bind(thumbnailsView.tileHeightProperty());
			imageView.setPreserveRatio(true);
			
			Tooltip tooltip = new Tooltip(image.getPath().toString());
			Tooltip.install(imageView, tooltip);
			
			GalleryImageView finalImageView = imageView;
			imageView.addEventHandler(MouseEvent.MOUSE_PRESSED, event ->
			{
				if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2)
				{
					ObservableList<Node> tiles = thumbnailsView.getTiles();
					showSlideShowFromThumbnails(tiles.stream()
					                                 .map(GalleryImageView.class::cast)
					                                 .map(GalleryImageView::getGalleryImage)
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
				if (((GalleryImageView) imageView).getGalleryImage() == lastImageSeen)
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
		});
		slideShow.show();
	}
	
	public List<String> autocompleteTags(String tagSearch)
	{
		tagSearch = Tag.normalize(tagSearch);
		
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
	
	public final AutoCompletionBehavior getMultiTagsAutocompleteBehavior()
	{
		return new AutoCompletionBehavior()
		{
			@Override
			public String getSearchText(AutoCompleteTextField field)
			{
				int caret = field.getCaretPosition();
				String text = field.getText();
				
				if (caret < text.length() && Tag.isCharacterAllowed(text.charAt(caret)))
					return "";
				
				int idxBeginTag = findBeginTag(field);
				if (idxBeginTag == caret)
					return "";
				
				return text.substring(idxBeginTag, caret);
			};
			
			@Override
			public Collection<String> getSuggestions(AutoCompleteTextField field, String searchText)
			{
				return autocompleteTags(searchText);
			}
			
			@Override
			public void onSuggestionSelected(AutoCompleteTextField field, String suggestion)
			{
				int caret = field.getCaretPosition();
				int idxBeginTag = findBeginTag(field);
				String text = field.getText();
				
				String newText = text.substring(0, idxBeginTag) + suggestion + text.substring(caret);
				field.setText(newText);
				field.positionCaret(idxBeginTag + suggestion.length());
			};
			
			private int findBeginTag(AutoCompleteTextField field)
			{
				int caret = field.getCaretPosition();
				if (caret == 0)
					return 0;
				
				String text = field.getText();
				
				int idxBeginTag = caret;
				while (idxBeginTag > 0 && Tag.isCharacterAllowed(text.charAt(idxBeginTag - 1)))
					idxBeginTag--;
				
				return idxBeginTag;
			}
		};
	}
	
	private Predicate<Image> getTagFilter()
	{
		List<String> nomalizedTags = Arrays.stream(tagFilterField.getText().split(" "))
		                                   .filter(t -> !t.isBlank())
		                                   .map(Tag::normalize)
		                                   .distinct()
		                                   .toList();
		
		if (nomalizedTags.isEmpty())
			return image -> true;
		else
			return image -> image.getImplicitTags().containsAll(nomalizedTags);
	}
	
	private void openGallery() throws IOException
	{
		LOGGER.info("Opening gallery {}", galleryFile);
		try (Reader reader = Files.newBufferedReader(galleryFile, StandardCharsets.UTF_8))
		{
			gallery = gson().fromJson(reader, Gallery.class);
		}
		gallery.setRootFolder(galleryFile.getParent());
		gallery.finishConstruct();
	}
	
	@FXML
	public void saveGallery() throws IOException
	{
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
			                        .registerTypeAdapter(ZonedDateTime.class, new DateTimeAdapter())
			                        .registerTypeAdapter(Gallery.class, new InjectionInstanceCreator())
			                        .registerTypeAdapterFactory(new RecordsTypeAdapterFactory())
			                        .disableHtmlEscaping()
			                        .setPrettyPrinting()
			                        .create();
		}
		
		return gson;
	}
	
	public void refreshFileSystem(Collection<Path> paths, boolean deep)
	{
		fileSystemTreeManager.refresh(paths, deep);
	}
	
	public void synchronizeFileSystem(Collection<Path> paths, boolean deep)
	{
		fileSystemTreeManager.synchronizeFileSystem(paths, deep);
	}
	
	public void delete(Collection<Path> paths, boolean deleteOnDisk)
	{
		fileSystemTreeManager.delete(paths, deleteOnDisk);
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
			fxmlLoader.load(controller.getClass().getModule().getResourceAsStream("resources/fxml/" + filename));
		}
		catch (IOException e)
		{
			throw new Error("Error while loading FXML for " + controller.getClass().getSimpleName()
			        + " (resources/fxml/" + filename + ")", e);
		}
	}
	
	
	
	// Scripts edition (TODO move out of UIController)
	
	@FXML
	private Label afterGalleryLoadScriptTitle;
	@FXML
	private TextArea afterGalleryLoadScript;
	@FXML
	private TextArea afterGalleryLoadScriptOutput;
	
	@FXML
	protected void saveAfterGalleryLoadScript()
	{
		System.out.println("saveAfterGalleryLoadScript");
		gallery.getScripts().setAfterGalleryLoad(afterGalleryLoadScript.getText());
		updateAfterGalleryLoadScriptModified();
	}
	
	@FXML
	protected void reloadAfterGalleryLoadScript()
	{
		System.out.println("cancelAfterGalleryLoadScript");
		afterGalleryLoadScript.setText(gallery.getScripts().getAfterGalleryLoad());
		updateAfterGalleryLoadScriptModified();
	}
	
	@FXML
	protected void runAfterGalleryLoadScript()
	{
		System.out.println("runAfterGalleryLoadScript");
		
		String script = afterGalleryLoadScript.getText();
		if (Utils.isBlank(script))
			return;
		
		PrintString output = new PrintString();
		
		try {
			ScriptEngine engine = new ScriptEngineManager().getEngineByExtension("js");
			System.out.println("getEngineName: " + engine.getFactory().getEngineName());
			System.out.println("getEngineVersion: " + engine.getFactory().getEngineVersion());
			System.out.println("getLanguageName: " + engine.getFactory().getLanguageName());
			System.out.println("getLanguageVersion: " + engine.getFactory().getLanguageVersion());
			
			engine.getContext().setWriter(output);
			engine.getContext().setErrorWriter(output);
			engine.getContext().setAttribute("polyglot.js.allowAllAccess", true, ScriptContext.ENGINE_SCOPE);
			
			engine.getContext().setAttribute("api", new ScriptAPI(output), ScriptContext.ENGINE_SCOPE);
			
			System.out.println(script);
			//afterGalleryLoadScriptOutput.setText("Executing script...");
			long begin = System.currentTimeMillis();
			engine.eval(script);
			long end = System.currentTimeMillis();
			output.println("Finished in "+(end-begin)+"ms");
		}
		catch (Exception e) {
			e.printStackTrace(output);
		}
		
		afterGalleryLoadScriptOutput.setText(output.toString());
	}
	
	private static final String HAS_CHANGED_MARKER = "*";
	
	private void updateAfterGalleryLoadScriptModified()
	{
		String scriptTitle = afterGalleryLoadScriptTitle.getText();
		boolean modified = !Objects.equals(afterGalleryLoadScript.getText(), gallery.getScripts().getAfterGalleryLoad());
		boolean markerPresent = scriptTitle.endsWith(HAS_CHANGED_MARKER);
		
		if (modified && !markerPresent)
			afterGalleryLoadScriptTitle.setText(scriptTitle+HAS_CHANGED_MARKER);
		else if (!modified && markerPresent)
			afterGalleryLoadScriptTitle.setText(scriptTitle.substring(0, scriptTitle.length()-HAS_CHANGED_MARKER.length()));
	}
}
