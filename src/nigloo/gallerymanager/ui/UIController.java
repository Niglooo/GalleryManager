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
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import nigloo.gallerymanager.autodownloader.FanboxDownloader;
import nigloo.gallerymanager.autodownloader.ImageReference;
import nigloo.gallerymanager.model.Artist;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Image;
import nigloo.tool.gson.InjectionInstanceCreator;
import nigloo.tool.gson.PathTypeAdapter;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Singleton;
import nigloo.tool.injection.impl.SingletonInjectionContext;
import nigloo.tool.thread.SafeThread;
import nigloo.tool.thread.ThreadStopException;

@Singleton
public class UIController extends Application
{
	private static javafx.scene.image.Image THUMBNAIL_PLACEHOLDER;
	
	@FXML
	protected TreeView<FileSystemElement> fileSystemView;
	private FileSystemTreeManager fileSystemTreeManager;
	
	@FXML
	protected ThumbnailsView thumbnailsView;
	private ThumbnailUpdaterThread thumbnailUpdater;
	
	private Stage primaryStage;
	
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
		
		this.primaryStage = primaryStage;
		this.primaryStage.addEventHandler(WindowEvent.WINDOW_CLOSE_REQUEST, EventHandler -> Platform.exit());
		
		SingletonInjectionContext singletonCtx = new SingletonInjectionContext();
		Injector.addContext(singletonCtx);
		singletonCtx.setSingletonInstance(UIController.class, this);
		singletonCtx.setSingletonInstance(Gallery.class, new Gallery());
		
		openGallery();
		
		// gallery.removeImagesNotHandledByAutoDowloader();
		// gallery.compactIds();
		
		Properties config = new Properties();
		config.load(new FileInputStream("config.properties"));
		for (Artist artist : gallery.getArtists())
			for (FanboxDownloader autoDownloader : artist.getAutodownloaders())
				;// autoDownloader.download(config.getProperty("cookie"));
				
		// saveGallery();

		FXMLLoader fxmlLoader = new FXMLLoader(StandardCharsets.UTF_8);
		fxmlLoader.setController(this);
		fxmlLoader.setRoot(primaryStage);
		fxmlLoader.load(getClass().getModule().getResourceAsStream("resources/fxml/ui.fxml"));
		
		// TODO custom ordering from each folder (saved in json)
		// TODO Delete file/directory (one disk, in gallery/treeview and downloader
		// mapping)
		// TODO Move file/directory
		fileSystemView.setCellFactory(new FileSystemTreeCellFactory());
		fileSystemView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
		fileSystemView.getSelectionModel().getSelectedItems().addListener(new ListChangeListener<TreeItem<FileSystemElement>>()
		{
			@Override
			public void onChanged(Change<? extends TreeItem<FileSystemElement>> c)
			{
				while (c.next())
					c.getRemoved()
					 .stream()
					 .flatMap(item -> getImages(item))
					 .forEach(Image::cancelLoadingThumbnail);
				
				requestRefreshThumbnails();
			}
		});
		TreeItem<FileSystemElement> root = new TreeItem<FileSystemElement>(new FileSystemElement(gallery.getRootFolder()));
		root.setExpanded(true);
		fileSystemView.setRoot(root);
		
		fileSystemTreeManager = new FileSystemTreeManager(fileSystemView);
		fileSystemTreeManager.refresh(List.of(root.getValue().getPath()), false);
		
		thumbnailUpdater = new ThumbnailUpdaterThread(500);
		thumbnailUpdater.start();
		
		this.primaryStage.show();
	}
	
	@Override
	public void stop() throws Exception
	{
		thumbnailUpdater.safeStop();
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
					checkThreadState();
					
					if (updateRequested)
					{
						long waitFor = lastUpdate + UPDATE_INTERVAL - System.currentTimeMillis();
						
						if (waitFor <= 0)
						{
							doUpdate();
							lastUpdate = System.currentTimeMillis();
							updateRequested = false;
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
		
		private void doUpdate()
		{
			Platform.runLater(() ->
			{
				thumbnailsView.getTiles()
				              .setAll(fileSystemView.getSelectionModel()
				                                    .getSelectedItems()
				                                    .stream()
				                                    .flatMap(UIController::getImages)
				                                    .map(UIController.this::getImageView)
				                                    .toList());
			});
		}
	}
	
	private static Stream<Image> getImages(TreeItem<FileSystemElement> rootItem)
	{
		if (rootItem == null || rootItem.getValue() == null)
			return Stream.empty();
		else if (rootItem.getValue().isImage())
			return Stream.of(rootItem.getValue().getImage());
		else
			return rootItem.getChildren().stream().flatMap(UIController::getImages);
	}
	
	private static final Function<Image, javafx.scene.image.Image> LOAD_THUMBNAIL_ASYNC = image -> image.getThumbnail(true);
	
	private Node getImageView(Image image)
	{
		GalleryImageView imageView = new GalleryImageView(image, LOAD_THUMBNAIL_ASYNC, THUMBNAIL_PLACEHOLDER);
		
		// Keep imageView instance in thumbnailsView to preserve
		// selection
		int index = thumbnailsView.getTiles().indexOf(imageView);
		if (index != -1)
			return thumbnailsView.getTiles().get(index);
		
		imageView.fitWidthProperty().bind(thumbnailsView.tileWidthProperty());
		imageView.fitHeightProperty().bind(thumbnailsView.tileHeightProperty());
		imageView.setPreserveRatio(true);
		
		imageView.visibleProperty().addListener((obs, oldValue, newValue) -> imageView.setDisplayed(newValue));
		
		return imageView;
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
		
		Path tmpFile = galleryFile.getParent().resolve("gallery_" + datetime + ".json");
		
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
			                        .registerTypeAdapter(Gallery.class, new InjectionInstanceCreator())
			                        .registerTypeAdapter(ImageReference.class, ImageReference.typeAdapter())
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
}
