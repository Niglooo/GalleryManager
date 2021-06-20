package nigloo.gallerymanager.ui;

import java.io.IOError;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import nigloo.gallerymanager.model.Image;

public class FileSystemElement
{
	private static final javafx.scene.image.Image ICON_FOLDER;
	private static final javafx.scene.image.Image ICON_FOLDER_LOADING;
	private static final javafx.scene.image.Image ICON_FOLDER_SYNC;
	private static final javafx.scene.image.Image ICON_FOLDER_UNSYNC;
	private static final javafx.scene.image.Image ICON_FOLDER_DELETED;
	private static final javafx.scene.image.Image ICON_IMAGE_SYNC;
	private static final javafx.scene.image.Image ICON_IMAGE_UNSYNC;
	private static final javafx.scene.image.Image ICON_IMAGE_DELETED;
	static
	{
		ICON_FOLDER = loadIcon("folder.png");
		ICON_FOLDER_LOADING = loadIcon("folder_loading.png");
		ICON_FOLDER_SYNC = loadIcon("folder_sync.png");
		ICON_FOLDER_UNSYNC = loadIcon("folder_unsync.png");
		ICON_FOLDER_DELETED = loadIcon("folder_deleted.png");
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
		NOT_LOADED(false),
		LOADING(false),
		NOT_FULLY_LOADED(false),
		EMPTY(true),
		SYNC(true),
		UNSYNC(true),
		DELETED(true);
		
		private final boolean fullyLoaded;

		private Status(boolean fullyLoaded)
		{
			this.fullyLoaded = fullyLoaded;
		}

		public boolean isFullyLoaded()
		{
			return fullyLoaded;
		}
	}
	
	private final Image image;
	private final Path path;
	private Status status;
	
	public FileSystemElement(Image image, Status status)
	{
		this.image = Objects.requireNonNull(image, "image");
		this.path = null;
		this.status = Objects.requireNonNull(status, "status");
		if (status != Status.SYNC && status != Status.UNSYNC && status != Status.DELETED)
			throw new IllegalArgumentException("Invalid status. Must be one of " + Status.SYNC + ", " + Status.UNSYNC
			        + ", " + Status.DELETED + ". Got: " + status);
	}
	
	public FileSystemElement(Path path)
	{
		this.image = null;
		this.path = Objects.requireNonNull(path, "path");
		if (!path.isAbsolute())
			throw new IllegalArgumentException("path must be absolute. Got: " + path);
		this.status = Status.NOT_LOADED;
	}
	
	@Override
	public String toString()
	{
		return getPath().toString();
	}
	
	@Override
	public int hashCode()
	{
		return image != null ? image.hashCode() : path.hashCode();
	}
	
	@Override
	public boolean equals(Object obj)
	{
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		
		FileSystemElement other = (FileSystemElement) obj;
		return (image != null) ? image.equals(other.image) : path.equals(other.path);
	}
	
	public Path getPath()
	{
		return image != null ? image.getAbsolutePath() : path;
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
				case LOADING:
				case NOT_FULLY_LOADED:
				case EMPTY:
					throw new IllegalStateException("{image=" + image + ", status=" + status + "}");
			}
		}
		else
		{
			switch (status)
			{
				case LOADING:
					return ICON_FOLDER_LOADING;
				case SYNC:
					return ICON_FOLDER_SYNC;
				case UNSYNC:
					return ICON_FOLDER_UNSYNC;
				case DELETED:
					return ICON_FOLDER_DELETED;
				case NOT_LOADED:
				case NOT_FULLY_LOADED:
				case EMPTY:
					return ICON_FOLDER;
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
