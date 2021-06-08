package nigloo.gallerymanager.ui;

import java.io.IOError;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import nigloo.gallerymanager.model.Image;

public class FileSystemElement
{
	private static final javafx.scene.image.Image ICON_FOLDER_SYNC;
	private static final javafx.scene.image.Image ICON_FOLDER_UNSYNC;
	private static final javafx.scene.image.Image ICON_FOLDER_DELETED;
	private static final javafx.scene.image.Image ICON_FOLDER_NOT_LOADED;
	private static final javafx.scene.image.Image ICON_IMAGE_SYNC;
	private static final javafx.scene.image.Image ICON_IMAGE_UNSYNC;
	private static final javafx.scene.image.Image ICON_IMAGE_DELETED;
	static
	{
		ICON_FOLDER_SYNC = loadIcon("folder_sync.png");
		ICON_FOLDER_UNSYNC = loadIcon("folder_unsync.png");
		ICON_FOLDER_DELETED = loadIcon("folder_deleted.png");
		ICON_FOLDER_NOT_LOADED = loadIcon("folder_not_loaded.png");
		ICON_IMAGE_SYNC = loadIcon("image_sync.png");
		ICON_IMAGE_UNSYNC = loadIcon("image_unsync.png");
		ICON_IMAGE_DELETED = loadIcon("image_deleted.png");
	}
	
	static private javafx.scene.image.Image loadIcon(final String filename)
	{
		try
		{
			return new javafx.scene.image.Image(FileSystemElement.class.getModule()
			                                                           .getResourceAsStream("resources/images/icons/"
			                                                                   + filename));
		}
		catch (IOException e)
		{
			throw new IOError(e);
		}
	}
	
	public enum Status
	{
		NOT_LOADED, NOT_FULLY_LOADED, EMPTY, SYNC, UNSYNC, DELETED
	}
	
	private final Image image;
	private final Path path;
	private Status status;
	
	public FileSystemElement(Image image, Status status)
	{
		this.image = Objects.requireNonNull(image, "image");
		this.path = null;
		this.status = Objects.requireNonNull(status, "status");
	}
	
	public FileSystemElement(Path path)
	{
		this.image = null;
		this.path = Objects.requireNonNull(path, "path");
		this.status = Status.NOT_LOADED;
	}
	
	@Override
	public String toString()
	{
		return getPath().toString();
	}
	
	public Path getPath()
	{
		return image != null ? image.getPath() : path;
	}
	
	public Image getImage()
	{
		return image;
	}
	
	public boolean isDirectory()
	{
		return image == null;
	}
	
	public boolean isImage()
	{
		return image != null;
	}
	
	public javafx.scene.image.Image getIcon()
	{
		if (image != null)
		{
			switch (status)
			{
				case SYNC:
					return ICON_IMAGE_SYNC;
				case UNSYNC:
					return ICON_IMAGE_UNSYNC;
				case DELETED:
					return ICON_IMAGE_DELETED;
				case NOT_LOADED:
				case NOT_FULLY_LOADED:
				case EMPTY:
					throw new IllegalStateException("{image=" + image + ", status=" + status + "}");
			}
		}
		else
		{
			switch (status)
			{
				case SYNC:
					return ICON_FOLDER_SYNC;
				case UNSYNC:
					return ICON_FOLDER_UNSYNC;
				case DELETED:
					return ICON_FOLDER_DELETED;
				case NOT_LOADED:
				case NOT_FULLY_LOADED:
				case EMPTY:
					return ICON_FOLDER_NOT_LOADED;
			}
		}
		
		throw new IllegalStateException("status = " + status);
	}
	
	public Status getStatus()
	{
		return status;
	}
	
	public void setStatus(Status status)
	{
		this.status = Objects.requireNonNull(status, "status");
	}
}
