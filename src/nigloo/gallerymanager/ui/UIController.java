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
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
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

@Singleton
public class UIController extends Application
{
	private static javafx.scene.image.Image THUMBNAIL_PLACEHOLDER;
	
	@FXML
	protected TreeView<FileSystemElement> fileSystemView;
	private FileSystemTreeManager fileSystemTreeManager;
	
	@FXML
	protected ThumbnailsView thumbnailsView;
	
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
					 .flatMap(item -> getImages(item).stream())
					 .forEach(Image::cancelLoadingThumbnail);
				
				refreshThumbnails();
			}
		});
		TreeItem<FileSystemElement> root = new TreeItem<FileSystemElement>(new FileSystemElement(gallery.getRootFolder()));
		root.setExpanded(true);
		fileSystemView.setRoot(root);
		
		fileSystemTreeManager = new FileSystemTreeManager();
		fileSystemTreeManager.refresh(root, false);
		
		this.primaryStage.show();
	}
	
	public void refreshThumbnails()
	{
		List<Image> selectedImage = fileSystemView.getSelectionModel()
		                                          .getSelectedItems()
		                                          .stream()
		                                          .flatMap(item -> getImages(item).stream())
		                                          .toList();
		showThumbnails(selectedImage);
	}
	
	
	
	private List<Image> getImages(TreeItem<FileSystemElement> rootItem)
	{
		if (rootItem == null || rootItem.getValue() == null)
			return List.of();
		else if (rootItem.getValue().isImage())
			return List.of(rootItem.getValue().getImage());
		else
			return rootItem.getChildren().stream().flatMap(item -> getImages(item).stream()).toList();
	}
	
	private static final Function<Image, javafx.scene.image.Image> LOAD_THUMBNAIL_ASYNC = image -> image.getThumbnail(true);
	
	private void showThumbnails(Collection<Image> images)
	{
		assert images != null;
		
		Platform.runLater(() ->
		{
			thumbnailsView.getTiles().setAll(images.stream().map(image ->
			{
				GalleryImageView imageView = new GalleryImageView(image, LOAD_THUMBNAIL_ASYNC, THUMBNAIL_PLACEHOLDER);
				
				// Keep imageView instance in thumbnailsView to preserve selection
				int index = thumbnailsView.getTiles().indexOf(imageView);
				if (index != -1)
					return thumbnailsView.getTiles().get(index);
				
				imageView.fitWidthProperty().bind(thumbnailsView.tileWidthProperty());
				imageView.fitHeightProperty().bind(thumbnailsView.tileHeightProperty());
				imageView.setPreserveRatio(true);
				
				imageView.visibleProperty().addListener((obs, oldValue, newValue) -> imageView.setDisplayed(newValue));
				
				return imageView;
			}).toList());
		});
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
	
	public void refreshFileSystemItem(TreeItem<FileSystemElement> item)
	{
		fileSystemTreeManager.refresh(item, true);
	}
}
