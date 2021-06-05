package nigloo.gallerymanager.ui;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

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
import nigloo.gallerymanager.autodownloader.FanboxDownloader;
import nigloo.gallerymanager.autodownloader.ImageReference;
import nigloo.gallerymanager.model.Artist;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Image;
import nigloo.tool.Utils;
import nigloo.tool.gson.InjectionInstanceCreator;
import nigloo.tool.gson.PathTypeAdapter;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.impl.SingletonInjectionContext;

public class UIController extends Application
{
	@FXML
	protected TreeView<FileSystemElement> fileSystemView;
	
	@FXML
	protected LargeVerticalTilePane thumbnailsView;
	
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
		this.primaryStage = primaryStage;
		
		SingletonInjectionContext singletonCtx = new SingletonInjectionContext();
		Injector.addContext(singletonCtx);
		singletonCtx.setSingletonInstance(Gallery.class, new Gallery());
		
		openGallery();
		
		// gallery.removeImagesNotHandledByAutoDowloader();
		gallery.compactIds();
		
		Properties config = new Properties();
		config.load(new FileInputStream("config.properties"));
		for (Artist artist : gallery.getArtists())
			for (FanboxDownloader autoDownloader : artist.getAutodownloaders())
				;// autoDownloader.download(config.getProperty("cookie"));
				
		saveGallery();

		FXMLLoader fxmlLoader = new FXMLLoader(StandardCharsets.UTF_8);
		fxmlLoader.setController(this);
		fxmlLoader.setRoot(primaryStage);
		fxmlLoader.load(getClass().getModule().getResourceAsStream("resources/fxml/ui.fxml"));
		
		fileSystemView.setCellFactory(new FileSystemTreeCellFactory(this));
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
				
