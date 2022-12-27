package nigloo.gallerymanager.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import nigloo.gallerymanager.model.Image;

public class FileSystemElement
{
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
