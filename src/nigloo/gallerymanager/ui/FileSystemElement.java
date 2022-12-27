package nigloo.gallerymanager.ui;

import java.io.IOError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import nigloo.gallerymanager.model.Image;

public class FileSystemElement
{
	private static final javafx.scene.image.Image ICON_FOLDER;
	private static final javafx.scene.image.Image ICON_FOLDER_LOADING;
	private static final javafx.scene.image.Image ICON_FOLDER_EMPTY;
	private static final javafx.scene.image.Image ICON_FOLDER_SYNC;
	private static final javafx.scene.image.Image ICON_FOLDER_UNSYNC;
	private static final javafx.scene.image.Image ICON_FOLDER_DELETED;
	private static final javafx.scene.image.Image ICON_IMAGE_SYNC;
	private static final javafx.scene.image.Image ICON_IMAGE_UNSYNC;
	private static final javafx.scene.image.Image ICON_IMAGE_DELETED;
	//TODO use FontIcon
	static
	{
		ICON_FOLDER = loadIcon("folder.png");
		ICON_FOLDER_LOADING = loadIcon("folder_loading.png");
		ICON_FOLDER_EMPTY = loadIcon("folder_empty.png");
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
		
		public boolean isNotFullyLoaded()
		{
			return !fullyLoaded;
		}
	}
	
	private final Image image;
	private final Path path;
	private final Status status;
	private long lastModified = -1;
	
	public FileSystemElement(Image image, Status status)
	{
		this.image = Objects.requireNonNull(image, "image");
		this.path = null;
		this.status = Objects.requireNonNull(status, "status");
		if (status != Status.SYNC && status != Status.UNSYNC && status != Status.DELETED)
			throw new IllegalArgumentException("Invalid status. Must be one of " + Status.SYNC + ", " + Status.UNSYNC
			        + ", " + Status.DELETED + ". Got: " + status);
	}
	
	public FileSystemElement(Path path, Status status)
	{
		this.image = null;
		this.path = Objects.requireNonNull(path, "path");
		if (!path.isAbsolute())
			throw new IllegalArgumentException("path must be absolute. Got: " + path);
		this.status = Objects.requireNonNull(status, "status");
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
	
	/**
	 * @return The absolute path of the element
	 */
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
			return switch (status)
			{
				case SYNC -> ICON_IMAGE_SYNC;
				case UNSYNC -> ICON_IMAGE_UNSYNC;
				case DELETED -> ICON_IMAGE_DELETED;
				case NOT_LOADED, LOADING, NOT_FULLY_LOADED, EMPTY -> throw new IllegalStateException("{image=" + image
				        + ", status=" + status + "}");
			};
		}
		else
		{
			return switch (status)
			{
				case LOADING -> ICON_FOLDER_LOADING;
				case EMPTY -> ICON_FOLDER_EMPTY;
				case SYNC -> ICON_FOLDER_SYNC;
				case UNSYNC -> ICON_FOLDER_UNSYNC;
				case DELETED -> ICON_FOLDER_DELETED;
				case NOT_LOADED, NOT_FULLY_LOADED -> ICON_FOLDER;
			};
		}
	}
	
	public Status getStatus()
	{
		return status;
	}
	
	public FileSystemElement withStatus(Status status)
	{
		FileSystemElement other;
		if (image != null)
			other = new FileSystemElement(image, status);
		else
			other =new FileSystemElement(path, status);
		
		other.lastModified = lastModified;
		
		return other;
	}
	
	public long getLastModified()
	{
		if (lastModified == -1)
		{
			try
			{
				lastModified = Files.getLastModifiedTime(getPath()).toMillis();
			}
			catch (IOException e)
			{
				lastModified = 0;
			}
		}
		
		return lastModified;
	}
}