				List<Image> selectedImage = c.getList().stream().flatMap(item -> getImages(item).stream()).toList();
				showThumbnails(selectedImage);
			}
		});
		TreeItem<FileSystemElement> root = new TreeItem<FileSystemElement>(new FileSystemElement(gallery.getRootFolder()));
		root.setExpanded(true);
		fileSystemView.setRoot(root);
		
		refreshFileSystemItem(root);
		
		this.primaryStage.show();
	}
	
	void refreshFileSystemItem(TreeItem<FileSystemElement> rootItem)
	{
		System.out.println("Refresh : " + rootItem.getValue().getPath());
		try
		{
			FileSystemElement rootElement = rootItem.getValue();
			Path rootPath = rootElement.getPath();
			Path absoluteRoot = gallery.toAbsolutePath(rootPath);
			
			Map<Path, TreeItem<FileSystemElement>> pathToItem = new HashMap<>();
			pathToItem.put(absoluteRoot, rootItem);
			
			rootItem.getChildren().clear();
			
			// Add files on disk
			if (Files.exists(absoluteRoot))
			{
				Files.walkFileTree(absoluteRoot, new FileVisitor<Path>()
				{
					@Override
					public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException
					{
						if (dir.equals(absoluteRoot))
							return FileVisitResult.CONTINUE;
						
						TreeItem<FileSystemElement> parentItem = pathToItem.get(dir.getParent());
						TreeItem<FileSystemElement> item = new TreeItem<>(new FileSystemElement(gallery.toRelativePath(dir)));
						
						parentItem.getChildren().add(item);
						pathToItem.put(dir, item);
						
						return FileVisitResult.CONTINUE;
					}
					
					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
					{
						Path relativePath = gallery.toRelativePath(file);
						
						Image image = gallery.findImage(relativePath);
						boolean isSyncronized;
						if (image == null)
						{
							if (!isImage(file))
								return FileVisitResult.CONTINUE;
							
							image = new Image(relativePath);
							isSyncronized = false;
						}
						else
						{
							isSyncronized = true;
						}
						
						if (file.equals(absoluteRoot))
							return FileVisitResult.CONTINUE;
						
						TreeItem<FileSystemElement> parentItem = pathToItem.get(file.getParent());
						TreeItem<FileSystemElement> item = new TreeItem<>(new FileSystemElement(image,
						                                                                        isSyncronized
						                                                                                ? FileSystemElement.Status.SYNC
						                                                                                : FileSystemElement.Status.UNSYNC));
						
						parentItem.getChildren().add(item);
						pathToItem.put(file, item);
						return FileVisitResult.CONTINUE;
					}
					
					@Override
					public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException
					{
						throw exc;
					}
					
					@Override
					public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException
					{
						if (exc != null)
							throw exc;
						
						return FileVisitResult.CONTINUE;
					}
				});
			}
			
			// Add deleted images
			for (Image image : gallery.getImages())
			{
				Path absolutePath = gallery.toAbsolutePath(image.getPath());
				if (absolutePath.startsWith(absoluteRoot) == false)
					continue;
				
				if (pathToItem.containsKey(absolutePath))
					continue;
				
				List<Path> ancestors = new ArrayList<>();
				for (Path currentPath = absolutePath.getParent() ;
				     pathToItem.containsKey(currentPath) == false ;
				     currentPath = currentPath.getParent())
				{
					ancestors.add(0, currentPath);
				}
				for (Path currentPath : ancestors)
				{
					TreeItem<FileSystemElement> parentItem = pathToItem.get(currentPath.getParent());
					TreeItem<FileSystemElement> item = new TreeItem<>(new FileSystemElement(currentPath));
					
					parentItem.getChildren().add(item);
					pathToItem.put(currentPath, item);
				}
				
				TreeItem<FileSystemElement> parentItem = pathToItem.get(absolutePath.getParent());
				if (parentItem.getValue().getStatus() == FileSystemElement.Status.SYNC)
					parentItem.getValue().setStatus(FileSystemElement.Status.UNSYNC);
				
				parentItem = ancestors.isEmpty() ? parentItem : pathToItem.get(ancestors.get(ancestors.size() - 1));
				TreeItem<FileSystemElement> item = new TreeItem<>(new FileSystemElement(image,
				                                                                        FileSystemElement.Status.DELETED));
				
				parentItem.getChildren().add(item);
			}
			
			removeEmptyDirectoriesandUpdateStatus(rootItem);
			sort(rootItem);
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
	}
	
	private void removeEmptyDirectoriesandUpdateStatus(TreeItem<FileSystemElement> rootItem)
	{
		if (rootItem.getValue().isDirectory() == false)
			return;
		
		Iterator<TreeItem<FileSystemElement>> it = rootItem.getChildren().iterator();
		FileSystemElement.Status updatedStatus = null;
		
		while (it.hasNext())
		{
			TreeItem<FileSystemElement> item = it.next();
			boolean removed = false;
			if (item.getValue().isDirectory())
			{
				removeEmptyDirectoriesandUpdateStatus(item);
				if (item.getChildren().isEmpty())
				{
					it.remove();
					removed = true;
				}
			}
			
			if (removed == false)
			{
				FileSystemElement.Status itemStatus = item.getValue().getStatus();
				if (updatedStatus == null)
					updatedStatus = itemStatus;
				else if (updatedStatus != itemStatus)
					updatedStatus = FileSystemElement.Status.UNSYNC;
			}
		}
		
		if (updatedStatus == null)
			updatedStatus = FileSystemElement.Status.SYNC;
		else if (updatedStatus == FileSystemElement.Status.DELETED
		        && Files.exists(gallery.toAbsolutePath(rootItem.getValue().getPath())))
			updatedStatus = FileSystemElement.Status.UNSYNC;
		
		rootItem.getValue().setStatus(updatedStatus);
	}
	
	private void sort(TreeItem<FileSystemElement> rootItem)
	{
		rootItem.getChildren()
		        .sort(Comparator.comparing(ti -> ti.getValue().getPath().getFileName().toString(),
		                                   Utils.NATURAL_ORDER));
		
		for (TreeItem<FileSystemElement> item : rootItem.getChildren())
			sort(item);
	}
	
	void syncFileSystemItem(TreeItem<FileSystemElement> rootItem)
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
	
	private static boolean isImage(Path file)
	{
		String filename = file.getFileName().toString();
		int posExt = filename.lastIndexOf('.');
		if (posExt == -1)
			return false;
		
		String extention = filename.substring(posExt + 1).toLowerCase();
		return extention.equals("jpg") || extention.equals("jpeg") || extention.equals("png")
		        || extention.equals("gif");
	}
	
	private List<Image> getImages(TreeItem<FileSystemElement> rootItem)
	{
		if (rootItem == null)
			return List.of();
		else if (!rootItem.getValue().isDirectory())
			return List.of(rootItem.getValue().getImage());
		else
			return rootItem.getChildren().stream().flatMap(item -> getImages(item).stream()).toList();
	}
	
	private void showThumbnails(Collection<Image> images)
	{
		assert images != null;
		
		Platform.runLater(() ->
		{
			thumbnailsView.getTiles().setAll(images.stream().map(image ->
			{
				GalleryImageView imageView = new GalleryImageView(image, Image::getThumbnail);
				
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
}
