package nigloo.gallerymanager.ui;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
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
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import nigloo.gallerymanager.autodownloader.BaseDownloader;
import nigloo.gallerymanager.model.Artist;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Image;
import nigloo.gallerymanager.model.Tag;
import nigloo.gallerymanager.ui.AutoCompleteTextField.AutoCompletionBehavior;
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
	static public final String STYLESHEET_DEFAULT = UIController.class.getModule()
	                                                                  .getClassLoader()
	                                                                  .getResource("resources/styles/default.css")
	                                                                  .toExternalForm();
	
	private static javafx.scene.image.Image THUMBNAIL_PLACEHOLDER;
	
	@FXML
	private TreeView<FileSystemElement> fileSystemView;
	private FileSystemTreeManager fileSystemTreeManager;
	@FXML
	private AutoCompleteTextField tagFilterField;
	@FXML
	private Pane tagListView;
	@FXML
	private ThumbnailsView thumbnailsView;
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
		
		launch(args);
	}
	
	@Override
	public void start(Stage primaryStage) throws Exception
	{
		THUMBNAIL_PLACEHOLDER = new javafx.scene.image.Image(ThumbnailsView.class.getModule()
		                                                                         .getResourceAsStream("resources/images/loading.gif"));
		
		primaryStage.addEventHandler(WindowEvent.WINDOW_CLOSE_REQUEST, EventHandler -> Platform.exit());
		
		SingletonInjectionContext singletonCtx = new SingletonInjectionContext();
		Injector.addContext(singletonCtx);
		singletonCtx.setSingletonInstance(UIController.class, this);
		singletonCtx.setSingletonInstance(Gallery.class, new Gallery());
		
		openGallery();
		
		// gallery.removeImagesNotHandledByAutoDowloader();
		// gallery.compactIds();
		
		for (Artist artist : gallery.getArtists())
		{
			for (BaseDownloader autoDownloader : artist.getAutodownloaders())
			{
				Properties config = new Properties();
				config.load(new FileInputStream("config.properties"));
				autoDownloader.download(config, false);
				for (Image image : gallery.getImages())
					if (autoDownloader.isHandling(image))
						image.addTag(artist.getTag());
			}
		}
		saveGallery();
		
		loadFXML(this, primaryStage, "ui.fxml");
		
		primaryStage.getScene().getStylesheets().add(STYLESHEET_DEFAULT);
		
		tagFilterField.setAutoCompletionBehavior(getMultiTagsAutocompleteBehavior());
		tagFilterField.setOnAction(e -> requestRefreshThumbnails());
		
		TreeItem<FileSystemElement> root = new TreeItem<FileSystemElement>(new FileSystemElement(gallery.getRootFolder()));
		root.setExpanded(true);
		fileSystemView.setRoot(root);
		
		fileSystemTreeManager = new FileSystemTreeManager(fileSystemView);
		fileSystemTreeManager.refresh(List.of(root.getValue().getPath()), false);
		
		thumbnailUpdater = new ThumbnailUpdaterThread(500);
		thumbnailUpdater.start();
		
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
					checkThreadState();
					
					if (updateRequested && !isUpdating)
					{
						long waitFor = lastUpdate + UPDATE_INTERVAL - System.currentTimeMillis();
						
						if (waitFor <= 0)
						{
							isUpdating = true;
							
							CompletableFuture.supplyAsync(UIController.this::getThumnailImages, Platform::runLater)
							                 .thenCompose(fileSystemTreeManager::asyncRefreshAndGetInOrder)
							                 .thenAcceptAsync(UIController.this::updateThumbnailImages, Platform::runLater)
							                 .thenRun(() ->
							                 {
								                 lastUpdate = System.currentTimeMillis();
								                 updateRequested = false;
								                 isUpdating = false;
							                 });
						}
						else
						{
							uninterruptedSleep(waitFor);
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
		long start = System.currentTimeMillis();
		long end;
		
		Collection<Path> fsSelection = fileSystemTreeManager.getSelectionWithoutChildren();
		
		end = System.currentTimeMillis();
		LOGGER.debug("fileSystemTreeManager.getSelectionWithoutChildren() : "+(end-start)+"ms");
		start = end;
		
		if (tagFilterField.getText().isBlank() && fsSelection.isEmpty())
			return List.of();
		
		Predicate<Image> tagFilter = getTagFilter();
		
		List<Image> images = gallery.getAllImages();
		
		end = System.currentTimeMillis();
		LOGGER.debug("gallery.getAllImages() (" + images.size() + ") : " + (end - start) + "ms");
		start = end;
		
		if (!fsSelection.isEmpty())
		{
			images = images.stream()
			               .filter(image -> fsSelection.stream()
			                                           .anyMatch(selectedPath -> image.getAbsolutePath()
			                                                                          .startsWith(selectedPath)))
			               .toList();
			
			end = System.currentTimeMillis();
			LOGGER.debug("Keep only selection (" + images.size() + ") : " + (end - start) + "ms");
			start = end;
		}
		
		images = images.stream().filter(tagFilter).toList();
		
		end = System.currentTimeMillis();
		LOGGER.debug("Keep only with tags (" + images.size() + ") : " + (end - start) + "ms");
		start = end;

		return images;
	}
	
	private void updateThumbnailImages(List<Image> sortedImages)
	{
		assert Platform.isFxApplicationThread();
		
		long start = System.currentTimeMillis();
		long end;
		
		HashSet<Image> oldVisibleImages = thumbnailsView.getTiles()
		                                                .stream()
		                                                .map(GalleryImageView.class::cast)
		                                                .filter(GalleryImageView::isVisible)
		                                                .map(GalleryImageView::getGalleryImage)
		                                                .collect(Collectors.toCollection(HashSet::new));
		oldVisibleImages.removeAll(sortedImages);
		oldVisibleImages.forEach(Image::cancelLoadingThumbnail);
		
		end = System.currentTimeMillis();
		LOGGER.debug("Cancel old images loading (" + oldVisibleImages.size() + ")  : "
		        + (end - start) + "ms");
		start = end;
		
		thumbnailsView.getTiles()
		              .setAll(sortedImages.stream()
		                                  .map(UIController.this::getImageView)
		                                  .toList());
		
		end = System.currentTimeMillis();
		LOGGER.debug("thumbnailsView.getTiles().setAll(...) (" + sortedImages.size()
		        + ") : " + (end - start) + "ms");
		start = end;
		
		tagListView.getChildren().clear();
		sortedImages.stream()
		            .flatMap(image -> image.getTags().stream())
		            .collect(Collectors.groupingBy(tag -> tag, Collectors.counting()))
		            .entrySet()
		            .stream()
		            .sorted(Comparator.comparing(Entry::getValue))
		            .forEachOrdered(entry ->
		            {
			            Tag tag = entry.getKey();
			            String tagName = tag.getName();
			            Color tagColor = tag.getColor();
			            long count = entry.getValue();
			            
			            Hyperlink tagText = new Hyperlink(tagName);
			            tagText.getStyleClass().add("tag");
			            if (tagColor != null)
				            tagText.setStyle("-fx-text-fill: " + FXUtils.toRGBA(tagColor)
				                    + ";");
			            tagText.setOnAction(event -> tagFilterField.setText(tagName));
			            
			            Text tagCountText = new Text(String.valueOf(count));
			            tagCountText.getStyleClass().add("tag-count");
			            
			            TextFlow tagEntry = new TextFlow(tagText,
			                                             new Text(" "),
			                                             tagCountText);
			            tagEntry.getStyleClass().add("tag-entry");
			            
			            tagListView.getChildren().add(tagEntry);
		            });
		            
		end = System.currentTimeMillis();
		LOGGER.debug("Update tagListView : " + (end - start) + "ms");
		start = end;
	}
	
	private static final Function<Image, javafx.scene.image.Image> LOAD_THUMBNAIL_ASYNC = image -> image.getThumbnail(true);
	
	private Node getImageView(Image image)
	{
		// TODO we need some cache....
		GalleryImageView imageView = new GalleryImageView(image, LOAD_THUMBNAIL_ASYNC, THUMBNAIL_PLACEHOLDER);
		
		// Keep imageView instance in thumbnailsView to preserve
		// selection
		int index = thumbnailsView.getTiles().indexOf(imageView);
		if (index != -1)
			return thumbnailsView.getTiles().get(index);
		
		imageView.fitWidthProperty().bind(thumbnailsView.tileWidthProperty());
		imageView.fitHeightProperty().bind(thumbnailsView.tileHeightProperty());
		imageView.setPreserveRatio(true);
		
		Tooltip tooltip = new Tooltip(image.getPath().toString());
		Tooltip.install(imageView, tooltip);
		
		imageView.visibleProperty().addListener((obs, oldValue, newValue) -> imageView.setDisplayed(newValue));
		
		return imageView;
	}
	
	public List<String> autocompleteTags(String tagSearch)
	{
		tagSearch = tagSearch.toLowerCase(Locale.ROOT);
		
		List<String> matchingTags = new ArrayList<>();
		Map<String, Integer> matchingTagPos = new HashMap<>();
		
		for (Tag tag : gallery.getTags())
		{
			String tagName = tag.getName();
			String lowTagValue = tagName.toLowerCase(Locale.ROOT);
			
			int pos = lowTagValue.indexOf(tagSearch);
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
			return image -> image.getImplicitNormalizedTags().containsAll(nomalizedTags);
	}
	
	private void openGallery() throws IOException
	{
		try (Reader reader = Files.newBufferedReader(galleryFile, StandardCharsets.UTF_8))
		{
			gallery = gson().fromJson(reader, Gallery.class);
		}
		gallery.setRootFolder(galleryFile.getParent());
		gallery.finishConstruct();
	}
	
	private void saveGallery() throws IOException
	{
		String datetime = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss")
		                                   .format(Instant.now().atZone(ZoneId.systemDefault()));
		
		Path tmpFile = galleryFile.resolveSibling("gallery_" + datetime + ".json");
		
		try (Writer writer = Files.newBufferedWriter(tmpFile, StandardCharsets.UTF_8))
		{
			gson().toJson(gallery, writer);
		}
		
		Files.move(tmpFile, galleryFile, StandardCopyOption.REPLACE_EXISTING);
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
}
