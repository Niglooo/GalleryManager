package nigloo.gallerymanager.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.google.gson.GsonBuilder;

import javafx.application.Application;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.stage.Stage;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Image;
import nigloo.tool.gson.PathTypeAdapter;

public class UIController extends Application
{
	@FXML protected TreeView<FileSystemElement> fileSystemView;
	
	private Stage primaryStage;
	
	private static Path galleryFile;
	
	private Gallery gallery;
	
	public UIController() {
	}

	public static void main(String[] args) {
		if (args.length < 1)
			throw new RuntimeException("missing gallery file");
			
		galleryFile = Paths.get(args[0]);
		
		launch(args);
	}

	@Override
	public void start(Stage primaryStage) throws Exception {
		this.primaryStage = primaryStage;
		
		gallery = new GsonBuilder()
				.registerTypeAdapter(Path.class, new PathTypeAdapter())
				.disableHtmlEscaping()
				.setPrettyPrinting()
				.create()
				.fromJson(Files.newBufferedReader(galleryFile, StandardCharsets.UTF_8), Gallery.class);
		gallery.setRootFolder(galleryFile.getParent());
		
		FXMLLoader fxmlLoader = new FXMLLoader(StandardCharsets.UTF_8);
		fxmlLoader.setController(this);
		fxmlLoader.setRoot(primaryStage);
		fxmlLoader.load(getClass().getModule().getResourceAsStream("resources/fxml/ui.fxml"));
		
		fileSystemView.setCellFactory(new FileSystemTreeCellFactory(this, gallery));
		
		TreeItem<FileSystemElement> root = new TreeItem<FileSystemElement>(new FileSystemElement(gallery.getRootFolder()));
		root.setExpanded(true);
		fileSystemView.setRoot(root);
		
		refreshFileSystemItem(root);
		
		this.primaryStage.show();
	}
	
	
	void refreshFileSystemItem(TreeItem<FileSystemElement> rootItem)
	{
		System.out.println("Refresh : "+rootItem.getValue().getPath());
		try {
			FileSystemElement rootElement = rootItem.getValue();
			Path rootPath = rootElement.getPath();
			Path absoluteRoot = gallery.toAbsolutePath(rootPath);
			
			Map<Path, TreeItem<FileSystemElement>> pathToItem = new HashMap<>();
			pathToItem.put(absoluteRoot, rootItem);
			
			rootItem.getChildren().clear();
			
			// Add files on disk
			if (Files.exists(absoluteRoot))
			{
				Files.walkFileTree(absoluteRoot, new FileVisitor<Path>() {
		
					@Override
					public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
						if (dir.equals(absoluteRoot))
							return FileVisitResult.CONTINUE;
						
						TreeItem<FileSystemElement> parentItem = pathToItem.get(dir.getParent());
						TreeItem<FileSystemElement> item = new TreeItem<>(new FileSystemElement(gallery.toRelativePath(dir)));
						
						parentItem.getChildren().add(item);
						pathToItem.put(dir, item);
						
						return FileVisitResult.CONTINUE;
					}
		
					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
						if (!isImage(file))
							return FileVisitResult.CONTINUE;
						
						Path relativePath = gallery.toRelativePath(file);
						
						Image image = gallery.findImage(relativePath);
						boolean isSyncronized;
						if (image == null) {
							image = new Image(relativePath);
							isSyncronized = false;
						}
						else {
							isSyncronized = true;
						}
						
						if (file.equals(absoluteRoot))
							return FileVisitResult.CONTINUE;
						
						TreeItem<FileSystemElement> parentItem = pathToItem.get(file.getParent());
						TreeItem<FileSystemElement> item = new TreeItem<>(new FileSystemElement(image,
								isSyncronized ? FileSystemElement.Status.SYNC : FileSystemElement.Status.UNSYNC));
		
						parentItem.getChildren().add(item);
						pathToItem.put(file, item);
						return FileVisitResult.CONTINUE;
					}
		
					@Override
					public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
						throw exc;
					}
		
					@Override
					public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
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
				for (Path currentPath = absolutePath.getParent() ; pathToItem.containsKey(currentPath) == false ; currentPath = currentPath.getParent())
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
				
				TreeItem<FileSystemElement> parentItem = pathToItem.get(ancestors.get(0).getParent());
				if (parentItem.getValue().getStatus() == FileSystemElement.Status.SYNC)
					parentItem.getValue().setStatus(FileSystemElement.Status.UNSYNC);
				
				parentItem = ancestors.isEmpty() ? parentItem : pathToItem.get(ancestors.get(ancestors.size()-1));
				TreeItem<FileSystemElement> item = new TreeItem<>(new FileSystemElement(image, FileSystemElement.Status.DELETED));
				
				parentItem.getChildren().add(item);
			}
			
			removeEmptyDirectoriesandUpdateStatus(rootItem);
		}
		catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	private void removeEmptyDirectoriesandUpdateStatus(TreeItem<FileSystemElement> rootItem)
	{
		if (rootItem.getValue().isDirectory() == false)
			return;
		
		Iterator<TreeItem<FileSystemElement>> it = rootItem.getChildren().iterator();
		FileSystemElement.Status updatedStatus = null;
		
		while (it.hasNext()) {
			TreeItem<FileSystemElement> item = it.next();
			boolean removed = false;
			if (item.getValue().isDirectory())
			{
				removeEmptyDirectoriesandUpdateStatus(item);
				if (item.getChildren().isEmpty()) {
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
		else if (updatedStatus == FileSystemElement.Status.DELETED && Files.exists(gallery.toAbsolutePath(rootItem.getValue().getPath())))
			updatedStatus = FileSystemElement.Status.UNSYNC;
		
		rootItem.getValue().setStatus(updatedStatus);
	}
	
	void saveFileSystemItem(TreeItem<FileSystemElement> rootItem)
	{
		System.out.println("Save : "+rootItem.getValue().getPath());
		try {
			if (false)throw new IOException();
			
		}
		catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	private static boolean isImage(Path file)
	{
		String filename = file.getFileName().toString();
		int posExt = filename.lastIndexOf('.');
		if (posExt == -1)
			return false;
		
		String extention = filename.substring(posExt+1).toLowerCase();
		return extention.equals("jpg") ||  extention.equals("jpeg") || extention.equals("png");
	}
}
